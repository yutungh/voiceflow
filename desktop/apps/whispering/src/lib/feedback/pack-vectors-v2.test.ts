/**
 * VoiceFlow 0.9.16: the feedback v2 contract (`pack/feedback/README.md`, v2,
 * MIT) as this app runs it. The vectors are `pack/vectors/feedback-v2.json`;
 * the answer's shape is `pack/schema/voiceflow-feedback.v2.schema.json`. The
 * Android app runs the same vectors when it gets feedback v2.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { CorrectionOp } from '../profile/ops';
import type { ProfileSnapshot } from '$lib/profile/types';
import { FIX_RESPONSE_SCHEMA_V2, parseFixResponseV2, type ModelProposal } from './contract';
import { observe, type StageTexts } from './stage-diff';
import { reviewProposals } from './validate';
import { ladderStep, type WordStep } from './word-ladder';

const PACK = join(import.meta.dir, '../../../../../../pack');
type Json = Record<string, unknown>;
const vectors = JSON.parse(readFileSync(join(PACK, 'vectors/feedback-v2.json'), 'utf8')) as {
	answers: Array<{ name: string; delivered: string; answer?: unknown; answerText?: string; expect: Json & { ok: boolean } }>;
	observations: Array<{ name: string; texts: StageTexts; expect: Json }>;
	ladder: {
		lists: Pick<ProfileSnapshot, 'terms' | 'defaults'>;
		cases: Array<{
			name: string;
			asked: WordStep;
			word: string;
			heard: string[];
			app: string | null;
			scope: 'app' | 'everywhere';
			expect: Json & { ok: boolean };
		}>;
	};
	evidence: Array<{
		name: string;
		dictation: {
			raw: string;
			tidy?: string;
			delivered: string;
			wanted?: string;
			app: string;
			spellings?: Array<{ id: string; heard: string; written: string; layer: string }>;
		};
		layer: string;
		proposal: unknown;
		expect: { kept: boolean };
	}>;
};
const schema = JSON.parse(readFileSync(join(PACK, 'schema/voiceflow-feedback.v2.schema.json'), 'utf8'));

/** A kept proposal as the pack writes it. */
function asPack(p: ModelProposal) {
	const { reason: _reason, issue, type, ...rest } = p as ModelProposal & Json;
	if (type === 'style_adjust') return { issue, type, expression_delta: rest.expressionDelta, style_id: rest.styleId };
	if (type === 'disable_spelling') return { issue, type, spelling_ref: rest.spellingRef };
	return { issue, type, ...rest };
}

describe('feedback v2 answers (pack/vectors/feedback-v2.json)', () => {
	test('there are enough of them', () => {
		expect(vectors.answers.length).toBeGreaterThanOrEqual(10);
		expect(vectors.observations.length).toBeGreaterThanOrEqual(6);
		expect(vectors.ladder.cases.length).toBeGreaterThanOrEqual(8);
		expect(vectors.evidence.length).toBeGreaterThanOrEqual(5);
	});
	for (const v of vectors.answers) {
		test(v.name, () => {
			const r = parseFixResponseV2(v.answerText ?? JSON.stringify(v.answer), v.delivered);
			expect(r.ok).toBe(v.expect.ok);
			if (!r.ok || !v.expect.ok) return;
			expect(r.value.correctedText).toBe(v.expect.correctedText as string);
			expect(r.value.issues).toEqual(v.expect.issues as never);
			expect(r.value.proposals.map(asPack)).toEqual(v.expect.proposals as never);
			expect(r.value.dropped).toEqual(v.expect.dropped as never);
			expect(r.value.droppedIssues).toBe(v.expect.droppedIssues as number);
		});
	}
});

describe('stage observations (pack/vectors/feedback-v2.json)', () => {
	for (const v of vectors.observations) {
		test(v.name, () => {
			expect(observe(v.texts)).toEqual(v.expect as never);
		});
	}
});

describe('the word ladder (pack/vectors/feedback-v2.json)', () => {
	for (const v of vectors.ladder.cases) {
		test(v.name, () => {
			const r = ladderStep(vectors.ladder.lists, v.asked, { word: v.word, heard: v.heard, appId: v.app, scope: v.scope });
			expect(r.ok).toBe(v.expect.ok);
			if (!r.ok || !v.expect.ok) return;
			expect({
				ok: true,
				step: r.step,
				entry: { text: r.term.text, heardAs: r.term.heardAs, priority: r.term.priority, apps: r.term.apps },
				replaces: r.replaces?.text ?? null,
				tidy: r.effect.after.tidy,
				transcriber: r.effect.after.transcriber,
				dropsOut: r.effect.dropsOut,
			}).toEqual(v.expect as never);
		});
	}
});

describe('stage evidence (pack/vectors/feedback-v2.json)', () => {
	for (const v of vectors.evidence) {
		test(v.name, () => {
			const d = v.dictation;
			const spellings = d.spellings ?? [];
			const parsed = parseFixResponseV2(
				JSON.stringify({ explanation: 'x', corrected_text: '', issues: [{ layer: v.layer, summary: 's', proposals: [v.proposal] }] }),
				d.delivered,
			);
			if (!parsed.ok) throw new Error(parsed.message);
			const snapshot = {
				id: 's',
				user: 'sam',
				terms: [],
				corrections: spellings.map((s) => ({ id: s.id, from: [s.heard], to: s.written, scope: { apps: ['*'] }, layer: s.layer })),
				styles: [],
				defaults: vectors.ladder.lists.defaults,
				privacy: {},
				apps: [],
				issues: [],
			} as unknown as ProfileSnapshot;
			const active: CorrectionOp[] = spellings.map((s) => ({
				schema: 'voiceflow-op',
				version: 1,
				id: s.id,
				device: 'd',
				createdAt: '',
				type: 'correction',
				from: [s.heard],
				to: s.written,
				scope: { apps: ['*'] },
			}));
			const r = reviewProposals(parsed.value.proposals, {
				evidence: {
					schema: 'voiceflow-dictation-evidence',
					version: 1,
					recordingId: 'r1',
					updatedAt: '',
					appId: d.app,
					appName: 'Kite',
					rawText: d.raw,
					...(d.tidy !== undefined ? { tidyOutput: d.tidy } : {}),
					deliveredText: d.delivered,
					corrections: spellings.map((s) => ({ id: s.id, heard: s.heard, written: s.written })),
				},
				snapshot,
				active,
				history: [],
				wanted: d.wanted ?? null,
				layers: parsed.value.issues.map((i) => i.layer),
			});
			expect(r.fixes.length === 1).toBe(v.expect.kept);
		});
	}
});

test("the v2 answer's shape is the pack schema, field for field", () => {
	const issue = schema.properties.issues.items;
	const proposal = issue.properties.proposals.items;
	expect(FIX_RESPONSE_SCHEMA_V2.required).toEqual(schema.required);
	const ours = FIX_RESPONSE_SCHEMA_V2.properties.issues.items;
	expect(ours.required).toEqual(issue.required);
	expect([...ours.properties.layer.enum]).toEqual(issue.properties.layer.enum);
	expect(ours.properties.proposals.items.required).toEqual(proposal.required);
	expect([...ours.properties.proposals.items.properties.type.enum]).toEqual(proposal.properties.type.enum);
});
