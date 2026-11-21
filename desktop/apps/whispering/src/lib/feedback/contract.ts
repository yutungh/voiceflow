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

/** v2: a form speech-to-text wrote for a word already in the lists (a hint, never a rewrite). */
export type HeardAsProposal = { type: 'heard_as'; from: string[]; to: string; reason: string };
/** v2: a word for the lists (the tidy-up's list, and the transcriber's when it ranks high enough). */
export type AddWordProposal = {
	type: 'add_word';
	from: string[];
	to: string;
	scope: 'app' | 'everywhere';
	reason: string;
};
/** v2: raise a listed word into the transcriber's 20 for this app. */
export type PriorityProposal = { type: 'raise_priority'; from: string[]; to: string; reason: string };
/** v2: turn off a saved spelling that changed this dictation (`s1`… from the request). */
export type DisableSpellingProposal = { type: 'disable_spelling'; spellingRef: string; reason: string };

/** A proposal, and (v2) the index of the issue it belongs to (v1: 0). */
export type ModelProposal = (
	| SpellingProposal
	| StyleProposal
	| HeardAsProposal
	| AddWordProposal
	| PriorityProposal
	| DisableSpellingProposal
) & { issue?: number };

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

// ===========================================================================
// v2 (VoiceFlow 0.9.16): several problems in one answer
// ===========================================================================

/**
 * The layer that failed, as the model judges it from the stage observations
 * (`stage-diff.ts`). The app never decides the layer; it only refuses fixes
 * the layer or the evidence doesn't support.
 */
export const LAYERS = [
	'transcriber',
	'spelling',
	'expression',
	'app-context',
	'style-prompt',
	'delivery-privacy',
	'uncertain',
] as const;
export type Layer = (typeof LAYERS)[number];

export const PROPOSAL_TYPES_V2 = [
	'spelling',
	'heard_as',
	'add_word',
	'raise_priority',
	'disable_spelling',
	'style_adjust',
	'none',
] as const;

/** Which fixes each layer may carry. Style rules (`style-prompt`) arrive in a later version. */
export const LAYER_FIXES: Record<Layer, readonly string[]> = {
	transcriber: ['spelling', 'heard_as', 'add_word', 'raise_priority'],
	spelling: ['disable_spelling', 'spelling'],
	expression: ['style_adjust'],
	'app-context': ['style_adjust'],
	'style-prompt': [],
	'delivery-privacy': [],
	uncertain: [],
};

/** Word fixes share one budget per review. */
export const WORD_FIX_TYPES = ['spelling', 'heard_as', 'add_word', 'raise_priority', 'disable_spelling'] as const;

export const MAX_ISSUES = 4;
/** More issues than this and the whole answer is refused. */
export const MAX_ISSUES_LISTED = 8;
export const MAX_WORD_FIXES = 3;
export const MAX_STYLE_FIXES = 1;
const MAX_SUMMARY = 240;

const PROPOSAL_V2 = {
	type: 'object',
	additionalProperties: false,
	required: ['type', 'from', 'to', 'scope', 'expression_delta', 'style_id', 'spelling_ref', 'reason'],
	properties: {
		type: { type: 'string', enum: [...PROPOSAL_TYPES_V2] },
		from: { type: 'array', items: { type: 'string' } },
		to: { type: ['string', 'null'] },
		scope: { type: ['string', 'null'], enum: ['app', 'everywhere', null] },
		expression_delta: { type: ['integer', 'null'] },
		style_id: { type: ['string', 'null'] },
		spelling_ref: { type: ['string', 'null'] },
		reason: { type: 'string' },
	},
} as const;

/** JSON Schema for v2 (OpenAI strict structured output; Anthropic via `nullableAsAnyOf`). */
export const FIX_RESPONSE_SCHEMA_V2 = {
	type: 'object',
	additionalProperties: false,
	required: ['explanation', 'corrected_text', 'issues'],
	properties: {
		explanation: { type: 'string' },
		corrected_text: { type: 'string' },
		issues: {
			type: 'array',
			items: {
				type: 'object',
				additionalProperties: false,
				required: ['layer', 'summary', 'proposals'],
				properties: {
					layer: { type: 'string', enum: [...LAYERS] },
					summary: { type: 'string' },
					proposals: { type: 'array', items: PROPOSAL_V2 },
				},
			},
		},
	},
} as const;

