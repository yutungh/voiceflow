package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Connect GitHub on the phone: the device flow (waiting at GitHub's interval, slowing
 * down, expiry, denial, cancel), the sealed credential with its rotating refresh, the
 * 401 rules, explicit changes winning over work already running, and the repository
 * picker. Run against a small fake of GitHub's sign-in pages and API on the loopback
 * address.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GitHubConnectTest {
    private static final String PAT = "github_pat_11TESTTOKEN0000000000_abcdefghijklmnop";

    private Context context;
    private TokenVault.KeySource savedKeys;
    private Fake fake;

    /** GitHub's OAuth pages (scripted answers, in order) and a few API pages. */
    static final class Fake {
        final ServerSocket socket;
        final Deque<String> oauth = new ArrayDeque<>();
        final Map<String, String[]> api = Collections.synchronizedMap(new HashMap<>());
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean stopped;
        private final Thread thread;

        Fake() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-github-oauth");
            thread.setDaemon(true);
            thread.start();
        }

        String origin() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        synchronized void answer(String json) {
            oauth.add(json);
        }

        int count(String needle) {
            synchronized (requests) {
                int n = 0;
                for (String r : requests) {
                    if (r.contains(needle)) {
                        n++;
                    }
                }
                return n;
            }
        }

        void stop() {
            stopped = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closed already.
            }
        }

        private void serve() {
            while (!stopped && !socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (IOException e) {
                    // Closed, or a client that went away.
                }
            }
        }

        private void handle(Socket client) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.split(" ");
            int length = 0;
            String auth = "";
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(15).trim());
                }
                if (lower.startsWith("authorization:")) {
                    auth = header.substring(14).trim();
                }
            }
            char[] body = new char[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            requests.add(parts[0] + " " + parts[1] + " " + new String(body, 0, read) + (auth.isEmpty() ? "" : " [" + auth + "]"));
            int status = 200;
            String reply;
            if ("POST".equals(parts[0])) {
                synchronized (this) {
                    reply = oauth.isEmpty() ? "{\"error\":\"authorization_pending\"}" : oauth.poll();
                }
            } else {
                String[] page = api.get(parts[1]);
                if (page == null) {
                    status = 404;
                    reply = "{\"message\":\"Not Found\"}";
                } else {
                    status = Integer.parseInt(page[0]);
                    reply = page[1];
                }
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }

    /** A clock whose sleeps pass at once. */
    static final class FakeClock implements GitHubAuth.Clock {
        long seconds;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public long elapsedSec() {
            return seconds;
        }

        @Override
        public long nowMs() {
            return 1_800_000_000_000L + seconds * 1000L;
        }

        @Override
        public void sleep(long s, AtomicBoolean cancelled) {
            sleeps.add(s);
            seconds += s;
        }
    }

    private static String tokens(int n) {
        return "{\"access_token\":\"ghu_" + pad(n, 36) + "\",\"expires_in\":28800,\"refresh_token\":\"ghr_" + pad(n, 76)
                + "\",\"refresh_token_expires_in\":15897600,\"scope\":\"\",\"token_type\":\"bearer\"}";
    }

    private static String access(int n) {
        return "ghu_" + pad(n, 36);
    }

    private static String pad(int n, int width) {
        String s = Integer.toString(n);
        StringBuilder out = new StringBuilder();
        while (out.length() + s.length() < width) {
            out.append('0');
        }
        return out.append(s).toString();
    }

    private static GitHubAuth.DeviceCode code(long expires) {
        return new GitHubAuth.DeviceCode("3584d83530557fdd1f46af8289938c8ef79f9dc5", "WDJB-MJHT", GitHubApp.DEVICE_URL, expires, 5);
    }

    /** Connected, with an access token that runs out at {@code expiresAt}. */
    private void connected(int n, long expiresAt) throws Exception {
        GitHubCredentials.connect(context, new GitHubAuth.Tokens(access(n), "ghr_" + pad(n, 76), expiresAt,
                expiresAt + 15_000_000_000L, false));
    }

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SyncStore.prefs(context).edit().clear().commit();
        savedKeys = SyncStore.keys;
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
        fake = new Fake();
        GitHubAuth.origin = fake.origin();
        GitHubClient.api = fake.origin();
    }

    @After
    public void tearDown() {
        SyncStore.keys = savedKeys;
        GitHubAuth.origin = "https://github.com";
        GitHubClient.api = GitHubClient.API;
        GitHubAuth.cancelAll();
        fake.stop();
    }

    @Test
    public void theCodeRequestSendsOnlyTheClientIdAndKeepsUsersOnGitHubsPage() throws Exception {
        fake.answer("{\"device_code\":\"3584d83530557fdd1f46af8289938c8ef79f9dc5\",\"user_code\":\"WDJB-MJHT\","
                + "\"verification_uri\":\"https://evil.example/login/device\",\"expires_in\":900,\"interval\":5}");
        GitHubAuth.DeviceCode code = GitHubAuth.requestCode(GitHubApp.CLIENT_ID);
        assertEquals("WDJB-MJHT", code.userCode);
        assertEquals(GitHubApp.DEVICE_URL, code.verificationUri);
        assertEquals("POST /login/device/code client_id=" + GitHubApp.CLIENT_ID, fake.requests.get(0));
        assertFalse("the device code is never shown", code.toString().contains("3584d835"));
    }

    @Test
    public void aDisabledDeviceFlowIsASetupProblem() {
        fake.answer("{\"error\":\"device_flow_disabled\"}");
        try {
            GitHubAuth.requestCode(GitHubApp.CLIENT_ID);
            fail();
        } catch (GitHubAuth.AuthException e) {
            assertTrue(e.config);
        }
    }

    @Test
    public void waitingPollsAtTheIntervalSlowsDownAndConnects() {
        fake.answer("{\"error\":\"authorization_pending\"}");
        fake.answer("{\"error\":\"slow_down\",\"interval\":10}");
        fake.answer("{\"error\":\"slow_down\"}");
        fake.answer("{\"error\":\"authorization_pending\"}");
        fake.answer(tokens(1));
        FakeClock clock = new FakeClock();
        GitHubAuth.Outcome outcome = GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(900), new AtomicBoolean(false), clock);
        assertEquals(GitHubAuth.End.CONNECTED, outcome.end);
        assertEquals(access(1), outcome.tokens.access);
        assertEquals(clock.nowMs() + 28_800_000L, outcome.tokens.accessExpiresAt);
        assertEquals(java.util.Arrays.asList(5L, 5L, 10L, 15L, 15L), clock.sleeps);
        String poll = fake.requests.get(0);
        assertTrue(poll, poll.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assertFalse("no secret", poll.contains("secret"));
        assertFalse("the tokens never show in a string", outcome.tokens.toString().contains("ghu_"));
    }

    @Test
    public void waitingEndsAtExpiryDenialOrCancel() {
        assertEquals(GitHubAuth.End.EXPIRED, GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(60), new AtomicBoolean(false), new FakeClock()).end);
        fake.answer("{\"error\":\"token_expired\"}");
        assertEquals(GitHubAuth.End.EXPIRED, GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(900), new AtomicBoolean(false), new FakeClock()).end);
        fake.answer("{\"error\":\"expired_token\"}");
        assertEquals(GitHubAuth.End.EXPIRED, GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(900), new AtomicBoolean(false), new FakeClock()).end);
        fake.answer("{\"error\":\"access_denied\"}");
        assertEquals(GitHubAuth.End.DENIED, GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(900), new AtomicBoolean(false), new FakeClock()).end);
        fake.requests.clear();
        assertEquals(GitHubAuth.End.CANCELLED, GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code(900), new AtomicBoolean(true), new FakeClock()).end);
        assertEquals("a cancelled attempt asks nothing", 0, fake.requests.size());
    }

    @Test
    public void aCancelledOrReplacedAttemptNeverSaves() {
        AtomicLong saves = new AtomicLong();
        GitHubAuth.Outcome connected = new GitHubAuth.Outcome(GitHubAuth.End.CONNECTED,
                new GitHubAuth.Tokens(access(1), "ghr_" + pad(1, 76), 1, 2, false), null);
        GitHubAuth.Attempt first = GitHubAuth.begin(code(900), 0);
        GitHubAuth.Attempt second = GitHubAuth.begin(code(900), 0);
        GitHubAuth.finish(first.id, connected, t -> saves.incrementAndGet());
        assertEquals("replaced", 0, saves.get());
        GitHubAuth.cancel(second.id);
        GitHubAuth.finish(second.id, connected, t -> saves.incrementAndGet());
        assertEquals("cancelled, then completed late", 0, saves.get());
        assertEquals("cancelled", second.state);
        GitHubAuth.Attempt third = GitHubAuth.begin(code(900), 0);
        GitHubAuth.finish(third.id, connected, t -> saves.incrementAndGet());
        assertEquals(1, saves.get());
        assertEquals("connected", third.state);
    }

    @Test
    public void aFreshTokenIsUsedWithoutAskingGitHub() throws Exception {
        connected(1, 10_000_000L);
        GitHubCredentials.Access a = GitHubCredentials.access(context, 10_000_000L - GitHubCredentials.REFRESH_MARGIN_MS - 1);
        assertEquals(access(1), a.token);
        assertTrue(a.app);
        assertEquals(0, fake.requests.size());
        assertEquals("app", GitHubCredentials.kind(context, 0));
    }

    @Test
    public void refreshingStoresTheRotatedPairBeforeItIsUsed() throws Exception {
        connected(1, 10_000_000L);
        fake.answer(tokens(7));
        GitHubCredentials.Access a = GitHubCredentials.access(context, 9_900_000L);
        assertEquals(access(7), a.token);
        String stored = TokenVault.open(SyncStore.keys, SyncStore.gitHubSealed(context));
        assertEquals(access(7), GitHubAuth.Tokens.fromJson(stored).access);
        assertEquals("ghr_" + pad(7, 76), GitHubAuth.Tokens.fromJson(stored).refresh);
        String body = fake.requests.get(0);
        assertTrue(body.contains("grant_type=refresh_token") && body.contains("ghr_" + pad(1, 76)) && !body.contains("secret"));
        assertEquals(access(7), GitHubCredentials.access(context, 9_900_001L).token);
        assertEquals("one refresh token, spent once", 1, fake.count("/login/oauth/access_token"));
    }

    @Test
    public void concurrentCallersRefreshOnce() throws Exception {
        connected(1, 10_000_000L);
        fake.answer(tokens(3));
        List<Thread> threads = new ArrayList<>();
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                try {
                    got.add(GitHubCredentials.access(context, 9_990_000L).token);
                } catch (GitHubCredentials.Problem e) {
                    got.add(e.code);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(20_000);
        }
        assertEquals(5, got.size());
        for (String token : got) {
            assertEquals(access(3), token);
        }
        assertEquals(1, fake.count("/login/oauth/access_token"));
    }

    @Test
    public void aRefusedRefreshMeansConnectAgainAndKeepsTheRecord() throws Exception {
        connected(1, 10_000_000L);
        fake.answer("{\"error\":\"bad_refresh_token\"}");
        try {
            GitHubCredentials.access(context, 9_999_000L);
            fail();
        } catch (GitHubCredentials.Problem e) {
            assertEquals("reconnect", e.code);
        }
        assertEquals("reconnect", GitHubCredentials.kind(context, 9_999_000L));
        assertFalse(SyncStore.gitHubSealed(context).isEmpty());
        try {
            GitHubCredentials.access(context, 9_999_000L);
            fail();
        } catch (GitHubCredentials.Problem e) {
            assertEquals("reconnect", e.code);
        }
        assertEquals("not tried again", 1, fake.count("/login/oauth/access_token"));
    }

    @Test
    public void anOfflineRefreshIsRetriedLaterNotADisconnect() throws Exception {
        connected(1, 10_000_000L);
        fake.stop();
        try {
            GitHubCredentials.access(context, 9_999_000L);
            fail();
        } catch (GitHubCredentials.Problem e) {
            assertEquals("retry", e.code);
        }
        assertEquals("app", GitHubCredentials.kind(context, 9_999_000L));
    }

    @Test
    public void a401AfterSomeoneElseRotatedReusesTheirToken() throws Exception {
        connected(1, 10_000_000L);
        fake.answer(tokens(8));
        fake.answer(tokens(9));
        GitHubCredentials.Access old = GitHubCredentials.access(context, 0);
        GitHubCredentials.Access newer = GitHubCredentials.access(context, 9_999_000L);
        GitHubCredentials.Access retried = GitHubCredentials.afterUnauthorized(context, old, 10_000_000L);
        assertEquals(newer.token, retried.token);
        assertEquals(1, fake.count("/login/oauth/access_token"));
        GitHubCredentials.Access fresh = GitHubCredentials.afterUnauthorized(context, retried, 10_000_000L);
        assertEquals(access(9), fresh.token);
        GitHubCredentials.refusedAgain(context, retried);
        assertEquals("a stale refusal doesn't poison the newer token", "app", GitHubCredentials.kind(context, 10_000_000L));
        GitHubCredentials.refusedAgain(context, fresh);
        assertEquals("reconnect", GitHubCredentials.kind(context, 10_000_000L));
    }

    @Test
    public void anExplicitChangeWinsOverWorkAlreadyRunning() throws Exception {
        connected(1, 10_000_000L);
        GitHubCredentials.Access running = GitHubCredentials.access(context, 0);
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", PAT);
        try {
            GitHubCredentials.afterUnauthorized(context, running, 1);
            fail();
        } catch (GitHubCredentials.Problem e) {
            assertEquals("replaced", e.code);
        }
        GitHubCredentials.refusedAgain(context, running);
        assertEquals("pat", GitHubCredentials.kind(context, 1));
        GitHubCredentials.Access pat = GitHubCredentials.access(context, 1);
        assertFalse(pat.app);
        assertEquals(PAT, pat.token);
        SyncStore.forgetGitHub(context);
        assertEquals("", GitHubCredentials.kind(context, 1));
        try {
            GitHubCredentials.access(context, 1);
            fail();
        } catch (GitHubCredentials.Problem e) {
            assertEquals("missing", e.code);
        }
        assertEquals(0, fake.requests.size());
    }

    @Test
    public void savingTheRepositoryLeavesTheConnectionExactlyAsItIs() throws Exception {
        connected(1, 10_000_000L);
        String before = SyncStore.gitHubSealed(context);
        long generation = SyncStore.gitHubGeneration();
        SyncStore.saveGitHub(context, "https://github.com/sam-lee/words", "main", "sam", "");
        assertEquals(before, SyncStore.gitHubSealed(context));
        assertEquals("no explicit change: a refresh in flight still lands", generation, SyncStore.gitHubGeneration());
        assertNotNull(SyncStore.gitHub(context));
        assertEquals("app", GitHubCredentials.kind(context, 0));
    }

    @Test
    public void saveNeedsAConnectionOrAToken() throws Exception {
        try {
            SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", "");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Connect GitHub"));
        }
        assertNull(SyncStore.gitHub(context));
    }

    private static String repo(long id, String name) {
        return "{\"id\":" + id + ",\"full_name\":\"" + name + "\",\"private\":true,\"default_branch\":\"trunk\"}";
    }

    @Test
    public void thePickerListsEveryInstalledRepositoryOnce() throws Exception {
        fake.api.put("/user/installations?per_page=100&page=1", new String[]{"200",
                "{\"total_count\":2,\"installations\":[{\"id\":11},{\"id\":12}]}"});
        JSONArray first = new JSONArray();
        for (int i = 0; i < 100; i++) {
            first.put(new JSONObject(repo(1000 + i, String.format(java.util.Locale.ROOT, "sam/r%03d", i))));
        }
        fake.api.put("/user/installations/11/repositories?per_page=100&page=1", new String[]{"200",
                "{\"total_count\":101,\"repositories\":" + first + "}"});
        fake.api.put("/user/installations/11/repositories?per_page=100&page=2", new String[]{"200",
                "{\"total_count\":101,\"repositories\":[" + repo(5, "sam/Words") + "]}"});
        fake.api.put("/user/installations/12/repositories?per_page=100&page=1", new String[]{"200",
                "{\"total_count\":2,\"repositories\":[" + repo(5, "sam/Words") + "," + repo(6, "family/lists") + "]}"});
        List<GitHubAuth.RepoChoice> repos = GitHubAuth.listRepos(access(1));
        assertEquals(102, repos.size());
        assertEquals("family/lists", repos.get(0).fullName);
        assertEquals("trunk", repos.get(0).defaultBranch);
        assertTrue(fake.requests.get(0).endsWith("[Bearer " + access(1) + "]"));
    }

    @Test
    public void thePickerSaysWhenNothingIsInstalledAndWhenTheTokenIsRefused() throws Exception {
        fake.api.put("/user/installations?per_page=100&page=1", new String[]{"200", "{\"total_count\":0,\"installations\":[]}"});
        assertTrue(GitHubAuth.listRepos(access(1)).isEmpty());
        fake.api.put("/user/installations?per_page=100&page=1", new String[]{"401", "{\"message\":\"Bad credentials\"}"});
        try {
            GitHubAuth.listRepos(access(1));
            fail();
        } catch (GitHubAuth.Unauthorized expected) {
            // The caller refreshes once.
        }
    }

    @Test
    public void tokensRoundTripAndAPastedTokenIsNotAnAppRecord() {
        GitHubAuth.Tokens t = new GitHubAuth.Tokens(access(4), "ghr_" + pad(4, 76), 10, 20, false);
        GitHubAuth.Tokens back = GitHubAuth.Tokens.fromJson(t.toJson());
        assertNotNull(back);
        assertEquals(t.access, back.access);
        assertEquals(t.refresh, back.refresh);
        assertEquals(20, back.refreshExpiresAt);
        assertNull(GitHubAuth.Tokens.fromJson(PAT));
        assertNull(GitHubAuth.Tokens.fromJson("{\"v\":2,\"kind\":\"app\"}"));
        assertNotEquals(t.toJson(), t.needingReconnect().toJson());
        assertTrue(GitHubApp.installUrl().startsWith("https://github.com/apps/" + GitHubApp.SLUG));
    }

    @Test
    public void phonesUseTheSameAppAsComputers() {
        // One App for computers and phones (desktop github_auth.rs: CLIENT_ID, APP_SLUG), so a
        // user installs one App on the word-list repository.
        assertEquals("Iv23liVu0ypF3zlpDwKj", GitHubApp.CLIENT_ID);
        assertEquals("voiceflow-word-lists", GitHubApp.SLUG);
        assertEquals("https://github.com/apps/voiceflow-word-lists/installations/new", GitHubApp.installUrl());
    }
}
