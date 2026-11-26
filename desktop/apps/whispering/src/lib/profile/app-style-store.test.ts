/**
 * VoiceFlow 0.9.21: Settings > Style for each app writes `app-style` edits.
 * A change replaces this PC's earlier edits for the app (new edit first,
 * then their tombstones); Remove retires every edit in force for it, and its
 * Undo brings them back. Run against the store's browser fallback
 * (localStorage), with the word-list service stubbed.
 */
import { beforeEach, describe, expect, mock, test } from 'bun:test';

const store = new Map<string, string>();
(globalThis as { localStorage?: unknown }).localStorage = {
	getItem: (k: string) => store.get(k) ?? null,
	setItem: (k: string, v: string) => void store.set(k, String(v)),
	removeItem: (k: string) => void store.delete(k),
};
let merges = 0;
mock.module('./service.svelte', () => ({
	wordLists: {
		snapshot: null,
		editsChanged: async () => {
			merges += 1;
		},
	},
}));
mock.module('$lib/feedback/log', () => ({ FEEDBACK_SCHEMA: 'voiceflow-feedback-draft', writeFeedback: async () => {} }));

const {
	loadOps,
	localAppRules,
	opIsLive,
	removeAppStyle,
	replaceAppStyle,
	saveAppStyle,
	undoRemoveAppStyle,
} = await import('./op-store');
const { activeAppStyles, liveAppStyleOps } = await import('./ops');

beforeEach(() => {
	store.clear();
	merges = 0;
});

describe('the app-style store', () => {
	test('a change replaces this PC’s earlier edits, so an old level can’t linger', async () => {
		const first = await saveAppStyle({ app: 'Telegram.exe', style: 'work', expression: 1 });
		expect(first.ok).toBe(true);
		const next = await replaceAppStyle({ app: 'telegram.exe', style: 'vf-builtin-friends' });
		expect(next.ok).toBe(true);
		if (!next.ok || !first.ok) return;
		expect(next.retired).toEqual([first.op.id]);
		const ops = await loadOps();
		// The new edit was written before the tombstone.
		const at = (id: string) => ops.findIndex((o) => o.id === id);
		const tomb = ops.find((o) => o.type === 'tombstone')!;
		expect(at(next.op.id)).toBeLessThan(at(tomb.id));
		expect(activeAppStyles(ops).get('telegram.exe')).toEqual({
			style: 'vf-builtin-friends',
			editId: next.op.id,
			editIds: [next.op.id],
		});
		expect(localAppRules().get('telegram.exe')?.style).toBe('vf-builtin-friends');
		expect(await opIsLive(first.op.id)).toBe(false);
		expect(await opIsLive(next.op.id)).toBe(true);
		expect(merges).toBeGreaterThan(0);
	});

	test('an explicit level is kept; a bad app or style is refused and nothing changes', async () => {
		const ok = await replaceAppStyle({ app: 'chatgpt.exe', style: 'vf-builtin-ai-prompt', expression: 0 });
		expect(ok.ok && ok.op.expression).toBe(0);
		const before = (await loadOps()).length;
		expect((await replaceAppStyle({ app: 'chat gpt', style: 'raw' })).ok).toBe(false);
		expect((await replaceAppStyle({ app: 'chatgpt.exe', style: 'Not An Id' })).ok).toBe(false);
		expect((await loadOps()).length).toBe(before);
	});

	test('Remove retires every edit in force for the app, Undo brings them back', async () => {
		await saveAppStyle({ app: 'telegram.exe', style: 'vf-builtin-friends' });
		await saveAppStyle({ app: 'telegram.exe', expression: 3 });
		await saveAppStyle({ app: 'slack.exe', style: 'raw' });
		// An edit cached from another device, named by the merged lists.
		const removed = await removeAppStyle('telegram.exe', ['otherpc-cached-1']);
		expect(removed.ok).toBe(true);
		expect(removed.tombstones.map((t) => t.target)).toHaveLength(3);
		expect(removed.tombstones.map((t) => t.target)).toContain('otherpc-cached-1');
		let ops = await loadOps();
		expect(liveAppStyleOps(ops, 'telegram.exe')).toHaveLength(0);
		expect(liveAppStyleOps(ops, 'slack.exe')).toHaveLength(1);
		// Undo is a later click (edits made in the same millisecond have no order).
		await Bun.sleep(5);
		expect(await undoRemoveAppStyle('telegram.exe', removed.tombstones)).toBe(true);
		ops = await loadOps();
		expect(activeAppStyles(ops).get('telegram.exe')).toMatchObject({ style: 'vf-builtin-friends', expression: 3 });
	});

	test('Undo is refused once a newer setting exists: an old level can’t come back under it', async () => {
		await saveAppStyle({ app: 'telegram.exe', style: 'work', expression: 1 });
		const removed = await removeAppStyle('telegram.exe');
		await Bun.sleep(5);
		const newer = await replaceAppStyle({ app: 'telegram.exe', style: 'vf-builtin-friends' });
		expect(newer.ok).toBe(true);
		await Bun.sleep(5);
		expect(await undoRemoveAppStyle('telegram.exe', removed.tombstones)).toBe(false);
		const rule = activeAppStyles(await loadOps()).get('telegram.exe');
		expect(rule?.style).toBe('vf-builtin-friends');
		expect(rule?.expression).toBeUndefined();
	});

	test('a page showing the rules hears every change', async () => {
		const { onLocalAppRulesChange } = await import('./op-store');
		let heard = 0;
		const stop = onLocalAppRulesChange(() => (heard += 1));
		await saveAppStyle({ app: 'kite.exe', expression: 2 });
		stop();
		await saveAppStyle({ app: 'kite.exe', expression: 3 });
		expect(heard).toBe(1);
	});
});
