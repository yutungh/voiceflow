package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure rules behind the model availability check. A wrong "missing"
 * verdict silently changes the user's model, so the negative cases matter
 * as much as the positive ones.
 */
public class ModelAvailabilityPolicyTest {
    private static final String A = Prefs.PROVIDER_ANTHROPIC;
    private static final String O = Prefs.PROVIDER_OPENAI;
    private static final String X = Prefs.PROVIDER_XAI;
    private static final String D = Prefs.PROVIDER_DEEPGRAM;
    private static final long DAY = ModelAvailabilityPolicy.CHECK_INTERVAL_MS;
    private static final long HOUR = 60L * 60L * 1000L;

    private static boolean missing(String provider, int status, String type, String code, String message, String model) {
        return ModelAvailabilityPolicy.isModelMissing(provider, status, type, code, message, model);
    }

    @Test
    public void anthropicModelNotFoundIsMissing() {
        assertTrue(missing(A, 404, "not_found_error", "", "model: claude-sonnet-5", "claude-sonnet-5"));
        assertTrue(missing(A, 404, "not_found_error", "", "Model: claude-sonnet-5", "claude-sonnet-5"));
    }

    @Test
    public void anthropicNotFoundThatIsNotAboutTheModelIsNotMissing() {
        assertFalse(missing(A, 404, "not_found_error", "", "Not found", "claude-sonnet-5"));
        assertFalse(missing(A, 404, "not_found_error", "", "file: file_123", "claude-sonnet-5"));
        assertFalse(missing(A, 404, "", "", "", "claude-sonnet-5"));
        assertFalse(missing(A, 404, "invalid_request_error", "", "model: claude-sonnet-5", "claude-sonnet-5"));
    }

    @Test
    public void openAiModelNotFoundIsMissing() {
        assertTrue(missing(O, 404, "invalid_request_error", "model_not_found",
                "The model `gpt-9` does not exist or you do not have access to it.", "gpt-9"));
    }

    @Test
    public void openAiGeneric404IsNotMissing() {
        assertFalse(missing(O, 404, "invalid_request_error", "", "Invalid URL (POST /v1/respones)", "gpt-5.4-mini"));
        assertFalse(missing(O, 404, "", "", "", "gpt-5.4-mini"));
        assertFalse(missing(O, 404, "invalid_request_error", "unknown_url", "", "gpt-5.4-mini"));
    }

    @Test
    public void aFourHundredIsNeverMissingEvenWithMissingLookingFields() {
        // Includes the temperature rejection and Fable's data-retention refusal.
        for (String provider : new String[]{A, O, X, D}) {
            assertFalse(provider, missing(provider, 400, "not_found_error", "model_not_found", "model: x", "x"));
            assertFalse(provider, missing(provider, 400, "invalid_request_error", "",
                    "temperature: This model does not support temperature", "claude-sonnet-5"));
            assertFalse(provider, missing(provider, 400, "invalid_request_error", "",
                    "This model is not available for organizations with zero data retention", "claude-fable-5"));
            assertFalse(provider, missing(provider, 400, "not-found", "not-found",
                    "The model x does not exist or your team does not have access to it.", "x"));
        }
    }

    @Test
    public void authRateLimitAndServerErrorsAreNeverMissing() {
        for (int status : new int[]{401, 403, 408, 409, 413, 429, 500, 502, 503, 504, 529}) {
            assertFalse(String.valueOf(status), missing(A, status, "not_found_error", "", "model: x", "x"));
            assertFalse(String.valueOf(status), missing(O, status, "invalid_request_error", "model_not_found", "", "x"));
            assertFalse(String.valueOf(status), missing(X, status, "", "not-found", "The model x does not exist", "x"));
        }
    }

    @Test
    public void xAiIsMissingOnlyOnItsCapturedNotFoundNamingThisModel() {
        String message = "The model grok-9 does not exist or your team 00000000-0000-0000-0000-000000000000 "
                + "does not have access to it.";
        assertTrue(missing(X, 404, "", "not-found", message, "grok-9"));
        assertFalse(missing(X, 404, "", "not-found", message, "grok-4.3"));
        assertFalse(missing(X, 404, "", "not-found", "Route not found", "grok-9"));
        assertFalse(missing(X, 404, "", "", message, "grok-9"));
    }

