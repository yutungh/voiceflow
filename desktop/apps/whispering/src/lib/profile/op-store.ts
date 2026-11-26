/**
 * VoiceFlow: where profile operations and feedback drafts live on this
 * device (PLAN-v2, "Add a word").
 *
 * - `<app data>/profile-ops/<device>-<uuid>.json`: one immutable operation
 *   per file (see `ops.ts`). Written once, never edited; Undo writes a
 *   tombstone. With word lists on, the Rust profile service uploads them to
 *   `profiles/<user>/ops/` on the share and merges them over the other
 *   layers (`wordLists.editsChanged()` after each write).
 * - `<app data>/feedback-drafts/<uuid>.json`: what the person said was wrong
 *   with a dictation, kept locally: the feedback log (`$lib/feedback/log`),
 *   which phase F turns into fixes.
 *
 * Outside the desktop app (the web dev build used for screenshots) both are
 * kept in localStorage instead.
 */
import { nanoid } from 'nanoid/non-secure';
import {
	activeAppStyles,
	activeCorrections,
	EVERY_APP,
	liveAppStyleOps,
	OP_SCHEMA,
	OP_VERSION,
	parseOp,
	removedIds,
	validateNewCorrection,
	type AppStyleOp,
	type LocalAppRule,
	type CorrectionOp,
	type NewCorrection,
	type ProfileOp,
	type TermOp,
	type TombstoneOp,
} from './ops';
import { wordLists } from './service.svelte';
import { FEEDBACK_SCHEMA, writeFeedback, type FeedbackRecord } from '$lib/feedback/log';
import type { ProfileSnapshot } from './types';

const DEVICE_KEY = 'voiceflow-device-id';
/** Heard forms per word or spelling (the word lists accept at most 50). */
const MAX_FORMS = 50;
const WEB_OPS_KEY = 'voiceflow-profile-ops';
const WEB_DRAFTS_KEY = 'voiceflow-feedback-drafts';

const isDesktop = () =>
	typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

/** This device's id, made once and kept with the other device settings. */
export function deviceId(): string {
	try {
		const stored = localStorage.getItem(DEVICE_KEY);
		if (stored) return stored;
		const id = `pc${nanoid(8).toLowerCase().replace(/[^a-z0-9]/g, '0')}`;
		localStorage.setItem(DEVICE_KEY, id);
		return id;
	} catch {
		return 'pc-unknown';
	}
}

function uuid(): string {
	return crypto.randomUUID();
}

async function dataDir(sub: string): Promise<string> {
	const { appDataDir, join } = await import('@tauri-apps/api/path');
	return join(await appDataDir(), sub);
}

async function ensureDir(path: string) {
	const { exists, mkdir } = await import('@tauri-apps/plugin-fs');
	if (!(await exists(path))) await mkdir(path, { recursive: true });
}

function readWeb<T>(key: string): T[] {
	try {
		return JSON.parse(localStorage.getItem(key) ?? '[]') as T[];
	} catch {
		return [];
	}
}

function writeWeb<T>(key: string, items: T[]) {
	localStorage.setItem(key, JSON.stringify(items));
}

/** Every well-formed operation on this device. Malformed files are skipped. */
export async function loadOps(): Promise<ProfileOp[]> {
	if (!isDesktop()) {
		return readWeb<unknown>(WEB_OPS_KEY)
			.map(parseOp)
			.filter((op): op is ProfileOp => op !== null);
	}
	const { exists, readDir, readTextFile } = await import(
		'@tauri-apps/plugin-fs'
	);
	const { join } = await import('@tauri-apps/api/path');
	const dir = await dataDir('profile-ops');
	if (!(await exists(dir))) return [];
	const entries = await readDir(dir);
	const ops: ProfileOp[] = [];
	for (const entry of entries) {
		if (!entry.isFile || !entry.name.endsWith('.json')) continue;
		try {
			const op = parseOp(JSON.parse(await readTextFile(await join(dir, entry.name))));
			if (op) ops.push(op);
			else console.warn(`[profile-ops] skipped malformed ${entry.name}`);
		} catch (error) {
			console.warn(`[profile-ops] could not read ${entry.name}`, error);
		}
	}
	return ops;
}

// VoiceFlow phase F: app rules from this device's edits, for the bar while
// word lists are off (with them on, the merged snapshot carries them).
let appRuleCache = new Map<string, LocalAppRule>();

/** The app rules this device's edits set (synchronous, for the bar). */
export function localAppRules(): ReadonlyMap<string, LocalAppRule> {
	return appRuleCache;
}

const appRuleListeners = new Set<() => void>();

/**
 * VoiceFlow 0.9.21: called after every re-read of this device's app rules
 * (a Settings change, a feedback fix or its Undo), so a page showing them
 * stays current. Returns the unsubscribe.
 */
