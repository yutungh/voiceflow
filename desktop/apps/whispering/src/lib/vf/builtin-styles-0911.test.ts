/**
 * VoiceFlow 0.9.11: the mixed-language rule in every everyday built-in, and
 * the refresh of built-ins nobody edited.
 */
import { describe, expect, test } from 'bun:test';
import matter from 'gray-matter';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Transformation } from '$lib/services/isomorphic/db';
import {
	BUILT_IN_STYLES,
	EARLIER_SEEDED,
	FUN_STYLES,
	MIXED_LANGUAGE_RULE,
	builtInStyle,
	refreshBuiltInStyles,
	seededHash,
	type RefreshDeps,
} from './builtin-styles';

const promptOf = (style: Transformation) => style.steps[0]!['prompt_transform.systemPromptTemplate'];
const byKey = (key: string) => BUILT_IN_STYLES.find((t) => t.key === key)!;

describe('mixed languages stay mixed, in every everyday style', () => {
	test('the Android-preset built-ins carry the rule, Work included', () => {
		for (const key of ['friends', 'work', 'family', 'partner']) {
			const prompt = promptOf(builtInStyle(byKey(key)));
			expect(prompt).toContain(MIXED_LANGUAGE_RULE);
			expect(prompt).toContain('Chinese inside English speech stays Chinese');
			expect(prompt).toContain('Simplified Chinese text by default');
		}
		expect(MIXED_LANGUAGE_RULE).toContain('in either direction');
		expect(MIXED_LANGUAGE_RULE).toContain('including Work');
		expect(MIXED_LANGUAGE_RULE).toContain('Never translate');
		expect(MIXED_LANGUAGE_RULE).toContain('Simplified characters');
	});

	test('the 0.9.9 built-ins (Work chat among them) carry it too', () => {
		for (const key of ['ai-prompt', 'email', 'work-chat', 'notes', 'concise']) {
			const prompt = promptOf(builtInStyle(byKey(key)));
			expect(prompt).toContain('Chinese inside English stays Chinese, even mid-sentence. Never translate any part.');
		}
	});

	test('the fun templates are left as Android has them', () => {
		for (const t of FUN_STYLES) expect(promptOf(builtInStyle(t))).not.toContain(MIXED_LANGUAGE_RULE);
	});
});

/** A fake PC: styles by id. */
function pc(styles: Transformation[]) {
	const db = new Map(styles.map((s) => [s.id, s]));
	const updates: Transformation[] = [];
	const deps = (earlier?: RefreshDeps['earlier']): RefreshDeps => ({
		read: async (id) => db.get(id) ?? null,
		update: async (style) => {
			updates.push(style);
			db.set(style.id, style);
			return true;
		},
		now: () => '2026-11-19T12:00:00.000Z',
		earlier,
	});
	return { db, updates, deps };
}

/** A style as an older version seeded it: same built-in, an older prompt. */
function olderSeed(key: string, prompt = 'An older prompt.\n\nLanguage: as before.'): Transformation {
	const style = builtInStyle(byKey(key), { now: '2026-11-18T22:42:38.365Z' });
	return { ...style, steps: [{ ...style.steps[0]!, 'prompt_transform.systemPromptTemplate': prompt }] };
}

