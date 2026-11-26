package com.voiceflowkeyboard.ime;

import android.content.Context;

import java.io.File;

final class TranscriptionClient {
    private TranscriptionClient() {
    }

    static String transcribe(Context context, File audioFile) throws Exception {
        return transcribe(context, audioFile, DictationLanguage.AUTO);
    }

    /**
     * Cloud transcription. OpenAI and Deepgram requests go through the model
     * availability fallback; xAI sends no model, so it has nothing to fall back
     * from. Deepgram's missing-model error cannot be told apart from a language
     * mismatch, so in practice it never retries.
     */
    static String transcribe(Context context, File audioFile, DictationLanguage language) throws Exception {
        return transcribe(context, audioFile, language, WordLists.forDictation(context, ""));
    }

    /** {@code words}: the vocabulary fixed when the recording started. */
    static String transcribe(Context context, File audioFile, DictationLanguage language,
                             WordLists.DictationWords words) throws Exception {
        return transcribe(context, audioFile, language, words, null);
    }

    /** The app's own speech model, with its model availability fallback. */
    static String transcribe(Context context, File audioFile, DictationLanguage language,
                             WordLists.DictationWords words, SpeechCancel cancel) throws Exception {
        ModelAvailabilityPolicy.Slot slot = Prefs.transcriptionModelSlot(context);
        if (Prefs.PROVIDER_SONIOX.equals(slot.provider)) {
            return SonioxClient.transcribe(context, audioFile, language, words, cancel);
        }
        if (Prefs.PROVIDER_XAI.equals(slot.provider)) {
            return XAiClient.transcribe(context, audioFile);
        }
        if (Prefs.PROVIDER_DEEPGRAM.equals(slot.provider)) {
            return ModelAvailability.callWithFallback(context, slot,
                    model -> DeepgramClient.transcribeWith(context, model, audioFile, language));
        }
        if (Prefs.PROVIDER_OPENAI.equals(slot.provider)) {
            return ModelAvailability.callWithFallback(context, slot,
                    model -> OpenAiClient.transcribeWith(context, model, audioFile, language, words));
        }
        return OpenAiClient.transcribeWith(context, OpenAiClient.configuredTranscriptionModel(context), audioFile, language, words);
    }

    /**
     * 0.9.3: one cloud request on an explicit choice ({@link SpeechModels}). {@code appDefault}
     * true goes through the app's own selection above, model fallback included; a style's own
     * choice is used as it is and never rewrites any stored model.
     */
    static String transcribeWith(Context context, SpeechModels.Choice choice, boolean appDefault, File audioFile,
                                 DictationLanguage language, WordLists.DictationWords words,
                                 SpeechCancel cancel) throws Exception {
        if (cancel != null) {
            cancel.throwIfCancelled();
        }
        if (appDefault) {
            return transcribe(context, audioFile, language, words, cancel);
        }
        String model = choice.effectiveModel();
        if (Prefs.PROVIDER_SONIOX.equals(choice.provider)) {
            return SonioxClient.transcribe(context, audioFile, language, words, cancel);
        }
        if (Prefs.PROVIDER_XAI.equals(choice.provider)) {
            return XAiClient.transcribe(context, audioFile);
        }
        if (Prefs.PROVIDER_DEEPGRAM.equals(choice.provider)) {
            return DeepgramClient.transcribeWith(context, model, audioFile, language);
        }
        if (Prefs.PROVIDER_OPENAI.equals(choice.provider)) {
            return OpenAiClient.transcribeWith(context, model, audioFile, language, words);
        }
        throw new IllegalStateException(Prefs.providerLabel(choice.provider) + " can't transcribe a recording here.");
    }
}
