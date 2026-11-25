/**
 * VoiceFlow 0.9.18: the words of the Feedback window's result, in one place.
 *
 * The result has two separate jobs, shown as two numbered sections and named
 * in a summary at the top:
 *   1. Fix this text: the corrected text, to copy or paste. Nothing is saved.
 *   2. Save changes for next time: lasting changes used from the next
 *      dictation. The corrected text above is not changed by them.
 * Why it happened (the explanation, the problems, what changed at each step)
 * is folded below both.
 *
 * Pure: FeedbackFix.svelte renders these strings and the tests check them.
 */
import type { AppliedFix, FeedbackRecord } from './log';
import type { ReviewedFix, SpellingFix, WordFix } from './validate';

export type Scope = 'app' | 'everywhere';

export const RESULT_HEADING = 'Feedback results';

export const SECTION_NOW = {
	title: '1 · Fix this text',
	scope: 'For this dictation only. Edit it if you like, then copy or paste it.',
} as const;

export const SECTION_NEXT = {
	title: '2 · Save changes for next time',
	scope: 'Saved changes apply from your next dictation. The text above stays as it is.',
} as const;

export const WHY_TITLE = 'Why it happened';

export const ORIGINAL_TEXT = 'Original text';

export const COPY_LABEL = 'Copy corrected text';
export const COPIED = 'Corrected text copied.';

const appOr = (appName: string | null | undefined) => appName?.trim() || 'the app';

/** The Paste button names the app it pastes into. */
export function pasteLabel(appName: string | null | undefined): string {
	return `Paste at cursor in ${appOr(appName)}`;
}

/** Always shown under the buttons: what Paste does, and what it doesn't. */
export function pasteHelp(appName: string | null | undefined): string {
	return `Returns to ${appOr(appName)} and pastes at its cursor. It won’t find or replace your earlier dictation automatically. If it can’t paste, it copies instead.`;
}

export function saveLabel(selected: number): string {
	return selected > 0 ? `Save selected changes (${selected})` : 'Save selected changes';
}

export const SELECT_HINT = 'Tick the changes you want, then save them.';

export type NextTimeState =
	| { kind: 'none' }
	| { kind: 'pending'; toSave: number; saved: number }
	| { kind: 'all-saved'; saved: number };

/** The keys of the changes saved now (applied and not undone). */
export function liveKeys(applied: readonly AppliedFix[] | undefined): Set<string> {
	return new Set((applied ?? []).filter((a) => !a.undoneAt).map((a) => a.key));
}

/** What section 2 holds: nothing, changes still to save, or all saved. */
export function nextTimeState(fixes: readonly ReviewedFix[], applied?: readonly AppliedFix[]): NextTimeState {
	if (fixes.length === 0) return { kind: 'none' };
	const live = liveKeys(applied);
	const saved = fixes.filter((f) => live.has(f.key)).length;
	if (saved === fixes.length) return { kind: 'all-saved', saved };
	return { kind: 'pending', toSave: fixes.length - saved, saved };
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

export const NOW_LINK = '1 · Corrected text';

/** The second link in the summary at the top. */
export function nextTimeLink(state: NextTimeState): string {
	switch (state.kind) {
		case 'none':
			return '2 · Next time: nothing to change';
		case 'all-saved':
			return '2 · Next time: all saved';
		case 'pending':
			return state.saved > 0
				? `2 · Next time: ${state.toSave} to save, ${state.saved} saved`
				: `2 · Next time: ${plural(state.toSave, 'change', 'changes')} to save`;
	}
}

export const EMPTY_NEXT_TIME = 'Nothing to change for next time.';
export const EMPTY_NEXT_TIME_WHY = 'No lasting change was suggested for this feedback, so nothing is saved.';
export const ALL_SAVED = 'All suggested changes are saved for next time.';

const q = (s: string) => `“${s}”`;

/** A change's one-line label, as its row shows it. */
export function fixLabel(fix: ReviewedFix, scope?: Scope): string {
	switch (fix.kind) {
		case 'spelling':
			return spellingLabel(fix);
		case 'word':
			return wordLabel(fix, scope ?? fix.defaultScope);
		default:
			return fix.label;
	}
}

export function spellingLabel(fix: SpellingFix): string {
	return `Always write ${q(fix.to)} for ${fix.from.map(q).join(' or ')}`;
}

export function wordLabel(fix: WordFix, scope: Scope): string {
	const choice = fix.choices[scope] ?? Object.values(fix.choices)[0];
	const added = choice?.added ?? [];
	if (fix.step === 'heard_as') return `Note that ${added.map(q).join(' or ')} is how ${q(fix.word)} comes out`;
	if (fix.step === 'add_word')
		return `Add ${q(fix.word)} to your words${added.length ? ` (heard as ${added.map(q).join(' or ')})` : ''}`;
	return `Give ${q(fix.word)} to the transcriber${fix.appName ? ` in ${fix.appName}` : ''}`;
}

/** Where a spelling or word change applies, in words (null for other kinds). */
export function scopeText(fix: ReviewedFix, scope: Scope | undefined): string | null {
	if (fix.kind !== 'spelling' && fix.kind !== 'word') return null;
	return (scope ?? fix.defaultScope) === 'app' ? `in ${fix.appName ?? 'this app'} only` : 'in every app';
}

/** The row's line once saved. */
export function savedLine(fix: ReviewedFix): string {
	return fix.kind === 'unspell' ? 'Spelling turned off for next time' : 'Saved for next time';
}

export type SavedChange = { fix: ReviewedFix; scope?: Scope };

/**
 * The changes one Save actually wrote: live in `after` and not in `before`.
 * Works on a partial save too (a failed writer keeps what was written).
 * A saved card keeps its review, so its label is the one that was ticked.
 */
export function newlySaved(before: FeedbackRecord | null, after: FeedbackRecord | null): SavedChange[] {
	if (!after?.result) return [];
	const was = liveKeys(before?.applied);
	const out: SavedChange[] = [];
	for (const a of after.applied ?? []) {
		if (a.undoneAt || was.has(a.key)) continue;
		const fix = after.result.fixes.find((f) => f.key === a.key);
		if (fix) out.push({ fix, ...(a.scope ? { scope: a.scope } : {}) });
	}
	return out;
}

/** One saved change, with where it applies. */
export function changeLine({ fix, scope }: SavedChange): string {
	const where = scopeText(fix, scope);
	return `${fixLabel(fix, scope)}${where ? ` (${where})` : ''}`;
}

export type Receipt = { head: string; lines: string[]; tail: string };

/** Shown in section 2 after Save: what changed, and how to take it back. */
export function savedReceipt(saved: readonly SavedChange[]): Receipt | null {
	if (saved.length === 0) return null;
	const one = saved.length === 1;
	return {
		head: one ? 'Saved for next time:' : `Saved ${saved.length} changes for next time:`,
		lines: saved.map(changeLine),
		tail: one
			? 'It applies from your next dictation. To take it back, press Undo next to it.'
			: 'They apply from your next dictation. To take one back, press Undo next to it.',
	};
}

/** After Undo, unless the engine has something more specific to say. */
export function undoneMessage(change: SavedChange | null): string {
	if (!change) return 'Undone.';
	if (change.fix.kind === 'unspell')
		return `Undone. The spelling ${q(change.fix.heard)} → ${q(change.fix.written)} is back on.`;
	return `Undone: ${changeLine(change)}. Your next dictation won’t use it.`;
}