describe('refreshing built-ins nobody edited', () => {
	test('one still exactly as an earlier version added it gets the new prompt', async () => {
		const old = olderSeed('work');
		const { db, updates, deps } = pc([old]);
		const result = await refreshBuiltInStyles(deps({ work: [(await seededHash(old))!] }));
		expect(result.refreshed).toEqual(['work']);
		const now = db.get('vf-builtin-work')!;
		expect(promptOf(now)).toContain(MIXED_LANGUAGE_RULE);
		// Same id, step id and creation date; a new updated date.
		expect(now.id).toBe(old.id);
		expect(now.steps[0]!.id).toBe(old.steps[0]!.id);
		expect(now.createdAt).toBe(old.createdAt);
		expect(now.updatedAt).toBe('2026-11-19T12:00:00.000Z');
		expect(await seededHash(now)).toBe(await seededHash(builtInStyle(byKey('work'))));
		expect(updates).toHaveLength(1);
		// The next start changes nothing.
		const again = await refreshBuiltInStyles(deps({ work: [(await seededHash(old))!] }));
		expect(again.refreshed).toEqual([]);
		expect(updates).toHaveLength(1);
	});

	test('an edited one is never touched', async () => {
		const old = olderSeed('work');
		const earlier = { work: [(await seededHash(old))!] };
		for (const edit of [
			(s: Transformation) => ({ ...s, steps: [{ ...s.steps[0]!, 'prompt_transform.systemPromptTemplate': `${promptOf(s)} Be brief.` }] }),
			(s: Transformation) => ({ ...s, title: 'Work (mine)' }),
			(s: Transformation) => ({ ...s, description: 'My own words' }),
			(s: Transformation) => ({ ...s, steps: [{ ...s.steps[0]!, 'prompt_transform.inference.provider.OpenAI.model': 'gpt-5.4' }] }),
			(s: Transformation) => ({ ...s, steps: [...s.steps, { ...s.steps[0]!, id: 'second' }] }),
		]) {
			const edited = edit(old) as Transformation;
			const { db, updates, deps } = pc([edited]);
			const result = await refreshBuiltInStyles(deps(earlier));
			expect(result.kept).toEqual(['work']);
			expect(updates).toHaveLength(0);
			expect(db.get('vf-builtin-work')).toBe(edited);
		}
	});

	test('an edit or a delete made while it refreshes wins', async () => {
		const old = olderSeed('work');
		const earlier = { work: [(await seededHash(old))!] };
		for (const second of [null, { ...old, title: 'Work (mine)' }] as const) {
			const { updates, deps } = pc([old]);
			let reads = 0;
			const result = await refreshBuiltInStyles({
				...deps(earlier),
				read: async (id) => (id !== old.id ? null : reads++ === 0 ? old : second),
			});
			expect(result.refreshed).toEqual([]);
			expect(updates).toHaveLength(0);
		}
	});

	test('a deleted one stays deleted', async () => {
		const { db, updates, deps } = pc([]);
		const result = await refreshBuiltInStyles(deps());
		expect(result.absent).toEqual(BUILT_IN_STYLES.map((t) => t.key));
		expect(updates).toHaveLength(0);
		expect(db.size).toBe(0);
	});

	test('an unreadable one is left alone; one that is current is left alone', async () => {
		const current = builtInStyle(byKey('notes'));
		const { updates, deps } = pc([current]);
		const result = await refreshBuiltInStyles({ ...deps(), read: async (id) => (id === current.id ? current : 'unknown') });
		expect(result.refreshed).toEqual([]);
		expect(result.kept).toEqual([]);
		expect(updates).toHaveLength(0);
	});

	test('a real 0.9.10 Work file, as written to disk, matches the shipped table and is refreshed', async () => {
		const file = readFileSync(join(import.meta.dir, 'fixtures', 'builtin-work-0.9.10.md'), 'utf8');
		const stored = matter(file).data as Transformation;
		expect(EARLIER_SEEDED.work).toContain((await seededHash(stored))!);
		const { db, deps } = pc([stored]);
		const result = await refreshBuiltInStyles(deps());
		expect(result.refreshed).toEqual(['work']);
		expect(promptOf(db.get('vf-builtin-work')!)).toContain(MIXED_LANGUAGE_RULE);
	});

	test("the four Android presets as 0.9.11 and 0.9.12 added them, before Android's last language rule, are refreshed", async () => {
		const SPACING_RULE =
			'\n\n- Where Chinese and English meet, put one space between an English word and a Chinese character, and end an English clause with English punctuation (, . ? !), not full-width marks.';
		for (const key of ['friends', 'work', 'family', 'partner']) {
			const current = builtInStyle(byKey(key));
			expect(promptOf(current)).toContain(SPACING_RULE);
			const old: Transformation = {
				...current,
				steps: [{ ...current.steps[0]!, 'prompt_transform.systemPromptTemplate': promptOf(current).replace(SPACING_RULE, '') }],
			};
			expect(EARLIER_SEEDED[key]).toContain((await seededHash(old))!);
			const { db, deps } = pc([old]);
			const result = await refreshBuiltInStyles(deps());
			expect(result.refreshed).toEqual([key]);
			expect(promptOf(db.get(current.id)!)).toContain(SPACING_RULE);
		}
	});

	test('the shipped table: every built-in, and never the current prompt', async () => {
		expect(Object.keys(EARLIER_SEEDED).sort()).toEqual(BUILT_IN_STYLES.map((t) => t.key).sort());
		for (const t of BUILT_IN_STYLES) {
			const current = (await seededHash(builtInStyle(t)))!;
			expect(EARLIER_SEEDED[t.key]).not.toContain(current);
			for (const h of EARLIER_SEEDED[t.key]!) expect(h).toMatch(/^[0-9a-f]{64}$/);
		}
	});
});
