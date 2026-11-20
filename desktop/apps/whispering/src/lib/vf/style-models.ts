/**
 * VoiceFlow: which model a style's tidy-up uses, and how the status bar
 * describes it. Kept free of runtime imports so it can be unit-tested.
 */
import type {
	Transformation,
	TransformationStep,
} from '$lib/services/isomorphic/db';

export function modelOf(step: TransformationStep): string {
	const provider = step['prompt_transform.inference.provider'];
	switch (provider) {
		case 'OpenAI':
			return step['prompt_transform.inference.provider.OpenAI.model'];
		case 'Groq':
			return step['prompt_transform.inference.provider.Groq.model'];
		case 'Anthropic':
			return step['prompt_transform.inference.provider.Anthropic.model'];
		case 'Google':
			return step['prompt_transform.inference.provider.Google.model'];
		case 'OpenRouter':
			return step['prompt_transform.inference.provider.OpenRouter.model'];
		case 'Custom':
			return step['prompt_transform.inference.provider.Custom.model'];
		default:
			return '';
	}
}

/**
 * What the status bar says about tidy-up for the style in use: a short label
 * (the model's name) and a sentence for its tooltip. No style means Raw.
 */
export function tidyUpStatus(style: Transformation | undefined): {
	label: string;
	detail: string;
} {
	if (!style) {
		return {
			label: 'off',
			detail: 'Style in use: Raw, so no tidy-up runs. Saved spellings still apply.',
		};
	}
	const name = style.title.trim() || 'Untitled style';
	const prompts = style.steps.filter((s) => s.type === 'prompt_transform');
	if (prompts.length === 0) {
		return {
			label: 'off',
			detail: `Style in use: ${name}. It only fixes saved spellings, so no tidy-up model runs.`,
		};
	}
	const models = [
		...new Set(
			prompts
				.map((s) => {
					const model = modelOf(s).trim();
					return model ? `${s['prompt_transform.inference.provider']} ${model}` : '';
				})
				.filter(Boolean),
		),
	];
	const [first] = models;
	if (!first) {
		return {
			label: 'no model set',
			detail: `Style in use: ${name}. Its tidy-up has no model chosen yet.`,
		};
	}
	const shortName = (m: string) => m.slice(m.indexOf(' ') + 1);
	if (models.length === 1) {
		return {
			label: shortName(first),
			detail: `Style in use: ${name}. It tidies your words with ${first}.`,
		};
	}
	return {
		label: `${shortName(first)} +${models.length - 1}`,
		detail: `Style in use: ${name}. It tidies your words in ${prompts.length} steps, with ${models.join(', ')}.`,
	};
}
