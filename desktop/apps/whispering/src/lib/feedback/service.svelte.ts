/**
 * VoiceFlow phase F: feedback rounds, in the main window (the engine).
 *
 * 1. Submit: the dictation's stage evidence and the feedback go to the
 *    configured model once (`engine.ts`); the record keeps what came back.
 * 2. The corrected text can be copied, or pasted back into the dictation's
 *    app (re-authorized, its window restored).
 * 3. Lasting fixes start unchecked. Apply writes one personal edit per ticked
 *    fix, checked again first against the word lists in force now; if they
 *    changed since the fixes were reviewed, the fixes are reviewed again and
 *    the person confirms once more. Each applied fix can be undone (a
 *    tombstone for that edit).
 *
 * The feedback window reaches this through `rpc.ts`; History calls it
 * directly. Every write is serialised per record, so a double click can't
 * apply a fix twice.
 */
import {
	correctionsInForce,
	localAppRules,
	removeOp,
	saveAppStyle,
	saveCorrection,
	saveFeedbackDraft,
	saveWord,
} from '$lib/profile/op-store';
import { wordLists } from '$lib/profile/service.svelte';
import { appIdOf } from '$lib/privacy/history';
import { services } from '$lib/services';
import type { Recording } from '$lib/services/isomorphic/db';
import { settings } from '$lib/stores/settings.svelte';
import type { FeedbackClient, FeedbackRequest, FeedbackSetup, Reply } from './client';
import { FEEDBACK_REQUEST_EVENT, FEEDBACK_RESPONSE_EVENT } from './client';
import { applyFixes, undoFix } from './apply';
import { runFix } from './engine';
import { evidenceFor } from './evidence';
import { loadEvidence, pruneEvidence } from './evidence-store';
import {
	deleteFeedback,
	deriveStatus,
	expiredFeedback,
	loadFeedback,
	statusForRecording,
	writeFeedback,
	type FeedbackRecord,
	type FeedbackStatus,
	type KeepFeedback,
} from './log';
import { disclosureForV2 } from './prompt';
import { completerFor } from './providers';
import {
	fixModelFromSettings,
	fixModelView,
	keyNeeded,
	KEY_SETTING,
	modelName,
	type FeedbackProvider,
	type ResolvedFixModel,
} from './fix-model';
import { openAIFetchFor } from '$lib/services/isomorphic/openai-pool';
import type { ProfileSnapshot } from '$lib/profile/types';

/**
 * The app's own transports: the page's CSP allows no direct fetch to a
 * provider. OpenAI goes over the pooled connection, Anthropic over
 * tauri-plugin-http.
 */
function transportFor(p: FeedbackProvider): typeof fetch | undefined {
	const f = openAIFetchFor(p === 'Anthropic' ? 'https://api.anthropic.com/v1' : undefined);
	return f as unknown as typeof fetch | undefined;
}
import type { HistoryText, ReviewContext } from './validate';

const isDesktop = () => typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;
const HISTORY_FOR_COLLISIONS = 300;
const DAY = 86_400_000;

let records = $state.raw<FeedbackRecord[]>([]);
/**
 * Every change to one feedback record runs in this queue, reading the record
 * as it is now: a slow Copy or Paste can't save an older copy over an Apply,
 * and a second Apply sees the first one's edits and writes nothing.
 */
const queues = new Map<string, Promise<unknown>>();
/** Records being sent right now: a second Send is refused, never queued (one paid call). */
const sending = new Set<string>();
function serial<T>(id: string, task: () => Promise<T>): Promise<T> {
	const previous = queues.get(id) ?? Promise.resolve();
	const next = previous.then(task, task);
	queues.set(
		id,
		next.catch(() => undefined),
	);
	return next;
}

/** VoiceFlow 0.9.17: the model Settings > Feedback says finds fixes (keys loaded first). */
async function fixModel(): Promise<ResolvedFixModel> {
	await settings.apiKeysReady;
	return fixModelFromSettings(settings.value);
}

function apiKeyFor(p: FeedbackProvider): string {
	return settings.value[KEY_SETTING[p]]?.trim() ?? '';
}

async function recordingOf(id: string): Promise<Recording | null> {
	const { data } = await services.db.recordings.getById(id);
	return data && !data.deletedAt ? data : null;
}

function upsert(record: FeedbackRecord) {
	const status = deriveStatus(record);
	const next = { ...record, status };
	records = [next, ...records.filter((r) => r.id !== record.id)];
	return next;
}

