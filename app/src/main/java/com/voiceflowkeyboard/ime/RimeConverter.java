package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.List;

/**
 * Rime behind the keyboard's candidate bar, with Classic always behind it
 * (0.9.0).
 *
 * <p>The buffer goes to Rime as it is: digits for keys, letters for a syllable
 * she pinned, apostrophes. Rime's candidates come back with how much of the
 * buffer each reads and the letters it spells, which is everything the bar,
 * the spelling strip and partial commits already use; then VoiceFlow's own
 * reranking step ({@link RimeRanking}). When Rime cannot answer a keystroke
 * (still starting, busy, failed, turned off) Classic answers that keystroke,
 * so a key is never lost and the bar is never empty because of Rime.
 *
 * <p>The 9-key spelling strip and the letters shown in the editor still come
 * from Classic's lookup, fed Rime's top candidate.
 */
final class RimeConverter implements PinyinConverter {

    private final PinyinComposer classic;
    private final RimeEngine engine;
    private final boolean keypad;
    private final boolean learning;
    /** Whether the last answer came from Rime; for tests and the gate check. */
    private boolean lastFromRime;

    RimeConverter(PinyinComposer classic, RimeEngine engine, boolean keypad, boolean learning) {
        this.classic = classic;
        this.engine = engine;
        this.keypad = keypad;
        this.learning = learning;
    }

    @Override
    public PinyinLookup lookup() {
        return classic.lookup();
    }

    PinyinComposer classic() {
        return classic;
    }

    boolean lastFromRime() {
        return lastFromRime;
    }

    @Override
    public List<PinyinCandidate> candidates(String rawInput, int limit) {
        String input = rawInput == null ? "" : classic.lookup().normalize(rawInput, PinyinComposer.MAX_INPUT_LENGTH);
        if (!input.isEmpty() && input.length() == rawInput.length()) {
            List<PinyinCandidate> found = engine.query(keypad, input, limit);
            // Empty only when nothing at all reads the buffer: Classic may still offer its partial readings.
            if (found != null && !found.isEmpty()) {
                List<PinyinCandidate> ranked = new ArrayList<>(found.size() > limit ? found.subList(0, limit) : found);
                RimeRanking.apply(ranked, input.length());
                lastFromRime = true;
                return ranked;
            }
        }
        lastFromRime = false;
        return classic.candidates(rawInput, limit);
    }

    @Override
    public void chosen(String consumedInput, PinyinCandidate candidate) {
        if (learning && lastFromRime && candidate != null) {
            engine.learn(keypad, consumedInput, candidate.text);
        }
    }
}
