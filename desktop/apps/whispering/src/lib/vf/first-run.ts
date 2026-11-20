import { services } from '$lib/services';
import { settings } from '$lib/stores/settings.svelte';

/**
 * VoiceFlow C1: first-run setup is shown once, on a fresh install only.
 * An install that already has dictations or an OpenAI key (someone who has
 * used VoiceFlow) is marked as set up without seeing it.
 */
const KEY = 'voiceflow-first-run';

function done(): boolean {
	try {
		return localStorage.getItem(KEY) === 'done';
	} catch {
		return true;
	}
}

export function markFirstRunDone() {
	try {
		localStorage.setItem(KEY, 'done');
	} catch {
		// Private storage unavailable: setup may show again; harmless.
	}
}

export async function shouldShowFirstRun(): Promise<boolean> {
	if (done()) return false;
	await settings.apiKeysReady;
	const { data: recordings, error } = await services.db.recordings.getAll();
	// If history can't be read, assume an existing install: never interrupt.
	const used =
		!!error || (recordings?.length ?? 0) > 0 || !!settings.value['apiKeys.openai'];
	if (used) {
		markFirstRunDone();
		return false;
	}
	return true;
}
