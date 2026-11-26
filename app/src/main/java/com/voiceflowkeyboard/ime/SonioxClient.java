package com.voiceflowkeyboard.ime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 0.9.3: Soniox speech-to-text, async file flow ({@value #MODEL}).
 *
 * <ol>
 *   <li>{@code POST /v1/files} (multipart, field {@code file}) gives a file id;</li>
 *   <li>{@code POST /v1/transcriptions} with {@code model}, {@code file_id},
 *       {@code language_hints} and {@code context.terms} gives a transcription id;</li>
 *   <li>{@code GET /v1/transcriptions/{id}} is polled until {@code completed}
 *       ({@code error} or {@code failed} end it), bounded by {@link #TIMEOUT_MS} and the
 *       dictation's {@link SpeechCancel};</li>
 *   <li>{@code GET /v1/transcriptions/{id}/transcript} gives {@code text}.</li>
 * </ol>
 *
 * The transcription and the uploaded file are deleted every time, in a finally block;
 * whatever can't be deleted yet (a transcription still running when the dictation was
 * cancelled answers 409) is kept by id in {@link SonioxCleanup} and retried later.
 * Soniox otherwise keeps async data for up to 30 days. The key travels only in the
 * Authorization header, and no error text ever includes it.
 */
final class SonioxClient {
    static final String MODEL = "stt-async-v6";
    static final String API = "https://api.soniox.com/v1";
    /** From upload to text; the poll stops here and the dictation fails (or falls back). */
    static final long TIMEOUT_MS = 60_000L;
    /**
     * Soniox caps the whole context at about 8,000 tokens (about 10,000 characters) and
     * refuses a request over it. The terms stay far below that.
     */
    static final int MAX_TERMS = 150;
    static final int MAX_TERMS_CHARS = 3000;
    static final int MAX_TERM_CHARS = 100;

    /** One HTTP exchange. The real one is {@link #HTTP}; tests use a fake. */
    interface Transport {
        Reply send(String method, String url, String apiKey, Body body, SpeechCancel cancel) throws IOException;
    }

    /** A request body: its content type and how to write it. */
    interface Body {
        String contentType();

        void writeTo(OutputStream out) throws IOException;
    }

    static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Waits between polls; tests replace it so they run instantly. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    interface Clock {
        long now();
    }

    private SonioxClient() {
    }

    static String transcribe(Context context, File audioFile, DictationLanguage language,
                             WordLists.DictationWords words, SpeechCancel cancel) throws Exception {
        String apiKey = Prefs.sonioxApiKey(context).trim();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("Add a Soniox API key in Settings.");
        }
        SonioxCleanup store = SonioxCleanup.of(context);
        // Leftovers from an earlier dictation (cancelled mid-run, or offline at the time).
        store.retry(HTTP, apiKey);
        return transcribe(HTTP, apiKey, audioFile, "voiceflow-keyboard.m4a", "audio/mp4", language,
                words == null ? Collections.<String>emptyList() : termsFor(words), cancel, store,
                Thread::sleep, System::currentTimeMillis, TIMEOUT_MS);
    }

    /** The whole flow, with every dependency passed in (tests). */
    static String transcribe(Transport http, String apiKey, File audio, String fileName, String mime,
                             DictationLanguage language, List<String> terms, SpeechCancel cancel,
                             SonioxCleanup cleanup, Sleeper sleeper, Clock clock, long timeoutMs) throws Exception {
        SpeechCancel token = cancel == null ? new SpeechCancel() : cancel;
        long deadline = clock.now() + timeoutMs;
        String fileId = null;
        String transcriptionId = null;
        try {
            token.throwIfCancelled();
            Reply uploaded = http.send("POST", API + "/files", apiKey, multipart(audio, fileName, mime), token);
            check(uploaded, "Soniox upload");
            fileId = requiredId(uploaded.body, "upload");
            token.throwIfCancelled();
            Reply created = http.send("POST", API + "/transcriptions", apiKey,
                    json(transcriptionRequest(fileId, language, terms)), token);
            check(created, "Soniox transcription");
            transcriptionId = requiredId(created.body, "transcription");
            int poll = 0;
            while (true) {
                long wait = pollDelay(poll++);
                if (clock.now() + wait > deadline) {
                    throw new IOException("Soniox took too long (over " + (timeoutMs / 1000) + " s).");
                }
                sleepCancellable(sleeper, wait, token);
                Reply status = http.send("GET", API + "/transcriptions/" + transcriptionId, apiKey, null, token);
                check(status, "Soniox status");
                JSONObject json = new JSONObject(status.body);
                String state = json.optString("status", "");
                if ("completed".equals(state)) {
                    break;
                }
                if ("error".equals(state) || "failed".equals(state)) {
                    String message = json.optString("error_message", "");
                    throw new ProviderHttpException(Prefs.PROVIDER_SONIOX,
                            "Soniox transcription failed: " + (message.isEmpty() ? state : message),
                            0, json.optString("error_type", ""), "", message, "");
                }
            }
            token.throwIfCancelled();
            Reply transcript = http.send("GET", API + "/transcriptions/" + transcriptionId + "/transcript",
                    apiKey, null, token);
            check(transcript, "Soniox transcript");
            return parseTranscript(transcript.body);
        } finally {
            if (cleanup != null) {
                cleanup.delete(http, apiKey, transcriptionId, fileId, sleeper);
            }
        }
    }

    /** The create-transcription body. Pure, for the tests. */
    static JSONObject transcriptionRequest(String fileId, DictationLanguage language, List<String> terms)
            throws JSONException {
        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("file_id", fileId);
        String[] hints = languageHints(language);
        if (hints.length > 0) {
            JSONArray array = new JSONArray();
            for (String hint : hints) {
                array.put(hint);
            }
            body.put("language_hints", array);
        }
        List<String> capped = capTerms(terms);
        if (!capped.isEmpty()) {
            JSONArray array = new JSONArray();
            for (String term : capped) {
                array.put(term);
            }
            body.put("context", new JSONObject().put("terms", array));
        }
        return body;
    }

    /**
     * Chinese mode: {@code zh} and {@code en}, so dropped-in English words stay English.
     * The English keyboard: {@code en}. Hints are never strict (this client never sends
     * {@code language_hints_strict}), so spoken Chinese on the English keyboard is still
     * transcribed as Chinese, as it is with the other providers.
     */
    static String[] languageHints(DictationLanguage language) {
        return language != null && language.isChinese() ? new String[]{"zh", "en"} : new String[]{"en"};
    }

    /** The words this dictation's request carries: every word the word lists chose. */
    static List<String> termsFor(WordLists.DictationWords words) {
        return words.contextTerms.isEmpty() ? words.keywords : words.contextTerms;
    }

    /** Distinct, one line each, at most {@link #MAX_TERMS} and {@link #MAX_TERMS_CHARS} in all. */
    static List<String> capTerms(List<String> terms) {
        List<String> out = new ArrayList<>();
        if (terms == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        int chars = 0;
        for (String raw : terms) {
            String term = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (term.isEmpty() || term.length() > MAX_TERM_CHARS || !seen.add(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (out.size() >= MAX_TERMS || chars + term.length() > MAX_TERMS_CHARS) {
                break;
            }
            out.add(term);
            chars += term.length();
        }
        return out;
    }

    static String parseTranscript(String body) throws IOException {
        try {
            String text = new JSONObject(body).optString("text", "").trim();
            if (!text.isEmpty()) {
                return text;
            }
        } catch (JSONException ignored) {
            // falls through
        }
        throw new IOException("Soniox response did not include text.");
    }

    /** A quarter second for the first two seconds, then half a second, then one. */
    static long pollDelay(int poll) {
        if (poll < 8) {
            return 250L;
        }
        return poll < 48 ? 500L : 1000L;
    }

    private static void sleepCancellable(Sleeper sleeper, long millis, SpeechCancel token) throws Exception {
        long left = millis;
        while (left > 0) {
            token.throwIfCancelled();
            long step = Math.min(50L, left);
            sleeper.sleep(step);
            left -= step;
        }
        token.throwIfCancelled();
    }

    private static void check(Reply reply, String label) throws ProviderHttpException {
        if (!reply.ok()) {
            throw ProviderHttpException.fromResponse(Prefs.PROVIDER_SONIOX, label, reply.status, reply.body);
        }
    }

    private static String requiredId(String body, String what) throws IOException {
        try {
            String id = new JSONObject(body).optString("id", "").trim();
            if (!id.isEmpty()) {
                return id;
            }
        } catch (JSONException ignored) {
            // falls through
        }
        throw new IOException("Soniox " + what + " response had no id.");
    }

    static Body json(JSONObject json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        return new Body() {
            @Override
            public String contentType() {
                return "application/json";
            }

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(bytes);
            }
        };
    }

    static Body multipart(File audio, String fileName, String mime) {
        String boundary = "VoiceFlowKeyboardBoundary" + System.nanoTime();
        return new Body() {
            @Override
            public String contentType() {
                return "multipart/form-data; boundary=" + boundary;
            }

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(audio)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                    }
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /** HttpURLConnection; a cancel disconnects the request in flight. */
    static final Transport HTTP = (method, url, apiKey, body, cancel) -> {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        Runnable abort = connection::disconnect;
        if (cancel != null) {
            cancel.onCancel(abort);
        }
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", body.contentType());
                try (OutputStream out = connection.getOutputStream()) {
                    body.writeTo(out);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            return new Reply(code, readAll(stream));
        } catch (IOException e) {
            if (cancel != null && cancel.isCancelled()) {
                throw new InterruptedIOException("Canceled");
            }
            throw e;
        } finally {
            if (cancel != null) {
                cancel.removeOnCancel(abort);
            }
            connection.disconnect();
        }
    };

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
            }
        }
        return out.toString();
    }
}
