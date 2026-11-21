package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The composing half of Chinese input: what has been typed but not yet turned
 * into Hanzi.
 *
 * <p>This exists as its own object because ending a composition is a
 * transaction with many entry points — space, punctuation, return, switching
 * layout or language, starting a recording, the cursor moving away, the editor
 * being torn down, the keyboard being rebuilt on rotation. Letting each of those
 * call sites do its own partial cleanup is how composing text leaks into other
 * apps, which is the classic IME bug. They all go through {@link #settle}
 * instead, which decides the outcome and clears the session atomically.
 *
 * <p>The session never touches an InputConnection. It returns a {@link Settlement}
 * describing what should happen and lets the service apply it, which keeps this
 * class free of Android types and unit testable.
 */
final class PinyinSession {

    /** Why a composition is ending. Determines what happens to the buffer. */
    enum SettleReason {
        /** Space, punctuation, or return: take the best reading. */
        ACCEPT_TOP,
        /** Switching language or layout: take the best reading rather than lose it. */
        MODE_CHANGED,
        /** A recording is starting; the buffer must not interleave with the transcript. */
        RECORDING_STARTED,
        /** Cursor moved out of the composing span; leave what is on screen alone. */
        CURSOR_MOVED,
        /** User backed out of the whole buffer, or explicitly cancelled. */
        CANCELLED,
        /**
         * The editor is going away or has already changed. Nothing may be written:
         * committing here can land text in the wrong field.
         */
        EDITOR_GONE
    }

    enum Action {
        /** Nothing was composing; the caller should carry on unchanged. */
        NOTHING,
        /** Replace the composing span with {@link Settlement#text}. */
        COMMIT,
        /** Finish composing and leave the raw letters the user typed in place. */
        KEEP_RAW,
        /** Remove the composing span entirely. */
        DISCARD,
        /** Drop internal state only; do not touch any input connection. */
        CLEAR_STATE_ONLY
    }

    static final class Settlement {
        final Action action;
        final String text;

        private Settlement(Action action, String text) {
            this.action = action;
            this.text = text;
        }

        static final Settlement NOTHING = new Settlement(Action.NOTHING, "");

        boolean isNothing() {
            return action == Action.NOTHING;
        }
    }

    /** How many candidates to surface; the strip scrolls, so this is generous. */
    private static final int CANDIDATE_LIMIT = 30;

    private PinyinConverter composer;
    private final StringBuilder buffer = new StringBuilder();
    private List<PinyinCandidate> candidates = Collections.emptyList();
    /** 9-key only: the buffer read as letters, and the spellings she can pin. */
    private String display = "";
    private List<PinyinKeypadLookup.SpellingOption> spellingOptions = Collections.emptyList();

    PinyinSession(PinyinConverter composer) {
        this.composer = composer;
    }

    /**
     * Swaps the search used for subsequent input, as when moving between the
     * QWERTY and 9-key layouts. The caller is expected to have settled first —
     * a half-typed buffer means nothing in the other mode.
     */
    void setComposer(PinyinConverter composer) {
        this.composer = composer;
        refresh();
    }

    boolean isComposing() {
        return buffer.length() > 0;
    }

    /** The raw letters or digits typed so far, shown as composing text. */
    String rawText() {
        return buffer.toString();
    }

    List<PinyinCandidate> candidates() {
        return candidates;
    }

    /** True when this session reads a nine-key (九宫格) buffer. */
    boolean isKeypad() {
        return keypad() != null;
    }

    private PinyinKeypadLookup keypad() {
        if (composer == null) {
            return null;
        }
        PinyinLookup lookup = composer.lookup();
        return lookup instanceof PinyinKeypadLookup ? (PinyinKeypadLookup) lookup : null;
    }

    /**
     * What the editor shows while composing: the raw letters on QWERTY, the
     * spelling ("ni'hao") on the 9-key pad, where the digits mean nothing to read.
     */
    String composingText() {
        return isKeypad() && !display.isEmpty() ? display : rawText();
    }

    /** 9-key: the spelling of the top reading, for the strip over the candidates. */
    String spellingDisplay() {
        return display;
    }

    /** 9-key: the spellings the first undecided digits could be (64: ni, mi, m, n, o). */
    List<PinyinKeypadLookup.SpellingOption> spellingOptions() {
        return spellingOptions;
    }

    /**
     * 9-key: pins the spelling at {@code index}, so the candidates that follow
     * all read it that way.
     *
     * @return false when there is no such option
     */
    boolean pinSpelling(int index) {
        if (index < 0 || index >= spellingOptions.size()) {
            return false;
        }
        PinyinKeypadLookup.SpellingOption option = spellingOptions.get(index);
        if (option.start < 0 || option.end > buffer.length() || option.start >= option.end) {
            return false;
        }
        String candidate = buffer.substring(0, option.start) + option.replacement + buffer.substring(option.end);
        String normalized = composer.lookup().normalize(candidate, PinyinComposer.MAX_INPUT_LENGTH);
        if (!normalized.equals(candidate)) {
            // Too long once the apostrophe is added, or doubled up: keep it as is.
            return false;
        }
        buffer.setLength(0);
        buffer.append(candidate);
        refresh();
        return true;
    }

    /**
     * Types one 9-key apostrophe (the 1 key, 分词), a hard syllable break.
     * Ignored at the start of the buffer and after another apostrophe.
     *
     * @return true when the key was absorbed (even if it changed nothing)
     */
    boolean appendSeparator() {
        if (composer == null || buffer.length() == 0) {
            return false;
        }
        if (buffer.charAt(buffer.length() - 1) == PinyinKeypadLookup.SEPARATOR
                || buffer.length() + 1 > PinyinComposer.MAX_INPUT_LENGTH) {
            return true;
        }
        buffer.append(PinyinKeypadLookup.SEPARATOR);
        refresh();
        return true;
    }

    /**
     * 9-key: commits the spelled letters as Latin text ("nihao"), for a word
     * that is not Chinese. Digits nothing reads stay digits.
     */
    Settlement selectSpelledLetters() {
        if (buffer.length() == 0) {
            return Settlement.NOTHING;
        }
        String shown = composingText();
        StringBuilder letters = new StringBuilder(shown.length());
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            if (c != PinyinKeypadLookup.SEPARATOR) {
                letters.append(c);
            }
        }
        clear();
        return new Settlement(Action.COMMIT, letters.toString());
    }

    /**
     * Whether accepting this composition should keep the literal Latin input.
     * No candidates is an automatic fallback; uppercase is an explicit signal
     * for an abbreviation such as "JR", even if lowercase "jr" happens to
     * produce a partial pinyin reading.
     */
    boolean shouldAcceptRaw() {
        if (buffer.length() == 0) {
            return false;
        }
        if (candidates.isEmpty()) {
            return true;
        }
        for (int i = 0; i < buffer.length(); i++) {
            if (Character.isUpperCase(buffer.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Best reading, or null when the buffer resolves to nothing. */
    private String topText() {
        return candidates.isEmpty() ? null : candidates.get(0).text;
    }

    /**
     * Feeds one typed character into the buffer.
     *
     * @return false when the character is not usable in this mode, in which case
     *     the caller should handle the key normally
     */
    boolean append(char typed) {
        if (composer == null) {
            return false;
        }
        String candidateBuffer = buffer.toString() + typed;
        if (candidateBuffer.length() > PinyinComposer.MAX_INPUT_LENGTH) {
            return false;
        }
        buffer.append(typed);
        refresh();
        return true;
    }

    /**
     * Handles a backspace.
     *
     * @return true when the buffer absorbed it; false when there was nothing
     *     composing and the caller should delete real text instead
     */
    boolean backspace() {
        if (buffer.length() == 0) {
            return false;
        }
        buffer.deleteCharAt(buffer.length() - 1);
        refresh();
        return true;
    }

    /**
     * Commits the candidate at {@code index}. When it only covers part of the
     * buffer the remainder stays composing, which is how a long run is committed
     * piece by piece.
     */
    Settlement select(int index) {
        if (index < 0 || index >= candidates.size()) {
            return Settlement.NOTHING;
        }
        PinyinCandidate chosen = candidates.get(index);
        int consumed = Math.min(Math.max(chosen.consumed, 0), buffer.length());
        // Tell the engine first (Rime learns from it), while its list is still the one shown.
        composer.chosen(composer.lookup().normalize(buffer.substring(0, consumed), PinyinComposer.MAX_INPUT_LENGTH), chosen);
        buffer.delete(0, consumed);
        // What is left starts a new word; a 9-key apostrophe in front of it
        // separated it from the part just committed.
        while (buffer.length() > 0 && buffer.charAt(0) == PinyinKeypadLookup.SEPARATOR) {
            buffer.deleteCharAt(0);
        }
        refresh();
        return new Settlement(Action.COMMIT, chosen.text);
    }

    /** Commits the composing buffer exactly as typed, including capitalization. */
    Settlement selectRaw() {
        if (buffer.length() == 0) {
            return Settlement.NOTHING;
        }
        String raw = buffer.toString();
        clear();
        return new Settlement(Action.COMMIT, raw);
    }

    /** Ends the composition, clearing the session whatever the outcome. */
    Settlement settle(SettleReason reason) {
        if (buffer.length() == 0) {
            clear();
            return Settlement.NOTHING;
        }
        String raw = buffer.toString();
        String top = topText();
        boolean acceptRaw = shouldAcceptRaw();
        clear();

        switch (reason) {
            case EDITOR_GONE:
                return new Settlement(Action.CLEAR_STATE_ONLY, "");
            case CANCELLED:
                return new Settlement(Action.DISCARD, "");
            case CURSOR_MOVED:
                return new Settlement(Action.KEEP_RAW, raw);
            case ACCEPT_TOP:
            case MODE_CHANGED:
            case RECORDING_STARTED:
            default:
                // Falling back to the raw letters rather than dropping them keeps
                // a mistyped run recoverable instead of silently vanishing.
                return acceptRaw || top == null
                        ? new Settlement(Action.KEEP_RAW, raw)
                        : new Settlement(Action.COMMIT, top);
        }
    }

    private void clear() {
        buffer.setLength(0);
        candidates = Collections.emptyList();
        display = "";
        spellingOptions = Collections.emptyList();
    }

    private void refresh() {
        if (buffer.length() == 0 || composer == null) {
            candidates = Collections.emptyList();
            display = "";
            spellingOptions = Collections.emptyList();
            return;
        }
        String raw = buffer.toString();
        List<PinyinCandidate> found = composer.candidates(raw, CANDIDATE_LIMIT);
        candidates = found.isEmpty() ? Collections.<PinyinCandidate>emptyList() : new ArrayList<>(found);
        PinyinKeypadLookup keypad = keypad();
        if (keypad == null) {
            display = "";
            spellingOptions = Collections.emptyList();
        } else {
            PinyinCandidate top = candidates.isEmpty() ? null : candidates.get(0);
            display = keypad.display(raw, top);
            spellingOptions = keypad.spellingOptions(raw, top);
        }
    }
}
