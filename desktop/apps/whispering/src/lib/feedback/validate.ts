/**
 * VoiceFlow phase F: every lasting fix the model proposes is checked here,
 * deterministically, against the merged word lists before the person sees
 * it, and again when they apply it (PLAN-v2: "whole-word, once,
 * non-cascading, collision examples shown").
 *
 * - A spelling must name forms that really are in this dictation (whole
 *   words), must not be a common word or one of the person's own words, and
 *   must not chain, cycle or clash with a spelling in force
 *   (`validateNewCorrection`). Corrections apply once, in one pass, so a new
 *   one never rewrites another's output.
 * - Its consequences are shown: "this would also change: …", from the
 *   person's recent dictations in the same scope.
 * - A style fix is bounded: this app's style (a word-list style or Raw) and
 *   expression (0–4, at most two steps), nothing else.
 *
 * Pure: the caller passes the snapshot, the corrections in force and the
 * recent dictations.
 */
import {
	applyCorrections,
	EVERY_APP,
	OP_SCHEMA,
	OP_VERSION,
	sameForm,
	validateNewCorrection,
	validRuleId,
	type CorrectionOp,
} from '../profile/ops';
import type { ProfileSnapshot } from '$lib/profile/types';
import type { ModelProposal } from './contract';
import type { DictationEvidence } from './evidence';

export const EXPRESSION_NAMES = ['Reserved', 'Subtle', 'Natural', 'Lively', 'Expressive'] as const;
const DEFAULT_LEVEL = 2;
const MAX_STEP = 2;
const MAX_EXAMPLES = 3;

/** Words a spelling may never rewrite: too common to be a mishearing. */
const COMMON = new Set(
	(
		'a an the and or but if of to in on at by for from with as is are was were be been am i me my you your ' +
		'he she it we they them our us his her its this that these those there here what which who when where why ' +
		'how not no yes do does did done have has had will would can could should shall may might must just so ' +
		'than then too very up down out over all any some one two get got go going ok okay hi hey thanks please ' +
		"i'm i'll i've i'd you're you'll you've we're we'll we've they're they'll it's that's there's what's " +
		"let's don't doesn't didn't can't won't isn't aren't wasn't weren't haven't hasn't wouldn't couldn't shouldn't"
	).split(' '),
);

export type Example = { recordingId: string; before: string; after: string };

export type ScopeCheck =
	| { ok: true; alsoChanges: Example[]; alsoCount: number }
	| { ok: false; message: string };

export type SpellingFix = {
	kind: 'spelling';
	key: string;
	from: string[];
	to: string;
	reason: string;
	/** The app's id for the "in <app> only" scope (null: unknown app). */
	appId: string | null;
	appName: string | null;
	defaultScope: 'app' | 'everywhere';
	/** One check per scope the person can choose. */
	checks: { app: ScopeCheck | null; everywhere: ScopeCheck };
};

export type StyleFix = {
	kind: 'style';
	key: string;
	reason: string;
	app: string;
	appName: string | null;
	/** The style to use for this app: a word-list style id or `raw`. */
	style: { id: string; name: string } | null;
	expression: { from: number; to: number } | null;
	/** The app rule this replaces, in plain words, when there is one. */
	replaces: string | null;
	label: string;
};

export type ReviewedFix = SpellingFix | StyleFix;

export type Dropped = { reason: string };

export type HistoryText = { recordingId: string; appId: string | null; text: string };

export type ReviewContext = {
	evidence: DictationEvidence;
	/** Word lists in force (null: off, this device's edits only). */
	snapshot: ProfileSnapshot | null;
	/** Spellings in force: every layer's with word lists on, else this device's. */
	active: readonly CorrectionOp[];
	/** App rules from this device's edits (word lists off). */
	localAppRules?: ReadonlyMap<string, { style?: string; expression?: number }>;
	/** Recent dictations, newest first (the subject is skipped). */
	history: readonly HistoryText[];
};

function pseudo(from: string[], to: string, apps: string[], id = 'candidate'): CorrectionOp {
	return {
		schema: OP_SCHEMA,
		version: OP_VERSION,
		id,
		device: 'candidate',
		createdAt: '',
		type: 'correction',
		from,
		to,
		scope: { apps },
	};
}

