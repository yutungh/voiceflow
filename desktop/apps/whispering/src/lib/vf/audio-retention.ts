/**
 * VoiceFlow C1: how long a dictation's audio is kept (Settings > History
 * and audio > Keep recordings). Only the audio goes: the text, the app, the
 * style and the timings stay in History for good. Pure functions; the file
 * work is in `audio-cleanup.ts`.
 */
import type { Recording } from '$lib/services/isomorphic/db';

export const KEEP_AUDIO_OPTIONS = ['off', '1d', '7d', '30d', 'forever'] as const;
export type KeepAudio = (typeof KEEP_AUDIO_OPTIONS)[number];
export const DEFAULT_KEEP_AUDIO: KeepAudio = '7d';

export const KEEP_AUDIO_LABELS: Record<KeepAudio, string> = {
	off: 'Off',
	'1d': '1 day',
	'7d': '7 days',
	'30d': '30 days',
	forever: 'Forever',
};

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * 0.9.12 (F5): "Nothing heard" on a recording of 3 s or more offers Retry on
 * the bar; its audio is kept this long after the result, whatever Keep
 * recordings says (a short tap has nothing to retry and goes at once).
 */
export const EMPTY_RETRY_KEEP_MS = 60_000;
const EMPTY_RETRY_MIN_MS = 3000;

/** True while a retryable "Nothing heard" still offers Retry. */
export function inEmptyRetryWindow(r: Recording, now: number = Date.now()): boolean {
	if (r.result !== 'no-text' || r.problemKind) return false;
	if (typeof r.durationMs === 'number' && r.durationMs < EMPTY_RETRY_MIN_MS) return false;
	const since = Date.parse(r.updatedAt || r.createdAt || r.timestamp);
	return !Number.isNaN(since) && now - since < EMPTY_RETRY_KEEP_MS;
}

/** Audio a dictation that still needs fixing keeps, unless older than this. */
export const UNRESOLVED_KEEP_DAYS = 30;

const KEEP_DAYS: Record<Exclude<KeepAudio, 'forever'>, number> = {
	off: 0,
	'1d': 1,
	'7d': 7,
	'30d': 30,
};

/**
 * True while the audio is still needed to fix the dictation: held (not
 * sent yet), transcription failed, or not transcribed yet. Retry needs it.
 */
export function needsAudio(r: Recording): boolean {
	return (
		r.privacy === 'held' ||
		r.transcriptionStatus !== 'DONE' ||
		// 0.9.12 (F5): "Nothing heard" (no text, no problem) needs it no more
		// than a pasted dictation; the bar's Retry keeps it a minute itself.
		(r.result === 'no-text' && !!r.problemKind)
	);
}

/**
 * Whether this dictation's audio should be removed now. Never for one whose
 * audio is already gone; never for one that still needs it, until it is
 * older than 30 days; otherwise once it is older than the chosen limit.
 */
export function shouldRemoveAudio(
	r: Recording,
	keep: KeepAudio,
	now: number = Date.now(),
): boolean {
	if (r.audioRemovedAt) return false;
	if (keep === 'forever') return false;
	const created = Date.parse(r.createdAt || r.timestamp);
	if (Number.isNaN(created)) return false;
	const age = now - created;
	if (inEmptyRetryWindow(r, now)) return false;
	if (needsAudio(r)) return age > UNRESOLVED_KEEP_DAYS * DAY_MS;
	return age >= KEEP_DAYS[keep] * DAY_MS;
}

/** The dictations whose audio goes in this cleanup. */
export function audioToRemove(
	recordings: readonly Recording[],
	keep: KeepAudio,
	now: number = Date.now(),
): Recording[] {
	return recordings.filter((r) => shouldRemoveAudio(r, keep, now));
}

/**
 * The audio files in the recordings folder, by dictation id. Only
 * `<id>.<audio extension>` counts: the dictation's `.md`, a `.md.tmp` being
 * written right now, or anything else there is never treated as audio.
 */
export function audioFilesById(names: readonly string[]): Map<string, string[]> {
	const byId = new Map<string, string[]>();
	for (const name of names) {
		const dot = name.indexOf('.');
		if (dot <= 0) continue;
		const ext = name.slice(dot + 1).toLowerCase();
		if (!AUDIO_EXTENSIONS.has(ext)) continue;
		const id = name.slice(0, dot);
		byId.set(id, [...(byId.get(id) ?? []), name]);
	}
	return byId;
}

/** What the recorder and uploads save (mime extensions; `bin` for unknown). */
const AUDIO_EXTENSIONS: ReadonlySet<string> = new Set([
	'wav', 'mp3', 'ogg', 'oga', 'opus', 'webm', 'weba', 'm4a', 'mp4', 'aac', 'flac', 'bin',
]);
