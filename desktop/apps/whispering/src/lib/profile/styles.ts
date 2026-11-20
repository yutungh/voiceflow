/**
 * VoiceFlow word lists: running a style that lives in the profile.
 *
 * A profile style is a label, a prompt and (optionally) a model. To tidy up
 * with it, it is turned into an in-memory tidy-up (one prompt step, the
 * dictation as the user message), exactly like a style made on the Styles
 * screen. Nothing is written to the device's styles.
 */
import { DEFAULT_OPENAI_INFERENCE_MODEL } from '$lib/constants/inference/openai-models';
import {
	generateDefaultTransformation,
	generateDefaultTransformationStep,
	type Transformation,
} from '$lib/services/isomorphic/db';
import { withStyleFields } from '$lib/vf/styles';
import { profileStyleIdOf, profileStyleKey } from './ownership';
import { styleModelFor } from './style-fields';
import type { ProfileSnapshot, ProfileStyle } from './types';

export function profileStyleTransformation(
	style: ProfileStyle,
	cleanupModel?: string,
): Transformation {
	// 0.9.13 (v2): the style's model for the provider a new style tidies
	// with on this PC, else its one model, else the word lists' default.
	const provider = generateDefaultTransformationStep()['prompt_transform.inference.provider'];
	const base = withStyleFields(generateDefaultTransformation(), {
		title: style.label,
		prompt: style.prompt,
		model: styleModelFor(style, provider, cleanupModel, DEFAULT_OPENAI_INFERENCE_MODEL),
	});
	// 0.9.13 (v2): its guidance is its description.
	return { ...base, id: profileStyleKey(style.id), description: style.guidance?.trim() ?? '' };
}

/**
 * The tidy-up for a default-style id: a profile style (`profile:<id>`) from
 * the given snapshot. Undefined when the id is not a profile style; null
 * when it is one that the snapshot doesn't have.
 */
export function resolveProfileStyle(
	selected: string | null | undefined,
	snapshot: ProfileSnapshot | null,
): Transformation | null | undefined {
	const id = profileStyleIdOf(selected);
	if (id === null) return undefined;
	const style = snapshot?.styles.find((s) => s.id === id);
	return style
		? profileStyleTransformation(style, snapshot?.defaults.cleanupModel)
		: null;
}

/**
 * The profile style behind a default-style id (`profile:<id>`) in the given
 * snapshot, or null when the id is not one or the snapshot doesn't have it.
 */
export function profileStyleOf(
	selected: string | null | undefined,
	snapshot: ProfileSnapshot | null,
): ProfileStyle | null {
	const id = profileStyleIdOf(selected);
	if (id === null) return null;
	return snapshot?.styles.find((s) => s.id === id) ?? null;
}
