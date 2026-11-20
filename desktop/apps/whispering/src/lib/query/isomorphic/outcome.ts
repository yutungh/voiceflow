import { activeCorrections, applyCorrections } from '$lib/profile/ops';
import { correctionsInForce, loadOps } from '$lib/profile/op-store';
import { profilePins } from '$lib/profile/service.svelte';
import { services } from '$lib/services';
import type { Recording } from '$lib/services/isomorphic/db';
import { applyRecordingPatch } from '$lib/vf/recording-patch';
import { db } from './db';

/**
 * VoiceFlow C1: keeps what happened to a dictation on the dictation itself
 * (pasted, not pasted and why, the final text), so Home's "Needs you" and
 * History show it after the notice is gone. A field set to undefined is
 * removed.
 */
export async function updateRecordingFields(
	id: string,
	patch: Partial<Recording>,
): Promise<void> {
	const { error } = await patchRecording(id, patch);
	if (error) console.error('[outcome] could not update the dictation', error);
}

/**
 * Changes some fields of a stored dictation on top of what is stored now
 * (`applyRecordingPatch`). A dictation that no longer exists is left gone.
 * Returns the error, if any, for callers that report it.
 */
export async function patchRecording(
	id: string,
	patch: Partial<Recording>,
): Promise<{ error: Error | null }> {
	const { data: stored, error } = await services.db.recordings.getById(id);
	if (error) return { error };
	if (!stored) return { error: null };
	const { error: updateError } = await db.recordings.update.execute(
		applyRecordingPatch(stored, patch),
	);
	return { error: updateError ?? null };
}

/**
 * Applies the spellings to a dictation's final text, once. With word lists
 * on: every layer's spellings, from the snapshot pinned when the dictation
 * started (`recordingId`); otherwise the ones saved on this PC with "Fix a
 * spelling". If they can't be read, the text is unchanged.
 */
export async function applySavedSpellings(
	text: string,
	appId: string | null,
	recordingId?: string | null,
) {
	try {
		const snapshot = profilePins.snapshotFor(recordingId);
		const corrections = snapshot
			? await correctionsInForce(snapshot)
			: activeCorrections(await loadOps());
		return applyCorrections(text, corrections, appId);
	} catch (error) {
		console.error('[outcome] could not read saved spellings', error);
		return { text, changes: [] };
	}
}
