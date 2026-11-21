package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.8.9: the nine-key weak spots of 0.8.8, the initials-only readings, and the
 * per-key time budget, against the shipped asset. The full benchmark runs only
 * when asked for: {@code ./gradlew testDebugUnitTest --tests '*PinyinBenchmarkTest'
 * -PvfBench=<out dir>} (bench/pinyin/README.md).
 */
public class PinyinBenchmarkTest {

    private static PinyinBenchmark.JavaEngine engine;
    private static List<PinyinBenchmark.Phrase> phrases;

    @BeforeClass
    public static void load() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        engine = new PinyinBenchmark.JavaEngine("shipped", PinyinBenchmark.loadDictionary(asset));
        phrases = PinyinBenchmark.loadPhrases(new File("../bench/pinyin/phrases.tsv"));
    }

    private static List<String> padTexts(String letters) {
        List<String> texts = new ArrayList<>();
        for (PinyinCandidate c : engine.keypad.candidates(PinyinDigitIndex.project(letters), 30)) {
            texts.add(c.text);
        }
        return texts;
    }

    /** 5426 is jian or jiao: 明天见, not 明天叫. 62 is ma or na: 可以吗, not 可以那. */
    @Test
    public void theWeakSpotsOf088ComeFirst() {
        assertEquals(padTexts("mingtianjian").toString(), "明天见", padTexts("mingtianjian").get(0));
        assertEquals(padTexts("keyima").toString(), "可以吗", padTexts("keyima").get(0));
    }

    /**
     * 943 943 is 谢谢 or 这些. Without context the data still slightly prefers
     * 这些 (written text counts it 21 times as often; the chat boost brings
     * 谢谢 to within 5%), so 谢谢 must at least be second: one tap.
     */
    @Test
    public void xieXieIsOneTapAway() {
        int rank = padTexts("xiexie").indexOf("谢谢");
        assertTrue(padTexts("xiexie").toString(), rank >= 0 && rank <= 1);
    }

    /** 简拼: the first letters of 我们 (w, m) on the pad, after the full readings of 96. */
    @Test
    public void initialsReadTheCommonestWords() {
        List<String> found = new ArrayList<>();
        int firstAbbreviation = -1;
        List<PinyinCandidate> candidates = engine.keypad.candidates("96", 30);
        for (int i = 0; i < candidates.size(); i++) {
            found.add(candidates.get(i).text);
            if (candidates.get(i).text.equals("我们")) {
                firstAbbreviation = i;
            }
        }
        assertTrue(found.toString(), firstAbbreviation > 0);
        assertEquals("我", found.get(0));
        PinyinDictionary dictionary = (PinyinDictionary) engine.qwerty.lookup();
        java.util.Map<String, String> readsAs = PinyinDigitIndex.charactersBySyllable(dictionary);
        assertEquals("wm", PinyinDigitIndex.initials(dictionary, readsAs, "women", "我们"));
        // Cut by the characters' readings (Codex review): ke'neng, never ken'eng.
        assertEquals("kn", PinyinDigitIndex.initials(dictionary, readsAs, "keneng", "可能"));
        assertEquals("sg", PinyinDigitIndex.initials(dictionary, readsAs, "sange", "三个"));
    }

    /**
     * Typing stays fast with ~344k keys: per key, through PinyinSession, the
     * 95th percentile on this JVM. A phone is slower; the budget there is 50 ms,
     * so 10 ms here leaves a factor of five.
     */
    @Test
    public void eachKeyIsFast() {
        List<PinyinBenchmark.Phrase> test = new ArrayList<>();
        for (PinyinBenchmark.Phrase p : phrases) {
            if (p.split.equals("test")) {
                test.add(p);
            }
        }
        double[] t9 = PinyinBenchmark.keyLatencies(engine.keypad, test, PinyinBenchmark.Mode.T9, 3);
        double[] qwerty = PinyinBenchmark.keyLatencies(engine.qwerty, test, PinyinBenchmark.Mode.QWERTY, 3);
        assertTrue("t9 p95 " + PinyinBenchmark.percentile(t9, .95), PinyinBenchmark.percentile(t9, .95) < 10);
        assertTrue("qwerty p95 " + PinyinBenchmark.percentile(qwerty, .95),
                PinyinBenchmark.percentile(qwerty, .95) < 10);
    }

    /** The full benchmark, on request only: -PvfBench=<out dir>. */
    @Test
    public void benchmark() throws IOException {
        String out = System.getProperty("vf.bench", "");
        Assume.assumeTrue("set -PvfBench=<out dir> to run the benchmark", !out.isEmpty());
        System.out.print(PinyinBenchmark.benchmark("shipped", new File("src/main/assets/pinyin_dict.txt"),
                new File("../bench/pinyin/phrases.tsv"), new File(out), true));
    }
}