function snippetAround(text: string, heard: string): string {
	const at = text.toLocaleLowerCase().indexOf(heard.toLocaleLowerCase());
	if (at < 0) return text.slice(0, 80);
	const start = Math.max(0, at - 30);
	const end = Math.min(text.length, at + heard.length + 30);
	return `${start > 0 ? '…' : ''}${text.slice(start, end).replace(/\s+/g, ' ').trim()}${end < text.length ? '…' : ''}`;
}

/** What else a spelling would change, in the dictations it would cover. */
export function collisions(
	candidate: CorrectionOp,
	history: readonly HistoryText[],
	subjectId: string,
): { alsoChanges: Example[]; alsoCount: number } {
	const alsoChanges: Example[] = [];
	let alsoCount = 0;
	const everywhere = candidate.scope.apps.includes(EVERY_APP);
	for (const h of history) {
		if (h.recordingId === subjectId || !h.text) continue;
		if (!everywhere && (!h.appId || !candidate.scope.apps.includes(h.appId))) continue;
		const { changes } = applyCorrections(h.text, [candidate], h.appId);
		if (changes.length === 0) continue;
		alsoCount += 1;
		if (alsoChanges.length < MAX_EXAMPLES) {
			const heard = changes[0]?.heard ?? '';
			const before = snippetAround(h.text, heard);
			alsoChanges.push({
				recordingId: h.recordingId,
				before,
				after: applyCorrections(before, [candidate], h.appId).text,
			});
		}
	}
	return { alsoChanges, alsoCount };
}

/** A spelling scope the word lists accept (see the Rust `check_apps`). */
export function validScopeApp(app: string | null | undefined): app is string {
	return !!app && app.length <= 200 && app === app.toLowerCase() && !/[\r\n<>]/.test(app) && app !== EVERY_APP;
}

/**
 * The app id an app rule (style, expression) is keyed by: the macOS bundle
 * id when there is one (the word lists' convention there), else the exe name.
 */
export function ruleAppOf(evidence: DictationEvidence): string | null {
	for (const id of [evidence.appBundleId, evidence.appId]) {
		const lower = id?.toLowerCase();
		if (lower && validRuleId(lower)) return lower;
	}
	return null;
}

const LETTER = /[\p{L}\p{N}]/u;

/** Forms of `from` found as whole words in the dictation's own texts. */
function groundedForms(from: string[], evidence: DictationEvidence): string[] {
	const texts = [evidence.rawText, evidence.tidyOutput, evidence.deliveredText].filter(
		(t): t is string => !!t,
	);
	return from.filter((f) =>
		texts.some((t) => applyCorrections(t, [pseudo([f], `\u0000${f}`, [EVERY_APP])]).changes.length > 0),
	);
}

