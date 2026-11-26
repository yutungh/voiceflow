package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Connect GitHub (docs/GITHUB-SYNC.md): the phone signs in to the VoiceFlow Word Lists
 * GitHub App ({@link GitHubApp}, the same App computers use) with GitHub's device flow, so
 * nobody makes or pastes a token.
 *
 * <ul>
 *   <li>{@code POST github.com/login/device/code} gives a code the user enters at
 *       github.com/login/device; the app polls {@code POST github.com/login/oauth/access_token}
 *       at GitHub's interval ({@code authorization_pending}, {@code slow_down} adds time, an
 *       expired or declined code ends it). The device code stays in memory only.</li>
 *   <li>The answer is an 8-hour access token ({@code ghu_}) and a 6-month refresh token
 *       ({@code ghr_}). Refreshing needs the client ID only and rotates both tokens
 *       ({@link GitHubCredentials} keeps them).</li>
 *   <li>The repository picker lists the user's installations of the App, then each one's
 *       repositories, from the fixed API address.</li>
 * </ul>
 * Requests go over HTTPS with redirects refused; tokens never appear in a URL, a log or an
 * exception message.
 */
final class GitHubAuth {
    /** GitHub's sign-in pages. Fixed in the app; a unit test points it at a local fake. */
    static volatile String origin = "https://github.com";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final int MAX_BYTES = 64 * 1024;
    private static final int TIMEOUT_MS = 20_000;
    static final Pattern ACCESS = Pattern.compile("ghu_[A-Za-z0-9_]{20,255}");
    static final Pattern REFRESH = Pattern.compile("ghr_[A-Za-z0-9_]{20,255}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9-]{1,100}");

    private GitHubAuth() {
    }

    // ---------------------------------------------------------------- tokens

    /** The App's tokens and when they run out (epoch millis). */
    static final class Tokens {
        final String access;
        final String refresh;
        final long accessExpiresAt;
        final long refreshExpiresAt;
        /** GitHub refused the refresh token: only a new Connect helps. */
        final boolean reconnect;

        Tokens(String access, String refresh, long accessExpiresAt, long refreshExpiresAt, boolean reconnect) {
            this.access = access;
            this.refresh = refresh;
            this.accessExpiresAt = accessExpiresAt;
            this.refreshExpiresAt = refreshExpiresAt;
            this.reconnect = reconnect;
        }

        Tokens needingReconnect() {
            return new Tokens(access, refresh, accessExpiresAt, refreshExpiresAt, true);
        }

        String toJson() {
            try {
                return new JSONObject()
                        .put("v", 1)
                        .put("kind", "app")
                        .put("accessToken", access)
                        .put("refreshToken", refresh)
                        .put("accessExpiresAt", accessExpiresAt)
                        .put("refreshExpiresAt", refreshExpiresAt)
                        .put("reconnect", reconnect)
                        .toString();
            } catch (JSONException e) {
                throw new IllegalStateException("tokens");
            }
        }

        /** The App's record, or null for anything else (a pasted token). */
        static Tokens fromJson(String text) {
            if (text == null || !text.trim().startsWith("{")) {
                return null;
            }
            try {
                JSONObject json = new JSONObject(text);
                if (json.optInt("v") != 1 || !"app".equals(json.optString("kind"))) {
                    return null;
                }
                String access = json.optString("accessToken", "");
                String refresh = json.optString("refreshToken", "");
                if (!ACCESS.matcher(access).matches() || !(refresh.isEmpty() || REFRESH.matcher(refresh).matches())) {
                    return null;
                }
                return new Tokens(access, refresh, json.optLong("accessExpiresAt"), json.optLong("refreshExpiresAt"),
                        json.optBoolean("reconnect", false));
            } catch (JSONException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return "Tokens(expires " + accessExpiresAt + ", reconnect " + reconnect + ")";
        }
    }

    /** Why signing in or refreshing didn't work, in words for the screen. */
    static final class AuthException extends Exception {
        /** The App's setup is wrong (device flow off, a wrong client ID): signing in again won't help. */
        final boolean config;

        AuthException(String message, boolean config) {
            super(message);
            this.config = config;
        }
    }

    private static AuthException network(IOException e) {
        return new AuthException(e instanceof SocketTimeoutException
                ? "GitHub didn't answer in time. Try again."
                : "Can't reach GitHub. Check the internet connection and try again.", false);
    }

    private static AuthException config(String code) {
        if ("device_flow_disabled".equals(code)) {
            return new AuthException("VoiceLnq's GitHub app doesn't allow this way of signing in. Use an access token (Advanced) for now.", true);
        }
        return new AuthException("GitHub refused VoiceLnq's sign-in request. Use an access token (Advanced) for now.", true);
    }

    // ---------------------------------------------------------------- requests

