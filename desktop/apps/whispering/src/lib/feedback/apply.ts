/**
 * VoiceFlow phase F: applying and undoing lasting fixes. Pure apart from the
 * writers passed in, so double-apply, stale reviews and undo are tested.
 *
 * - A fix already applied (and not undone) is never written again.
 * - If the word lists changed since the fixes were reviewed, nothing is
 *   written: the fixes are reviewed again and the person confirms again.
 * - The chosen fixes are checked together right before writing.
 * - Undo writes a tombstone for that one edit.
 *
 * VoiceFlow 0.9.16: a word fix writes a `term` edit (the whole entry); turning
 * a spelling off writes a tombstone for it, so its Undo is a tombstone for
 * that tombstone, which brings the spelling back. Spellings are turned off
 * first, so a spelling chosen to replace one is checked against the lists
 * without it.
 */
import { EVERY_APP } from '../profile/ops';
import type { WordEdit } from '../profile/op-store';
import { issuesOf } from './engine';
import type { DictationEvidence } from './evidence';
import type { AppliedFix, FeedbackRecord } from './log';
import { checkSelection, reviewProposals, type ReviewContext, type Selection, type WordChoice } from './validate';

type Saved = Promise<{ ok: true; op: { id: string; createdAt: string } } | { ok: false; message: string }>;

export type Writers = {
	saveCorrection: (c: { from: string[]; to: string; scope: { apps: string[] }; note?: string }) => Saved;
	saveAppStyle: (s: { app: string; style?: string; expression?: number }) => Saved;
	/** 0.9.16: a word, every field it keeps (it may replace a listed one). */
	saveWord: (w: WordEdit) => Saved;
	removeOp: (target: string) => Promise<{ id: string; createdAt: string }>;
};

export type ApplyOutcome =
	| { ok: true; record: FeedbackRecord }
	| { ok: false; message: string; record: FeedbackRecord; stale?: true };

/** The edit a word choice writes. */
export function wordEditOf(choice: WordChoice): WordEdit {
	const t = choice.term;
	return {
		text: t.text,
		...(t.heardAs.length ? { heardAs: t.heardAs } : {}),
		...(t.kind ? { kind: t.kind } : {}),
		...(t.gloss ? { gloss: t.gloss } : {}),
		priority: t.priority,
		apps: t.apps.length ? t.apps : [EVERY_APP],
	};
}

/** The review context for a record: its wanted text and its issues' layers. */
export function contextFor(
	record: FeedbackRecord,
	evidence: DictationEvidence,
	review: Omit<ReviewContext, 'evidence'>,
): ReviewContext {
	return {
		...review,
		evidence,
		wanted: record.wanted ?? null,
		...(record.result?.contract === 2 ? { layers: issuesOf(record.result).map((i) => i.layer) } : {}),
	};
}

export async function applyFixes({
	record,
	selections: asked,
	evidence,
	review,
	revisionAfter,
	writers,
}: {
	record: FeedbackRecord;
	selections: readonly Selection[];
	evidence: DictationEvidence;
	review: Omit<ReviewContext, 'evidence'> & { revision: string };
	/** The lists' revision once the edits are in (read after writing). */
	revisionAfter: () => Promise<string>;
	writers: Writers;
}): Promise<ApplyOutcome> {
	const result = record.result;
	if (!result) return { ok: false, message: 'There are no fixes to apply yet.', record };
	const live = new Set((record.applied ?? []).filter((x) => !x.undoneAt).map((x) => x.key));
	const unique = asked.filter((s, i, all) => !live.has(s.key) && all.findIndex((t) => t.key === s.key) === i);
	if (unique.length === 0) return { ok: false, message: 'Tick a fix to apply first.', record };
	const ctx = contextFor(record, evidence, review);
	if (review.revision !== result.reviewedAgainst) {
		const again = reviewProposals(result.proposals ?? [], ctx);
		return {
			ok: false,
			stale: true,
			message:
				'Your word lists changed since these fixes were checked. Look them over again, then apply.',
			record: {
				...record,
				result: { ...result, fixes: again.fixes, dropped: again.dropped, reviewedAgainst: review.revision },
			},
		};
	}
	const checked = checkSelection(result.fixes, unique, ctx, live);
	if (!checked.ok) return { ok: false, message: checked.message, record };
	// Spellings are turned off before anything is written over them.
	const kindOf = (s: Selection) => result.fixes.find((f) => f.key === s.key)?.kind;
	const selections = [...unique.filter((s) => kindOf(s) === 'unspell'), ...unique.filter((s) => kindOf(s) !== 'unspell')];
	const applied: AppliedFix[] = [...(record.applied ?? [])];
	let failure: string | null = null;
	for (const sel of selections) {
		const fix = result.fixes.find((f) => f.key === sel.key);
		if (!fix) continue;
		if (fix.kind === 'unspell') {
			try {
				const tombstone = await writers.removeOp(fix.target);
				applied.push({ key: fix.key, opId: tombstone.id, appliedAt: tombstone.createdAt });
			} catch (error) {
				failure = `The spelling couldn't be turned off (${error instanceof Error ? error.message : String(error)}).`;
				break;
			}
			continue;
		}
		let saved: Awaited<Saved>;
		let scope: 'app' | 'everywhere' | undefined;
		if (fix.kind === 'spelling') {
			scope = sel.scope ?? fix.defaultScope;
			saved = await writers.saveCorrection({
				from: fix.from,
				to: fix.to,
				scope: { apps: scope === 'app' && fix.appId ? [fix.appId] : [EVERY_APP] },
				note: 'From feedback',
			});
		} else if (fix.kind === 'word') {
			scope = sel.scope ?? fix.defaultScope;
			const choice = fix.choices[scope];
			if (!choice) {
				failure = 'That word fix is no longer offered.';
				break;
			}
			saved = await writers.saveWord(wordEditOf(choice));
		} else {
			saved = await writers.saveAppStyle({
				app: fix.app,
				...(fix.style ? { style: fix.style.id } : {}),
				...(fix.expression ? { expression: fix.expression.to } : {}),
			});
		}
		if (!saved.ok) {
			failure = saved.message;
			break;
		}
		applied.push({ key: fix.key, opId: saved.op.id, ...(scope ? { scope } : {}), appliedAt: saved.op.createdAt });
	}
	const next: FeedbackRecord = {
		...record,
		applied,
		// The lists now include these edits: that is the revision reviewed.
		result: { ...result, reviewedAgainst: await revisionAfter() },
	};
	if (failure) return { ok: false, message: failure, record: next };
	return { ok: true, record: next };
}

export async function undoFix({
	record,
	opId,
	removeOp,
	revisionAfter,
}: {
	record: FeedbackRecord;
	opId: string;
	removeOp: Writers['removeOp'];
	revisionAfter: () => Promise<string>;
}): Promise<{ ok: true; record: FeedbackRecord } | { ok: false; message: string }> {
	const entry = record.applied?.find((x) => x.opId === opId && !x.undoneAt);
	if (!entry) return { ok: false, message: 'That fix is not applied.' };
	const tombstone = await removeOp(entry.opId);
	return {
		ok: true,
		record: {
			...record,
			applied: (record.applied ?? []).map((x) =>
				x.opId === entry.opId ? { ...x, undoneAt: tombstone.createdAt, tombstoneId: tombstone.id } : x,
			),
			...(record.result ? { result: { ...record.result, reviewedAgainst: await revisionAfter() } } : {}),
		},
	};
}
