package com.voiceflowkeyboard.ime;

/**
 * Where dictated text may go, decided from evidence rather than hope.
 *
 * <ul>
 *   <li>A dictation goes only into the editor it was started in: the same app,
 *       the same field and the same input session (a new session starts whenever
 *       focus moves to an editor, so two fields with the same metadata are still
 *       told apart). A result that comes back after the user moved is kept in History,
 *       never typed somewhere else.</li>
 *   <li>A replacement ("Use cleaned", Retone) replaces only the verified last
 *       insertion: same editor session, the cursor known and still right after
 *       it with nothing selected, and exactly that text before the cursor.
 *       Android's surrounding-text calls can fail or lie, so any missing piece of
 *       evidence refuses the replacement; the caller keeps the text in History.</li>
 * </ul>
 *
 * Pure: the keyboard service gathers the evidence and acts on the verdict.
 */
final class DeliveryGuard {
    enum Verdict {
        OK,
        NO_EDITOR,
        OTHER_APP,
        OTHER_FIELD,
        NOTHING_TO_REPLACE,
        SELECTION_ACTIVE,
        CURSOR_UNKNOWN,
        CURSOR_MOVED,
        TEXT_CHANGED,
        TEXT_UNKNOWN
    }

    /** An editor: its app, its field key, and the keyboard's input-session counter for it. */
    static final class Target {
        final String packageName;
        final String fieldKey;
        final int session;

        Target(String packageName, String fieldKey, int session) {
            this.packageName = packageName == null ? "" : packageName;
            this.fieldKey = fieldKey == null ? "" : fieldKey;
            this.session = session;
        }

        boolean sameEditor(Target other) {
            return other != null
                    && packageName.equals(other.packageName)
                    && fieldKey.equals(other.fieldKey)
                    && session == other.session;
        }
    }

    /** What was inserted, where. {@code selectionEnd} is -1 when the editor did not say. */
    static final class Insertion {
        final Target editor;
        final int selectionEnd;
        final String text;

        Insertion(Target editor, int selectionEnd, String text) {
            this.editor = editor;
            this.selectionEnd = selectionEnd;
            this.text = text == null ? "" : text;
        }
    }

    private DeliveryGuard() {
    }

    /** May a finished dictation be typed into the editor that is current now? */
    static Verdict mayInsert(Target frozen, Target current, boolean hasConnection) {
        if (!hasConnection || current == null || current.packageName.isEmpty()) {
            return Verdict.NO_EDITOR;
        }
        if (frozen == null || frozen.packageName.isEmpty() || !frozen.packageName.equals(current.packageName)) {
            // A recording with no known target (should not happen) is treated as moved.
            return Verdict.OTHER_APP;
        }
        return frozen.sameEditor(current) ? Verdict.OK : Verdict.OTHER_FIELD;
    }

    /**
     * May the last insertion be replaced now?
     *
     * @param selectedLength   length of the current selection, or -1 if unknown
     * @param selectionEnd     the cursor now, or -1 if unknown
     * @param textBeforeCursor what the editor reports right before the cursor; null if it failed
     */
    static Verdict mayReplace(
            Insertion insertion,
            Target current,
            int selectedLength,
            int selectionEnd,
            CharSequence textBeforeCursor
    ) {
        if (insertion == null || insertion.text.isEmpty()) {
            return Verdict.NOTHING_TO_REPLACE;
        }
        if (current == null || current.packageName.isEmpty()) {
            return Verdict.NO_EDITOR;
        }
        if (insertion.editor == null || !insertion.editor.sameEditor(current)) {
            return Verdict.OTHER_FIELD;
        }
        if (selectedLength != 0) {
            // Unknown (-1) counts as possibly selected: replacing would eat the selection.
            return Verdict.SELECTION_ACTIVE;
        }
        if (insertion.selectionEnd < 0 || selectionEnd < 0) {
            // Without positions, the same words earlier in the field would look identical.
            return Verdict.CURSOR_UNKNOWN;
        }
        if (insertion.selectionEnd != selectionEnd) {
            return Verdict.CURSOR_MOVED;
        }
        if (textBeforeCursor == null) {
            return Verdict.TEXT_UNKNOWN;
        }
        if (!insertion.text.contentEquals(textBeforeCursor)) {
            return Verdict.TEXT_CHANGED;
        }
        return Verdict.OK;
    }

    /**
     * Whether a voice edit, which rewrites the whole field, may go ahead: the same
     * editor session it was started in, and a field snapshot known to be complete
     * that still equals the text the edit was made from.
     *
     * @param completeText the whole field, or null when completeness cannot be shown
     */
    static Verdict mayRewriteField(Target frozen, Target current, String source, String completeText) {
        Verdict where = mayInsert(frozen, current, true);
        if (where != Verdict.OK) {
            return where;
        }
        if (completeText == null) {
            return Verdict.TEXT_UNKNOWN;
        }
        return completeText.equals(source) ? Verdict.OK : Verdict.TEXT_CHANGED;
    }

    /**
     * The whole field, only when two independent reads agree that it is complete:
     * an extraction that starts at offset 0, and the text before the cursor, the
     * selection and the text after it, each shorter than what was asked for.
     * Otherwise null.
     */
    static String completeField(
            CharSequence extracted,
            int extractedStartOffset,
            CharSequence before,
            CharSequence selected,
            CharSequence after,
            int requestedChars
    ) {
        if (extracted == null || extractedStartOffset != 0 || before == null || after == null) {
            return null;
        }
        if (before.length() >= requestedChars || after.length() >= requestedChars) {
            return null;
        }
        String around = before.toString() + (selected == null ? "" : selected.toString()) + after;
        return around.contentEquals(extracted) ? around : null;
    }
}