/**
 * Why a v2 proposal was left out, deterministically:
 * `malformed` (fails its field rules or an unknown type), `layer` (not a fix
 * its issue's layer may carry), `limit` (past the per-review limits: three
 * word fixes, one style change), `issue-limit` (in an issue past the fourth).
 */
export type DropCode = 'malformed' | 'layer' | 'limit' | 'issue-limit';

export type ParsedIssue = {
	layer: Layer;
	summary: string;
	/** The first `none` proposal's reason in this issue. */
	noFixReason: string | null;
};

export type FixResponseV2 = {
	explanation: string;
	correctedText: string;
	issues: ParsedIssue[];
	/** The proposals kept, each with `issue` set, in answer order. */
	proposals: ModelProposal[];
	dropped: DropCode[];
	/** Issues past the fourth, left out with their proposals. */
	droppedIssues: number;
};

export type ParseResultV2 = { ok: true; value: FixResponseV2 } | { ok: false; message: string };

const SPELLING_REF = /^s[1-9][0-9]?$/;

function forms(value: unknown, min: number): string[] | null {
	if (!Array.isArray(value) || value.length < min || value.length > MAX_FORMS) return null;
	const out: string[] = [];
	for (const f of value) {
		const w = word(f);
		if (!w) return null;
		if (!out.some((g) => g.toLocaleLowerCase() === w.toLocaleLowerCase())) out.push(w);
	}
	return out;
}

/** One v2 proposal on its own field rules (the v1 rules for spelling and style_adjust). */
function parseProposalV2(item: unknown): ModelProposal | 'none' | null {
	if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
	const p = item as Record<string, unknown>;
	const reason = oneLine(p.reason ?? '', MAX_REASON);
	if (reason === null) return null;
	switch (p.type) {
		case 'none':
			return 'none';
		case 'spelling':
		case 'style_adjust':
			return parseProposal(item);
		case 'heard_as':
		case 'raise_priority': {
			const from = forms(p.from, 1);
			const to = word(p.to);
			if (!from || !to) return null;
			return { type: p.type, from, to, reason };
		}
		case 'add_word': {
			const from = forms(p.from, 1);
			const to = word(p.to);
			if (!from || !to) return null;
			return { type: 'add_word', from, to, scope: p.scope === 'everywhere' ? 'everywhere' : 'app', reason };
		}
		case 'disable_spelling': {
			if (typeof p.spelling_ref !== 'string' || !SPELLING_REF.test(p.spelling_ref)) return null;
			return { type: 'disable_spelling', spellingRef: p.spelling_ref, reason };
		}
		default:
			return null;
	}
}

/** Whether a parsed proposal is one its issue's layer may carry. */
function fitsLayer(layer: Layer, p: ModelProposal): boolean {
	if (!LAYER_FIXES[layer].includes(p.type)) return false;
	// An expression problem moves the level, never the style.
	if (layer === 'expression' && p.type === 'style_adjust') return p.expressionDelta !== null && p.styleId === null;
	return true;
}

/**
 * Parses and checks a v2 answer (`pack/feedback/README.md`, v2). The whole
 * answer is refused when it isn't the contract; a bad issue or proposal is
 * left out on its own and recorded in `dropped`.
 */
