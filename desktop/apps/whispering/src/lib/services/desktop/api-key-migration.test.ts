import { describe, expect, test } from 'bun:test';
import { clearMovedKeys, migrateApiKeys } from './api-key-migration';

const KEYS = ['apiKeys.openai', 'apiKeys.groq', 'apiKeys.anthropic'] as const;
type Key = (typeof KEYS)[number];

function fakeStore({
	failWrites = [] as Key[],
	failReads = [] as Key[],
	existing = {} as Partial<Record<Key, string>>,
} = {}) {
	const saved = new Map<Key, string>(Object.entries(existing) as [Key, string][]);
	return {
		saved,
		write: async (key: Key, value: string) => {
			if (failWrites.includes(key)) return { data: null, error: new Error('denied') };
			saved.set(key, value);
			return { data: undefined, error: null };
		},
		read: async (key: Key) => {
			if (failReads.includes(key)) return { data: null, error: new Error('locked') };
			return { data: saved.get(key) ?? null, error: null };
		},
	};
}

describe('API key migration to the OS credential store', () => {
	test('moved keys are cleared from localStorage; stored keys are read', async () => {
		const store = fakeStore({ existing: { 'apiKeys.anthropic': 'sk-ant' } });
		const stored = { 'apiKeys.openai': 'sk-open', 'apiKeys.groq': '', 'apiKeys.anthropic': '' };
		const { secrets, moved } = await migrateApiKeys({ keys: KEYS, stored, ...store });
		expect(secrets).toEqual({ 'apiKeys.openai': 'sk-open', 'apiKeys.groq': '', 'apiKeys.anthropic': 'sk-ant' });
		expect(store.saved.get('apiKeys.openai')).toBe('sk-open');
		expect(clearMovedKeys(stored, moved)['apiKeys.openai']).toBe('');
	});

	test('a key whose write failed stays in localStorage when another key moved', async () => {
		const store = fakeStore({ failWrites: ['apiKeys.groq'] });
		const stored = { 'apiKeys.openai': 'sk-open', 'apiKeys.groq': 'gsk-groq', 'apiKeys.anthropic': '' };
		const errors: unknown[] = [];
		const { secrets, moved } = await migrateApiKeys({
			keys: KEYS,
			stored,
			...store,
			onWriteError: (e) => errors.push(e),
		});
		// Both keys work this session.
		expect(secrets['apiKeys.groq']).toBe('gsk-groq');
		expect(errors).toHaveLength(1);
		const cleared = clearMovedKeys(stored, moved);
		expect(cleared['apiKeys.openai']).toBe('');
		// The failed key is kept, so the next launch tries again.
		expect(cleared['apiKeys.groq']).toBe('gsk-groq');
		expect(store.saved.has('apiKeys.groq')).toBe(false);
	});

	test('a key changed in localStorage after the move is not cleared', () => {
		const moved = new Map<'apiKeys.openai', string>([['apiKeys.openai', 'sk-old']]);
		const now = { 'apiKeys.openai': 'sk-new', other: 1 };
		expect(clearMovedKeys(now, moved)).toEqual({ 'apiKeys.openai': 'sk-new', other: 1 });
	});

	test('a read error leaves that key empty and reports it', async () => {
		const store = fakeStore({ existing: { 'apiKeys.groq': 'gsk' }, failReads: ['apiKeys.groq'] });
		const stored = { 'apiKeys.openai': '', 'apiKeys.groq': '', 'apiKeys.anthropic': '' };
		let reported = 0;
		const { secrets, moved } = await migrateApiKeys({
			keys: KEYS,
			stored,
			...store,
			onReadError: () => reported++,
		});
		expect(secrets['apiKeys.groq']).toBe('');
		expect(reported).toBe(1);
		expect(moved.size).toBe(0);
	});
});
