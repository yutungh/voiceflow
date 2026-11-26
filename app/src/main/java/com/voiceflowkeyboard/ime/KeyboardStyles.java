package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.9.4: the style follows the letter keyboard. Pure, so the rules are unit-tested;
 * {@link Prefs} reads and writes the two slots and the service applies the result.
 *
 * <ul>
 *   <li>Two slots. English: the saved default ({@link Prefs#activePreset}). Chinese (full keys
 *       and the nine-key pad share it): "Style on the Chinese keyboard"
 *       ({@link Prefs#chineseKeyboardStyle}), empty for "Same as English".</li>
 *   <li>A dictation on the Chinese keyboard starts on the Chinese slot when it names a style
 *       this phone has, else on the English slot. A style that is gone (a word list dropped
 *       it, or the lists aren't loaded yet) is never cleared here: it shows as missing and
 *       the English style is used until it is back or replaced.</li>
 *   <li>Style choice ({@link Prefs#styleChoiceRemembers}): by default a pick is remembered
 *       for the keyboard it was made on; "Just for one message" is the 0.9.3 behaviour,
 *       where only Make default saves (to the same slot).</li>
 *   <li>The bar: when the Chinese slot names a style, the English style is first on the bar,
 *       the Chinese style second, then the other pinned styles.</li>
 * </ul>
 */
final class KeyboardStyles {
    /** {@link Prefs#styleChoiceMode}: a pick is kept for the next messages (the default). */
    static final String CHOICE_REMEMBER = "remember";
    /** {@link Prefs#styleChoiceMode}: a pick is for one message; Make default saves it (0.9.3). */
    static final String CHOICE_ONE_MESSAGE = "message";

    /** The settings row's state for the Chinese slot. */
    enum Status { SAME_AS_ENGLISH, SET, MISSING }

    private KeyboardStyles() {
    }

    static String sanitizeChoiceMode(String value) {
        return CHOICE_ONE_MESSAGE.equals(value) ? CHOICE_ONE_MESSAGE : CHOICE_REMEMBER;
    }

    static String sanitizeSlot(String value) {
        return value == null ? "" : value.trim();
    }

    /** The Chinese slot's state: unset, a style this phone has, or one it doesn't (any more). */
    static Status status(String chineseSetting, Collection<String> knownIds) {
        String id = sanitizeSlot(chineseSetting);
        if (id.isEmpty()) {
            return Status.SAME_AS_ENGLISH;
        }
        return knownIds != null && knownIds.contains(id) ? Status.SET : Status.MISSING;
    }

    /**
     * The style a dictation starts on. {@code chineseKeyboard}: the letter keyboard is Chinese
     * (and Chinese input is on).
     */
    static String effective(boolean chineseKeyboard, String english, String chineseSetting,
                            Collection<String> knownIds) {
        if (chineseKeyboard && status(chineseSetting, knownIds) == Status.SET) {
            return sanitizeSlot(chineseSetting);
        }
        return english;
    }

    /**
     * The Chinese style that orders the bar: the Chinese slot when it names a style this phone
     * has and Chinese input is on; otherwise null (the bar is the user's pins as before).
     */
    static String barChinese(boolean chineseInput, String chineseSetting, Collection<String> knownIds) {
        return chineseInput && status(chineseSetting, knownIds) == Status.SET ? sanitizeSlot(chineseSetting) : null;
    }

    /**
     * The bar's order. {@code onBar}: {@link StylePins#onBar}'s result. With a Chinese style:
     * the English style first, the Chinese style second, then the rest in their order.
     * Never stored: the user's own pins stay as they are.
     */
    static List<String> barOrder(List<String> onBar, String english, String chinese, Collection<String> knownIds) {
        if (chinese == null || chinese.isEmpty() || knownIds == null || !knownIds.contains(chinese)) {
            return onBar;
        }
        Set<String> out = new LinkedHashSet<>();
        if (english != null && knownIds.contains(english)) {
            out.add(english);
        }
        out.add(chinese);
        out.addAll(onBar);
        return new ArrayList<>(out);
    }

    /** Whether the bar puts this style in front by itself (its pin can't take it off). */
    static boolean fixedOnBar(String id, String english, String chinese) {
        return chinese != null && id != null && (id.equals(english) || id.equals(chinese));
    }
}
