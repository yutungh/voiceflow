package com.voiceflowkeyboard.ime;

import java.util.List;

/**
 * VoiceFlow's own step after Rime's ranking (0.9.0): the final-particle rule
 * the Java engine has had since 0.8.9, ported.
 *
 * <p>On the nine-key pad 62 is ma or na, and a chat message that ends there
 * means 吗 far more often than 拿 or 那. Rime ranks 可以拿 over 可以吗; the Java
 * engine's scoring gives a reading that ends the buffer on a sentence-final
 * particle a bonus, and this is the same idea for a list that comes without
 * scores: when the first candidate reads the whole buffer and does not end on
 * a particle, and one of the next few reads the whole buffer too, says the same
 * thing up to its last character, and ends on a particle, that one goes first.
 *
 * <p>Only that swap; nothing else moves. Measured on the benchmark's dev split
 * (bench/pinyin/README.md, 0.9.0). Pure Java, shared with the benchmark.
 */
final class RimeRanking {

    private RimeRanking() {
    }

    /** How far down the list a particle reading may be promoted from. */
    static final int REACH = 4;

    /**
     * The index to move to the front, or 0 when the order stays.
     *
     * @param texts candidates' texts, best first
     * @param consumed how much of the buffer each reads
     * @param length the buffer's length
     */
    static int promoted(List<String> texts, int[] consumed, int length) {
        if (texts.size() < 2 || consumed[0] != length) {
            return 0;
        }
        String top = texts.get(0);
        if (endsWithParticle(top)) {
            return 0;
        }
        int topLength = top.codePointCount(0, top.length());
        if (topLength < 2) {
            return 0;
        }
        String stem = top.substring(0, top.offsetByCodePoints(0, topLength - 1));
        for (int i = 1; i < Math.min(texts.size(), REACH + 1); i++) {
            String text = texts.get(i);
            if (consumed[i] == length
                    && text.codePointCount(0, text.length()) == topLength
                    && text.startsWith(stem)
                    && endsWithParticle(text)) {
                return i;
            }
        }
        return 0;
    }

    static boolean endsWithParticle(String word) {
        if (word.isEmpty()) {
            return false;
        }
        int last = word.codePointBefore(word.length());
        return last < 0x10000 && PinyinComposer.FINAL_PARTICLES.indexOf((char) last) >= 0;
    }

    /** Applies {@link #promoted} to a candidate list, in place. */
    static void apply(List<PinyinCandidate> candidates, int length) {
        if (candidates.size() < 2) {
            return;
        }
        int n = Math.min(candidates.size(), REACH + 1);
        java.util.List<String> texts = new java.util.ArrayList<>(n);
        int[] consumed = new int[n];
        for (int i = 0; i < n; i++) {
            texts.add(candidates.get(i).text);
            consumed[i] = candidates.get(i).consumed;
        }
        int index = promoted(texts, consumed, length);
        if (index > 0) {
            candidates.add(0, candidates.remove(index));
        }
    }
}
