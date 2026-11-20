/**
 * VoiceFlow: the app's dictation timing instance and its JSONL file.
 *
 * Writes happen after the paste and never block a dictation: a failed write
 * is logged and dropped. Desktop only; in the browser build nothing is kept.
 */
import {
	createDictationTiming,
	parseRecords,
	type TimingRecord,
} from './dictation-timing';

export {
	countWords,
	SEGMENTS,
	summarize,
	type SegmentStats,
	type TimingRecord,
} from './dictation-timing';

const FILE_NAME = 'dictations.jsonl';
/** The file is trimmed to this many lines when it grows past twice as many. */
const KEEP_LINES = 500;

async function timingFilePath(): Promise<string> {
	const { appLocalDataDir, join } = await import('@tauri-apps/api/path');
	return await join(await appLocalDataDir(), 'timing', FILE_NAME);
}

let writes: Promise<void> = Promise.resolve();

async function appendRecord(record: TimingRecord): Promise<void> {
	const { mkdir, readTextFile, writeTextFile, exists } = await import(
		'@tauri-apps/plugin-fs'
	);
	const { appLocalDataDir, join } = await import('@tauri-apps/api/path');
	const dir = await join(await appLocalDataDir(), 'timing');
	if (!(await exists(dir))) await mkdir(dir, { recursive: true });
	const file = await join(dir, FILE_NAME);
	await writeTextFile(file, `${JSON.stringify(record)}\n`, { append: true });
	// Keep the file small: trim the oldest lines now and then.
	const text = await readTextFile(file);
	const lines = text.split('\n').filter(Boolean);
	if (lines.length > KEEP_LINES * 2) {
		await writeTextFile(file, `${lines.slice(-KEEP_LINES).join('\n')}\n`);
	}
}

export const dictationTiming = createDictationTiming({
	write: (record) => {
		if (!window.__TAURI_INTERNALS__) return;
		// Serialized so two dictations finishing together don't interleave.
		writes = writes
			.then(() => appendRecord(record))
			.catch((error) => console.warn('[timing] could not save', error));
	},
});

/** The stored records, oldest first. Empty when there are none yet. */
export async function readTimingRecords(): Promise<TimingRecord[]> {
	if (!window.__TAURI_INTERNALS__) return [];
	const { exists, readTextFile } = await import('@tauri-apps/plugin-fs');
	const file = await timingFilePath();
	if (!(await exists(file))) return [];
	return parseRecords(await readTextFile(file));
}

/** Where the file lives, for the Settings page. */
export async function timingFileLocation(): Promise<string | null> {
	if (!window.__TAURI_INTERNALS__) return null;
	return await timingFilePath();
}
