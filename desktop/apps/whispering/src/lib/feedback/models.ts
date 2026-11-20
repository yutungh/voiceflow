/**
 * VoiceFlow phase F: model names for stage evidence ("which model heard it,
 * which tidied it"). Kept locally with the dictation; not sent with feedback.
 */
import type { Transformation } from '$lib/services/isomorphic/db';

/** The first AI step's provider and model, e.g. `OpenAI gpt-5.4-mini`. */
export function tidyModelOf(transformation: Pick<Transformation, 'steps'>): string | undefined {
	for (const step of transformation.steps ?? []) {
		const s = step as Record<string, unknown>;
		if (s.type !== 'prompt_transform') continue;
		const provider = s['prompt_transform.inference.provider'];
		if (typeof provider !== 'string') continue;
		const model = s[`prompt_transform.inference.provider.${provider}.model`];
		return typeof model === 'string' && model ? `${provider} ${model}` : provider;
	}
	return undefined;
}

/** The speech-to-text service and model in use. */
export function transcriptionModelOf(values: Record<string, unknown>): string | undefined {
	const service = values['transcription.selectedTranscriptionService'];
	if (typeof service !== 'string') return undefined;
	const key: Record<string, string> = {
		OpenAI: 'transcription.openai.model',
		Groq: 'transcription.groq.model',
		ElevenLabs: 'transcription.elevenlabs.model',
		Deepgram: 'transcription.deepgram.model',
		Mistral: 'transcription.mistral.model',
		speaches: 'transcription.speaches.modelId',
	};
	const model = key[service] ? values[key[service]] : undefined;
	return typeof model === 'string' && model ? `${service} ${model}` : service;
}
