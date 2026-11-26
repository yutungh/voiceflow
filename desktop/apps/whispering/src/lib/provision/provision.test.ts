import { describe, expect, test } from 'bun:test';
import {
	importProvision,
	parseProvision,
	parseProvisionText,
	PROVISION_SCHEMA,
	validAccelerator,
	type ImportIo,
	type ParseOptions,
	type ProvisionDeps,
	type WordListConfig,
} from './provision';

// Invented values only. The "keys" are shaped like keys and are not real.
const OPENAI = 'sk-test-0000000000000000000000000000';
const TOKEN = 'vfs1.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde';
const LANGS = new Set(['auto', 'en', 'zh', 'ja']);

const OPTIONS: ParseOptions = {
	isValidSetting: (key, value) => {
		if (key === 'transcription.outputLanguage') return typeof value === 'string' && LANGS.has(value);
		if (key === 'transcription.selectedTranscriptionService') return value === 'OpenAI' || value === 'Groq';
		return true;
	},
	knownStyleIds: ['vf-builtin-friends', 'vf-builtin-clean'],
};

function file(extra: Record<string, unknown> = {}) {
	return {
		schema: PROVISION_SCHEMA,
		version: 1,
		apiKeys: { openai: OPENAI },
		settings: {
			'transcription.outputLanguage': 'auto',
			'transcription.openai.model': 'gpt-transcribe',
			'transformations.selectedTransformationId': 'vf-builtin-friends',
		},
		...extra,
	};
}

const GATEWAY = {
	kind: 'gateway',
	url: 'https://Sync.Example.test/',
	user: 'alex',
	device: 'mac1a2b3c4d',
	token: TOKEN,
};

/** In-memory stores, recording every write. */
function fakes(over: { keyWriteFails?: boolean; keyReadsWrong?: boolean; settingsLost?: boolean; deviceId?: string | null; localEdits?: boolean; tokenNotStored?: boolean } = {}) {
	const log: string[] = [];
	const keys = new Map<string, string>();
	let stored: Record<string, unknown> = { 'transcription.outputLanguage': 'en' };
	let deviceId: string | null = over.deviceId === undefined ? null : over.deviceId;
	let token = false;
	let config: WordListConfig | null = null;
	const deps: ProvisionDeps = {
		keys: {
			available: async () => true,
			write: async (p, v) => {
				log.push(`key:${p}`);
				if (over.keyWriteFails) return false;
				keys.set(p, v);
				return true;
			},
			read: async (p) => (over.keyReadsWrong ? 'something-else' : (keys.get(p) ?? null)),
		},
		settings: {
			write: (values) => {
				log.push('settings');
				if (!over.settingsLost) stored = { ...stored, ...values };
			},
			readStored: () => stored,
		},
		wordLists: {
			deviceId: () => deviceId,
			ensureDeviceId: () => (deviceId ??= 'pcnew00001'),
			setDeviceId: (id) => {
				log.push('device');
				deviceId = id;
			},
			hasLocalEdits: async () => !!over.localEdits,
			setGatewayToken: async () => {
				log.push('token');
				token = !over.tokenNotStored;
			},
			gatewayTokenStored: () => token,
			setConfig: async (c) => {
				log.push('config');
				config = c;
			},
			config: () => config,
		},
	};
	const files = { receipt: null as string | null, removed: false, waited: 0 };
	const io: ImportIo = {
		writeReceipt: async (text) => {
			log.push('receipt');
			files.receipt = text;
		},
		removeSource: async () => {
			log.push('remove');
			files.removed = true;
		},
		wait: async (ms) => {
			files.waited += ms;
		},
		now: () => '2026-11-26T12:00:00Z',
	};
	return { deps, io, log, keys, files, stored: () => stored, config: () => config, deviceId: () => deviceId };
}

const run = (text: string, f: ReturnType<typeof fakes>, removeSource = true) =>
	importProvision(text, f.deps, f.io, { ...OPTIONS, from: 'launch', removeSource, settleMs: 5 });

