package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the language key does (0.8.8). English is always the main keyboard;
 * the others are the Chinese layouts when Chinese input is on.
 *
 * <ul>
 *   <li>{@link Behaviour#TOGGLE} (the default): a tap goes between English and
 *       one second keyboard, the one last picked from the list.</li>
 *   <li>{@link Behaviour#CYCLE}: a tap goes to the next keyboard, English first.</li>
 *   <li>{@link Behaviour#LIST}: a tap opens the list.</li>
 * </ul>
 *
 * A hold always opens the list. Pure Java, so the choices are unit tested
 * directly.
 */
final class LanguageKey {

    enum Behaviour {
        TOGGLE("toggle"),
        CYCLE("cycle"),
        LIST("list");

        final String prefsValue;

        Behaviour(String prefsValue) {
            this.prefsValue = prefsValue;
        }

        static Behaviour fromPrefs(String value) {
            for (Behaviour behaviour : values()) {
                if (behaviour.prefsValue.equals(value)) {
                    return behaviour;
                }
            }
            return TOGGLE;
        }
    }

    private LanguageKey() {
    }

    /** The keyboards on offer, English first, in the order a cycle visits them. */
    static List<InputMode> keyboards(boolean chineseOn) {
        if (!chineseOn) {
            return Collections.singletonList(InputMode.ENGLISH);
        }
        List<InputMode> all = new ArrayList<>(3);
        all.add(InputMode.ENGLISH);
        all.add(InputMode.CHINESE_QWERTY);
        all.add(InputMode.CHINESE_KEYPAD);
        return all;
    }

    /**
     * The keyboard a tap switches to, or null when the tap opens the list.
     * With only English on offer, a tap stays on English.
     */
    static InputMode onTap(Behaviour behaviour, InputMode current, InputMode secondary, boolean chineseOn) {
        List<InputMode> keyboards = keyboards(chineseOn);
        if (keyboards.size() < 2) {
            return InputMode.ENGLISH;
        }
        switch (behaviour) {
            case LIST:
                return null;
            case CYCLE: {
                int at = keyboards.indexOf(current);
                return keyboards.get((at + 1) % keyboards.size());
            }
            case TOGGLE:
            default:
                if (current != InputMode.ENGLISH) {
                    return InputMode.ENGLISH;
                }
                return secondary == null || secondary == InputMode.ENGLISH || !keyboards.contains(secondary)
                        ? keyboards.get(1)
                        : secondary;
        }
    }

    /** The short name on the language key: the keyboard showing now. */
    static String keyLabel(InputMode mode) {
        switch (mode) {
            case CHINESE_QWERTY:
                return "中";
            case CHINESE_KEYPAD:
                return "九";
            case ENGLISH:
            default:
                return "EN";
        }
    }

    /** The keyboard's name in the list and in Settings. */
    static String name(InputMode mode) {
        switch (mode) {
            case CHINESE_QWERTY:
                return "中文 全键 (full keyboard)";
            case CHINESE_KEYPAD:
                return "中文 九宫格 (nine-key)";
            case ENGLISH:
            default:
                return "English";
        }
    }

    /** What the key's tap does, for Settings and the list's footer. */
    static String describe(Behaviour behaviour, InputMode secondary) {
        switch (behaviour) {
            case CYCLE:
                return "Cycle through all";
            case LIST:
                return "Always show the list";
            case TOGGLE:
            default:
                return "Toggle English and " + shortName(secondary);
        }
    }

    static String shortName(InputMode mode) {
        switch (mode) {
            case CHINESE_QWERTY:
                return "全键";
            case CHINESE_KEYPAD:
                return "九宫格";
            case ENGLISH:
            default:
                return "English";
        }
    }

    /** What a screen reader says for the key. */
    static String contentDescription(Behaviour behaviour, InputMode current, InputMode secondary, boolean chineseOn) {
        String now = "Keyboard: " + name(current) + ". ";
        InputMode next = onTap(behaviour, current, secondary, chineseOn);
        if (next == null) {
            return now + "Tap to choose a keyboard";
        }
        return now + "Tap for " + name(next) + "; hold to choose";
    }
}
