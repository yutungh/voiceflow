import { describe, expect, test } from 'bun:test';
import type { Settings } from '$lib/settings/settings';
import {
	blockedUpdates,
	deviceOnly,
	effectiveSnapshot,
	profileOverlay,
	profileStyleIdOf,
	profileStyleKey,
} from './ownership';
import type { ProfileSnapshot } from './types';

function snapshot(defaults: Partial<ProfileSnapshot['defaults']> = {}): ProfileSnapshot {
	return {
		id: 's1',
		user: 'sam',
		terms: [{ id: 'z', text: 'Zorblax', heardAs: [], priority: 50, apps: ['*'], layer: 'personal' }],
		corrections: [],
		styles: [],
		defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {}, ...defaults },
		privacy: {},
		apps: [],
		issues: [],
	};
}

// Only the keys these functions touch matter here.
const stored = {
	'transcription.prompt': 'my old prompt',
	'transcription.openai.model': 'device-model',
	'transcription.outputLanguage': 'en',
	'transformations.selectedTransformationId': 'abc',
	'sound.playOn.manual-start': true,
} as unknown as Settings;

describe('profileOverlay', () => {
	test('word lists off: nothing is owned', () => {
		expect(profileOverlay(null)).toEqual({});
	});

	test('the prompt is always owned; defaults only when a layer sets them', () => {
		expect(profileOverlay(snapshot())).toEqual({ 'transcription.prompt': 'Vocabulary: Zorblax' });
		const all = profileOverlay(
			snapshot({ transcriptionModel: 'gpt-x', language: 'en', cleanupStyle: 'casual' }),
		);
		expect(all['transcription.openai.model']).toBe('gpt-x');
		expect(all['transcription.outputLanguage']).toBe('en');
		expect(all['transformations.selectedTransformationId']).toBe('profile:casual');
		expect(profileOverlay(snapshot({ cleanupStyle: 'raw' }))['transformations.selectedTransformationId']).toBeNull();
	});

	test('an unknown language is left to the device', () => {
		expect(profileOverlay(snapshot({ language: 'xx' }), () => false)).not.toHaveProperty(
			'transcription.outputLanguage',
		);
	});
});

describe('deviceOnly (what is written to localStorage)', () => {
	test('profile values are never copied into localStorage', () => {
		const owned = profileOverlay(snapshot({ transcriptionModel: 'gpt-x' }));
		// What `settings.update` builds: the effective (merged) values plus a change.
		const effective = { ...stored, ...owned, 'sound.playOn.manual-start': false } as Settings;
		const written = deviceOnly(effective, stored, owned);
		expect(written['transcription.prompt']).toBe('my old prompt');
		expect(written['transcription.openai.model']).toBe('device-model');
		// Device settings still change.
		expect(written['sound.playOn.manual-start']).toBe(false);
		// Not owned (no layer sets it): the device keeps choosing.
		expect(written['transformations.selectedTransformationId']).toBe('abc');
	});

	test('reset keeps the inert device values of owned keys', () => {
		const owned = profileOverlay(snapshot());
		const defaults = { ...stored, 'transcription.prompt': '' } as Settings;
		expect(deviceOnly(defaults, stored, owned)['transcription.prompt']).toBe('my old prompt');
	});

	test('with word lists off everything is written as given', () => {
		const next = { ...stored, 'transcription.prompt': 'new' } as Settings;
		expect(deviceOnly(next, stored, {})['transcription.prompt']).toBe('new');
	});

	test('blockedUpdates names owned keys a caller tried to change', () => {
		const owned = profileOverlay(snapshot({ transcriptionModel: 'gpt-x' }));
		expect(blockedUpdates({ 'transcription.openai.model': 'other' }, owned)).toEqual([
			'transcription.openai.model',
		]);
		expect(blockedUpdates({ 'transcription.openai.model': 'gpt-x' }, owned)).toEqual([]);
		expect(blockedUpdates({ 'sound.playOn.manual-start': false }, owned)).toEqual([]);
	});
});

test('profile style keys round-trip', () => {
	expect(profileStyleIdOf(profileStyleKey('casual'))).toBe('casual');
	expect(profileStyleIdOf('abc')).toBeNull();
	expect(profileStyleIdOf(null)).toBeNull();
});

describe('effectiveSnapshot', () => {
	const base = (layers: { state: 'ok' | 'cached' | 'offline' | 'missing' }[], enabled = true) => ({
		config: { enabled, source: 'x', user: 'sam', assistant: '', device: 'dev1' },
		snapshot: snapshot(),
		status: {
			refreshing: false,
			deferred: false,
			report: {
				sourceReachable: false,
				attemptedAt: 't',
				usingCache: true,
				layers: layers.map((l) => ({ layer: null, words: 0, corrections: 0, ...l })),
				pendingEdits: 0,
				uploadedNow: 0,
				errors: [],
			},
		},
	});
	test('nothing read yet: the device settings stay in force (never an empty prompt)', () => {
		expect(effectiveSnapshot(base([{ state: 'missing' }, { state: 'missing' }]))).toBeNull();
	});
	test('a copy on this device or a read from the folder makes it active', () => {
		expect(effectiveSnapshot(base([{ state: 'offline' }, { state: 'missing' }]))?.id).toBe('s1');
		expect(effectiveSnapshot(base([{ state: 'ok' }]))?.id).toBe('s1');
	});
	test('off is off', () => {
		expect(effectiveSnapshot(base([{ state: 'ok' }], false))).toBeNull();
		expect(effectiveSnapshot(null)).toBeNull();
	});
});
