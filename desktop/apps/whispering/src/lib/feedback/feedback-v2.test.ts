/**
 * VoiceFlow 0.9.16: feedback v2 (phase 1 of the feedback redesign): several
 * problems per feedback, stage observations, the word ladder, turning a
 * spelling off, what it should have said, and the tidy-up kept per dictation.
 * Invented words only.
 */
import { describe, expect, test } from 'bun:test';
import { activeCorrections, parseOp, removedIds, type CorrectionOp, type ProfileOp } from '../profile/ops';
import type { ProfileSnapshot, ProfileTerm } from '$lib/profile/types';
import { applyFixes, undoFix, wordEditOf, type Writers } from './apply';
import { FIX_RESPONSE_SCHEMA_V2, nullableAsAnyOf } from './contract';
import { issuesOf, runFix, type FixResult } from './engine';
import { mergeEvidence, parseEvidence, spellingRefs, tidyConfigOf, type DictationEvidence } from './evidence';
import { parseFeedbackRecord, type FeedbackRecord } from './log';
import { buildFixRequestV2 } from './prompt';
import { MAX_WORDS, observe } from './stage-diff';
import { checkSelection, reviewProposals, type ReviewContext, type UnspellFix, type WordFix } from './validate';

const term = (text: string, priority: number, extra: Partial<ProfileTerm> = {}): ProfileTerm => ({
	id: text.toLowerCase(),
	text,
	heardAs: [],
	priority,
	apps: ['*'],
	layer: 'personal',
	...extra,
});

const FILLER = ['Abrel', 'Bexon', 'Corvia', 'Dunmar', 'Elvaro', 'Fintra', 'Gorvel', 'Halvik', 'Isoral', 'Jentrix', 'Kalvon', 'Lorvane', 'Mirtel', 'Norvix', 'Ostrel', 'Pellum', 'Quarvin', 'Rastol', 'Sovane', 'Tirvel'];

function snapshot(over: Partial<ProfileSnapshot> = {}): ProfileSnapshot {
	return {
		id: 'snap-1',
		user: 'tester',
		terms: [...FILLER.map((t, i) => term(t, 90 - i)), term('Zibbo', 40, { heardAs: ['Zebbo'] })],
		corrections: [
			{ id: 'dev1-bad', from: ['Castrel'], to: 'Kastrel', scope: { apps: ['*'] }, layer: 'edit' },
			{ id: 'gen-1', from: ['Morro'], to: 'Morrow', scope: { apps: ['*'] }, layer: 'generated' },
		],
		styles: [{ id: 'work', label: 'Work', prompt: 'p', layer: 'personal', expression: 2 }],
		defaults: { promptCharsEffective: 2000, promptTermsEffective: 50, fromLayer: {} },
		privacy: {},
		apps: [],
		issues: [],
		...over,
	};
}

const asOp = (c: ProfileSnapshot['corrections'][number]): CorrectionOp => ({
	schema: 'voiceflow-op',
	version: 1,
	id: c.id,
	device: 'dev1',
	createdAt: '',
	type: 'correction',
	from: c.from,
	to: c.to,
	scope: c.scope,
});

function evidence(over: Partial<DictationEvidence> = {}): DictationEvidence {
	return {
		schema: 'voiceflow-dictation-evidence',
		version: 1,
		recordingId: 'rec1',
		updatedAt: '2026-11-21T00:00:00Z',
		appName: 'Kite',
		appId: 'kite.exe',
		rawText: 'ask zebbo and castrel to bring the deck',
		tidyOutput: 'Ask Zebbo and Castrel to bring the deck!',
		deliveredText: 'Ask Zebbo and Kastrel to bring the deck!',
		corrections: [{ id: 'dev1-bad', heard: 'Castrel', written: 'Kastrel' }],
		styleId: 'profile:work',
		styleName: 'Work',
		expression: 3,
		outcome: 'pasted',
		...over,
	};
}

function ctx(over: Partial<ReviewContext> = {}): ReviewContext {
	const snap = over.snapshot === undefined ? snapshot() : over.snapshot;
	return {
		evidence: evidence(),
		snapshot: snap,
		active: snap ? snap.corrections.map(asOp) : [],
		history: [],
		...over,
	};
}

