package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 0.9.3: a new field opens on the keyboard last chosen, unless Settings says always English. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyboardOpeningTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private static final int WEB_PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    private static final int EMAIL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
    private static final int URL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
    private static final int PHONE = InputType.TYPE_CLASS_PHONE;
    private static final int MESSAGE = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
    }

    @Test
    public void aNewFieldOpensOnTheKeyboardLastChosen() {
        String last = KeyboardOpening.OPEN_LAST_USED;
        assertEquals(InputMode.CHINESE_QWERTY, KeyboardOpening.choose(last, MESSAGE, true,
                KeyboardOpening.remember(InputMode.CHINESE_QWERTY, MESSAGE)));
        assertEquals(InputMode.CHINESE_KEYPAD, KeyboardOpening.choose(last, TEXT, true,
                KeyboardOpening.remember(InputMode.CHINESE_KEYPAD, TEXT)));
        assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(last, TEXT, true,
                KeyboardOpening.remember(InputMode.ENGLISH, TEXT)));
        // Nothing chosen yet: English, as before.
        assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(last, TEXT, true, null));
        assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(last, TEXT, true, "something-else"));
    }

    @Test
    public void aRestartOfTheSameFieldIsNotANewField() {
        assertTrue(KeyboardOpening.isNewField(false, "app:1:1", "app:2:1"));
        assertTrue(KeyboardOpening.isNewField(false, "app:1:1", null));
        assertFalse("the same field starting again keeps the keyboard on screen",
                KeyboardOpening.isNewField(false, "app:1:1", "app:1:1"));
        assertFalse("an app restarting input (after a send) keeps it too",
                KeyboardOpening.isNewField(true, "app:1:1", "app:2:1"));
    }

    @Test
    public void latinOnlyFieldsOpenInEnglishAndAreNotRemembered() {
        String chinese = KeyboardOpening.remember(InputMode.CHINESE_QWERTY, TEXT);
        for (int type : new int[]{PASSWORD, WEB_PASSWORD, EMAIL, URL, NUMBER, PHONE}) {
            assertTrue(KeyboardOpening.latinOnlyField(type));
            assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(KeyboardOpening.OPEN_LAST_USED, type, true, chinese));
            assertNull("a choice made there is not remembered", KeyboardOpening.remember(InputMode.CHINESE_QWERTY, type));
            assertNull(KeyboardOpening.remember(InputMode.ENGLISH, type));
        }
        assertFalse(KeyboardOpening.latinOnlyField(MESSAGE));
        assertFalse(KeyboardOpening.latinOnlyField(TEXT));
    }

    @Test
    public void withoutChineseItIsAlwaysEnglish() {
        String keypad = KeyboardOpening.remember(InputMode.CHINESE_KEYPAD, TEXT);
        assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(KeyboardOpening.OPEN_LAST_USED, TEXT, false, keypad));
    }

    @Test
    public void theSettingAlwaysEnglishKeepsTheOldBehaviour() {
        assertEquals(KeyboardOpening.OPEN_LAST_USED, Prefs.keyboardOpenOn(context));
        String chinese = KeyboardOpening.remember(InputMode.CHINESE_QWERTY, TEXT);
        Prefs.setKeyboardOpenOn(context, KeyboardOpening.OPEN_ENGLISH);
        assertEquals(KeyboardOpening.OPEN_ENGLISH, Prefs.keyboardOpenOn(context));
        assertEquals(InputMode.ENGLISH, KeyboardOpening.choose(Prefs.keyboardOpenOn(context), TEXT, true, chinese));
        Prefs.setKeyboardOpenOn(context, "nonsense");
        assertEquals(KeyboardOpening.OPEN_LAST_USED, Prefs.keyboardOpenOn(context));
    }

    @Test
    public void theRememberedKeyboardSurvivesInPrefs() {
        assertNull(Prefs.rememberedKeyboard(context));
        Prefs.setRememberedKeyboard(context, KeyboardOpening.remember(InputMode.CHINESE_KEYPAD, TEXT));
        Prefs.setRememberedKeyboard(context, KeyboardOpening.remember(InputMode.ENGLISH, PASSWORD)); // ignored
        assertEquals(InputMode.CHINESE_KEYPAD, KeyboardOpening.choose(Prefs.keyboardOpenOn(context), TEXT, true,
                Prefs.rememberedKeyboard(context)));
    }
}