    @Test
    public void deepgramIsNeverMissing() {
        assertFalse(missing(D, 400, "", "Bad Request",
                "Bad Request: No such model/language/tier combination found.", "nova-9"));
        assertFalse(missing(D, 404, "not_found_error", "model_not_found", "model: nova-9", "nova-9"));
    }

    @Test
    public void lookupTreatsAnyTwoHundredAsExistingSoAliasesResolve() {
        // Anthropic answers GET /v1/models/claude-haiku-4-5 with the dated ID;
        // the answer's ID is deliberately not compared with the requested one.
        assertEquals(ModelAvailabilityPolicy.Verdict.EXISTS,
                ModelAvailabilityPolicy.lookupVerdict(A, 200, "", "", "", "claude-haiku-4-5"));
        assertEquals(ModelAvailabilityPolicy.Verdict.MISSING,
                ModelAvailabilityPolicy.lookupVerdict(A, 404, "not_found_error", "", "model: claude-x", "claude-x"));
        assertEquals(ModelAvailabilityPolicy.Verdict.UNKNOWN,
                ModelAvailabilityPolicy.lookupVerdict(A, 500, "api_error", "", "Internal", "claude-x"));
        assertEquals(ModelAvailabilityPolicy.Verdict.UNKNOWN,
                ModelAvailabilityPolicy.lookupVerdict(O, 401, "invalid_request_error", "invalid_api_key", "", "gpt-x"));
        assertEquals(ModelAvailabilityPolicy.Verdict.UNKNOWN,
                ModelAvailabilityPolicy.lookupVerdict(O, 400, "invalid_request_error", "model_not_found", "", "gpt-x"));
    }

    @Test
    public void slotsCoverTranscriptionTransformAndThisProvidersOverrides() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("transform_model_override_anthropic_business", "claude-opus-4-1");
        overrides.put("transform_model_override_anthropic_family", "  ");
        overrides.put("transform_model_override_openai_business", "gpt-4.1");

        List<ModelAvailabilityPolicy.Slot> slots = ModelAvailabilityPolicy.slotsInUse(
                O, "gpt-4o-transcribe", A, "claude-sonnet-5", overrides);

