/**
 * VoiceFlow 0.9.18: the Feedback window's result shows two separate jobs.
 * 1. Fix this text (copy or paste the corrected text), 2. Save changes for
 * next time (lasting edits, each with Undo). These tests check the words,
 * the honest empty state, the receipt built from what a Save really wrote
 * (real applyFixes / undoFix with in-memory writers), and that the window's
 * markup has both sections and no longer uses the old labels.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseOp, type ProfileOp } from '../profile/ops';
import type { ProfileSnapshot } from '$lib/profile/types';
import { applyFixes, undoFix, type Writers } from './apply';
import { demoClient, demoRecord } from './demo';
import { runFix } from './engine';
import type { DictationEvidence } from './evidence';
import type { FeedbackRecord } from './log';
import {
	ALL_SAVED,
	COPY_LABEL,
	EMPTY_NEXT_TIME,
	NOW_LINK,
	RESULT_HEADING,
	SECTION_NEXT,
	SECTION_NOW,
	changeLine,
	newlySaved,
	nextTimeLink,
	nextTimeState,
	pasteHelp,
	pasteLabel,
	saveLabel,
	savedReceipt,
	undoneMessage,
} from './result-view';
import type { HistoryText } from './validate';

const read = (path: string) => readFileSync(join(import.meta.dir, path), 'utf8').replace(/\r\n/g, '\n');
const SVELTE = read('../vf/FeedbackFix.svelte');
const WINDOW = read('../../routes/feedback-capture/+page.svelte');

// ---------------------------------------------------------------------------
// fixtures (invented): one spelling and one style change, from the real engine
// ---------------------------------------------------------------------------

const SNAPSHOT: ProfileSnapshot = {
	id: 'snap-1',
	user: 'tester',
	terms: [],
	corrections: [],
	styles: [{ id: 'work', label: 'Work', prompt: 'p', layer: 'personal', expression: 2 }],
	defaults: { promptCharsEffective: 1000, promptTermsEffective: 50, fromLayer: {} },
	privacy: {},
	apps: [{ id: 'kite.exe', style: 'work' }],
	issues: [],
};

function evidence(): DictationEvidence {
	return {
		schema: 'voiceflow-dictation-evidence',
		version: 1,
		recordingId: 'rec1',
		updatedAt: '2026-11-18T00:00:00Z',
		appName: 'Kite',
		appId: 'kite.exe',
		rawText: 'ask zebbo to file the note',
		tidyOutput: 'Ask Zebbo to file the note.',
		deliveredText: 'Ask Zebbo to file the note.',
		styleId: 'profile:work',
		styleName: 'Work',
		expression: 2,
		outcome: 'pasted',
		pastedInto: 'Kite',
	};
}

const HISTORY: HistoryText[] = [{ recordingId: 'h1', appId: 'kite.exe', text: 'Zebbo said the build is green' }];
const REVIEW = { snapshot: SNAPSHOT, active: [], history: HISTORY, revision: 'rev-1' };
const SPELLING = {
	type: 'spelling',
	from: ['Zebbo'],
	to: 'Zibbo',
	scope: 'app',
	expression_delta: null,
	style_id: null,
	reason: 'Speech-to-text keeps hearing Zibbo as Zebbo.',
	spelling_ref: null,
};

async function record(proposals: 'both' | 'none' = 'both'): Promise<FeedbackRecord> {
	const issues =
		proposals === 'none'
			? [{ layer: 'uncertain', summary: 'Not sure.', proposals: [] }]
			: [
					{ layer: 'transcriber', summary: 'Misheard.', proposals: [SPELLING] },
					{
						layer: 'expression',
						summary: 'Too lively.',
						proposals: [{ ...SPELLING, type: 'style_adjust', from: [], to: null, scope: null, expression_delta: -1 }],
					},
				];
	const outcome = await runFix({
		evidence: evidence(),
		feedback: 'you wrote Zebbo',
		review: REVIEW,
		provider: { apiKey: 'k', model: 'm' },
		complete: async () => ({
			ok: true,
			text: JSON.stringify({ explanation: 'Zibbo was heard as Zebbo.', corrected_text: 'Ask Zibbo to file the note.', issues }),
		}),
	});
	if (!outcome.ok) throw new Error(outcome.message);
	return {
		schema: 'voiceflow-feedback-draft',
		version: 2,
		id: 'fb-00000001',
		device: 'dev1',
		createdAt: '2026-11-18T00:00:00Z',
		recordingId: 'rec1',
		rawText: '',
		feedback: 'you wrote Zebbo',
		result: outcome.result,
	};
}

/** In-memory writers; `failAfter` makes every save after that many fail. */
function memoryWriters(failAfter = Infinity) {
	const ops: ProfileOp[] = [];
	let k = 0;
	const base = () => {
		k += 1;
		return {
			schema: 'voiceflow-op' as const,
			version: 1 as const,
			id: `dev1-op${k}`,
			device: 'dev1',
			createdAt: `2026-11-18T00:00:0${k}.000Z`,
		};
	};
	const save = async (body: Record<string, unknown>) => {
		if (ops.length >= failAfter) return { ok: false as const, message: 'The disk is full.' };
		const op = parseOp({ ...base(), ...body });
		if (!op) return { ok: false as const, message: 'bad' };
		ops.push(op);
		return { ok: true as const, op };
	};
	const writers: Writers = {
		saveCorrection: (c) => save({ type: 'correction', ...c }),
		saveAppStyle: (s) => save({ type: 'app-style', ...s }),
		saveWord: (w) => save({ type: 'term', term: w }),
		async removeOp(target) {
			const op = parseOp({ ...base(), type: 'tombstone', target })!;
			ops.push(op);
			return op;
		},
	};
	return { ops, writers };
}