const P = (type: string, over: Record<string, unknown> = {}) => ({
	type,
	from: [],
	to: null,
	scope: null,
	expression_delta: null,
	style_id: null,
	spelling_ref: null,
	reason: 'r',
	...over,
});

/** Three problems at once: a mishearing, a bad saved spelling, too lively. */
const THREE = {
	explanation: 'Zibbo was misheard, a saved spelling wrote Kastrel, and it was too lively.',
	corrected_text: 'Ask Zibbo and Kestrel to bring the deck.',
	issues: [
		{ layer: 'transcriber', summary: 'Zibbo came out as Zebbo.', proposals: [P('raise_priority', { from: ['zebbo'], to: 'Zibbo' })] },
		{
			layer: 'spelling',
			summary: 'A saved spelling turned Castrel into Kastrel.',
			proposals: [P('disable_spelling', { spelling_ref: 's1' }), P('spelling', { from: ['Castrel'], to: 'Kestrel', scope: 'app' })],
		},
		{ layer: 'expression', summary: 'Too lively for Kite.', proposals: [P('style_adjust', { expression_delta: -1 })] },
	],
};

function memoryWriters() {
	const ops: ProfileOp[] = [];
	let k = 0;
	const base = () => {
		k += 1;
		return { schema: 'voiceflow-op' as const, version: 1 as const, id: `dev1-op${k}`, device: 'dev1', createdAt: `2026-11-21T00:00:${String(k).padStart(2, '0')}.000Z` };
	};
	const writers: Writers = {
		async saveCorrection(c) {
			const op = parseOp({ ...base(), type: 'correction', ...c });
			if (!op) return { ok: false, message: 'bad' };
			ops.push(op);
			return { ok: true, op };
		},
		async saveAppStyle(s) {
			const op = parseOp({ ...base(), type: 'app-style', ...s });
			if (!op) return { ok: false, message: 'bad' };
			ops.push(op);
			return { ok: true, op };
		},
		async saveWord(w) {
			const op = parseOp({ ...base(), type: 'term', term: w });
			if (!op) return { ok: false, message: 'bad' };
			ops.push(op);
			return { ok: true, op };
		},
		async removeOp(target) {
			const op = parseOp({ ...base(), type: 'tombstone', target })!;
			ops.push(op);
			return op;
		},
	};
	return { ops, writers };
}

const REVIEW = () => {
	const c = ctx();
	return { snapshot: c.snapshot, active: c.active, history: [], revision: 'rev-1' };
};

async function reviewed(answer: unknown = THREE, wanted: string | null = null): Promise<FeedbackRecord> {
	const outcome = await runFix({
		evidence: evidence(),
		feedback: 'It wrote Zebbo, it is Zibbo. Kestrel, not Kastrel. And calmer please.',
		wanted,
		review: REVIEW(),
		provider: { apiKey: 'k', model: 'm' },
		complete: async () => ({ ok: true, text: JSON.stringify(answer) }),
	});
	if (!outcome.ok) throw new Error(outcome.message);
	return {
		schema: 'voiceflow-feedback-draft',
		version: 2,
		id: 'fb-00000002',
		device: 'dev1',
		createdAt: '2026-11-21T00:00:00Z',
		recordingId: 'rec1',
		rawText: '',
		feedback: 'f',
		...(wanted ? { wanted } : {}),
		result: outcome.result,
	};
}

