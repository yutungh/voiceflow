/**
 * VoiceFlow profile edit operations (PLAN-v2, Decisions, "Add a word").
 *
 * Every edit made on this device is an immutable JSON operation: a
 * correction ("always write these heard forms as this") or a tombstone that
 * removes an earlier operation, and since phase B + C2 a word ("Add a
 * word"). Operations are never changed after they are written; removing one
 * writes a tombstone. The Rust profile service uploads them to the user's
 * word lists and merges them over the household and personal layers.
 *
 * This module is pure: parsing, validating and applying operations. Reading
 * and writing the files is in `op-store.ts`.
 */

export const OP_SCHEMA = 'voiceflow-op' as const;
export const OP_VERSION = 1 as const;

/** Applies in every app. */
export const EVERY_APP = '*' as const;

type OpBase = {
	schema: typeof OP_SCHEMA;
	version: typeof OP_VERSION;
	/** `<device>-<uuid>`, also the file name. */
	id: string;
	device: string;
	/** ISO 8601. */
	createdAt: string;
};

export type CorrectionOp = OpBase & {
	type: 'correction';
	/** The forms VoiceFlow hears, e.g. ["Zefira"]. */
	from: string[];
	/** How to write them, e.g. "Zephyra". */
	to: string;
	/** ["*"] for every app, or app ids (lower-case exe name or bundle id). */
	scope: { apps: string[] };
	note?: string;
};

export type TombstoneOp = OpBase & {
	type: 'tombstone';
	/** The id of the operation this removes. */
	target: string;
};

/**
 * "Add a word" (phase B + C2): a word to put in the vocabulary prompt. It
 * only helps recognition; it never rewrites text (that is a correction).
 * Clients that don't know this type skip it.
 */
export type TermOp = OpBase & {
	type: 'term';
	term: {
		text: string;
		/** Forms VoiceFlow may hear for it (hints only). */
		heardAs?: string[];
		kind?: string;
		/** ["*"] for every app, or app ids. Absent: every app. */
		apps?: string[];
		/** A short note on the word (at most 80 characters). */
		gloss?: string;
		/** 0–100 (absent: 50). Higher words reach the transcriber first. */
		priority?: number;
	};
};

/**
 * VoiceFlow phase F: an app's style and/or expression, from an approved
 * feedback fix ("in Kite, use the Casual style", "less expressive in Kite").
 * It sets the app rule's fields over the word lists'. Undo is a tombstone.
 */
export type AppStyleOp = OpBase & {
	type: 'app-style';
	/** Lower-case app id (exe name or bundle id). */
	app: string;
	/** A word-list style id, or `raw`. */
	style?: string;
	/** 0–4. */
	expression?: number;
};

export type ProfileOp = CorrectionOp | TombstoneOp | TermOp | AppStyleOp;

/** Ids the word lists accept for apps and styles (see the Rust model). */
export function validRuleId(id: string): boolean {
	return /^[a-z0-9][a-z0-9._-]{0,99}$/.test(id);
}

function isString(value: unknown): value is string {
	return typeof value === 'string';
}

function isStringArray(value: unknown): value is string[] {
	return Array.isArray(value) && value.every(isString);
}

/**
 * Checks a parsed JSON value and returns it as an operation, or null when it
 * is not a well-formed version 1 operation. Unknown extra fields are dropped.
 */
