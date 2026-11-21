/**
 * VoiceFlow 0.9.9: the built-in styles are added once. One that was
 * deleted never comes back, one that was edited is never written over, and
 * one the person already has under an equivalent name is skipped.
 */
import { describe, expect, test } from 'bun:test';
import type { Transformation } from '$lib/services/isomorphic/db';
import {
	BUILT_IN_STYLES,
	PACK_BUILT_INS,
	builtInStyle,
	isEquivalentName,
	seedBuiltInStyles,
	type SeedDeps,
} from './builtin-styles';

/** A fake install: styles by id, and the marker file. */
function install(styles: Array<Partial<Transformation> & { id: string; title: string }> = []) {
	const db = new Map<string, Transformation>();
	for (const s of styles)
		db.set(s.id, { description: '', steps: [], createdAt: '', updatedAt: '', ...s } as Transformation);
	let marker: string | null = null;
	let markerBroken = false;
	let failWrites = false;
	let creates = 0;
	const deps: SeedDeps = {
		async readMarker() {
			if (markerBroken) throw new Error('bad json');
			return marker === null ? null : (JSON.parse(marker).handled as string[]);
		},
		async writeMarker(keys) {
			if (failWrites) throw new Error('disk full');
			marker = JSON.stringify({ version: 1, handled: keys });
		},
		async idState(id) {
			return db.has(id) ? 'present' : 'absent';
		},
		async names() {
			return [...db.values()].map((s) => s.title);
		},
		async create(style) {
			if (db.has(style.id)) throw new Error('would overwrite');
			creates += 1;
			db.set(style.id, style);
			return true;
		},
		now: () => '2026-11-18T10:00:00.000Z',
	};
	return {
		db,
		deps,
		get marker() {
			return marker ? (JSON.parse(marker).handled as string[]) : null;
		},
		get creates() {
			return creates;
		},
		breakMarker() {
			markerBroken = true;
		},
		failWrites(on: boolean) {
			failWrites = on;
		},
	};
}

/** Everything seeding adds: the built-ins, then (0.9.13) Clean from the pack. */
const KEYS = [...BUILT_IN_STYLES, ...PACK_BUILT_INS].map((t) => t.key);

describe('the built-in styles', () => {
	test('names, model and format match the styles made on the Styles page', () => {
		expect(BUILT_IN_STYLES.map((t) => t.title)).toEqual([
			'AI prompt',
			'Email',
			'Work chat',
			'Notes',
			'Concise',
			// 0.9.10: the Android app's everyday presets.
			'Friends',
			'Work',
			'Family',
			'Partner',
		]);
		for (const t of BUILT_IN_STYLES) {
			const style = builtInStyle(t, { now: 'x' });
			expect(style.id).toBe(t.id);
			expect(style.steps).toHaveLength(1);
			const step = style.steps[0]!;
			expect(step.type).toBe('prompt_transform');
			expect(step['prompt_transform.inference.provider']).toBe('OpenAI');
			expect(step['prompt_transform.inference.provider.OpenAI.model']).toBe('gpt-5.4-mini');
			expect(step['prompt_transform.systemPromptTemplate']).toStartWith(
				'You are a text-transformation tool, not a conversational assistant.',
			);
			// The Android ports keep Android's own ending and output contract.
			expect(step['prompt_transform.systemPromptTemplate']).toContain(
				t.preset ? 'Output contract:\n- Return plain text only.' : 'Return only the rewritten text',
			);
			expect(step['prompt_transform.userPromptTemplate']).toContain('<transcript>\n{{input}}\n</transcript>');
			// Never pinned on its own: the person decides what is on the bar.
			expect(style.showOnBar).toBeUndefined();
		}
		// A template copy gets its own id.
		expect(builtInStyle(BUILT_IN_STYLES[0]!, { id: 'fresh' }).id).toBe('fresh');
	});

	test('equivalent names are narrow', () => {
		const [ai, email, chat, notes, concise] = BUILT_IN_STYLES;
		expect(isEquivalentName(ai!, 'Prompt')).toBe(true);
		expect(isEquivalentName(ai!, 'ChatGPT prompt')).toBe(true);
		expect(isEquivalentName(email!, 'E-mail')).toBe(true);
		expect(isEquivalentName(chat!, 'Slack')).toBe(true);
		expect(isEquivalentName(notes!, 'Meeting notes')).toBe(true);
		expect(isEquivalentName(concise!, 'Shorter')).toBe(true);
		// Not the same thing.
		expect(isEquivalentName(chat!, 'Work')).toBe(false);
		expect(isEquivalentName(email!, 'Formal email to the landlord')).toBe(false);
		expect(isEquivalentName(notes!, 'Relaxed Format Text')).toBe(false);
		expect(isEquivalentName(concise!, 'Friends')).toBe(false);
	});
});

