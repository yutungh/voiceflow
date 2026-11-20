/**
 * VoiceFlow word lists: how long a tidy-up may take for one dictation.
 */
import type { ProfileSnapshot } from './types';

/**
 * The word lists' `cleanupTimeoutS` (1-120 s) from the snapshot the
 * dictation started with, else `fallbackMs`.
 */
export function tidyUpTimeoutMs(
	snapshot: Pick<ProfileSnapshot, 'defaults'> | null,
	fallbackMs: number,
): number {
	const seconds = snapshot?.defaults.cleanupTimeoutS;
	return typeof seconds === 'number' && seconds >= 1 && seconds <= 120
		? seconds * 1000
		: fallbackMs;
}
