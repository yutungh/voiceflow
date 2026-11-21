package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * Nine-key (九宫格) pinyin, 0.8.7: digits to syllables, the spelling strip,
 * pinning a spelling and committing part of a run.
 *
 * <p>The ambiguous cases are the classic ones. Note the projections: 94664 is
 * zhong or xiong (xiang is 94264); 426 is hao or gao; 7426 is shan, qian,
 * shao, piao or pian.
 */
public class PinyinKeypadTest {

    private static PinyinDictionary dictionary;
    private static PinyinKeypadLookup keypad;
    private static PinyinComposer composer;

    @BeforeClass
    public static void load() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        keypad = new PinyinKeypadLookup(PinyinDigitIndex.build(dictionary));
        composer = new PinyinComposer(keypad);
    }

    private static List<String> labels(List<PinyinKeypadLookup.SpellingOption> options) {
        List<String> labels = new ArrayList<>();
        for (PinyinKeypadLookup.SpellingOption option : options) {
            labels.add(option.label);
        }
        return labels;
    }

    private static List<String> texts(List<PinyinCandidate> candidates) {
        List<String> texts = new ArrayList<>();
        for (PinyinCandidate candidate : candidates) {
            texts.add(candidate.text);
        }
        return texts;
    }

    private static PinyinCandidate top(String typed) {
        List<PinyinCandidate> found = composer.candidates(typed, 30);
        assertFalse("nothing for " + typed, found.isEmpty());
        return found.get(0);
    }

    // ------------------------------------------------------------ segmentation

    @Test
    public void zhongLeadsItsDigitsAndXiongIsOffered() {
        // 94664: z-h-o-n-g and x-i-o-n-g share the keys.
        PinyinCandidate first = top("94664");
        assertEquals("中", first.text);
        assertEquals("zhong", first.spelling);
        List<String> spellings = labels(keypad.spellingOptions("94664", first));
        assertEquals("zhong", spellings.get(0));
        assertTrue(spellings.toString(), spellings.contains("xiong"));
        assertTrue(texts(composer.candidates("94664", 30)).contains("熊"));
    }

    @Test
    public void xiangIsItsOwnDigits() {
        assertEquals("94264", PinyinDigitIndex.project("xiang"));
        List<String> spellings = labels(keypad.spellingOptions("94264", top("94264")));
        assertTrue(spellings.toString(), spellings.contains("xiang"));
    }

    @Test
    public void haoAndGaoShare426() {
        PinyinCandidate first = top("426");
        assertEquals("好", first.text);
        List<String> spellings = labels(keypad.spellingOptions("426", first));
        assertEquals("hao", spellings.get(0));
        assertTrue(spellings.toString(), spellings.contains("gao"));
        assertTrue(texts(composer.candidates("426", 30)).contains("高"));
    }

    /**
     * 4426 is not pinyin at all (no syllable starts with two of g/h/i), so it
     * has no candidates and stays as typed; hao/gao is 426. The first digit's
     * letters are still offered, so a slip can be pinned and fixed.
     */
    @Test
    public void fourFourTwoSixIsNotPinyinButTheLettersStayPinnable() {
        assertTrue(composer.candidates("4426", 30).isEmpty());
        List<String> spellings = labels(keypad.spellingOptions("4426", null));
        assertFalse(spellings.isEmpty());
        // The first digit's letters are always there to pin by hand.
        assertTrue(spellings.toString(), spellings.contains("g"));
        assertTrue(spellings.toString(), spellings.contains("h"));
        // No pinyin syllable starts with i, so it is not offered.
        assertFalse(spellings.toString(), spellings.contains("i"));
    }

    @Test
    public void sevenFourTwoSixIsShanQianShaoPiaoPian() {
        List<String> spellings = labels(keypad.spellingOptions("7426", top("7426")));
        for (String wanted : new String[]{"shan", "qian", "shao", "piao", "pian"}) {
            assertTrue(wanted + " in " + spellings, spellings.contains(wanted));
        }
        // rian is not pinyin.
        assertFalse(spellings.contains("rian"));
        List<String> words = texts(composer.candidates("7426", 30));
        assertTrue(words.toString(), words.contains("钱"));
        assertTrue(words.toString(), words.contains("山"));
    }

    @Test
    public void sixFourIsNiThenMiThenTheLetters() {
        PinyinCandidate first = top("64");
        assertEquals("你", first.text);
        List<String> spellings = labels(keypad.spellingOptions("64", first));
        assertEquals("ni", spellings.get(0));
        assertTrue(spellings.contains("mi"));
        assertTrue(spellings.indexOf("mi") < spellings.indexOf("m"));
        assertTrue(spellings.containsAll(java.util.Arrays.asList("m", "n", "o")));
        // Interjections with no vowel (m, n, ng) are not offered as syllables.
        assertEquals(1, java.util.Collections.frequency(spellings, "n"));
    }

    @Test
    public void everydayWordsLeadOnThePad() {
        String[][] cases = {
                {"nihao", "你好"},
                {"xiexie", "谢谢"},
                {"zhongguo", "中国"},
                {"women", "我们"},
                {"haode", "好的"},
                {"mingtian", "明天"},
                {"chifan", "吃饭"},
        };
        for (String[] row : cases) {
            String typed = PinyinDigitIndex.project(row[0]);
            List<String> words = texts(composer.candidates(typed, 5));
            assertTrue(row[0] + " (" + typed + ") wanted " + row[1] + " in " + words, words.contains(row[1]));
        }
        assertEquals("你好", top("64426").text);
    }

    /**
     * The 9-key gate from PinyinDigitIndexTest, through the keypad lookup that
     * ships: the leading-words tier must not push everyday phrases out of the
     * top five. Same agreed bar, 28 of 30.
     */
    @Test
    public void everydayPhrasesStillRankInTopFive() {
        String[][] corpus = {
                {"nihao", "你好"}, {"xiexie", "谢谢"}, {"zaijian", "再见"}, {"woaini", "我爱你"},
                {"zhongguo", "中国"}, {"shenmeshihou", "什么时候"}, {"duibuqi", "对不起"},
                {"meiguanxi", "没关系"}, {"zenmeyang", "怎么样"}, {"chifanle", "吃饭了"},
                {"womenshi", "我们是"}, {"jintianwanshang", "今天晚上"}, {"mingtianjian", "明天见"},
                {"womenqunali", "我们去哪里"}, {"nizainali", "你在哪里"}, {"wozhidaole", "我知道了"},
                {"qingwen", "请问"}, {"xiawujian", "下午见"}, {"haode", "好的"}, {"buyongxie", "不用谢"},
                {"shengrikuaile", "生日快乐"}, {"gongxifacai", "恭喜发财"}, {"beijing", "北京"},
                {"shanghai", "上海"}, {"pengyou", "朋友"}, {"gongzuo", "工作"}, {"dianhua", "电话"},
                {"xianzai", "现在"}, {"keyi", "可以"}, {"zhidao", "知道"},
        };
        List<String> misses = new ArrayList<>();
        for (String[] row : corpus) {
            String typed = PinyinDigitIndex.project(row[0]);
            List<String> words = texts(composer.candidates(typed, 30));
            List<String> topFive = words.subList(0, Math.min(5, words.size()));
            if (!topFive.contains(row[1])) {
                misses.add(row[0] + " wanted " + row[1] + ", got " + topFive);
            }
        }
        assertTrue(String.join("\n", misses), corpus.length - misses.size() >= 28);
    }

    // ----------------------------------------------------------- the display

    @Test
    public void displayReadsTheDigitsAsTheTopSpelling() {
        assertEquals("ni'hao", keypad.display("64426", top("64426")));
        assertEquals("zhong'guo", keypad.display("94664486", top("94664486")));
        // A pinned syllable keeps its apostrophe.
        assertEquals("ni'hao", keypad.display("ni'426", top("ni'426")));
        // Mid-word: the completion's spelling is cut to what was typed.
        String partial = keypad.display("6442", top("6442"));
        assertEquals(5, partial.length());
        assertTrue(partial, partial.startsWith("ni'"));
    }

    @Test
    public void syllablesSplitWithTheFewestCuts() {
        assertEquals(java.util.Arrays.asList("ni", "hao"), keypad.syllablesOf("nihao"));
        assertEquals(java.util.Collections.singletonList("xian"), keypad.syllablesOf("xian"));
        assertEquals(java.util.Arrays.asList("zhong", "guo"), keypad.syllablesOf("zhongguo"));
    }

    // -------------------------------------------------------- pins and cuts

    @Test
    public void pinnedLettersFilterTheCandidates() {
        // 64 pinned as mi: 你 goes, 米 leads.
        List<String> words = texts(composer.candidates("mi'", 10));
        assertFalse(words.contains("你"));
        assertEquals("米", words.get(0));
        // 7426 pinned as qian: money, not mountains.
        List<String> qian = texts(composer.candidates("qian'", 10));
        assertEquals("钱", qian.get(0));
        assertFalse(qian.contains("山"));
    }

    @Test
    public void anApostropheIsAHardSyllableBreak() {
        // xian with a cut after xi is xi'an (西安), never 先.
        assertTrue(keypad.cutsAt("xian", new int[]{2}));
        assertFalse(keypad.cutsAt("xian", new int[]{1}));
        List<String> words = texts(composer.candidates("94'26", 10));
        assertFalse(words.toString(), words.contains("先"));
        assertTrue(words.toString(), words.contains("西安"));
        assertEquals("xi'an", keypad.display("94'26", top("94'26")));
    }

    @Test
    public void oneCharacterIsOneSyllableInTheSpelling() {
        PinyinCandidate xian = null;
        for (PinyinCandidate candidate : composer.candidates("9426", 30)) {
            if ("西安".equals(candidate.text)) {
                xian = candidate;
            }
        }
        assertNotNull(xian);
        assertEquals(java.util.Arrays.asList("xi", "an"), keypad.spellingSyllables(xian.spelling, xian.text));
        assertEquals(java.util.Collections.singletonList("xian"), keypad.spellingSyllables("xian", "先"));
    }

    @Test
    public void theStartOfARunIsOfferedOnItsOwn() {
        // 64426: 你好 first, and 你 still there to commit alone.
        List<PinyinCandidate> found = composer.candidates("64426", 30);
        assertEquals("你好", found.get(0).text);
        PinyinCandidate you = null;
        for (PinyinCandidate candidate : found) {
            if ("你".equals(candidate.text)) {
                you = candidate;
            }
        }
        assertNotNull(texts(found).toString(), you);
        assertEquals(2, you.consumed);
    }

    // Codex review, 0.8.7: three ways a pin could be ignored.

    /** 2482 pinned as chua: one syllable, never 出 + 啊, and 出 alone is not offered. */
    @Test
    public void aPinnedSyllableIsNeverSplit() {
        List<PinyinCandidate> found = composer.candidates("chua'", 30);
        for (PinyinCandidate candidate : found) {
            assertFalse(candidate + " splits chua", candidate.text.startsWith("出"));
            assertEquals(1, candidate.text.codePointCount(0, candidate.text.length()));
        }
        if (!found.isEmpty()) {
            assertEquals("chua", keypad.display("chua'", found.get(0)).replace("'", ""));
            assertEquals("chua'", keypad.display("chua'", found.get(0)));
        }
    }

    /** 746426 pinned as pin: 平安 is ping'an and crosses the pin, so it goes. */
    @Test
    public void charactersDecideWhereAWordsSyllablesBreak() {
        List<String> words = texts(composer.candidates("pin'426", 30));
        assertFalse(words.toString(), words.contains("平安"));
        PinyinCandidate first = top("746426");
        if ("平安".equals(first.text)) {
            assertEquals("ping'an", keypad.display("746426", first));
        }
        assertEquals(java.util.Arrays.asList("ping", "an"), keypad.spellingSyllables("pingan", "平安"));
    }

    /** 36 pinned as fo: 否 (fou) runs past the break and is not offered. */
    @Test
    public void aTrailingBreakHoldsForCompletionsToo() {
        List<String> words = texts(composer.candidates("fo'", 30));
        assertFalse(words.toString(), words.contains("否"));
        assertFalse(words.toString(), words.contains("否则"));
        assertTrue(words.toString(), words.contains("佛"));
    }

    @Test
    public void normalizeKeepsDigitsLettersAndSingleApostrophes() {
        assertEquals("ni'426", keypad.normalize("ni'426", 32));
        assertEquals("64'2", keypad.normalize("'64''2", 32));
        assertEquals("642", keypad.normalize("6-4#2 01", 32));
        assertEquals("ni", keypad.normalize("NI", 32));
    }

    // -------------------------------------------------------------- session

    @Test
    public void sessionPinsASpellingAndCommitsPartOfTheRun() {
        PinyinSession session = new PinyinSession(composer);
        assertTrue(session.isKeypad());
        for (char c : "64426".toCharArray()) {
            assertTrue(session.append(c));
        }
        assertEquals("ni'hao", session.composingText());
        assertEquals("你好", session.candidates().get(0).text);

        // Pin ni, then commit 你 alone: hao stays for the next choice.
        int ni = labels(session.spellingOptions()).indexOf("ni");
        assertTrue(ni >= 0);
        assertTrue(session.pinSpelling(ni));
        assertEquals("ni'426", session.rawText());
        int you = texts(session.candidates()).indexOf("你");
        assertTrue(texts(session.candidates()).toString(), you >= 0);
        PinyinSession.Settlement first = session.select(you);
        assertEquals(PinyinSession.Action.COMMIT, first.action);
        assertEquals("你", first.text);
        assertTrue(session.isComposing());
        assertEquals("426", session.rawText());
        assertEquals("好", session.candidates().get(0).text);
        PinyinSession.Settlement second = session.select(0);
        assertEquals("好", second.text);
        assertFalse(session.isComposing());
    }

    @Test
    public void theOneKeySeparatesAndIsIgnoredWhereItMeansNothing() {
        PinyinSession session = new PinyinSession(composer);
        assertFalse("nothing to separate yet", session.appendSeparator());
        session.append('9');
        session.append('4');
        assertTrue(session.appendSeparator());
        assertTrue(session.appendSeparator());
        assertEquals("94'", session.rawText());
        session.append('2');
        session.append('6');
        assertTrue(texts(session.candidates()).contains("西安"));
        assertFalse(texts(session.candidates()).contains("先"));
    }

    @Test
    public void spelledLettersCommitAsLatin() {
        PinyinSession session = new PinyinSession(composer);
        for (char c : "65".toCharArray()) {
            session.append(c);
        }
        PinyinSession.Settlement latin = session.selectSpelledLetters();
        assertEquals(PinyinSession.Action.COMMIT, latin.action);
        assertEquals(2, latin.text.length());
        assertFalse(latin.text.contains("'"));
        assertFalse(session.isComposing());
    }

    @Test
    public void backspaceUndoesAPinOneCharacterAtATime() {
        PinyinSession session = new PinyinSession(composer);
        session.append('6');
        session.append('4');
        assertTrue(session.pinSpelling(labels(session.spellingOptions()).indexOf("mi")));
        assertEquals("mi'", session.rawText());
        assertTrue(session.backspace());
        assertEquals("mi", session.rawText());
        assertTrue(session.backspace());
        assertTrue(session.backspace());
        assertFalse(session.isComposing());
        assertTrue(session.spellingOptions().isEmpty());
        assertEquals("", session.spellingDisplay());
    }

    @Test
    public void qwertySessionsAreUntouched() {
        PinyinSession session = new PinyinSession(new PinyinComposer(dictionary));
        assertFalse(session.isKeypad());
        for (char c : "nihao".toCharArray()) {
            session.append(c);
        }
        assertEquals("nihao", session.composingText());
        assertTrue(session.spellingOptions().isEmpty());
        assertEquals("你好", session.candidates().get(0).text);
        assertNotNull(session.candidates().get(0).spelling);
    }

    @Test
    public void keystrokesStayFastWithPins() {
        String typed = "ni'" + PinyinDigitIndex.project("haowomenjintianquchifan");
        long worst = 0;
        for (int i = 1; i <= typed.length(); i++) {
            long start = System.nanoTime();
            List<PinyinCandidate> found = composer.candidates(typed.substring(0, i), 30);
            if (!found.isEmpty()) {
                keypad.spellingOptions(typed.substring(0, i), found.get(0));
            }
            worst = Math.max(worst, (System.nanoTime() - start) / 1000);
        }
        assertTrue("worst pinned keystroke was " + worst + "us", worst < 60000);
    }
}
