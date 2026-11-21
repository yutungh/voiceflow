package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Phase 4: the phone reads the word lists from the gateway, keeps an offline
 * copy, merges it with its own list (which nothing here may change or lose)
 * and uses the result for dictation only. Run against a small fake gateway on
 * the loopback address that serves the same routes as sync/.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WordListsTest {
    private static final String TOKEN = "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final String APP = "org.pinewood.chat";

    private Context context;
    private TokenVault.KeySource savedKeys;
    private FakeGateway gateway;
    private Enrolment enrolment;

    /** The gateway's routes, from files held in memory: a tiny HTTP/1.1 server, one request per connection. */
    static final class FakeGateway {
        final ServerSocket socket;
        final Map<String, byte[]> layers = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, byte[]> ops = Collections.synchronizedMap(new LinkedHashMap<>());
        volatile String user;
        volatile String device;
        volatile int forceStatus;
        private volatile boolean stopped;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final Thread thread;

        FakeGateway(String user, String device) throws IOException {
            this.user = user;
            this.device = device;
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-gateway");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Stops serving and returns only once the server thread has ended. Closing the
         * socket alone is not enough: an accept already in progress can still take one
         * more connection and answer it, so a sync right after would reach a "stopped"
         * gateway. Once the thread has ended, nothing listens on the port any more.
         */
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
            if (thread.isAlive()) {
                throw new AssertionError("the fake gateway did not stop");
            }
        }

        String origin() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private void serve() {
            while (!stopped && !socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    if (stopped) {
                        return; // accepted while stopping: closed unanswered
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
            requests.add(path);
            OutputStream out = client.getOutputStream();
            try {
                if (!("Bearer " + TOKEN).equals(headers.get("authorization"))) {
                    send(out, 401, "{\"error\":\"token\"}".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                if (forceStatus != 0) {
                    send(out, forceStatus, "{\"error\":\"refused\"}".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                if (path.equals("/v1/manifest")) {
                    JSONObject layerInfo = new JSONObject();
                    for (String name : WordLayer.FILE_LAYERS) {
                        byte[] bytes = layers.get(name);
                        layerInfo.put(name, bytes == null ? JSONObject.NULL
                                : new JSONObject().put("sha256", WordLists.sha256(bytes)).put("size", bytes.length));
                    }
                    List<String> names;
                    synchronized (ops) {
                        names = new ArrayList<>(ops.keySet());
                    }
                    byte[] body = new JSONObject().put("schema", "vf-sync-manifest").put("version", 1)
                            .put("user", user).put("device", device).put("layers", layerInfo)
                            .put("ops", new JSONArray(names))
                            .toString().getBytes(StandardCharsets.UTF_8);
                    String etag = "\"" + WordLists.sha256(body).substring(0, 40) + "\"";
                    if (etag.equals(headers.get("if-none-match"))) {
                        send(out, 304, null, etag);
                    } else {
                        send(out, 200, body, etag);
                    }
                    return;
                }
                byte[] bytes = null;
                if (path.startsWith("/v1/layer/")) {
                    bytes = layers.get(path.substring("/v1/layer/".length()));
                } else if (path.startsWith("/v1/ops/")) {
                    bytes = ops.get(path.substring("/v1/ops/".length()));
                }
                send(out, bytes == null ? 404 : 200, bytes == null ? "{}".getBytes(StandardCharsets.UTF_8) : bytes, null);
            } catch (org.json.JSONException e) {
                send(out, 500, "{}".getBytes(StandardCharsets.UTF_8), null);
            }
        }

        static void send(OutputStream out, int status, byte[] body, String etag) throws IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
            if (etag != null) {
                head.append("ETag: ").append(etag).append("\r\n");
            }
            head.append("Content-Type: application/json\r\nConnection: close\r\n");
            head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
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
    }

    @After
    public void tearDown() {
        SyncStore.keys = savedKeys;
        if (gateway != null) {
            gateway.stop();
        }
        WordLists.invalidate();
    }

    // ------------------------------------------------------------------ helpers

    private void enrol(String user) throws Exception {
        enrolment = SyncStore.enrol(context, user, "https://sync.pinewood.example", TOKEN);
        if (gateway == null) {
            gateway = new FakeGateway(user, enrolment.device);
        }
        gateway.user = user;
        gateway.device = enrolment.device;
    }

    private WordLists.Outcome sync() throws Exception {
        return WordLists.refresh(context, new GatewayClient(gateway.origin(), TOKEN), enrolment);
    }

    private static byte[] layer(String layer, String user, String body) {
        return ("{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,\"layer\":\"" + layer + "\",\"user\":\"" + user
                + "\",\"updatedAt\":\"2026-11-19T09:00:00Z\"" + (body.isEmpty() ? "" : "," + body) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] op(String id, String createdAt, String body) {
        String device = id.substring(0, id.indexOf('-'));
        return ("{\"schema\":\"voiceflow-op\",\"version\":1,\"id\":\"" + id + "\",\"device\":\"" + device
                + "\",\"createdAt\":\"" + createdAt + "\"," + body + "}").getBytes(StandardCharsets.UTF_8);
    }

    private void standardNas() {
        gateway.layers.put("household", layer("household", "household",
                "\"terms\":[{\"id\":\"pinewood\",\"text\":\"Pinewood\",\"kind\":\"place\",\"priority\":60}]"));
        gateway.layers.put("generated", layer("generated", gateway.user,
                "\"terms\":[{\"id\":\"febe\",\"text\":\"Febe\",\"kind\":\"bot\",\"heardAs\":[\"Phoebe\"],\"gloss\":\"the household bot\",\"priority\":90},"
                        + "{\"id\":\"qbr\",\"text\":\"QBR\",\"kind\":\"acronym\",\"gloss\":\"said \\\"cue-bar\\\"\",\"heardAs\":[\"Q bar\"],\"priority\":85}]"));
        gateway.layers.put("personal", layer("personal", gateway.user,
                "\"terms\":[{\"id\":\"milo\",\"text\":\"Milo\",\"priority\":70}],"
                        + "\"corrections\":[{\"id\":\"zork\",\"from\":[\"Zork Blacks\"],\"to\":\"Zorblax\"},"
                        + "{\"id\":\"kestrel\",\"from\":[\"Castrel\"],\"to\":\"Kestral\"}],"
                        + "\"defaults\":{\"promptTerms\":50}"));
    }

    // ------------------------------------------------------------------ before any synced copy

    @Test
    public void notEnrolledDictatesExactlyAsBefore() {
        List<PhraseReplacement> rows = Arrays.asList(
                new PhraseReplacement("Castrel\nKestral", "Kestrel", "the project"),
                new PhraseReplacement("Milo", "Milo", ""));
        Prefs.saveUserPhraseReplacements(context, rows);
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        List<PhraseReplacement> all = Prefs.allPhraseReplacements(context);
        assertTrue(words.legacy);
        assertEquals(PersonalVocabulary.transcriptionPrompt(all), words.prompt);
        assertEquals(PersonalVocabulary.keywords(all), words.keywords);
        assertEquals(all.size(), words.corrections.size());
        assertEquals("Kestrel", PersonalVocabulary.applyReplacements("castrel", words.corrections));
    }

    @Test
    public void enrolledButNeverSyncedStaysOnThePhonesList() throws Exception {
        enrol("milo");
        assertTrue(WordLists.forDictation(context, APP).legacy);
        // An unreachable gateway is not an accepted copy either.
        gateway.stop();
        assertEquals(WordLists.Outcome.OFFLINE, sync());
        assertTrue(WordLists.forDictation(context, APP).legacy);
        gateway = null;
    }

    @Test
    public void anEmptyNasIsAnAcceptedCopy() throws Exception {
        enrol("milo");
        assertEquals(WordLists.Outcome.UPDATED, sync());
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertFalse(words.legacy);
        // The built-in fixes still apply.
        assertEquals("Claude Code", PersonalVocabulary.applyReplacements("Cloud Code", words.corrections));
    }

    // ------------------------------------------------------------------ merging with the phone's list

    @Test
    public void nasWordsReachTheRequestAndThePhonesRowsAreKept() throws Exception {
        List<PhraseReplacement> rows = Arrays.asList(
                new PhraseReplacement("Castrel", "Kestrel", "our project"),
                new PhraseReplacement("Glim", "Glim", ""));
        Prefs.saveUserPhraseReplacements(context, rows);
        String before = Prefs.userPhraseReplacementsJson(context);
        enrol("milo");
        standardNas();
        assertEquals(WordLists.Outcome.UPDATED, sync());

        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertFalse(words.legacy);
        assertTrue(words.prompt, words.prompt.startsWith("Vocabulary: "));
        for (String word : new String[]{"Pinewood", "Febe", "QBR", "Milo", "Kestrel", "Glim"}) {
            assertTrue(word + " in keywords " + words.keywords, words.keywords.contains(word));
        }
        assertTrue(words.prompt, words.prompt.contains("Febe (not \"Phoebe\")"));
        assertTrue(words.prompt, words.prompt.contains("QBR (said 'cue-bar'; not \"Q bar\")"));
        // This phone's rows go last (most relevant), its own gloss as a hint.
        assertTrue(words.prompt, words.prompt.endsWith("Glim, Kestrel (our project; not \"Castrel\")"));
        // The phone's row beats the personal file for the same heard form.
        assertEquals("Kestrel and Zorblax", PersonalVocabulary.applyReplacements("Castrel and zork  blacks", words.corrections));
        // Nothing on the phone changed.
        assertEquals(before, Prefs.userPhraseReplacementsJson(context));
        assertEquals(2, Prefs.userPhraseReplacements(context).size());
        // Typing keeps using the phone's own list only.
        assertEquals("Zork Blacks", PersonalVocabulary.applyReplacements("Zork Blacks", Prefs.allPhraseReplacements(context)));
    }

    @Test
    public void anEditBeatsThePhonesRowAndAnUndoBringsTheRowBack() throws Exception {
        Prefs.saveUserPhraseReplacements(context, Collections.singletonList(new PhraseReplacement("Feebee", "Febey", "")));
        enrol("milo");
        gateway.ops.put("pc1-a.json", op("pc1-a", "2026-11-19T10:00:00Z",
                "\"type\":\"correction\",\"from\":[\"feebee\"],\"to\":\"Febe\",\"scope\":{\"apps\":[\"*\"]}"));
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertEquals("Febe", PersonalVocabulary.applyReplacements("Feebee", words.corrections));
        assertTrue(WordLists.current(context).snapshot.issues.contains(WordMerge.ISSUE_CONFLICT));

        gateway.ops.put("pc1-b.json", op("pc1-b", "2026-11-19T10:05:00Z", "\"type\":\"tombstone\",\"target\":\"pc1-a\""));
        sync();
        assertEquals("Febey", PersonalVocabulary.applyReplacements("Feebee", WordLists.forDictation(context, APP).corrections));
        // The deleted edit stays deleted on later syncs, even though its file is still listed.
        sync();
        assertEquals("Febey", PersonalVocabulary.applyReplacements("Feebee", WordLists.forDictation(context, APP).corrections));
    }

    @Test
    public void thePhonesRowsKeepTheirOldBehaviourAmongThemselves() {
        // First row wins an overlap; a chain between two rows is kept and applied once.
        WordLayer phone = WordLists.phoneLayer(Arrays.asList(
                new PhraseReplacement("Pip", "Pippa", ""),
                new PhraseReplacement("Pip\nPipp", "Pippin", ""),
                new PhraseReplacement("Pippa", "Pip-Pip", "")));
        WordMerge.Snapshot s = WordMerge.merge(Arrays.asList(WordLists.defaultsLayer(), phone),
                new ArrayList<>(), Collections.emptySet());
        List<PhraseReplacement> corrections = WordLists.DictationWords.fromSnapshot(s, APP).corrections;
        assertEquals("Pippa Pippin Pip-Pip", PersonalVocabulary.applyReplacements("Pip Pipp Pippa", corrections));
    }

    @Test
    public void aRowThatOnlySpellsItsWordStillFixesItsCapitals() throws Exception {
        Prefs.saveUserPhraseReplacements(context, Arrays.asList(
                new PhraseReplacement("pinewood", "PineWood", ""),
                new PhraseReplacement("Milo", "Milo", "")));
        List<PhraseReplacement> legacy = WordLists.forDictation(context, APP).corrections;
        assertEquals("PineWood and Milo", PersonalVocabulary.applyReplacements("pinewood and milo", legacy));
        enrol("milo");
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertFalse(words.legacy);
        assertEquals("PineWood and Milo", PersonalVocabulary.applyReplacements("pinewood and milo", words.corrections));
        assertTrue(WordLists.current(context).snapshot.issues.isEmpty());
    }

    @Test
    public void aPhoneWordStaysFirstWhenAnEditListsItToo() throws Exception {
        Prefs.saveUserPhraseReplacements(context, Collections.singletonList(new PhraseReplacement("Castrel", "Kestrel", "")));
        enrol("milo");
        gateway.layers.put("generated", layer("generated", "milo", "\"terms\":[{\"id\":\"z\",\"text\":\"Zorblax\",\"priority\":99}]"));
        gateway.layers.put("personal", layer("personal", "milo", "\"defaults\":{\"promptTerms\":1}"));
        gateway.ops.put("pc1-a.json", op("pc1-a", "2026-11-19T10:00:00Z",
                "\"type\":\"term\",\"term\":{\"text\":\"Kestrel\",\"priority\":0,\"apps\":[\"notes.exe\"]}"));
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertEquals(Collections.singletonList("Kestrel"), words.keywords);
    }

    @Test
    public void recordStartNeverBuildsOnTheMainThread() throws Exception {
        enrol("milo");
        standardNas();
        sync(); // a sync builds the snapshot on its own thread
        assertFalse(WordLists.forRecordStart(context, APP).legacy);

        // No build from before may still be running: it would refill the memory cleared below.
        WordLists.awaitBackgroundBuild();

        // Each build asked for below is held back until the recording has started without it,
        // so what the record start returns never depends on how fast the builder thread is.
        Thread testThread = Thread.currentThread();
        java.util.concurrent.Executor real = WordLists.builds;
        List<Thread> buildThreads = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> gate =
                new java.util.concurrent.atomic.AtomicReference<>();
        WordLists.builds = task -> {
            java.util.concurrent.CountDownLatch held = gate.get();
            real.execute(() -> {
                try {
                    held.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                buildThreads.add(Thread.currentThread());
                task.run();
            });
        };
        try {
            // Cold, as after a restart: the first recording uses the phone's list and asks for a build.
            WordLists.removeOtherCopies(context, enrolment); // keeps this user's copy
            WordLists.invalidate();
            java.lang.reflect.Field last = WordLists.class.getDeclaredField("lastBuilt");
            last.setAccessible(true);
            last.set(null, null);
            gate.set(new java.util.concurrent.CountDownLatch(1));
            assertTrue(WordLists.forRecordStart(context, APP).legacy);
            assertNull("the record start built a snapshot itself", last.get(null));
            gate.get().countDown();
            WordLists.awaitBackgroundBuild();
            assertEquals(1, buildThreads.size());
            WordLists.DictationWords words = WordLists.forRecordStart(context, APP);
            assertFalse(words.legacy);
            assertTrue(words.keywords.contains("Milo"));

            // Out of date (the phone's list changed): the last snapshot is used while a new one is built.
            Prefs.saveUserPhraseReplacements(context, Collections.singletonList(new PhraseReplacement("Glim", "Glim", "")));
            gate.set(new java.util.concurrent.CountDownLatch(1));
            WordLists.DictationWords stale = WordLists.forRecordStart(context, APP);
            assertFalse(stale.legacy);
            assertFalse(stale.keywords.contains("Glim"));
            gate.get().countDown();
            WordLists.awaitBackgroundBuild();
            assertEquals(2, buildThreads.size());
            assertTrue(WordLists.forRecordStart(context, APP).keywords.contains("Glim"));
            // Both builds ran on the builder's thread, never on the caller's.
            for (Thread thread : buildThreads) {
                assertFalse(thread == testThread);
            }
        } finally {
            if (gate.get() != null) {
                gate.get().countDown();
            }
            WordLists.builds = real;
        }
    }

    @Test
    public void aLargeFileIsCheckedQuickly() throws Exception {
        StringBuilder corrections = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                corrections.append(',');
            }
            corrections.append("{\"id\":\"c").append(i).append("\",\"from\":[");
            for (int f = 0; f < 50; f++) {
                corrections.append(f > 0 ? "," : "").append("\"heard").append(i).append('x').append(f).append('"');
            }
            corrections.append("],\"to\":\"Word").append(i).append("\"}");
        }
        byte[] file = layer("personal", "milo", "\"corrections\":[" + corrections + "]");
        long start = System.nanoTime();
        WordLayer parsed = WordLayer.parse(file, "personal", "milo");
        WordMerge.merge(Collections.singletonList(parsed), new ArrayList<>(), Collections.emptySet());
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(1000, parsed.corrections.size());
        assertTrue(ms + " ms", ms < 5000);
    }

    @Test
    public void onlyARealTimestampReachesTheStatusFile() throws Exception {
        enrol("milo");
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            note.append("Secret words ");
        }
        gateway.layers.put("personal", ("{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,\"layer\":\"personal\","
                + "\"user\":\"milo\",\"updatedAt\":\"" + note + "\"}").getBytes(StandardCharsets.UTF_8));
        sync();
        String status = WordLists.statusBlock(context).toString();
        assertFalse(status.contains("Secret"));
        assertTrue(status.length() < 4000);
    }

    @Test
    public void scopedFixesApplyOnlyInTheirApp() throws Exception {
        enrol("milo");
        gateway.layers.put("personal", layer("personal", "milo",
                "\"terms\":[{\"id\":\"kes\",\"text\":\"Kestrel\",\"apps\":[\"" + APP + "\"],\"priority\":10}],"
                        + "\"corrections\":[{\"id\":\"glim\",\"from\":[\"Glimm\"],\"to\":\"Glim\",\"scope\":{\"apps\":[\"" + APP + "\"]}}]"));
        sync();
        WordLists.DictationWords inApp = WordLists.forDictation(context, APP);
        WordLists.DictationWords elsewhere = WordLists.forDictation(context, "org.other.app");
        assertEquals("Glim", PersonalVocabulary.applyReplacements("Glimm", inApp.corrections));
        assertEquals("Glimm", PersonalVocabulary.applyReplacements("Glimm", elsewhere.corrections));
        assertTrue(inApp.keywords.contains("Kestrel"));
        assertFalse(elsewhere.keywords.contains("Kestrel"));
    }

    // ------------------------------------------------------------------ offline and bad files

    @Test
    public void offlineAndRejectedAndMissingFilesKeepTheLastGoodCopy() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        String first = WordLists.forDictation(context, APP).prompt;
        assertTrue(first.contains("Milo"));

        // A bad personal file (a typo'd field): rejected whole, the last good copy stays.
        gateway.layers.put("personal", layer("personal", "milo", "\"terms\":[{\"id\":\"x\",\"text\":\"Glim\",\"heardas\":[\"g\"]}]"));
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertTrue(words.prompt.contains("Milo"));
        assertFalse(words.prompt.contains("Glim"));
        JSONObject status = WordLists.statusBlock(context);
        assertTrue(status.toString(), status.getJSONArray("errors").toString().contains("personal: rejected (unknown-field)"));
        assertEquals("cached", layerState(status, "personal"));
        // The same bad file is not fetched again.
        int fetches = count("/v1/layer/personal");
        gateway.layers.put("generated", layer("generated", "milo", "\"terms\":[{\"id\":\"febe\",\"text\":\"Febe\"}]"));
        sync();
        assertEquals(fetches, count("/v1/layer/personal"));

        // The personal file disappears from the sync server: the copy stays.
        gateway.layers.remove("personal");
        sync();
        assertTrue(WordLists.forDictation(context, APP).prompt.contains("Milo"));

        // The gateway can't be reached: everything stays, the status says so.
        gateway.stop();
        assertEquals(WordLists.Outcome.OFFLINE, sync());
        assertTrue(WordLists.forDictation(context, APP).prompt.contains("Milo"));
        status = WordLists.statusBlock(context);
        assertTrue(status.getBoolean("usingCache"));
        assertFalse(status.getBoolean("sourceReachable"));
        gateway = null;
    }

    @Test
    public void unchangedNasCostsOneBodilessRequest() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        int before = gateway.requests.size();
        assertEquals(WordLists.Outcome.UNCHANGED, sync());
        assertEquals(before + 1, gateway.requests.size());
    }

    @Test
    public void aBadEditIsSkippedAndNotFetchedAgain() throws Exception {
        enrol("milo");
        gateway.ops.put("pc1-a.json", "{not json".getBytes(StandardCharsets.UTF_8));
        gateway.ops.put("pc1-b.json", op("pc1-b", "2026-11-19T10:00:00Z", "\"type\":\"style-set\",\"style\":\"x\""));
        gateway.ops.put("pc1-c.json", op("pc1-c", "2026-11-19T10:00:00Z", "\"type\":\"term\",\"term\":{\"text\":\"Glim\"}"));
        // An edit whose id doesn't match its name.
        gateway.ops.put("pc1-d.json", op("pc1-e", "2026-11-19T10:00:00Z", "\"type\":\"term\",\"term\":{\"text\":\"Zorb\"}"));
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertTrue(words.keywords.contains("Glim"));
        assertFalse(words.keywords.contains("Zorb"));
        int before = count("/v1/ops/pc1-a.json");
        gateway.layers.put("household", layer("household", "household", ""));
        sync();
        assertEquals(before, count("/v1/ops/pc1-a.json"));
    }

    // ------------------------------------------------------------------ privacy

    @Test
    public void aRefusedTokenKeepsTheCopyAndIsReportedOnce() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        gateway.forceStatus = 403;
        try {
            sync();
            fail("a 403 must be reported to the worker");
        } catch (WordLists.AuthRefused e) {
            assertEquals(403, e.cause.status);
            assertEquals(UpdateEvidence.ERROR_AUTH, e.cause.kind);
        }
        assertTrue(WordLists.forDictation(context, APP).prompt.contains("Milo"));
        JSONObject status = WordLists.statusBlock(context);
        assertTrue(status.getJSONArray("errors").toString().contains("gateway-http-403"));
    }

    @Test
    public void aManifestForAnotherUserOrDeviceIsNeverUsed() throws Exception {
        enrol("febe");
        standardNas();
        gateway.user = "milo"; // a gateway answering for someone else
        sync();
        assertTrue(WordLists.forDictation(context, APP).legacy);
        assertTrue(WordLists.statusBlock(context).getJSONArray("errors").toString().contains("manifest-mismatch"));
        assertEquals(0, count("/v1/layer/personal"));

        // Another user's personal file in this user's place is rejected too.
        gateway.user = "febe";
        gateway.layers.put("personal", layer("personal", "milo", "\"terms\":[{\"id\":\"s\",\"text\":\"Secret\"}]"));
        sync();
        assertFalse(WordLists.forDictation(context, APP).keywords.contains("Secret"));
    }

    @Test
    public void enrollingAsSomeoneElseRemovesTheOldCopy() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        File miloFolder = WordLists.folder(context, enrolment);
        assertTrue(new File(miloFolder, "layer-personal.json").isFile());
        enrol("febe");
        assertFalse(miloFolder.exists());
        assertTrue(WordLists.forDictation(context, APP).legacy);
        assertFalse(WordLists.forDictation(context, APP).keywords.contains("Milo"));
    }

    @Test
    public void theStatusBlockHoldsCountsNotWords() throws Exception {
        Prefs.saveUserPhraseReplacements(context, Collections.singletonList(new PhraseReplacement("Glimm", "Glim", "")));
        enrol("milo");
        standardNas();
        sync();
        JSONObject profile = WordLists.statusBlock(context);
        String text = profile.toString();
        for (String word : new String[]{"Pinewood", "Febe", "QBR", "Milo", "Zorblax", "Kestral", "Glim", "Phoebe"}) {
            assertFalse(word + " must not be in the status file", text.contains(word));
        }
        assertEquals(16, profile.getString("snapshotId").length());
        assertEquals("ok", layerState(profile, "household"));
        assertEquals("ok", layerState(profile, "personal"));
        JSONObject full = StatusReport.build(enrolment, "0.8.2", "Android test", System.currentTimeMillis(),
                UpdateEvidence.fresh(48, System.currentTimeMillis()), profile);
        assertEquals(profile.toString(), full.getJSONObject("profile").toString());
        // Pinewood, Febe, QBR, Milo and this phone's Glim; the built-in fixes add corrections only.
        assertEquals(5, profile.getInt("words"));
        assertEquals(1, profile.getJSONArray("layers").getJSONObject(2).getInt("words"));
        assertEquals(2, profile.getJSONArray("layers").getJSONObject(2).getInt("corrections"));
    }

    // ------------------------------------------------------------------ the prompt budget

    @Test
    public void thePromptStaysWithinItsBudget() throws Exception {
        enrol("milo");
        StringBuilder terms = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                terms.append(',');
            }
            terms.append("{\"id\":\"w").append(i).append("\",\"text\":\"Word").append(i)
                    .append("\",\"heardAs\":[\"Werd ").append(i).append("\"],\"priority\":100}");
        }
        gateway.layers.put("generated", layer("generated", "milo", "\"terms\":[" + terms + "]"));
        gateway.layers.put("personal", layer("personal", "milo", "\"defaults\":{\"promptTerms\":50}"));
        Prefs.saveUserPhraseReplacements(context, Arrays.asList(
                new PhraseReplacement("Glim", "Glim", ""), new PhraseReplacement("Castrel", "Kestrel", "")));
        sync();
        WordLists.DictationWords words = WordLists.forDictation(context, APP);
        assertTrue(words.prompt.length() + " chars", words.prompt.length() <= 600);
        assertTrue(words.keywords.size() <= 50);
        // The phone's rows are never pushed out by sync server words, even at priority 100.
        assertTrue(words.keywords.contains("Glim"));
        assertTrue(words.keywords.contains("Kestrel"));
        // A larger budget from the sync server is honoured by the tidy-up's list; the hard limit of
        // the contract still holds. 0.8.5: the request itself carries 20 words, the phone's first.
        gateway.layers.put("personal", layer("personal", "milo", "\"defaults\":{\"promptChars\":4000,\"promptTerms\":500}"));
        sync();
        words = WordLists.forDictation(context, APP);
        assertTrue(words.prompt.length() <= 4000);
        assertEquals(WordPrompt.TRANSCRIBE_TERMS, words.keywords.size());
        assertTrue(words.keywords.contains("Glim"));
        assertTrue(words.keywords.contains("Kestrel"));
        assertTrue(words.tidyWords.words.size() + " tidy words", words.tidyWords.words.size() > 50);
    }

    @Test
    public void aDictationKeepsTheWordsItStartedWith() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        WordLists.DictationWords pinned = WordLists.forDictation(context, APP);
        gateway.layers.put("personal", layer("personal", "milo", "\"terms\":[{\"id\":\"z\",\"text\":\"Zorb\"}]"));
        sync();
        assertFalse(pinned.keywords.contains("Zorb"));
        assertTrue(pinned.keywords.contains("Milo"));
        assertTrue(WordLists.forDictation(context, APP).keywords.contains("Zorb"));
        try {
            pinned.keywords.add("x");
            fail("pinned words are immutable");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(pinned);
        }
    }

    @Test
    public void theWordListsScreenShowsWhereEachEntryComesFrom() throws Exception {
        enrol("milo");
        standardNas();
        sync();
        List<FindReplaceActivity.SharedRow> rows = FindReplaceActivity.sharedRows(WordLists.current(context).snapshot);
        Map<String, String> badges = new LinkedHashMap<>();
        for (FindReplaceActivity.SharedRow row : rows) {
            badges.put(row.spelling + (row.hint ? "" : " (fix)"), WordLists.badge(row.layer));
        }
        assertEquals("Household", badges.get("Pinewood"));
        assertEquals("From your notes", badges.get("Febe"));
        assertEquals("Your list", badges.get("Milo"));
        assertEquals("Your list", badges.get("Zorblax (fix)"));
        assertNull(badges.get("Claude Code (fix)")); // built-ins are not listed
    }

    private int count(String path) {
        int n = 0;
        synchronized (gateway.requests) {
            for (String request : gateway.requests) {
                if (request.equals(path)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String layerState(JSONObject profile, String layer) throws Exception {
        JSONArray layers = profile.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            if (layer.equals(layers.getJSONObject(i).getString("layer"))) {
                return layers.getJSONObject(i).getString("state");
            }
        }
        return "";
    }
}
