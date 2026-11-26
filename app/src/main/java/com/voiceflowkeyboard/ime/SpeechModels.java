package com.voiceflowkeyboard.ime;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 0.9.3: each style may pick its own speech model.
 *
 * <p>What a style stores (under its stable id, {@link Prefs#speechModelForStyle}):</p>
 * <ul>
 *   <li>nothing: <b>inherit</b>. The style's profile choice if its word list gives one
 *       (none does yet: see {@link #profileChoice}), else the app's speech model. The
 *       picker shows it as "Same as app".</li>
 *   <li>{@value #APP}: <b>the app default</b>, even when the profile names a model.</li>
 *   <li>{@code {"provider": "soniox", "model": "stt-async-v6"}}: its own model. An empty or
 *       absent model means that provider's default. Fields this version doesn't know are
 *       kept, because the stored text is only ever replaced by a new pick.</li>
 * </ul>
 *
 * <p>The choice is resolved once, when recording starts, before cloud or offline routing
 * (a style switched on the recording panel keeps the model chosen at the start). A provider
 * this version doesn't know, or one whose key isn't saved, is kept and shown as unavailable,
 * and the app default is used. Only dictations use a style's model; text creation, voice
 * instructions and translation use the app's.</p>
 *
 * <p>Fallback ({@link Prefs#speechFallbackToAppDefault}, on by default): when a style's own
 * cloud model fails, the app default is tried once, if it is a different cloud model whose
 * key is saved. Never a loop, never offline (the recording is AAC, offline needs PCM), and a
 * cancel stops it.</p>
 */
final class SpeechModels {
    static final String APP = "app";

    /** One provider and model. {@code model} "" means the provider's default. */
    static final class Choice {
        final String provider;
        final String model;

        Choice(String provider, String model) {
            this.provider = provider == null ? "" : provider.trim();
            this.model = model == null ? "" : model.trim();
        }

        String effectiveModel() {
            return model.isEmpty() ? Prefs.defaultTranscriptionModel(provider) : model;
        }

        boolean sameAs(Choice other) {
            return other != null && provider.equals(other.provider)
                    && effectiveModel().equalsIgnoreCase(other.effectiveModel());
        }

        String label() {
            return Prefs.providerLabel(provider) + " " + effectiveModel();
        }
    }

    enum Kind { INHERIT, APP_DEFAULT, OWN }

    /** A stored value, read. */
    static final class Stored {
        final Kind kind;
        /** Set for {@link Kind#OWN}; its provider may be one this version doesn't know. */
        final Choice own;

        Stored(Kind kind, Choice own) {
            this.kind = kind;
            this.own = own;
        }
    }

    /** What a dictation will use. */
    static final class Resolved {
        final Choice used;
        /** True when the style's (or its profile's) own model is used, not the app's. */
        final boolean fromStyle;
        /** The style's choice that couldn't be used (no key, unknown provider), else null. */
        final Choice unavailable;

        Resolved(Choice used, boolean fromStyle, Choice unavailable) {
            this.used = used;
            this.fromStyle = fromStyle;
            this.unavailable = unavailable;
        }
    }

    /** Whether a provider can be used on this phone now (known, and its key saved). */
    interface Availability {
        boolean usable(String provider);
    }

    private SpeechModels() {
    }

    static Stored parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return new Stored(Kind.INHERIT, null);
        }
        if (APP.equals(value)) {
            return new Stored(Kind.APP_DEFAULT, null);
        }
        try {
            JSONObject json = new JSONObject(value);
            String provider = json.optString("provider", "").trim();
            if (provider.isEmpty()) {
                return new Stored(Kind.INHERIT, null);
            }
            return new Stored(Kind.OWN, new Choice(provider, json.optString("model", "")));
        } catch (JSONException e) {
            // Not ours to read: inherit, and leave the stored text alone.
            return new Stored(Kind.INHERIT, null);
        }
    }

    static String encode(Choice choice) {
        try {
            JSONObject json = new JSONObject().put("provider", choice.provider);
            if (!choice.model.isEmpty()) {
                json.put("model", choice.model);
            }
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * @param appDefault the app's speech model
     * @param stored     the style's stored value (null: nothing stored)
     * @param profile    the style's profile choice, null when its word list gives none
     */
    static Resolved resolve(Choice appDefault, String stored, Choice profile, Availability availability) {
        Stored parsed = parse(stored);
        Choice wanted;
        if (parsed.kind == Kind.APP_DEFAULT) {
            wanted = null;
        } else if (parsed.kind == Kind.OWN) {
            wanted = parsed.own;
        } else {
            wanted = profile;
        }
        if (wanted == null || wanted.sameAs(appDefault)) {
            return new Resolved(appDefault, false, null);
        }
        if (!Prefs.supportsTranscription(wanted.provider) || !availability.usable(wanted.provider)) {
            return new Resolved(appDefault, false, wanted);
        }
        return new Resolved(wanted, true, null);
    }

    /** The one model to try when the style's own fails, or null when there is none. */
    static Choice fallback(Resolved resolved, Choice appDefault, boolean enabled, Availability availability) {
        return fallback(resolved, appDefault, enabled, availability, DictationLanguage.AUTO);
    }

    /** {@code language}: the recording's; a Chinese one never falls back to a model without Chinese. */
    static Choice fallback(Resolved resolved, Choice appDefault, boolean enabled, Availability availability,
                           DictationLanguage language) {
        if (!enabled || resolved == null || !resolved.fromStyle || appDefault == null) {
            return null;
        }
        if (language != null && language.isChinese() && !Prefs.supportsChineseDictation(appDefault.provider)) {
            return null;
        }
        if (resolved.used.sameAs(appDefault)
                || !isCloud(resolved.used.provider)
                || !isCloud(appDefault.provider)
                || !Prefs.supportsTranscription(appDefault.provider)
                || !availability.usable(appDefault.provider)) {
            return null;
        }
        return appDefault;
    }

    static boolean isCloud(String provider) {
        return !ModelAvailabilityPolicy.isOffline(provider);
    }

    /** One speech request. {@code appDefault}: the app's own selection (with its model fallback). */
    interface Attempt {
        String run(Choice choice, boolean appDefault) throws Exception;
    }

    static final class Outcome {
        final String text;
        final Choice used;
        /** The style's model that failed before the fallback worked, else null. */
        final Choice failed;

        Outcome(String text, Choice used, Choice failed) {
            this.text = text;
            this.used = used;
            this.failed = failed;
        }

        /** "Soniox failed, used OpenAI", or "" when nothing fell back. */
        String notice() {
            return failed == null ? "" : Prefs.providerLabel(failed.provider) + " failed, used "
                    + Prefs.providerLabel(used.provider);
        }
    }

    /**
     * The style's model, then at most once the fallback. A cancel ends both; when both fail,
     * the first error is thrown (the second is attached), and the caller keeps the recording.
     */
    static Outcome run(Resolved resolved, Choice fallback, Attempt attempt, SpeechCancel cancel) throws Exception {
        try {
            return new Outcome(attempt.run(resolved.used, !resolved.fromStyle), resolved.used, null);
        } catch (Exception first) {
            if (first instanceof CancellationException || (cancel != null && cancel.isCancelled()) || fallback == null) {
                throw first;
            }
            String text;
            try {
                text = attempt.run(fallback, true);
            } catch (Exception second) {
                if (second instanceof CancellationException || (cancel != null && cancel.isCancelled())) {
                    throw second;
                }
                first.addSuppressed(second);
                throw first;
            }
            if (cancel != null && cancel.isCancelled()) {
                throw new CancellationException("Canceled");
            }
            return new Outcome(text, fallback, resolved.used);
        }
    }

    /**
     * What a speech model does with the word list, true to what this app sends. Spelling fixes
     * from the word lists are applied after every model ({@link #CORRECTIONS_NOTE}).
     */
    static String wordListNotice(String provider, String model) {
        if (Prefs.PROVIDER_SONIOX.equals(provider)) {
            return "Uses your word list to guide recognition";
        }
        if (Prefs.PROVIDER_OPENAI.equals(provider)) {
            return OpenAiClient.sendsVocabulary(model == null || model.trim().isEmpty()
                    ? Prefs.defaultTranscriptionModel(provider) : model)
                    ? "Uses your word list"
                    : "Word-list hints aren't sent by this integration";
        }
        if (ModelAvailabilityPolicy.isOffline(provider)) {
            return "Doesn't use your word list";
        }
        return "Word-list hints aren't sent by this integration";
    }

    static final String CORRECTIONS_NOTE = "Spelling fixes from your word list apply after every speech model.";

    /** The app's recommended speech model: OpenAI gpt-transcribe, the default. */
    static boolean isRecommended(String provider, String model) {
        return Prefs.PROVIDER_OPENAI.equals(provider)
                && "gpt-transcribe".equalsIgnoreCase(model == null || model.trim().isEmpty()
                ? Prefs.defaultTranscriptionModel(provider) : model.trim());
    }

    // ---------------------------------------------------------------- Android

    static Choice appDefault(Context context) {
        return new Choice(Prefs.transcriptionProvider(context), Prefs.transcriptionModel(context));
    }

    static Availability availability(Context context) {
        return provider -> Prefs.supportsTranscription(provider) && Prefs.hasApiKeyForProvider(context, provider);
    }

    /**
     * The style's choice from its word list. None yet: the word lists' style format refuses
     * fields it doesn't know (every phone up to 0.9.2 would refuse the whole file), so a
     * {@code speechModel} field waits for a format change that readers take first.
     */
    static Choice profileChoice(String styleId) {
        return null;
    }

    /** Resolved for a dictation in {@code styleId}, when its recording starts. */
    static Resolved atRecordStart(Context context, String styleId) {
        return resolve(appDefault(context), Prefs.speechModelForStyle(context, styleId), profileChoice(styleId),
                availability(context));
    }

    /** One row of a style's speech-model picker. */
    static final class Option {
        /** The value to store: null for "Same as app", else {@link #APP} or a choice's JSON. */
        final String value;
        final String title;
        final String detail;
        final boolean available;

        Option(String value, String title, String detail, boolean available) {
            this.value = value;
            this.title = title;
            this.detail = detail;
            this.available = available;
        }
    }

    /**
     * "Same as app", then each cloud provider with a saved key (and the style's current pick
     * even without one, marked unavailable), then the offline models.
     */
    static List<Option> options(Context context, String styleId) {
        Choice app = appDefault(context);
        Availability availability = availability(context);
        List<Option> options = new ArrayList<>();
        options.add(new Option(null, "Same as app (" + app.label() + ")", wordListNotice(app.provider, app.model), true));
        Stored stored = parse(Prefs.speechModelForStyle(context, styleId));
        String[] providers = {Prefs.PROVIDER_OPENAI, Prefs.PROVIDER_SONIOX, Prefs.PROVIDER_DEEPGRAM, Prefs.PROVIDER_XAI};
        boolean currentListed = stored.kind != Kind.OWN;
        for (String provider : providers) {
            boolean usable = availability.usable(provider);
            boolean current = stored.kind == Kind.OWN && stored.own.provider.equals(provider);
            if (!usable && !current) {
                continue;
            }
            Choice choice = current ? stored.own : new Choice(provider, "");
            currentListed |= current;
            String detail = wordListNotice(provider, choice.model);
            if (isRecommended(provider, choice.model)) {
                detail = "Recommended. " + detail;
            }
            if (!usable) {
                detail = "Unavailable: add a " + Prefs.providerLabel(provider) + " key. The app's model is used.";
            }
            options.add(new Option(encode(choice), choice.label(), detail, usable));
        }
        for (String provider : new String[]{Prefs.PROVIDER_OFFLINE_PARAKEET, Prefs.PROVIDER_OFFLINE_VOSK}) {
            Choice choice = new Choice(provider, "");
            currentListed |= stored.kind == Kind.OWN && stored.own.provider.equals(provider);
            options.add(new Option(encode(choice), Prefs.providerLabel(provider), wordListNotice(provider, ""), true));
        }
        if (!currentListed) {
            // A provider this version doesn't know: kept, shown, not used.
            options.add(new Option(Prefs.speechModelForStyle(context, styleId), stored.own.provider,
                    "Unavailable in this version. The app's model is used.", false));
        }
        return options;
    }

    /** The picker row's summary for a style. */
    static String summary(Context context, String styleId) {
        Stored stored = parse(Prefs.speechModelForStyle(context, styleId));
        if (stored.kind != Kind.OWN) {
            return "Same as app (" + appDefault(context).label() + ")";
        }
        Resolved resolved = atRecordStart(context, styleId);
        if (resolved.unavailable != null) {
            return stored.own.provider.isEmpty() ? "Same as app"
                    : (Prefs.supportsTranscription(stored.own.provider)
                    ? Prefs.providerLabel(stored.own.provider) : stored.own.provider)
                    + " (unavailable, using " + Prefs.providerLabel(resolved.used.provider) + ")";
        }
        return stored.own.label();
    }
}
