package com.voiceflowkeyboard.ime;

import java.util.List;

/**
 * What {@link PinyinSession} needs from an engine (0.9.0): candidates for a
 * buffer, the lookup that reads the buffer (normalising it, and on the 9-key
 * pad showing it as letters and offering spellings), and word of what she
 * chose. {@link PinyinComposer} (Classic, pure Java) and {@link RimeConverter}
 * (Rime, with Classic behind it) both provide it.
 */
interface PinyinConverter {

    /** The lookup that reads this layout's buffer. */
    PinyinLookup lookup();

    /** Ranked candidates for {@code rawInput}, at most {@code limit}. */
    List<PinyinCandidate> candidates(String rawInput, int limit);

    /**
     * She chose {@code candidate}; {@code consumedInput} is the normalized part
     * of the buffer it read. Engines that learn use it; the default ignores it.
     */
    default void chosen(String consumedInput, PinyinCandidate candidate) {
    }
}
