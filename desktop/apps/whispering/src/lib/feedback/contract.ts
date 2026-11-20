/**
 * VoiceFlow phase F: what the fix engine must answer, and the parser that
 * enforces it. The model's reply is untrusted: anything that doesn't match
 * is refused here, before a person sees it, and every proposal is then
 * checked again against the word lists (`validate.ts`).
 *
 * The contract (one JSON object, nothing else):
 *
 * ```json
 * {
 *   "diagnosis": "mishearing" | "tidy-up-rewrite" | "style-mismatch" | "delivery-privacy" | "uncertain",
 *   "explanation": "one plain sentence",
 *   "corrected_text": "the dictation as it should have been",
 *   "proposals": [
 *     { "type": "spelling", "from": ["Zebbo"], "to": "Zibbo", "scope": "app" | "everywhere",
 *       "expression_delta": null, "style_id": null, "reason": "one line" },
 *     { "type": "style_adjust", "from": [], "to": null, "scope": null,
 *       "expression_delta": -1, "style_id": null | "<style id>" | "raw", "reason": "one line" },
 *     { "type": "none", "from": [], "to": null, "scope": null,
 *       "expression_delta": null, "style_id": null, "reason": "why no lasting fix" }
 *   ]
 * }
 * ```
 *
 * Every field is present on every proposal (OpenAI strict structured output
 * needs that); the ones a type doesn't use are null or empty.
 */

export const DIAGNOSES = [
	'mishearing',
	'tidy-up-rewrite',
	'style-mismatch',
	'delivery-privacy',
	'uncertain',
] as const;
export type Diagnosis = (typeof DIAGNOSES)[number];

export const PROPOSAL_TYPES = ['spelling', 'style_adjust', 'none'] as const;

/** JSON Schema for OpenAI's strict structured output (and the Anthropic tool). */
export const FIX_RESPONSE_SCHEMA = {
	type: 'object',
	additionalProperties: false,
	required: ['diagnosis', 'explanation', 'corrected_text', 'proposals'],
	properties: {
		diagnosis: { type: 'string', enum: [...DIAGNOSES] },
		explanation: { type: 'string' },
		corrected_text: { type: 'string' },
		proposals: {
			type: 'array',
			items: {
				type: 'object',
				additionalProperties: false,
				required: ['type', 'from', 'to', 'scope', 'expression_delta', 'style_id', 'reason'],
				properties: {
					type: { type: 'string', enum: [...PROPOSAL_TYPES] },
					from: { type: 'array', items: { type: 'string' } },
					to: { type: ['string', 'null'] },
					scope: { type: ['string', 'null'], enum: ['app', 'everywhere', null] },
					expression_delta: { type: ['integer', 'null'] },
					style_id: { type: ['string', 'null'] },
					reason: { type: 'string' },
				},
			},
		},
	},
} as const;

export type SpellingProposal = {
	type: 'spelling';
	from: string[];
	to: string;
	/** What the model suggests; the person chooses (app is the default). */
	scope: 'app' | 'everywhere';
	reason: string;
};

export type StyleProposal = {
	type: 'style_adjust';
	/** -4..4, not 0; null: leave the expression. */
	expressionDelta: number | null;
	/** A style id from the request's list, `raw`, or null: keep the style. */
	styleId: string | null;
	reason: string;
};

export type ModelProposal = SpellingProposal | StyleProposal;

export type FixResponse = {
	diagnosis: Diagnosis;
	explanation: string;
	correctedText: string;
	proposals: ModelProposal[];
	/** The model's "no durable fix" reason, when it gave one. */
	noFixReason: string | null;
	/** Proposals refused by the parser (malformed), for the plain-language note. */
	dropped: number;
};

export type ParseResult = { ok: true; value: FixResponse } | { ok: false; message: string };

// 0.9.13: the pack feedback contract (pack/feedback/README.md): at most three.
const MAX_PROPOSALS = 3;
/** More proposals than this and the whole answer is refused. */
const MAX_LISTED = 12;
const MAX_FORMS = 5;
const MAX_REASON = 240;
const MAX_EXPLANATION = 400;
const MAX_WORD = 60;

/** Control and invisible formatting characters (newlines aside). */
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\p{Cf}\u2028\u2029]/u;

function oneLine(value: unknown, max: number): string | null {
	if (typeof value !== 'string') return null;
	const line = value.replace(/\s+/g, ' ').trim();
	if (CONTROL.test(line)) return null;
	return line.length > max ? `${line.slice(0, max - 1)}…` : line;
}

