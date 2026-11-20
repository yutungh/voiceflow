/**
 * VoiceFlow C1: Styles shown over the existing tidy-up data (upstream
 * "transformations"). A style is one prompt and a model. Find-and-replace
 * steps made before keep running unseen. A transformation with several
 * prompts is shown, but can only be used or removed here (phase C2 moves
 * styles to the profile).
 */
import { DEFAULT_OPENAI_INFERENCE_MODEL } from '$lib/constants/inference/openai-models';
import {
	generateDefaultTransformation,
	generateDefaultTransformationStep,
	type Transformation,
	type TransformationStep,
} from '$lib/services/isomorphic/db';

export type StyleShape =
	| { kind: 'simple'; prompt: TransformationStep }
	| { kind: 'spellings-only' }
	| { kind: 'complex'; prompts: number };

export function styleShape(style: Transformation): StyleShape {
	const prompts = style.steps.filter((s) => s.type === 'prompt_transform');
	const [only] = prompts;
	if (prompts.length === 1 && only) return { kind: 'simple', prompt: only };
	if (prompts.length === 0) return { kind: 'spellings-only' };
	return { kind: 'complex', prompts: prompts.length };
}

/** The prompt that tells the model how to tidy (system prompt, else user prompt). */
export function promptOf(step: TransformationStep): string {
	const system = step['prompt_transform.systemPromptTemplate'].trim();
	return system || step['prompt_transform.userPromptTemplate'].trim();
}

export { modelOf, tidyUpStatus } from './style-models';

/** One line under a style's name. */
export function styleSummary(style: Transformation): string {
	if (style.description.trim()) return style.description.trim();
	const shape = styleShape(style);
	if (shape.kind === 'spellings-only') return 'No tidy-up; fixed spellings only';
	if (shape.kind === 'complex') return `${shape.prompts} tidy-up steps`;
	const prompt = promptOf(shape.prompt).replace(/\s+/g, ' ');
	return prompt.length > 70 ? `${prompt.slice(0, 68).trimEnd()}…` : prompt || 'No prompt yet';
}

/** Find-and-replace steps kept from before ("fixed spellings"). */
export function fixedSpellingsOf(style: Transformation): number {
	return style.steps.filter((s) => s.type === 'find_replace').length;
}

export { OPENAI_INFERENCE_MODELS as STYLE_MODEL_SUGGESTIONS } from '$lib/constants/inference/openai-models';

/**
 * Writes a style's editable fields back into its transformation. The prompt
 * becomes the system prompt and the dictation goes in as the user message,
 * so a new style needs only the instructions.
 */
export function withStyleFields(
	style: Transformation,
	fields: { title: string; prompt: string; model: string },
): Transformation {
	const now = new Date().toISOString();
	const shape = styleShape(style);
	const base =
		shape.kind === 'simple' ? shape.prompt : generateDefaultTransformationStep();
	const provider = base['prompt_transform.inference.provider'];
	const prompt: TransformationStep = {
		...base,
		type: 'prompt_transform',
		'prompt_transform.systemPromptTemplate': fields.prompt,
		'prompt_transform.userPromptTemplate':
			base['prompt_transform.userPromptTemplate'].trim() || '{{input}}',
	};
	switch (provider) {
		case 'OpenAI':
			prompt['prompt_transform.inference.provider.OpenAI.model'] = fields.model;
			break;
		case 'OpenRouter':
			prompt['prompt_transform.inference.provider.OpenRouter.model'] = fields.model;
			break;
		case 'Custom':
			prompt['prompt_transform.inference.provider.Custom.model'] = fields.model;
			break;
		default:
			break;
	}
	const steps =
		shape.kind === 'simple'
			? style.steps.map((s) => (s.id === base.id ? prompt : s))
			: [prompt, ...style.steps];
	return { ...style, title: fields.title, steps, updatedAt: now };
}

/** A new, empty style. */
export function newStyle(): Transformation {
	const style = generateDefaultTransformation();
	return withStyleFields(style, {
		title: 'New style',
		prompt:
			'Tidy up this dictation: fix punctuation and capital letters, remove filler words like "um" and repeated words, and keep the speaker\'s own words and meaning. Reply with the tidied text only.',
		model: DEFAULT_OPENAI_INFERENCE_MODEL,
	});
}

/** Whether a provider's model is a free text field (else a fixed list). */
export function modelIsEditable(step: TransformationStep): boolean {
	return ['OpenAI', 'OpenRouter', 'Custom'].includes(
		step['prompt_transform.inference.provider'],
	);
}