async function save(record: FeedbackRecord): Promise<FeedbackRecord> {
	await writeFeedback(record);
	return upsert(record);
}

async function recordById(id: string): Promise<FeedbackRecord | null> {
	const found = records.find((r) => r.id === id);
	if (found) return found;
	const all = await loadFeedback();
	records = all;
	return all.find((r) => r.id === id) ?? null;
}

/** The word lists' revision a review is made against. */
function revisionOf(snapshot: ProfileSnapshot | null, active: readonly { id: string }[]): string {
	if (snapshot) return `lists:${snapshot.id}`;
	const rules = [...localAppRules().values()].map((r) => r.editId ?? '').join(',');
	return `local:${active.map((c) => c.id).join(',')}|${rules}`;
}

/** The revision of the lists in force now (read after an edit is written). */
async function revision(): Promise<string> {
	const snapshot = wordLists.snapshot;
	return revisionOf(snapshot, snapshot ? [] : await correctionsInForce(null));
}

async function reviewContext(recording: Recording): Promise<Omit<ReviewContext, 'evidence'> & { revision: string }> {
	// One snapshot for the whole review: its revision names exactly what was checked.
	const snapshot = wordLists.snapshot;
	const active = await correctionsInForce(snapshot);
	const { data: all } = await services.db.recordings.getAll();
	const history: HistoryText[] = (all ?? [])
		.filter((r) => !r.deletedAt && r.id !== recording.id)
		.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
		.slice(0, HISTORY_FOR_COLLISIONS)
		.map((r) => ({ recordingId: r.id, appId: appIdOf(r), text: r.finalText || r.transcribedText || '' }));
	return {
		snapshot,
		active,
		localAppRules: localAppRules(),
		history,
		revision: revisionOf(snapshot, active),
	};
}

async function setup(recordingId: string): Promise<Reply<{ setup: FeedbackSetup }>> {
	const recording = await recordingOf(recordingId);
	const resolved = await fixModel();
	const setup: FeedbackSetup = {
		subject: recording
			? {
					recordingId: recording.id,
					appName: recording.appName ?? null,
					at: recording.createdAt,
					styleName: recording.styleName ?? null,
					deliveredText: recording.finalText ?? '',
					rawText: recording.transcribedText ?? '',
					canPaste: !!(recording.appExe || recording.appBundleId) && recording.privacy !== 'held',
				}
			: null,
		...fixModelView(resolved, (provider, name) => disclosureForV2(provider, recording?.appName, name)),
	};
	return { ok: true, setup };
}

/** The longest "what it should have said" kept (a rewrite of one dictation). */
const WANTED_CAP = 20_000;

/** Longer than this is refused, never cut. */
const tooLong = (text: string | undefined) => (text?.length ?? 0) > WANTED_CAP;
const TOO_LONG = `What it should have said is too long (${WANTED_CAP.toLocaleString('en')} characters at most).`;

function wantedOf(text: string | undefined): string | undefined {
	const t = text?.replace(/\r\n?/g, '\n');
	return t?.trim() ? t : undefined;
}

async function draftFor(a: { recordingId: string; feedback: string; wanted?: string; recordId?: string }) {
	const recording = await recordingOf(a.recordingId);
	if (!recording) return { recording: null, record: null };
	const existing = a.recordId ? await recordById(a.recordId) : null;
	const wanted = wantedOf(a.wanted);
	if (existing && existing.recordingId === recording.id) {
		const { wanted: _old, ...rest } = existing;
		return { recording, record: { ...rest, feedback: a.feedback.trim(), ...(wanted ? { wanted } : {}) } };
	}
	const record = await saveFeedbackDraft({
		recordingId: recording.id,
		appName: recording.appName,
		appId: appIdOf(recording) ?? undefined,
		rawText: recording.transcribedText,
		finalText: recording.finalText,
		feedback: a.feedback.trim(),
		...(wanted ? { wanted } : {}),
	});
	upsert(record);
	return { recording, record };
}