export function parseFixResponseV2(raw: string, delivered: string): ParseResultV2 {
	let value: unknown;
	try {
		value = JSON.parse(raw.trim());
	} catch {
		return { ok: false, message: 'The answer was not valid JSON.' };
	}
	if (!value || typeof value !== 'object' || Array.isArray(value))
		return { ok: false, message: 'The answer was not a JSON object.' };
	const v = value as Record<string, unknown>;
	const explanation = oneLine(v.explanation ?? '', MAX_EXPLANATION);
	if (explanation === null)
		return { ok: false, message: 'The answer’s explanation had characters VoiceFlow refuses.' };
	if (typeof v.corrected_text !== 'string') return { ok: false, message: 'The answer had no corrected text.' };
	const correctedText = v.corrected_text.replace(/\r\n?/g, '\n');
	if (CONTROL.test(correctedText))
		return { ok: false, message: 'The corrected text had hidden characters, so it wasn’t used.' };
	if (correctedText.length > maxCorrectedLength(delivered))
		return {
			ok: false,
			message: 'The corrected text came back much longer than your dictation, so it wasn’t used.',
		};
	if (!Array.isArray(v.issues) || v.issues.length === 0)
		return { ok: false, message: 'The answer named no problem.' };
	if (v.issues.length > MAX_ISSUES_LISTED)
		return { ok: false, message: 'The answer named far too many problems, so none were used.' };
	let listed = 0;
	for (const issue of v.issues) {
		const ps = issue && typeof issue === 'object' ? (issue as Record<string, unknown>).proposals : null;
		if (Array.isArray(ps)) listed += ps.length;
	}
	if (listed > MAX_LISTED) return { ok: false, message: 'The answer had far too many fixes, so none were used.' };

	const issues: ParsedIssue[] = [];
	const proposals: ModelProposal[] = [];
	const dropped: DropCode[] = [];
	let droppedIssues = 0;
	let wordFixes = 0;
	let styleFixes = 0;
	for (const item of v.issues) {
		const i = item && typeof item === 'object' && !Array.isArray(item) ? (item as Record<string, unknown>) : null;
		const list = i && Array.isArray(i.proposals) ? i.proposals : [];
		/** Proposals that would have been fixes (a `none` is not one). */
		const fixesIn = list.filter((p) => !(p && typeof p === 'object' && (p as Record<string, unknown>).type === 'none')).length;
		if (issues.length >= MAX_ISSUES) {
			droppedIssues += 1;
			for (let k = 0; k < fixesIn; k++) dropped.push('issue-limit');
			continue;
		}
		const layer = i && typeof i.layer === 'string' && (LAYERS as readonly string[]).includes(i.layer) ? (i.layer as Layer) : null;
		const summary = i ? oneLine(i.summary ?? '', MAX_SUMMARY) : null;
		if (!i || !layer || summary === null || !Array.isArray(i.proposals)) {
			// A malformed issue is left out with whatever it proposed.
			droppedIssues += 1;
			for (let k = 0; k < fixesIn; k++) dropped.push('malformed');
			continue;
		}
		const index = issues.length;
		const parsed: ParsedIssue = { layer, summary, noFixReason: null };
		issues.push(parsed);
		for (const item of list) {
			const p = parseProposalV2(item);
			if (p === 'none') {
				const reason = oneLine((item as Record<string, unknown>).reason, MAX_REASON);
				parsed.noFixReason ??= reason || null;
				continue;
			}
			if (!p) {
				dropped.push('malformed');
				continue;
			}
			if (!fitsLayer(layer, p)) {
				dropped.push('layer');
				continue;
			}
			const isWord = (WORD_FIX_TYPES as readonly string[]).includes(p.type);
			if (isWord ? wordFixes >= MAX_WORD_FIXES : styleFixes >= MAX_STYLE_FIXES) {
				dropped.push('limit');
				continue;
			}
			if (isWord) wordFixes += 1;
			else styleFixes += 1;
			proposals.push({ ...p, issue: index });
		}
	}
	if (issues.length === 0) return { ok: false, message: 'The answer named no problem VoiceFlow can read.' };
	return {
		ok: true,
		value: {
			explanation,
			correctedText: correctedText.trim() ? correctedText : delivered,
			issues,
			proposals,
			dropped,
			droppedIssues,
		},
	};
}

/**
 * A strict JSON schema with nullable types written as `anyOf`, which
 * Anthropic's structured output takes (OpenAI takes the schema as it is).
 */
export function nullableAsAnyOf(schema: unknown): unknown {
	if (Array.isArray(schema)) return schema.map(nullableAsAnyOf);
	if (!schema || typeof schema !== 'object') return schema;
	const s = schema as Record<string, unknown>;
	if (Array.isArray(s.type) && s.type.includes('null')) {
		const types = (s.type as string[]).filter((t) => t !== 'null');
		const base: Record<string, unknown> = { type: types.length === 1 ? types[0] : types };
		if (Array.isArray(s.enum)) base.enum = s.enum.filter((e) => e !== null);
		return { anyOf: [base, { type: 'null' }] };
	}
	const out: Record<string, unknown> = {};
	for (const [k, val] of Object.entries(s)) out[k] = nullableAsAnyOf(val);
	return out;
}