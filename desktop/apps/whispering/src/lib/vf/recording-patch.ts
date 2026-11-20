import type { Recording } from '$lib/services/isomorphic/db';

/**
 * VoiceFlow: a dictation is changed field by field on top of what is stored
 * now, never by writing back an older full copy. That keeps a change made
 * meanwhile (deleted in History, a result, a spelling) when a slower step,
 * such as transcription, finishes. A field set to undefined is removed; the
 * id never changes.
 */
export function applyRecordingPatch(
	stored: Recording,
	patch: Partial<Recording>,
): Recording {
	const next: Recording = { ...stored, ...patch, id: stored.id };
	for (const key of Object.keys(patch) as (keyof Recording)[]) {
		if (patch[key] === undefined) delete next[key];
	}
	return next;
}