export function parseOp(value: unknown): ProfileOp | null {
	if (!value || typeof value !== 'object') return null;
	const v = value as Record<string, unknown>;
	if (v.schema !== OP_SCHEMA || v.version !== OP_VERSION) return null;
	if (!isString(v.id) || !v.id) return null;
	if (!isString(v.device) || !v.device) return null;
	if (!isString(v.createdAt) || Number.isNaN(Date.parse(v.createdAt)))
		return null;
	const base: OpBase = {
		schema: OP_SCHEMA,
		version: OP_VERSION,
		id: v.id,
		device: v.device,
		createdAt: v.createdAt,
	};
	if (v.type === 'tombstone') {
		if (!isString(v.target) || !v.target) return null;
		return { ...base, type: 'tombstone', target: v.target };
	}
	if (v.type === 'term') {
		const term = v.term as Record<string, unknown> | undefined;
		if (!term || !isString(term.text) || !term.text.trim()) return null;
		const out: TermOp['term'] = { text: term.text.trim() };
		if (isStringArray(term.heardAs)) {
			const heard = term.heardAs.map((h) => h.trim()).filter(Boolean);
			if (heard.length > 0) out.heardAs = heard;
		}
		if (isString(term.kind) && term.kind) out.kind = term.kind;
		if (isStringArray(term.apps) && term.apps.length > 0)
			out.apps = term.apps.map((a) => (a === EVERY_APP ? a : a.toLowerCase()));
		if (isString(term.gloss) && term.gloss.trim()) out.gloss = term.gloss.trim();
		if (term.priority !== undefined) {
			const p = term.priority;
			if (typeof p !== 'number' || !Number.isInteger(p) || p < 0 || p > 100) return null;
			out.priority = p;
		}
		return { ...base, type: 'term', term: out };
	}
	if (v.type === 'app-style') {
		if (!isString(v.app)) return null;
		const app = v.app.trim().toLowerCase();
		if (!validRuleId(app)) return null;
		const op: AppStyleOp = { ...base, type: 'app-style', app };
		if (v.style !== undefined && v.style !== null) {
			if (!isString(v.style) || !(v.style === 'raw' || validRuleId(v.style))) return null;
			op.style = v.style;
		}
		if (v.expression !== undefined && v.expression !== null) {
			if (
				typeof v.expression !== 'number' ||
				!Number.isInteger(v.expression) ||
				v.expression < 0 ||
				v.expression > 4
			)
				return null;
			op.expression = v.expression;
		}
		if (op.style === undefined && op.expression === undefined) return null;
		return op;
	}
	if (v.type === 'correction') {
		if (!isStringArray(v.from)) return null;
		const from = v.from.map((f) => f.trim()).filter((f) => f.length > 0);
		if (from.length === 0) return null;
		if (!isString(v.to) || !v.to.trim()) return null;
		const scope = v.scope as Record<string, unknown> | undefined;
		if (!scope || !isStringArray(scope.apps) || scope.apps.length === 0)
			return null;
		const op: CorrectionOp = {
			...base,
			type: 'correction',
			from,
			to: v.to.trim(),
			scope: { apps: scope.apps.map((a) => a.toLowerCase()) },
		};
		if (isString(v.note) && v.note.trim()) op.note = v.note.trim();
		return op;
	}
	return null;
}

/** Same form, ignoring case and runs of spaces. */
export function sameForm(a: string, b: string): boolean {
	return normalizeForm(a) === normalizeForm(b);
}

function normalizeForm(form: string): string {
	return form.trim().replace(/\s+/g, ' ').toLocaleLowerCase();
}

/**
 * The ids the tombstones in force remove. Newest first, as the word lists'
 * merge does (`live_ops` in Rust): a tombstone removed by a newer one has no
 * effect, so undoing an undo brings the edit back (VoiceFlow 0.9.16: a
 * spelling turned off from feedback, then Undo).
 */
