import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type } from 'arktype';
import { DEFAULT_SOUND_SET, DEFAULT_SOUND_VOLUME } from '../constants/sounds/sound-sets';
import { migrateSoundSettings, migrateStoredSoundSettings, SOUND_SETTINGS } from './sound-schema';

/**
 * The sound keys as `parseStoredSettings` (settings.ts) reads them: its first
 * step is the schema itself, which fills a missing key with its default and
 * keeps every valid stored value. settings.ts imports `$lib` modules that
 * `bun test` cannot resolve, so the sound part is tested on its own.
 */
const SoundSettings = type(SOUND_SETTINGS);

function parse(stored: Record<string, unknown>) {
	const result = SoundSettings(stored);
	if (result instanceof type.errors) throw new Error(result.summary);
	return result;
}

const source = () => readFileSync(join(import.meta.dir, 'settings.ts'), 'utf8');

/** What 0.9.4 and earlier saved: the eight event switches, nothing else. */
function savedBefore095(over: Record<string, boolean> = {}) {
	return {
		'sound.playOn.manual-start': true,
		'sound.playOn.manual-stop': true,
		'sound.playOn.manual-cancel': true,
		'sound.playOn.vad-start': true,
		'sound.playOn.vad-capture': true,
		'sound.playOn.vad-stop': true,
		'sound.playOn.transcriptionComplete': true,
		'sound.playOn.transformationComplete': true,
		...over,
	};
}

describe('sound settings (VoiceFlow 0.9.6)', () => {
	test('settings.ts uses this schema', () => {
		expect(source()).toContain('...SOUND_SETTINGS,');
		expect(source()).not.toContain("'sound.playOn.manual-start': 'boolean");
	});

	test('new installs get the default set, volume and every sound on', () => {
		const s = parse({});
		expect(s['sound.set']).toBe(DEFAULT_SOUND_SET);
		expect(s['sound.volume']).toBe(DEFAULT_SOUND_VOLUME);
		expect(s['sound.playOn.manual-start']).toBe(true);
		expect(s['sound.playOn.error']).toBe(true);
	});

	test('an existing user keeps their per-event switches and gets the default set', () => {
		const s = parse(
			savedBefore095({
				'sound.playOn.manual-start': false,
				'sound.playOn.transcriptionComplete': false,
			}),
		);
		expect(s['sound.playOn.manual-start']).toBe(false);
		expect(s['sound.playOn.transcriptionComplete']).toBe(false);
		expect(s['sound.playOn.manual-stop']).toBe(true);
		expect(s['sound.set']).toBe(DEFAULT_SOUND_SET);
		expect(s['sound.volume']).toBe(DEFAULT_SOUND_VOLUME);
		expect(s['sound.playOn.error']).toBe(true);
	});

	test('a user who turned every sound off stays silent, the new error sound too', () => {
		const allOff = Object.fromEntries(
			Object.keys(savedBefore095()).map((k) => [k, false]),
		);
		const s = parse(migrateSoundSettings(savedBefore095(allOff)));
		for (const key of Object.keys(allOff)) expect(s[key as keyof typeof s]).toBe(false);
		expect(s['sound.playOn.error']).toBe(false);
		expect(source()).toContain('migrateSoundSettings(migrated)');
	});

	test('the all-off rule touches nothing else', () => {
		const some = savedBefore095({ 'sound.playOn.manual-start': false });
		expect(migrateSoundSettings(some)).toBe(some);
		const fresh = {};
		expect(migrateSoundSettings(fresh)).toBe(fresh);
		// Already on 0.9.6: the user's own error switch stands.
		const current = { ...savedBefore095(), 'sound.set': 'glass', 'sound.playOn.error': true };
		expect(migrateSoundSettings(current)).toBe(current);
	});

	test('the store migrates saved settings before reading them (they pass the schema as they are)', () => {
		const store = readFileSync(join(import.meta.dir, '../stores/settings.svelte.ts'), 'utf8');
		const migrate = store.indexOf("migrateStoredSoundSettings(window.localStorage, 'whispering-settings')");
		expect(migrate).toBeGreaterThan(-1);
		expect(migrate).toBeLessThan(store.indexOf('createPersistedState({'));

		const memory = (value: string | null) => {
			const writes: string[] = [];
			return {
				writes,
				getItem: () => value,
				setItem: (_k: string, v: string) => {
					writes.push(v);
				},
			};
		};
		const allOff = Object.fromEntries(Object.keys(savedBefore095()).map((k) => [k, false]));
		const off = memory(JSON.stringify({ other: 1, ...allOff }));
		expect(migrateStoredSoundSettings(off, 'whispering-settings')).toBe(true);
		expect(JSON.parse(off.writes[0] as string)).toEqual({ other: 1, ...allOff, 'sound.playOn.error': false });
		expect(parse(JSON.parse(off.writes[0] as string))['sound.playOn.error']).toBe(false);

		for (const untouched of [JSON.stringify(savedBefore095()), null, '', 'not json', '[1]', '"x"']) {
			const m = memory(untouched);
			expect(migrateStoredSoundSettings(m, 'whispering-settings')).toBe(false);
			expect(m.writes).toEqual([]);
		}
		// Second run: already migrated, nothing written again.
		const again = memory(off.writes[0] as string);
		expect(migrateStoredSoundSettings(again, 'whispering-settings')).toBe(false);
	});

	test('the default is applied once: a chosen set and volume are kept', () => {
		const first = parse(savedBefore095());
		const chosen = parse({ ...first, 'sound.set': 'off', 'sound.volume': 0.3 });
		expect(chosen['sound.set']).toBe('off');
		expect(chosen['sound.volume']).toBe(0.3);
		expect(parse({ ...first, 'sound.set': 'minimal' })['sound.set']).toBe('minimal');
	});

	test('an unknown set or an out-of-range volume is rejected (settings.ts then uses the default for that key only)', () => {
		expect(SoundSettings({ 'sound.set': 'zapsplat' }) instanceof type.errors).toBe(true);
		expect(SoundSettings({ 'sound.volume': 7 }) instanceof type.errors).toBe(true);
		expect(SoundSettings({ 'sound.volume': -0.1 }) instanceof type.errors).toBe(true);
		expect(SoundSettings({ 'sound.volume': 0 }) instanceof type.errors).toBe(false);
	});
});
