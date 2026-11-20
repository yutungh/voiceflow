/**
 * VoiceFlow 0.9.13: the voiceflow-profile v2 style fields as this PC uses
 * them. Pure (type imports only), so it is unit tested with bun.
 */
import type { ProfileSnapshot, ProfileStyle } from './types';

/** A tidy-up provider as the word lists name it (`models` keys): OpenAI -> openai. */
export function providerKey(provider: string): string {
	return provider.toLowerCase();
}

/**
 * The model a word-list style runs with on a provider: its model for that
 * provider, else its one model, else the word lists' default, else the
 * app's.
 */
export function styleModelFor(
	style: Pick<ProfileStyle, 'model' | 'models'>,
	provider: string,
	cleanupModel: string | undefined,
	fallback: string,
): string {
	return style.models?.[providerKey(provider)] || style.model || cleanupModel || fallback;
}

/**
 * The word-list styles to offer in pickers and on the bar. A hidden one is
 * left out, but still works where a default or an app rule names it
 * (`resolveProfileStyle` looks in every style).
 */
export function visibleProfileStyles(snapshot: ProfileSnapshot | null): ProfileStyle[] {
	return (snapshot?.styles ?? []).filter((s) => s.hidden !== true);
}
