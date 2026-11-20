package com.voiceflowkeyboard.ime;

/**
 * The two deterministic typographic rules: double-space becomes a full stop,
 * and a space before closing punctuation is dropped.
 *
 * <p>Pure decisions — given what is before the cursor and the key that was
 * pressed, say what should happen. The service remains the only thing that
 * touches the {@code InputConnection}, and these stay unit-testable without an
 * editor.
 *
 * <p>Deliberately two rules and no rule framework. Every further "smart"
 * substitution (curly quotes, automatic spacing after punctuation) carries more
 * risk of fighting the user or a host editor than it repays.
 */
final class TypingRules {

    /** Punctuation that should sit tight against the word it follows. */
    private static final String CLOSING_PUNCTUATION = ".,?!";

    /**
     * Punctuation that also opens a text emoticon (":)", ":|", ";)", ":D").
     * Pressing one after "word " cannot yet tell a clause mark from an
     * emoticon, so the space is kept, and absorbed only once a following space
     * shows the mark ended the clause.
     */
    private static final String EMOTICON_OPENING_PUNCTUATION = ":;";

    /** A rewrite of the text around the cursor, or {@link #NONE}. */
    static final class Outcome {
        /** Characters to remove immediately before the cursor first. */
        final int deleteBefore;
        /** Text to commit in place of the pressed key. */
        final String insert;

        private Outcome(int deleteBefore, String insert) {
            this.deleteBefore = deleteBefore;
            this.insert = insert;
        }

        boolean applies() {
            return this != NONE;
        }

        static final Outcome NONE = new Outcome(0, "");
    }

    private TypingRules() {
    }

    /**
     * What pressing {@code separator} should actually do, given the text
     * {@code before} the cursor.
     *
     * <p>Returns {@link Outcome#NONE} when nothing clever applies and the
     * separator should simply be committed.
     *
     * <p>Both rules require a word character followed by exactly one space. That
     * single condition is what stops them running away: a third space after
     * ". " finds a space rather than a word character and does nothing, and
     * neither rule can fire at the start of a field.
     *
     * <p>A colon or semicolon is the one deferred case: "word :" stays as typed,
     * and only a space pressed straight after it closes the gap ("word: ").
     * Anything else after the mark ("word :|", "word ;)") is left alone.
     */
    static Outcome forSeparator(String separator, CharSequence before) {
        if (separator == null || before == null || before.length() < 2) {
            return Outcome.NONE;
        }
        if (" ".equals(separator) && endsWithSpacedEmoticonOpener(before)) {
            char mark = before.charAt(before.length() - 1);
            return new Outcome(2, mark + " ");
        }
        if (before.charAt(before.length() - 1) != ' ') {
            return Outcome.NONE;
        }
        if (!isWordCharacter(before.charAt(before.length() - 2))) {
            return Outcome.NONE;
        }
        if (" ".equals(separator)) {
            return new Outcome(1, ". ");
        }
        if (separator.length() == 1 && CLOSING_PUNCTUATION.indexOf(separator.charAt(0)) >= 0) {
            return new Outcome(1, separator);
        }
        return Outcome.NONE;
    }

    /** True when {@code before} ends with a word character, one space, then ':' or ';'. */
    private static boolean endsWithSpacedEmoticonOpener(CharSequence before) {
        int length = before.length();
        return length >= 3
                && EMOTICON_OPENING_PUNCTUATION.indexOf(before.charAt(length - 1)) >= 0
                && before.charAt(length - 2) == ' '
                && isWordCharacter(before.charAt(length - 3));
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '\'';
    }
}