describe('several problems in one feedback', () => {
	test('each problem keeps its layer and its own fixes', async () => {
		const record = await reviewed();
		const r = record.result!;
		expect(r.contract).toBe(2);
		expect(r.issues!.map((i) => i.layer)).toEqual(['transcriber', 'spelling', 'expression']);
		const byIssue = [0, 1, 2].map((n) => r.fixes.filter((f) => f.issue === n).map((f) => f.kind));
		expect(byIssue).toEqual([['word'], ['unspell', 'spelling'], ['style']]);
		// The raise: Zibbo (40) needs 72 to pass Tirvel (71, earlier in the alphabet on a tie).
		const word = r.fixes.find((f) => f.kind === 'word') as WordFix;
		expect(word.step).toBe('raise_priority');
		expect(word.adjusted).toBeNull();
		const choice = word.choices[word.defaultScope]!;
		expect(choice.priority).toEqual({ from: 40, to: 72 });
		expect(choice.effect.dropsOut).toEqual(['Tirvel']);
		// The new spelling replaces the one turned off, so it needs that one too.
		const spelling = r.fixes.find((f) => f.kind === 'spelling')!;
		const unspell = r.fixes.find((f) => f.kind === 'unspell') as UnspellFix;
		expect(spelling.kind === 'spelling' && spelling.requires).toEqual([unspell.key]);
		expect(unspell.target).toBe('dev1-bad');
		// Observations went with it and are kept.
		expect(r.observations!.some((o) => o.stage === 'spelling' && o.spelling === 's1')).toBe(true);
	});

	test('apply: the spelling is turned off first, then the rest; undo of the turn-off brings it back', async () => {
		const { ops, writers } = memoryWriters();
		const record = await reviewed();
		const fixes = record.result!.fixes;
		const key = (kind: string) => fixes.find((f) => f.kind === kind)!.key;
		// The replacing spelling alone is refused.
		const alone = checkSelection(fixes, [{ key: key('spelling') }], ctx());
		expect(alone.ok).toBe(false);
		const out = await applyFixes({
			record,
			selections: [{ key: key('spelling') }, { key: key('unspell') }, { key: key('word') }, { key: key('style') }],
			evidence: evidence(),
			review: REVIEW(),
			revisionAfter: async () => 'rev-2',
			writers,
		});
		expect(out.ok).toBe(true);
		expect(ops.map((o) => o.type)).toEqual(['tombstone', 'correction', 'term', 'app-style']);
		expect(ops[0]).toMatchObject({ type: 'tombstone', target: 'dev1-bad' });
		expect(ops[1]).toMatchObject({ from: ['Castrel'], to: 'Kestrel', scope: { apps: ['kite.exe'] } });
		expect(ops[2]).toMatchObject({ type: 'term', term: { text: 'Zibbo', heardAs: ['Zebbo'], priority: 72, apps: ['*'] } });
		expect(ops[3]).toMatchObject({ type: 'app-style', app: 'kite.exe', expression: 2 });
		// Undo the turn-off: a tombstone for the tombstone.
		const turnOff = out.record.applied!.find((a) => a.key === key('unspell'))!;
		const back = await undoFix({ record: out.record, opId: turnOff.opId, removeOp: writers.removeOp, revisionAfter: async () => 'rev-3' });
		expect(back.ok).toBe(true);
		expect(ops.at(-1)).toMatchObject({ type: 'tombstone', target: turnOff.opId });
		const all = [asOp(snapshot().corrections[0]!), ...ops];
		expect(removedIds(all).has('dev1-bad')).toBe(false);
		expect(activeCorrections(all).map((c) => c.id)).toContain('dev1-bad');
	});

	test('what he typed is the corrected text; the model’s stays as a suggestion; a word not in it is refused', async () => {
		const wanted = 'Ask Zibbo and Kestrel to bring the deck.';
		const record = await reviewed(THREE, wanted);
		expect(record.result!.correctedText).toBe(wanted);
		expect(record.result!.suggestedText).toBe(THREE.corrected_text);
		const other = await reviewed(
			{ ...THREE, issues: [{ layer: 'transcriber', summary: 's', proposals: [P('spelling', { from: ['zebbo'], to: 'Zobbo' })] }] },
			wanted,
		);
		expect(other.result!.fixes).toEqual([]);
		expect(other.result!.dropped[0]!.reason).toContain('what you said it should have said');
	});

	test('a style-prompt problem carries no fix in this version; old (v1) results read as one issue', async () => {
		const r = await reviewed({
			explanation: 'x',
			corrected_text: '',
			issues: [{ layer: 'style-prompt', summary: 'It over-cleaned.', proposals: [P('none', { reason: 'Later.' })] }],
		});
		expect(r.result!.fixes).toEqual([]);
		expect(issuesOf(r.result!)).toEqual([{ layer: 'style-prompt', summary: 'It over-cleaned.', noFixReason: 'Later.' }]);
		const v1: FixResult = { diagnosis: 'mishearing', explanation: 'e', correctedText: 'c', fixes: [], dropped: [], noFixReason: 'n', proposals: [], reviewedAgainst: '' };
		expect(issuesOf(v1)).toEqual([{ layer: 'transcriber', summary: 'e', noFixReason: 'n' }]);
	});
});

