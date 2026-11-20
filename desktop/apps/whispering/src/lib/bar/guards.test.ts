import { describe, expect, test } from 'bun:test';
import { createExclusive } from './exclusive';
import { createKeySync } from './key-sync';

function deferred() {
	let resolve!: () => void;
	const promise = new Promise<void>((r) => (resolve = r));
	return { promise, resolve };
}

describe('one recovery action per dictation', () => {
	test('a second Paste raw (bar and notice) while the first runs delivers once', async () => {
		const guard = createExclusive();
		const gate = deferred();
		let deliveries = 0;
		const paste = () =>
			guard.run('rec-1', async () => {
				await gate.promise;
				deliveries += 1;
				return 'pasted';
			});
		const first = paste();
		const second = paste();
		expect(guard.busy('rec-1')).toBe(true);
		gate.resolve();
		expect(await first).toBe('pasted');
		expect(await second).toBeUndefined();
		expect(deliveries).toBe(1);
		// Once it has finished, the person may paste again on purpose.
		await paste();
		expect(deliveries).toBe(2);
	});

	test('another dictation is not blocked, and a failure frees the dictation', async () => {
		const guard = createExclusive();
		const gate = deferred();
		const slow = guard.run('a', () => gate.promise);
		expect(await guard.run('b', async () => 'b done')).toBe('b done');
		gate.resolve();
		await slow;
		await expect(guard.run('a', async () => Promise.reject(new Error('x')))).rejects.toThrow('x');
		expect(guard.busy('a')).toBe(false);
	});
});

describe('dictation-only keys', () => {
	function setup(available = true) {
		const live = new Set<string>();
		const gates: Array<ReturnType<typeof deferred>> = [];
		const sync = createKeySync<string>({
			wanted: () => ['Alt+Shift+Up', 'Alt+Shift+Down'],
			async register(key) {
				const gate = deferred();
				gates.push(gate);
				await gate.promise;
				if (available) live.add(key);
				return available;
			},
			async unregister(key) {
				live.delete(key);
			},
		});
		const releaseAll = async () => {
			for (let i = 0; i < 10; i++) {
				for (const g of gates.splice(0)) g.resolve();
				await new Promise((r) => setTimeout(r, 0));
			}
		};
		return { live, sync, releaseAll };
	}

	test('a Stop while the keys are being registered leaves none registered', async () => {
		const { live, sync, releaseAll } = setup();
		const start = sync.sync(true);
		const stop = sync.sync(false);
		await releaseAll();
		await Promise.all([start, stop]);
		expect([...live]).toEqual([]);
		expect(sync.registered).toEqual([]);
	});

	test('start registers both; stop removes both; unavailable keys are skipped', async () => {
		const { live, sync, releaseAll } = setup();
		const start = sync.sync(true);
		await releaseAll();
		await start;
		expect([...live].sort()).toEqual(['Alt+Shift+Down', 'Alt+Shift+Up']);
		await sync.sync(false);
		expect([...live]).toEqual([]);
		const none = setup(false);
		const p = none.sync.sync(true);
		await none.releaseAll();
		await p;
		expect(none.sync.registered).toEqual([]);
	});
});
