package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The final-particle rule ported from the Java engine (0.9.0). */
public class RimeRankingTest {

    private static List<PinyinCandidate> list(Object... textAndConsumed) {
        List<PinyinCandidate> out = new ArrayList<>();
        for (int i = 0; i < textAndConsumed.length; i += 2) {
            out.add(new PinyinCandidate((String) textAndConsumed[i], (Integer) textAndConsumed[i + 1], false, 0));
        }
        return out;
    }

    private static List<String> texts(List<PinyinCandidate> candidates) {
        List<String> out = new ArrayList<>();
        for (PinyinCandidate c : candidates) {
            out.add(c.text);
        }
        return out;
    }

    @Test
    public void aQuestionParticleComesFirstWhenOnlyTheLastCharacterDiffers() {
        // 9-key 537462: 可以拿 / 可以吗 read the same keys.
        List<PinyinCandidate> c = list("可以拿", 6, "可以吗", 6, "可以", 4, "可", 2);
        RimeRanking.apply(c, 6);
        assertEquals(Arrays.asList("可以吗", "可以拿", "可以", "可"), texts(c));
    }

    @Test
    public void nothingMovesWhenTheTopAlreadyEndsOnAParticle() {
        List<PinyinCandidate> c = list("好吧", 4, "号码", 4);
        RimeRanking.apply(c, 4);
        assertEquals(Arrays.asList("好吧", "号码"), texts(c));
    }

    @Test
    public void aDifferentWordIsNotPromotedJustForItsParticle() {
        // 号码 and 好吗 differ before the last character: Rime's order stands.
        List<PinyinCandidate> c = list("号码", 4, "好吗", 4);
        RimeRanking.apply(c, 4);
        assertEquals(Arrays.asList("号码", "好吗"), texts(c));
    }

    @Test
    public void onlyReadingsOfTheWholeBufferCount() {
        // The top reads only part of the buffer: leave the list alone.
        List<PinyinCandidate> partial = list("可以拿", 6, "可以吗", 8);
        RimeRanking.apply(partial, 8);
        assertEquals(Arrays.asList("可以拿", "可以吗"), texts(partial));
        // The particle reading covers less than the buffer: not promoted.
        List<PinyinCandidate> shorter = list("可以拿", 6, "可以吗", 5);
        RimeRanking.apply(shorter, 6);
        assertEquals(Arrays.asList("可以拿", "可以吗"), texts(shorter));
    }

    @Test
    public void reachIsLimited() {
        List<PinyinCandidate> c = list("可以拿", 6, "a", 6, "b", 6, "c", 6, "d", 6, "可以吗", 6);
        RimeRanking.apply(c, 6);
        assertEquals("可以拿", c.get(0).text);
        List<PinyinCandidate> near = list("可以拿", 6, "a", 6, "b", 6, "c", 6, "可以吗", 6);
        RimeRanking.apply(near, 6);
        assertEquals("可以吗", near.get(0).text);
    }

    @Test
    public void singleCharactersAndShortListsAreLeftAlone() {
        List<PinyinCandidate> one = list("拿", 2, "吗", 2);
        RimeRanking.apply(one, 2);
        assertEquals("拿", one.get(0).text);
        List<PinyinCandidate> empty = new ArrayList<>();
        RimeRanking.apply(empty, 2);
        assertEquals(0, empty.size());
    }
}
