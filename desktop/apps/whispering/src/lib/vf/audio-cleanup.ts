/**
 * VoiceFlow C1: removes the audio of dictations past the Keep recordings
 * limit (see `audio-retention.ts`). Runs a little after start-up and then
 * once a day, away from the dictation path. Only audio files are removed;
 * each dictation keeps its text and is marked "Audio removed".
 */
import { PATHS } from '$lib/constants/paths';
import { updateRecordingFields } from '$lib/query/isomorphic/outcome';
import { services } from '$lib/services';
import type { Recording } from '$lib/services/isomorphic/db';
import { settings } from '$lib/stores/settings.svelte';
import { audioFilesById, audioToRemove, type KeepAudio } from './audio-retention';

const DAY_MS = 24 * 60 * 60 * 1000;
let running = false;
/** Dictations asked for while a pass ran; handled right after it. */
const queued = new Map<string, { keep: KeepAudio; recording: Recording }>();

/** Removes one dictation's audio files. Returns the bytes freed. */
async function removeAudioFiles(
	dir: string,
	names: string[],
): Promise<number> {
	const { join } = await import('@tauri-apps/api/path');
	const { remove, stat } = await import('@tauri-apps/plugin-fs');
	let bytes = 0;
	for (const name of names) {
		const path = await join(dir, name);
		try {
			bytes += (await stat(path)).size;
		} catch {
			// Size unknown: still remove it.
		}
		await remove(path);
	}
	return bytes;
}

/**
 * One cleanup pass. Safe to call any time. A full pass asked for while one
 * runs is skipped (the daily one comes again); specific dictations asked for
 * meanwhile are queued and handled right after it. Returns what was removed.
 */
export async function cleanUpAudio(
	keep: KeepAudio = settings.value['history.keepAudio'],
	only?: Recording[],
): Promise<{ files: number; bytes: number }> {
	if (!window.__TAURI_INTERNALS__) return { files: 0, bytes: 0 };
	if (running) {
		for (const recording of only ?? []) queued.set(recording.id, { keep, recording });
		return { files: 0, bytes: 0 };
	}
	running = true;
	let files = 0;
	let bytes = 0;
	try {
		const recordings =
			only ?? (await services.db.recordings.getAll()).data ?? [];
		const due = audioToRemove(recordings, keep);
		if (due.length === 0) return { files, bytes };

		const { readDir } = await import('@tauri-apps/plugin-fs');
		const dir = await PATHS.DB.RECORDINGS();
		const entries = await readDir(dir);
		const byId = audioFilesById(
			entries.filter((entry) => entry.isFile).map((entry) => entry.name),
		);

		for (const r of due) {
			const names = byId.get(r.id) ?? [];
			try {
				services.db.recordings.revokeAudioUrl(r.id);
				bytes += await removeAudioFiles(dir, names);
				files += names.length;
				await updateRecordingFields(r.id, {
					audioRemovedAt: new Date().toISOString(),
				});
			} catch (error) {
				console.warn(`[audio-cleanup] could not remove audio of ${r.id}`, error);
			}
		}
		console.info(
			`[audio-cleanup] removed ${files} audio files (${(bytes / 1048576).toFixed(1)} MB) from ${due.length} dictations; keep recordings: ${keep}`,
		);
		return { files, bytes };
	} catch (error) {
		console.warn('[audio-cleanup] cleanup failed', error);
		return { files, bytes };
	} finally {
		running = false;
		if (queued.size) {
			const next = [...queued.values()];
			queued.clear();
			for (const keepGroup of new Set(next.map((q) => q.keep))) {
				void cleanUpAudio(
					keepGroup,
					next.filter((q) => q.keep === keepGroup).map((q) => q.recording),
				);
			}
		}
	}
}

/** After a successful dictation with Keep recordings set to Off. */
export async function removeAudioIfOff(recordingId: string) {
	if (settings.value['history.keepAudio'] !== 'off') return;
	const { data } = await services.db.recordings.getById(recordingId);
	if (data) await cleanUpAudio('off', [data]);
}

/** Starts the daily cleanup; returns a function that stops it. */
export function scheduleAudioCleanup(): () => void {
	const first = setTimeout(() => void cleanUpAudio(), 15_000);
	const daily = setInterval(() => void cleanUpAudio(), DAY_MS);
	return () => {
		clearTimeout(first);
		clearInterval(daily);
	};
}
