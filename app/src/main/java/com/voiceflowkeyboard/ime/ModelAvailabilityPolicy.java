package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decisions behind the model availability check, kept free of Android so
 * they can be tested directly.
 *
 * <p>A selected model is replaced only when its own provider says, in its
 * documented not-found response, that the model does not exist. Anything else,
 * including every 400, an auth failure, a rate limit, a server error or a
 * generic 404, leaves the selection alone. The replacement is always the same
 * provider's default; the provider itself never changes.
 */
final class ModelAvailabilityPolicy {
    static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    enum Verdict {
        EXISTS,
        MISSING,
        /** The lookup failed for another reason: act on nothing. */
        UNKNOWN
    }

    /** One stored model selection, captured when a check or request starts. */
    static final class Slot {
        final boolean transcription;
        /** The SharedPreferences key the selection lives under. */
        final String prefKey;
        final String provider;
        /** The raw stored value at capture time; null when nothing is stored. */
        final String stored;
        /** The model a request actually uses: the stored value, or the default. */
        final String model;
        final String defaultModel;

        Slot(boolean transcription, String prefKey, String provider, String stored, String defaultModel) {
            this.transcription = transcription;
            this.prefKey = prefKey;
            this.provider = provider;
            this.stored = stored;
            this.defaultModel = defaultModel;
            this.model = trim(stored).isEmpty() ? defaultModel : trim(stored);
        }

        boolean usesDefault() {
            return model.equalsIgnoreCase(defaultModel);
        }
    }

    private ModelAvailabilityPolicy() {
    }