    /** One form POST to GitHub's sign-in pages, with no token; GitHub's errors come back as JSON. */
    private static JSONObject post(String path, String[][] form) throws AuthException {
        StringBuilder body = new StringBuilder();
        try {
            for (String[] pair : form) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(pair[0], "UTF-8")).append('=').append(URLEncoder.encode(pair[1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", "VoiceFlow-Android");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] answer = new byte[0];
            if (in != null) {
                try (InputStream stream = in) {
                    answer = GatewayClient.readCapped(stream, MAX_BYTES);
                }
            }
            if (code >= 500) {
                throw new AuthException("GitHub is having trouble right now. Try again in a few minutes.", false);
            }
            try {
                return new JSONObject(new String(answer, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new AuthException("GitHub's answer couldn't be read (HTTP " + code + ").", false);
            }
        } catch (IOException e) {
            throw network(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** The code the user types on GitHub. */
    static final class DeviceCode {
        /** Kept in memory only; never shown or logged. */
        final String deviceCode;
        final String userCode;
        final String verificationUri;
        final long expiresInSec;
        final long intervalSec;

        DeviceCode(String deviceCode, String userCode, String verificationUri, long expiresInSec, long intervalSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresInSec = expiresInSec;
            this.intervalSec = intervalSec;
        }

        @Override
        public String toString() {
            return "DeviceCode(" + userCode + ")";
        }
    }

    static DeviceCode requestCode(String clientId) throws AuthException {
        JSONObject json = post("/login/device/code", new String[][]{{"client_id", clientId}});
        String error = json.optString("error", "");
        if (!error.isEmpty()) {
            throw config(error);
        }
        String user = json.optString("user_code", "");
        String device = json.optString("device_code", "");
        if (!CODE.matcher(user).matches() || user.length() > 16 || !CODE.matcher(device).matches()) {
            throw new AuthException("GitHub's answer couldn't be read.", false);
        }
        // The user is only ever sent to GitHub's own page.
        return new DeviceCode(device, user, GitHubApp.DEVICE_URL,
                clamp(json.optLong("expires_in", 900), 60, 1800), clamp(json.optLong("interval", 5), 1, 60));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    enum PollState { PENDING, SLOW_DOWN, DONE, EXPIRED, DENIED, FAILED }

    static final class Poll {
        final PollState state;
        final long interval;
        final Tokens tokens;
        final AuthException error;

        Poll(PollState state, long interval, Tokens tokens, AuthException error) {
            this.state = state;
            this.interval = interval;
            this.tokens = tokens;
            this.error = error;
        }
    }

    /** The tokens in a successful answer, with their expiry times (millis). */
    static Tokens tokensFrom(JSONObject json, long now) {
        String access = json.optString("access_token", "");
        String refresh = json.optString("refresh_token", "");
        if (!ACCESS.matcher(access).matches() || !(refresh.isEmpty() || REFRESH.matcher(refresh).matches())) {
            return null;
        }
        // No expiry given: tokens that don't expire.
        long accessAt = json.has("expires_in") ? now + json.optLong("expires_in") * 1000L : Long.MAX_VALUE;
        long refreshAt = json.has("refresh_token_expires_in") ? now + json.optLong("refresh_token_expires_in") * 1000L : Long.MAX_VALUE;
        return new Tokens(access, refresh, accessAt, refreshAt, false);
    }

    /** Asks once whether the user has entered the code. */
    static Poll poll(String clientId, String deviceCode, long interval, long now) {
        JSONObject json;
        try {
            json = post("/login/oauth/access_token", new String[][]{
                    {"client_id", clientId}, {"device_code", deviceCode}, {"grant_type", DEVICE_GRANT}});
        } catch (AuthException e) {
            return new Poll(PollState.FAILED, interval, null, e);
        }
        String error = json.optString("error", "");
        switch (error) {
            case "authorization_pending":
                return new Poll(PollState.PENDING, interval, null, null);
            case "slow_down": {
                long next = json.optLong("interval", 0);
                return new Poll(PollState.SLOW_DOWN, Math.min(120, next > interval ? next : interval + 5), null, null);
            }
            case "expired_token":
            case "token_expired":
                return new Poll(PollState.EXPIRED, interval, null, null);
            case "access_denied":
                return new Poll(PollState.DENIED, interval, null, null);
            case "":
                Tokens tokens = tokensFrom(json, now);
                return tokens == null
                        ? new Poll(PollState.FAILED, interval, null, new AuthException("GitHub's answer couldn't be read.", false))
                        : new Poll(PollState.DONE, interval, tokens, null);
            default:
                return new Poll(PollState.FAILED, interval, null, config(error));
        }
    }

    enum RefreshState { OK, REJECTED, RETRY }

    static final class Refreshed {
        final RefreshState state;
        final Tokens tokens;
        final AuthException error;

        Refreshed(RefreshState state, Tokens tokens, AuthException error) {
            this.state = state;
            this.tokens = tokens;
            this.error = error;
        }
    }

    /** New tokens for a refresh token (the client ID alone: no secret). Rotates both. */
    static Refreshed refresh(String clientId, String refreshToken, long now) {
        JSONObject json;
        try {
            json = post("/login/oauth/access_token", new String[][]{
                    {"client_id", clientId}, {"grant_type", "refresh_token"}, {"refresh_token", refreshToken}});
        } catch (AuthException e) {
            return new Refreshed(RefreshState.RETRY, null, e);
        }
        String error = json.optString("error", "");
        if ("bad_refresh_token".equals(error) || "unauthorized".equals(error) || "incorrect_client_credentials".equals(error)) {
            return new Refreshed(RefreshState.REJECTED, null, null);
        }
        if (!error.isEmpty()) {
            return new Refreshed(RefreshState.RETRY, null, config(error));
        }
        Tokens tokens = tokensFrom(json, now);
        return tokens == null
                ? new Refreshed(RefreshState.RETRY, null, new AuthException("GitHub's answer couldn't be read.", false))
                : new Refreshed(RefreshState.OK, tokens, null);
    }

    // ---------------------------------------------------------------- waiting for the user

    /** Time, for the wait between polls (a fake one in tests). */
    interface Clock {
        long elapsedSec();

        long nowMs();

        /** Sleeps, or less when {@code cancelled} is set. */
        void sleep(long seconds, AtomicBoolean cancelled);
    }

    static Clock realClock() {
        final long start = android.os.SystemClock.elapsedRealtime();
        return new Clock() {
            @Override
            public long elapsedSec() {
                return (android.os.SystemClock.elapsedRealtime() - start) / 1000L;
            }

            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long seconds, AtomicBoolean cancelled) {
                long end = android.os.SystemClock.elapsedRealtime() + seconds * 1000L;
                while (!cancelled.get() && android.os.SystemClock.elapsedRealtime() < end) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
    }

    enum End { CONNECTED, EXPIRED, DENIED, CANCELLED, FAILED }

    static final class Outcome {
        final End end;
        final Tokens tokens;
        final String message;

        Outcome(End end, Tokens tokens, String message) {
            this.end = end;
            this.tokens = tokens;
            this.message = message;
        }
    }

    /**
     * Polls at GitHub's interval until the code is entered, declined, expired or the
     * attempt is cancelled. A network hiccup is tried again (five in a row end it).
     */
    static Outcome waitForUser(String clientId, DeviceCode code, AtomicBoolean cancelled, Clock clock) {
        long started = clock.elapsedSec();
        long interval = Math.max(1, code.intervalSec);
        int failures = 0;
        while (true) {
            clock.sleep(interval, cancelled);
            if (cancelled.get()) {
                return new Outcome(End.CANCELLED, null, null);
            }
            if (clock.elapsedSec() - started >= code.expiresInSec) {
                return new Outcome(End.EXPIRED, null, null);
            }
            Poll poll = poll(clientId, code.deviceCode, interval, clock.nowMs());
            switch (poll.state) {
                case PENDING:
                    failures = 0;
                    break;
                case SLOW_DOWN:
                    interval = poll.interval;
                    break;
                case DONE:
                    return new Outcome(End.CONNECTED, poll.tokens, null);
                case EXPIRED:
                    return new Outcome(End.EXPIRED, null, null);
                case DENIED:
                    return new Outcome(End.DENIED, null, null);
                default:
                    if (poll.error.config || ++failures >= 5) {
                        return new Outcome(End.FAILED, null, poll.error.getMessage());
                    }
            }
        }
    }

    // ---------------------------------------------------------------- the one attempt

    /** What the Connect screen shows of an attempt (no device code, no token). */
    static final class Attempt {
        final long id;
        final String userCode;
        final String verificationUri;
        final long expiresAt;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** "waiting", "connected", "expired", "denied", "cancelled" or "failed". */
        volatile String state = "waiting";
        volatile String message;

        Attempt(long id, DeviceCode code, long now) {
            this.id = id;
            this.userCode = code.userCode;
            this.verificationUri = code.verificationUri;
            this.expiresAt = now + code.expiresInSec * 1000L;
        }
    }

    interface Saver {
        void save(Tokens tokens) throws Exception;
    }

    private static final Object ATTEMPT_LOCK = new Object();
    private static Attempt current;
    private static long nextId;

    /** A new attempt; a running one is cancelled. */
    static Attempt begin(DeviceCode code, long now) {
        synchronized (ATTEMPT_LOCK) {
            if (current != null) {
                current.cancelled.set(true);
            }
            current = new Attempt(++nextId, code, now);
            return current;
        }
    }

    static Attempt current() {
        synchronized (ATTEMPT_LOCK) {
            return current;
        }
    }

    static void cancel(long id) {
        synchronized (ATTEMPT_LOCK) {
            if (current != null && current.id == id) {
                current.cancelled.set(true);
                if ("waiting".equals(current.state)) {
                    current.state = "cancelled";
                }
            }
        }
    }

    /** Forgetting GitHub stops whatever attempt is waiting. */
    static void cancelAll() {
        synchronized (ATTEMPT_LOCK) {
            if (current != null) {
                cancel(current.id);
            }
        }
    }

    /**
     * Ends attempt {@code id}. A connection is saved only while the attempt is still the
     * current one and not cancelled, checked under the attempt lock, so a cancelled or
     * replaced attempt never overwrites the credential.
     */
    static void finish(long id, Outcome outcome, Saver saver) {
        synchronized (ATTEMPT_LOCK) {
            if (current == null || current.id != id) {
                return;
            }
            if (current.cancelled.get()) {
                current.state = "cancelled";
                return;
            }
            switch (outcome.end) {
                case CONNECTED:
                    try {
                        saver.save(outcome.tokens);
                        current.state = "connected";
                    } catch (Exception e) {
                        current.state = "failed";
                        current.message = "Connected, but this phone couldn't keep the sign-in. Try again.";
                    }
                    break;
                case EXPIRED:
                    current.state = "expired";
                    current.message = "The code ran out before it was entered. Tap Connect GitHub for a new one.";
                    break;
                case DENIED:
                    current.state = "denied";
                    current.message = "GitHub says the request was cancelled there. Tap Connect GitHub to try again.";
                    break;
                case CANCELLED:
                    current.state = "cancelled";
                    break;
                default:
                    current.state = "failed";
                    current.message = outcome.message;
            }
        }
    }

    // ---------------------------------------------------------------- choosing a repository

    static final class RepoChoice {
        final String fullName;
        final boolean isPrivate;
        final String defaultBranch;

        RepoChoice(String fullName, boolean isPrivate, String defaultBranch) {
            this.fullName = fullName;
            this.isPrivate = isPrivate;
            this.defaultBranch = defaultBranch;
        }
    }

    /** GitHub refused the token (401) while listing. */
    static final class Unauthorized extends IOException {
        Unauthorized() {
            super("github-401");
        }
    }

    private static final int MAX_PAGES = 10;

    private static JSONObject apiGet(String url, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "VoiceFlow-Android");
        try {
            int code = connection.getResponseCode();
            if (code == 401) {
                throw new Unauthorized();
            }
            if (code != 200) {
                throw new IOException(code == 403 || code == 429
                        ? "GitHub asked VoiceLnq to slow down; try again in a few minutes."
                        : "GitHub answered with an error (HTTP " + code + ").");
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(new String(GatewayClient.readCapped(in, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("GitHub's answer couldn't be read.");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The repositories this sign-in reaches through the App: its installations, then
     * each one's repositories, in pages of 100 built from the fixed API address.
     */
    static List<RepoChoice> listRepos(String token) throws IOException {
        String api = GitHubClient.api;
        List<Long> installations = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject json = apiGet(api + "/user/installations?per_page=100&page=" + page, token);
            JSONArray items = json.optJSONArray("installations");
            int n = items == null ? 0 : items.length();
            for (int i = 0; i < n; i++) {
                long id = items.optJSONObject(i) == null ? 0 : items.optJSONObject(i).optLong("id", 0);
                if (id > 0) {
                    installations.add(id);
                }
            }
            if (n == 0 || installations.size() >= json.optLong("total_count", 0)) {
                break;
            }
        }
        Set<Long> seen = new HashSet<>();
        List<RepoChoice> repos = new ArrayList<>();
        for (long id : installations) {
            long count = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                JSONObject json = apiGet(api + "/user/installations/" + id + "/repositories?per_page=100&page=" + page, token);
                JSONArray items = json.optJSONArray("repositories");
                int n = items == null ? 0 : items.length();
                count += n;
                for (int i = 0; i < n; i++) {
                    JSONObject r = items.optJSONObject(i);
                    if (r == null || r.optLong("id", 0) <= 0 || r.optString("full_name", "").isEmpty()) {
                        continue;
                    }
                    if (seen.add(r.optLong("id"))) {
                        repos.add(new RepoChoice(r.optString("full_name"), r.optBoolean("private", false),
                                r.optString("default_branch", "main")));
                    }
                }
                if (n == 0 || count >= json.optLong("total_count", 0)) {
                    break;
                }
            }
        }
        repos.sort((a, b) -> a.fullName.toLowerCase(Locale.ROOT).compareTo(b.fullName.toLowerCase(Locale.ROOT)));
        return repos;
    }
}
