package com.voiceflowkeyboard.ime;

/**
 * The style and expression for the next or current message, and whether that
 * is a one-off. The keyboard service owns one of these; it is the only copy.
 *
 * <ul>
 *   <li>A choice in the style sheet is for one message. It never writes the
 *       saved default; only {@link #makeDefault()} says what to save.</li>
 *   <li>A recording starts from the saved default unless a one-off was picked,
 *       and the choice is fixed for that message when it is processed.</li>
 *   <li>After the message, or when the keyboard goes away, the one-off ends.</li>
 * </ul>
 */
final class StyleChoice {
    String preset;
    int expression;
    boolean oneOff;

    StyleChoice(String preset, int expression) {
        this.preset = preset;
        this.expression = Prefs.sanitizeExpression(expression);
    }

    /** Picks a style for this message, at that style's own saved expression. */
    void pick(String preset, int styleExpression) {
        this.preset = preset;
        this.expression = Prefs.sanitizeExpression(styleExpression);
        this.oneOff = true;
    }

    /** Changes the expression for this message only. */
    void setExpression(int expression) {
        this.expression = Prefs.sanitizeExpression(expression);
        this.oneOff = true;
    }

    /** 0.9.3: the style {@link #startRecording} will settle on, without changing anything. */
    String presetAtStart(String defaultPreset) {
        return oneOff ? preset : defaultPreset;
    }

    /** At record start: the saved default, unless a one-off is waiting. */
    void startRecording(String defaultPreset, int defaultExpression) {
        if (!oneOff) {
            preset = defaultPreset;
            expression = Prefs.sanitizeExpression(defaultExpression);
        }
    }

    /** After the message, or when the keyboard hides: back to the saved default. */
    void reset(String defaultPreset, int defaultExpression) {
        preset = defaultPreset;
        expression = Prefs.sanitizeExpression(defaultExpression);
        oneOff = false;
    }

    /** Whether this choice differs from the saved default. */
    boolean differsFrom(String defaultPreset, int defaultExpression) {
        return !preset.equals(defaultPreset) || expression != Prefs.sanitizeExpression(defaultExpression);
    }

    /**
     * The choice becomes the default: the caller saves {@link #preset} as the
     * active style and {@link #expression} as its level. It is no longer a one-off.
     */
    void makeDefault() {
        oneOff = false;
    }
}
