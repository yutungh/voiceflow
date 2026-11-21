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
import type { Layer, ModelProposal } from './contract';
import { spellingRefs, type DictationEvidence } from './evidence';
import { hasWords } from './stage-diff';
import { ladderStep, placeOf, withWord, type LadderStep, type WordStep } from './word-ladder';

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
	/** v2: the issue it fixes (v1 answers: 0). */
	issue?: number;
	/**
	 * v2: it replaces a spelling this review also offers to turn off: it can
	 * only be applied with that one (its keys).
	 */
	requires?: string[];
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
	issue?: number;
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

export type WordChoice = Extract<LadderStep, { ok: true }>;

/** v2: a word fix up the ladder (a heard-as form, a new word, a raised priority). */
export type WordFix = {
	kind: 'word';
	key: string;
	issue: number;
	reason: string;
	/** The step every choice takes (the same step in each scope). */
	step: WordStep;
	/** When the model asked for another step: why this one instead. */
	adjusted: string | null;
	word: string;
	appId: string | null;
	appName: string | null;
	/** A new word's scope is chosen; a listed word keeps its own (one choice). */
	defaultScope: 'app' | 'everywhere';
	choices: Partial<Record<'app' | 'everywhere', WordChoice>>;
};

/** v2: turn off a saved spelling that changed this dictation (an edit's tombstone). */
export type UnspellFix = {
	kind: 'unspell';
	key: string;
	issue: number;
	reason: string;
	/** The spelling's id (the edit the tombstone removes). */
	target: string;
	heard: string;
	written: string;
	label: string;
};

export type ReviewedFix = SpellingFix | StyleFix | WordFix | UnspellFix;

export type Dropped = { reason: string; issue?: number };

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
	/** v2: what the person said it should have said (their words, the truth). */
	wanted?: string | null;
	/** v2: the layer of each issue in the answer, by index. */
	layers?: readonly Layer[];
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

