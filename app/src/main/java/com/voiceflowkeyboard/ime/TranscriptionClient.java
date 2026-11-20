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
        ModelAvailabilityPolicy.Slot slot = Prefs.transcriptionModelSlot(context);
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
}