/** A spelling's problems that don't depend on its scope; null when none. */
function spellingProblem(
	from: string[],
	to: string,
	snapshot: ProfileSnapshot | null,
): string | null {
	for (const f of from) {
		// A heard form is a word or words: it starts and ends with a letter or digit.
		if (!LETTER.test(f.at(0) ?? '') || !LETTER.test(f.at(-1) ?? ''))
			return `“${f}” isn’t a word VoiceFlow can correct.`;
		const words = f.toLocaleLowerCase().split(/[^\p{L}\p{N}'’]+/u).filter(Boolean);
		const common = (w: string) => {
			const plain = w.replace(/’/g, "'");
			return COMMON.has(plain) || COMMON.has(plain.replace(/'s$/, ''));
		};
		if (f.length < 2 || words.every(common))
			return `“${f}” is too common a word to change everywhere it appears.`;
		const own = snapshot?.terms.find((t) => sameForm(t.text, f));
		if (own) return `“${own.text}” is one of your words, so it isn’t changed.`;
	}
	return null;
}

/**
 * Checks one spelling in one scope against the spellings in force and the
 * other fixes chosen with it (so two fixes can't form a chain together).
 */
export function checkSpelling(
	fix: Pick<SpellingFix, 'from' | 'to'>,
	apps: string[],
	ctx: Pick<ReviewContext, 'active' | 'history' | 'snapshot'> & { subjectId: string },
	alsoChosen: readonly CorrectionOp[] = [],
): ScopeCheck {
	const problem = spellingProblem(fix.from, fix.to, ctx.snapshot);
	if (problem) return { ok: false, message: problem };
	const checked = validateNewCorrection([...ctx.active, ...alsoChosen], {
		from: fix.from,
		to: fix.to,
		scope: { apps },
	});
	if (!checked.ok) return checked;
	return { ok: true, ...collisions(pseudo(fix.from, fix.to, apps), ctx.history, ctx.subjectId) };
}

function appRule(
	snapshot: ProfileSnapshot | null,
	local: ReviewContext['localAppRules'],
	app: string,
): { style?: string; expression?: number } | null {
	if (snapshot) {
		for (const rule of snapshot.apps) {
			if (!rule || typeof rule !== 'object') continue;
			const r = rule as { id?: unknown; style?: unknown; expression?: unknown };
			if (typeof r.id !== 'string' || r.id.toLowerCase() !== app) continue;
			return {
				...(typeof r.style === 'string' ? { style: r.style } : {}),
				...(typeof r.expression === 'number' ? { expression: r.expression } : {}),
			};
		}
		return null;
	}
	return local?.get(app) ?? null;
}

function styleName(snapshot: ProfileSnapshot | null, id: string): string {
	if (id === 'raw') return 'Raw';
	return snapshot?.styles.find((s) => s.id === id)?.label || id;
}

function reviewStyle(
	p: Extract<ModelProposal, { type: 'style_adjust' }>,
	ctx: ReviewContext,
	key: string,
): StyleFix | Dropped {
	const { evidence, snapshot } = ctx;
	const app = ruleAppOf(evidence) ?? '';
	if (!app)
		return { reason: 'A style change needs to know the app, and this dictation’s app isn’t known.' };
	const current = evidence.styleId?.startsWith('profile:')
		? evidence.styleId.slice('profile:'.length)
		: evidence.styleId === 'raw'
			? 'raw'
			: null;
	let style: StyleFix['style'] = null;
	if (p.styleId) {
		if (!snapshot)
			return { reason: 'Switching an app’s style needs word lists, so that suggestion was left out.' };
		if (p.styleId !== 'raw' && !snapshot.styles.some((s) => s.id === p.styleId))
			return { reason: 'A suggested style isn’t one of yours, so it was left out.' };
		if (p.styleId !== current) style = { id: p.styleId, name: styleName(snapshot, p.styleId) };
	}
	let expression: StyleFix['expression'] = null;
	const target = style?.id ?? current;
	if (p.expressionDelta !== null && target !== 'raw') {
		const own =
			current && current !== 'raw'
				? snapshot?.styles.find((s) => s.id === current)?.expression
				: undefined;
		const base =
			typeof evidence.expression === 'number'
				? evidence.expression
				: typeof own === 'number'
					? own
					: DEFAULT_LEVEL;
		const step = Math.max(-MAX_STEP, Math.min(MAX_STEP, p.expressionDelta));
		const to = Math.max(0, Math.min(4, base + step));
		if (to !== base) expression = { from: base, to };
	}
	if (!style && !expression) return { reason: 'A style suggestion would change nothing, so it was left out.' };
	const appName = evidence.appName ?? app;
	const parts: string[] = [];
	if (style) parts.push(style.id === 'raw' ? 'skip tidy-up (Raw)' : `use the ${style.name} style`);
	if (expression)
		parts.push(
			`make tidy-up ${expression.to < expression.from ? 'more reserved' : 'more expressive'} (${EXPRESSION_NAMES[expression.from]} → ${EXPRESSION_NAMES[expression.to]})`,
		);
	const rule = appRule(snapshot, ctx.localAppRules, app);
	const replaces =
		rule && (rule.style !== undefined || rule.expression !== undefined)
			? `Replaces the current rule for ${appName}${rule.style ? ` (${styleName(snapshot, rule.style)} style)` : ''}.`
			: null;
	return {
		kind: 'style',
		key,
		reason: p.reason,
		app,
		appName: evidence.appName ?? null,
		style,
		expression,
		replaces,
		label: `In ${appName}, ${parts.join(' and ')}`,
	};
}

/**
 * Reviews the model's proposals. Each comes back ready to show (unchecked),
 * or is dropped with a plain reason.
 */
export function reviewProposals(
	proposals: readonly ModelProposal[],
	ctx: ReviewContext,
): { fixes: ReviewedFix[]; dropped: Dropped[] } {
	const fixes: ReviewedFix[] = [];
	const dropped: Dropped[] = [];
	const appId = validScopeApp(ctx.evidence.appId) ? ctx.evidence.appId : null;
	const subjectId = ctx.evidence.recordingId;
	proposals.forEach((p, i) => {
		const key = `p${i}`;
		if (p.type === 'style_adjust') {
			const reviewed = reviewStyle(p, ctx, key);
			if ('kind' in reviewed) {
				if (fixes.some((f) => f.kind === 'style'))
					dropped.push({ reason: 'Only one style change is offered at a time.' });
				else fixes.push(reviewed);
			} else dropped.push(reviewed);
			return;
		}
		const from = groundedForms(p.from, ctx.evidence);
		if (from.length === 0) {
			dropped.push({ reason: `A spelling for “${p.from[0]}” was left out: that word isn’t in this dictation.` });
			return;
		}
		if (fixes.some((f) => f.kind === 'spelling' && f.from.some((g) => from.some((h) => sameForm(g, h))))) {
			dropped.push({ reason: 'Two suggestions changed the same word, so only the first is offered.' });
			return;
		}
		const appOk = !!appId;
		const checkCtx = { active: ctx.active, history: ctx.history, snapshot: ctx.snapshot, subjectId };
		const appCheck = appOk ? checkSpelling({ from, to: p.to }, [appId], checkCtx) : null;
		const everywhere = checkSpelling({ from, to: p.to }, [EVERY_APP], checkCtx);
		if (!(appCheck?.ok || everywhere.ok)) {
			dropped.push({
				reason: `A spelling for “${from[0]}” was left out: ${
					(appCheck && !appCheck.ok ? appCheck.message : !everywhere.ok ? everywhere.message : '') ||
					'it can’t be saved.'
				}`,
			});
			return;
		}
		fixes.push({
			kind: 'spelling',
			key,
			from,
			to: p.to,
			reason: p.reason,
			appId: appOk ? appId : null,
			appName: ctx.evidence.appName ?? null,
			// Personal and app-scoped by default; "every app" only when it is
			// the only scope that works or the person picks it.
			defaultScope: appCheck?.ok ? 'app' : 'everywhere',
			checks: { app: appCheck, everywhere },
		});
	});
	return { fixes, dropped };
}

export type Selection = { key: string; scope?: 'app' | 'everywhere' };

/**
 * Checks the chosen fixes together, in order, right before they are written:
 * each spelling against the ones in force plus the ones chosen before it.
 */
export function checkSelection(
	fixes: readonly ReviewedFix[],
	selection: readonly Selection[],
	ctx: ReviewContext,
): { ok: true } | { ok: false; key: string; message: string } {
	const chosen: CorrectionOp[] = [];
	const checkCtx = {
		active: ctx.active,
		history: ctx.history,
		snapshot: ctx.snapshot,
		subjectId: ctx.evidence.recordingId,
	};
	let styles = 0;
	for (const sel of selection) {
		const fix = fixes.find((f) => f.key === sel.key);
		if (!fix) return { ok: false, key: sel.key, message: 'That fix is no longer offered.' };
		if (fix.kind === 'style') {
			styles += 1;
			if (styles > 1) return { ok: false, key: sel.key, message: 'Choose one style change at a time.' };
			if (
				fix.style &&
				fix.style.id !== 'raw' &&
				!ctx.snapshot?.styles.some((s) => s.id === fix.style?.id)
			)
				return { ok: false, key: sel.key, message: `The ${fix.style.name} style is no longer in your word lists.` };
			continue;
		}
		const scope = sel.scope ?? fix.defaultScope;
		if (scope === 'app' && !fix.appId)
			return { ok: false, key: sel.key, message: 'This app isn’t known, so the spelling can only apply in every app.' };
		const apps = scope === 'app' && fix.appId ? [fix.appId] : [EVERY_APP];
		const check = checkSpelling(fix, apps, checkCtx, chosen);
		if (!check.ok) return { ok: false, key: sel.key, message: check.message };
		chosen.push(pseudo(fix.from, fix.to, apps, `chosen-${chosen.length}`));
	}
	return { ok: true };
}