const isCommon = (form: string) => {
	const words = form.toLocaleLowerCase().split(/[^\p{L}\p{N}'’]+/u).filter(Boolean);
	const common = (w: string) => {
		const plain = w.replace(/’/g, "'");
		return COMMON.has(plain) || COMMON.has(plain.replace(/'s$/, ''));
	};
	return form.length < 2 || words.every(common);
};

const q = (s: string) => `“${s}”`;

const LAYER_NAMES: Record<string, string> = {
	household: 'household list',
	generated: 'list made from your notes',
	personal: 'personal list',
	default: 'built-in list',
};

/** The spelling's layer: from the merged lists, or this device's edits when they are off. */
function layerOfSpelling(ctx: ReviewContext, id: string): string | null {
	if (!ctx.snapshot) return ctx.active.some((c) => c.id === id) ? 'edit' : null;
	return ctx.snapshot.corrections.find((c) => c.id === id)?.layer ?? null;
}

function reviewUnspell(
	p: Extract<ModelProposal, { type: 'disable_spelling' }>,
	ctx: ReviewContext,
	key: string,
	issue: number,
): UnspellFix | Dropped {
	const applied = spellingRefs(ctx.evidence).find((s) => s.ref === p.spellingRef);
	if (!applied) return { reason: 'A suggestion named a spelling that didn’t change this dictation, so it was left out.', issue };
	const label = `${q(applied.heard)} → ${q(applied.written)}`;
	if (!ctx.active.some((c) => c.id === applied.id))
		return { reason: `The spelling ${label} is already off.`, issue };
	const layer = layerOfSpelling(ctx, applied.id);
	if (layer !== 'edit')
		return {
			reason: `The spelling ${label} comes from your ${LAYER_NAMES[layer ?? ''] ?? 'word lists'}, so it is changed there, not here.`,
			issue,
		};
	return {
		kind: 'unspell',
		key,
		issue,
		reason: p.reason,
		target: applied.id,
		heard: applied.heard,
		written: applied.written,
		label: `Turn off the spelling ${label}`,
	};
}

const STEP_WORDS: Record<WordStep, string> = {
	heard_as: 'a heard-as form',
	add_word: 'a new word',
	raise_priority: 'a higher priority',
};

function reviewWord(
	p: Extract<ModelProposal, { type: 'heard_as' | 'add_word' | 'raise_priority' }>,
	from: string[],
	ctx: ReviewContext,
	key: string,
	issue: number,
	appId: string | null,
): WordFix | Dropped {
	const snapshot = ctx.snapshot;
	if (!snapshot) return { reason: `A word fix for ${q(p.to)} needs word lists, so it was left out.`, issue };
	const to = p.to.trim().replace(/\s+/g, ' ');
	if (isCommon(to)) return { reason: `${q(to)} is too common a word to add.`, issue };
	// A word a saved spelling rewrites would never reach the text as written.
	const rewritten = ctx.active.find((c) => c.from.some((f) => sameForm(f, to)) && c.to !== to);
	if (rewritten)
		return { reason: `A saved spelling changes ${q(to)} to ${q(rewritten.to)}, so the word can’t be listed as it is.`, issue };
	const other = from.filter((f) => !sameForm(f, to));
	const heard = other.filter((f) => !isCommon(f));
	if (heard.length === 0 && p.type !== 'raise_priority')
		return {
			reason: `A word fix for ${q(to)} was left out: ${
				other.length === 0 ? 'speech-to-text already wrote it that way' : 'the heard form is too common a word'
			}.`,
			issue,
		};
	const scopes: Array<'app' | 'everywhere'> = p.type === 'add_word' ? (appId ? ['app', 'everywhere'] : ['everywhere']) : ['app'];
	const choices: WordFix['choices'] = {};
	let first: Extract<LadderStep, { ok: false }> | null = null;
	for (const scope of scopes) {
		const step = ladderStep(snapshot, p.type, { word: to, heard, appId, scope });
		if (step.ok) choices[scope] = step;
		else first ??= step;
	}
	const picked = Object.values(choices)[0];
	if (!picked) return { reason: `A word fix for ${q(to)} was left out: ${first?.message ?? 'it can’t be saved.'}`, issue };
	// A listed word keeps its own scope: one choice, shown as it is.
	const listed = !!picked.replaces;
	if (listed) {
		for (const k of Object.keys(choices) as Array<'app' | 'everywhere'>) if (choices[k] !== picked) delete choices[k];
	}
	const defaultScope: 'app' | 'everywhere' = listed
		? picked.term.apps.includes(EVERY_APP)
			? 'everywhere'
			: 'app'
		: p.type === 'add_word' && p.scope === 'everywhere'
			? 'everywhere'
			: choices.app
				? 'app'
				: 'everywhere';
	if (listed && !choices[defaultScope]) {
		choices[defaultScope] = picked;
		delete choices[defaultScope === 'app' ? 'everywhere' : 'app'];
	}
	const adjusted =
		picked.step === p.type
			? null
			: picked.step === 'add_word'
				? `${q(to)} isn’t in your words yet, so this adds it.`
				: p.type === 'add_word'
					? `${q(to)} is already in your words, so this gives it ${STEP_WORDS[picked.step]} instead.`
					: `${q(to)} already has that heard form, so this gives it ${STEP_WORDS[picked.step]} instead.`;
	return {
		kind: 'word',
		key,
		issue,
		reason: p.reason,
		step: picked.step,
		adjusted,
		word: picked.term.text,
		appId,
		appName: ctx.evidence.appName ?? null,
		defaultScope,
		choices,
	};
}

/**
 * Reviews the model's proposals. Each comes back ready to show (unchecked),
 * or is dropped with a plain reason.
 *
 * v2: each proposal belongs to an issue whose layer the model chose, and
 * the stage evidence must support it: a hearing fix names a form that is in
 * what speech-to-text heard; a word the person said it should be must be in
 * what they typed; a spelling turned off must have changed this dictation
 * and be an edit (one inside a word-list file is changed there).
 */
export function reviewProposals(
	proposals: readonly ModelProposal[],
	ctx: ReviewContext,
	/** Keys already applied (and kept as they were reviewed): not reviewed again. */
	skip: ReadonlySet<string> = new Set(),
): { fixes: ReviewedFix[]; dropped: Dropped[] } {
	const fixes: ReviewedFix[] = [];
	const dropped: Dropped[] = [];
	const appId = validScopeApp(ctx.evidence.appId) ? ctx.evidence.appId : null;
	const subjectId = ctx.evidence.recordingId;
	const wanted = ctx.wanted?.trim() ? ctx.wanted : null;
	const raw = ctx.evidence.rawText ?? '';
	const layerOf = (p: ModelProposal): Layer | null => (p.issue !== undefined ? (ctx.layers?.[p.issue] ?? null) : null);
	const notWanted = (to: string, issue: number) =>
		wanted && !hasWords(wanted, to)
			? { reason: `A fix writing ${q(to)} was left out: that isn’t in what you said it should have said.`, issue }
			: null;

	// Spellings turned off come first: a new spelling may replace one of them.
	const unspells = new Map<string, UnspellFix>();
	proposals.forEach((p, i) => {
		if (p.type !== 'disable_spelling' || skip.has(`p${i}`)) return;
		const issue = p.issue ?? 0;
		const reviewed = reviewUnspell(p, ctx, `p${i}`, issue);
		if ('kind' in reviewed) {
			if ([...unspells.values()].some((u) => u.target === reviewed.target))
				dropped.push({ reason: 'Two suggestions turned off the same spelling, so only the first is offered.', issue });
			else unspells.set(reviewed.key, reviewed);
		} else dropped.push(reviewed);
	});
	const offTargets = new Set([...unspells.values()].map((u) => u.target));
	const activeWithout = ctx.active.filter((c) => !offTargets.has(c.id));

	const words = new Set<string>();
	proposals.forEach((p, i) => {
		const key = `p${i}`;
		if (skip.has(key)) return;
		const issue = p.issue ?? 0;
		const layer = layerOf(p);
		if (p.type === 'disable_spelling') {
			const u = unspells.get(key);
			if (u) fixes.push(u);
			return;
		}
		if (p.type === 'style_adjust') {
			const reviewed = reviewStyle(p, ctx, key);
			if ('kind' in reviewed) {
				if (fixes.some((f) => f.kind === 'style'))
					dropped.push({ reason: 'Only one style change is offered at a time.', issue });
				else fixes.push({ ...reviewed, issue });
			} else dropped.push({ ...reviewed, issue });
			return;
		}
		// A hearing problem names what speech-to-text wrote.
		const heardOnly = layer === 'transcriber';
		const from = heardOnly ? p.from.filter((f) => hasWords(raw, f)) : groundedForms(p.from, ctx.evidence);
		if (from.length === 0) {
			dropped.push({
				reason: heardOnly
					? `A fix for ${q(p.from[0] ?? '')} was left out: speech-to-text didn’t write that, so it isn’t a hearing problem.`
					: `A spelling for ${q(p.from[0] ?? '')} was left out: that word isn’t in this dictation.`,
				issue,
			});
			return;
		}
		const unwanted = notWanted(p.to, issue);
		if (unwanted) {
			dropped.push(unwanted);
			return;
		}
		// A saved-spelling problem re-spells only what a saved spelling changed here.
		if (layer === 'spelling' && p.type === 'spelling') {
			const applied = spellingRefs(ctx.evidence);
			if (!from.every((f) => applied.some((s) => sameForm(s.heard, f)))) {
				dropped.push({
					reason: `A spelling for ${q(from[0] ?? '')} was left out: no saved spelling changed that word in this dictation.`,
					issue,
				});
				return;
			}
		}
		if (p.type !== 'spelling') {
			const k = p.to.trim().toLocaleLowerCase();
			if (words.has(k)) {
				dropped.push({ reason: `Two suggestions changed ${q(p.to)}, so only the first is offered.`, issue });
				return;
			}
			const reviewed = reviewWord(p, from, ctx, key, issue, appId);
			if ('kind' in reviewed) {
				words.add(k);
				fixes.push(reviewed);
			} else dropped.push(reviewed);
			return;
		}
		if (fixes.some((f) => f.kind === 'spelling' && f.from.some((g) => from.some((h) => sameForm(g, h))))) {
			dropped.push({ reason: 'Two suggestions changed the same word, so only the first is offered.', issue });
			return;
		}
		const appOk = !!appId;
		const checkCtx = { active: ctx.active, history: ctx.history, snapshot: ctx.snapshot, subjectId };
		let appCheck = appOk ? checkSpelling({ from, to: p.to }, [appId], checkCtx) : null;
		let everywhere = checkSpelling({ from, to: p.to }, [EVERY_APP], checkCtx);
		let requires: string[] | undefined;
		// It may replace a spelling this review turns off: then it needs that one ticked too.
		if (!(appCheck?.ok || everywhere.ok) && offTargets.size > 0) {
			const without = { ...checkCtx, active: activeWithout };
			const a2 = appOk ? checkSpelling({ from, to: p.to }, [appId], without) : null;
			const e2 = checkSpelling({ from, to: p.to }, [EVERY_APP], without);
			if (a2?.ok || e2.ok) {
				appCheck = a2;
				everywhere = e2;
				const clashes = (u: UnspellFix) => {
					const c = ctx.active.find((x) => x.id === u.target);
					return (
						!!c &&
						(c.from.some((g) => sameForm(g, p.to) || from.some((h) => sameForm(g, h))) ||
							from.some((h) => sameForm(h, c.to)))
					);
				};
				const all = [...unspells.values()];
				requires = (all.some(clashes) ? all.filter(clashes) : all).map((u) => u.key);
			}
		}
		if (!(appCheck?.ok || everywhere.ok)) {
			dropped.push({
				reason: `A spelling for “${from[0]}” was left out: ${
					(appCheck && !appCheck.ok ? appCheck.message : !everywhere.ok ? everywhere.message : '') ||
					'it can’t be saved.'
				}`,
				issue,
			});
			return;
		}
		fixes.push({
			kind: 'spelling',
			key,
			issue,
			...(requires ? { requires } : {}),
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
 * each spelling against the ones in force (less any chosen to be turned off)
 * plus the ones chosen before it.
 */
export function checkSelection(
	fixes: readonly ReviewedFix[],
	selection: readonly Selection[],
	ctx: ReviewContext,
	/** Keys already applied and not undone (a required "turn off" may be one). */
	applied: ReadonlySet<string> = new Set(),
): { ok: true } | { ok: false; key: string; message: string } {
	const chosen: CorrectionOp[] = [];
	const picked = new Set(selection.map((s) => s.key));
	const off = new Set(
		fixes.filter((f): f is UnspellFix => f.kind === 'unspell' && picked.has(f.key)).map((f) => f.target),
	);
	const checkCtx = {
		active: ctx.active.filter((c) => !off.has(c.id)),
		history: ctx.history,
		snapshot: ctx.snapshot,
		subjectId: ctx.evidence.recordingId,
	};
	let styles = 0;
	const words = new Set<string>();
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
		if (fix.kind === 'unspell') {
			if (!ctx.active.some((c) => c.id === fix.target))
				return { ok: false, key: sel.key, message: `The spelling “${fix.heard}” → “${fix.written}” is already off.` };
			continue;
		}
		if (fix.kind === 'word') {
			const scope = sel.scope ?? fix.defaultScope;
			if (!fix.choices[scope])
				return { ok: false, key: sel.key, message: `${q(fix.word)} can’t be saved ${scope === 'app' ? 'for this app only' : 'for every app'}.` };
			const k = fix.word.toLocaleLowerCase();
			if (words.has(k)) return { ok: false, key: sel.key, message: `Choose one change to ${q(fix.word)} at a time.` };
			words.add(k);
			continue;
		}
		if (fix.requires?.length && !fix.requires.some((r) => picked.has(r) || applied.has(r)))
			return {
				ok: false,
				key: sel.key,
				message: `This spelling replaces one that is on now: tick “Turn off” for that one too.`,
			};
		const scope = sel.scope ?? fix.defaultScope;
		if (scope === 'app' && !fix.appId)
			return { ok: false, key: sel.key, message: 'This app isn’t known, so the spelling can only apply in every app.' };
		const apps = scope === 'app' && fix.appId ? [fix.appId] : [EVERY_APP];
		const check = checkSpelling(fix, apps, checkCtx, chosen);
		if (!check.ok) return { ok: false, key: sel.key, message: check.message };
		chosen.push(pseudo(fix.from, fix.to, apps, `chosen-${chosen.length}`));
	}
	// Word fixes share the transcriber's 20: each was previewed alone, so
	// together they must still do what their cards said.
	const wordPicks = selection.flatMap((sel) => {
		const fix = fixes.find((f): f is WordFix => f.key === sel.key && f.kind === 'word');
		const choice = fix?.choices[sel.scope ?? fix.defaultScope];
		return fix && choice ? [{ fix, choice }] : [];
	});
	if (wordPicks.length > 1 && ctx.snapshot) {
		let lists: Pick<ProfileSnapshot, 'terms' | 'defaults'> = ctx.snapshot;
		for (const { choice } of wordPicks) lists = withWord(lists, choice.term);
		for (const { fix, choice } of wordPicks) {
			const place = placeOf(lists, fix.appId, choice.term.text);
			if (place.transcriber !== choice.effect.after.transcriber || place.tidy !== choice.effect.after.tidy)
				return {
					ok: false,
					key: fix.key,
					message: `Together these word fixes don’t do what each card says (they compete for the transcriber’s 20 words). Apply ${q(fix.word)} on its own, then look at the others again.`,
				};
		}
	}
	return { ok: true };
}