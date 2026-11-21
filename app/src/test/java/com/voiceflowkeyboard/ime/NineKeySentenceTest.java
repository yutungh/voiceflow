package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Nine-key sentences, 0.8.8: a run of digits over several syllables offers the
 * whole phrase or sentence first, built by the same lattice and word
 * frequencies that full-keyboard pinyin uses, then alternatives, then the words
 * that read just the start of the run.
 */
public class NineKeySentenceTest {

    private static PinyinComposer qwerty;
    private static PinyinComposer keypad;

    @BeforeClass
    public static void load() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        PinyinDictionary dictionary;
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        qwerty = new PinyinComposer(dictionary);
        keypad = new PinyinComposer(new PinyinKeypadLookup(PinyinDigitIndex.build(dictionary)));
    }

    private static List<String> texts(List<PinyinCandidate> candidates) {
        List<String> texts = new ArrayList<>();
        for (PinyinCandidate candidate : candidates) {
            texts.add(candidate.text);
        }
        return texts;
    }

    private static PinyinCandidate topOnPad(String digits) {
        List<PinyinCandidate> found = keypad.candidates(digits, 30);
        assertFalse("nothing for " + digits, found.isEmpty());
        return found.get(0);
    }

    @Test
    public void woShiIsOneTapForTheWholeRun() {
        PinyinCandidate first = topOnPad("96744");
        assertEquals(texts(keypad.candidates("96744", 30)).toString(), "我是", first.text);
        assertEquals("the whole run", 5, first.consumed);
    }

    @Test
    public void aSentenceComesFirstWhole() {
        // wo shi zhong guo ren
        String digits = PinyinDigitIndex.project("woshizhongguoren");
        PinyinCandidate first = topOnPad(digits);
        assertEquals(texts(keypad.candidates(digits, 30)).toString(), "我是中国人", first.text);
        assertEquals(digits.length(), first.consumed);
    }

    /**
     * 64426 62 is nihao ma and nigan ma alike (hao and gan are both 426). Chat
     * says 你干嘛 at least as often, so 0.8.9 may put it first; 你好吗 must stay
     * right behind it, whole.
     */
    @Test
    public void niHaoMaIsOfferedWhole() {
        String digits = PinyinDigitIndex.project("nihaoma");
        List<PinyinCandidate> found = keypad.candidates(digits, 30);
        int rank = texts(found).indexOf("你好吗");
        assertTrue(texts(found).toString(), rank >= 0 && rank < 3);
        assertEquals(digits.length(), found.get(rank).consumed);
    }

    /** Runs whose digits read no stronger sentence than the letters do. */
    private static final String[] SAME_ON_BOTH = {
            "woshi", "nihao", "woshizhongguoren", "wozaijia",
            "nizaiganma", "woaini", "zhongguo", "duibuqi", "meiguanxi", "haode", "wohenxiangni",
            "nichifanlema", "womenyiqiqu", "zhegewentihenjiandan", "wobuzhidao", "tashiwodepengyou",
            "jintianhenre", "nikanzhege", "wozaigongsi", "zaoshanghao", "wanan", "shenmeshihou",
            "meiwenti", "dajiahao",
    };

    /**
     * Runs where the keys clash: xie and zhe are both 943, jian and jiao both
     * 5426, ma and na both 62, hao, gan and gao all 426. The pad cannot know
     * which was meant, so it takes the commoner reading and offers the full
     * keyboard's one right after it. (0.8.9: 明天见 and 可以吗 now come first;
     * nihaoma and the weather sentence moved here, 很高 and 很好 being a near
     * tie in Wanxiang's counts.)
     */
    private static final String[] KEYS_CLASH = {
            "xiexie", "womenzoule", "mingtianjian", "keyima", "wojintianbuqule", "xiexienidebangzhu",
            "nihaoma", "jintiantianqihenhao",
    };

    @Test
    public void thePadAndTheFullKeyboardGiveTheSameSentence() {
        List<String> differences = new ArrayList<>();
        for (String run : SAME_ON_BOTH) {
            String onQwerty = qwerty.candidates(run, 1).get(0).text;
            String onPad = topOnPad(PinyinDigitIndex.project(run)).text;
            if (!onQwerty.equals(onPad)) {
                differences.add(run + ": qwerty " + onQwerty + ", pad " + onPad);
            }
        }
        assertTrue(differences.toString(), differences.isEmpty());
    }

    /**
     * The pad searches every reading of the digits with the same lattice and
     * scores, so its first reading is never weaker than the full keyboard's, and
     * where the keys clash the full keyboard's sentence is still near the top.
     */
    @Test
    public void whereTheKeysClashTheFullKeyboardsSentenceIsStillOffered() {
        List<String> runs = new ArrayList<>(java.util.Arrays.asList(SAME_ON_BOTH));
        runs.addAll(java.util.Arrays.asList(KEYS_CLASH));
        for (String run : runs) {
            PinyinCandidate onQwerty = qwerty.candidates(run, 1).get(0);
            String digits = PinyinDigitIndex.project(run);
            List<PinyinCandidate> onPad = keypad.candidates(digits, 30);
            assertTrue(run + ": the pad's search missed a stronger reading",
                    onPad.get(0).score >= onQwerty.score - 1e-9);
            int rank = texts(onPad).indexOf(onQwerty.text);
            assertTrue(run + ": " + onQwerty.text + " at " + rank + " in " + texts(onPad), rank >= 0 && rank < 5);
            assertEquals(digits.length(), onPad.get(rank).consumed);
        }
    }

    @Test
    public void afterTheSentenceComeAlternativesThenTheStartOfTheRun() {
        String digits = PinyinDigitIndex.project("woshizhongguoren");
        List<PinyinCandidate> found = keypad.candidates(digits, 30);
        // Whole readings first, then shorter ones; never a shorter one above a whole one.
        boolean seenPartial = false;
        boolean anyPartial = false;
        for (PinyinCandidate candidate : found) {
            boolean whole = candidate.consumed == digits.length() && !candidate.completion;
            if (!whole) {
                seenPartial = true;
                anyPartial |= candidate.consumed < digits.length();
            } else {
                assertFalse("whole reading after a partial one: " + texts(found), seenPartial);
            }
        }
        assertTrue("the start of the run is offered: " + texts(found), anyPartial);
        assertTrue(texts(found).toString(), texts(found).contains("我"));
    }
}
