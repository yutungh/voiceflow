/**
 * VoiceFlow 0.9.13: this app's feedback fixer keeps the shared contract
 * (`pack/feedback/README.md`, MIT), so the Android app can read the same
 * answers and make the same fixes. The vectors are in
 * `pack/vectors/feedback.json`; the answer's shape is
 * `pack/schema/voiceflow-feedback.v1.schema.json`.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ProfileSnapshot } from '$lib/profile/types';
import { FIX_RESPONSE_SCHEMA, parseFixResponse, type ModelProposal } from './contract';
import type { DictationEvidence } from './evidence';
import { reviewProposals, type StyleFix } from './validate';

const PACK = join(import.meta.dir, '../../../../../../pack');
const vectors = JSON.parse(readFileSync(join(PACK, 'vectors/feedback.json'), 'utf8')) as {
	answers: Array<{
		name: string;
		delivered: string;
		answer?: unknown;
		answerText?: string;
		expect: {
			ok: boolean;
			diagnosis?: string;
			correctedText?: string;
			proposals?: unknown[];
			noFixReason?: string | null;
			dropped?: number;
		};
	}>;
	styleFixes: Array<{
		name: string;
		proposal: { expression_delta: number | null; style_id: string | null };
		dictation: { app: string | null; style: string; expression: number | null; styleExpression: number | null };
		expect: { edit: { app: string; style?: string; expression?: number } | null };
	}>;
};
const schema = JSON.parse(readFileSync(join(PACK, 'schema/voiceflow-feedback.v1.schema.json'), 'utf8'));

/** A checked proposal as the pack writes it. */
function asPack(p: ModelProposal) {
	return p.type === 'spelling'
		? { type: p.type, from: p.from, to: p.to, scope: p.scope }
		: { type: p.type, expression_delta: p.expressionDelta, style_id: p.styleId };
}

describe('answers (pack/vectors/feedback.json)', () => {
	test('there are enough of them', () => {
		expect(vectors.answers.length).toBeGreaterThanOrEqual(10);
		expect(vectors.styleFixes.length).toBeGreaterThanOrEqual(8);
	});
	for (const v of vectors.answers) {
		test(v.name, () => {
			const raw = v.answerText ?? JSON.stringify(v.answer);
			const r = parseFixResponse(raw, v.delivered);
			expect(r.ok).toBe(v.expect.ok);
			if (!r.ok || !v.expect.ok) return;
			expect(r.value.diagnosis).toBe(v.expect.diagnosis as never);
			expect(r.value.correctedText).toBe(v.expect.correctedText!);
			expect(r.value.proposals.map(asPack)).toEqual(v.expect.proposals as never);
			expect(r.value.noFixReason).toBe(v.expect.noFixReason ?? null);
			expect(r.value.dropped).toBe(v.expect.dropped!);
		});
	}
});

describe('style fixes (pack/vectors/feedback.json)', () => {
	for (const v of vectors.styleFixes) {
		test(v.name, () => {
			const d = v.dictation;
			const snapshot = {
				id: 's',
				user: 'sam',
				terms: [],
				corrections: [],
				styles: ['work', 'casual'].map((id) => ({
					id,
					label: id === 'work' ? 'Work' : 'Casual',
					prompt: 'p',
					layer: 'personal',
					...(id === d.style && d.styleExpression !== null ? { expression: d.styleExpression } : {}),
				})),
				defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {} },
				privacy: {},
				apps: [],
				issues: [],
			} as ProfileSnapshot;
			const evidence: DictationEvidence = {
				schema: 'voiceflow-dictation-evidence',
				version: 1,
				recordingId: 'r1',
				updatedAt: '2026-11-19T10:00:00Z',
				appName: 'Kite',
				...(d.app ? { appId: d.app } : {}),
				rawText: 'Milo will bring the deck.',
				styleId: d.style === 'raw' ? 'raw' : `profile:${d.style}`,
				expression: d.expression,
			};
			const proposal: ModelProposal = {
				type: 'style_adjust',
				expressionDelta: v.proposal.expression_delta,
				styleId: v.proposal.style_id,
				reason: 'r',
			};
			const { fixes } = reviewProposals([proposal], { evidence, snapshot, active: [], history: [] });
			const fix = fixes[0] as StyleFix | undefined;
			// What apply.ts writes as the app-style edit.
			const edit = fix
				? {
						app: fix.app,
						...(fix.style ? { style: fix.style.id } : {}),
						...(fix.expression ? { expression: fix.expression.to } : {}),
					}
				: null;
			expect(edit).toEqual(v.expect.edit as never);
		});
	}
});

test("the answer's shape is the pack schema, field for field", () => {
	expect(FIX_RESPONSE_SCHEMA.required).toEqual(schema.required);
	expect([...FIX_RESPONSE_SCHEMA.properties.diagnosis.enum]).toEqual(schema.properties.diagnosis.enum);
	expect(FIX_RESPONSE_SCHEMA.properties.proposals.items.required).toEqual(schema.properties.proposals.items.required);
	expect([...FIX_RESPONSE_SCHEMA.properties.proposals.items.properties.type.enum]).toEqual(
		schema.properties.proposals.items.properties.type.enum,
	);
});