describe('the evidence a fix needs', () => {
	const one = (layer: string, proposal: unknown, over: Partial<ReviewContext> = {}) => {
		const c = ctx(over);
		return reviewProposals([{ ...(proposal as object), issue: 0 } as never], { ...c, layers: [layer as never] });
	};
	const spelling = (from: string[], to: string) => ({ type: 'spelling', from, to, scope: 'app', reason: 'r' });

	test('a spelling from a word-list file is changed there; one already off is refused', () => {
		const e = evidence({ corrections: [{ id: 'gen-1', heard: 'Morro', written: 'Morrow' }], deliveredText: 'Morrow', rawText: 'morro', tidyOutput: 'Morro' });
		const r = one('spelling', { type: 'disable_spelling', spellingRef: 's1', reason: 'r' }, { evidence: e });
		expect(r.fixes).toEqual([]);
		expect(r.dropped[0]!.reason).toContain('list made from your notes');
		const off = one('spelling', { type: 'disable_spelling', spellingRef: 's1', reason: 'r' }, { active: [] });
		expect(off.dropped[0]!.reason).toContain('already off');
	});

	test('word fixes need word lists, and never list a common word', () => {
		expect(one('transcriber', { type: 'add_word', from: ['zebbo'], to: 'Zabbo', scope: 'app', reason: 'r' }, { snapshot: null }).dropped[0]!.reason).toContain('needs word lists');
		expect(one('transcriber', { type: 'add_word', from: ['zebbo'], to: 'the', scope: 'app', reason: 'r' }).fixes).toEqual([]);
		expect(one('transcriber', { type: 'heard_as', from: ['and'], to: 'Zibbo', reason: 'r' }).fixes).toEqual([]);
	});

	test('a new word offers both scopes; a listed word keeps its own', () => {
		const r = one('transcriber', { type: 'add_word', from: ['deck'], to: 'Dekko', scope: 'everywhere', reason: 'r' });
		const fix = r.fixes[0] as WordFix;
		expect(Object.keys(fix.choices).sort()).toEqual(['app', 'everywhere']);
		expect(fix.defaultScope).toBe('everywhere');
		const listed = one('transcriber', { type: 'add_word', from: ['zebbo'], to: 'Zibbo', scope: 'app', reason: 'r' }).fixes[0] as WordFix;
		expect(listed.step).toBe('raise_priority');
		expect(listed.adjusted).toContain('already in your words');
		expect(Object.keys(listed.choices)).toEqual(['everywhere']);
		expect(wordEditOf(listed.choices.everywhere!)).toEqual({ text: 'Zibbo', heardAs: ['Zebbo'], priority: 72, apps: ['*'] });
	});

	test('a word a saved spelling rewrites is not listed', () => {
		const r = one('transcriber', { type: 'add_word', from: ['castrel'], to: 'Castrel', scope: 'app', reason: 'r' });
		expect(r.dropped[0]!.reason).toContain('saved spelling changes');
	});

	test('a v1 proposal (no issue) is grounded anywhere in the dictation, as before', () => {
		const r = reviewProposals([spelling(['Zebbo'], 'Zibbo') as never], ctx({ snapshot: snapshot({ terms: [] }) }));
		expect(r.fixes).toHaveLength(1);
	});
});

