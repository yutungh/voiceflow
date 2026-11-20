/**
 * VoiceFlow 0.9.9: which styles get a chip ("Show on the bar" pins, else the
 * most used in the last 30 days), and how the chips wrap: whole names, at
 * most two rows, the style in use never pushed out.
 */
import { describe, expect, test } from 'bun:test';
import { RAW_STYLE_ID, type BarStyleOption } from './contract';
import {
	CHIP_NAME_MAX,
	chipNameCut,
	chipRow,
	layoutChips,
	nextKept,
	preferredStyleIds,
	styleUsage,
	type UsageEvent,
} from './chips';
import { createBarMachine } from './machine';
import { CHIPS_WIDTH } from './geometry';

const style = (id: string, name = id.toUpperCase()): BarStyleOption => ({ id, name, kind: 'custom' });
const RAW: BarStyleOption = { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };
// The Styles page's order.
const EIGHT = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((id) => style(id)).concat(RAW);
const ids = (list: BarStyleOption[]) => list.map((s) => s.id);
const DAY = 24 * 60 * 60 * 1000;
const NOW = 100 * DAY;

describe('pins: "Show on the bar"', () => {
	test('the pinned styles, in the Styles page order (not the order they were pinned)', () => {
		const preferred = preferredStyleIds(EIGHT, new Set(['f', 'b', RAW_STYLE_ID]), new Map());
		expect(preferred).toEqual(['b', 'f', RAW_STYLE_ID]);
		const row = chipRow(EIGHT, 'b', null, 5, preferred);
		expect(ids(row.chips)).toEqual(['b', 'f', RAW_STYLE_ID]);
		expect(ids(row.more)).toEqual(['a', 'c', 'd', 'e', 'g']);
	});

	test('at most five pinned styles show; usage never overrides a pin', () => {
		const pinned = new Set(['a', 'b', 'c', 'd', 'e', 'f']);
		const usage = new Map([['G', 99]]);
		expect(preferredStyleIds(EIGHT, pinned, usage)).toEqual(['a', 'b', 'c', 'd', 'e']);
	});

	test('a pin for a style that no longer exists is ignored', () => {
		expect(preferredStyleIds(EIGHT, new Set(['gone']), new Map([['C', 3]]))).toEqual([
			'a',
			'b',
			'c',
			'd',
			'e',
		]);
	});

	test('the style in use is always shown: after the pinned ones, or in the fifth place', () => {
		const two = preferredStyleIds(EIGHT, new Set(['a', 'b']), new Map());
		expect(ids(chipRow(EIGHT, 'g', null, 5, two).chips)).toEqual(['a', 'b', 'g']);
		const five = preferredStyleIds(EIGHT, new Set(['a', 'b', 'c', 'd', 'e']), new Map());
		expect(ids(chipRow(EIGHT, 'g', null, 5, five).chips)).toEqual(['a', 'b', 'c', 'd', 'g']);
		for (const current of ids(EIGHT)) {
			const row = chipRow(EIGHT, current, null, 5, two);
			expect(ids(row.chips)).toContain(current);
			expect([...ids(row.chips), ...ids(row.more)].sort()).toEqual(ids(EIGHT).sort());
		}
	});

	test('a style picked from More keeps its chip for the recording (with pins)', () => {
		const two = preferredStyleIds(EIGHT, new Set(['a', 'b']), new Map());
		let kept = nextKept(EIGHT, 'f', null, 5, two);
		expect(kept).toBe('f');
		kept = nextKept(EIGHT, 'a', kept, 5, two);
		expect(kept).toBe('f');
		expect(ids(chipRow(EIGHT, 'a', kept, 5, two).chips)).toEqual(['a', 'b', 'f']);
	});

	test('the engine shows the favourites it was given, and the hotkey cycles them', () => {
		const machine = createBarMachine({ now: () => 1 });
		machine.begin({
			recordingId: 'r',
			purpose: 'dictation',
			app: { name: null, icon: null },
			style: EIGHT[0]!,
			styles: EIGHT,
			preferred: ['a', 'c', 'g'],
		});
		expect(ids(machine.view()!.favourites)).toEqual(['a', 'c', 'g']);
		const seen: string[] = [];
		for (let i = 0; i < 3; i++) {
			machine.cycleStyle();
			seen.push(machine.view()!.style.id);
		}
		expect(seen).toEqual(['c', 'g', 'a']);
	});
});