        assertEquals(3, slots.size());
        assertTrue(slots.get(0).transcription);
        assertEquals("transcription_model", slots.get(0).prefKey);
        assertEquals("gpt-4o-transcribe", slots.get(0).model);
        assertEquals("gpt-transcribe", slots.get(0).defaultModel);
        assertEquals("transform_model", slots.get(1).prefKey);
        assertEquals("claude-sonnet-5", slots.get(1).model);
        assertEquals("claude-haiku-4-5", slots.get(1).defaultModel);
        assertEquals("transform_model_override_anthropic_business", slots.get(2).prefKey);
        assertEquals("claude-opus-4-1", slots.get(2).model);
        assertEquals(A, slots.get(2).provider);
    }

    @Test
    public void offlineAndXaiTranscriptionAreSkippedAndEmptyMeansDefault() {
        for (String offline : new String[]{Prefs.PROVIDER_OFFLINE_VOSK, Prefs.PROVIDER_OFFLINE_PARAKEET, X}) {
            List<ModelAvailabilityPolicy.Slot> slots = ModelAvailabilityPolicy.slotsInUse(
                    offline, "whatever", O, null, Collections.emptyMap());
            assertEquals(offline, 1, slots.size());
            assertFalse(slots.get(0).transcription);
            assertEquals("gpt-5.4-mini", slots.get(0).model);
            assertTrue(slots.get(0).usesDefault());
        }
    }

    @Test
    public void dueLookupsAreDistinctThrottledAndLimitedToLookupProvidersWithKeys() {
        List<ModelAvailabilityPolicy.Slot> slots = Arrays.asList(
                ModelAvailabilityPolicy.transformSlot("transform_model", A, "claude-sonnet-5"),
                ModelAvailabilityPolicy.transformSlot("transform_model_override_anthropic_business", A, "claude-sonnet-5"),
                ModelAvailabilityPolicy.transformSlot("transform_model_override_anthropic_family", A, "claude-opus-4-8"),
                ModelAvailabilityPolicy.transcriptionSlot(O, "gpt-4o-transcribe"),
                ModelAvailabilityPolicy.transcriptionSlot(D, "nova-3")
        );
        Map<String, Long> last = new HashMap<>();
        last.put("anthropic/claude-opus-4-8", 1000L);
        List<String> keys = Arrays.asList(A, O, D);

        Map<String, ModelAvailabilityPolicy.Slot> due = ModelAvailabilityPolicy.dueLookups(slots, last, 1000L + HOUR, keys);
        assertEquals(Arrays.asList("anthropic/claude-sonnet-5", "openai/gpt-4o-transcribe"),
                Arrays.asList(due.keySet().toArray(new String[0])));

        due = ModelAvailabilityPolicy.dueLookups(slots, last, 1000L + DAY, Collections.singletonList(A));
        assertEquals(Arrays.asList("anthropic/claude-sonnet-5", "anthropic/claude-opus-4-8"),
                Arrays.asList(due.keySet().toArray(new String[0])));
    }

    @Test
    public void throttleAllowsOneCheckPerDayAndSurvivesAClockSetBack() {
        assertTrue(ModelAvailabilityPolicy.isDue(null, 5000L));
        assertFalse(ModelAvailabilityPolicy.isDue(5000L, 5000L));
        assertFalse(ModelAvailabilityPolicy.isDue(5000L, 5000L + DAY - 1));
        assertTrue(ModelAvailabilityPolicy.isDue(5000L, 5000L + DAY));
        assertTrue(ModelAvailabilityPolicy.isDue(5000L + DAY, 5000L));
    }

    @Test
    public void compareAndSetRefusesAnyChangeSinceCapture() {
        ModelAvailabilityPolicy.Slot slot = ModelAvailabilityPolicy.transformSlot("transform_model", A, "claude-sonnet-5");
        assertTrue(ModelAvailabilityPolicy.unchangedSince(slot, A, "claude-sonnet-5"));
        assertTrue(ModelAvailabilityPolicy.unchangedSince(slot, A, " claude-sonnet-5 "));
        assertFalse(ModelAvailabilityPolicy.unchangedSince(slot, A, "claude-opus-4-8"));
        assertFalse(ModelAvailabilityPolicy.unchangedSince(slot, O, "claude-sonnet-5"));
        assertFalse(ModelAvailabilityPolicy.unchangedSince(slot, A, null));

        ModelAvailabilityPolicy.Slot unset = ModelAvailabilityPolicy.transformSlot("transform_model", A, null);
        assertTrue(ModelAvailabilityPolicy.unchangedSince(unset, A, null));
        assertTrue(ModelAvailabilityPolicy.unchangedSince(unset, A, ""));
        assertFalse(ModelAvailabilityPolicy.unchangedSince(unset, A, "claude-sonnet-5"));
    }

    @Test
    public void noticesUseShortModelNames() {
        assertEquals("Sonnet 5 unavailable → Haiku 4.5",
                ModelAvailabilityPolicy.switchNotice("claude-sonnet-5", "claude-haiku-4-5"));
        assertEquals("Opus 4.1 unavailable — pick another model",
                ModelAvailabilityPolicy.pickNotice("claude-opus-4-1"));
        assertEquals("Haiku 4.5", ModelAvailabilityPolicy.displayName("claude-haiku-4-5-20251001"));
        assertEquals("Sonnet 3.5", ModelAvailabilityPolicy.displayName("claude-3-5-sonnet-20241022"));
        assertEquals("Opus 5.5", ModelAvailabilityPolicy.displayName("claude-opus-5-5"));
        assertEquals("gpt-5.4-mini", ModelAvailabilityPolicy.displayName("gpt-5.4-mini"));
    }

    @Test
    public void noticesMergeWithoutRepeating() {
        assertEquals("a", ModelAvailabilityPolicy.mergeNotice(null, "a"));
        assertEquals("a", ModelAvailabilityPolicy.mergeNotice("a", "a"));
        assertEquals("a; b", ModelAvailabilityPolicy.mergeNotice("a", "b"));
        assertEquals("a; b", ModelAvailabilityPolicy.mergeNotice("a; b", "b"));
        assertEquals("a", ModelAvailabilityPolicy.mergeNotice("a", " "));
    }

    @Test
    public void onlyOpenAiAndAnthropicHaveTheByIdLookup() {
        assertTrue(ModelAvailabilityPolicy.supportsLookup(O));
        assertTrue(ModelAvailabilityPolicy.supportsLookup(A));
        assertFalse(ModelAvailabilityPolicy.supportsLookup(X));
        assertFalse(ModelAvailabilityPolicy.supportsLookup(D));
    }
}