describe('seeding', () => {
	test('added once to an existing install; the next start adds nothing', async () => {
		const pc = install([{ id: 'mine', title: 'Relaxed Format Text' }]);
		const first = await seedBuiltInStyles(pc.deps);
		expect(first.added).toEqual(KEYS);
		expect(pc.db.size).toBe(KEYS.length + 1);
		expect(pc.marker).toEqual(KEYS);
		const second = await seedBuiltInStyles(pc.deps);
		expect(second.added).toEqual([]);
		expect(pc.creates).toBe(KEYS.length);
		// The person's own style is untouched.
		expect(pc.db.get('mine')!.title).toBe('Relaxed Format Text');
	});

	test('a built-in he deleted never comes back', async () => {
		const pc = install();
		await seedBuiltInStyles(pc.deps);
		pc.db.delete('vf-builtin-email');
		const again = await seedBuiltInStyles(pc.deps);
		expect(again.added).toEqual([]);
		expect(pc.db.has('vf-builtin-email')).toBe(false);
	});

	test('a built-in he edited is never written over', async () => {
		const pc = install();
		await seedBuiltInStyles(pc.deps);
		const edited = { ...pc.db.get('vf-builtin-notes')!, title: 'My notes', description: 'mine' };
		pc.db.set(edited.id, edited);
		await seedBuiltInStyles(pc.deps);
		expect(pc.db.get('vf-builtin-notes')).toBe(edited);
		expect(pc.creates).toBe(KEYS.length);
	});

	test('one he already has (same or equivalent name) is skipped, and stays skipped', async () => {
		const pc = install([
			{ id: 'x1', title: 'Email' },
			{ id: 'x2', title: 'Slack' },
		]);
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).toEqual(['ai-prompt', 'notes', 'concise', 'friends', 'work', 'family', 'partner', 'clean']);
		expect(result.skipped.filter((s) => s.reason === 'same-name').map((s) => s.key)).toEqual(['email', 'work-chat']);
		// Skipped ones are marked too: deleting the user's own Email later never brings ours.
		expect(pc.marker).toEqual(expect.arrayContaining(KEYS));
		pc.db.delete('x1');
		expect((await seedBuiltInStyles(pc.deps)).added).toEqual([]);
	});

	test('a marker that cannot be read: nothing is added', async () => {
		const pc = install();
		pc.breakMarker();
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).toEqual([]);
		expect(result.stopped).toContain('marker unreadable');
		expect(pc.db.size).toBe(0);
	});

	test('styles that cannot be read: nothing is added', async () => {
		const pc = install();
		pc.deps.names = async () => null;
		expect((await seedBuiltInStyles(pc.deps)).stopped).toBe('styles not readable');
		expect(pc.db.size).toBe(0);
	});

	test('the marker cannot be written: nothing is created (so nothing can come back later)', async () => {
		const pc = install();
		pc.failWrites(true);
		const first = await seedBuiltInStyles(pc.deps);
		expect(first.added).toEqual([]);
		expect(first.stopped).toContain('disk full');
		expect(pc.db.size).toBe(0);
		pc.failWrites(false);
		expect((await seedBuiltInStyles(pc.deps)).added).toEqual(KEYS);
	});

	test('marked before it is created: a failed create is not retried, and never resurrects', async () => {
		const pc = install();
		pc.deps.create = async (style) => style.id !== 'vf-builtin-email' && (pc.db.set(style.id, style), true);
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.skipped).toContainEqual({ key: 'email', reason: 'not-created' });
		expect(pc.marker).toContain('email');
	});

	test('its id already there (renamed, marker lost): marked, never written over', async () => {
		const pc = install([{ id: 'vf-builtin-concise', title: 'Short and sweet' }]);
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.skipped).toContainEqual({ key: 'concise', reason: 'id-taken' });
		expect(pc.db.get('vf-builtin-concise')!.title).toBe('Short and sweet');
		expect(pc.marker).toContain('concise');
	});

	test('an id that cannot be checked is left for the next start', async () => {
		const pc = install();
		pc.deps.idState = async (id) => (id === 'vf-builtin-email' ? 'unknown' : 'absent');
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).not.toContain('email');
		expect(pc.marker).not.toContain('email');
	});
});
