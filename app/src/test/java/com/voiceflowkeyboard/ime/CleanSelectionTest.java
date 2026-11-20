package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Clean: read from the shared pack file, the default only on a genuinely new
 * install, offered (never imposed) on an existing one, and never duplicated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CleanSelectionTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
    }

    private static int count(String[] ids, String id) {
        int n = 0;
        for (String each : ids) {
            if (each.equals(id)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void cleanComesFromThePackFileByteForByte() throws Exception {
        Path pack = Paths.get("..", "pack", "builtin", "styles.json");
        JSONObject file = new JSONObject(new String(Files.readAllBytes(pack), StandardCharsets.UTF_8));
        JSONObject clean = file.getJSONArray("styles").getJSONObject(0);
        assertEquals("clean", clean.getString("id"));

        BuiltinStyles.Style loaded = BuiltinStyles.find(Prefs.PRESET_CLEAN);
        assertNotNull("pack/builtin/styles.json must be on the classpath", loaded);
        assertEquals(clean.getString("prompt").trim(), loaded.prompt);
        assertEquals(clean.getString("prompt").trim(), Prefs.defaultPromptForPreset(Prefs.PRESET_CLEAN));
        assertEquals(Prefs.EXPRESSION_NATURAL, loaded.expression);
        assertEquals("Clean", Prefs.defaultLabelForPreset(Prefs.PRESET_CLEAN));
    }

    @Test
    public void thePackParserRefusesAnotherSchema() {
        try {
            BuiltinStyles.parse("{\"schema\":\"something-else\",\"version\":1,\"styles\":[]}");
            throw new AssertionError("should have refused");
        } catch (org.json.JSONException expected) {
            // refused whole
        }
    }

    @Test
    public void aNewInstallStartsOnCleanNatural() {
        assertTrue(Prefs.isFreshInstall(context));
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));
        assertEquals(Prefs.EXPRESSION_NATURAL, Prefs.expressionForPreset(context, Prefs.PRESET_CLEAN));
        String[] ids = Prefs.selectablePresetValues(context);
        assertEquals(Prefs.PRESET_CLEAN, ids[0]);
        assertEquals(1, count(ids, Prefs.PRESET_CLEAN));
        assertFalse(Prefs.isFreshInstall(context));
    }

    @Test
    public void anExistingInstallWithNoChosenStyleStillMeansFriends() {
        // What 0.7.x leaves after first use: its own style list, nothing chosen.
        Prefs.shared(context).edit()
                .putString("prompts_json", "[{\"id\":\"casual\",\"name\":\"Friends\",\"icon\":\"🙂\"},"
                        + "{\"id\":\"business\",\"name\":\"Work\",\"icon\":\"💼\"},"
                        + "{\"id\":\"family\",\"name\":\"Family\",\"icon\":\"🏠\"}]")
                .commit();
        assertFalse(Prefs.isFreshInstall(context));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(context));
        // Clean is offered right after Friends, once, however often the list is read.
        String[] ids = Prefs.selectablePresetValues(context);
        assertEquals(Prefs.PRESET_CASUAL, ids[0]);
        assertEquals(Prefs.PRESET_CLEAN, ids[1]);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, count(Prefs.selectablePresetValues(context), Prefs.PRESET_CLEAN));
        }
    }

    @Test
    public void anExistingDefaultAndItsPromptsStayExactlyAsTheyWere() {
        String exact = "Keep my words.\n  Don't add emoji 🙂 and keep 那个 when it means \"that\".  ";
        Prefs.saveApiKeys(context, "sk-test-not-real", "", "", "");
        Prefs.promptProfiles(context);
        Prefs.addVoiceStyleTemplate(context, Prefs.PRESET_PARTNER);
        Prefs.shared(context).edit()
                .putString("prompt_partner", exact)
                .putString("active_preset", Prefs.PRESET_PARTNER)
                .putInt("expression_partner", Prefs.EXPRESSION_EXPRESSIVE)
                .commit();

        List<PromptProfile> profiles = Prefs.promptProfiles(context);
        assertTrue(profiles.size() > 1);
        assertEquals(Prefs.PRESET_PARTNER, Prefs.activePreset(context));
        assertEquals(exact, Prefs.promptForPreset(context, Prefs.PRESET_PARTNER));
        assertEquals(Prefs.EXPRESSION_EXPRESSIVE, Prefs.expressionForPreset(context, Prefs.PRESET_PARTNER));
    }

    @Test
    public void cleanIsSelectableAndKeepsTheLanguageRules() {
        Prefs.promptProfiles(context);
        Prefs.setActivePreset(context, Prefs.PRESET_CLEAN);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));
        String composed = Prefs.promptForPreset(context, Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        assertTrue(composed.startsWith(Prefs.languageRuleFirst()));
        assertTrue(composed.contains(Prefs.defaultPromptForPreset(Prefs.PRESET_CLEAN)));
        assertTrue(composed.contains(Prefs.languageContract()));
        assertTrue(composed.contains("Selected expression level: Natural"));
        assertFalse(Prefs.isFunVoiceStyle(Prefs.PRESET_CLEAN));
    }

    @Test
    public void theStyleDigestHashesValuesAndNeverShowsThem() {
        Prefs.saveApiKeys(context, "sk-test-not-real", "", "", "");
        Prefs.promptProfiles(context);
        Prefs.shared(context).edit()
                .putString("prompt_family", "Febe's Pinewood prompt")
                .putInt("expression_family", Prefs.EXPRESSION_LIVELY)
                .commit();
        String digest = Prefs.styleDigest(context);
        assertFalse(digest.contains("Pinewood"));
        assertFalse(digest.contains("sk-test"));
        assertTrue(digest.contains("prompt_family=sha256:"));
        assertTrue(digest.contains("expression_family=value:3"));
        assertTrue(digest.contains("list:clean=sha256:"));
        assertTrue(digest.contains("active_preset=unset"));
        assertEquals(digest, Prefs.styleDigest(context));
    }

    @Test
    public void cleanCannotBeDeleted() {
        assertFalse(Prefs.canDeletePromptProfile(Prefs.PRESET_CLEAN));
        Prefs.promptProfiles(context);
        Prefs.deletePromptProfile(context, Prefs.PRESET_CLEAN);
        assertEquals(1, count(Prefs.selectablePresetValues(context), Prefs.PRESET_CLEAN));
    }
}
