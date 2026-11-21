package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** The language key's choices, 0.8.8: toggle, cycle and list. */
public class LanguageKeyTest {

    private static final InputMode EN = InputMode.ENGLISH;
    private static final InputMode FULL = InputMode.CHINESE_QWERTY;
    private static final InputMode NINE = InputMode.CHINESE_KEYPAD;

    @Test public void toggleGoesBetweenEnglishAndTheSecondKeyboard() {
        LanguageKey.Behaviour toggle = LanguageKey.Behaviour.TOGGLE;
        assertEquals(NINE, LanguageKey.onTap(toggle, EN, NINE, true));
        assertEquals(EN, LanguageKey.onTap(toggle, NINE, NINE, true));
        assertEquals(FULL, LanguageKey.onTap(toggle, EN, FULL, true));
        assertEquals(EN, LanguageKey.onTap(toggle, FULL, FULL, true));
        // On the Chinese keyboard that is not the second one (cycled to it, say), English comes back.
        assertEquals(EN, LanguageKey.onTap(toggle, FULL, NINE, true));
    }

    @Test public void toggleNeverMakesEnglishTheSecondKeyboard() {
        assertEquals(FULL, LanguageKey.onTap(LanguageKey.Behaviour.TOGGLE, EN, EN, true));
        assertEquals(FULL, LanguageKey.onTap(LanguageKey.Behaviour.TOGGLE, EN, null, true));
    }

    @Test public void cycleVisitsEveryKeyboardEnglishFirst() {
        LanguageKey.Behaviour cycle = LanguageKey.Behaviour.CYCLE;
        assertEquals(FULL, LanguageKey.onTap(cycle, EN, NINE, true));
        assertEquals(NINE, LanguageKey.onTap(cycle, FULL, NINE, true));
        assertEquals(EN, LanguageKey.onTap(cycle, NINE, NINE, true));
    }

    @Test public void listMeansATapOpensTheList() {
        assertNull(LanguageKey.onTap(LanguageKey.Behaviour.LIST, EN, NINE, true));
        assertNull(LanguageKey.onTap(LanguageKey.Behaviour.LIST, NINE, NINE, true));
    }

    @Test public void withChineseOffATapStaysOnEnglish() {
        for (LanguageKey.Behaviour behaviour : LanguageKey.Behaviour.values()) {
            assertEquals(EN, LanguageKey.onTap(behaviour, EN, NINE, false));
        }
        assertEquals(Arrays.asList(EN), LanguageKey.keyboards(false));
        assertEquals(Arrays.asList(EN, FULL, NINE), LanguageKey.keyboards(true));
    }

    @Test public void toggleIsTheDefaultAndUnknownValuesFallBackToIt() {
        assertEquals(LanguageKey.Behaviour.TOGGLE, LanguageKey.Behaviour.fromPrefs(""));
        assertEquals(LanguageKey.Behaviour.TOGGLE, LanguageKey.Behaviour.fromPrefs(null));
        assertEquals(LanguageKey.Behaviour.TOGGLE, LanguageKey.Behaviour.fromPrefs("sideways"));
        for (LanguageKey.Behaviour behaviour : LanguageKey.Behaviour.values()) {
            assertEquals(behaviour, LanguageKey.Behaviour.fromPrefs(behaviour.prefsValue));
        }
    }

    @Test public void theKeyShowsTheKeyboardOnScreen() {
        assertEquals("EN", EN.keyLabel());
        assertEquals("中", FULL.keyLabel());
        assertEquals("九", NINE.keyLabel());
    }

    @Test public void settingsDescribeEachBehaviour() {
        assertEquals("Toggle English and 九宫格", LanguageKey.describe(LanguageKey.Behaviour.TOGGLE, NINE));
        assertEquals("Cycle through all", LanguageKey.describe(LanguageKey.Behaviour.CYCLE, NINE));
        assertEquals("Always show the list", LanguageKey.describe(LanguageKey.Behaviour.LIST, NINE));
        assertTrue(LanguageKey.contentDescription(LanguageKey.Behaviour.TOGGLE, EN, NINE, true)
                .contains("Tap for 中文 九宫格"));
        assertTrue(LanguageKey.contentDescription(LanguageKey.Behaviour.LIST, EN, NINE, true)
                .contains("Tap to choose"));
    }
}
