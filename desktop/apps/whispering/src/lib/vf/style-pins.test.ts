/** VoiceFlow 0.9.9: the Styles page's order and its "Show on the bar" pins. */
import { describe, expect, test } from 'bun:test';
import { MAX_PINS, barPinsOf, canPinMore, inPageOrder, withBarPin } from './style-pins';

describe('Styles page order', () => {
	test('oldest first, then by id; an unreadable date sorts first', () => {
		const styles = [
			{ id: 'vf-builtin-email', createdAt: '2026-11-18T10:00:00.000Z' },
			{ id: 'b', createdAt: '2026-06-07T13:38:52.201Z' },
			{ id: 'a', createdAt: '2026-06-07T13:38:52.201Z' },
			{ id: 'old', createdAt: 'not a date' },
		];
		expect(inPageOrder(styles).map((s) => s.id)).toEqual(['old', 'a', 'b', 'vf-builtin-email']);
	});
});

describe('pins kept in the setting (word-list styles and Raw)', () => {
	test('an old install has none', () => {
		expect(barPinsOf('')).toEqual([]);
		expect(barPinsOf(undefined)).toEqual([]);
	});

	test('pin and unpin by id, no duplicates', () => {
		let v = withBarPin('', 'raw', true);
		v = withBarPin(v, 'profile:work', true);
		v = withBarPin(v, 'raw', true);
		expect(barPinsOf(v)).toEqual(['profile:work', 'raw']);
		v = withBarPin(v, 'profile:work', false);
		expect(barPinsOf(v)).toEqual(['raw']);
	});

	test('at most five, counting only styles that still exist', () => {
		expect(MAX_PINS).toBe(5);
		const existing = new Set(['a', 'b', 'c', 'd', 'e', 'f']);
		expect(canPinMore(['a', 'b', 'c', 'd'], existing)).toBe(true);
		expect(canPinMore(['a', 'b', 'c', 'd', 'e'], existing)).toBe(false);
		expect(canPinMore(['a', 'b', 'c', 'd', 'gone'], existing)).toBe(true);
	});
});
