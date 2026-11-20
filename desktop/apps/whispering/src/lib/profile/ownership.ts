/**
 * VoiceFlow word lists: which settings the profile owns (plan v2,
 * Decisions, "Ownership").
 *
 * With word lists on, profile-owned settings come only from the merged
 * profile. They are never read from localStorage and never written back
 * there: the device's own earlier values stay where they were, unused
 * (nothing is deleted, so turning word lists off brings them back).
 * Device settings (keys, microphone, theme, pasting, sounds) stay on the
 * device as before.
 *
 * - The vocabulary prompt is always the profile's.
 * - A default (transcription model, language, default style) is owned only
 *   when some layer sets it; otherwise the device keeps choosing.
 *
 * Pure functions: `settings.svelte.ts` applies them.
 */
import type { Settings } from '$lib/settings/settings';
import { buildPrompt } from './prompt';
import type { ProfileSnapshot, ProfileState } from './types';

/**
 * The merged profile in force, or null when the device's own settings
 * apply: word lists off, or on but nothing read yet (the first start before
 * the folder was reached, with no copy on this device). Words never go
 * empty just because the folder hasn't answered yet.
 */
export function effectiveSnapshot(
	state: ProfileState | null | undefined,
): ProfileSnapshot | null {
	if (!state?.config.enabled || !state.snapshot) return null;
	const report = state.status.report;
	const readSomething =
		!!state.status.lastSyncAt ||
		!!report?.syncedAt ||
		!!report?.layers.some((layer) => layer.state !== 'missing');
	return readSomething ? state.snapshot : null;
}

/** Settings a profile can own. */
export const PROFILE_OWNED_KEYS = [
	'transcription.prompt',
	'transcription.openai.model',
	'transcription.outputLanguage',
	'transformations.selectedTransformationId',
] as const satisfies readonly (keyof Settings)[];

export type ProfileOwnedKey = (typeof PROFILE_OWNED_KEYS)[number];

/** A profile style as a default-style id (device setting format). */
export const PROFILE_STYLE_PREFIX = 'profile:';

export function profileStyleKey(styleId: string): string {
	return `${PROFILE_STYLE_PREFIX}${styleId}`;
}

export function profileStyleIdOf(selected: string | null | undefined) {
	return selected?.startsWith(PROFILE_STYLE_PREFIX)
		? selected.slice(PROFILE_STYLE_PREFIX.length)
		: null;
}

/**
 * The settings the profile provides, or an empty object when word lists
 * are off. Only keys returned here are owned.
 */
export function profileOverlay(
	snapshot: ProfileSnapshot | null,
	isLanguage: (value: string) => boolean = () => true,
): Partial<Pick<Settings, ProfileOwnedKey>> {
	if (!snapshot) return {};
	const overlay: Partial<Pick<Settings, ProfileOwnedKey>> = {
		'transcription.prompt': buildPrompt(snapshot, null).prompt,
	};
	const d = snapshot.defaults;
	if (d.transcriptionModel)
		overlay['transcription.openai.model'] = d.transcriptionModel;
	if (d.language && isLanguage(d.language))
		overlay['transcription.outputLanguage'] =
			d.language as Settings['transcription.outputLanguage'];
	if (d.cleanupStyle)
		overlay['transformations.selectedTransformationId'] =
			d.cleanupStyle === 'raw' ? null : profileStyleKey(d.cleanupStyle);
	return overlay;
}

/**
 * What goes to localStorage when settings are saved: owned keys keep the
 * value already stored there (the effective, profile-provided value is
 * never copied in), every other key takes the new value.
 */
export function deviceOnly(
	next: Settings,
	stored: Settings,
	owned: Partial<Pick<Settings, ProfileOwnedKey>>,
): Settings {
	const out = { ...next };
	for (const key of Object.keys(owned) as ProfileOwnedKey[]) {
		(out as Record<string, unknown>)[key] = stored[key];
	}
	return out;
}

/** Owned keys the caller tried to change (they are ignored). */
export function blockedUpdates(
	updates: Partial<Settings>,
	owned: Partial<Pick<Settings, ProfileOwnedKey>>,
): ProfileOwnedKey[] {
	return (Object.keys(owned) as ProfileOwnedKey[]).filter(
		(key) => key in updates && updates[key] !== owned[key],
	);
}