describe('no pins: the five most used in the last 30 days', () => {
	const events = (list: Array<[string, number]>): UsageEvent[] =>
		list.map(([styleName, daysAgo]) => ({ styleName, at: NOW - daysAgo * DAY }));

	test('counts only the last 30 days', () => {
		const usage = styleUsage(events([['A', 1], ['A', 29], ['B', 31], ['C', 0]]), NOW);
		expect(usage.get('A')).toBe(2);
		expect(usage.has('B')).toBe(false);
		expect(usage.get('C')).toBe(1);
	});

	test('most used first; ties and unused styles in the Styles page order; shown in page order', () => {
		const usage = styleUsage(
			events([
				['G', 1],
				['G', 2],
				['G', 3],
				['F', 1],
				['F', 2],
				['D', 1],
				['B', 1],
			]),
			NOW,
		);
		// G 3, F 2, then D and B (1 each, page order: B before D), then A (unused, first).
		expect(preferredStyleIds(EIGHT, new Set(), usage)).toEqual(['a', 'b', 'd', 'f', 'g']);
	});

	test('nothing used yet: the first five in the Styles page order', () => {
		expect(preferredStyleIds(EIGHT, new Set(), new Map())).toEqual(['a', 'b', 'c', 'd', 'e']);
	});
});

describe('wrapping: whole names, at most two rows', () => {
	const W = CHIPS_WIDTH;

	test('five short chips and More fit one row', () => {
		const l = layoutChips({ widths: [64, 52, 62, 66, 56], moreWidth: 64, width: W, gap: 8, moreNeeded: true });
		expect(l).toEqual({ shown: [0, 1, 2, 3, 4], overflow: [], rows: 1, more: true });
	});

	test('long names wrap to a second row; nothing is cut or dropped', () => {
		// "Relaxed Format Text", "AI prompt", "Email", "Work chat", "Notes".
		const l = layoutChips({ widths: [150, 88, 64, 90, 68], moreWidth: 64, width: W, gap: 8, moreNeeded: true });
		expect(l.rows).toBe(2);
		expect(l.shown).toEqual([0, 1, 2, 3, 4]);
		expect(l.overflow).toEqual([]);
	});

	test('chips that would need a third row go to More, never the style in use', () => {
		const widths = [200, 190, 240, 170, 110];
		const l = layoutChips({ widths, moreWidth: 64, width: W, gap: 8, keep: 2, moreNeeded: false });
		expect(l.rows).toBe(2);
		expect(l.shown).toContain(2);
		expect(l.more).toBe(true);
		expect([...l.shown, ...l.overflow].sort()).toEqual([0, 1, 2, 3, 4]);
		// What is shown really fits in two rows, More included.
		const again = layoutChips({
			widths: l.shown.map((i) => widths[i]!),
			moreWidth: 64,
			width: W,
			gap: 8,
			moreNeeded: true,
		});
		expect(again.overflow).toEqual([]);
		expect(again.rows).toBeLessThanOrEqual(2);
	});

	test('at fractional sizes (150 % DPI) the same names still fit', () => {
		const l = layoutChips({ widths: [150.4, 88.6, 64.2, 90.7, 68.1], moreWidth: 64.5, width: W, gap: 8, moreNeeded: true });
		expect(l.overflow).toEqual([]);
		expect(l.rows).toBe(2);
	});

	test('only an absurdly long name may be cut', () => {
		expect(CHIP_NAME_MAX).toBe(32);
		expect(chipNameCut('Relaxed Format Text')).toBe(false);
		expect(chipNameCut('x'.repeat(32))).toBe(false);
		expect(chipNameCut('Formal Letters to the Homeowners Association Board')).toBe(true);
	});

	test('the page never cuts a chip short: chips do not shrink, and only .cut names end in an ellipsis', async () => {
		const { readFileSync } = await import('node:fs');
		const { join } = await import('node:path');
		const page = readFileSync(join(import.meta.dir, '../../routes/bar/+page.svelte'), 'utf8');
		expect(page).toMatch(/\.chip \{[^}]*flex: none;/);
		expect(page).toMatch(/\.chip-name \{\s*white-space: nowrap;\s*\}/);
		expect(page).toMatch(/\.chip-name\.cut \{[^}]*text-overflow: ellipsis;/);
		expect(page).toContain('class:cut={chipNameCut(style.name)}');
		// The chips wrap; the card's rows come from the measured layout.
		expect(page).toMatch(/\.chips \{[^}]*flex-wrap: wrap;/);
		expect(page).toContain('layoutChips({');
	});
});
