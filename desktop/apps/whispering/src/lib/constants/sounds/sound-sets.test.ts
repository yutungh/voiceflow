import { describe, expect, test } from 'bun:test';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
	CUE_OF,
	DEFAULT_SOUND_SET,
	EVENTS_OF_CUE,
	nativeSoundConfig,
	SOUND_CUES,
	SOUND_SETS,
	type SoundSettings,
	soundFor,
} from './sound-sets';
import type { WhisperingSoundNames } from './sound-names';

const ALL_EVENTS = Object.keys(CUE_OF) as WhisperingSoundNames[];

function soundSettings(over: Partial<SoundSettings> = {}): SoundSettings {
	const base = Object.fromEntries(ALL_EVENTS.map((n) => [`sound.playOn.${n}`, true]));
	return {
		...base,
		'sound.set': 'glass',
		'sound.volume': 0.8,
		...over,
	} as SoundSettings;
}

describe('sound mapping', () => {
	test('every event plays one of the five cues', () => {
		for (const name of ALL_EVENTS) expect(SOUND_CUES).toContain(CUE_OF[name]);
		expect(CUE_OF['manual-start']).toBe('start');
		expect(CUE_OF['manual-stop']).toBe('stop');
		expect(CUE_OF['manual-cancel']).toBe('cancel');
		expect(CUE_OF.transcriptionComplete).toBe('done');
		expect(CUE_OF.transformationComplete).toBe('done');
		expect(CUE_OF.error).toBe('error');
	});

	test("each cue's switch covers exactly the events that play it", () => {
		const covered = SOUND_CUES.flatMap((cue) => EVENTS_OF_CUE[cue]);
		expect([...covered].sort()).toEqual([...ALL_EVENTS].sort());
		for (const cue of SOUND_CUES)
			for (const name of EVENTS_OF_CUE[cue]) expect(CUE_OF[name]).toBe(cue);
	});

	test('Done is on when either completion switch is on (one sound per dictation)', () => {
		const mixed = soundSettings({ 'sound.playOn.transformationComplete': false });
		expect(soundFor('transformationComplete', mixed)?.cue).toBe('done');
		const bothOff = soundSettings({
			'sound.playOn.transcriptionComplete': false,
			'sound.playOn.transformationComplete': false,
		});
		expect(soundFor('transformationComplete', bothOff)).toBeNull();
	});

	test('the chosen set and volume are played', () => {
		expect(soundFor('manual-start', soundSettings({ 'sound.set': 'soft' }))).toEqual({
			set: 'soft',
			cue: 'start',
			volume: 0.8,
		});
	});

	test('Off, a switched-off event or zero volume plays nothing', () => {
		expect(soundFor('manual-start', soundSettings({ 'sound.set': 'off' }))).toBeNull();
		const stopOff = soundSettings({
			'sound.playOn.manual-stop': false,
			'sound.playOn.vad-stop': false,
			'sound.playOn.vad-capture': false,
		});
		expect(soundFor('manual-stop', stopOff)).toBeNull();
		expect(soundFor('error', soundSettings({ 'sound.volume': 0 }))).toBeNull();
	});

	test('Rust gets the set, volume and the three dictation toggles', () => {
		expect(
			nativeSoundConfig(
				soundSettings({
					'sound.set': 'minimal',
					'sound.volume': 0.5,
					'sound.playOn.manual-cancel': false,
				}),
			),
		).toEqual({ set: 'minimal', volume: 0.5, start: true, stop: true, cancel: false });
	});

	test('every set has a WAV for every cue (the files Rust embeds)', () => {
		const assets = join(import.meta.dir, '../../services/isomorphic/sound/assets');
		for (const set of SOUND_SETS)
			for (const cue of SOUND_CUES) {
				const file = join(assets, set, `${cue}.wav`);
				expect(existsSync(file)).toBe(true);
				const header = readFileSync(file).subarray(0, 44);
				expect(header.toString('ascii', 0, 4)).toBe('RIFF');
				expect(header.readUInt16LE(22)).toBe(1); // mono
				expect(header.readUInt32LE(24)).toBe(48_000);
			}
	});

	test('the committed WAVs are exactly what the generator makes, loudness-matched', async () => {
		// desktop/tools/sounds/generate.mjs: deterministic, no samples.
		// Loaded by URL: the tool is not part of the app's typecheck.
		const tool = join(import.meta.dir, '../../../../../../tools/sounds/generate.mjs');
		const gen = await import(pathToFileURL(tool).href);
		const assets = join(import.meta.dir, '../../services/isomorphic/sound/assets');
		const clips = gen.renderAll();
		for (const set of SOUND_SETS)
			for (const cue of SOUND_CUES) {
				const samples = clips[set][cue];
				const ms = (samples.length / gen.SR) * 1000;
				expect(ms).toBeGreaterThanOrEqual(80);
				expect(ms).toBeLessThanOrEqual(gen.MAX_MS);
				expect(Math.abs(gen.momentaryLufs(samples) - gen.TARGET_LUFS)).toBeLessThan(0.5);
				expect(gen.peakDb(samples)).toBeLessThanOrEqual(gen.PEAK_CEILING_DB + 0.01);
				const committed = readFileSync(join(assets, set, `${cue}.wav`));
				expect(Buffer.compare(committed, gen.encodeWav(samples))).toBe(0);
			}
	}, 30_000);

	test('the default set is one of the sets', () => {
		expect(SOUND_SETS).toContain(DEFAULT_SOUND_SET);
	});
});