export function onLocalAppRulesChange(listener: () => void): () => void {
	appRuleListeners.add(listener);
	return () => appRuleListeners.delete(listener);
}

/** Re-reads this device's edits into the app-rule cache. */
export async function refreshLocalAppRules(): Promise<void> {
	try {
		appRuleCache = activeAppStyles(await loadOps());
	} catch (error) {
		console.warn('[profile-ops] could not read app rules', error);
	}
	for (const listener of appRuleListeners) listener();
}

async function writeOp(op: ProfileOp): Promise<void> {
	await writeOpFile(op);
	if (op.type === 'app-style' || op.type === 'tombstone') await refreshLocalAppRules();
}

async function writeOpFile(op: ProfileOp): Promise<void> {
	if (!isDesktop()) {
		writeWeb(WEB_OPS_KEY, [...readWeb<ProfileOp>(WEB_OPS_KEY), op]);
		return;
	}
	const { join } = await import('@tauri-apps/api/path');
	const { exists, writeTextFile } = await import('@tauri-apps/plugin-fs');
	const dir = await dataDir('profile-ops');
	await ensureDir(dir);
	const path = await join(dir, `${op.id}.json`);
	// Operations are immutable: never overwrite one.
	if (await exists(path)) throw new Error(`Operation ${op.id} already exists`);
	await writeTextFile(path, `${JSON.stringify(op, null, '\t')}\n`);
}

function newBase() {
	const device = deviceId();
	return {
		schema: OP_SCHEMA,
		version: OP_VERSION,
		id: `${device}-${uuid()}`,
		device,
		createdAt: new Date().toISOString(),
	};
}

export type SaveResult =
	| { ok: true; op: CorrectionOp }
	| { ok: false; message: string };