describe('the provisioning file: format and allowlist', () => {
	test('a good file parses into keys, settings and word lists', () => {
		const r = parseProvision(file({ wordLists: GATEWAY }), OPTIONS);
		expect(r.ok).toBe(true);
		if (!r.ok) return;
		expect(r.plan.apiKeys).toEqual({ openai: OPENAI });
		expect(r.plan.settings['transformations.selectedTransformationId']).toBe('vf-builtin-friends');
		expect(r.plan.wordLists).toEqual({ ...GATEWAY, url: 'https://sync.example.test', assistant: '' } as never);
		expect(r.ignored).toEqual([]);
	});

	test('anything off the allowlist is ignored and named, never applied', () => {
		const r = parseProvision(
			file({
				extra: 1,
				apiKeys: { openai: OPENAI, soniox: 'sk-test-11111111111111' },
				settings: { 'transcription.outputLanguage': 'en', 'privacy.mode': 'best-effort', 'analytics.enabled': true },
			}),
			OPTIONS,
		);
		expect(r.ok).toBe(true);
		if (!r.ok) return;
		expect(r.ignored.sort()).toEqual(['apiKeys.soniox', 'extra', 'settings.analytics.enabled', 'settings.privacy.mode']);
		expect(Object.keys(r.plan.settings)).toEqual(['transcription.outputLanguage']);
		expect(Object.keys(r.plan.apiKeys)).toEqual(['openai']);
	});

	test('a bad value on the allowlist stops the whole file, and errors name fields, never values', () => {
		const r = parseProvision(
			file({
				apiKeys: { openai: 'has a space in it' },
				settings: {
					'transcription.outputLanguage': 'klingon',
					'transformations.selectedTransformationId': 'vf-pc-mine',
					'shortcuts.global.pushToTalk': 'Hyper+Q',
				},
			}),
			OPTIONS,
		);
		expect(r.ok).toBe(false);
		if (r.ok) return;
		expect(r.errors.sort()).toEqual([
			'apiKeys.openai',
			'settings.shortcuts.global.pushToTalk',
			'settings.transcription.outputLanguage',
			'settings.transformations.selectedTransformationId',
		]);
		expect(JSON.stringify(r)).not.toContain('has a space');
	});

	test('schema and version are required', () => {
		expect(parseProvision({ ...file(), schema: 'other' }, OPTIONS).ok).toBe(false);
		expect(parseProvision({ ...file(), version: 2 }, OPTIONS).ok).toBe(false);
		expect(parseProvisionText('not json', OPTIONS).ok).toBe(false);
	});

	test('a byte order mark is fine', () => {
		expect(parseProvisionText(`﻿${JSON.stringify(file())}`, OPTIONS).ok).toBe(true);
	});

	test('word lists without a token yet are skipped, and the rest still imports', () => {
		const r = parseProvision(file({ wordLists: { ...GATEWAY, token: 'PASTE-THE-TOKEN-HERE' } }), OPTIONS);
		expect(r.ok).toBe(true);
		if (!r.ok) return;
		expect(r.plan.wordLists).toBeNull();
		expect(r.skipped).toEqual([{ field: 'wordLists', reason: 'no sync server token in the file yet' }]);
		expect(Object.keys(r.plan.apiKeys)).toEqual(['openai']);
	});

	test('a pasted secret as a field name is never repeated', () => {
		const r = parseProvision(file({ apiKeys: { openai: OPENAI, [OPENAI]: 'openai' } }), OPTIONS);
		expect(r.ok).toBe(true);
		if (r.ok) expect(r.ignored.join(' ')).not.toContain(OPENAI);
		if (!r.ok) return;
		expect(r.ignored).toEqual(['apiKeys.(a field)']);
	});

	test('the default style must be a built-in (Raw would be replaced by a new install default)', () => {
		const r = parseProvision(file({ settings: { 'transformations.selectedTransformationId': null } }), OPTIONS);
		expect(r.ok).toBe(false);
	});

	test('folder word lists on a fresh install get a device id', async () => {
		const f = fakes();
		const out = await run(JSON.stringify(file({ wordLists: { kind: 'folder', source: '/Volumes/words', user: 'alex' } })), f);
		expect(out.status).toBe('applied');
		expect(f.config()?.device).toBe('pcnew00001');
		expect(f.config()?.kind).toBe('folder');
	});

	test('word-list addresses and names are checked', () => {
		for (const bad of [
			{ ...GATEWAY, url: 'http://sync.example.test' },
			{ ...GATEWAY, url: 'https://sync.example.test/path' },
			{ ...GATEWAY, user: 'Household' },
			{ ...GATEWAY, user: 'household' },
			{ ...GATEWAY, device: '../x' },
			{ ...GATEWAY, kind: 'github' },
		]) {
			expect(parseProvision(file({ wordLists: bad }), OPTIONS).ok).toBe(false);
		}
	});

	test('shortcuts: modifiers and one key', () => {
		for (const ok of ['CommandOrControl+Shift+;', "CommandOrControl+Shift+'", 'CommandOrAlt+Shift+D', 'Alt+F9', 'Control+Space'])
			expect(validAccelerator(ok)).toBe(true);
		for (const bad of ['D', 'Shift+Shift+D', 'Hyper+D', 'Control+Shift+', 'Control + D', 'Control+Shift+DD'])
			expect(validAccelerator(bad)).toBe(false);
	});
});

