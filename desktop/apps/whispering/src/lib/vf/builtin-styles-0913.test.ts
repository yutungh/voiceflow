/**
 * VoiceFlow 0.9.13: Clean, the shared built-in style, comes from the MIT
 * pack file (`pack/builtin/styles.json`) that Android packages too. It is
 * added once like the other built-ins, and it is the default only on a
 * genuinely new install.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Transformation } from '$lib/services/isomorphic/db';
import {
	ANDROID_LANGUAGE_CONTRACT,
	BUILT_IN_STYLES,
	NEW_INSTALL_DEFAULT_KEY,
	PACK_BUILT_INS,
	builtInStyle,
	isEquivalentName,
	packStyles,
	seedBuiltInStyles,
	type SeedDeps,
} from './builtin-styles';

const pack = JSON.parse(readFileSync(join(import.meta.dir, '../../../../../../pack/builtin/styles.json'), 'utf8'));

function install({
	marker = null as string[] | null,
	styles = [] as Array<{ id: string; title: string }>,
	dictations = 0,
	defaultId = null as string | null,
} = {}) {
	const db = new Map<string, Transformation>();
	for (const s of styles) db.set(s.id, { description: '', steps: [], createdAt: '', updatedAt: '', ...s } as Transformation);
	let handled = marker;
	let def = defaultId;
	const deps: SeedDeps = {
		readMarker: async () => handled,
		writeMarker: async (keys) => {
			handled = keys;
		},
		idState: async (id) => (db.has(id) ? 'present' : 'absent'),
		names: async () => [...db.values()].map((s) => s.title),
		create: async (style) => (db.set(style.id, style), true),
		isNewInstall: async () => dictations === 0 && def === null,
		setDefault: async (id) => {
			def = id;
		},
		now: () => '2026-11-19T10:00:00.000Z',
	};
	return {
		db,
		deps,
		get default() {
			return def;
		},
	};
}

describe('Clean from the pack', () => {
	const clean = pack.styles.find((s: { id: string }) => s.id === 'clean');

	test('one built-in, made from the pack file word for word', () => {
		expect(PACK_BUILT_INS.map((t) => [t.key, t.id, t.title])).toEqual([['clean', 'vf-builtin-clean', 'Clean']]);
		const t = PACK_BUILT_INS[0]!;
		expect(t.prompt.startsWith(clean.prompt.trim())).toBe(true);
		// The same shape as the Android presets here: the language and output contracts after it.
		expect(t.prompt).toContain(ANDROID_LANGUAGE_CONTRACT);
		expect(t.prompt.endsWith('- Do not add any text before or after the transformed text.')).toBe(true);
		const style = builtInStyle(t);
		expect(style.steps).toHaveLength(1);
		expect(style.steps[0]!['prompt_transform.inference.provider.OpenAI.model']).toBe('gpt-5.4-mini');
		expect(style.steps[0]!['prompt_transform.userPromptTemplate']).toContain('{{input}}');
		// Not one of the Android presets: Friends stays Friends.
		expect(BUILT_IN_STYLES.some((b) => b.key === 'clean')).toBe(false);
		expect(t.preset).toBeUndefined();
		expect(isEquivalentName(t, 'Clean')).toBe(true);
		expect(isEquivalentName(t, 'clean up')).toBe(true);
		expect(isEquivalentName(t, 'Friends')).toBe(false);
	});

	test('a pack file of another schema or version gives nothing (never a broken style)', () => {
		expect(packStyles({ ...pack, version: 2 })).toEqual([]);
		expect(packStyles({ ...pack, schema: 'other' })).toEqual([]);
		expect(packStyles(null)).toEqual([]);
		expect(packStyles({ ...pack, styles: [{ id: 'Bad Id', name: 'x', prompt: 'p' }, { id: 'ok', name: ' ', prompt: 'p' }] })).toEqual([]);
		// Fields it doesn't use are fine (v2 style fields, say).
		expect(packStyles({ ...pack, styles: [{ ...clean, models: { openai: 'x' }, hidden: false }] })).toHaveLength(1);
	});
});

describe('the default on a new install', () => {
	test('a genuinely new install starts with Clean', async () => {
		const pc = install();
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).toContain(NEW_INSTALL_DEFAULT_KEY);
		expect(result.defaultSet).toBe('vf-builtin-clean');
		expect(pc.default).toBe('vf-builtin-clean');
	});

	test('an existing install gets Clean as a choice; its default never changes', async () => {
		// 0.9.12: a marker, Raw as the default (null), dictations in History.
		const pc = install({ marker: [...BUILT_IN_STYLES.map((t) => t.key)], dictations: 40 });
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).toEqual(['clean']);
		expect(result.defaultSet).toBeUndefined();
		expect(pc.default).toBeNull();
		expect(pc.db.has('vf-builtin-clean')).toBe(true);
	});

	test('no marker but already used (dictations, or a default): no change of default', async () => {
		for (const pc of [install({ dictations: 1 }), install({ defaultId: 'his-style' })]) {
			const before = pc.default;
			await seedBuiltInStyles(pc.deps);
			expect(pc.default).toBe(before);
		}
	});

	test('one he already has called Clean is kept, and the default is not moved to ours', async () => {
		const pc = install({ styles: [{ id: 'mine', title: 'Clean' }] });
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.skipped).toContainEqual({ key: 'clean', reason: 'same-name' });
		expect(pc.default).toBeNull();
	});

	test('a deleted Clean never comes back', async () => {
		const pc = install({ dictations: 3 });
		await seedBuiltInStyles(pc.deps);
		pc.db.delete('vf-builtin-clean');
		expect((await seedBuiltInStyles(pc.deps)).added).toEqual([]);
	});
});
