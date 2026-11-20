import { describe, expect, test } from 'bun:test';
import type { ProfileSnapshot, ProfileStyle } from '$lib/profile/types';
import type { BarStyleOption } from './contract';
import { appRuleStyleId, barStyleChoice, defaultStyleId, profileStyleOptions, targetAppIds } from './styles';

function style(id: string, label: string, extra: Partial<ProfileStyle> = {}): ProfileStyle {
	return { id, label, prompt: `Tidy as ${label}.`, layer: 'personal', ...extra };
}

function snapshot(extra: Partial<ProfileSnapshot> = {}): ProfileSnapshot {
	return {
		id: 'snap-1',
		user: 'sam',
		terms: [],
		corrections: [],
		styles: [style('work', 'Work', { expression: 1 }), style('partner', 'Sam')],
		defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {} },
		privacy: {},
		apps: [],
		issues: [],
		...extra,
	};
}

const DEVICE: BarStyleOption[] = [
	{ id: 'dev-1', name: 'Friends', kind: 'casual' },
	{ id: 'dev-2', name: 'Notes', kind: 'custom' },
];
const KITE = { exeName: 'Kite.exe', bundleId: null };

describe('bar styles from the word lists', () => {
	test('word lists off: this PC styles, its default, Raw last', () => {
		const out = barStyleChoice({ target: KITE, snapshot: null, deviceStyles: DEVICE, deviceDefaultId: 'dev-2' });
		expect(out.style.id).toBe('dev-2');
		expect(out.styles.map((s) => s.id)).toEqual(['dev-1', 'dev-2', 'raw']);
	});

	test('word lists on: their styles first (stable ids, preset kind, own expression)', () => {
		const out = barStyleChoice({ target: KITE, snapshot: snapshot(), deviceStyles: DEVICE, deviceDefaultId: null });
		expect(out.styles.map((s) => s.id)).toEqual(['profile:work', 'profile:partner', 'dev-1', 'dev-2', 'raw']);
		expect(out.styles[0]).toEqual({ id: 'profile:work', name: 'Work', kind: 'business', expression: 1 });
		// The kind comes from the stable id even when the label is a name.
		expect(out.styles[1]?.kind).toBe('partner');
		expect(out.style.id).toBe('raw');
	});

	test('the default style comes from the pinned snapshot when it sets one', () => {
		const snap = snapshot({ defaults: { ...snapshot().defaults, cleanupStyle: 'partner' } });
		expect(defaultStyleId(snap, 'dev-1')).toBe('profile:partner');
		expect(defaultStyleId({ ...snap, defaults: { ...snap.defaults, cleanupStyle: 'raw' } }, 'dev-1')).toBeNull();
		expect(defaultStyleId(snapshot(), 'dev-1')).toBe('dev-1');
		expect(barStyleChoice({ target: KITE, snapshot: snap, deviceStyles: DEVICE, deviceDefaultId: 'dev-1' }).style.id).toBe(
			'profile:partner',
		);
	});

	test('an app rule picks the starting style for that app only', () => {
		const snap = snapshot({
			apps: [
				{ id: 'kite.exe', style: 'work' },
				{ id: 'telegram.exe', style: 'raw' },
				{ id: 'notes.exe', style: 'missing' },
				{ id: 'teams.exe', terms: [] },
			],
		});
		const pick = (exeName: string) =>
			barStyleChoice({ target: { exeName, bundleId: null }, snapshot: snap, deviceStyles: DEVICE, deviceDefaultId: 'dev-1' })
				.style.id;
		expect(pick('Kite.exe')).toBe('profile:work');
		expect(pick('Telegram.exe')).toBe('raw');
		// A rule naming a style the lists don't have, or no style: the default.
		expect(pick('notes.exe')).toBe('dev-1');
		expect(pick('teams.exe')).toBe('dev-1');
		expect(pick('other.exe')).toBe('dev-1');
		expect(appRuleStyleId(snap, [])).toBeNull();
		expect(appRuleStyleId(null, ['kite.exe'])).toBeNull();
	});

	test('a default not loaded yet keeps its id so the dictation is still tidied with it', () => {
		const out = barStyleChoice({ target: KITE, snapshot: null, deviceStyles: [], deviceDefaultId: 'dev-9' });
		expect(out.style).toEqual({ id: 'dev-9', name: 'Default style', kind: 'custom' });
		expect(out.styles.map((s) => s.id)).toEqual(['dev-9', 'raw']);
	});

	test('app ids: the exe name and the bundle id, lower case', () => {
		expect(targetAppIds({ exeName: 'Kite.EXE', bundleId: null })).toEqual(['kite.exe']);
		expect(targetAppIds({ exeName: null, bundleId: 'com.example.Notes' })).toEqual(['com.example.notes']);
		expect(profileStyleOptions(null)).toEqual([]);
	});

	test('macOS: a rule keyed by the bundle id matches although the exe name is also known', () => {
		const snap = snapshot({ apps: [{ id: 'com.apple.textedit', style: 'work' }] });
		const out = barStyleChoice({
			target: { exeName: 'TextEdit', bundleId: 'com.apple.TextEdit' },
			snapshot: snap,
			deviceStyles: DEVICE,
			deviceDefaultId: 'dev-1',
		});
		expect(out.style.id).toBe('profile:work');
	});
});
