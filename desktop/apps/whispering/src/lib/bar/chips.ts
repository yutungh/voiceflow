/**
 * VoiceFlow 0.9.8: which style chips the recording card shows, and the
 * expression slider's mapping. Pure, so it is unit tested; `/bar` draws them.
 *
 * Chips: at most five, always in the order the Styles page lists them (the
 * word lists' styles, then this PC's, then Raw). Which five (0.9.9):
 * - the styles marked "Show on the bar" on the Styles page (`preferred`);
 * - with none marked, the five used most in the last 30 days of History,
 *   then the Styles page's order for ties and for styles never used;
 * - with nothing known (no `preferred`), the first five.
 *
 * The style in use is always one of them: when it is not among the
 * favourites it takes the fifth place (or the next free one). A style picked
 * from "More" keeps that place for the rest of that recording ("kept"), even
 * after another chip is picked, so the row doesn't reshuffle under the
 * pointer. The rest are in the "More" list.
 */
import type { BarStyleOption } from './contract';
import { EXPRESSION_EXPRESSIVE, EXPRESSION_RESERVED, sanitizeExpression } from './expression';

export const MAX_CHIPS = 5;
/** Usage counts from this many days of History (0.9.9). */
export const USAGE_DAYS = 30;

export type ChipRow = {
	/** The chips, in order (at most `max`). */
	chips: BarStyleOption[];
	/** Everything else, in the Styles page's order: the "More" list. */
	more: BarStyleOption[];
};

function uniqueOf(styles: readonly BarStyleOption[]): BarStyleOption[] {
	return styles.filter((s, i) => styles.findIndex((o) => o.id === s.id) === i);
}

/** The favourites before the style in use is added: page order, at most `max`. */
function favouritesOf(
	unique: readonly BarStyleOption[],
	preferred: readonly string[] | null,
	max: number,
): BarStyleOption[] {
	// 0.9.13: in the order given (shared pins keep the word lists' order).
	const list = preferred
		? preferred
				.map((id) => unique.find((s) => s.id === id))
				.filter((s): s is BarStyleOption => !!s)
		: unique;
	return list.slice(0, max);
}

/**
 * The chip row for a recording.
 * - `styles`: every style, in the Styles page's order.
 * - `currentId`: the style in use (always shown).
 * - `keptId`: a style picked from More earlier in this recording.
 * - `preferred`: the favourites' ids (`preferredStyleIds`); null: the first five.
 */
export function chipRow(
	styles: readonly BarStyleOption[],
	currentId: string,
	keptId: string | null = null,
	max = MAX_CHIPS,
	preferred: readonly string[] | null = null,
): ChipRow {
	const unique = uniqueOf(styles);
	const fav = favouritesOf(unique, preferred, max);
	const full = fav.length >= max;
	const head = full ? fav.slice(0, max - 1) : fav;
	const inHead = (id: string | null) => !!id && head.some((s) => s.id === id);
	const find = (id: string | null) => (id ? unique.find((s) => s.id === id) : undefined);
	const last =
		(!inHead(currentId) ? find(currentId) : undefined) ??
		(!inHead(keptId) ? find(keptId) : undefined) ??
		(full ? fav[max - 1] : undefined);
	// The favourites in the Styles page's order; a guest (the style in use, or
	// one kept from More) always last, so the others never move under the pointer.
	const chips = last ? [...head, last] : head;
	return { chips, more: unique.filter((s) => !chips.some((c) => c.id === s.id)) };
}

/**
 * The style that keeps its chip after a change: the style in use when it
 * is not one of the favourites' fixed places, else whatever was kept
 * before. Reset per recording by the engine.
 */
export function nextKept(
	styles: readonly BarStyleOption[],
	currentId: string,
	keptId: string | null,
	max = MAX_CHIPS,
	preferred: readonly string[] | null = null,
): string | null {
	const unique = uniqueOf(styles);
	const fav = favouritesOf(unique, preferred, max);
	// Every style is a chip: nothing to keep.
	if (fav.length === unique.length && fav.length <= max) return null;
	const head = fav.length >= max ? fav.slice(0, max - 1) : fav;
	return head.some((s) => s.id === currentId) ? keptId : currentId;
}

/** A dictation in History, as far as usage counting needs it. */
export type UsageEvent = { styleName: string; at: number };

/** How often each style (by name) was used in the last `days` days. */
export function styleUsage(
	events: readonly UsageEvent[],
	now: number,
	days = USAGE_DAYS,
): Map<string, number> {
	const since = now - days * 24 * 60 * 60 * 1000;
	const counts = new Map<string, number>();
	for (const e of events) {
		if (!e.styleName || !(e.at >= since) || e.at > now + 60_000) continue;
		counts.set(e.styleName, (counts.get(e.styleName) ?? 0) + 1);
	}
	return counts;
}