    /**
     * Whether an error is the provider's documented "this model does not exist"
     * response for {@code requestedModel}.
     *
     * <ul>
     *   <li>Anthropic: 404, {@code error.type == "not_found_error"}, and a
     *       message that names the model ({@code "model: <id>"}).</li>
     *   <li>OpenAI: 404 and {@code error.code == "model_not_found"}.</li>
     *   <li>xAI: 404, {@code code == "not-found"}, and the captured message
     *       {@code "The model <id> does not exist ..."} naming this model.</li>
     *   <li>Deepgram: never. Its documented unknown-model error is a 400 that
     *       also fires for an unsupported language, so it cannot tell the two
     *       apart.</li>
     * </ul>
     */
    static boolean isModelMissing(
            String provider,
            int status,
            String errorType,
            String errorCode,
            String errorMessage,
            String requestedModel
    ) {
        if (status != 404) {
            return false;
        }
        String message = trim(errorMessage);
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
            return "not_found_error".equals(trim(errorType))
                    && message.toLowerCase(Locale.US).startsWith("model:");
        }
        if (Prefs.PROVIDER_OPENAI.equals(provider)) {
            return "model_not_found".equals(trim(errorCode));
        }
        if (Prefs.PROVIDER_XAI.equals(provider)) {
            String model = trim(requestedModel);
            return "not-found".equals(trim(errorCode))
                    && !model.isEmpty()
                    && message.startsWith("The model " + model + " does not exist");
        }
        return false;
    }

    static boolean isModelMissing(ProviderHttpException e, String requestedModel) {
        return e != null && isModelMissing(
                e.provider,
                e.status,
                e.errorType,
                e.errorCode,
                e.errorMessage,
                requestedModel
        );
    }

    /**
     * The result of looking a model up by its exact ID. Any 2xx means it exists,
     * even when the provider answers with a different ID: Anthropic resolves the
     * alias {@code claude-haiku-4-5} to {@code claude-haiku-4-5-20251001}.
     */
    static Verdict lookupVerdict(
            String provider,
            int status,
            String errorType,
            String errorCode,
            String errorMessage,
            String requestedModel
    ) {
        if (status >= 200 && status < 300) {
            return Verdict.EXISTS;
        }
        return isModelMissing(provider, status, errorType, errorCode, errorMessage, requestedModel)
                ? Verdict.MISSING
                : Verdict.UNKNOWN;
    }

    /** Providers with a by-ID model lookup the daily check can use. */
    static boolean supportsLookup(String provider) {
        return Prefs.PROVIDER_OPENAI.equals(provider) || Prefs.PROVIDER_ANTHROPIC.equals(provider);
    }

    static boolean isOffline(String provider) {
        return Prefs.PROVIDER_OFFLINE_VOSK.equals(provider) || Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider);
    }

    /**
     * Every cloud model selection that is currently in use: the transcription
     * model, the global transform model, and each per-preset transform override
     * stored for the active transform provider. Offline models are skipped, and
     * so is xAI transcription, which sends no model at all.
     *
     * @param overrides every stored per-preset override, key to raw value
     */
    static List<Slot> slotsInUse(
            String transcriptionProvider,
            String transcriptionStored,
            String transformProvider,
            String transformStored,
            Map<String, String> overrides
    ) {
        List<Slot> slots = new ArrayList<>();
        if (!isOffline(transcriptionProvider) && !Prefs.PROVIDER_XAI.equals(transcriptionProvider)) {
            slots.add(transcriptionSlot(transcriptionProvider, transcriptionStored));
        }
        slots.add(transformSlot(Prefs.MODEL_KEY_TRANSFORM, transformProvider, transformStored));
        String prefix = Prefs.MODEL_KEY_TRANSFORM_OVERRIDE_PREFIX + transformProvider + "_";
        if (overrides != null) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                if (entry.getKey().startsWith(prefix) && !trim(entry.getValue()).isEmpty()) {
                    slots.add(transformSlot(entry.getKey(), transformProvider, entry.getValue()));
                }
            }
        }
        return slots;
    }

    static Slot transcriptionSlot(String provider, String stored) {
        return new Slot(true, Prefs.MODEL_KEY_TRANSCRIPTION, provider, stored, Prefs.defaultTranscriptionModel(provider));
    }

    static Slot transformSlot(String prefKey, String provider, String stored) {
        return new Slot(false, prefKey, provider, stored, Prefs.defaultTransformModel(provider));
    }

    /** Key for one model in the throttle record. */
    static String checkKey(String provider, String model) {
        return provider + "/" + trim(model);
    }

    static boolean isDue(Long lastCheckedMillis, long nowMillis) {
        if (lastCheckedMillis == null) {
            return true;
        }
        // A clock set backwards should not postpone the check indefinitely.
        return nowMillis < lastCheckedMillis || nowMillis - lastCheckedMillis >= CHECK_INTERVAL_MS;
    }

    /**
     * The distinct (provider, model) pairs the daily check should look up now,
     * as check key to slot. Only providers with a by-ID lookup and a saved key
     * take part.
     */
    static Map<String, Slot> dueLookups(
            List<Slot> slots,
            Map<String, Long> lastChecked,
            long nowMillis,
            List<String> providersWithKeys
    ) {
        Map<String, Slot> due = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (!supportsLookup(slot.provider) || !providersWithKeys.contains(slot.provider)) {
                continue;
            }
            String key = checkKey(slot.provider, slot.model);
            if (!due.containsKey(key) && isDue(lastChecked.get(key), nowMillis)) {
                due.put(key, slot);
            }
        }
        return due;
    }

    /**
     * Compare-and-set test: a replacement may be written only when the user has
     * not changed this selection, or its provider, since the slot was captured.
     */
    static boolean unchangedSince(Slot slot, String currentProvider, String currentStored) {
        return slot.provider.equals(currentProvider)
                && trim(slot.stored).equals(trim(currentStored));
    }

    static String switchNotice(String fromModel, String toModel) {
        return displayName(fromModel) + " unavailable → " + displayName(toModel);
    }

    static String pickNotice(String model) {
        return displayName(model) + " unavailable — pick another model";
    }

    /** Adds a notice to what is already pending, without repeating it. */
    static String mergeNotice(String pending, String notice) {
        String existing = trim(pending);
        String added = trim(notice);
        if (added.isEmpty()) {
            return existing;
        }
        if (existing.isEmpty()) {
            return added;
        }
        for (String part : existing.split("; ")) {
            if (part.equals(added)) {
                return existing;
            }
        }
        return existing + "; " + added;
    }

    /**
     * Short model name for a notice: {@code claude-sonnet-5} becomes
     * "Sonnet 5" and {@code claude-haiku-4-5-20251001} becomes "Haiku 4.5".
     * Other providers' IDs are already short and are shown as they are.
     */
    static String displayName(String model) {
        String id = trim(model);
        String lower = id.toLowerCase(Locale.US);
        if (!lower.startsWith("claude-")) {
            return id;
        }
        List<String> words = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (String part : lower.substring("claude-".length()).split("-")) {
            if (part.isEmpty() || "latest".equals(part) || part.matches("\\d{8}")) {
                continue;
            }
            if (part.matches("\\d+")) {
                numbers.add(part);
            } else {
                words.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
            }
        }
        if (words.isEmpty()) {
            return id;
        }
        String name = String.join(" ", words);
        return numbers.isEmpty() ? name : name + " " + String.join(".", numbers);
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
