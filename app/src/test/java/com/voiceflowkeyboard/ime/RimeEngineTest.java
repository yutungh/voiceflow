package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Rime engine's Java side (0.9.0) over a fake native backend: start-up,
 * mapping, the watchdog, the breaker, learning and "Forget my typing".
 */
public class RimeEngineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Answers like librime; can be told to fail, hang or refuse to start. */
    static class FakeBackend implements RimeEngine.Backend {
        volatile String startError = "";
        volatile CountDownLatch hang;
        volatile boolean queryReturnsNull;
        volatile boolean queryThrows;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String start(String shared, String prebuilt, String user) {
            calls.add("start");
            return startError;
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public String[] query(int mode, String input, int limit) {
            calls.add("query " + mode + " " + input);
            CountDownLatch wait = hang;
            if (wait != null && !input.equals("64426") && !input.equals("nihao")) {
                try {
                    wait.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (queryThrows) {
                throw new IllegalStateException("native error");
            }
            if (queryReturnsNull) {
                return null;
            }
            if (input.equals("943943")) {
                return new String[]{"这些", "6", "zhe'xie", "", "谢谢", "6", "xie'xie", "", "这", "3", "zhe", "",
                        "好久不见哦", "99", "hao'jiu'bu'jian'o", "c", "", "6", "", ""};
            }
            return new String[0];
        }

        @Override
        public boolean learn(int mode, String input, String text) {
            calls.add("learn " + mode + " " + input + " " + text);
            return true;
        }

        @Override
        public String version() {
            return "fake";
        }
    }

    static final class FakePlatform implements RimeEngine.Platform {
        volatile boolean library = true;
        volatile RimeBreaker.PreviousExit exit = RimeBreaker.PreviousExit.ORDINARY;
        final List<Runnable> delayed = Collections.synchronizedList(new ArrayList<>());
        final List<String> starts = Collections.synchronizedList(new ArrayList<>());
        volatile long now = 1_800_000_000_000L;
        File base;

        @Override
        public boolean loadLibrary() {
            return library;
        }

        @Override
        public RimeEngine.Dirs installData() {
            return new RimeEngine.Dirs(new File(base, "shared"), new File(base, "build"), new File(base, "user"),
                    "0123456789abcdef");
        }

        @Override
        public String installError() {
            return "";
        }

        @Override
        public RimeBreaker.PreviousExit previousExit(long since) {
            return exit;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            if (delayMs == 0) {
                task.run();
            } else {
                delayed.add(task);
            }
        }

        @Override
        public void recordStart(String outcome) {
            starts.add(outcome);
        }
    }

    private FakeBackend backend;
    private FakePlatform platform;
    private RimeBreakerTest.MemoryStore store;
    private ExecutorService worker;
    private RimeEngine engine;

    @Before
    public void setUp() throws Exception {
        backend = new FakeBackend();
        platform = new FakePlatform();
        platform.base = folder.newFolder("rime");
        store = new RimeBreakerTest.MemoryStore();
        worker = Executors.newSingleThreadExecutor();
        engine = new RimeEngine(backend, platform, new RimeBreaker(store), worker);
    }

    @After
    public void tearDown() {
        CountDownLatch hang = backend.hang;
        if (hang != null) {
            hang.countDown();
        }
        worker.shutdownNow();
    }

    private void startAndWait() throws InterruptedException {
        engine.start();
        waitFor(() -> engine.state() != RimeEngine.State.STARTING);
    }

    private interface Condition {
        boolean met();
    }

    private static void waitFor(Condition condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (!condition.met() && System.currentTimeMillis() < until) {
            Thread.sleep(2);
        }
        assertTrue("timed out", condition.met());
    }

    @Test
    public void notReadyMeansClassicAnswers() {
        assertNull(engine.query(true, "943943", 30));
    }

    @Test
    public void startsOffTheCallingThreadAndWarmsUp() throws Exception {
        startAndWait();
        assertEquals(RimeEngine.State.READY, engine.state());
        assertEquals("0123456789abcdef", engine.detail());
        assertTrue(backend.calls.contains("start"));
        assertTrue("warm-up query", backend.calls.contains("query 0 64426"));
        assertTrue(platform.starts.get(0).startsWith("ok "));
        // Rime is live: the breaker's marker is set until a clean stop.
        assertTrue(store.get(RimeBreaker.KEY_ACTIVE).length() > 0);
        engine.stop();
        waitFor(() -> backend.calls.contains("stop"));
        waitFor(() -> store.get(RimeBreaker.KEY_ACTIVE).isEmpty());
    }

    @Test
    public void candidatesKeepTextConsumedAndSpelling() throws Exception {
        startAndWait();
        List<PinyinCandidate> found = engine.query(true, "943943", 30);
        assertNotNull(found);
        assertEquals(4, found.size());
        assertEquals("这些", found.get(0).text);
        assertEquals(6, found.get(0).consumed);
        assertEquals("zhe'xie", found.get(0).spelling);
        assertEquals(3, found.get(2).consumed);
        // A consumed count past the buffer is clamped; a completion is flagged; an empty text is dropped.
        assertEquals(6, found.get(3).consumed);
        assertTrue(found.get(3).completion);
        assertFalse(found.get(0).completion);
    }

    @Test
    public void aHangIsAnsweredByClassicAndNotQueuedBehind() throws Exception {
        startAndWait();
        backend.hang = new CountDownLatch(1);
        long start = System.nanoTime();
        assertNull(engine.query(true, "943943", 30));
        long waited = (System.nanoTime() - start) / 1_000_000;
        assertTrue("waited " + waited + " ms", waited >= RimeEngine.QUERY_TIMEOUT_MS && waited < 2000);
        assertEquals(1, engine.hangs());
        // While the stuck call is out, nothing new is sent and nothing waits.
        int sent = backend.calls.size();
        start = System.nanoTime();
        assertNull(engine.query(true, "943943", 30));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 50);
        assertEquals(sent, backend.calls.size());
        // Once it returns, Rime answers again.
        backend.hang.countDown();
        backend.hang = null;
        Thread.sleep(50);
        assertNotNull(engine.query(true, "943943", 30));
        assertEquals(RimeEngine.State.READY, engine.state());
    }

    @Test
    public void repeatedHangsTripTheBreakerAndItStaysTrippedNextRun() throws Exception {
        startAndWait();
        for (int i = 0; i < RimeBreaker.LIMIT; i++) {
            backend.hang = new CountDownLatch(1);
            assertNull(engine.query(true, "943943", 30));
            backend.hang.countDown();
            Thread.sleep(30);
            platform.now += 1000;
        }
        assertEquals(RimeEngine.State.DISABLED, engine.state());
        assertNull(engine.query(true, "943943", 30));
        // A new process: the breaker keeps Rime off without loading it.
        backend.hang = null;
        ExecutorService second = Executors.newSingleThreadExecutor();
        try {
            RimeEngine next = new RimeEngine(backend, platform, new RimeBreaker(store), second);
            int startsBefore = Collections.frequency(backend.calls, "start");
            next.start();
            assertEquals(RimeEngine.State.DISABLED, next.state());
            assertEquals("breaker-hang", next.detail());
            Thread.sleep(50);
            assertEquals(startsBefore, Collections.frequency(backend.calls, "start"));
            // Choosing Rime again in Settings re-enables it.
            next.reenable();
            next.start();
            waitFor(() -> next.state() != RimeEngine.State.STARTING);
            assertEquals(RimeEngine.State.READY, next.state());
        } finally {
            second.shutdownNow();
        }
    }

    @Test
    public void aCrashInThePreviousRunCounts() throws Exception {
        store.put(RimeBreaker.KEY_ACTIVE, String.valueOf(platform.now - 5000));
        platform.exit = RimeBreaker.PreviousExit.NATIVE_CRASH;
        startAndWait();
        assertEquals(1, new RimeBreaker(store).recentFailures(platform.now).size());
        assertEquals(RimeEngine.State.READY, engine.state());
    }

    @Test
    public void theThirdCrashKeepsRimeUnloaded() throws Exception {
        RimeBreaker breaker = new RimeBreaker(store);
        breaker.recordFailure(RimeBreaker.KIND_CRASH, platform.now - 2000);
        breaker.recordFailure(RimeBreaker.KIND_CRASH, platform.now - 1000);
        store.put(RimeBreaker.KEY_ACTIVE, String.valueOf(platform.now - 500));
        platform.exit = RimeBreaker.PreviousExit.NATIVE_CRASH;
        startAndWait();
        assertEquals(RimeEngine.State.DISABLED, engine.state());
        assertFalse(backend.calls.contains("start"));
    }

    @Test
    public void aMissingLibraryOrAFailedStartFallsBack() throws Exception {
        platform.library = false;
        startAndWait();
        assertEquals(RimeEngine.State.FAILED, engine.state());
        assertEquals("no-library", engine.detail());
        assertNull(engine.query(true, "943943", 30));

        ExecutorService second = Executors.newSingleThreadExecutor();
        try {
            platform.library = true;
            backend.startError = "no-t9-schema";
            RimeEngine next = new RimeEngine(backend, platform, new RimeBreaker(store), second);
            next.start();
            waitFor(() -> next.state() != RimeEngine.State.STARTING);
            assertEquals(RimeEngine.State.FAILED, next.state());
            assertEquals("start-no-t9-schema", next.detail());
            assertEquals(1, new RimeBreaker(store).recentFailures(platform.now).size());
            assertTrue(store.get(RimeBreaker.KEY_STARTING).isEmpty());
        } finally {
            second.shutdownNow();
        }
    }

    @Test
    public void aStartThatNeverFinishesCountsAsAHang() throws Exception {
        backend.hang = new CountDownLatch(1);
        // The warm-up is exempt from the fake's hang, so block the start itself instead.
        FakeBackend slow = new FakeBackend() {
            @Override
            public String start(String shared, String prebuilt, String user) {
                try {
                    backend.hang.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        };
        RimeEngine stuck = new RimeEngine(slow, platform, new RimeBreaker(store), worker);
        stuck.start();
        assertEquals(RimeEngine.State.STARTING, stuck.state());
        assertEquals(1, platform.delayed.size());
        platform.delayed.get(0).run();
        assertEquals(RimeEngine.State.FAILED, stuck.state());
        assertEquals("start-timeout", stuck.detail());
        assertEquals(1, new RimeBreaker(store).recentFailures(platform.now).size());
    }

    @Test
    public void nativeErrorsFallBackAndEventuallyStopAsking() throws Exception {
        startAndWait();
        backend.queryThrows = true;
        for (int i = 0; i < RimeEngine.ERROR_LIMIT; i++) {
            assertNull(engine.query(true, "943943", 30));
        }
        assertEquals(RimeEngine.State.FAILED, engine.state());
        assertEquals(0, new RimeBreaker(store).recentFailures(platform.now).size());
    }

    @Test
    public void learnsOnTheWorker() throws Exception {
        startAndWait();
        engine.learn(true, "943943", "谢谢");
        waitFor(() -> backend.calls.contains("learn 0 943943 谢谢"));
        engine.learn(false, "xiexie", "谢谢");
        waitFor(() -> backend.calls.contains("learn 1 xiexie 谢谢"));
    }

    @Test
    public void forgetMyTypingClearsTheUserDictionaryAndRestarts() throws Exception {
        startAndWait();
        File user = new File(platform.base, "user");
        File userdb = new File(user, "vf_pinyin.userdb");
        assertTrue(userdb.mkdirs());
        assertTrue(new File(userdb, "000003.log").createNewFile());
        assertTrue(new File(user, "vf_pinyin.userdb.txt").createNewFile());
        AtomicReference<Boolean> result = new AtomicReference<>();
        engine.forget(user, result::set);
        waitFor(() -> result.get() != null);
        assertTrue(result.get());
        assertFalse(userdb.exists());
        assertFalse(new File(user, "vf_pinyin.userdb.txt").exists());
        assertEquals(RimeEngine.State.READY, engine.state());
        assertEquals(2, Collections.frequency(backend.calls, "start"));
        assertTrue(backend.calls.indexOf("stop") < backend.calls.lastIndexOf("start"));
    }

    @Test
    public void forgetWithoutRimeRunningJustClears() throws Exception {
        File user = folder.newFolder("user");
        assertTrue(new File(user, "vf_pinyin.userdb.txt").createNewFile());
        AtomicBoolean done = new AtomicBoolean();
        engine.forget(user, ok -> done.set(ok));
        waitFor(done::get);
        assertFalse(new File(user, "vf_pinyin.userdb.txt").exists());
        assertFalse(backend.calls.contains("start"));
    }

    @Test
    public void parseIgnoresMalformedEntries() {
        List<PinyinCandidate> parsed = RimeEngine.parse(new String[]{"你", "x", "", "", "好", "2", "hao", "", "trailing"}, 2);
        assertEquals(1, parsed.size());
        assertEquals("好", parsed.get(0).text);
        assertTrue(RimeEngine.parse(new String[0], 2).isEmpty());
    }
}
