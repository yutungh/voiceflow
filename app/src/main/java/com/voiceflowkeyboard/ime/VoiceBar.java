package com.voiceflowkeyboard.ime;

/**
 * Which bar the keyboard shows above the keys, decided from the service's own
 * state. Pure, so every rule is unit-tested; the service owns the state and
 * renders the result.
 */
final class VoiceBar {
    /** How long the "Inserted" bar with Undo, Original and Feedback stays. */
    static final long INSERTED_MS = 8_000L;
    /** How long a one-line message stays in the bar before the style chip returns. */
    static final long MESSAGE_MS = 4_000L;
    /** Below this width the bar's buttons drop their words and keep their icons. */
    static final int COMPACT_WIDTH_DP = 380;

    enum Mode {
        /** Style chip, Dictate, History and the overflow menu. */
        IDLE,
        /** Typing suggestions or a Paste offer, with a compact Dictate button. */
        SUGGESTIONS,
        /** Pinyin candidates: the whole width, nothing else. */
        CANDIDATES,
        /** Red dot, "Listening", the waveform, the timer, Cancel and Stop. */
        LISTENING,
        /** "Transcribing", then "Tidying up", always in words. */
        WORKING,
        /** A tick, "Inserted · style · expression", Undo, Original, Feedback. */
        INSERTED,
        /** His own words were kept (mixed languages): Use cleaned, Feedback. */
        KEPT_WORDS,
        /** The history list is open in place of the keys. */
        HISTORY,
        /** Choosing a style to retone the last insertion. */
        RETONE
    }

    /** Everything the decision reads, as the service holds it right now. */
    static final class State {
        boolean recording;
        boolean processing;
        boolean historyMode;
        boolean retoneMode;
        boolean composingCandidates;
        /** A held-back tidy-up is on offer for the last insertion. */
        boolean keptWords;
        /** The last insertion is still verified and inside its Inserted window. */
        boolean insertedFresh;
        /** A short message is waiting to be read in place of the style chip. */
        boolean message;
        boolean hasSuggestions;
    }

    private VoiceBar() {
    }

    static Mode mode(State s) {
        if (s.recording) {
            return Mode.LISTENING;
        }
        if (s.processing) {
            return Mode.WORKING;
        }
        if (s.retoneMode) {
            return Mode.RETONE;
        }
        if (s.historyMode) {
            return Mode.HISTORY;
        }
        // While composing, the strip is the only way to choose a character.
        if (s.composingCandidates) {
            return Mode.CANDIDATES;
        }
        if (s.keptWords) {
            return Mode.KEPT_WORDS;
        }
        if (s.insertedFresh) {
            return Mode.INSERTED;
        }
        // A message (a result, or why Dictate did nothing) is read before typing resumes;
        // typing clears it.
        if (s.message) {
            return Mode.IDLE;
        }
        if (s.hasSuggestions) {
            return Mode.SUGGESTIONS;
        }
        return Mode.IDLE;
    }

    /** "Clean · Natural": the style chip's words and the Inserted bar's second line. */
    static String styleLabel(String styleName, int expression) {
        return styleName + " · " + Prefs.expressionLabel(expression);
    }

    /**
     * Routine states the bar does not announce as a message: the keys already
     * show them (shift, layers) or they are the bar's own state.
     */
    static boolean routineStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return true;
        }
        switch (status) {
            case "Ready":
            case "Shift on":
            case "Caps lock":
            case "Symbols":
            case "More symbols":
            case "Numbers":
            case "History":
            case "Emoji":
            case "Pasted":
                return true;
            default:
                return status.startsWith("Recording:") || status.startsWith("Preset:");
        }
    }

    /** The listening label for each kind of capture. */
    static String listeningLabel(boolean translation, boolean creation, boolean instruction) {
        if (translation) {
            return "Translating";
        }
        if (creation) {
            return "Creating";
        }
        if (instruction) {
            return "Instruction";
        }
        return "Listening";
    }

    /** m:ss for the recording timer. */
    static String elapsed(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }
}
