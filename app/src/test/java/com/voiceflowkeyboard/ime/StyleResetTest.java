package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The one-time reset to Clean touches only a phone enrolled as the user and
 * device the instruction names, runs once, and keeps keys, words and history.
 * Milo plays the owner of the reset phone, Febe the other person in the house.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StyleResetTest {
    private static final String TOKEN = "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final String GATEWAY = "https://sync.pinewood.example";
    private static final String RESET_ID = "milo-clean-2026-11-19";

    private Context context;
    private TokenVault.KeySource savedKeys;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        SyncStore.prefs(context).edit().clear().commit();
        savedKeys = SyncStore.keys;
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
    }

    @After
    public void tearDown() {
        SyncStore.keys = savedKeys;
    }

    /** A phone that has been used for a while: custom styles, overrides, keys, words, history. */
    private void seedUsedPhone(String activePreset) {
        Prefs.saveApiKeys(context, "sk-test-not-real", "", "", "");
        Prefs.promptProfiles(context);
        Prefs.addVoiceStyleTemplate(context, Prefs.PRESET_PARTNER);
        String custom = Prefs.addPromptProfile(context, "Pinewood crew");
        Prefs.savePromptProfile(context, Prefs.PRESET_CASUAL, "Friends", "", "Keep it short and say QBR as is.", "Casual.");
        Prefs.savePromptProfile(context, custom, "Pinewood crew", "", "Write like a camp counsellor.", "Camp.");
        Prefs.setExpressionForPreset(context, Prefs.PRESET_CASUAL, Prefs.EXPRESSION_LIVELY);
        Prefs.setExpressionForPreset(context, custom, Prefs.EXPRESSION_EXPRESSIVE);
        Prefs.shared(context).edit()
                .putString("expression_override_" + custom, "Use exclamation marks freely.")
                .putString("transform_model_override_openai_" + custom, "gpt-test-model")
                .commit();
        List<PhraseReplacement> words = new ArrayList<>();
        words.add(new PhraseReplacement("Castrel", "Kestrel", "the bird"));
        Prefs.saveUserPhraseReplacements(context, words);
        Prefs.addTranscriptHistory(context, "um send the QBR deck", "Send the QBR deck", Prefs.PRESET_CASUAL);
        Prefs.setActivePreset(context, activePreset);
    }

    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        return new TreeMap<>(prefs.getAll());
    }

    @Test
    public void resetsTheEnrolledOwnersPhoneToCleanAndKeepsEverythingElse() throws Exception {
        seedUsedPhone(Prefs.PRESET_CASUAL);
        Enrolment me = SyncStore.enrol(context, "milo", GATEWAY, TOKEN);

        String decision = StyleReset.run(context, new StyleReset.Instruction(RESET_ID, "milo", me.device));

        assertEquals(StyleReset.APPLY, decision);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));
        assertEquals(Prefs.EXPRESSION_NATURAL, Prefs.expressionForPreset(context, Prefs.PRESET_CLEAN));
        assertEquals(Prefs.PRESET_CLEAN, Prefs.selectablePresetValues(context)[0]);
        // His old prompts and overrides are gone; built-ins are back to the APK defaults.
        assertEquals(Prefs.defaultPromptForPreset(Prefs.PRESET_CASUAL), Prefs.promptForPreset(context, Prefs.PRESET_CASUAL));
        for (String key : Prefs.shared(context).getAll().keySet()) {
            assertFalse(key, key.startsWith("prompt_"));
            assertFalse(key, key.startsWith("expression_override_"));
            assertFalse(key, key.startsWith("transform_model_override_"));
            assertFalse(key, key.startsWith("style_guidance_"));
        }
        for (String id : Prefs.selectablePresetValues(context)) {
            assertFalse(id, id.startsWith("custom_"));
        }
        // Keys, words and history stay.
        assertEquals("sk-test-not-real", Prefs.openAiApiKey(context));
        assertEquals("Kestrel", Prefs.userPhraseReplacements(context).get(0).to);
        assertEquals(1, Prefs.transcriptHistory(context).size());
    }

    @Test
    public void runsOnceAndNeverAgain() throws Exception {
        seedUsedPhone(Prefs.PRESET_CASUAL);
        Enrolment me = SyncStore.enrol(context, "milo", GATEWAY, TOKEN);
        StyleReset.Instruction reset = new StyleReset.Instruction(RESET_ID, "milo", me.device);
        assertEquals(StyleReset.APPLY, StyleReset.run(context, reset));

        // The user makes a new style afterwards; the same instruction arriving again must not wipe it.
        String mine = Prefs.addPromptProfile(context, "QBR notes");
        Prefs.setActivePreset(context, mine);
        assertEquals(StyleReset.ALREADY_DONE, StyleReset.run(context, reset));
        assertEquals(mine, Prefs.activePreset(context));
    }

    @Test
    public void neverTouchesAnotherUsersPhone() throws Exception {
        seedUsedPhone(Prefs.PRESET_FAMILY);
        Enrolment hers = SyncStore.enrol(context, "febe", GATEWAY, TOKEN);
        Map<String, Object> before = snapshot(Prefs.shared(context));

        // An instruction for Milo, even one that names her device by mistake.
        assertEquals(StyleReset.OTHER_USER, StyleReset.run(context, new StyleReset.Instruction(RESET_ID, "milo", hers.device)));
        assertEquals(StyleReset.OTHER_USER, StyleReset.run(context, new StyleReset.Instruction(RESET_ID, "milo", "ph99999999")));

        assertEquals(before, snapshot(Prefs.shared(context)));
        assertEquals(Prefs.PRESET_FAMILY, Prefs.activePreset(context));
        assertTrue(SyncStore.resetsDone(context).isEmpty());
    }

    @Test
    public void neverTouchesAnUnenrolledPhone() {
        seedUsedPhone(Prefs.PRESET_CASUAL);
        String device = SyncStore.deviceId(context);
        Map<String, Object> before = snapshot(Prefs.shared(context));

        assertEquals(StyleReset.NOT_ENROLLED, StyleReset.run(context, new StyleReset.Instruction(RESET_ID, "milo", device)));
        assertEquals(before, snapshot(Prefs.shared(context)));
    }

    @Test
    public void neverTouchesAnotherDeviceOfTheSameUser() throws Exception {
        seedUsedPhone(Prefs.PRESET_CASUAL);
        SyncStore.enrol(context, "milo", GATEWAY, TOKEN);
        Map<String, Object> before = snapshot(Prefs.shared(context));

        assertEquals(StyleReset.OTHER_DEVICE, StyleReset.run(context, new StyleReset.Instruction(RESET_ID, "milo", "ph00000000")));
        assertEquals(before, snapshot(Prefs.shared(context)));
    }

    @Test
    public void refusesMalformedInstructions() {
        Enrolment me = new Enrolment("ph0a1b2c3d", "milo", GATEWAY);
        assertEquals(StyleReset.BAD_INSTRUCTION, StyleReset.decide(me, null, new HashSet<>()));
        assertEquals(StyleReset.BAD_INSTRUCTION, StyleReset.decide(me, new StyleReset.Instruction("", "milo", "ph0a1b2c3d"), new HashSet<>()));
        assertEquals(StyleReset.BAD_INSTRUCTION, StyleReset.decide(me, new StyleReset.Instruction(RESET_ID, "Milo!", "ph0a1b2c3d"), new HashSet<>()));
        assertEquals(StyleReset.BAD_INSTRUCTION, StyleReset.decide(me, new StyleReset.Instruction(RESET_ID, "milo", "phone-1"), new HashSet<>()));
        assertEquals(StyleReset.APPLY, StyleReset.decide(me, new StyleReset.Instruction(RESET_ID, "milo", "ph0a1b2c3d"), new HashSet<>()));
    }
}