/**
 * The favourites' ids, in the Styles page's order (0.9.9):
 * - the pinned styles ("Show on the bar"), at most `max`;
 * - none pinned: the `max` most used (`usage`, by style name), ties and
 *   unused styles in the Styles page's order.
 */
export function preferredStyleIds(
	styles: readonly BarStyleOption[],
	pinned: ReadonlySet<string>,
	usage: ReadonlyMap<string, number>,
	max = MAX_CHIPS,
	/** 0.9.13: the word lists' pins, first and in their own order. */
	first: readonly string[] = [],
): string[] {
	const unique = uniqueOf(styles);
	const shared = first.filter((id, i) => first.indexOf(id) === i && unique.some((s) => s.id === id));
	const marked = unique.filter((s) => pinned.has(s.id) && !shared.includes(s.id)).map((s) => s.id);
	if (shared.length + marked.length > 0) return [...shared, ...marked].slice(0, max);
	const ranked = unique
		.map((s, index) => ({ s, index, uses: usage.get(s.name) ?? 0 }))
		.sort((a, b) => b.uses - a.uses || a.index - b.index)
		.slice(0, max)
		.sort((a, b) => a.index - b.index);
	return ranked.map((r) => r.s.id);
}

/** A style name longer than this may be cut on its chip (full name in the tooltip). */
export const CHIP_NAME_MAX = 32;

/** True when a chip may cut its name (only an absurdly long one). */
export function chipNameCut(name: string): boolean {
	return name.trim().length > CHIP_NAME_MAX;
}

export type ChipLayout = {
	/** The chips that fit, by index into the input, in order. */
	shown: number[];
	/** The chips that would need a third row: they go to More. */
	overflow: number[];
	/** Chip rows used (1 or 2; 1 when there is nothing). */
	rows: number;
	/** Whether the More chip is drawn. */
	more: boolean;
};

/**
 * 0.9.9: lays the chips out in at most `maxRows` rows of `width` px, each
 * chip at its natural width (names are never cut short), with the More chip
 * at the end of the last row when there is anything in More. Chips that do
 * not fit go to More, last first, but never the style in use (`keep`).
 * Pure: the page measures the widths and draws what this returns.
 */
export function layoutChips({
	widths,
	moreWidth,
	width,
	gap,
	maxRows = 2,
	keep = -1,
	moreNeeded = false,
}: {
	widths: readonly number[];
	moreWidth: number;
	width: number;
	gap: number;
	maxRows?: number;
	/** The index of the style in use, never moved to More. */
	keep?: number;
	/** Something is in More already (styles beyond the chips). */
	moreNeeded?: boolean;
}): ChipLayout {
	const fits = (indices: number[], withMore: boolean): number => {
		// Rows used by these chips (and More at the end), or Infinity.
		let rows = 1;
		let used = 0;
		const items = indices.map((i) => Math.min(widths[i] ?? 0, width));
		if (withMore) items.push(Math.min(moreWidth, width));
		for (const w of items) {
			const next = used === 0 ? w : used + gap + w;
			if (next <= width + 0.5) used = next;
			else {
				rows += 1;
				used = w;
			}
		}
		return rows;
	};
	let shown = widths.map((_, i) => i);
	const overflow: number[] = [];
	let withMore = moreNeeded;
	let rows = fits(shown, withMore);
	while (rows > maxRows && shown.length > 0) {
		// Move the last chip that is not the style in use to More.
		let at = shown.length - 1;
		while (at >= 0 && shown[at] === keep) at -= 1;
		if (at < 0) break;
		overflow.unshift(shown[at]!);
		shown = shown.filter((_, i) => i !== at);
		withMore = true;
		rows = fits(shown, withMore);
	}
	return { shown, overflow: overflow.sort((a, b) => a - b), rows: Math.min(rows, maxRows), more: withMore };
}

/** The slider's levels, left to right. */
export const SLIDER_STOPS = EXPRESSION_EXPRESSIVE - EXPRESSION_RESERVED;

/**
 * The level for a pointer at `x` over a track from `left` that is `width`
 * wide: the nearest of the five stops (it snaps), clamped at both ends.
 */
export function levelAt(x: number, left: number, width: number): number {
	if (!(width > 0) || !Number.isFinite(x) || !Number.isFinite(left)) return EXPRESSION_RESERVED;
	const ratio = Math.max(0, Math.min(1, (x - left) / width));
	return sanitizeExpression(EXPRESSION_RESERVED + ratio * SLIDER_STOPS);
}

/** Where a level's stop sits on the track, 0..1 from the left. */
export function stopRatio(level: number): number {
	return (sanitizeExpression(level) - EXPRESSION_RESERVED) / SLIDER_STOPS;
}
