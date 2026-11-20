import { describe, expect, test } from 'bun:test';
import { createPins, PIN_EXPIRY_MS } from './pins';
import type { ProfileSnapshot } from './types';

const snap = (id: string) => ({ id }) as ProfileSnapshot;

function setup() {
	let current: ProfileSnapshot | null = snap('v1');
	let enabled = true;
	let clock = 1_000;
	const busy: boolean[] = [];
	const pins = createPins({
		current: () => current,
		enabled: () => enabled,
		setBusy: (b) => busy.push(b),
		now: () => clock,
	});
	return {
		pins,
		busy,
		set: (s: ProfileSnapshot | null) => (current = s),
		off: () => (enabled = false),
		tick: (ms: number) => (clock += ms),
	};
}

describe('snapshot pinning', () => {
	test('a dictation keeps the snapshot it started with across a refresh', () => {
		const t = setup();
		const pin = t.pins.pin();
		t.pins.bind('rec1', pin);
		t.set(snap('v2')); // refreshed while rec1 was being transcribed
		expect(t.pins.snapshotFor('rec1')?.id).toBe('v1');
		expect(t.pins.snapshotFor('rec2')?.id).toBe('v2');
		expect(t.pins.snapshotFor(null)?.id).toBe('v2');
	});

	test('busy while any dictation runs; idle when the last one ends', () => {
		const t = setup();
		const a = t.pins.pin();
		const b = t.pins.pin();
		t.pins.release(a);
		expect(t.busy).toEqual([true, true]);
		t.pins.release(b);
		expect(t.busy).toEqual([true, true, false]);
		// Releasing twice changes nothing.
		t.pins.release(b);
		expect(t.busy).toEqual([true, true, false]);
	});

	test('a pin never released stops counting after the expiry', () => {
		const t = setup();
		t.pins.pin(); // lost (crash mid-dictation)
		t.tick(PIN_EXPIRY_MS + 1);
		const next = t.pins.pin();
		expect(t.pins.running).toBe(1);
		t.pins.release(next);
		expect(t.busy.at(-1)).toBe(false);
	});

	test('word lists off: no snapshot, the device settings apply', () => {
		const t = setup();
		const pin = t.pins.pin();
		t.pins.bind('rec1', pin);
		t.off();
		expect(t.pins.snapshotFor('rec1')).toBeNull();
	});

	test('a pin taken with word lists off falls back to the current snapshot', () => {
		const t = setup();
		t.set(null);
		const pin = t.pins.pin();
		t.pins.bind('rec1', pin);
		t.set(snap('v3'));
		expect(t.pins.snapshotFor('rec1')?.id).toBe('v3');
	});
});
