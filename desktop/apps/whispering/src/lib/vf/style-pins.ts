/**
 * VoiceFlow 0.9.9: the Styles page's order and its "Show on the bar" pins.
 * Pure, so it is unit tested; the Styles page and the bar both use it, so
 * they always agree on the order.
 *
 * - A style made on this PC keeps its pin with itself: `showOnBar: true` in
 *   its own file. A style from before 0.9.9 simply has none.
 * - A word-list style is read-only here (its lists own it) and Raw has no
 *   file, so their pins are kept on this PC in the `bar.pinnedStyles`
 *   setting: their bar ids (`profile:<id>`, `raw`), one per line.
 * - At most `MAX_PINS` styles are pinned; the bar shows them in the Styles
 *   page's order.
 */
import { MAX_CHIPS } from '../bar/chips';

export const MAX_PINS = MAX_CHIPS;

/**
 * This PC's styles in the Styles page's order: oldest first (so new and
 * built-in styles come after the ones already there), then by id. Files are
 * otherwise read in directory order, which no one chose.
 */
export function inPageOrder<T extends { id: string; createdAt: string }>(styles: readonly T[]): T[] {
	const time = (s: T) => {
		const at = Date.parse(s.createdAt);
		return Number.isFinite(at) ? at : 0;
	};
	return [...styles].sort((a, b) => time(a) - time(b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** The pins kept in the setting, as bar ids. */
export function barPinsOf(setting: string | null | undefined): string[] {
	if (!setting) return [];
	const ids = setting
		.split('\n')
		.map((id) => id.trim())
		.filter(Boolean);
	return ids.filter((id, i) => ids.indexOf(id) === i);
}

/** The setting's value with `id` pinned or not. */
export function withBarPin(setting: string | null | undefined, id: string, on: boolean): string {
	const ids = barPinsOf(setting).filter((x) => x !== id);
	if (on) ids.push(id);
	return ids.join('\n');
}

/**
 * Whether one more style may be pinned. `pinned`: the bar ids pinned now,
 * from both places; only those that still exist count.
 */
export function canPinMore(pinned: Iterable<string>, existing: ReadonlySet<string>): boolean {
	let count = 0;
	for (const id of new Set(pinned)) if (existing.has(id)) count += 1;
	return count < MAX_PINS;
}
