package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 0.9.4: the stored side of the keyboard's styles (the Chinese keyboard's style, Style choice,
 * a style's word list on or off) and the USB actions SET_KEYBOARD_STYLE and SET_STYLE_WORDS.
 * Every id and label is invented.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyboardStyleSettingsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        ListStyles.resetForTest();
        Prefs.promptProfiles(context); // a new install: Clean first
        Prefs.setChineseInputEnabled(context, true);
    }

    @After
    public void tearDown() {
        ListStyles.resetForTest();
    }

    static WordMerge.Snapshot listWithStyle(String id, String label) throws Exception {
        String json = "{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,\"layer\":\"personal\",\"user\":\"sam\","
                + "\"styles\":[{\"id\":\"" + id + "\",\"label\":\"" + label + "\",\"prompt\":\"Keep the mix as said.\"}]}";
        WordLayer layer = WordLayer.parse(json.getBytes(StandardCharsets.UTF_8), "personal", "sam");
        return WordMerge.merge(Collections.singletonList(layer), Collections.<WordOp>emptyList(), BuiltinStyles.ids());
    }

    @Test
    public void rememberIsOnForEveryoneByDefault() {
        assertTrue(Prefs.styleChoiceRemembers(context));
        Prefs.setStyleChoiceMode(context, KeyboardStyles.CHOICE_ONE_MESSAGE);
        assertFalse(Prefs.styleChoiceRemembers(context));
    }

    @Test
    public void theChineseKeyboardsStyleAppliesOnlyWithChineseInputOn() {
        Prefs.setChineseKeyboardStyle(context, Prefs.PRESET_BUSINESS);
        assertEquals(Prefs.PRESET_BUSINESS, Prefs.keyboardStyle(context, true));
        assertEquals(Prefs.PRESET_CLEAN, Prefs.keyboardStyle(context, false));
        Prefs.setChineseInputEnabled(context, false);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.keyboardStyle(context, true));
    }

    @Test
    public void makeDefaultOnEachKeyboardSavesThatKeyboardsStyle() {
        Prefs.saveKeyboardStyle(context, true, Prefs.PRESET_CASUAL);
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(context));
        assertEquals("English untouched", Prefs.PRESET_CLEAN, Prefs.activePreset(context));
        Prefs.saveKeyboardStyle(context, false, Prefs.PRESET_BUSINESS);
        assertEquals(Prefs.PRESET_BUSINESS, Prefs.activePreset(context));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(context));
    }

    @Test
    public void aWordListStyleNotHereYetIsKeptAndComesBackWithTheLists() throws Exception {
        Prefs.chooseChineseKeyboardStyle(context, "list_mixed-example");
        assertEquals("list_mixed-example", Prefs.chineseKeyboardStyle(context));
        assertEquals("missing: the English style", Prefs.PRESET_CLEAN, Prefs.keyboardStyle(context, true));
        assertEquals(KeyboardStyles.Status.MISSING,
                KeyboardStyles.status(Prefs.chineseKeyboardStyle(context), Prefs.styleIds(context)));
        ListStyles.publish(context, listWithStyle("mixed-example", "Mixed example"));
        assertEquals("list_mixed-example", Prefs.keyboardStyle(context, true));
    }

    @Test
    public void namingTheChineseStylePinsItOnTheBar() throws Exception {
        ListStyles.publish(context, listWithStyle("mixed-example", "Mixed example"));
        Prefs.chooseChineseKeyboardStyle(context, "list_mixed-example");
        List<String> pins = Prefs.pinnedStyles(context);
        assertTrue(String.valueOf(pins), pins.contains("list_mixed-example"));
        Prefs.chooseChineseKeyboardStyle(context, "");
        assertEquals("Same as English", "", Prefs.chineseKeyboardStyle(context));
        assertTrue("her pin stays", Prefs.pinnedStyles(context).contains("list_mixed-example"));
    }

    @Test
    public void aStylesWordListIsOnUntilTurnedOffAndGoesWithTheStyle() {
        assertTrue(Prefs.styleWordsOn(context, "list_mixed-example"));
        Prefs.setStyleWordsOn(context, "list_mixed-example", false);
        assertFalse(Prefs.styleWordsOn(context, "list_mixed-example"));
        assertTrue("other styles keep theirs", Prefs.styleWordsOn(context, Prefs.PRESET_CLEAN));
        Prefs.setStyleWordsOn(context, "list_mixed-example", true);
        assertTrue(Prefs.styleWordsOn(context, "list_mixed-example"));
    }

    // ------------------------------------------------------------------ over USB

    @Test
    public void setKeyboardStyleStoresAndRepliesWhetherTheStyleIsKnown() throws Exception {
        String reply = SyncCommandReceiver.setKeyboardStyle(context, "chinese", "list_mixed-example");
        assertTrue(reply, reply.startsWith("keyboard=chinese style=list_mixed-example styleKnown=not-yet"));
        assertTrue(reply, reply.contains("startsOn=clean english=clean chineseInput=on styleChoice=remember"));

        ListStyles.publish(context, listWithStyle("mixed-example", "Mixed example"));
        reply = SyncCommandReceiver.setKeyboardStyle(context, "chinese", "list_mixed-example");
        assertTrue(reply, reply.contains("styleKnown=yes startsOn=list_mixed-example"));

        reply = SyncCommandReceiver.setKeyboardStyle(context, "chinese", "list_other-example");
        assertTrue("lists read, style absent", reply.contains("styleKnown=no"));

        reply = SyncCommandReceiver.setKeyboardStyle(context, "chinese", "same");
        assertTrue(reply, reply.startsWith("keyboard=chinese style=same startsOn=clean"));
        assertEquals("", Prefs.chineseKeyboardStyle(context));
    }

    @Test
    public void setKeyboardStyleRefusesBadValuesByName() {
        for (String[] bad : new String[][]{{"english", "casual"}, {"", "casual"}, {"chinese", ""},
                {"chinese", "Has Space"}}) {
            try {
                SyncCommandReceiver.setKeyboardStyle(context, bad[0], bad[1]);
                fail(Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("keyboard")
                        || expected.getMessage().startsWith("style"));
            }
        }
        assertEquals("", Prefs.chineseKeyboardStyle(context));
    }

    @Test
    public void setStyleWordsStoresAndRepliesWithTheStoredValue() {
        String reply = SyncCommandReceiver.setStyleWords(context, "list_mixed-example", "off");
        assertTrue(reply, reply.startsWith("style=list_mixed-example words=off"));
        assertFalse(Prefs.styleWordsOn(context, "list_mixed-example"));
        reply = SyncCommandReceiver.setStyleWords(context, "list_mixed-example", "on");
        assertTrue(reply, reply.startsWith("style=list_mixed-example words=on"));
        try {
            SyncCommandReceiver.setStyleWords(context, "casual", "maybe");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("words"));
        }
    }

    @Test
    public void theBroadcastsReachTheActions() {
        SyncCommandReceiver receiver = new SyncCommandReceiver();
        Intent set = new Intent(SyncCommandReceiver.ACTION_SET_KEYBOARD_STYLE)
                .putExtra("keyboard", "chinese").putExtra("style", "casual");
        Intent words = new Intent(SyncCommandReceiver.ACTION_SET_STYLE_WORDS)
                .putExtra("style", "casual").putExtra("words", "off");
        for (Intent intent : new Intent[]{set, words}) {
            // Outside an ordered broadcast setResult* throws; the action has run by then.
            try {
                receiver.onReceive(context, intent);
            } catch (RuntimeException ignored) {
                // no ordered broadcast here
            }
        }
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(context));
        assertFalse(Prefs.styleWordsOn(context, Prefs.PRESET_CASUAL));
        assertNull(Prefs.speechModelForStyle(context, Prefs.PRESET_CASUAL));
    }
}
