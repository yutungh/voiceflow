package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The availability check against real SharedPreferences: what gets replaced,
 * what is left alone, and the notice the user sees.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ModelAvailabilityTest {
    private static final String A = Prefs.PROVIDER_ANTHROPIC;
    private static final String O = Prefs.PROVIDER_OPENAI;
    private static final String BUSINESS_OVERRIDE = "transform_model_override_anthropic_business";
    private static final long T0 = 1_750_000_000_000L;
    private static final long HOUR = 60L * 60L * 1000L;

    private Context context;
    private FakeLookup lookup;
    private final AtomicLong now = new AtomicLong(T0);

    /** Answers from a table; unlisted models are UNKNOWN. Counts every call. */
    private static final class FakeLookup implements ModelAvailability.ModelLookup {
        final Map<String, ModelAvailabilityPolicy.Verdict> answers = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final Map<String, Runnable> during = new HashMap<>();

        FakeLookup answer(String provider, String model, ModelAvailabilityPolicy.Verdict verdict) {
            answers.put(provider + "/" + model, verdict);
            return this;
        }

        @Override
        public ModelAvailabilityPolicy.Verdict lookup(String provider, String apiKey, String model) {
            String key = provider + "/" + model;
            calls.add(key);
            Runnable hook = during.get(key);
            if (hook != null) {
                hook.run();
            }
            ModelAvailabilityPolicy.Verdict verdict = answers.get(key);
            return verdict == null ? ModelAvailabilityPolicy.Verdict.UNKNOWN : verdict;
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        Prefs.saveApiKeys(context, "test-openai-key", "test-anthropic-key", "", "");
        lookup = new FakeLookup().answer(O, "gpt-transcribe", ModelAvailabilityPolicy.Verdict.EXISTS);
    }

    private void selectClaude(String model) {
        Prefs.setTransformProvider(context, A);
        Prefs.setTransformModel(context, model);
    }

    private void runCheck() {
        ModelAvailability.runCheck(context, lookup, now::get);
    }

    private static ProviderHttpException anthropicMissing(String model) {
        return ProviderHttpException.fromResponse(A, "Claude transform", 404,
                "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: " + model + "\"}}");
    }

    private static ProviderHttpException anthropicError(int status, String type) {
        return ProviderHttpException.fromResponse(A, "Claude transform", status,
                "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"nope\"}}");
    }

    // Proactive path

    @Test
    public void missingModelIsReplacedByConfirmedDefaultWithOneNotice() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.EXISTS);

        runCheck();

        assertEquals("claude-haiku-4-5", Prefs.transformModel(context));
        assertEquals(A, Prefs.transformProvider(context));
        assertEquals("Sonnet 5 unavailable → Haiku 4.5", Prefs.takeModelSwitchNotice(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void existingModelIsLeftAlone() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.EXISTS);

        runCheck();

        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
        assertFalse(lookup.calls.contains("anthropic/claude-haiku-4-5"));
    }

    @Test
    public void unknownLookupChangesNothing() {
        selectClaude("claude-sonnet-5");
        // The lookup failed (network, 401, 429, 5xx): no verdict, no change.
        runCheck();

        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void perPresetOverrideIsCheckedAndReplacedOnItsOwnKey() {
        selectClaude("claude-sonnet-5");
        Prefs.shared(context).edit()
                .putString(BUSINESS_OVERRIDE, "claude-opus-4-1")
                .putString("transform_model_override_openai_business", "gpt-4.1")
                .commit();
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.EXISTS)
                .answer(A, "claude-opus-4-1", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.EXISTS);

        runCheck();

        assertEquals("claude-haiku-4-5", Prefs.transformModelForPreset(context, Prefs.PRESET_BUSINESS));
        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertEquals("gpt-4.1", Prefs.shared(context).getString("transform_model_override_openai_business", null));
        assertFalse("inactive provider's override is not in use", lookup.calls.contains("openai/gpt-4.1"));
        assertEquals("Opus 4.1 unavailable → Haiku 4.5", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void userChangingTheModelMidCheckIsNeverOverwritten() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.EXISTS);
        lookup.during.put("anthropic/claude-sonnet-5", () -> Prefs.setTransformModel(context, "claude-opus-4-8"));

        runCheck();

        assertEquals("claude-opus-4-8", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void userSwitchingProviderMidCheckIsNeverOverwritten() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.EXISTS);
        lookup.during.put("anthropic/claude-sonnet-5", () -> Prefs.setTransformProvider(context, O));

        runCheck();

        assertEquals(O, Prefs.transformProvider(context));
        assertEquals("gpt-5.4-mini", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void missingDefaultMeansNoChainedFallbackJustAPickNotice() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.MISSING);

        runCheck();

        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertEquals("Sonnet 5 unavailable — pick another model", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void unconfirmedDefaultIsNotSwitchedTo() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.MISSING);

        runCheck();

        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void missingSelectedDefaultAsksTheUserToPick() {
        selectClaude("claude-haiku-4-5");
        lookup.answer(A, "claude-haiku-4-5", ModelAvailabilityPolicy.Verdict.MISSING);

        runCheck();

        assertEquals("claude-haiku-4-5", Prefs.transformModel(context));
        assertEquals("Haiku 4.5 unavailable — pick another model", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void transcriptionModelIsCheckedAndReplacedWithinOpenAi() {
        Prefs.setTranscriptionProvider(context, O);
        Prefs.setTranscriptionModel(context, "gpt-4o-mini-transcribe");
        lookup.answer(O, "gpt-4o-mini-transcribe", ModelAvailabilityPolicy.Verdict.MISSING)
                .answer(O, "gpt-5.4-mini", ModelAvailabilityPolicy.Verdict.EXISTS);

        runCheck();

        assertEquals("gpt-transcribe", Prefs.transcriptionModel(context));
        assertEquals(O, Prefs.transcriptionProvider(context));
        assertEquals("gpt-4o-mini-transcribe unavailable → gpt-transcribe", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void eachModelIsLookedUpAtMostOncePerDay() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.EXISTS);
        List<Runnable> queued = new ArrayList<>();

        assertTrue(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        queued.remove(0).run();
        assertEquals(1, count("anthropic/claude-sonnet-5"));

        now.set(T0 + 23 * HOUR);
        assertFalse(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        assertTrue(queued.isEmpty());

        now.set(T0 + 24 * HOUR);
        assertTrue(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        queued.remove(0).run();
        assertEquals(2, count("anthropic/claude-sonnet-5"));
    }

    @Test
    public void aFailedLookupAlsoWaitsADay() {
        selectClaude("claude-sonnet-5");
        runCheck();
        runCheck();
        assertEquals(1, count("anthropic/claude-sonnet-5"));
    }

    @Test
    public void duplicateRequestsMergeIntoTheCheckInFlight() {
        selectClaude("claude-sonnet-5");
        lookup.answer(A, "claude-sonnet-5", ModelAvailabilityPolicy.Verdict.EXISTS);
        List<Runnable> queued = new ArrayList<>();

        assertTrue(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        assertFalse(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        assertFalse(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        assertEquals(1, queued.size());

        queued.remove(0).run();
        assertEquals(1, count("anthropic/claude-sonnet-5"));
    }

    @Test
    public void nothingIsScheduledWithoutAKeyOrForProvidersWithoutALookup() {
        List<Runnable> queued = new ArrayList<>();
        Prefs.saveApiKeys(context, "", "", "test-xai-key", "test-deepgram-key");
        Prefs.setTransformProvider(context, Prefs.PROVIDER_XAI);
        Prefs.setTranscriptionProvider(context, Prefs.PROVIDER_DEEPGRAM);

        assertFalse(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));

        Prefs.setTranscriptionProvider(context, Prefs.PROVIDER_OFFLINE_VOSK);
        assertFalse(ModelAvailability.checkIfDue(context, queued::add, lookup, now::get));
        assertTrue(queued.isEmpty());
        assertTrue(lookup.calls.isEmpty());
    }

    // Reactive path

    @Test
    public void reactiveRetryOnDefaultSavesOnlyWhenItSucceeds() throws Exception {
        selectClaude("claude-sonnet-5");
        List<String> models = new ArrayList<>();

        String result = ModelAvailability.callWithFallback(context, Prefs.transformModelSlot(context), model -> {
            models.add(model);
            if (model.equals("claude-sonnet-5")) {
                throw anthropicMissing(model);
            }
            return "ok:" + model;
        });

        assertEquals("ok:claude-haiku-4-5", result);
        assertEquals(2, models.size());
        assertEquals("claude-haiku-4-5", Prefs.transformModel(context));
        assertEquals("Sonnet 5 unavailable → Haiku 4.5", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void failedRetryKeepsTheSelectionAndRethrowsTheOriginalError() {
        selectClaude("claude-sonnet-5");
        ProviderHttpException original = anthropicMissing("claude-sonnet-5");
        try {
            ModelAvailability.callWithFallback(context, Prefs.transformModelSlot(context), model -> {
                throw model.equals("claude-sonnet-5") ? original : anthropicError(529, "overloaded_error");
            });
            fail("expected the original error");
        } catch (Exception e) {
            assertSame(original, e);
            assertEquals(1, e.getSuppressed().length);
        }
        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void aFourHundredIsNeverRetried() {
        selectClaude("claude-sonnet-5");
        ProviderHttpException rejected = anthropicError(400, "invalid_request_error");
        List<String> models = new ArrayList<>();
        try {
            ModelAvailability.callWithFallback(context, Prefs.transformModelSlot(context), model -> {
                models.add(model);
                throw rejected;
            });
            fail("expected the 400");
        } catch (Exception e) {
            assertSame(rejected, e);
        }
        assertEquals(1, models.size());
        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
    }

    @Test
    public void reactiveRetryDoesNotOverwriteASelectionChangedDuringTheRequest() throws Exception {
        selectClaude("claude-sonnet-5");

        String result = ModelAvailability.callWithFallback(context, Prefs.transformModelSlot(context), model -> {
            if (model.equals("claude-sonnet-5")) {
                Prefs.setTransformModel(context, "claude-opus-4-8");
                throw anthropicMissing(model);
            }
            return "ok:" + model;
        });

        assertEquals("ok:claude-haiku-4-5", result);
        assertEquals("claude-opus-4-8", Prefs.transformModel(context));
        assertNull(Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void reactiveMissingDefaultLeavesAPickNotice() {
        selectClaude("claude-sonnet-5");
        try {
            ModelAvailability.callWithFallback(context, Prefs.transformModelSlot(context), model -> {
                throw anthropicMissing(model);
            });
            fail("expected the original error");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("model: claude-sonnet-5"));
        }
        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
        assertEquals("Sonnet 5 unavailable — pick another model", Prefs.takeModelSwitchNotice(context));
    }

    @Test
    public void reactivePresetFallbackReplacesOnlyThatPresetsOverride() throws Exception {
        selectClaude("claude-sonnet-5");
        Prefs.shared(context).edit().putString(BUSINESS_OVERRIDE, "claude-opus-4-1").commit();
        ModelAvailabilityPolicy.Slot slot = Prefs.transformModelSlotForPreset(context, Prefs.PRESET_BUSINESS);
        assertEquals(BUSINESS_OVERRIDE, slot.prefKey);
        assertEquals("claude-opus-4-1", slot.model);
        assertEquals("transform_model", Prefs.transformModelSlotForPreset(context, Prefs.PRESET_FAMILY).prefKey);

        ModelAvailability.callWithFallback(context, slot, model -> {
            if (model.equals("claude-opus-4-1")) {
                throw anthropicMissing(model);
            }
            return "ok";
        });

        assertEquals("claude-haiku-4-5", Prefs.shared(context).getString(BUSINESS_OVERRIDE, null));
        assertEquals("claude-sonnet-5", Prefs.transformModel(context));
    }

    @Test
    public void reactiveTranscriptionFallbackStaysWithOpenAi() throws Exception {
        Prefs.setTranscriptionProvider(context, O);
        Prefs.setTranscriptionModel(context, "gpt-4o-mini-transcribe");

        String text = ModelAvailability.callWithFallback(context, Prefs.transcriptionModelSlot(context), model -> {
            if (model.equals("gpt-4o-mini-transcribe")) {
                throw ProviderHttpException.fromResponse(O, "OpenAI request", 404,
                        "{\"error\":{\"message\":\"The model `" + model + "` does not exist or you do not have access to it.\","
                                + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"model_not_found\"}}");
            }
            return "hello";
        });

        assertEquals("hello", text);
        assertEquals("gpt-transcribe", Prefs.transcriptionModel(context));
        assertEquals(O, Prefs.transcriptionProvider(context));
    }

    @Test
    public void deepgramErrorsAreNeverRetried() {
        Prefs.setTranscriptionProvider(context, Prefs.PROVIDER_DEEPGRAM);
        Prefs.setTranscriptionModel(context, "nova-9");
        List<String> models = new ArrayList<>();
        try {
            ModelAvailability.callWithFallback(context, Prefs.transcriptionModelSlot(context), model -> {
                models.add(model);
                throw ProviderHttpException.fromResponse(Prefs.PROVIDER_DEEPGRAM, "Deepgram transcription", 400,
                        "{\"err_code\":\"Bad Request\",\"err_msg\":\"Bad Request: No such model/language/tier combination found.\"}");
            });
            fail("expected the 400");
        } catch (Exception expected) {
            assertTrue(expected instanceof ProviderHttpException);
        }
        assertEquals(1, models.size());
        assertEquals("nova-9", Prefs.transcriptionModel(context));
    }

    private int count(String key) {
        int n = 0;
        for (String call : lookup.calls) {
            if (call.equals(key)) {
                n++;
            }
        }
        return n;
    }
}
