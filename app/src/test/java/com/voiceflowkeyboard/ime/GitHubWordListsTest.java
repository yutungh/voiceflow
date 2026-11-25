package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The phone reads the word lists from a private GitHub repository: the same offline
 * copy and last-good-copy rules as the gateway, an unchanged branch costing one
 * bodiless 304, a refused or truncated listing never emptying a layer, and GitHub's
 * waits honoured. Run against a small fake of GitHub's API on the loopback address
 * (its answers checked against the real API), plus an opt-in run against a real
 * private repository.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GitHubWordListsTest {
    private static final String TOKEN = "github_pat_11TESTTOKEN0000000000_abcdefghijklmnop";
    private static final String GATEWAY_TOKEN = "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";

    private Context context;
    private TokenVault.KeySource savedKeys;
    private FakeGitHub github;

    /** One branch of one repository, answering GET routes the way GitHub does. */
    static final class FakeGitHub {
        final ServerSocket socket;
        final Map<String, byte[]> files = Collections.synchronizedMap(new TreeMap<>());
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile int commits;
        volatile boolean truncated;
        volatile boolean isPrivate = true;
        volatile int forceStatus;
        volatile String[] forceHeaders = new String[0];
        volatile String forceBody = "{}";
        private volatile boolean stopped;
        private final Thread thread;

        FakeGitHub() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-github");
            thread.setDaemon(true);
            thread.start();
        }

        String origin() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        void put(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
            commits++;
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
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void serve() {
            while (!stopped && !socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    if (stopped) {
                        return;
                    }
                    handle(client);
                } catch (IOException e) {
                    // Closed, or a client that went away.
                }
            }
        }

        private void handle(Socket client) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String path = line.split(" ")[1];
            Map<String, String> headers = new java.util.HashMap<>();
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    headers.put(header.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), header.substring(colon + 1).trim());
                }
            }
            requests.add(line.split(" ")[0] + " " + path + (headers.containsKey("if-none-match") ? " (if-none-match)" : ""));
            OutputStream out = client.getOutputStream();
            if (!("Bearer " + TOKEN).equals(headers.get("authorization"))) {
                send(out, 401, "{\"message\":\"Bad credentials\"}", null);
                return;
            }
            if (forceStatus != 0) {
                send(out, forceStatus, forceBody, null, forceHeaders);
                return;
            }
            String base = "/repos/sam-lee/words";
            if (!path.startsWith(base)) {
                send(out, 404, "{\"message\":\"Not Found\"}", null);
                return;
            }
            String route = path.substring(base.length());
            String query = "";
            int q = route.indexOf('?');
            if (q >= 0) {
                query = route.substring(q + 1);
                route = route.substring(0, q);
            }
            try {
                if (route.isEmpty()) {
                    send(out, 200, new JSONObject().put("private", isPrivate).put("default_branch", "main").toString(), null);
                    return;
                }
                if (route.equals("/git/trees/main") && query.equals("recursive=1")) {
                    String etag = "W/\"tree-" + commits + "\"";
                    String sent = headers.get("if-none-match");
                    if (sent != null && sent.replace("W/", "").equals(etag.replace("W/", ""))) {
                        send(out, 304, null, etag);
                        return;
                    }
                    JSONArray tree = new JSONArray();
                    java.util.Set<String> dirs = new java.util.TreeSet<>();
                    synchronized (files) {
                        for (Map.Entry<String, byte[]> f : files.entrySet()) {
                            String[] parts = f.getKey().split("/");
                            StringBuilder dir = new StringBuilder();
                            for (int i = 0; i < parts.length - 1; i++) {
                                dir.append(i == 0 ? "" : "/").append(parts[i]);
                                dirs.add(dir.toString());
                            }
                            tree.put(new JSONObject().put("path", f.getKey()).put("type", "blob").put("mode", "100644")
                                    .put("sha", GitHubClient.blobId(f.getValue())).put("size", f.getValue().length));
                        }
                    }
                    for (String d : dirs) {
                        tree.put(new JSONObject().put("path", d).put("type", "tree").put("mode", "040000").put("sha", "0000000000000000000000000000000000000000"));
                    }
                    send(out, 200, new JSONObject().put("sha", "root").put("tree", tree).put("truncated", truncated).toString(), etag);
                    return;
                }
                if (route.startsWith("/git/trees/")) {
                    send(out, 404, "{\"message\":\"Not Found\"}", null);
                    return;
                }
                if (route.startsWith("/git/blobs/")) {
                    String sha = route.substring("/git/blobs/".length());
                    synchronized (files) {
                        for (byte[] bytes : files.values()) {
                            if (GitHubClient.blobId(bytes).equals(sha)) {
                                sendBytes(out, 200, bytes);
                                return;
                            }
                        }
                    }
                    send(out, 404, "{\"message\":\"Not Found\"}", null);
                    return;
                }
                if (route.startsWith("/contents/")) {
                    byte[] bytes = files.get(java.net.URLDecoder.decode(route.substring("/contents/".length()), "UTF-8"));
                    if (bytes == null) {
                        send(out, 404, "{\"message\":\"Not Found\"}", null);
                    } else {
                        sendBytes(out, 200, bytes);
                    }
                    return;
                }
                send(out, 404, "{\"message\":\"Not Found\"}", null);
            } catch (org.json.JSONException e) {
                send(out, 500, "{}", null);
            }
        }

        static void send(OutputStream out, int status, String body, String etag, String... extra) throws IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
            if (etag != null) {
                head.append("ETag: ").append(etag).append("\r\n");
            }
            for (int i = 0; i + 1 < extra.length; i += 2) {
                head.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
            }
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            head.append("Content-Type: application/json\r\nConnection: close\r\nContent-Length: ").append(bytes.length).append("\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        }

        static void sendBytes(OutputStream out, int status, byte[] bytes) throws IOException {
            String head = "HTTP/1.1 " + status + " X\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nContent-Length: "
                    + bytes.length + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        }
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
        WordLists.removeOtherCopies(context, null);
        Prefs.saveUserPhraseReplacements(context, new ArrayList<>());
        WordLists.invalidate();
        github = new FakeGitHub();
        GitHubClient.api = github.origin();
        github.put("README.md", "invented words only");
        github.put("profiles/household/profile.json", layer("household", "household",
                "\"terms\":[{\"id\":\"tovo\",\"text\":\"Tovo\",\"heardAs\":[\"Toe Vo\"]}]"));
        github.put("profiles/sam/profile.json", layer("personal", "sam",
                "\"terms\":[{\"id\":\"quorvane\",\"text\":\"Quorvane\",\"priority\":80}],"
                        + "\"corrections\":[{\"id\":\"core-vane\",\"from\":[\"Core Vane\"],\"to\":\"Quorvane\"}]"));
    }

    @After
    public void tearDown() {
        SyncStore.keys = savedKeys;
        GitHubClient.api = GitHubClient.API;
        if (github != null) {
            github.stop();
        }
        WordLists.invalidate();
    }

    private static String layer(String layer, String user, String body) {
        return "{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,\"layer\":\"" + layer + "\",\"user\":\"" + user
                + "\",\"updatedAt\":\"2026-11-25T09:00:00Z\"," + body + "}";
    }

    private static String op(String id, String body) {
        String device = id.substring(0, id.indexOf('-'));
        return "{\"schema\":\"voiceflow-op\",\"version\":1,\"id\":\"" + id + "\",\"device\":\"" + device
                + "\",\"createdAt\":\"2026-11-25T10:00:00Z\"," + body + "}";
    }

    private WordLists.Outcome sync() throws Exception {
        Enrolment words = SyncStore.wordEnrolment(context);
        SyncStore.GitHubConfig config = SyncStore.gitHub(context);
        return WordLists.refresh(context, new GitHubClient(config.owner, config.repo, config.branch, config.user,
                words.device, config.token(), SyncStore.gitHubWait(context)), words);
    }

    private List<String> texts() {
        List<String> out = new ArrayList<>();
        WordMerge.Snapshot snapshot = WordLists.current(context).snapshot;
        if (snapshot != null) {
            for (WordLayer.Term t : snapshot.terms) {
                out.add(t.text);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reading

    @Test
    public void readsTheRepoThenCostsOne304() throws Exception {
        SyncStore.saveGitHub(context, "https://github.com/sam-lee/words.git", "main", "sam", TOKEN);
        assertEquals(WordLists.Outcome.UPDATED, sync());
        assertTrue(texts().containsAll(java.util.Arrays.asList("Tovo", "Quorvane")));
        assertEquals(2, github.count("/git/blobs/"));
        github.requests.clear();
        assertEquals(WordLists.Outcome.UNCHANGED, sync());
        assertEquals(Collections.singletonList("GET /repos/sam-lee/words/git/trees/main?recursive=1 (if-none-match)"),
                new ArrayList<>(github.requests));
        // The dictation side uses it.
        WordLists.DictationWords words = WordLists.forDictation(context, "");
        assertFalse(words.legacy);
        assertTrue(words.prompt.contains("Quorvane"));
    }

    @Test
    public void anEditFromAnotherDeviceAppearsAndStaysOffline() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        sync();
        github.put("profiles/sam/ops/pc12ab34cd-1.json", op("pc12ab34cd-1", "\"type\":\"term\",\"term\":{\"text\":\"Zephlet\"}"));
        assertEquals(WordLists.Outcome.UPDATED, sync());
        assertTrue(texts().contains("Zephlet"));
        github.stop();
        assertEquals(WordLists.Outcome.OFFLINE, sync());
        WordLists.invalidate();
        assertTrue("offline copy", texts().contains("Zephlet"));
        assertFalse(WordLists.current(context).state.reachable);
    }

    /** GitHub serves any size: an edit over the app's limit is skipped, and the edits after it still arrive. */
    @Test
    public void anOversizedEditNeverBlocksTheNextOnes() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        StringBuilder big = new StringBuilder();
        while (big.length() <= WordOp.MAX_OP_BYTES) {
            big.append("x");
        }
        github.put("profiles/sam/ops/pc1-aaa.json", op("pc1-aaa", "\"type\":\"term\",\"term\":{\"text\":\"Velmorin\",\"gloss\":\"" + big + "\"}"));
        github.put("profiles/sam/ops/pc1-bbb.json", op("pc1-bbb", "\"type\":\"term\",\"term\":{\"text\":\"Zephlet\"}"));
        assertEquals(WordLists.Outcome.UPDATED, sync());
        assertTrue(texts().contains("Zephlet"));
        assertFalse(texts().contains("Velmorin"));
        assertTrue("accepted on the first sync", WordLists.current(context).state.accepted);
    }

    @Test
    public void aBadLayerKeepsTheLastGoodCopyAndIsNotFetchedAgain() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        sync();
        github.put("profiles/sam/profile.json", "{\"schema\":\"voiceflow-profile\"");
        sync();
        assertTrue(texts().contains("Quorvane"));
        JSONObject status = WordLists.statusBlock(context);
        assertTrue(status.toString(), status.toString().contains("rejected-"));
        github.requests.clear();
        github.commits++; // the listing changes, the bad file doesn't
        sync();
        assertEquals("the same bad file isn't fetched again", 0, github.count("/git/blobs/"));
    }

    @Test
    public void aRefusedTokenIsCountedAndKeepsTheCopy() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        sync();
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", "github_pat_11SOMEONEELSE000000_abcdefghijklmnop");
        try {
            sync();
            fail("a refused token is reported to the worker");
        } catch (WordLists.AuthRefused e) {
            assertEquals("github-401", e.cause.code);
        }
        assertTrue(texts().contains("Quorvane"));
        assertTrue(WordLists.statusBlock(context).getJSONArray("errors").toString().contains("github-401"));
    }

    @Test
    public void aTruncatedListingOrAnEmptyRepoIsRefused() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        sync();
        github.truncated = true;
        github.put("profiles/sam/profile.json", "{}");
        assertEquals(WordLists.Outcome.REFUSED, sync());
        assertTrue(texts().contains("Quorvane"));
        assertTrue(WordLists.statusBlock(context).getJSONArray("errors").toString().contains("github-too-many-files"));
        github.truncated = false;
        synchronized (github.files) {
            github.files.keySet().removeIf(p -> p.startsWith("profiles/"));
        }
        github.commits++;
        assertEquals(WordLists.Outcome.REFUSED, sync());
        assertTrue(texts().contains("Quorvane"));
    }

    @Test
    public void aRateLimitIsHonouredAcrossRuns() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        sync();
        long reset = System.currentTimeMillis() / 1000 + 1800;
        github.forceStatus = 403;
        github.forceHeaders = new String[]{"X-RateLimit-Remaining", "0", "X-RateLimit-Reset", Long.toString(reset)};
        github.forceBody = "{\"message\":\"API rate limit exceeded\"}";
        github.commits++;
        assertEquals(WordLists.Outcome.REFUSED, sync());
        assertEquals(reset * 1000, SyncStore.gitHubNotBefore(context));
        github.forceStatus = 0;
        github.requests.clear();
        sync();
        assertEquals("no request before the reset", 0, github.requests.size());
        assertTrue(texts().contains("Quorvane"));
    }

    @Test
    public void rateLimitAnswers() {
        long now = 100_000L;
        assertEquals(0, GitHubClient.rateLimitUntil(403, null, "4999", null, "{\"message\":\"Resource not accessible by personal access token\"}", now));
        assertEquals(now + 7_200_000L, GitHubClient.rateLimitUntil(403, "7200", null, null, "", now));
        assertEquals(now + 60_000L, GitHubClient.rateLimitUntil(429, null, null, null, "", now));
        assertEquals(now + 60_000L, GitHubClient.rateLimitUntil(403, null, null, null, "You have exceeded a secondary rate limit", now));
        assertEquals(500_000L, GitHubClient.rateLimitUntil(403, "60", "0", "500", "", now));
        assertEquals(0, GitHubClient.rateLimitUntil(404, null, null, null, "", now));
    }

    // ------------------------------------------------------------------ settings

    @Test
    public void namesAndTokens() {
        assertArrayEquals(new String[]{"Sam-Lee", "Words"}, GitHubClient.parseRepo(" https://github.com/Sam-Lee/Words.git/ "));
        assertNull(GitHubClient.parseRepo("words"));
        assertNull(GitHubClient.parseRepo("sam lee/words"));
        assertNull(GitHubClient.parseRepo("a/b/c"));
        assertTrue(GitHubClient.validBranch("feature/word-lists"));
        assertFalse(GitHubClient.validBranch("a..b"));
        assertFalse(GitHubClient.validBranch("x/"));
        assertTrue(GitHubClient.validToken(TOKEN));
        assertTrue(GitHubClient.validToken("ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertFalse(GitHubClient.validToken(GATEWAY_TOKEN));
        assertEquals("github:sam-lee/words@Main", GitHubClient.identity("Sam-Lee", "Words", "Main"));
        // Git's own id for "hello\n" (git hash-object).
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubClient.blobId("hello\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void badSettingsChangeNothing() throws Exception {
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        for (String[] bad : new String[][]{
                {"words", "main", "sam", TOKEN},
                {"sam-lee/words", "", "sam", TOKEN},
                {"sam-lee/words", "main", "Sam!", TOKEN},
                {"sam-lee/words", "main", "household", TOKEN},
                {"sam-lee/words", "main", "sam", GATEWAY_TOKEN}}) {
            try {
                SyncStore.saveGitHub(context, bad[0], bad[1], bad[2], bad[3]);
                fail("accepted " + bad[0] + " " + bad[1] + " " + bad[2]);
            } catch (IllegalArgumentException expected) {
                // Refused, as it should be.
            }
        }
        assertEquals("sam", SyncStore.gitHub(context).user);
        assertEquals(TOKEN, SyncStore.gitHub(context).token());
        // An empty token keeps the stored one.
        SyncStore.saveGitHub(context, "sam-lee/words", "dev", "sam", "");
        assertEquals("dev", SyncStore.gitHub(context).branch);
        assertEquals(TOKEN, SyncStore.gitHub(context).token());
        // The sealed value is not the token.
        assertFalse(SyncStore.prefs(context).getAll().toString().contains(TOKEN));
    }

    /**
     * A gateway phone that switches to GitHub keeps its gateway (updates, status) and
     * reads its words from GitHub; switching never mixes two offline copies; "this
     * phone only" dictates as before.
     */
    @Test
    public void switchingSourcesKeepsTheGatewayAndNeverMixesCopies() throws Exception {
        Enrolment gateway = SyncStore.enrol(context, "sam", "https://sync.pinewood.example", GATEWAY_TOKEN);
        assertEquals(gateway.gateway, SyncStore.wordEnrolment(context).gateway);
        File gatewayCopy = WordLists.folder(context, gateway);
        assertTrue(gatewayCopy.mkdirs() || gatewayCopy.isDirectory());

        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        assertNotNull("the gateway stays enrolled", SyncStore.enrolment(context));
        assertTrue(SyncStore.wordEnrolment(context).gateway.startsWith("github:"));
        assertFalse("the gateway's word copy goes", gatewayCopy.exists());
        sync();
        assertTrue(texts().contains("Quorvane"));
        // Enrolling with the gateway again (an adb re-enrolment) leaves the GitHub copy alone.
        SyncStore.enrol(context, "sam", "https://sync.pinewood.example", GATEWAY_TOKEN);
        assertTrue(texts().contains("Quorvane"));

        SyncStore.chooseWordSource(context, SyncStore.SOURCE_NONE);
        assertNull(SyncStore.wordEnrolment(context));
        WordLists.invalidate();
        assertTrue(WordLists.forDictation(context, "").legacy);
        assertTrue(SyncStore.anySync(context));

        SyncStore.chooseWordSource(context, SyncStore.SOURCE_GATEWAY);
        assertEquals(gateway.gateway, SyncStore.wordEnrolment(context).gateway);
        SyncStore.forgetGitHub(context);
        assertNull(SyncStore.gitHub(context));
        assertEquals("gateway", SyncStore.wordSource(context));
    }

    /** A phone with no gateway: the worker reads GitHub, and nothing else runs. */
    @Test
    public void theWorkerReadsGitHubWithoutAGateway() throws Exception {
        assertFalse(SyncStore.anySync(context));
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", TOKEN);
        assertTrue(SyncStore.anySync(context));
        SyncWorker.run(context);
        WordLists.invalidate();
        assertTrue(texts().contains("Quorvane"));
        assertEquals(0, github.count("/v1/"));
        // A refused GitHub token is noted but never counted in the gateway's evidence.
        SyncStore.saveGitHub(context, "sam-lee/words", "main", "sam", "github_pat_11SOMEONEELSE000000_abcdefghijklmnop");
        github.commits++;
        SyncWorker.refreshWordLists(context, null, null, SelfUpdater.installedVersionCode(context));
        UpdateEvidence evidence = SyncStore.evidence(context, SelfUpdater.installedVersionCode(context), System.currentTimeMillis());
        assertEquals(0, evidence.authErrors);
        assertEquals("github-401", evidence.lastError);
    }

    /**
     * The worker's gateway client belongs to the enrolment its token came with: after a
     * re-enrolment to another gateway, the old client's words go nowhere.
     */
    @Test
    public void aGatewayClientNeverFillsAnotherGatewaysCopy() throws Exception {
        WordListsTest.FakeGateway old = new WordListsTest.FakeGateway("sam", SyncStore.deviceId(context));
        try {
            old.layers.put("personal", layer("personal", "sam", "\"terms\":[{\"id\":\"quorvane\",\"text\":\"Quorvane\"}]")
                    .getBytes(StandardCharsets.UTF_8));
            Enrolment a = SyncStore.enrol(context, "sam", "https://a.pinewood.example", GATEWAY_TOKEN);
            Enrolment b = SyncStore.enrol(context, "sam", "https://b.pinewood.example", GATEWAY_TOKEN);
            SyncWorker.refreshWordLists(context, new GatewayClient(old.origin(), GATEWAY_TOKEN), a,
                    SelfUpdater.installedVersionCode(context));
            assertEquals("nothing was asked", 0, old.requests.size());
            assertFalse(new File(WordLists.folder(context, b), "layer-personal.json").exists());
            assertFalse(WordLists.folder(context, a).exists());
            // With the enrolment still current, the same call reads.
            SyncWorker.refreshWordLists(context, new GatewayClient(old.origin(), GATEWAY_TOKEN), b,
                    SelfUpdater.installedVersionCode(context));
            assertTrue(new File(WordLists.folder(context, b), "layer-personal.json").exists());
        } finally {
            old.stop();
        }
    }

    /** Test connection reads only, and says what it found. */
    @Test
    public void testConnection() throws Exception {
        github.isPrivate = false;
        GitHubClient.TestResult result = new GitHubClient("sam-lee", "words", "main", "alex", "ph00000001", TOKEN, null).test();
        assertTrue(result.message, result.ok);
        assertEquals(Collections.singletonList("sam"), result.users);
        assertFalse(result.userFound);
        assertTrue(result.warnings.toString(), result.warnings.toString().contains("public"));
        assertTrue(result.warnings.toString(), result.warnings.toString().contains("profiles/alex/"));
        GitHubClient.TestResult bad = new GitHubClient("sam-lee", "words", "main", "sam", "ph00000001",
                "github_pat_11SOMEONEELSE000000_abcdefghijklmnop", null).test();
        assertFalse(bad.ok);
        assertTrue(bad.message, bad.message.contains("didn't accept the token"));
        GitHubClient.TestResult noBranch = new GitHubClient("sam-lee", "words", "nope", "sam", "ph00000001", TOKEN, null).test();
        assertFalse(noBranch.ok);
        assertTrue(noBranch.message, noBranch.message.contains("can't find"));
    }

    /** A copy kept by an older version (bare SHA-256 values) is read as revisions: nothing is fetched again. */
    @Test
    public void anOlderGatewayStateKeepsItsCopy() throws Exception {
        WordListsTest.FakeGateway gateway = new WordListsTest.FakeGateway("sam", SyncStore.deviceId(context));
        try {
            Enrolment enrolment = SyncStore.enrol(context, "sam", "https://sync.pinewood.example", GATEWAY_TOKEN);
            byte[] personal = layer("personal", "sam", "\"terms\":[{\"id\":\"quorvane\",\"text\":\"Quorvane\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            gateway.layers.put("personal", personal);
            WordLists.refresh(context, new GatewayClient(gateway.origin(), GATEWAY_TOKEN), enrolment);
            // Rewrite the kept state as 0.8.x wrote it: "sha256" and "rejectedSha", no revisions.
            File state = new File(WordLists.folder(context, enrolment), "state.json");
            JSONObject json = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
            JSONObject kept = json.getJSONObject("layers").getJSONObject("personal");
            kept.put("sha256", kept.getString("revision").substring("sha256:".length())).remove("revision");
            kept.remove("rejectedRevision");
            kept.put("rejectedSha", "");
            json.put("manifestEtag", "");
            Files.write(state.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            gateway.requests.clear();
            WordLists.refresh(context, new GatewayClient(gateway.origin(), GATEWAY_TOKEN), enrolment);
            assertFalse("the layer isn't fetched again: " + gateway.requests, gateway.requests.contains("/v1/layer/personal"));
            assertTrue(texts().contains("Quorvane"));
        } finally {
            gateway.stop();
        }
    }

    // ------------------------------------------------------------------ live

    /**
     * Against a real private repository with invented words. Skipped unless
     * VOICEFLOW_GITHUB_TEST_REPO (owner/name) and VOICEFLOW_GITHUB_TEST_TOKEN are set
     * (never in CI). Reads only. The token is read here and never printed or written.
     */
    @Test
    public void liveRepository() throws Exception {
        String repo = System.getenv("VOICEFLOW_GITHUB_TEST_REPO");
        String token = System.getenv("VOICEFLOW_GITHUB_TEST_TOKEN");
        assumeTrue(repo != null && !repo.isEmpty() && token != null && !token.isEmpty());
        github.stop();
        GitHubClient.api = GitHubClient.API;
        String[] parts = GitHubClient.parseRepo(repo);
        GitHubClient.TestResult tested = new GitHubClient(parts[0], parts[1], "main", "sam", "ph00000001", token, null).test();
        assertTrue(tested.message, tested.ok);
        assertEquals("refusing a public repository", Boolean.TRUE, tested.isPrivate);
        System.out.println("live test connection: " + tested.message + " users=" + tested.users);
        // The app takes only fine-grained or classic tokens; a test token of another
        // kind (for example the GitHub CLI's) is sealed straight into place.
        SyncStore.saveGitHub(context, repo, "main", "sam", TOKEN);
        SyncStore.prefs(context).edit().putString("github_token_sealed", TokenVault.seal(SyncStore.keys, token)).commit();
        WordLists.Outcome first = sync();
        System.out.println("live first sync: " + first + " words=" + texts().size());
        assertTrue(texts().contains("Quorvane"));
        WordLists.Outcome second = sync();
        System.out.println("live second sync: " + second);
        assertEquals("an unchanged branch: a 304", WordLists.Outcome.UNCHANGED, second);
        SyncStore.saveGitHub(context, repo, "main", "sam", "github_pat_11AAAAAAA0invalidinvalidinvalid_000000");
        try {
            sync();
            fail("a bad token is refused");
        } catch (WordLists.AuthRefused e) {
            assertEquals("github-401", e.cause.code);
        }
        assertTrue("last good copy", texts().contains("Quorvane"));
        System.out.println("live bad token: refused, last good copy kept");
    }
}
