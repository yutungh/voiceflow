/**
 * VoiceFlow 0.9.8: the card's style chips (first five, the style in use
 * always shown, More for the rest) and the expression slider's mapping.
 */
import { describe, expect, test } from 'bun:test';
import { RAW_STYLE_ID, type BarStyleOption } from './contract';
import { MAX_CHIPS, chipRow, levelAt, nextKept, stopRatio } from './chips';
import { createBarMachine } from './machine';
import { EXPRESSION_LABELS } from './expression';

const style = (id: string): BarStyleOption => ({ id, name: id, kind: 'custom' });
const RAW: BarStyleOption = { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };
// The Styles page's order: word-list styles, this PC's, then Raw.
const SEVEN = ['a', 'b', 'c', 'd', 'e', 'f'].map(style).concat(RAW);
const ids = (list: BarStyleOption[]) => list.map((s) => s.id);

describe('chip row', () => {
	test('five or fewer styles: all of them, in order, no More', () => {
		const five = ['a', 'b', 'c', 'd'].map(style).concat(RAW);
		expect(ids(chipRow(five, 'b').chips)).toEqual(['a', 'b', 'c', 'd', 'raw']);
		expect(chipRow(five, 'b').more).toEqual([]);
		expect(ids(chipRow([RAW], 'raw').chips)).toEqual(['raw']);
		expect(chipRow([], 'x')).toEqual({ chips: [], more: [] });
	});

	test('more than five: the first five in the Styles page order, the rest in More', () => {
		const row = chipRow(SEVEN, 'a');
		expect(MAX_CHIPS).toBe(5);
		expect(ids(row.chips)).toEqual(['a', 'b', 'c', 'd', 'e']);
		expect(ids(row.more)).toEqual(['f', 'raw']);
	});

	test('the style in use is always shown: it takes the fifth place', () => {
		expect(ids(chipRow(SEVEN, 'raw').chips)).toEqual(['a', 'b', 'c', 'd', 'raw']);
		expect(ids(chipRow(SEVEN, 'raw').more)).toEqual(['e', 'f']);
		expect(ids(chipRow(SEVEN, 'f').chips)).toEqual(['a', 'b', 'c', 'd', 'f']);
		// In the first four: nothing moves.
		expect(ids(chipRow(SEVEN, 'c').chips)).toEqual(['a', 'b', 'c', 'd', 'e']);
		// Every style is either a chip or in More, exactly once.
		for (const current of ids(SEVEN)) {
			const row = chipRow(SEVEN, current);
			expect(ids(row.chips)).toContain(current);
			expect([...ids(row.chips), ...ids(row.more)].sort()).toEqual(ids(SEVEN).sort());
		}
	});

	test('a style picked from More keeps the fifth place for that recording', () => {
		// Picked "f" from More: it shows in the fifth place.
		let kept = nextKept(SEVEN, 'f', null);
		expect(kept).toBe('f');
		expect(ids(chipRow(SEVEN, 'f', kept).chips)).toEqual(['a', 'b', 'c', 'd', 'f']);
		// Then "b" chip: "f" stays, so the row doesn't reshuffle under the pointer.
		kept = nextKept(SEVEN, 'b', kept);
		expect(kept).toBe('f');
		expect(ids(chipRow(SEVEN, 'b', kept).chips)).toEqual(['a', 'b', 'c', 'd', 'f']);
		expect(ids(chipRow(SEVEN, 'b', kept).more)).toEqual(['e', 'raw']);
		// Then Raw from More: Raw takes it.
		kept = nextKept(SEVEN, 'raw', kept);
		expect(ids(chipRow(SEVEN, 'raw', kept).chips)).toEqual(['a', 'b', 'c', 'd', 'raw']);
		// A kept id that no longer exists is ignored.
		expect(ids(chipRow(SEVEN, 'a', 'gone').chips)).toEqual(['a', 'b', 'c', 'd', 'e']);
		// Five or fewer: nothing to pin.
		expect(nextKept(SEVEN.slice(0, 5), 'e', null)).toBeNull();
	});

	test('a duplicate id shows once', () => {
		const dup = [style('a'), style('a'), style('b')];
		expect(ids(chipRow(dup, 'a').chips)).toEqual(['a', 'b']);
	});

	test('the engine sends the chips, and the style hotkey cycles the ones shown', () => {
		const machine = createBarMachine({ now: () => 1 });
		machine.begin({
			recordingId: 'r',
			purpose: 'dictation',
			app: { name: null, icon: null },
			style: SEVEN[0]!,
			styles: SEVEN,
		});
		const chips = () => ids(machine.view()!.favourites);
		expect(chips()).toEqual(['a', 'b', 'c', 'd', 'e']);
		// "f" picked from More takes the fifth chip...
		machine.command({ type: 'set-style', recordingId: 'r', styleId: 'f' });
		expect(chips()).toEqual(['a', 'b', 'c', 'd', 'f']);
		// ...and keeps it after another chip is picked.
		machine.command({ type: 'set-style', recordingId: 'r', styleId: 'b' });
		expect(chips()).toEqual(['a', 'b', 'c', 'd', 'f']);
		// The hotkey goes through what is shown: c, d, f, a (never the hidden e).
		const seen: string[] = [];
		for (let i = 0; i < 4; i++) {
			machine.cycleStyle();
			seen.push(machine.view()!.style.id);
		}
		expect(seen).toEqual(['c', 'd', 'f', 'a']);
		expect(chips()).toEqual(['a', 'b', 'c', 'd', 'f']);
		// A recording that starts on a style outside the first five shows it fifth.
		machine.begin({
			recordingId: 's',
			purpose: 'dictation',
			app: { name: null, icon: null },
			style: RAW,
			styles: SEVEN,
		});
		expect(chips()).toEqual(['a', 'b', 'c', 'd', 'raw']);
	});
});

describe('expression slider', () => {
	test('five stops, evenly spaced, Reserved to Expressive', () => {
		expect(EXPRESSION_LABELS).toEqual(['Reserved', 'Subtle', 'Natural', 'Lively', 'Expressive']);
		expect([0, 1, 2, 3, 4].map(stopRatio)).toEqual([0, 0.25, 0.5, 0.75, 1]);
		// Out of range clamps; non-numbers are Natural.
		expect(stopRatio(9)).toBe(1);
		expect(stopRatio(-3)).toBe(0);
		expect(stopRatio(Number.NaN)).toBe(0.5);
	});

	test('a pointer maps to the nearest stop (it snaps)', () => {
		// A 200 px track from x = 100.
		const at = (x: number) => levelAt(x, 100, 200);
		expect(at(100)).toBe(0);
		expect(at(124)).toBe(0);
		expect(at(126)).toBe(1);
		expect(at(150)).toBe(1);
		expect(at(174)).toBe(1);
		expect(at(176)).toBe(2);
		expect(at(200)).toBe(2);
		expect(at(250)).toBe(3);
		expect(at(300)).toBe(4);
		// Every stop's own position gives that stop back.
		for (const level of [0, 1, 2, 3, 4]) expect(at(100 + stopRatio(level) * 200)).toBe(level);
	});

	test('dragging past either end clamps; a broken track never throws', () => {
		expect(levelAt(-500, 100, 200)).toBe(0);
		expect(levelAt(9_000, 100, 200)).toBe(4);
		expect(levelAt(150, 100, 0)).toBe(0);
		expect(levelAt(Number.NaN, 100, 200)).toBe(0);
	});
});