describe('the provisioning file: applying, persistence before removal, the receipt', () => {
	test('applies keys, then settings, then word lists; writes the receipt; removes the file last', async () => {
		const f = fakes();
		const out = await run(JSON.stringify(file({ wordLists: GATEWAY })), f);
		expect(out.status).toBe('applied');
		if (out.status !== 'applied') return;
		expect(out.removed).toBe(true);
		expect(f.log).toEqual(['key:openai', 'settings', 'device', 'token', 'config', 'receipt', 'remove']);
		expect(f.keys.get('openai')).toBe(OPENAI);
		expect(f.stored()['transformations.selectedTransformationId']).toBe('vf-builtin-friends');
		expect(f.deviceId()).toBe('mac1a2b3c4d');
		expect(f.config()).toEqual({
			enabled: true,
			kind: 'gateway',
			gateway: 'https://sync.example.test',
			source: '',
			user: 'alex',
			assistant: '',
			device: 'mac1a2b3c4d',
		});
		expect(f.files.waited).toBe(5);
	});

	test('the receipt names fields and never holds a key or a token', async () => {
		const f = fakes();
		await run(JSON.stringify(file({ wordLists: GATEWAY, extra: 'x' })), f);
		const receipt = f.files.receipt ?? '';
		expect(receipt).not.toContain(OPENAI);
		expect(receipt).not.toContain(TOKEN);
		expect(receipt).not.toContain('vf-builtin-friends');
		const parsed = JSON.parse(receipt);
		expect(parsed.schema).toBe('voicelnq-provision-receipt');
		expect(parsed.appliedAt).toBe('2026-11-26T12:00:00Z');
		expect(parsed.set).toContain('apiKeys.openai');
		expect(parsed.set).toContain('settings.transcription.outputLanguage');
		expect(parsed.ignored).toEqual(['extra']);
	});

	test('a key the store refuses: nothing else is written and the file stays', async () => {
		const f = fakes({ keyWriteFails: true });
		const out = await run(JSON.stringify(file()), f);
		expect(out.status).toBe('failed');
		expect(f.log).toEqual(['key:openai']);
		expect(f.files.removed).toBe(false);
		expect(f.files.receipt).toBeNull();
		expect(JSON.stringify(out)).not.toContain(OPENAI);
	});

	test("a key that doesn't read back: the file stays", async () => {
		const f = fakes({ keyReadsWrong: true });
		const out = await run(JSON.stringify(file()), f);
		expect(out.status).toBe('failed');
		expect(f.files.removed).toBe(false);
	});

	test("settings that don't read back: the file stays", async () => {
		const f = fakes({ settingsLost: true });
		const out = await run(JSON.stringify(file()), f);
		expect(out.status).toBe('failed');
		if (out.status !== 'failed') return;
		expect(out.failed.map((x) => x.field)).toContain('settings.transcription.outputLanguage');
		expect(f.files.removed).toBe(false);
	});

	test('a token the store refuses: the file stays', async () => {
		const f = fakes({ tokenNotStored: true });
		const out = await run(JSON.stringify(file({ wordLists: GATEWAY })), f);
		expect(out.status).toBe('failed');
		expect(f.files.removed).toBe(false);
	});

	test('an install with its own edits keeps its device id: the gateway part fails, nothing is removed', async () => {
		const f = fakes({ deviceId: 'pcabcdefgh', localEdits: true });
		const out = await run(JSON.stringify(file({ wordLists: GATEWAY })), f);
		expect(out.status).toBe('failed');
		expect(f.deviceId()).toBe('pcabcdefgh');
		expect(f.log).not.toContain('token');
		expect(f.files.removed).toBe(false);
	});

	test('an install with an id but no edits takes the token id', async () => {
		const f = fakes({ deviceId: 'pcabcdefgh' });
		const out = await run(JSON.stringify(file({ wordLists: GATEWAY })), f);
		expect(out.status).toBe('applied');
		expect(f.deviceId()).toBe('mac1a2b3c4d');
	});

	test('a change during the wait (storage not kept) keeps the file', async () => {
		const f = fakes();
		const io = {
			...f.io,
			wait: async () => {
				// Something reverted the setting before the second read-back.
				f.deps.settings.write({ 'transcription.outputLanguage': 'en' });
			},
		};
		const out = await importProvision(JSON.stringify(file()), f.deps, io, { ...OPTIONS, from: 'launch', removeSource: true });
		expect(out.status).toBe('applied');
		if (out.status !== 'applied') return;
		expect(out.removed).toBe(false);
		expect(out.kept).toContain('settings.transcription.outputLanguage');
		expect(f.files.removed).toBe(false);
	});

	test('a manual import never removes the chosen file', async () => {
		const f = fakes();
		const out = await run(JSON.stringify(file()), f, false);
		expect(out.status).toBe('applied');
		expect(f.files.removed).toBe(false);
		expect(f.files.receipt).not.toBeNull();
	});

	test('the same file twice gives the same result (a retry after a crash)', async () => {
		const f = fakes();
		await run(JSON.stringify(file({ wordLists: GATEWAY })), f, false);
		const again = await run(JSON.stringify(file({ wordLists: GATEWAY })), f);
		expect(again.status).toBe('applied');
		expect(f.config()?.device).toBe('mac1a2b3c4d');
	});

	test('a placeholder token: applied without word lists, and still removed', async () => {
		const f = fakes();
		const out = await run(JSON.stringify(file({ wordLists: { ...GATEWAY, token: '' } })), f);
		expect(out.status).toBe('applied');
		if (out.status !== 'applied') return;
		expect(out.removed).toBe(true);
		expect(out.receipt.skipped).toEqual([{ field: 'wordLists', reason: 'no sync server token in the file yet' }]);
		expect(f.log).not.toContain('config');
	});
});