// ---------------------------------------------------------------------------

describe('the two sections', () => {
	test('both jobs are named, numbered, and say what they change', () => {
		expect(SECTION_NOW.title).toBe('1 · Fix this text');
		expect(SECTION_NOW.scope).toContain('For this dictation only');
		expect(SECTION_NEXT.title).toBe('2 · Save changes for next time');
		expect(SECTION_NEXT.scope).toBe('Saved changes apply from your next dictation. The text above stays as it is.');
		expect(RESULT_HEADING).toBe('Feedback results');
	});

	test('the window renders both sections, the summary that jumps to them, and the folded why', () => {
		expect(SVELTE).toContain('id="fbx-now"');
		expect(SVELTE).toContain('id="fbx-next"');
		expect(SVELTE).toContain("jump('fbx-now')");
		expect(SVELTE).toContain("jump('fbx-next')");
		expect(SVELTE).toContain('{SECTION_NOW.title}');
		expect(SVELTE).toContain('{SECTION_NEXT.title}');
		expect(SVELTE).toContain('{NOW_LINK}');
		expect(SVELTE).toContain('{nextTimeLink(next)}');
		// Section 1 comes before section 2, and the evidence after both, folded.
		const now = SVELTE.indexOf('id="fbx-now"');
		const later = SVELTE.indexOf('id="fbx-next"');
		const why = SVELTE.indexOf('<summary>{WHY_TITLE}</summary>');
		expect(now).toBeGreaterThan(-1);
		expect(later).toBeGreaterThan(now);
		expect(why).toBeGreaterThan(later);
		expect(SVELTE.slice(SVELTE.lastIndexOf('<details', why), why)).not.toContain(' open');
		// The steps and the problem chips are inside the fold, not before section 2.
		expect(SVELTE.indexOf('STAGE_NAMES[o.stage]')).toBeGreaterThan(why);
		expect(SVELTE.indexOf('LAYER_LABELS[issue.layer]')).toBeGreaterThan(why);
	});

	test('the old, unclear labels are gone', () => {
		for (const old of ['Here is the fix', 'Apply selected', 'Paste into {', 'Saved to your own word list'])
			expect(SVELTE).not.toContain(old);
	});

	test('the result hides the compose note and folds the original text', () => {
		expect(WINDOW).toContain('onStep={(s) => (showingResult = s === \'result\')}');
		expect(WINDOW).toContain('{ORIGINAL_TEXT}');
		expect(WINDOW).toMatch(/\{#if showingResult\}[\s\S]*?\{:else if payload\?\.transcriptProblem\}/);
	});

	test('the preview data loads only outside the app, in a dev build', () => {
		expect(WINDOW).toContain("!desktop && import.meta.env.DEV ? new URLSearchParams(location.search).get('demo')");
	});
});

describe('buttons say exactly what they do', () => {
	test('Copy and Paste name the text and the app it goes to', () => {
		expect(COPY_LABEL).toBe('Copy corrected text');
		expect(pasteLabel('Kite')).toBe('Paste at cursor in Kite');
		expect(pasteLabel(null)).toBe('Paste at cursor in the app');
		expect(pasteLabel('  ')).toBe('Paste at cursor in the app');
		expect(SVELTE).toContain('{pasteLabel(appName)}');
		expect(SVELTE).toContain('{COPY_LABEL}');
	});

	test('Paste says it adds at the cursor and does not replace the earlier text', () => {
		const help = pasteHelp('Kite');
		expect(help).toContain('Returns to Kite and pastes at its cursor.');
		expect(help).toContain('won’t find or replace your earlier dictation');
		expect(help).toContain('If it can’t paste, it copies instead.');
		// Shown whenever Paste is, tied to the button.
		expect(SVELTE).toContain('aria-describedby="fbx-paste-help"');
		expect(SVELTE).toContain('id="fbx-paste-help"');
	});

	test('Save names what it saves and how many', () => {
		expect(saveLabel(0)).toBe('Save selected changes');
		expect(saveLabel(2)).toBe('Save selected changes (2)');
	});
});

describe('section 2: honest states', () => {
	test('no change suggested: says so plainly, in the summary and the section', async () => {
		const r = await record('none');
		expect(r.result!.fixes).toHaveLength(0);
		const state = nextTimeState(r.result!.fixes, r.applied);
		expect(state).toEqual({ kind: 'none' });
		expect(nextTimeLink(state)).toBe('2 · Next time: nothing to change');
		expect(EMPTY_NEXT_TIME).toBe('Nothing to change for next time.');
		expect(SVELTE).toContain("{#if next.kind === 'none'}");
		expect(SVELTE).toContain('{EMPTY_NEXT_TIME}');
	});

	test('counts what is left to save, what is saved, and all saved', async () => {
		const r = await record();
		const [a, b] = r.result!.fixes;
		expect(nextTimeLink(nextTimeState(r.result!.fixes))).toBe('2 · Next time: 2 changes to save');
		const one = [{ key: a!.key, opId: 'x', appliedAt: 't' }];
		expect(nextTimeLink(nextTimeState(r.result!.fixes, one))).toBe('2 · Next time: 1 to save, 1 saved');
		const both = [...one, { key: b!.key, opId: 'y', appliedAt: 't' }];
		expect(nextTimeState(r.result!.fixes, both)).toEqual({ kind: 'all-saved', saved: 2 });
		expect(nextTimeLink(nextTimeState(r.result!.fixes, both))).toBe('2 · Next time: all saved');
		// An undone change is to save again.
		const undone = [...one, { key: b!.key, opId: 'y', appliedAt: 't', undoneAt: 'u' }];
		expect(nextTimeState(r.result!.fixes, undone)).toEqual({ kind: 'pending', toSave: 1, saved: 1 });
		expect(ALL_SAVED).toContain('saved for next time');
		expect(NOW_LINK).toBe('1 · Corrected text');
	});
});

describe('saving and undoing (real apply and undo)', () => {
	test('the receipt names each change saved, where it applies, and how to undo it', async () => {
		const { ops, writers } = memoryWriters();
		const before = await record();
		const out = await applyFixes({
			record: before,
			selections: before.result!.fixes.map((f) => ({ key: f.key, ...(f.kind === 'spelling' ? { scope: 'everywhere' as const } : {}) })),
			evidence: evidence(),
			review: REVIEW,
			revisionAfter: async () => 'rev-2',
			writers,
		});
		expect(out.ok).toBe(true);
		expect(ops).toHaveLength(2);
		const saved = newlySaved(before, out.record);
		expect(saved).toHaveLength(2);
		const receipt = savedReceipt(saved)!;
		expect(receipt.head).toBe('Saved 2 changes for next time:');
		expect(receipt.lines[0]).toBe('Always write “Zibbo” for “Zebbo” (in every app)');
		expect(receipt.lines[1]).toMatch(/^In Kite, make tidy-up more reserved/);
		expect(receipt.tail).toContain('press Undo next to it');
		expect(nextTimeState(out.record.result!.fixes, out.record.applied).kind).toBe('all-saved');
	});

	test('one change: a one-line receipt in the singular', async () => {
		const { writers } = memoryWriters();
		const before = await record();
		const spelling = before.result!.fixes.find((f) => f.kind === 'spelling')!;
		const out = await applyFixes({
			record: before,
			selections: [{ key: spelling.key, scope: 'app' }],
			evidence: evidence(),
			review: REVIEW,
			revisionAfter: async () => 'rev-2',
			writers,
		});
		const receipt = savedReceipt(newlySaved(before, out.record))!;
		expect(receipt.head).toBe('Saved for next time:');
		expect(receipt.lines).toEqual(['Always write “Zibbo” for “Zebbo” (in Kite only)']);
		expect(receipt.tail).toBe('It applies from your next dictation. To take it back, press Undo next to it.');
	});

	test('a Save that fails part-way still names what it did write', async () => {
		const { ops, writers } = memoryWriters(1);
		const before = await record();
		const out = await applyFixes({
			record: before,
			selections: before.result!.fixes.map((f) => ({ key: f.key })),
			evidence: evidence(),
			review: REVIEW,
			revisionAfter: async () => 'rev-2',
			writers,
		});
		expect(out.ok).toBe(false);
		expect(ops).toHaveLength(1);
		const saved = newlySaved(before, out.record);
		expect(saved.map(changeLine)).toEqual(['Always write “Zibbo” for “Zebbo” (in Kite only)']);
		expect(nextTimeState(out.record.result!.fixes, out.record.applied)).toEqual({ kind: 'pending', toSave: 1, saved: 1 });
	});

	test('nothing new saved: no receipt', async () => {
		const r = await record();
		expect(newlySaved(r, r)).toEqual([]);
		expect(savedReceipt([])).toBeNull();
	});

	test('Undo still writes a tombstone, and says what was taken back', async () => {
		const { ops, writers } = memoryWriters();
		const before = await record();
		const applied = await applyFixes({
			record: before,
			selections: before.result!.fixes.map((f) => ({ key: f.key })),
			evidence: evidence(),
			review: REVIEW,
			revisionAfter: async () => 'rev-2',
			writers,
		});
		const entry = applied.record.applied![0]!;
		const undone = await undoFix({ record: applied.record, opId: entry.opId, removeOp: writers.removeOp, revisionAfter: async () => 'rev-3' });
		expect(undone.ok).toBe(true);
		expect(ops.at(-1)).toMatchObject({ type: 'tombstone', target: entry.opId });
		if (!undone.ok) return;
		const fix = undone.record.result!.fixes.find((f) => f.key === entry.key)!;
		expect(undoneMessage({ fix, scope: entry.scope })).toBe(
			'Undone: Always write “Zibbo” for “Zebbo” (in Kite only). Your next dictation won’t use it.',
		);
		expect(nextTimeState(undone.record.result!.fixes, undone.record.applied)).toEqual({ kind: 'pending', toSave: 1, saved: 1 });
		// The window uses the engine's own note when there is one, else this line.
		expect(SVELTE).toContain('reply.message ?? undoneMessage(');
	});

	test('turning a spelling back on says so', () => {
		const fix = { kind: 'unspell' as const, key: 'f1', issue: 0, reason: 'r', target: 'op', heard: 'zebbo', written: 'Zebra', label: 'Turn off the spelling “zebbo” → “Zebra”' };
		expect(undoneMessage({ fix })).toBe('Undone. The spelling “zebbo” → “Zebra” is back on.');
	});
});

describe('the preview client (browser preview only)', () => {
	test('apply then undo moves a change between the two states', async () => {
		const client = demoClient('both');
		const start = demoRecord('both');
		const key = start.result!.fixes[0]!.key;
		const applied = await client.apply({ recordId: start.id, selections: [{ key, scope: 'app' }] });
		expect(applied.ok).toBe(true);
		if (!applied.ok) return;
		expect(newlySaved(start, applied.record).map(changeLine)).toEqual(['Always write “Zibbo” for “Zebbo” (in Telegram only)']);
		const opId = applied.record.applied![0]!.opId;
		const undone = await client.undo({ recordId: start.id, opId });
		expect(undone.ok && nextTimeState(undone.record.result!.fixes, undone.record.applied).kind).toBe('pending');
		expect(demoRecord('none').result!.fixes).toHaveLength(0);
	});
});
