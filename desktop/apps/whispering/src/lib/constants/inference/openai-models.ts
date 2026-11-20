/**
 * OpenAI chat models offered for tidy-up (prompt transform) steps.
 * @see https://platform.openai.com/docs/models
 *
 * VoiceFlow: every OpenAI chat model in Whispering 7.11's list is retired or
 * retiring (gpt-4.1-nano is gone; gpt-5, gpt-5-mini and o3 shut down on
 * 2026-12-11; gpt-4o, gpt-4o-mini, gpt-4.1 and gpt-4.1-mini on 2027-01-14).
 * The step stores any model id as text, so the picker is only a shortcut:
 * a model that is not listed here can be typed in, and a step saved with
 * an old model keeps it until the user picks another.
 */

/** The default tidy-up model for new steps (spec §2.4). */
export const DEFAULT_OPENAI_INFERENCE_MODEL = 'gpt-5.4-mini' as const;

export const OPENAI_INFERENCE_MODELS = [
	DEFAULT_OPENAI_INFERENCE_MODEL,
	'gpt-5.4-nano',
	'gpt-5.4',
] as const;

export const OPENAI_INFERENCE_MODEL_OPTIONS = OPENAI_INFERENCE_MODELS.map(
	(model) => ({
		value: model,
		label: model,
	}),
);
