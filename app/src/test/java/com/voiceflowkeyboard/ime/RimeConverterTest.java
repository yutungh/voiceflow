package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rime behind the candidate bar, Classic behind Rime (0.9.0): the mapping into
 * the bar, the fallback, the reranking step, learning, and the 9-key session
 * features (spelling strip, pins, partial commits) working on Rime's answers.
 */
public class RimeConverterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static PinyinComposer keypadClassic;
    private static PinyinComposer lettersClassic;

    private RimeEngineTest.FakeBackend backend;
    private ExecutorService worker;
    private RimeEngine engine;

    @BeforeClass
    public static void loadClassic() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        PinyinDictionary dictionary;
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        keypadClassic = new PinyinComposer(new PinyinKeypadLookup(PinyinDigitIndex.build(dictionary)));
        lettersClassic = new PinyinComposer(dictionary);
    }

    /** Rime's answers for a few 9-key buffers, as librime gives them (vf_t9, fresh user). */
    static final class ScriptedBackend extends RimeEngineTest.FakeBackend {
        @Override
        public String[] query(int mode, String input, int limit) {
            calls.add("query " + mode + " " + input);
            if (queryReturnsNull) {
                return null;
            }
            switch (input) {
                case "537462":
                    return new String[]{"可以拿", "6", "ke'yi'na", "", "可以吗", "6", "ke'yi'ma", "", "可以", "4", "ke'yi", ""};
                case "64426":
                    return new String[]{"你好", "5", "ni'hao", "", "你", "2", "ni", ""};
                case "ni'426":
                    return new String[]{"你好", "6", "ni'hao", "", "你", "3", "ni", ""};
                case "426":
                    return new String[]{"好", "3", "hao", ""};
                case "644269":
                    // Nothing reads the trailing 9: the best reading covers the first five keys.
                    return new String[]{"你好", "5", "ni'hao", ""};
                default:
                    return new String[0];
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        backend = new ScriptedBackend();
        RimeEngineTest.FakePlatform platform = new RimeEngineTest.FakePlatform();
        platform.base = folder.newFolder("rime");
        worker = Executors.newSingleThreadExecutor();
        engine = new RimeEngine(backend, platform, new RimeBreaker(new RimeBreakerTest.MemoryStore()), worker);
    }

    @After
    public void tearDown() {
        worker.shutdownNow();
    }

    private void ready() throws InterruptedException {
        engine.start();
        long until = System.currentTimeMillis() + 5000;
        while (!engine.isReady() && System.currentTimeMillis() < until) {
            Thread.sleep(2);
        }
        assertTrue(engine.isReady());
    }

    @Test
    public void classicAnswersUntilRimeIsReady() throws Exception {
        RimeConverter converter = new RimeConverter(keypadClassic, engine, true, true);
        List<PinyinCandidate> before = converter.candidates("64426", 30);
        assertFalse(converter.lastFromRime());
        assertEquals(keypadClassic.candidates("64426", 30).get(0).text, before.get(0).text);
        ready();
        List<PinyinCandidate> after = converter.candidates("64426", 30);
        assertTrue(converter.lastFromRime());
        assertEquals("你好", after.get(0).text);
        assertEquals("ni'hao", after.get(0).spelling);
    }

    @Test
    public void classicAnswersWhenRimeHasNothingOrFails() throws Exception {
        ready();
        RimeConverter converter = new RimeConverter(keypadClassic, engine, true, true);
        // Nothing from Rime: Classic's list (possibly empty too), never a blank bar because of Rime.
        assertEquals(keypadClassic.candidates("2222222222", 30).size(), converter.candidates("2222222222", 30).size());
        assertFalse(converter.lastFromRime());
        backend.queryReturnsNull = true;
        assertFalse(converter.candidates("64426", 30).isEmpty());
        assertFalse(converter.lastFromRime());
    }

    @Test
    public void theParticleRuleRunsOnRimesList() throws Exception {
        ready();
        RimeConverter converter = new RimeConverter(keypadClassic, engine, true, true);
        List<PinyinCandidate> found = converter.candidates("537462", 30);
        assertEquals("可以吗", found.get(0).text);
        assertEquals("可以拿", found.get(1).text);
    }

    @Test
    public void theNineKeySessionWorksOnRimesAnswers() throws Exception {
        ready();
        PinyinSession session = new PinyinSession(new RimeConverter(keypadClassic, engine, true, true));
        for (char c : "64426".toCharArray()) {
            assertTrue(session.append(c));
        }
        assertTrue(session.isKeypad());
        assertEquals("你好", session.candidates().get(0).text);
        // The digits read as Rime's top reading.
        assertEquals("ni'hao", session.composingText());
        assertFalse(session.spellingOptions().isEmpty());
        assertEquals("ni", session.spellingOptions().get(0).label);
        // Pin "ni": the buffer the next query sends is Rime's own pinned form.
        assertTrue(session.pinSpelling(0));
        assertEquals("ni'426", session.rawText());
        assertEquals("你好", session.candidates().get(0).text);
        // A partial commit: 你 reads "ni'", the rest keeps composing and is asked of Rime again.
        PinyinSession.Settlement first = session.select(1);
        assertEquals("你", first.text);
        assertEquals("426", session.rawText());
        assertEquals("好", session.candidates().get(0).text);
        PinyinSession.Settlement second = session.select(0);
        assertEquals("好", second.text);
        assertFalse(session.isComposing());
        // Each choice was learned over exactly the part of the buffer it read.
        long until = System.currentTimeMillis() + 5000;
        while (!backend.calls.contains("learn 0 426 好") && System.currentTimeMillis() < until) {
            Thread.sleep(2);
        }
        assertTrue(backend.calls.toString(), backend.calls.contains("learn 0 ni' 你"));
        assertTrue(backend.calls.contains("learn 0 426 好"));
    }

    @Test
    public void acceptingTheTopKeepsWhatItDoesNotReadAndLearnsIt() throws Exception {
        ready();
        PinyinSession session = new PinyinSession(new RimeConverter(keypadClassic, engine, true, true));
        for (char c : "644269".toCharArray()) {
            session.append(c);
        }
        PinyinSession.Settlement settled = session.settle(PinyinSession.SettleReason.ACCEPT_TOP);
        assertEquals(PinyinSession.Action.COMMIT, settled.action);
        assertEquals("你好9", settled.text);
        assertFalse(session.isComposing());
        long until = System.currentTimeMillis() + 5000;
        while (!backend.calls.contains("learn 0 64426 你好") && System.currentTimeMillis() < until) {
            Thread.sleep(2);
        }
        assertTrue(backend.calls.toString(), backend.calls.contains("learn 0 64426 你好"));
    }

    @Test
    public void switchingAwayCommitsWithoutLearning() throws Exception {
        ready();
        PinyinSession session = new PinyinSession(new RimeConverter(keypadClassic, engine, true, true));
        for (char c : "64426".toCharArray()) {
            session.append(c);
        }
        assertEquals("你好", session.settle(PinyinSession.SettleReason.MODE_CHANGED).text);
        Thread.sleep(50);
        for (String call : backend.calls) {
            assertFalse(call, call.startsWith("learn"));
        }
    }

    @Test
    public void classicAnswersAreNotLearnedAndLearningCanBeOff() throws Exception {
        PinyinSession classicServed = new PinyinSession(new RimeConverter(keypadClassic, engine, true, true));
        for (char c : "64426".toCharArray()) {
            classicServed.append(c);
        }
        classicServed.select(0);
        ready();
        PinyinSession noLearning = new PinyinSession(new RimeConverter(keypadClassic, engine, true, false));
        for (char c : "64426".toCharArray()) {
            noLearning.append(c);
        }
        noLearning.select(0);
        Thread.sleep(50);
        for (String call : backend.calls) {
            assertFalse(call, call.startsWith("learn"));
        }
    }

    @Test
    public void fullKeyboardBuffersAreSentLowerCased() throws Exception {
        ready();
        RimeConverter converter = new RimeConverter(lettersClassic, engine, false, true);
        converter.candidates("NiHao", 30);
        assertTrue(backend.calls.contains("query 1 nihao"));
    }
}
