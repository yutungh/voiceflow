package com.voiceflowkeyboard.ime;

import java.util.List;

/**
 * The only thing {@link PinyinComposer} needs from a dictionary: given a span of
 * typed input, what words could it spell?
 *
 * <p>This seam is what lets full-QWERTY pinyin and the 9-key keypad share one
 * search. The Viterbi pass does not care whether a span is letters ("nihao") or
 * digits ("64426") — only whether the span resolves to entries.
 */
interface PinyinLookup {

    /**
     * Strips the raw buffer down to the characters this mode understands,
     * lower-casing where relevant, capped at {@code maxLength}.
     */
    String normalize(String rawInput, int maxLength);

    /** Entries spelled exactly by {@code span}, ordered by descending frequency. */
    List<PinyinDictionary.Entry> entriesFor(String span);

    /**
     * Entries for spans that begin with {@code prefix} but are longer — the
     * completions offered mid-word. At most one per distinct key, so a short
     * prefix cannot fan out into tens of thousands of parses.
     */
    List<PinyinDictionary.Entry> completionsFor(String prefix, int limit);

    /** Longest span worth attempting; spans beyond this can never match. */
    int maxSpanLength();

    /** Sum of all entry frequencies, the unigram normaliser. */
    long totalFrequency();

    /**
     * Whether the candidates should end with the words that read just the
     * start of the buffer (你 for 64426), so a long run can be committed a word
     * at a time. The 9-key pad does this, as Sogou's does; full QWERTY keeps
     * its list as it was.
     */
    default boolean offersLeadingWords() {
        return false;
    }

    /**
     * Positions in the normalized {@code input} (0 to length) where a word may
     * start or end, or null when any position may. The 9-key pad forbids the
     * inside of a syllable she pinned whole.
     */
    default boolean[] spanBoundaries(String input) {
        return null;
    }

    /**
     * How many syllables of {@code entry}'s spelling are rarely what was meant
     * (0.8.9, nine-key): 64 is ni far more often than the interjection n plus i.
     * The composer charges each one {@code Scoring.rareSyllablePenalty}.
     */
    default int rareSyllables(PinyinDictionary.Entry entry) {
        return 0;
    }
}