const service: FeedbackClient = {
	setup,

	async saveDraft(a) {
		if (!a.feedback.trim()) return { ok: false, message: 'Say what should have been different.' };
		if (tooLong(a.wanted)) return { ok: false, message: TOO_LONG };
		return serial(a.recordId ?? `new:${a.recordingId}`, async () => {
			const { record } = await draftFor(a);
			if (!record) return { ok: false as const, message: 'That dictation is no longer in History.' };
			return { ok: true as const, record: await save(record) };
		});
	},

	async submit(a) {
		if (!a.feedback.trim()) return { ok: false, message: 'Say or type what should have been different.' };
		if (tooLong(a.wanted)) return { ok: false, message: TOO_LONG };
		const before = await fixModel();
		if (!before.ok) return { ok: false, message: keyNeeded(before).message };
		const { provider: shown, model: shownModel } = before.choice;
		if (a.expect && (a.expect.provider !== shown || a.expect.model !== shownModel))
			return {
				ok: false,
				message: `Settings changed: this would now go to ${shown} (${modelName(before.choice)}). Check it and try again.`,
			};
		const { recording, record } = await draftFor(a);
		if (!recording || !record) return { ok: false, message: 'That dictation is no longer in History.' };
		if (sending.has(record.id)) return { ok: false, message: 'This feedback is already being sent.', record };
		sending.add(record.id);
		return serial(record.id, async () => {
			const now = await fixModel();
			if (!now.ok || now.choice.provider !== shown || now.choice.model !== shownModel)
				return { ok: false as const, message: 'Settings changed while sending. Check them and try again.', record };
			const { provider: p, model } = now.choice;
			const apiKey = apiKeyFor(p);
			const evidence = evidenceFor(recording, await loadEvidence(recording.id), appIdOf(recording));
			const review = await reviewContext(recording);
			const outcome = await runFix({
				evidence,
				feedback: record.feedback,
				wanted: record.wanted ?? null,
				review,
				provider: { apiKey, model, fetch: transportFor(p) },
				complete: completerFor(p),
			});
			const { wanted: _stored, ...latest } = (await recordById(record.id)) ?? record;
			const wanted = record.wanted ? { wanted: record.wanted } : {};
			if (!outcome.ok) {
				const kept = await save({ ...latest, feedback: record.feedback, ...wanted });
				return { ok: false as const, message: outcome.message, record: kept };
			}
			const next = await save({
				...latest,
				feedback: record.feedback,
				...wanted,
				version: 2,
				sentAt: new Date().toISOString(),
				provider: p,
				model,
				result: outcome.result,
			});
			return { ok: true as const, record: next };
		}).finally(() => sending.delete(record.id));
	},

	async apply(a) {
		return serial(a.recordId, async (): Promise<Reply<{ record: FeedbackRecord }>> => {
			const record = await recordById(a.recordId);
			if (!record?.result) return { ok: false, message: 'There are no fixes to apply yet.' };
			const recording = await recordingOf(record.recordingId);
			if (!recording) return { ok: false, message: 'That dictation is no longer in History.' };
			const outcome = await applyFixes({
				record,
				selections: a.selections,
				evidence: evidenceFor(recording, await loadEvidence(recording.id), appIdOf(recording)),
				review: await reviewContext(recording),
				revisionAfter: revision,
				reviewAfter: () => reviewContext(recording),
				writers: { saveCorrection, saveAppStyle, saveWord, removeOp },
			});
			const saved = outcome.record === record ? record : await save(outcome.record);
			if (outcome.ok) return { ok: true, record: saved };
			return { ok: false, message: outcome.message, record: saved, ...(outcome.stale ? { stale: true } : {}) };
		});
	},

	async undo(a) {
		return serial(a.recordId, async (): Promise<Reply<{ record: FeedbackRecord; message?: string }>> => {
			const record = await recordById(a.recordId);
			if (!record) return { ok: false, message: 'That feedback is gone.' };
			try {
				const outcome = await undoFix({
					record,
					opId: a.opId,
					removeOp,
					revisionAfter: revision,
					listsAfter: () => wordLists.snapshot,
					reviewAfter: async () => {
						const recording = await recordingOf(record.recordingId);
						if (!recording) return null;
						return {
							evidence: evidenceFor(recording, await loadEvidence(recording.id), appIdOf(recording)),
							review: await reviewContext(recording),
						};
					},
				});
				if (!outcome.ok) return outcome;
				return {
					ok: true,
					record: await save(outcome.record),
					...(outcome.message ? { message: outcome.message } : {}),
				};
			} catch (error) {
				return { ok: false, message: `It couldn't be undone (${error instanceof Error ? error.message : String(error)}).` };
			}
		});
	},

	async copy(a) {
		const found = await recordById(a.recordId);
		if (!found) return { ok: false, message: 'That feedback is gone.' };
		const { rpc } = await import('$lib/query');
		const { error } = await rpc.text.copyToClipboard.execute({ text: a.text });
		if (error) return { ok: false, message: "Couldn't copy to the clipboard." };
		return serial(a.recordId, async () => {
			const record = (await recordById(a.recordId)) ?? found;
			const saved = await save({ ...record, usedCorrectedText: record.usedCorrectedText ?? 'copied' });
			return { ok: true as const, message: 'Copied', record: saved };
		});
	},

	async paste(a) {
		const found = await recordById(a.recordId);
		if (!found) return { ok: false, message: 'That feedback is gone.' };
		const { recovery } = await import('$lib/query/isomorphic/actions');
		const result = await recovery.pasteCorrected(found.recordingId, a.text);
		if (!result.ok) return { ok: false, message: result.message, record: (await recordById(a.recordId)) ?? found };
		return serial(a.recordId, async () => {
			const record = (await recordById(a.recordId)) ?? found;
			const saved = await save({ ...record, usedCorrectedText: 'pasted' });
			return { ok: true as const, message: result.message, record: saved };
		});
	},

	/** VoiceFlow 0.9.17: the main window, at the Settings section where a key goes. */
	async openSettings(section) {
		if (section !== 'feedback' && section !== 'transcription') return { ok: false, message: 'Unknown section.' };
		if (isDesktop()) {
			const { getCurrentWindow } = await import('@tauri-apps/api/window');
			const main = getCurrentWindow();
			await main.show().catch(() => {});
			await main.unminimize().catch(() => {});
			await main.setFocus().catch(() => {});
		}
		const { goto } = await import('$app/navigation');
		await goto(`/settings#${section}`);
		return { ok: true };
	},
};

