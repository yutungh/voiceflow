/**
 * VoiceFlow phase F: the feedback log. One file per piece of feedback in
 * `<app data>/feedback-drafts/<uuid>.json` (the folder C1's drafts already
 * use): what the person said, what was sent, what came back, and which
 * lasting fixes they applied or undid. Kept on this device only, for as long
 * as Settings > Feedback says; "Delete all feedback" removes every file.
 *
 * Version 1 files (C1 drafts) read as drafts.
 */
import type { FixResult } from './engine';

export const FEEDBACK_SCHEMA = 'voiceflow-feedback-draft' as const;

export type FeedbackStatus = 'drafted' | 'sent' | 'fixed';

export type AppliedFix = {
	/** The reviewed fix's key in `result.fixes`. */
	key: string;
	opId: string;
	scope?: 'app' | 'everywhere';
	appliedAt: string;
	undoneAt?: string;
	tombstoneId?: string;
};

export type FeedbackRecord = {
	schema: typeof FEEDBACK_SCHEMA;
	version: 1 | 2;
	id: string;
	device: string;
	createdAt: string;
	updatedAt?: string;
	recordingId: string;
	appName?: string;
	appId?: string;
	rawText: string;
	finalText?: string;
	feedback: string;
	/** VoiceFlow 0.9.16: what the person says it should have said (optional, their words). */
	wanted?: string;
	status?: FeedbackStatus;
	sentAt?: string;
	provider?: string;
	model?: string;
	result?: FixResult;
	/** The person copied or pasted the corrected text. */
	usedCorrectedText?: 'copied' | 'pasted';
	applied?: AppliedFix[];
};

export const KEEP_FEEDBACK_OPTIONS = ['30d', '1y', 'forever'] as const;
export type KeepFeedback = (typeof KEEP_FEEDBACK_OPTIONS)[number];
export const KEEP_FEEDBACK_LABELS: Record<KeepFeedback, string> = {
	'30d': '30 days',
	'1y': '1 year',
	forever: 'Forever',
};
const DAY = 86_400_000;
const KEEP_MS: Record<KeepFeedback, number | null> = { '30d': 30 * DAY, '1y': 365 * DAY, forever: null };

export function parseFeedbackRecord(value: unknown): FeedbackRecord | null {
	if (!value || typeof value !== 'object') return null;
	const v = value as Record<string, unknown>;
	if (v.schema !== FEEDBACK_SCHEMA || (v.version !== 1 && v.version !== 2)) return null;
	for (const key of ['id', 'device', 'createdAt', 'recordingId'] as const)
		if (typeof v[key] !== 'string' || !v[key]) return null;
	if (typeof v.feedback !== 'string') return null;
	const record = { ...(v as unknown as FeedbackRecord) };
	if (typeof record.rawText !== 'string') record.rawText = '';
	if (record.wanted !== undefined && typeof record.wanted !== 'string') delete record.wanted;
	if (!record.status || !['drafted', 'sent', 'fixed'].includes(record.status))
		record.status = record.result ? 'sent' : 'drafted';
	if (record.applied && !Array.isArray(record.applied)) record.applied = [];
	return record;
}

/** The status a record should show, from what happened to it. */
export function deriveStatus(record: FeedbackRecord): FeedbackStatus {
	const liveFix = (record.applied ?? []).some((a) => !a.undoneAt);
	if (liveFix || record.usedCorrectedText) return 'fixed';
	if (record.result || record.sentAt) return 'sent';
	return 'drafted';
}

/** For History: the most advanced status among a dictation's feedback. */
export function statusForRecording(
	records: readonly FeedbackRecord[],
	recordingId: string,
): FeedbackStatus | null {
	const rank: Record<FeedbackStatus, number> = { drafted: 1, sent: 2, fixed: 3 };
	let best: FeedbackStatus | null = null;
	for (const r of records) {
		if (r.recordingId !== recordingId) continue;
		const s = deriveStatus(r);
		if (!best || rank[s] > rank[best]) best = s;
	}
	return best;
}

/** Records older than the retention setting (by when they were written). */
export function expiredFeedback(
	records: readonly FeedbackRecord[],
	keep: KeepFeedback,
	now: number,
): string[] {
	const ms = KEEP_MS[keep];
	if (ms === null) return [];
	return records
		.filter((r) => {
			const t = Date.parse(r.createdAt);
			return Number.isFinite(t) && now - t > ms;
		})
		.map((r) => r.id);
}

// ---------------------------------------------------------------------------
// Files (desktop) or localStorage (web build)
// ---------------------------------------------------------------------------

const WEB_KEY = 'voiceflow-feedback-drafts';
const DIR = 'feedback-drafts';
const isDesktop = () => typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

/** File names are the record's own uuid; anything else is refused. */
const SAFE_ID = /^[A-Za-z0-9-]{8,64}$/;

function readWeb(): unknown[] {
	try {
		return JSON.parse(localStorage.getItem(WEB_KEY) ?? '[]') as unknown[];
	} catch {
		return [];
	}
}

async function dir(): Promise<string> {
	const { appDataDir, join } = await import('@tauri-apps/api/path');
	return join(await appDataDir(), DIR);
}

export async function loadFeedback(): Promise<FeedbackRecord[]> {
	if (!isDesktop())
		return readWeb()
			.map(parseFeedbackRecord)
			.filter((r): r is FeedbackRecord => r !== null);
	const { exists, readDir, readTextFile } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const d = await dir();
	if (!(await exists(d))) return [];
	const out: FeedbackRecord[] = [];
	for (const entry of await readDir(d)) {
		if (!entry.isFile || !entry.name.endsWith('.json')) continue;
		try {
			const r = parseFeedbackRecord(JSON.parse(await readTextFile(await join(d, entry.name))));
			if (r) out.push(r);
		} catch (error) {
			console.warn(`[feedback] could not read ${entry.name}`, error);
		}
	}
	return out.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export async function writeFeedback(record: FeedbackRecord): Promise<void> {
	if (!SAFE_ID.test(record.id)) throw new Error('Invalid feedback id');
	const next: FeedbackRecord = { ...record, updatedAt: new Date().toISOString() };
	next.status = deriveStatus(next);
	if (!isDesktop()) {
		const others = readWeb().filter((r) => (r as FeedbackRecord)?.id !== record.id);
		localStorage.setItem(WEB_KEY, JSON.stringify([...others, next]));
		return;
	}
	const { exists, mkdir, writeTextFile } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const d = await dir();
	if (!(await exists(d))) await mkdir(d, { recursive: true });
	await writeTextFile(await join(d, `${record.id}.json`), `${JSON.stringify(next, null, '\t')}\n`);
}

export async function deleteFeedback(ids: readonly string[]): Promise<number> {
	const safe = ids.filter((id) => SAFE_ID.test(id));
	if (safe.length === 0) return 0;
	if (!isDesktop()) {
		const keep = readWeb().filter((r) => !safe.includes((r as FeedbackRecord)?.id));
		localStorage.setItem(WEB_KEY, JSON.stringify(keep));
		return safe.length;
	}
	const { exists, remove } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const d = await dir();
	let n = 0;
	for (const id of safe) {
		const path = await join(d, `${id}.json`);
		try {
			if (await exists(path)) {
				await remove(path);
				n += 1;
			}
		} catch (error) {
			console.warn(`[feedback] could not remove ${id}`, error);
		}
	}
	return n;
}
