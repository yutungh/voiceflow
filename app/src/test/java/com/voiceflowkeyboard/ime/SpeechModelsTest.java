package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** A style's own speech model: resolving it at record start, the fallback, the notices, storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SpeechModelsTest {
    private static final SpeechModels.Choice OPENAI = new SpeechModels.Choice(Prefs.PROVIDER_OPENAI, "gpt-transcribe");
    private static final SpeechModels.Choice SONIOX = new SpeechModels.Choice(Prefs.PROVIDER_SONIOX, "");
    private static final String SONIOX_STORED = "{\"provider\":\"soniox\",\"model\":\"stt-async-v6\"}";

    private Context context;
    private final Set<String> keys = new HashSet<>();
    private final SpeechModels.Availability withKeys = provider -> keys.contains(provider)
            || ModelAvailabilityPolicy.isOffline(provider);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        keys.clear();
        keys.add(Prefs.PROVIDER_OPENAI);
        keys.add(Prefs.PROVIDER_SONIOX);
    }

    // ------------------------------------------------------------ resolving at record start

    @Test
    public void nothingStoredInheritsTheAppDefault() {
        SpeechModels.Resolved r = SpeechModels.resolve(OPENAI, null, null, withKeys);
        assertSame(OPENAI, r.used);
        assertFalse(r.fromStyle);
        assertNull(r.unavailable);
    }

    @Test
    public void nothingStoredInheritsTheProfilesChoice() {
        SpeechModels.Resolved r = SpeechModels.resolve(OPENAI, null, SONIOX, withKeys);
        assertTrue(r.used.sameAs(SONIOX));
        assertTrue(r.fromStyle);
    }

    @Test
    public void appDefaultWinsOverTheProfilesChoice() {
        SpeechModels.Resolved r = SpeechModels.resolve(OPENAI, SpeechModels.APP, SONIOX, withKeys);
        assertSame(OPENAI, r.used);
        assertFalse(r.fromStyle);
    }

    @Test
    public void theStylesOwnChoiceWinsOverTheProfiles() {
        SpeechModels.Resolved r = SpeechModels.resolve(SONIOX, "{\"provider\":\"openai\",\"model\":\"gpt-transcribe\"}",
                SONIOX, withKeys);
        assertTrue(r.used.sameAs(OPENAI));
        assertTrue(r.fromStyle);
    }

    @Test
    public void aChoiceEqualToTheAppDefaultIsTheAppDefault() {
        SpeechModels.Resolved r = SpeechModels.resolve(OPENAI, "{\"provider\":\"openai\"}", null, withKeys);
        assertSame(OPENAI, r.used);
        assertFalse("so the model availability fallback still applies", r.fromStyle);
    }

    @Test
    public void aMissingKeyKeepsTheChoiceButUsesTheAppDefault() {
        keys.remove(Prefs.PROVIDER_SONIOX);
        SpeechModels.Resolved r = SpeechModels.resolve(OPENAI, SONIOX_STORED, null, withKeys);
        assertSame(OPENAI, r.used);
        assertFalse(r.fromStyle);
        assertEquals(Prefs.PROVIDER_SONIOX, r.unavailable.provider);
    }

    @Test
    public void anUnknownProviderOrValueGetsTheSafeDefault() {
        SpeechModels.Resolved unknown = SpeechModels.resolve(OPENAI, "{\"provider\":\"future-asr\",\"model\":\"x\",\"extra\":1}",
                null, provider -> true);
        assertSame(OPENAI, unknown.used);
        assertEquals("future-asr", unknown.unavailable.provider);
        assertSame(OPENAI, SpeechModels.resolve(OPENAI, "not json", null, withKeys).used);
        assertSame(OPENAI, SpeechModels.resolve(OPENAI, "{}", null, withKeys).used);
    }

    @Test
    public void resolvedAtRecordStartFromTheStylesStoredValue() {
        Prefs.saveApiKeys(context, "sk-test-openai", "", "", "", "test-soniox-key-0000");
        Prefs.setSpeechModelForStyle(context, "list_chinese_mix", SpeechModels.encode(SONIOX));
        SpeechModels.Resolved r = SpeechModels.atRecordStart(context, "list_chinese_mix");
        assertEquals(Prefs.PROVIDER_SONIOX, r.used.provider);
        assertEquals("stt-async-v6", r.used.effectiveModel());
        assertTrue(r.fromStyle);
        // another style still uses the app's model
        SpeechModels.Resolved other = SpeechModels.atRecordStart(context, Prefs.PRESET_CASUAL);
        assertEquals(Prefs.PROVIDER_OPENAI, other.used.provider);
        assertFalse(other.fromStyle);
    }

    @Test
    public void anUnknownStoredValueSurvivesUntilANewPick() {
        String future = "{\"provider\":\"future-asr\",\"model\":\"m\",\"region\":\"eu\"}";
        Prefs.setSpeechModelForStyle(context, "custom_1", future);
        SpeechModels.atRecordStart(context, "custom_1");
        SpeechModels.options(context, "custom_1");
        assertEquals(future, Prefs.speechModelForStyle(context, "custom_1"));
        boolean shown = false;
        for (SpeechModels.Option o : SpeechModels.options(context, "custom_1")) {
            shown |= future.equals(o.value) && !o.available;
        }
        assertTrue("kept, shown, unavailable", shown);
    }

    // ------------------------------------------------------------ fallback

    @Test
    public void fallbackIsTheDistinctAppDefaultOnce() {
        SpeechModels.Resolved style = new SpeechModels.Resolved(SONIOX, true, null);
        assertSame(OPENAI, SpeechModels.fallback(style, OPENAI, true, withKeys));
    }

    @Test
    public void noFallbackWhenOffOrSameOrFromTheAppOrOfflineOrNoKey() {
        SpeechModels.Resolved style = new SpeechModels.Resolved(SONIOX, true, null);
        assertNull("setting off", SpeechModels.fallback(style, OPENAI, false, withKeys));
        assertNull("app default is Soniox too", SpeechModels.fallback(style, SONIOX, true, withKeys));
        assertNull("already the app's", SpeechModels.fallback(new SpeechModels.Resolved(OPENAI, false, null), OPENAI, true, withKeys));
        SpeechModels.Choice vosk = new SpeechModels.Choice(Prefs.PROVIDER_OFFLINE_VOSK, "");
        assertNull("offline app default", SpeechModels.fallback(style, vosk, true, withKeys));
        assertNull("offline style", SpeechModels.fallback(new SpeechModels.Resolved(vosk, true, null), OPENAI, true, withKeys));
        keys.remove(Prefs.PROVIDER_OPENAI);
        assertNull("no key for the app default", SpeechModels.fallback(style, OPENAI, true, withKeys));
    }

    @Test
    public void aChineseDictationNeverFallsBackToAModelWithoutChinese() {
        keys.add(Prefs.PROVIDER_XAI);
        SpeechModels.Resolved style = new SpeechModels.Resolved(SONIOX, true, null);
        SpeechModels.Choice xai = new SpeechModels.Choice(Prefs.PROVIDER_XAI, "");
        assertNull(SpeechModels.fallback(style, xai, true, withKeys, DictationLanguage.CHINESE));
        assertSame(xai, SpeechModels.fallback(style, xai, true, withKeys, DictationLanguage.AUTO));
        assertSame(OPENAI, SpeechModels.fallback(style, OPENAI, true, withKeys, DictationLanguage.CHINESE));
    }

    @Test
    public void fallbackSettingIsOnByDefault() {
        assertTrue(Prefs.speechFallbackToAppDefault(context));
        Prefs.setSpeechFallbackToAppDefault(context, false);
        assertFalse(Prefs.speechFallbackToAppDefault(context));
    }

    @Test
    public void aFailureFallsBackOnceAndSaysWhichWasUsed() throws Exception {
        List<String> calls = new ArrayList<>();
        SpeechModels.Outcome outcome = SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), OPENAI,
                (choice, appDefault) -> {
                    calls.add(choice.provider + (appDefault ? "/app" : "/style"));
                    if (Prefs.PROVIDER_SONIOX.equals(choice.provider)) {
                        throw new IOException("Soniox down");
                    }
                    return "hello";
                }, new SpeechCancel());
        assertEquals(Arrays.asList("soniox/style", "openai/app"), calls);
        assertEquals("hello", outcome.text);
        assertEquals("Soniox failed, used OpenAI", outcome.notice());
    }

    @Test
    public void successNeedsNoFallbackAndNoNotice() throws Exception {
        SpeechModels.Outcome outcome = SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), OPENAI,
                (choice, appDefault) -> "ok", null);
        assertEquals("", outcome.notice());
        assertEquals(Prefs.PROVIDER_SONIOX, outcome.used.provider);
    }

    @Test
    public void bothFailingThrowsTheFirstErrorWithTheSecondAttached() {
        try {
            SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), OPENAI, (choice, appDefault) -> {
                throw new IOException(choice.provider + " failed");
            }, new SpeechCancel());
            fail();
        } catch (Exception e) {
            assertEquals("soniox failed", e.getMessage());
            assertEquals("openai failed", e.getSuppressed()[0].getMessage());
        }
    }

    @Test
    public void noFallbackMeansOneTry() {
        List<String> calls = new ArrayList<>();
        try {
            SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), null, (choice, appDefault) -> {
                calls.add(choice.provider);
                throw new IOException("down");
            }, new SpeechCancel());
            fail();
        } catch (Exception expected) {
            assertEquals(Arrays.asList("soniox"), calls);
        }
    }

    @Test
    public void aCancelAlsoCancelsTheFallback() {
        SpeechCancel cancel = new SpeechCancel();
        List<String> calls = new ArrayList<>();
        try {
            SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), OPENAI, (choice, appDefault) -> {
                calls.add(choice.provider);
                cancel.cancel();
                throw new CancellationException("Canceled");
            }, cancel);
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof CancellationException);
        }
        assertEquals(Arrays.asList("soniox"), calls);
    }

    @Test
    public void aCancelDuringTheFallbackDeliversNothing() {
        SpeechCancel cancel = new SpeechCancel();
        try {
            SpeechModels.run(new SpeechModels.Resolved(SONIOX, true, null), OPENAI, (choice, appDefault) -> {
                if (Prefs.PROVIDER_SONIOX.equals(choice.provider)) {
                    throw new IOException("down");
                }
                cancel.cancel();
                return "late text";
            }, cancel);
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof CancellationException);
        }
    }

    @Test
    public void cancelRunsListenersOnceAndLateListenersAtOnce() {
        SpeechCancel cancel = new SpeechCancel();
        int[] runs = {0};
        cancel.onCancel(() -> runs[0]++);
        cancel.cancel();
        cancel.cancel();
        assertEquals(1, runs[0]);
        cancel.onCancel(() -> runs[0]++);
        assertEquals(2, runs[0]);
    }

    // ------------------------------------------------------------ word-list notices

    @Test
    public void noticesAreTrueToWhatThisAppSends() {
        assertEquals("Uses your word list", SpeechModels.wordListNotice(Prefs.PROVIDER_OPENAI, "gpt-transcribe"));
        assertEquals("Uses your word list", SpeechModels.wordListNotice(Prefs.PROVIDER_OPENAI, ""));
        assertEquals("Word-list hints aren't sent by this integration",
                SpeechModels.wordListNotice(Prefs.PROVIDER_OPENAI, "gpt-4o-transcribe-diarize"));
        assertEquals("Uses your word list to guide recognition", SpeechModels.wordListNotice(Prefs.PROVIDER_SONIOX, ""));
        assertEquals("Word-list hints aren't sent by this integration",
                SpeechModels.wordListNotice(Prefs.PROVIDER_DEEPGRAM, "nova-3"));
        assertEquals("Word-list hints aren't sent by this integration",
                SpeechModels.wordListNotice(Prefs.PROVIDER_XAI, "grok-transcribe"));
        assertEquals("Doesn't use your word list", SpeechModels.wordListNotice(Prefs.PROVIDER_OFFLINE_VOSK, ""));
        assertEquals("Doesn't use your word list", SpeechModels.wordListNotice(Prefs.PROVIDER_OFFLINE_PARAKEET, ""));
    }

    @Test
    public void gptTranscribeIsRecommendedAndTheAppDefault() {
        assertTrue(SpeechModels.isRecommended(Prefs.PROVIDER_OPENAI, "gpt-transcribe"));
        assertFalse(SpeechModels.isRecommended(Prefs.PROVIDER_SONIOX, "stt-async-v6"));
        assertEquals(Prefs.PROVIDER_OPENAI, Prefs.transcriptionProvider(context));
        assertEquals("gpt-transcribe", Prefs.transcriptionModel(context));
    }

    @Test
    public void optionsListSameAsAppThenProvidersWithKeysThenOffline() {
        Prefs.saveApiKeys(context, "sk-test-openai", "", "", "", "test-soniox-key-0000");
        List<SpeechModels.Option> options = SpeechModels.options(context, Prefs.PRESET_CASUAL);
        assertNull(options.get(0).value);
        assertTrue(options.get(0).title.startsWith("Same as app"));
        List<String> titles = new ArrayList<>();
        for (SpeechModels.Option o : options) {
            titles.add(o.title);
        }
        assertTrue(titles.contains("OpenAI gpt-transcribe"));
        assertTrue(titles.contains("Soniox stt-async-v6"));
        assertFalse("no Deepgram key saved", titles.toString().contains("Deepgram"));
        assertTrue(titles.contains("Offline Vosk"));
        for (SpeechModels.Option o : options) {
            if ("OpenAI gpt-transcribe".equals(o.title)) {
                assertTrue(o.detail.startsWith("Recommended."));
            }
        }
    }

    @Test
    public void aPickWhoseKeyWentStaysVisibleAndUnavailable() {
        Prefs.setSpeechModelForStyle(context, "custom_1", SONIOX_STORED);
        boolean shown = false;
        for (SpeechModels.Option o : SpeechModels.options(context, "custom_1")) {
            if (o.title.startsWith("Soniox")) {
                shown = !o.available && o.detail.startsWith("Unavailable");
            }
        }
        assertTrue(shown);
        assertTrue(SpeechModels.summary(context, "custom_1").contains("unavailable"));
    }

    // ------------------------------------------------------------ storage and round trips

    @Test
    public void deletingOrResettingAStyleForgetsItsSpeechModel() {
        String id = Prefs.addPromptProfile(context, "Kite crew");
        Prefs.setSpeechModelForStyle(context, id, SONIOX_STORED);
        Prefs.deletePromptProfile(context, id);
        assertNull(Prefs.speechModelForStyle(context, id));

        Prefs.setSpeechModelForStyle(context, Prefs.PRESET_CASUAL, SONIOX_STORED);
        Prefs.resetStylesToClean(context);
        assertNull(Prefs.speechModelForStyle(context, Prefs.PRESET_CASUAL));
    }

    @Test
    public void storedStyleFieldsThisVersionDoesntKnowSurviveASave() throws Exception {
        Prefs.promptProfiles(context);
        JSONArray stored = new JSONArray(Prefs.shared(context).getString("prompts_json", "[]"));
        stored.getJSONObject(0).put("speechModel", new JSONObject().put("provider", "soniox"));
        stored.getJSONObject(0).put("futureFlag", true);
        String firstId = stored.getJSONObject(0).getString("id");
        Prefs.shared(context).edit().putString("prompts_json", stored.toString()).commit();

        // A rename, and an unrelated new style, both rewrite the list.
        Prefs.savePromptProfile(context, firstId, "Renamed", "", "Prompt text", "");
        Prefs.addPromptProfile(context, "Another one");

        JSONArray after = new JSONArray(Prefs.shared(context).getString("prompts_json", "[]"));
        JSONObject first = null;
        for (int i = 0; i < after.length(); i++) {
            if (firstId.equals(after.getJSONObject(i).getString("id"))) {
                first = after.getJSONObject(i);
            }
        }
        assertEquals("Renamed", first.getString("name"));
        assertEquals("soniox", first.getJSONObject("speechModel").getString("provider"));
        assertTrue(first.getBoolean("futureFlag"));
    }

    @Test
    public void extrasThatArentAnObjectNeverLoseTheStyle() throws Exception {
        PromptProfile odd = new PromptProfile("custom_9", "Odd", "", "not json");
        JSONObject json = odd.toJson();
        assertEquals("custom_9", json.getString("id"));
        assertEquals("", PromptProfile.extrasOf(new JSONObject().put("id", "a").put("name", "b").put("icon", "")));
    }

    @Test
    public void encodeAndParseRoundTrip() {
        SpeechModels.Stored parsed = SpeechModels.parse(SpeechModels.encode(new SpeechModels.Choice("soniox", "stt-async-v6")));
        assertEquals(SpeechModels.Kind.OWN, parsed.kind);
        assertEquals("soniox", parsed.own.provider);
        assertEquals("stt-async-v6", parsed.own.model);
        assertEquals(SpeechModels.Kind.APP_DEFAULT, SpeechModels.parse("app").kind);
        assertEquals(SpeechModels.Kind.INHERIT, SpeechModels.parse(null).kind);
    }

    @Test
    public void sonioxIsAKnownSpeechProviderThatTakesChinese() {
        assertEquals(Prefs.PROVIDER_SONIOX, Prefs.sanitizeTranscriptionProvider(Prefs.PROVIDER_SONIOX));
        assertTrue(Prefs.supportsChineseDictation(Prefs.PROVIDER_SONIOX));
        assertEquals("Soniox", Prefs.providerLabel(Prefs.PROVIDER_SONIOX));
        assertEquals("stt-async-v6", Prefs.defaultTranscriptionModel(Prefs.PROVIDER_SONIOX));
        assertFalse(Prefs.hasApiKeyForProvider(context, Prefs.PROVIDER_SONIOX));
        Prefs.setSonioxApiKey(context, "  test-soniox-key-0000  ");
        assertEquals("test-soniox-key-0000", Prefs.sonioxApiKey(context));
        assertTrue(Prefs.hasApiKeyForProvider(context, Prefs.PROVIDER_SONIOX));
        assertEquals(1, Prefs.savedApiKeyCount(context));
    }
}
