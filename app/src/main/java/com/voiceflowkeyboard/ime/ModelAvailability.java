package com.voiceflowkeyboard.ime;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Keeps the selected cloud models usable when a provider retires one.
 *
 * <p>Two paths, both limited to the same provider's default model:
 * <ul>
 *   <li>Proactive: {@link #checkIfDue} looks each in-use OpenAI or Anthropic
 *       model up by exact ID, at most once a day per model, off the main
 *       thread. A model its provider reports missing is replaced by the
 *       provider default once that default is confirmed to exist.</li>
 *   <li>Reactive: {@link #callWithFallback} wraps a request. When it fails
 *       with the provider's documented not-found response, the request is
 *       retried once on the default, and the switch is saved only if that
 *       retry succeeds.</li>
 * </ul>
 * A replacement is written only if the user has not changed that selection
 * since the check or request began. Every switch, and every missing model with
 * no usable default, leaves a notice for {@link Prefs#takeModelSwitchNotice}.
 */
final class ModelAvailability {
    /** One request made on a given model. */
    interface ModelCall {
        String run(String model) throws Exception;
    }

    /** Looks one model up by exact ID. Never throws; failures are UNKNOWN. */
    interface ModelLookup {
        ModelAvailabilityPolicy.Verdict lookup(String provider, String apiKey, String model);
    }

    private static final AtomicBoolean CHECK_IN_FLIGHT = new AtomicBoolean(false);

    private ModelAvailability() {
    }

    /**
     * Starts the daily availability check on {@code executor} if any in-use
     * model is due. Cheap enough to call on every keyboard start: it only reads
     * preferences on the calling thread, and the network work happens on the
     * executor. A call made while a check is running is merged into it.
     *
     * @return true when a check was handed to the executor
     */
    static boolean checkIfDue(Context context, Executor executor) {
        return checkIfDue(context, executor, ModelAvailability::lookupOverHttp, System::currentTimeMillis);
    }

    static boolean checkIfDue(Context context, Executor executor, ModelLookup lookup, LongSupplier clock) {
        if (context == null || executor == null) {
            return false;
        }
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        if (!CHECK_IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        boolean handedOff = false;
        try {
            if (dueLookups(app, clock.getAsLong()).isEmpty()) {
                return false;
            }
            executor.execute(() -> {
                try {
                    runCheck(app, lookup, clock);
                } catch (RuntimeException ignored) {
                    // A failed check changes nothing; the next due check retries.
                } finally {
                    CHECK_IN_FLIGHT.set(false);
                }
            });
            handedOff = true;
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        } finally {
            if (!handedOff) {
                CHECK_IN_FLIGHT.set(false);
            }
        }
    }

    /** The check itself; runs on the executor. */
    static void runCheck(Context context, ModelLookup lookup, LongSupplier clock) {
        List<ModelAvailabilityPolicy.Slot> slots = Prefs.modelSlotsInUse(context);
        Map<String, ModelAvailabilityPolicy.Slot> due = ModelAvailabilityPolicy.dueLookups(
                slots,
                Prefs.modelCheckTimes(context),
                clock.getAsLong(),
                providersWithKeys(context)
        );
        Map<String, ModelAvailabilityPolicy.Verdict> verdicts = new HashMap<>();
        for (Map.Entry<String, ModelAvailabilityPolicy.Slot> entry : due.entrySet()) {
            ModelAvailabilityPolicy.Slot slot = entry.getValue();
            verdicts.put(entry.getKey(), lookupAndRecord(context, lookup, slot.provider, slot.model, clock));
        }

        Set<String> notices = new LinkedHashSet<>();
        for (ModelAvailabilityPolicy.Slot slot : slots) {
            if (verdicts.get(ModelAvailabilityPolicy.checkKey(slot.provider, slot.model))
                    != ModelAvailabilityPolicy.Verdict.MISSING) {
                continue;
            }
            if (slot.usesDefault()) {
                if (Prefs.modelSelectionUnchanged(context, slot)) {
                    notices.add(ModelAvailabilityPolicy.pickNotice(slot.model));
                }
                continue;
            }
            String defaultKey = ModelAvailabilityPolicy.checkKey(slot.provider, slot.defaultModel);
            ModelAvailabilityPolicy.Verdict defaultVerdict = verdicts.get(defaultKey);
            if (defaultVerdict == null) {
                defaultVerdict = lookupAndRecord(context, lookup, slot.provider, slot.defaultModel, clock);
                verdicts.put(defaultKey, defaultVerdict);
            }
            if (defaultVerdict == ModelAvailabilityPolicy.Verdict.EXISTS) {
                if (Prefs.replaceModelIfUnchanged(context, slot, slot.defaultModel)) {
                    notices.add(ModelAvailabilityPolicy.switchNotice(slot.model, slot.defaultModel));
                }
            } else if (defaultVerdict == ModelAvailabilityPolicy.Verdict.MISSING) {
                // No chained fallbacks: the user picks the next model.
                if (Prefs.modelSelectionUnchanged(context, slot)) {
                    notices.add(ModelAvailabilityPolicy.pickNotice(slot.model));
                }
            }
            // UNKNOWN: the default could not be confirmed, so nothing changes.
        }
        for (String notice : notices) {
            Prefs.addModelSwitchNotice(context, notice);
        }
    }

    /**
     * Runs {@code call} on the slot's model. If the provider reports that model
     * missing, retries once on the provider default and, only if that works,
     * saves the default into the slot. Any other failure is rethrown untouched,
     * and a failed retry rethrows the original error.
     */
    static String callWithFallback(Context context, ModelAvailabilityPolicy.Slot slot, ModelCall call) throws Exception {
        try {
            return call.run(slot.model);
        } catch (ProviderHttpException e) {
            if (!ModelAvailabilityPolicy.isModelMissing(e, slot.model)) {
                throw e;
            }
            if (slot.usesDefault()) {
                if (Prefs.modelSelectionUnchanged(context, slot)) {
                    Prefs.addModelSwitchNotice(context, ModelAvailabilityPolicy.pickNotice(slot.model));
                }
                throw e;
            }
            String result;
            try {
                result = call.run(slot.defaultModel);
            } catch (ProviderHttpException retryError) {
                if (ModelAvailabilityPolicy.isModelMissing(retryError, slot.defaultModel)
                        && Prefs.modelSelectionUnchanged(context, slot)) {
                    Prefs.addModelSwitchNotice(context, ModelAvailabilityPolicy.pickNotice(slot.model));
                }
                e.addSuppressed(retryError);
                throw e;
            } catch (Exception retryError) {
                e.addSuppressed(retryError);
                throw e;
            }
            if (Prefs.replaceModelIfUnchanged(context, slot, slot.defaultModel)) {
                Prefs.addModelSwitchNotice(context, ModelAvailabilityPolicy.switchNotice(slot.model, slot.defaultModel));
            }
            return result;
        }
    }

    private static Map<String, ModelAvailabilityPolicy.Slot> dueLookups(Context context, long nowMillis) {
        return ModelAvailabilityPolicy.dueLookups(
                Prefs.modelSlotsInUse(context),
                Prefs.modelCheckTimes(context),
                nowMillis,
                providersWithKeys(context)
        );
    }

    private static ModelAvailabilityPolicy.Verdict lookupAndRecord(
            Context context,
            ModelLookup lookup,
            String provider,
            String model,
            LongSupplier clock
    ) {
        ModelAvailabilityPolicy.Verdict verdict;
        try {
            verdict = lookup.lookup(provider, Prefs.apiKeyForProvider(context, provider), model);
        } catch (RuntimeException e) {
            verdict = ModelAvailabilityPolicy.Verdict.UNKNOWN;
        }
        // Recorded whatever the outcome, so a failing lookup is not retried on
        // every keyboard start.
        Prefs.recordModelCheck(context, ModelAvailabilityPolicy.checkKey(provider, model), clock.getAsLong());
        return verdict == null ? ModelAvailabilityPolicy.Verdict.UNKNOWN : verdict;
    }

    private static List<String> providersWithKeys(Context context) {
        List<String> providers = new ArrayList<>();
        for (String provider : new String[]{Prefs.PROVIDER_OPENAI, Prefs.PROVIDER_ANTHROPIC}) {
            if (!ModelAvailabilityPolicy.trim(Prefs.apiKeyForProvider(context, provider)).isEmpty()) {
                providers.add(provider);
            }
        }
        return providers;
    }

    static ModelAvailabilityPolicy.Verdict lookupOverHttp(String provider, String apiKey, String model) {
        try {
            if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
                AnthropicClient.retrieveModel(apiKey, model);
            } else if (Prefs.PROVIDER_OPENAI.equals(provider)) {
                OpenAiClient.retrieveModel(apiKey, model);
            } else {
                return ModelAvailabilityPolicy.Verdict.UNKNOWN;
            }
            return ModelAvailabilityPolicy.Verdict.EXISTS;
        } catch (ProviderHttpException e) {
            return ModelAvailabilityPolicy.lookupVerdict(
                    e.provider,
                    e.status,
                    e.errorType,
                    e.errorCode,
                    e.errorMessage,
                    model
            );
        } catch (Exception e) {
            return ModelAvailabilityPolicy.Verdict.UNKNOWN;
        }
    }
}