/** A word or heard form the word lists accept: one line, no < or >. */
export function wordProblem(word: string): string | null {
	const w = word.trim();
	if (!w) return 'Type a word.';
	if (w.length > 60) return `“${w.slice(0, 20)}…” is too long (60 characters at most).`;
	if (/[<>\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(w))
		return 'Words can’t contain < or >, line breaks or control characters.';
	return null;
}

/**
 * The spellings in force, to check a new one against: with word lists on,
 * every layer's (household, notes, personal, edits from every device);
 * otherwise this device's edits.
 */
export async function correctionsInForce(
	snapshot: ProfileSnapshot | null = wordLists.snapshot,
): Promise<CorrectionOp[]> {
	if (snapshot)
		return snapshot.corrections.map((c) => ({
			schema: OP_SCHEMA,
			version: OP_VERSION,
			id: c.id,
			device: c.device ?? c.layer,
			createdAt: c.createdAt ?? '',
			type: 'correction',
			from: c.from,
			to: c.to,
			scope: c.scope,
		}));
	return activeCorrections(await loadOps());
}

/** Validates and saves a spelling. Plain-language refusal when it can't. */
export async function saveCorrection(
	candidate: NewCorrection & { note?: string },
): Promise<SaveResult> {
	if (candidate.from.length > MAX_FORMS)
		return { ok: false, message: `At most ${MAX_FORMS} heard forms in one spelling.` };
	for (const word of [...candidate.from, candidate.to]) {
		const problem = word.trim() ? wordProblem(word) : null;
		if (problem) return { ok: false, message: problem };
	}
	const active = await correctionsInForce();
	const checked = validateNewCorrection(active, candidate);
	if (!checked.ok) return checked;
	const op: CorrectionOp = {
		...newBase(),
		type: 'correction',
		from: candidate.from.map((f) => f.trim()).filter(Boolean),
		to: candidate.to.trim(),
		scope: {
			apps: candidate.scope.apps.map((a) =>
				a === EVERY_APP ? a : a.toLowerCase(),
			),
		},
	};
	if (candidate.note?.trim()) op.note = candidate.note.trim();
	try {
		await writeOp(op);
	} catch (error) {
		return {
			ok: false,
			message: `The spelling couldn't be saved (${error instanceof Error ? error.message : String(error)}).`,
		};
	}
	await wordLists.editsChanged();
	return { ok: true, op };
}

export type NewTerm = {
	text: string;
	heardAs?: string[];
	kind?: string;
	apps?: string[];
};

export type SaveTermResult =
	| { ok: true; op: TermOp }
	| { ok: false; message: string };

/**
 * "Add a word": a word for the vocabulary prompt, saved as an edit to the
 * personal word list. Refused when it is already there.
 */
export async function saveTerm(candidate: NewTerm): Promise<SaveTermResult> {
	const text = candidate.text.trim().replace(/\s+/g, ' ');
	const problem = wordProblem(text);
	if (problem) return { ok: false, message: problem };
	const heardAs = (candidate.heardAs ?? [])
		.map((h) => h.trim().replace(/\s+/g, ' '))
		.filter((h) => h && h.toLocaleLowerCase() !== text.toLocaleLowerCase());
	if (heardAs.length > MAX_FORMS)
		return { ok: false, message: `At most ${MAX_FORMS} heard forms for one word.` };
	for (const heard of heardAs) {
		const issue = wordProblem(heard);
		if (issue) return { ok: false, message: issue };
	}
	const existing = wordLists.snapshot?.terms.find(
		(t) => t.text.toLocaleLowerCase() === text.toLocaleLowerCase(),
	);
	if (existing)
		return { ok: false, message: `“${existing.text}” is already in your words.` };
	const term: TermOp['term'] = { text };
	if (heardAs.length > 0) term.heardAs = heardAs;
	if (candidate.kind) term.kind = candidate.kind;
	if (candidate.apps?.length)
		term.apps = candidate.apps.map((a) => (a === EVERY_APP ? a : a.toLowerCase()));
	const op: TermOp = { ...newBase(), type: 'term', term };
	try {
		await writeOp(op);
	} catch (error) {
		return {
			ok: false,
			message: `The word couldn't be saved (${error instanceof Error ? error.message : String(error)}).`,
		};
	}
	await wordLists.editsChanged();
	return { ok: true, op };
}

/** VoiceFlow 0.9.16: a word as feedback writes it, every field it keeps. */
export type WordEdit = {
	text: string;
	heardAs?: string[];
	kind?: string;
	gloss?: string;
	priority?: number;
	apps?: string[];
};

/**
 * VoiceFlow 0.9.16 (feedback): writes a word as one edit. Unlike "Add a
 * word", the word may already be in the lists: the edit is the whole entry
 * (its heard forms with the new one, or its raised priority), and the merge
 * keeps the highest layer's entry for one word, so it replaces the listed
 * one until it is undone (a tombstone brings the listed one back).
 */
export async function saveWord(
	word: WordEdit,
): Promise<{ ok: true; op: TermOp } | { ok: false; message: string }> {
	const text = word.text.trim().replace(/\s+/g, ' ');
	for (const w of [text, ...(word.heardAs ?? [])]) {
		const problem = wordProblem(w);
		if (problem) return { ok: false, message: problem };
	}
	if ((word.heardAs?.length ?? 0) > MAX_FORMS)
		return { ok: false, message: `At most ${MAX_FORMS} heard forms for one word.` };
	const term: Record<string, unknown> = { text };
	if (word.heardAs?.length) term.heardAs = word.heardAs.map((h) => h.trim().replace(/\s+/g, ' '));
	if (word.kind) term.kind = word.kind;
	if (word.gloss) term.gloss = word.gloss;
	if (word.priority !== undefined) term.priority = word.priority;
	if (word.apps?.length) term.apps = word.apps.map((a) => (a === EVERY_APP ? a : a.toLowerCase()));
	const op = parseOp({ ...newBase(), type: 'term', term });
	if (!op || op.type !== 'term') return { ok: false, message: 'That word isn’t one VoiceFlow can save.' };
	try {
		await writeOp(op);
	} catch (error) {
		return {
			ok: false,
			message: `The word couldn't be saved (${error instanceof Error ? error.message : String(error)}).`,
		};
	}
	await wordLists.editsChanged();
	return { ok: true, op };
}

export type NewAppStyle = { app: string; style?: string; expression?: number };

/**
 * VoiceFlow phase F: an app's style and/or expression, from an approved
 * feedback fix. Personal (an edit on this device, uploaded to the person's
 * own lists), never household.
 */
export async function saveAppStyle(
	candidate: NewAppStyle,
): Promise<{ ok: true; op: AppStyleOp } | { ok: false; message: string }> {
	const app = candidate.app.trim().toLowerCase();
	const op = parseOp({
		...newBase(),
		type: 'app-style',
		app,
		...(candidate.style !== undefined ? { style: candidate.style } : {}),
		...(candidate.expression !== undefined ? { expression: candidate.expression } : {}),
	});
	if (!op || op.type !== 'app-style')
		return { ok: false, message: 'That style change isn’t one VoiceFlow can save.' };
	try {
		await writeOp(op);
	} catch (error) {
		return {
			ok: false,
			message: `The style change couldn't be saved (${error instanceof Error ? error.message : String(error)}).`,
		};
	}
	await wordLists.editsChanged();
	return { ok: true, op };
}

/**
 * VoiceFlow 0.9.21 (Settings > Tidy-up > Style for each app): sets the
 * style, and the level or none ("Automatic"), an app starts in. It replaces
 * this device's own earlier edits for the app, so an older level can't
 * linger under the new choice: the new edit is written first, then those
 * edits get tombstones. If the write fails nothing changed; if a tombstone
 * fails the new edit is still the newest and in force. Edits from other
 * devices are left alone (removing the setting retires those too).
 */
export async function replaceAppStyle(candidate: {
	app: string;
	style: string;
	expression?: number;
}): Promise<
	| { ok: true; op: AppStyleOp; retired: string[]; warning?: string }
	| { ok: false; message: string }
> {
	const app = candidate.app.trim().toLowerCase();
	const before = liveAppStyleOps(await loadOps(), app).map((op) => op.id);
	const op = parseOp({
		...newBase(),
		type: 'app-style',
		app,
		style: candidate.style,
		...(candidate.expression !== undefined ? { expression: candidate.expression } : {}),
	});
	if (!op || op.type !== 'app-style')
		return { ok: false, message: 'That app or style isn’t one VoiceFlow can save.' };
	try {
		await writeOpFile(op);
	} catch (error) {
		return {
			ok: false,
			message: `The style for this app couldn't be saved (${error instanceof Error ? error.message : String(error)}).`,
		};
	}
	const retired: string[] = [];
	let warning: string | undefined;
	for (const target of before) {
		try {
			await writeOpFile({ ...newBase(), type: 'tombstone', target });
			retired.push(target);
		} catch {
			warning = 'Saved. An older setting for this app could not be cleared; the new one is in force.';
		}
	}
	await refreshLocalAppRules();
	await wordLists.editsChanged();
	return { ok: true, op, retired, ...(warning ? { warning } : {}) };
}

/**
 * VoiceFlow 0.9.21: removes an app's own setting: a tombstone for each edit
 * in force for it (this device's, plus `alsoIds`: the merged lists' edits
 * for the app, cached ones from other devices included). Returns the
 * tombstones written, for Undo.
 */
export async function removeAppStyle(
	app: string,
	alsoIds: readonly string[] = [],
): Promise<{ ok: true; tombstones: TombstoneOp[] } | { ok: false; message: string; tombstones: TombstoneOp[] }> {
	const key = app.trim().toLowerCase();
	const targets = [
		...new Set([...liveAppStyleOps(await loadOps(), key).map((op) => op.id), ...alsoIds]),
	];
	const tombstones: TombstoneOp[] = [];
	let message: string | null = null;
	for (const target of targets) {
		const op: TombstoneOp = { ...newBase(), type: 'tombstone', target };
		try {
			await writeOpFile(op);
			tombstones.push(op);
		} catch (error) {
			message = `The setting couldn't be fully removed (${error instanceof Error ? error.message : String(error)}).`;
		}
	}
	await refreshLocalAppRules();
	await wordLists.editsChanged();
	return message ? { ok: false, message, tombstones } : { ok: true, tombstones };
}

/**
 * VoiceFlow 0.9.21: Undo for `removeAppStyle`: the tombstones' own
 * tombstones. Refused (false, nothing written) once the app has a newer
 * edit on this device: bringing the removed ones back would put an older
 * level under the newer choice (any live edit for the app that the
 * removal didn't retire is newer).
 */
export async function undoRemoveAppStyle(
	app: string,
	tombstones: readonly TombstoneOp[],
): Promise<boolean> {
	const removed = new Set(tombstones.map((t) => t.target));
	const live = liveAppStyleOps(await loadOps(), app.trim().toLowerCase());
	if (live.some((op) => !removed.has(op.id))) return false;
	for (const t of tombstones) await writeOpFile({ ...newBase(), type: 'tombstone', target: t.id });
	await refreshLocalAppRules();
	await wordLists.editsChanged();
	return true;
}

/** Whether an edit is still in force on this device (not undone). */
export async function opIsLive(id: string): Promise<boolean> {
	const ops = await loadOps();
	return ops.some((op) => op.id === id) && !removedIds(ops).has(id);
}

/** Removes an operation by writing a tombstone for it (used by Undo). */
export async function removeOp(target: string): Promise<TombstoneOp> {
	const op: TombstoneOp = { ...newBase(), type: 'tombstone', target };
	await writeOp(op);
	await wordLists.editsChanged();
	return op;
}

export type FeedbackDraft = FeedbackRecord;

/** Keeps a feedback draft on this device (the feedback log's first state). */
export async function saveFeedbackDraft(
	draft: Pick<FeedbackRecord, 'recordingId' | 'appName' | 'appId' | 'rawText' | 'finalText' | 'feedback' | 'wanted'>,
): Promise<FeedbackDraft> {
	const full: FeedbackRecord = {
		schema: FEEDBACK_SCHEMA,
		version: 2,
		id: uuid(),
		device: deviceId(),
		createdAt: new Date().toISOString(),
		status: 'drafted',
		...draft,
	};
	await writeFeedback(full);
	return full;
}

if (isDesktop()) void refreshLocalAppRules();
