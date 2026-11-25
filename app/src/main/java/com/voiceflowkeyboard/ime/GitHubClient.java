package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The word lists in a private GitHub repository (docs/GITHUB-SYNC.md), read only:
 * the phone never writes to the repository.
 *
 * <ul>
 *   <li>The index is one {@code GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1}
 *       with the last ETag: an unchanged branch answers a bodiless 304, which GitHub
 *       does not count against the rate limit. It becomes a {@code vf-sync-manifest}
 *       whose layer revisions are git blob ids ({@code git:<id>}).</li>
 *   <li>Files are read by blob id ({@code /git/blobs/{id}}, raw), or by path when the
 *       index came from a 304 and no listing is in memory.</li>
 *   <li>A truncated listing is refused, never read in part. When GitHub asks to wait,
 *       no request is made until then ({@link SyncStore#gitHubNotBefore}).</li>
 *   <li>The token goes in the Authorization header only, to {@code api.github.com}
 *       over HTTPS, with redirects refused. It never appears in a URL, a log or an
 *       exception message.</li>
 * </ul>
 */
final class GitHubClient implements WordSource {
    static final String API = "https://api.github.com";
    /** Where requests go. Fixed in the app; a unit test points it at a local fake. */
    static volatile String api = API;
    static final int MAX_TREE_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** A rate limit that says no time: try again after this long. */
    static final long RATE_LIMIT_FALLBACK_MS = 60_000L;

    static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    static final Pattern BRANCH_PART = Pattern.compile("[A-Za-z0-9._-]+");
    static final Pattern TOKEN = Pattern.compile("(github_pat_[A-Za-z0-9_]{20,255}|ghp_[A-Za-z0-9_]{20,255})");
    private static final Pattern OP_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}\\.json$");

    /** Where the rate limit's wait is kept, read and written by the caller. */
    interface Wait {
        long notBefore();

        void setNotBefore(long millis);
    }

    final String owner;
    final String repo;
    final String branch;
    private final String user;
    private final String device;
    private final String token;
    private final Wait wait;
    /** Path to blob id, from the last listing read in this run. */
    private final Map<String, String> blobs = new HashMap<>();

    GitHubClient(String owner, String repo, String branch, String user, String device, String token, Wait wait) {
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
        this.user = user;
        this.device = device;
        this.token = token;
        this.wait = wait;
    }

    // ---------------------------------------------------------------- names

    /** "owner/name" (a pasted github.com link or a trailing .git is accepted), or null. */
    static String[] parseRepo(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        for (String prefix : new String[]{"https://github.com/", "http://github.com/", "github.com/"}) {
            if (v.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                v = v.substring(prefix.length());
            }
        }
        if (v.endsWith(".git")) {
            v = v.substring(0, v.length() - 4);
        }
        String[] parts = v.split("/", -1);
        if (parts.length != 2 || !OWNER.matcher(parts[0]).matches() || !NAME.matcher(parts[1]).matches()
                || parts[1].equals(".") || parts[1].equals("..")) {
            return null;
        }
        return parts;
    }

    static boolean validBranch(String branch) {
        if (branch == null || branch.isEmpty() || branch.length() > 200 || branch.contains("..")) {
            return false;
        }
        for (String part : branch.split("/", -1)) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".lock") || !BRANCH_PART.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** The offline copy's key part: owner and name ignore case on GitHub, a branch doesn't. */
    static String identity(String owner, String repo, String branch) {
        return "github:" + owner.toLowerCase(Locale.ROOT) + "/" + repo.toLowerCase(Locale.ROOT) + "@" + branch;
    }

    // ---------------------------------------------------------------- WordSource

    @Override
    public GatewayClient.Fetched manifest(String etag) throws IOException {
        Listing listing = listing(etag);
        if (listing.notModified) {
            return new GatewayClient.Fetched(true, null, etag);
        }
        JSONObject manifest = new JSONObject();
        try {
            JSONObject layers = new JSONObject();
            for (String layer : WordLayer.FILE_LAYERS) {
                String path = layerPath(layer);
                String sha = listing.blobs.get(path);
                layers.put(layer, sha == null ? JSONObject.NULL
                        : new JSONObject().put("revision", "git:" + sha).put("size", listing.sizes.get(path)));
            }
            List<String> ops = new ArrayList<>();
            String prefix = "profiles/" + user + "/ops/";
            for (String path : listing.blobs.keySet()) {
                if (path.startsWith(prefix)) {
                    String name = path.substring(prefix.length());
                    if (OP_NAME.matcher(name).matches()) {
                        ops.add(name);
                    }
                }
            }
            Collections.sort(ops);
            manifest.put("schema", "vf-sync-manifest").put("version", 1).put("user", user).put("device", device)
                    .put("layers", layers).put("ops", new JSONArray(ops));
        } catch (JSONException e) {
            throw new GatewayClient.GatewayException(200, UpdateEvidence.ERROR_VALIDATION, "github-index");
        }
        return new GatewayClient.Fetched(false, manifest.toString().getBytes(StandardCharsets.UTF_8), listing.etag);
    }

    @Override
    public GatewayClient.Fetched layer(String layer) throws IOException {
        if (!WordLayer.FILE_LAYERS.contains(layer)) {
            throw new IllegalArgumentException("not a layer");
        }
        return file(layerPath(layer), WordLayer.MAX_LAYER_BYTES);
    }

    @Override
    public GatewayClient.Fetched op(String name) throws IOException {
        if (!OP_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not an edit name");
        }
        return file("profiles/" + user + "/ops/" + name, WordOp.MAX_OP_BYTES);
    }

    @Override
    public String revisionOf(byte[] bytes) {
        return "git:" + blobId(bytes);
    }

    /** Git's id of a file: SHA-1 of "blob <length>\0" and the bytes. */
    static String blobId(byte[] bytes) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return GatewayClient.hex(sha1.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("no sha-1");
        }
    }

    private String layerPath(String layer) {
        switch (layer) {
            case WordLayer.HOUSEHOLD:
                return "profiles/household/profile.json";
            case WordLayer.GENERATED:
                return "profiles/" + user + "/generated/profile.json";
            default:
                return "profiles/" + user + "/profile.json";
        }
    }

    private GatewayClient.Fetched file(String path, int max) throws IOException {
        String sha = blobs.get(path);
        String url = sha != null
                ? base() + "/git/blobs/" + enc(sha)
                : base() + "/contents/" + encPath(path) + "?ref=" + enc(branch);
        Response response = get(url, "application/vnd.github.raw+json", null, max);
        if (response.status != 200) {
            throw refused(response);
        }
        return new GatewayClient.Fetched(false, response.body, "");
    }

    // ---------------------------------------------------------------- the listing

    static final class Listing {
        final boolean notModified;
        final String etag;
        final Map<String, String> blobs;
        final Map<String, Long> sizes;
        final List<String> users;

        Listing(boolean notModified, String etag, Map<String, String> blobs, Map<String, Long> sizes, List<String> users) {
            this.notModified = notModified;
            this.etag = etag;
            this.blobs = blobs;
            this.sizes = sizes;
            this.users = users;
        }
    }

    /** The branch's files; a refusal for a truncated listing or one without {@code profiles/}. */
    Listing listing(String etag) throws IOException {
        Response response = get(base() + "/git/trees/" + enc(branch) + "?recursive=1", "application/vnd.github+json",
                etag, MAX_TREE_BYTES);
        if (response.status == 304 && etag != null && !etag.isEmpty()) {
            return new Listing(true, etag, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
        }
        if (response.status != 200) {
            throw refused(response);
        }
        JSONObject tree;
        try {
            tree = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new GatewayClient.GatewayException(200, UpdateEvidence.ERROR_VALIDATION, "github-listing-unreadable");
        }
        if (tree.optBoolean("truncated", false)) {
            throw new GatewayClient.GatewayException(200, UpdateEvidence.ERROR_VALIDATION, "github-too-many-files");
        }
        JSONArray entries = tree.optJSONArray("tree");
        Map<String, String> found = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        List<String> users = new ArrayList<>();
        boolean profiles = false;
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String path = entry.optString("path", "");
            String type = entry.optString("type", "");
            if (path.startsWith("profiles/")) {
                profiles = true;
            }
            if ("tree".equals(type) && path.startsWith("profiles/") && path.indexOf('/', 9) < 0) {
                String name = path.substring(9);
                if (!"household".equals(name) && Enrolment.validUser(name)) {
                    users.add(name);
                }
            }
            String sha = entry.optString("sha", "");
            if ("blob".equals(type) && sha.matches("[0-9a-f]{40}") && wanted(path)) {
                found.put(path, sha);
                sizes.put(path, entry.optLong("size", 0L));
            }
        }
        if (!profiles) {
            throw new GatewayClient.GatewayException(200, UpdateEvidence.ERROR_VALIDATION, "github-no-profiles");
        }
        Collections.sort(users);
        blobs.clear();
        blobs.putAll(found);
        String served = response.etag;
        return new Listing(false, served != null && GatewayClient.validEtag(served) ? served : "", found, sizes, users);
    }

    private boolean wanted(String path) {
        if (path.equals("profiles/household/profile.json")
                || path.equals("profiles/" + user + "/profile.json")
                || path.equals("profiles/" + user + "/generated/profile.json")) {
            return true;
        }
        String ops = "profiles/" + user + "/ops/";
        return path.startsWith(ops) && path.indexOf('/', ops.length()) < 0;
    }

    // ---------------------------------------------------------------- Test connection

    /** What Test connection found. Reads only. */
    static final class TestResult {
        boolean ok;
        String message = "";
        Boolean isPrivate;
        String branch = "";
        List<String> users = new ArrayList<>();
        boolean userFound;
        final List<String> warnings = new ArrayList<>();
    }

    /** Reads the repository, its branch and the user's layers; writes nothing. */
    TestResult test() {
        TestResult result = new TestResult();
        result.branch = branch;
        try {
            Response meta = get(base(), "application/vnd.github+json", null, 256 * 1024);
            if (meta.status != 200) {
                throw refused(meta);
            }
            JSONObject json = new JSONObject(new String(meta.body, StandardCharsets.UTF_8));
            result.isPrivate = json.has("private") ? json.optBoolean("private") : null;
            if (Boolean.FALSE.equals(result.isPrivate)) {
                result.warnings.add("This repository is public: anyone can read its word lists. Make it private on GitHub.");
            }
            Listing listing = listing(null);
            result.users = listing.users;
            result.userFound = listing.users.contains(user);
            if (!result.userFound) {
                result.warnings.add("There is no profiles/" + user + "/ folder yet. Check the name, or add your words there.");
            }
            for (String layer : WordLayer.FILE_LAYERS) {
                if (!listing.blobs.containsKey(layerPath(layer))) {
                    continue;
                }
                try {
                    WordLayer.parse(layer(layer).body, layer, user);
                } catch (WordLayer.Rejected rejected) {
                    result.warnings.add(layerPath(layer) + " can't be used (" + rejected.reason + ").");
                }
            }
            result.ok = true;
            result.message = "Connected to " + owner + "/" + repo + " (" + branch + "). Reading works.";
        } catch (GatewayClient.GatewayException e) {
            result.message = describe(e.code);
        } catch (IOException e) {
            result.message = "Can't reach GitHub. Check the internet connection.";
        } catch (JSONException | RuntimeException e) {
            result.message = "GitHub's answer couldn't be read.";
        }
        return result;
    }

    /** A refusal's code, in words (for the screen). */
    static String describe(String code) {
        switch (code) {
            case "github-401":
                return "GitHub didn't accept the token. Check it was copied whole and hasn't expired.";
            case "github-403":
                return "GitHub refused this token access to the repository.";
            case "github-404":
                return "GitHub can't find the repository or branch with this token. Check the names, and that the token was made for this repository.";
            case "github-wait":
                return "GitHub asked VoiceFlow to wait; try again later.";
            case "github-too-many-files":
                return "The repository has too many files for GitHub to list at once; keep only the word lists in it.";
            case "github-no-profiles":
                return "The repository has no profiles folder on this branch yet. Add the template files.";
            default:
                return "GitHub refused the request (" + code + ").";
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Response {
        final int status;
        final byte[] body;
        final String etag;

        Response(int status, byte[] body, String etag) {
            this.status = status;
            this.body = body;
            this.etag = etag;
        }
    }

    private String base() {
        return api + "/repos/" + enc(owner) + "/" + enc(repo);
    }

    private Response get(String url, String accept, String etag, int max) throws IOException {
        long now = System.currentTimeMillis();
        if (wait != null && wait.notBefore() > now) {
            throw new GatewayClient.GatewayException(0, "", "github-wait");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "VoiceFlow-Android");
        if (etag != null && !etag.isEmpty() && GatewayClient.validEtag(etag)) {
            connection.setRequestProperty("If-None-Match", etag);
        }
        try {
            int code = connection.getResponseCode();
            String served = connection.getHeaderField("ETag");
            if (code == 304) {
                return new Response(304, new byte[0], served);
            }
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = new byte[0];
            if (in != null) {
                try (InputStream stream = in) {
                    body = GatewayClient.readCapped(stream, code >= 400 ? GatewayClient.MAX_JSON_BYTES : max);
                } catch (IOException e) {
                    // A file larger than the app reads (GitHub serves any size): refused like
                    // the gateway's 413, so it is skipped and never stops the files after it.
                    if (code == 200 && "response-too-large".equals(e.getMessage())) {
                        return new Response(413, new byte[0], served);
                    }
                    throw e;
                }
            }
            if (code == 429 || code == 403) {
                long until = rateLimitUntil(code, connection.getHeaderField("Retry-After"),
                        connection.getHeaderField("X-RateLimit-Remaining"), connection.getHeaderField("X-RateLimit-Reset"),
                        new String(body, StandardCharsets.UTF_8), now);
                if (until > 0) {
                    if (wait != null) {
                        wait.setNotBefore(Math.max(wait.notBefore(), until));
                    }
                    throw new GatewayClient.GatewayException(code, "", "github-wait");
                }
            }
            return new Response(code, body, served);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * When a refused answer is a rate limit: the time to wait until (every valid time
     * GitHub gives is honoured), or 0 when it isn't one.
     */
    static long rateLimitUntil(int status, String retryAfter, String remaining, String reset, String body, long now) {
        boolean remainingZero = "0".equals(remaining == null ? null : remaining.trim());
        boolean saysRate = body != null && body.toLowerCase(Locale.ROOT).contains("rate limit");
        boolean limited = status == 429 || (status == 403 && (remainingZero || retryAfter != null || saysRate));
        if (!limited) {
            return 0;
        }
        long until = 0;
        try {
            long seconds = Long.parseLong(retryAfter == null ? "" : retryAfter.trim());
            if (seconds >= 0) {
                until = now + seconds * 1000L;
            }
        } catch (NumberFormatException ignored) {
            // A date or nothing: the reset or the fallback below.
        }
        if (remainingZero) {
            try {
                long resetMs = Long.parseLong(reset == null ? "" : reset.trim()) * 1000L;
                if (resetMs > now) {
                    until = Math.max(until, resetMs);
                }
            } catch (NumberFormatException ignored) {
                // No reset given.
            }
        }
        return until > 0 ? until : now + RATE_LIMIT_FALLBACK_MS;
    }

    private static GatewayClient.GatewayException refused(Response response) {
        return new GatewayClient.GatewayException(response.status, GatewayClient.errorKind(response.status),
                "github-" + response.status);
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encPath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(enc(part));
        }
        return out.toString();
    }
}
