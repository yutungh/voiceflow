/**
 * VoiceFlow phase F: where stage evidence lives
 * (`<app data>/dictation-evidence/<recording id>.json`, main window only).
 * The pipeline calls `noteEvidence` as each stage finishes; writes for one
 * dictation are serialised, so a later stage never loses to an earlier one.
 * Evidence for dictations no longer in History is removed by
 * `pruneEvidence`.
 */
import { mergeEvidence, parseEvidence, type DictationEvidence, type EvidencePatch } from './evidence';

const DIR = 'dictation-evidence';
const WEB_KEY = 'voiceflow-dictation-evidence';
const KEEP_IN_MEMORY = 50;
const SAFE_ID = /^[A-Za-z0-9_-]{1,64}$/;

const isDesktop = () => typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

const cache = new Map<string, DictationEvidence>();
const queues = new Map<string, Promise<void>>();

async function dir(): Promise<string> {
	const { appDataDir, join } = await import('@tauri-apps/api/path');
	return join(await appDataDir(), DIR);
}

function readWeb(): Record<string, unknown> {
	try {
		return JSON.parse(localStorage.getItem(WEB_KEY) ?? '{}') as Record<string, unknown>;
	} catch {
		return {};
	}
}

async function readStored(recordingId: string): Promise<DictationEvidence | null> {
	if (!isDesktop()) return parseEvidence(readWeb()[recordingId]);
	const { exists, readTextFile } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const path = await join(await dir(), `${recordingId}.json`);
	try {
		if (!(await exists(path))) return null;
		return parseEvidence(JSON.parse(await readTextFile(path)));
	} catch {
		return null;
	}
}

async function writeStored(evidence: DictationEvidence): Promise<void> {
	if (!isDesktop()) {
		const all = readWeb();
		all[evidence.recordingId] = evidence;
		const ids = Object.keys(all);
		for (const id of ids.slice(0, Math.max(0, ids.length - 200))) delete all[id];
		localStorage.setItem(WEB_KEY, JSON.stringify(all));
		return;
	}
	const { exists, mkdir, writeTextFile } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const d = await dir();
	if (!(await exists(d))) await mkdir(d, { recursive: true });
	await writeTextFile(await join(d, `${evidence.recordingId}.json`), `${JSON.stringify(evidence)}\n`);
}

/** Records what a stage did. Never throws: evidence must not break a dictation. */
export function noteEvidence(recordingId: string, patch: EvidencePatch): Promise<void> {
	if (!SAFE_ID.test(recordingId)) return Promise.resolve();
	const previous = queues.get(recordingId) ?? Promise.resolve();
	const next = previous
		.then(async () => {
			const stored = cache.get(recordingId) ?? (await readStored(recordingId));
			const merged = mergeEvidence(recordingId, stored, patch, new Date().toISOString());
			cache.delete(recordingId);
			cache.set(recordingId, merged);
			while (cache.size > KEEP_IN_MEMORY) {
				const oldest = cache.keys().next().value;
				if (oldest === undefined) break;
				cache.delete(oldest);
			}
			await writeStored(merged);
		})
		.catch((error) => console.warn('[evidence] could not record a stage', error));
	queues.set(recordingId, next);
	void next.finally(() => {
		if (queues.get(recordingId) === next) queues.delete(recordingId);
	});
	return next;
}

export async function loadEvidence(recordingId: string): Promise<DictationEvidence | null> {
	if (!SAFE_ID.test(recordingId)) return null;
	await queues.get(recordingId);
	return cache.get(recordingId) ?? (await readStored(recordingId));
}

/** Removes evidence whose dictation is gone from History for good. */
export async function pruneEvidence(liveIds: ReadonlySet<string>): Promise<number> {
	if (!isDesktop()) return 0;
	const { exists, readDir, remove } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const d = await dir();
	if (!(await exists(d))) return 0;
	let n = 0;
	for (const entry of await readDir(d)) {
		if (!entry.isFile || !entry.name.endsWith('.json')) continue;
		const id = entry.name.slice(0, -'.json'.length);
		if (liveIds.has(id)) continue;
		try {
			await remove(await join(d, entry.name));
			cache.delete(id);
			n += 1;
		} catch (error) {
			console.warn(`[evidence] could not remove ${entry.name}`, error);
		}
	}
	return n;
}
