package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 0.9.3: the word lists' styles can be picked on the phone, and the lists' default
 * style is applied once each time it changes. Every name and prompt is invented.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ListStylesTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        ListStyles.resetForTest();
        Prefs.promptProfiles(context); // a new install: Clean first
    }

    @After
    public void tearDown() {
        ListStyles.resetForTest();
    }

    private static WordLayer layer(String layer, String user, String body) throws Exception {
        String json = "{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,\"layer\":\"" + layer
                + "\",\"user\":\"" + user + "\"" + (body.isEmpty() ? "" : "," + body) + "}";
        return WordLayer.parse(json.getBytes(StandardCharsets.UTF_8), layer, "sam");
    }

    private static WordMerge.Snapshot merge(WordLayer... layers) {
        return WordMerge.merge(Arrays.asList(layers), Collections.<WordOp>emptyList(), BuiltinStyles.ids());
    }

    private static final String PIRATE_LITE = "{\"id\":\"pebble\",\"label\":\"Pebble\",\"prompt\":\"Tidy this up for Pebble Example.\",\"expression\":1}";

    @Test
    public void aLayersStylesAreKeptWholeAndAHigherLayerReplacesOne() throws Exception {
        WordLayer household = layer("household", "household",
                "\"styles\":[{\"id\":\"pebble\",\"label\":\"House pebble\",\"prompt\":\"Lower.\"},"
                        + "{\"id\":\"lantern\",\"label\":\"Lantern\",\"prompt\":\"Keep it short.\",\"model\":\"some-pc-model\"}]");
        WordLayer personal = layer("personal", "sam", "\"styles\":[" + PIRATE_LITE + "]");
        WordMerge.Snapshot snapshot = merge(household, personal);
        assertEquals(2, snapshot.styles.size());
        WordLayer.Style pebble = snapshot.styles.get(0);
        assertEquals("pebble", pebble.id);
        assertEquals("Pebble", pebble.label);
        assertEquals("Tidy this up for Pebble Example.", pebble.prompt);
        assertEquals(1, pebble.expression);
        assertEquals("personal", pebble.layer);
        assertEquals("lantern", snapshot.styles.get(1).id);
        assertEquals(-1, snapshot.styles.get(1).expression);
        assertEquals("some-pc-model", snapshot.styles.get(1).model);
    }

    @Test
    public void snapshotIdsWithoutStylesAreUnchangedAndStylesChangeTheId() throws Exception {
        WordLayer plain = layer("personal", "sam", "\"terms\":[{\"id\":\"zorblax\",\"text\":\"Zorblax\"}]");
        WordLayer styled = layer("personal", "sam",
                "\"terms\":[{\"id\":\"zorblax\",\"text\":\"Zorblax\"}],\"styles\":[" + PIRATE_LITE + "]");
        WordMerge.Snapshot a = merge(plain);
        WordMerge.Snapshot b = merge(styled);
        assertNotEquals(a.id, b.id);
        assertEquals(a.id, new WordMerge.Snapshot(a.terms, a.corrections, a.issues, a.defaults).id);
    }

    @Test
    public void aListStyleIsOfferedButNeverStoredEditedOrDeleted() throws Exception {
        ListStyles.publish(context, merge(layer("personal", "sam", "\"styles\":[" + PIRATE_LITE + "]")));
        String id = ListStyles.PREFIX + "pebble";
        List<String> ids = Arrays.asList(Prefs.selectablePresetValues(context));
        assertTrue(ids.contains(id));
        assertEquals(Prefs.PRESET_CLEAN, ids.get(0));
        assertEquals("Pebble", Prefs.labelForPreset(context, id));

        // Its prompt is the list's, wrapped in the language rules like every everyday style.
        String composed = Prefs.promptForPreset(context, id, Prefs.EXPRESSION_NATURAL);
        assertTrue(composed.startsWith(Prefs.languageRuleFirst()));
        assertTrue(composed.contains("Tidy this up for Pebble Example."));
        assertTrue(composed.contains(Prefs.languageContract()));
        assertFalse(Prefs.isFunVoiceStyle(id));
        // It starts at the list's level until one is picked on the phone.
        assertEquals(1, Prefs.expressionForPreset(context, id));
        Prefs.setExpressionForPreset(context, id, Prefs.EXPRESSION_LIVELY);
        assertEquals(Prefs.EXPRESSION_LIVELY, Prefs.expressionForPreset(context, id));

        // Not editable, not deletable, never written into the phone's own list.
        assertFalse(Prefs.canDeletePromptProfile(id));
        Prefs.savePromptProfile(context, id, "Renamed", "", "Changed on the phone.", "");
        assertTrue(Prefs.promptForPreset(context, id).equals("Tidy this up for Pebble Example."));
        Prefs.addPromptProfile(context, "Another one"); // writes the phone's list
        JSONArray stored = new JSONArray(Prefs.shared(context).getString("prompts_json", "[]"));
        for (int i = 0; i < stored.length(); i++) {
            assertFalse(stored.getJSONObject(i).getString("id").startsWith(ListStyles.PREFIX));
        }
        assertTrue(Arrays.asList(Prefs.selectablePresetValues(context)).contains(id));
    }

    @Test
    public void aStyleTheListsDropGoesAwayAndItsChoiceFallsBack() throws Exception {
        ListStyles.publish(context, merge(layer("personal", "sam", "\"styles\":[" + PIRATE_LITE + "]")));
        Prefs.setActivePreset(context, ListStyles.PREFIX + "pebble");
        assertEquals(ListStyles.PREFIX + "pebble", Prefs.activePreset(context));
        ListStyles.publish(context, merge(layer("personal", "sam", "")));
        assertFalse(Arrays.asList(Prefs.selectablePresetValues(context)).contains(ListStyles.PREFIX + "pebble"));
        assertNotEquals(ListStyles.PREFIX + "pebble", Prefs.activePreset(context));
        // A dictation that started in it still tidies up (as Friends), never with an empty prompt.
        assertFalse(Prefs.defaultPromptForPreset(ListStyles.PREFIX + "pebble").trim().isEmpty());
    }

    @Test
    public void theListsDefaultIsAppliedOnceAndAPickOnThePhoneStays() throws Exception {
        WordMerge.Snapshot withDefault = merge(layer("personal", "sam",
                "\"styles\":[" + PIRATE_LITE + "],\"defaults\":{\"cleanupStyle\":\"pebble\"}"));
        ListStyles.publish(context, withDefault);
        assertEquals(ListStyles.PREFIX + "pebble", Prefs.activePreset(context));

        // Picked on the phone afterwards: the same lists never switch it back.
        Prefs.setActivePreset(context, Prefs.PRESET_CASUAL);
        ListStyles.publish(context, withDefault);
        ListStyles.publish(context, merge(layer("personal", "sam",
                "\"styles\":[" + PIRATE_LITE + "],\"defaults\":{\"cleanupStyle\":\"pebble\"},\"terms\":[{\"id\":\"zorblax\",\"text\":\"Zorblax\"}]")));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(context));

        // A new value is applied once more.
        ListStyles.publish(context, merge(layer("personal", "sam",
                "\"styles\":[" + PIRATE_LITE + "],\"defaults\":{\"cleanupStyle\":\"clean\"}")));
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));

        // Taken away, then set again: applied again.
        Prefs.setActivePreset(context, Prefs.PRESET_CASUAL);
        ListStyles.publish(context, merge(layer("personal", "sam", "\"styles\":[" + PIRATE_LITE + "]")));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(context));
        ListStyles.publish(context, withDefault);
        assertEquals(ListStyles.PREFIX + "pebble", Prefs.activePreset(context));
    }

    @Test
    public void noSnapshotLeavesTheRecordAloneAndRawChangesNothing() throws Exception {
        WordMerge.Snapshot withDefault = merge(layer("personal", "sam",
                "\"styles\":[" + PIRATE_LITE + "],\"defaults\":{\"cleanupStyle\":\"pebble\"}"));
        ListStyles.publish(context, withDefault);
        Prefs.setActivePreset(context, Prefs.PRESET_CASUAL);
        ListStyles.publish(context, null); // not enrolled or no copy yet: styles gone, record kept
        assertTrue(ListStyles.published().isEmpty());
        ListStyles.publish(context, withDefault);
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(context));

        ListStyles.publish(context, merge(layer("personal", "sam", "\"defaults\":{\"cleanupStyle\":\"raw\"}")));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(context));
    }

    @Test
    public void aListsOwnStyleCalledCleanWinsOverTheBuiltIn() throws Exception {
        WordMerge.Snapshot snapshot = merge(layer("personal", "sam",
                "\"styles\":[{\"id\":\"clean\",\"label\":\"My clean\",\"prompt\":\"Mine.\"}],\"defaults\":{\"cleanupStyle\":\"clean\"}"));
        assertEquals(ListStyles.PREFIX + "clean", ListStyles.target(snapshot, "clean"));
        ListStyles.publish(context, snapshot);
        assertEquals(ListStyles.PREFIX + "clean", Prefs.activePreset(context));
        assertEquals("Mine.", Prefs.promptForPreset(context, Prefs.activePreset(context)));
    }

    @Test
    public void aBlankHigherEntryReplacesALowerStyleAndIsOfferedNowhere() throws Exception {
        WordLayer household = layer("household", "household", "\"styles\":[" + PIRATE_LITE + "]");
        WordLayer personal = layer("personal", "sam", "\"styles\":[{\"id\":\"pebble\",\"label\":\"Pebble\",\"prompt\":\"   \"}]");
        assertTrue(merge(household, personal).styles.isEmpty());
        assertEquals(1, merge(household).styles.size());
    }

    @Test
    public void aRawDefaultNeverSwitchesEvenToAListStyleCalledRaw() throws Exception {
        WordMerge.Snapshot snapshot = merge(layer("personal", "sam",
                "\"styles\":[{\"id\":\"raw\",\"label\":\"Odd\",\"prompt\":\"Odd.\"}],\"defaults\":{\"cleanupStyle\":\"raw\"}"));
        assertEquals("", ListStyles.target(snapshot, "raw"));
        ListStyles.publish(context, snapshot);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));
    }

    @Test
    public void aDefaultNamingAStyleNoListHasIsDroppedByTheMerge() throws Exception {
        WordMerge.Snapshot snapshot = merge(layer("personal", "sam", "\"defaults\":{\"cleanupStyle\":\"nowhere\"}"));
        assertNull(snapshot.defaults.get("cleanupStyle"));
        assertTrue(snapshot.issues.contains(WordMerge.ISSUE_MISSING_STYLE));
        ListStyles.publish(context, snapshot);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(context));
    }

    /**
     * Checks a real layer file on this computer without committing it: set VF_PROFILE_CHECK
     * to the file and VF_PROFILE_USER to its user, and this reads it as the phone would.
     */
    @Test
    public void aLocalLayerFileNamedByTheEnvironmentIsAcceptedAndOffered() throws Exception {
        String path = System.getenv("VF_PROFILE_CHECK");
        String user = System.getenv("VF_PROFILE_USER");
        Assume.assumeTrue(path != null && !path.isEmpty() && user != null && !user.isEmpty());
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        WordLayer personal = WordLayer.parse(bytes, WordLayer.PERSONAL, user);
        assertFalse(personal.styles.isEmpty());
        List<WordLayer> layers = new ArrayList<>();
        layers.add(personal);
        WordMerge.Snapshot snapshot = WordMerge.merge(layers, Collections.<WordOp>emptyList(), BuiltinStyles.ids());
        assertFalse(snapshot.issues.toString(), snapshot.issues.contains(WordMerge.ISSUE_MISSING_STYLE));
        ListStyles.publish(context, snapshot);
        for (WordLayer.Style style : snapshot.styles) {
            String id = ListStyles.PREFIX + style.id;
            assertTrue(Arrays.asList(Prefs.selectablePresetValues(context)).contains(id));
            assertNotNull(ListStyles.find(id));
            assertTrue(Prefs.promptForPreset(context, id, Prefs.EXPRESSION_NATURAL).contains(style.prompt));
        }
        System.out.println("VF_PROFILE_CHECK ok: styles=" + snapshot.styles.size()
                + " cleanupStyle=" + snapshot.defaults.get("cleanupStyle") + " active=" + Prefs.activePreset(context));
    }
}
