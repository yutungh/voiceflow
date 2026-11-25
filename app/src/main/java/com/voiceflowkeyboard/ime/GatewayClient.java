package com.voiceflowkeyboard.ime;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The phone's side of the vf-sync gateway (sync/README.md): fixed routes on the
 * enrolled HTTPS origin, the token in the Authorization header only, no redirects
 * followed, and size caps on everything read. The user and device always come
 * from the token on the server; no route here names them.
 */
final class GatewayClient implements WordSource {
    static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** A refused request. {@link #kind} is the evidence category, or "" for a transient failure. */
    static final class GatewayException extends IOException {
        final int status;
        final String kind;
        final String code;

        GatewayException(int status, String kind, String code) {
            super("gateway " + code);
            this.status = status;
            this.kind = kind;
            this.code = code;
        }
    }

    private final String origin;
    private final String token;

    GatewayClient(String origin, String token) {
        this.origin = origin;
        this.token = token;
    }

    /** Which evidence category an HTTP status counts as; "" for none (network, busy, server). */
    static String errorKind(int status) {
        if (status == 401 || status == 403) {
            return UpdateEvidence.ERROR_AUTH;
        }
        if (status == 400 || status == 409 || status == 413 || status == 422) {
            return UpdateEvidence.ERROR_VALIDATION;
        }
        return "";
    }

    void putStatus(JSONObject status) throws IOException {
        HttpURLConnection connection = open("/v1/status", "PUT");
        byte[] body = status.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            drain(connection, code);
            if (code != 200 && code != 201 && code != 204) {
                throw refused(code);
            }
        } finally {
            connection.disconnect();
        }
    }

    JSONObject releaseOffer() throws IOException {
        return releaseOffer(null).body;
    }

    /** The release answer, or "not modified" when {@code etag} still matches (an empty 304). */
    static final class Release {
        final boolean notModified;
        final JSONObject body;
        /** The server's ETag for this answer, or "" when it sent none. */
        final String etag;

        Release(boolean notModified, JSONObject body, String etag) {
            this.notModified = notModified;
            this.body = body;
            this.etag = etag;
        }
    }

    /**
     * One cheap request: with the ETag of the last answer, an unchanged release
     * costs a bodiless 304. A server that sends no ETag simply answers in full.
     */
    Release releaseOffer(String etag) throws IOException {
        HttpURLConnection connection = open("/v1/release/android", "GET");
        if (etag != null && !etag.isEmpty() && validEtag(etag)) {
            connection.setRequestProperty("If-None-Match", etag);
        }
        try {
            int code = connection.getResponseCode();
            if (code == 304 && etag != null && !etag.isEmpty()) {
                drain(connection, code);
                return new Release(true, null, etag);
            }
            if (code != 200) {
                drain(connection, code);
                throw refused(code);
            }
            String served = connection.getHeaderField("ETag");
            byte[] bytes = readCapped(connection.getInputStream(), MAX_JSON_BYTES);
            try {
                return new Release(false, new JSONObject(new String(bytes, StandardCharsets.UTF_8)),
                        served != null && validEtag(served) ? served : "");
            } catch (Exception e) {
                throw new GatewayException(code, UpdateEvidence.ERROR_VALIDATION, "release-unreadable");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** A word-list file: its bytes, or "not modified" (an empty 304) for the ETag sent. */
    static final class Fetched {
        final boolean notModified;
        final byte[] body;
        final String etag;

        Fetched(boolean notModified, byte[] body, String etag) {
            this.notModified = notModified;
            this.body = body;
            this.etag = etag;
        }
    }

    static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;

    /** The manifest of this device's user: layer hashes and edit names. */
    @Override
    public Fetched manifest(String etag) throws IOException {
        return fetch("/v1/manifest", etag, MAX_MANIFEST_BYTES);
    }

    /** One layer file: "household", "generated" or "personal". */
    @Override
    public Fetched layer(String layer) throws IOException {
        if (!WordLayer.FILE_LAYERS.contains(layer)) {
            throw new IllegalArgumentException("not a layer");
        }
        return fetch("/v1/layer/" + layer, null, WordLayer.MAX_LAYER_BYTES);
    }

    /** One edit file, by a name the manifest listed (checked again here). */
    @Override
    public Fetched op(String name) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9._-]{0,99}\\.json")) {
            throw new IllegalArgumentException("not an edit name");
        }
        return fetch("/v1/ops/" + name, null, WordOp.MAX_OP_BYTES);
    }

    private Fetched fetch(String path, String etag, int max) throws IOException {
        HttpURLConnection connection = open(path, "GET");
        if (etag != null && !etag.isEmpty() && validEtag(etag)) {
            connection.setRequestProperty("If-None-Match", etag);
        }
        try {
            int code = connection.getResponseCode();
            if (code == 304 && etag != null && !etag.isEmpty()) {
                drain(connection, code);
                return new Fetched(true, null, etag);
            }
            if (code != 200) {
                drain(connection, code);
                throw refused(code);
            }
            String served = connection.getHeaderField("ETag");
            byte[] bytes = readCapped(connection.getInputStream(), max);
            return new Fetched(false, bytes, served != null && validEtag(served) ? served : "");
        } finally {
            connection.disconnect();
        }
    }

    /** The gateway names a layer by the SHA-256 of its bytes. */
    @Override
    public String revisionOf(byte[] bytes) {
        return "sha256:" + WordLists.sha256(bytes);
    }

    /** A short quoted tag (optionally weak), nothing that could break a header. */
    static boolean validEtag(String etag) {
        return etag.length() <= 100 && etag.matches("(W/)?\"[\\x21\\x23-\\x7e]*\"");
    }

    /**
     * Downloads the offered APK to {@code target}, never more than the offered size,
     * and returns its SHA-256 (lower-case hex).
     */
    String downloadApk(UpdatePolicy.Offer offer, File target) throws IOException {
        HttpURLConnection connection = open("/v1/release/android/apk/" + offer.versionCode, "GET");
        connection.setReadTimeout(60_000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                drain(connection, code);
                throw refused(code);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    written += read;
                    if (written > offer.size) {
                        throw new GatewayException(code, UpdateEvidence.ERROR_VALIDATION, "apk-too-large");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.getFD().sync();
            }
            if (written != offer.size) {
                throw new IOException("apk-short");
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no-sha256");
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static GatewayException refused(int code) {
        return new GatewayException(code, errorKind(code), "http-" + code);
    }

    /** Reads and discards a small error body so the connection can be reused. Never logged. */
    private static void drain(HttpURLConnection connection, int code) {
        try {
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    readCapped(stream, MAX_JSON_BYTES);
                }
            }
        } catch (IOException ignored) {
            // Nothing in the body is used.
        }
    }

    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > max) {
                throw new IOException("response-too-large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
