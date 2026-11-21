package com.voiceflowkeyboard.ime;

/** One choice offered for the current pinyin buffer. */
final class PinyinCandidate {

    /** The Hanzi to commit. */
    final String text;

    /**
     * How many characters of the raw pinyin buffer this candidate consumes.
     * Equal to the whole buffer for a full match or a completion; shorter when
     * the candidate only covers a leading part of what was typed.
     */
    final int consumed;

    /**
     * True when the candidate spells out more syllables than the user has typed
     * so far, i.e. it is a completion rather than an exact reading.
     */
    final boolean completion;

    final double score;

    /**
     * The letters this reading spells, one dictionary key per word with an
     * apostrophe between words ("ni'hao" for 你+好, "nihao" for 你好), or "" when
     * the lookup did not say. On the 9-key layout this is what the digits
     * turned out to mean.
     */
    final String spelling;

    PinyinCandidate(String text, int consumed, boolean completion, double score) {
        this(text, consumed, completion, score, "");
    }

    PinyinCandidate(String text, int consumed, boolean completion, double score, String spelling) {
        this.text = text;
        this.consumed = consumed;
        this.completion = completion;
        this.score = score;
        this.spelling = spelling == null ? "" : spelling;
    }

    @Override
    public String toString() {
        return text + "(" + consumed + (completion ? ",completion" : "") + ")";
    }
}
