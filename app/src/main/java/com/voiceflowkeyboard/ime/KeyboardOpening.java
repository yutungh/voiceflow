package com.voiceflowkeyboard.ime;

import android.text.InputType;

/**
 * 0.9.3: which letter keyboard a new field (or the keyboard reopening) starts on.
 *
 * <p>By default the one last chosen: English, Chinese full keys or the Chinese
 * nine-key pad, remembered across the keyboard closing and the service
 * restarting. "Always English" is the 0.8.8 behaviour. A field that only takes
 * Latin text (a password, an email address, a web address) or no text at all (a
 * number or phone field) opens in English, as before, and choosing a keyboard
 * there is not remembered. Without Chinese input there is only English.
 */
final class KeyboardOpening {
    /** {@link Prefs#keyboardOpenOn}: the keyboard last chosen (the default). */
    static final String OPEN_LAST_USED = "last";
    /** {@link Prefs#keyboardOpenOn}: always English (as from 0.8.8 to 0.9.2). */
    static final String OPEN_ENGLISH = "english";

    static final String REMEMBER_ENGLISH = "english";

    private KeyboardOpening() {
    }

    static String sanitizeOpenOn(String value) {
        return OPEN_ENGLISH.equals(value) ? OPEN_ENGLISH : OPEN_LAST_USED;
    }

    /** A field where only Latin text, or no text, is typed: never opened in Chinese. */
    static boolean latinOnlyField(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return true;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
    }

    /**
     * Whether input starting on a field is a new field (so the opening keyboard
     * applies): not a restart of the same field, which keeps the keyboard on screen.
     */
    static boolean isNewField(boolean restarting, String fieldKey, String lastFieldKey) {
        return !restarting && !fieldKey.equals(lastFieldKey);
    }

    /** The keyboard to open on. {@code remembered}: {@link #remember}'s value, or null. */
    static InputMode choose(String openOn, int inputType, boolean chineseAvailable, String remembered) {
        if (!chineseAvailable || OPEN_ENGLISH.equals(sanitizeOpenOn(openOn)) || latinOnlyField(inputType)) {
            return InputMode.ENGLISH;
        }
        if (Prefs.CHINESE_LAYOUT_KEYPAD.equals(remembered)) {
            return InputMode.CHINESE_KEYPAD;
        }
        if (Prefs.CHINESE_LAYOUT_QWERTY.equals(remembered)) {
            return InputMode.CHINESE_QWERTY;
        }
        return InputMode.ENGLISH;
    }

    /** The value to remember for a keyboard chosen in a field of {@code inputType}; null: remember nothing. */
    static String remember(InputMode mode, int inputType) {
        if (mode == null || latinOnlyField(inputType)) {
            return null;
        }
        return mode.isChinese() ? mode.prefsLayout() : REMEMBER_ENGLISH;
    }
}
