package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 0.9.4: the style follows the letter keyboard; the pure rules. Style ids are invented. */
public class KeyboardStylesTest {
    private static final List<String> KNOWN = Arrays.asList("clean", "casual", "business", "list_mixed-example");

    @Test
    public void theChineseKeyboardStartsOnItsOwnStyleAndEnglishOnTheDefault() {
        assertEquals("list_mixed-example", KeyboardStyles.effective(true, "clean", "list_mixed-example", KNOWN));
        assertEquals("clean", KeyboardStyles.effective(false, "clean", "list_mixed-example", KNOWN));
    }

    @Test
    public void withoutASettingTheChineseKeyboardIsTheSameAsEnglish() {
        assertEquals("clean", KeyboardStyles.effective(true, "clean", "", KNOWN));
        assertEquals("clean", KeyboardStyles.effective(true, "clean", null, KNOWN));
        assertEquals("clean", KeyboardStyles.effective(true, "clean", "  ", KNOWN));
        assertEquals(KeyboardStyles.Status.SAME_AS_ENGLISH, KeyboardStyles.status("", KNOWN));
    }

    @Test
    public void aMissingStyleFallsBackToEnglishAndIsShownAsMissing() {
        assertEquals("clean", KeyboardStyles.effective(true, "clean", "list_gone-example", KNOWN));
        assertEquals(KeyboardStyles.Status.MISSING, KeyboardStyles.status("list_gone-example", KNOWN));
        assertEquals(KeyboardStyles.Status.SET, KeyboardStyles.status("business", KNOWN));
    }

    @Test
    public void theBarLeadsWithTheEnglishThenTheChineseStyle() {
        List<String> onBar = Arrays.asList("casual", "clean", "business");
        assertEquals(Arrays.asList("clean", "list_mixed-example", "casual", "business"),
                KeyboardStyles.barOrder(onBar, "clean", "list_mixed-example", KNOWN));
        // Without a Chinese style the bar is her pins as before.
        assertSame(onBar, KeyboardStyles.barOrder(onBar, "clean", null, KNOWN));
        assertSame(onBar, KeyboardStyles.barOrder(onBar, "clean", "list_gone-example", KNOWN));
        // The same style on both keyboards is on the bar once.
        assertEquals(Arrays.asList("business", "casual", "clean"),
                KeyboardStyles.barOrder(onBar, "business", "business", KNOWN));
    }

    @Test
    public void theBarsChineseStyleNeedsChineseInputAndAKnownStyle() {
        assertEquals("business", KeyboardStyles.barChinese(true, "business", KNOWN));
        assertNull(KeyboardStyles.barChinese(false, "business", KNOWN));
        assertNull(KeyboardStyles.barChinese(true, "", KNOWN));
        assertNull(KeyboardStyles.barChinese(true, "list_gone-example", KNOWN));
        assertTrue(KeyboardStyles.fixedOnBar("clean", "clean", "business"));
        assertTrue(KeyboardStyles.fixedOnBar("business", "clean", "business"));
        assertFalse(KeyboardStyles.fixedOnBar("casual", "clean", "business"));
        assertFalse("no Chinese style: every pin works", KeyboardStyles.fixedOnBar("clean", "clean", null));
    }

    @Test
    public void rememberIsTheDefaultChoiceMode() {
        assertEquals(KeyboardStyles.CHOICE_REMEMBER, KeyboardStyles.sanitizeChoiceMode(null));
        assertEquals(KeyboardStyles.CHOICE_REMEMBER, KeyboardStyles.sanitizeChoiceMode("odd"));
        assertEquals(KeyboardStyles.CHOICE_ONE_MESSAGE, KeyboardStyles.sanitizeChoiceMode("message"));
    }
}