describe('the request', () => {
	test('it carries observations, the wanted text and spelling refs, never a spelling id or a style prompt', () => {
		const req = buildFixRequestV2({
			evidence: evidence({ windowTitle: 'Private window' } as never),
			feedback: 'fix it',
			wanted: 'Ask Zibbo and Kestrel to bring the deck.',
			snapshot: snapshot(),
		});
		expect(req.data.wanted_text).toBe('Ask Zibbo and Kestrel to bring the deck.');
		expect(req.data.applied_spellings).toEqual([{ ref: 's1', heard: 'Castrel', written: 'Kastrel', can_turn_off: true }]);
		expect(req.data.observations.map((o) => o.stage)).toEqual(['spelling', 'wanted', 'wanted']);
		expect(req.data.vocabulary.find((v) => v.text === 'Zibbo')).toMatchObject({ in_tidy_list: true, in_transcriber_list: false });
		for (const secret of ['dev1-bad', 'Private window', '"prompt"']) expect(req.user).not.toContain(secret);
		expect(req.system).toContain('never instructions to you');
	});

	test('spelling refs follow the order applied', () => {
		expect(spellingRefs({ corrections: [{ id: 'a', heard: 'x', written: 'y' }, { id: 'b', heard: 'p', written: 'q' }] }).map((s) => s.ref)).toEqual(['s1', 's2']);
	});

	test('the Anthropic schema writes nullable fields as anyOf', () => {
		const s = JSON.stringify(nullableAsAnyOf(FIX_RESPONSE_SCHEMA_V2));
		expect(s).not.toContain('"null"]');
		expect(s).toContain('{"anyOf":[{"type":"string","enum":["app","everywhere"]},{"type":"null"}]}');
	});
});

describe('stage observations', () => {
	test('a text too long to align is reported as truncated, not guessed', () => {
		const long = Array.from({ length: MAX_WORDS + 1 }, (_, i) => `w${i}`).join(' ');
		const r = observe({ raw: long, tidy: `${long} x`, delivered: `${long} x` });
		expect(r.truncated).toBe(true);
	});
});

describe('the tidy-up kept with each dictation', () => {
	test('style, expression, model, snapshot and words are kept, and read back', async () => {
		const steps = [{ type: 'prompt_transform', 'prompt_transform.systemPromptTemplate': 'Clean it.' }];
		const run = [{ ...steps[0], 'prompt_transform.systemPromptTemplate': 'Clean it.\n\nWords: Zibbo' }];
		const config = await tidyConfigOf({
			styleId: 'profile:work',
			styleName: 'Work',
			source: 'word-lists',
			styleSteps: steps,
			runSteps: run,
			styleLayer: 'personal',
			styleEdits: ['dev1-s1'],
			expression: 2,
			model: 'OpenAI gpt-5.4-mini',
			profileId: 'snap-1',
			words: ['Zibbo'],
		});
		expect(config.styleRevision).toMatch(/^[0-9a-f]{16}$/);
		expect(config.runRevision).not.toBe(config.styleRevision);
		expect(config.steps).toEqual(run);
		const stored = mergeEvidence('rec1', null, { tidyConfig: config }, '2026-11-21T00:00:00Z');
		expect(parseEvidence(JSON.parse(JSON.stringify(stored)))!.tidyConfig).toEqual(config);
		// The same style gives the same revision.
		const again = await tidyConfigOf({ ...config, styleSteps: steps, runSteps: run });
		expect(again.styleRevision).toBe(config.styleRevision);
		// Too large to keep: the hashes stay, the steps don't.
		const huge = await tidyConfigOf({ ...config, styleSteps: steps, runSteps: [{ p: 'x'.repeat(70_000) }] });
		expect(huge.steps).toEqual([]);
		expect(huge.stepsOmitted).toBe(true);
	});

	test('a malformed stored config is ignored, not trusted', () => {
		const e = parseEvidence({ schema: 'voiceflow-dictation-evidence', version: 1, recordingId: 'r', tidyConfig: { styleId: 7 } });
		expect(e!.tidyConfig).toBeUndefined();
	});
});

describe('the feedback log', () => {
	test('what it should have said is kept as text only', () => {
		const base = { schema: 'voiceflow-feedback-draft', version: 2, id: 'aaaaaaaa', device: 'd', createdAt: 'x', recordingId: 'r', feedback: 'f' };
		expect(parseFeedbackRecord({ ...base, wanted: 'Right text.' })!.wanted).toBe('Right text.');
		expect(parseFeedbackRecord({ ...base, wanted: 42 })!.wanted).toBeUndefined();
	});
});