/** A heard form or spelling the word lists accept (see `wordProblem`). */
function word(value: unknown): string | null {
	if (typeof value !== 'string') return null;
	// Checked before spaces are tidied: a line break is refused, not joined.
	if (/[<>\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(value)) return null;
	const w = value.trim().replace(/\s+/g, ' ');
	if (!w || w.length > MAX_WORD) return null;
	return w;
}

/**
 * The longest corrected text accepted: a fix rewrites a dictation, it never
 * writes a new one.
 */
export function maxCorrectedLength(delivered: string): number {
	return Math.max(400, delivered.length * 2 + 200);
}

/**
 * Parses and checks the model's reply. A reply that isn't the contract is
 * refused as a whole; a single malformed proposal is dropped and counted.
 * `delivery-privacy` never carries proposals: paste and privacy complaints
 * are diagnostics only.
 */
export function parseFixResponse(raw: string, delivered: string): ParseResult {
	let value: unknown;
	try {
		value = JSON.parse(raw.trim());
	} catch {
		return { ok: false, message: 'The answer was not valid JSON.' };
	}
	if (!value || typeof value !== 'object' || Array.isArray(value))
		return { ok: false, message: 'The answer was not a JSON object.' };
	const v = value as Record<string, unknown>;
	if (typeof v.diagnosis !== 'string' || !(DIAGNOSES as readonly string[]).includes(v.diagnosis))
		return { ok: false, message: 'The answer had no diagnosis VoiceFlow knows.' };
	const diagnosis = v.diagnosis as Diagnosis;
	const explanation = oneLine(v.explanation ?? '', MAX_EXPLANATION);
	if (explanation === null)
		return { ok: false, message: 'The answer’s explanation had characters VoiceFlow refuses.' };
	if (typeof v.corrected_text !== 'string')
		return { ok: false, message: 'The answer had no corrected text.' };
	const correctedText = v.corrected_text.replace(/\r\n?/g, '\n');
	if (CONTROL.test(correctedText))
		return { ok: false, message: 'The corrected text had hidden characters, so it wasn’t used.' };
	if (correctedText.length > maxCorrectedLength(delivered))
		return {
			ok: false,
			message: 'The corrected text came back much longer than your dictation, so it wasn’t used.',
		};
	if (!Array.isArray(v.proposals))
		return { ok: false, message: 'The answer had no list of fixes.' };
	if (v.proposals.length > MAX_LISTED)
		return { ok: false, message: 'The answer had far too many fixes, so none were used.' };

	const proposals: ModelProposal[] = [];
	let dropped = 0;
	let noFixReason: string | null = null;
	for (const item of v.proposals) {
		const p = parseProposal(item);
		if (p === 'none') {
			const reason =
				item && typeof item === 'object'
					? oneLine((item as Record<string, unknown>).reason, MAX_REASON)
					: null;
			noFixReason ??= reason || null;
			continue;
		}
		if (!p) {
			dropped += 1;
			continue;
		}
		if (proposals.length >= MAX_PROPOSALS) {
			dropped += 1;
			continue;
		}
		proposals.push(p);
	}
	// Paste and privacy complaints are diagnostics only (PLAN-v2, phase F).
	if (diagnosis === 'delivery-privacy' || diagnosis === 'uncertain') {
		dropped += proposals.length;
		proposals.length = 0;
	}
	return {
		ok: true,
		value: {
			diagnosis,
			explanation,
			correctedText: correctedText.trim() ? correctedText : delivered,
			proposals,
			noFixReason,
			dropped,
		},
	};
}

function parseProposal(item: unknown): ModelProposal | 'none' | null {
	if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
	const p = item as Record<string, unknown>;
	const reason = oneLine(p.reason ?? '', MAX_REASON);
	if (reason === null) return null;
	if (p.type === 'none') return 'none';
	if (p.type === 'spelling') {
		if (!Array.isArray(p.from) || p.from.length === 0 || p.from.length > MAX_FORMS) return null;
		const from: string[] = [];
		for (const f of p.from) {
			const w = word(f);
			if (!w) return null;
			if (!from.some((g) => g.toLocaleLowerCase() === w.toLocaleLowerCase())) from.push(w);
		}
		const to = word(p.to);
		if (!to) return null;
		const scope = p.scope === 'everywhere' ? 'everywhere' : 'app';
		return { type: 'spelling', from, to, scope, reason };
	}
	if (p.type === 'style_adjust') {
		let expressionDelta: number | null = null;
		if (p.expression_delta !== null && p.expression_delta !== undefined) {
			if (
				typeof p.expression_delta !== 'number' ||
				!Number.isInteger(p.expression_delta) ||
				p.expression_delta < -4 ||
				p.expression_delta > 4
			)
				return null;
			expressionDelta = p.expression_delta === 0 ? null : p.expression_delta;
		}
		let styleId: string | null = null;
		if (p.style_id !== null && p.style_id !== undefined) {
			if (typeof p.style_id !== 'string' || !/^[a-z0-9][a-z0-9._-]{0,99}$/.test(p.style_id))
				return null;
			styleId = p.style_id;
		}
		if (expressionDelta === null && styleId === null) return null;
		return { type: 'style_adjust', expressionDelta, styleId, reason };
	}
	return null;
}