export function removedIds(ops: readonly ProfileOp[]): Set<string> {
	const time = (op: ProfileOp) => Date.parse(op.createdAt) || 0;
	const sorted = [...ops].sort((a, b) => time(a) - time(b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	const dead = new Set<string>();
	for (let i = sorted.length - 1; i >= 0; i--) {
		const op = sorted[i]!;
		if (dead.has(op.id)) continue;
		if (op.type === 'tombstone') dead.add(op.target);
	}
	return dead;
}

/**
 * The corrections in force: every correction no tombstone removes, oldest
 * first. If two devices saved the same heard form, the newer one wins for
 * that form (the profile service will show such conflicts later).
 */
export function activeCorrections(ops: readonly ProfileOp[]): CorrectionOp[] {
	const removed = removedIds(ops);
	return ops
		.filter(
			(op): op is CorrectionOp =>
				op.type === 'correction' && !removed.has(op.id),
		)
		.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/**
 * The app rules this device's edits set (word lists off): for each app, the
 * newest edit's style and expression, field by field, oldest first.
 */
export function activeAppStyles(
	ops: readonly ProfileOp[],
): Map<string, { style?: string; expression?: number; editId: string }> {
	const removed = removedIds(ops);
	const rules = new Map<string, { style?: string; expression?: number; editId: string }>();
	const live = ops
		.filter((op): op is AppStyleOp => op.type === 'app-style' && !removed.has(op.id))
		.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
	for (const op of live) {
		const rule = rules.get(op.app) ?? { editId: op.id };
		if (op.style !== undefined) rule.style = op.style;
		if (op.expression !== undefined) rule.expression = op.expression;
		rule.editId = op.id;
		rules.set(op.app, rule);
	}
	return rules;
}

function appliesTo(op: CorrectionOp, appId: string | null | undefined) {
	if (op.scope.apps.includes(EVERY_APP)) return true;
	if (!appId) return false;
	return op.scope.apps.includes(appId.toLowerCase());
}

function scopesOverlap(a: CorrectionOp['scope'], b: CorrectionOp['scope']) {
	if (a.apps.includes(EVERY_APP) || b.apps.includes(EVERY_APP)) return true;
	return a.apps.some((app) => b.apps.includes(app));
}

export type NewCorrection = {
	from: string[];
	to: string;
	scope: { apps: string[] };
};

export type ValidationResult = { ok: true } | { ok: false; message: string };

const quote = (s: string) => `“${s.trim()}”`;

/**
 * Checks a new correction against the ones in force, before it is saved.
 * Corrections are applied in one pass, so a chain (A → B, then B → C) or a
 * cycle would make the result depend on order: both are refused, as are
 * duplicates. Messages are plain sentences for the person saving it.
 */
export function validateNewCorrection(
	active: readonly CorrectionOp[],
	candidate: NewCorrection,
): ValidationResult {
	const from = candidate.from.map((f) => f.trim()).filter(Boolean);
	const to = candidate.to.trim();
	if (from.length === 0)
		return { ok: false, message: 'Type the word VoiceFlow heard.' };
	if (!to) return { ok: false, message: 'Type how it should be written.' };
	if (from.every((f) => f === to))
		return {
			ok: false,
			message: `${quote(to)} is already written that way.`,
		};
	if (from.some((f, i) => from.findIndex((g) => sameForm(f, g)) !== i))
		return { ok: false, message: 'The same heard word is listed twice.' };
	if (candidate.scope.apps.length === 0)
		return { ok: false, message: 'Choose where the spelling applies.' };

	for (const op of active) {
		if (!scopesOverlap(op.scope, candidate.scope)) continue;
		for (const f of from) {
			const clash = op.from.find((g) => sameForm(f, g));
			if (!clash) continue;
			if (op.to === to)
				return {
					ok: false,
					message: `VoiceFlow already writes ${quote(clash)} as ${quote(op.to)}.`,
				};
			return {
				ok: false,
				message: `VoiceFlow already writes ${quote(clash)} as ${quote(op.to)}. Remove that spelling first if ${quote(to)} is right.`,
			};
		}
		if (op.from.some((g) => sameForm(g, to)))
			return {
				ok: false,
				message: `${quote(to)} is itself changed to ${quote(op.to)} by another spelling, so this one can't be saved.`,
			};
		if (from.some((f) => sameForm(f, op.to)))
			return {
				ok: false,
				message: `${quote(op.to)} is the spelling another fix writes, so it can't also be changed to ${quote(to)}.`,
			};
	}
	return { ok: true };
}

function escapeRegExp(s: string): string {
	return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** A heard form as a pattern: spaces match any run of spaces. */
function formPattern(form: string): string {
	return form.trim().split(/\s+/).map(escapeRegExp).join('\\s+');
}

export type AppliedChange = {
	heard: string;
	written: string;
	/** The correction that made the change (phase F: stage evidence). */
	id: string;
};

/**
 * Applies the corrections that cover `appId` to the final text: whole words
 * only, ignoring case, in one pass (a replacement is never corrected again).
 */
export function applyCorrections(
	text: string,
	corrections: readonly CorrectionOp[],
	appId?: string | null,
): { text: string; changes: AppliedChange[] } {
	const byForm = new Map<string, { to: string; id: string }>();
	for (const op of corrections) {
		if (!appliesTo(op, appId)) continue;
		for (const form of op.from) byForm.set(normalizeForm(form), { to: op.to, id: op.id });
	}
	if (byForm.size === 0 || !text) return { text, changes: [] };

	// Longest forms first, so "cloud code" wins over "cloud".
	const forms = [...byForm.keys()].sort((a, b) => b.length - a.length);
	const pattern = new RegExp(
		`(?<![\\p{L}\\p{N}_])(?:${forms.map(formPattern).join('|')})(?![\\p{L}\\p{N}_])`,
		'giu',
	);
	const changes: AppliedChange[] = [];
	const out = text.replace(pattern, (match) => {
		const hit = byForm.get(normalizeForm(match));
		if (hit === undefined || hit.to === match) return match;
		changes.push({ heard: match, written: hit.to, id: hit.id });
		return hit.to;
	});
	return { text: out, changes };
}