async function handleRequest(req: FeedbackRequest): Promise<Reply<Record<string, unknown>>> {
	switch (req.op) {
		case 'setup':
			return service.setup(req.recordingId);
		case 'saveDraft':
			return service.saveDraft(req);
		case 'submit':
			return service.submit(req);
		case 'apply':
			return service.apply(req);
		case 'undo':
			return service.undo(req);
		case 'copy':
			return service.copy(req);
		case 'paste':
			return service.paste(req);
		case 'openSettings':
			return service.openSettings(req.section);
		default:
			return { ok: false, message: 'Unknown request.' };
	}
}

/** Feedback older than Settings > Feedback allows, and evidence for gone dictations. */
async function prune(): Promise<void> {
	try {
		const all = await loadFeedback();
		const keep = settings.value['feedback.keep'] as KeepFeedback;
		const expired = expiredFeedback(all, keep, Date.now());
		if (expired.length) await deleteFeedback(expired);
		records = all.filter((r) => !expired.includes(r.id));
		const { data: dictations } = await services.db.recordings.getAll();
		if (dictations) await pruneEvidence(new Set(dictations.map((d) => d.id)));
	} catch (error) {
		console.warn('[feedback] could not tidy the feedback log', error);
	}
}

let started = false;

export const feedbackService = {
	...service,
	/** Main window only: answers the feedback window and keeps the log tidy. */
	async init() {
		if (started || !isDesktop()) return;
		started = true;
		await prune();
		setInterval(() => void prune(), DAY);
		const { listen, emitTo } = await import('@tauri-apps/api/event');
		await listen<{ reqId: string; request: FeedbackRequest }>(FEEDBACK_REQUEST_EVENT, async (event) => {
			const { reqId, request } = event.payload ?? ({} as { reqId?: string; request?: FeedbackRequest });
			if (typeof reqId !== 'string' || !request || typeof request !== 'object') return;
			let reply: Reply<Record<string, unknown>>;
			try {
				reply = await handleRequest(request);
			} catch (error) {
				reply = { ok: false, message: error instanceof Error ? error.message : String(error) };
			}
			await emitTo('feedback', FEEDBACK_RESPONSE_EVENT, { reqId, reply });
		});
	},
	/** Loads the log for History (web build too). */
	async load() {
		records = await loadFeedback();
	},
	get records() {
		return records;
	},
	statusFor(recordingId: string): FeedbackStatus | null {
		return statusForRecording(records, recordingId);
	},
	/** The newest feedback on a dictation, to reopen it in History. */
	latestFor(recordingId: string): FeedbackRecord | null {
		return records.find((r) => r.recordingId === recordingId) ?? null;
	},
	async deleteAll(): Promise<number> {
		const all = await loadFeedback();
		const n = await deleteFeedback(all.map((r) => r.id));
		records = [];
		return n;
	},
	prune,
};
