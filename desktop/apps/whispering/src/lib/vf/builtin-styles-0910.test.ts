/**
 * VoiceFlow 0.9.10: the Android app's presets on the desktop.
 * - Friends, Work, Family and Partner are added once, like the 0.9.9
 *   built-ins: one that was deleted never comes back, one that was edited is
 *   never written over, and one the user already has (same name, or a name that
 *   maps to the same Android preset) is skipped.
 * - Haiku, Pirate, Shakespearean, Noir Detective and Wizard are templates
 *   only: offered under "Add a style", never added on their own.
 * - Every name maps to its Android preset, so the right expression guidance
 *   applies, and every prompt is Android's, read from the Android sources.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Transformation } from '$lib/services/isomorphic/db';
import { presetKindOf, withExpression } from '../bar/expression';
import {
	BUILT_IN_STYLES,
	FUN_STYLES,
	MIXED_LANGUAGE_RULE,
	builtInStyle,
	isEquivalentName,
	seedBuiltInStyles,
	styleNames,
	type SeedDeps,
} from './builtin-styles';

const EVERYDAY = ['friends', 'work', 'family', 'partner'] as const;
const FUN = ['fun-haiku', 'fun-pirate', 'fun-shakespeare', 'fun-noir', 'fun-wizard'] as const;
const byKey = (key: string) => [...BUILT_IN_STYLES, ...FUN_STYLES].find((t) => t.key === key)!;

/** A fake install: styles by id, and the marker file. */
function install(styles: Array<{ id: string; title: string }> = [], marker: string[] | null = null) {
	const db = new Map<string, Transformation>();
	for (const s of styles)
		db.set(s.id, { description: '', steps: [], createdAt: '', updatedAt: '', ...s } as Transformation);
	let handled = marker;
	let creates = 0;
	const deps: SeedDeps = {
		readMarker: async () => (handled ? [...handled] : null),
		writeMarker: async (keys) => {
			handled = [...keys];
		},
		idState: async (id) => (db.has(id) ? 'present' : 'absent'),
		names: async () => [...db.values()].map((s) => s.title),
		create: async (style) => {
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
			return handled;
		},
		get creates() {
			return creates;
		},
	};
}

// ---- the Android sources ----------------------------------------------------
const ANDROID_DIR = join(import.meta.dir, '../../../../../../app/src/main/java/com/voiceflowkeyboard/ime');
const PREFS = readFileSync(join(ANDROID_DIR, 'Prefs.java'), 'utf8').replace(/\r\n/g, '\n');
const OPENAI_CLIENT = readFileSync(join(ANDROID_DIR, 'OpenAiClient.java'), 'utf8').replace(/\r\n/g, '\n');

/** The Java string literals from `from` to the end of that statement, joined, escapes undone. */
function javaText(from: string, source = PREFS): string {
	const start = source.indexOf(from);
	if (start < 0) throw new Error(`not in the Android source: ${from}`);
	const end = source.indexOf('";\n', start);
	const literals = source.slice(start, end + 2).matchAll(/"((?:[^"\\]|\\.)*)"/g);
	return [...literals]
		.map((m) => m[1]!.replace(/\\(.)/g, (_, c: string) => (c === 'n' ? '\n' : c)))
		.join('');
}

/**
 * 0.9.11: the desktop's language contract from Android's. Android's newer
 * first line (Simplified Chinese by default) replaces the older one while the
 * committed source still has it, and the mixed-language rule goes in after
 * the line about mixed languages until Android has it too. Everything else
 * must match Android word for word.
 */
const OLD_FIRST_LINE =
	'- Write the result in the same language the speaker used. Chinese speech produces Chinese text; English speech produces English text.';
const NEW_FIRST_LINE =
	'- Write the result in the same language the speaker used. Chinese speech produces Simplified Chinese text by default; use Traditional Chinese only when the speaker explicitly requests it. English speech produces English text.';
const MIXED_LINE_START = '- When the speaker mixes languages,';
function desktopLanguage(android: string): string {
	let text = android.replace(OLD_FIRST_LINE, NEW_FIRST_LINE);
	if (!text.includes(MIXED_LANGUAGE_RULE)) {
		const at = text.indexOf(MIXED_LINE_START);
		expect(at).toBeGreaterThan(-1);
		const end = text.indexOf('\n\n', at);
		text = `${text.slice(0, end)}\n\n${MIXED_LANGUAGE_RULE}${text.slice(end)}`;
	}
	return text;
}

const systemPrompt = (key: string) => builtInStyle(byKey(key)).steps[0]!['prompt_transform.systemPromptTemplate'];

describe('the Android presets', () => {
	test('Android names, model and format; the four everyday ones come after the 0.9.9 five', () => {
		expect(BUILT_IN_STYLES.slice(5).map((t) => [t.key, t.title])).toEqual([
			['friends', 'Friends'],
			['work', 'Work'],
			['family', 'Family'],
			['partner', 'Partner'],
		]);
		expect(FUN_STYLES.map((t) => t.title)).toEqual(['Haiku', 'Pirate', 'Shakespearean', 'Noir Detective', 'Wizard']);
		for (const key of [...EVERYDAY, ...FUN]) {
			const style = builtInStyle(byKey(key));
			const step = style.steps[0]!;
			expect(step['prompt_transform.inference.provider']).toBe('OpenAI');
			expect(step['prompt_transform.inference.provider.OpenAI.model']).toBe('gpt-5.4-mini');
			expect(step['prompt_transform.systemPromptTemplate']).toStartWith(
				'You are a text-transformation tool, not a conversational assistant.',
			);
			expect(step['prompt_transform.userPromptTemplate']).toContain('<transcript>\n{{input}}\n</transcript>');
			expect(style.showOnBar).toBeUndefined();
		}
		// Android's default labels, so the names match on both apps.
		for (const [preset, title] of [
			['PRESET_FAMILY', 'Family'],
			['PRESET_PARTNER', 'Partner'],
			['PRESET_FUN_HAIKU', 'Haiku'],
			['PRESET_FUN_PIRATE', 'Pirate'],
			['PRESET_FUN_SHAKESPEARE', 'Shakespearean'],
			['PRESET_FUN_NOIR', 'Noir Detective'],
			['PRESET_FUN_WIZARD', 'Wizard'],
		])
			expect(PREFS).toContain(`if (${preset}.equals(preset)) {\n            return "${title}";`);
		expect(PREFS).toContain('return "Work";');
		expect(PREFS).toContain('return "Friends";');
	});

	test('presetKindOf maps each name to its Android preset', () => {
		const titles = [...BUILT_IN_STYLES.slice(5), ...FUN_STYLES].map((t) => [t.title, presetKindOf(t.title)]);
		expect(Object.fromEntries(titles)).toEqual({
			Friends: 'casual',
			Work: 'business',
			Family: 'family',
			Partner: 'partner',
			Haiku: 'fun_haiku',
			Pirate: 'fun_pirate',
			Shakespearean: 'fun_shakespeare',
			'Noir Detective': 'fun_noir',
			Wizard: 'fun_wizard',
		});
		for (const t of [...BUILT_IN_STYLES.slice(5), ...FUN_STYLES]) expect(presetKindOf(t.title)).toBe(t.preset!);
		// The 0.9.9 built-ins stay general styles.
		for (const t of BUILT_IN_STYLES.slice(0, 5)) expect(presetKindOf(t.title)).toBe('custom');
	});

	test('the expression control gives each its Android guidance', () => {
		const work = withExpression(builtInStyle(byKey('work')), 3);
		expect(work.steps[0]!['prompt_transform.systemPromptTemplate']).toContain('Because this is the Work style');
		const partner = withExpression(builtInStyle(byKey('partner')), 4);
		expect(partner.steps[0]!['prompt_transform.systemPromptTemplate']).toContain("for the speaker's spouse");
		const haiku = withExpression(builtInStyle(byKey('fun-haiku')), 2);
		expect(haiku.steps[0]!['prompt_transform.systemPromptTemplate']).toContain('5-7-5 English haiku pattern');
	});

	test('the prompts are Android word for word, plus the contracts Android adds when it sends them', () => {
		const language = desktopLanguage(javaText('return "Language:'));
		const output = javaText('OUTPUT_CONTRACT =', OPENAI_CLIENT).trim();
		expect(output).toStartWith('Output contract:\n- Return plain text only.');
		const everyday = (prompt: string) => `${prompt}\n\n${language}\n\n${output}`;
		const guidance = (preset: string) => javaText(`if (${preset}.equals(preset)) {\n            return "Write for`);
		const relationship = (g: string) =>
			javaText('String relationshipPrompt(').replace('Voice style:\n', `Voice style:\n${g}`);
		expect(systemPrompt('friends')).toBe(everyday(javaText('String casualPrompt()')));
		expect(systemPrompt('work')).toBe(everyday(javaText('String businessPrompt()')));
		expect(systemPrompt('family')).toBe(everyday(relationship(guidance('PRESET_FAMILY'))));
		expect(systemPrompt('partner')).toBe(everyday(relationship(guidance('PRESET_PARTNER'))));
		expect(systemPrompt('family')).toContain('less peer-like than Friends and never romantic like Partner');
		expect(systemPrompt('partner')).toContain("Write for the speaker's spouse or romantic partner.");

		// The fun ones: Android's shared opening and persona text and the output
		// contract, with no language contract (Android leaves it off). Only the
		// one-line examples differ: the desktop's use neutral names, so they are
		// compared apart from the rest, which must match word for word.
		const shared = javaText('String shared = ');
		const EXAMPLE = /Example: '.*?' -> '.*?[.!]' /g;
		const withoutExamples = (text: string) => text.replace(EXAMPLE, 'Example: … ');
		const persona = (from: string) => javaText(from);
		const personas: Record<(typeof FUN)[number], string> = {
			'fun-haiku': persona('if (PRESET_FUN_HAIKU.equals(preset)) {\n            return shared'),
			'fun-pirate': persona('if (PRESET_FUN_PIRATE.equals(preset)) {\n            return shared'),
			'fun-shakespeare': persona('if (PRESET_FUN_SHAKESPEARE.equals(preset)) {\n            return shared'),
			'fun-noir': persona('if (PRESET_FUN_NOIR.equals(preset)) {\n            return shared'),
			'fun-wizard': persona('return shared\n                + "Restate the message as a theatrical fantasy wizard'),
		};
		for (const key of FUN) {
			expect(personas[key].length).toBeGreaterThan(200);
			expect(withoutExamples(systemPrompt(key))).toBe(`${shared}${withoutExamples(personas[key])}\n\n${output}`);
			expect(systemPrompt(key)).not.toContain('Language:\n');
			// Each kept its one example (Haiku's is a worked syllable count instead).
			const examples = [...systemPrompt(key).matchAll(EXAMPLE)].length;
			expect(examples).toBe(key === 'fun-haiku' ? 0 : 1);
			expect([...personas[key].matchAll(EXAMPLE)].length).toBe(examples);
		}
	});
});

describe('seeding the four', () => {
	test('added once to a 0.9.9 install; the next start adds nothing', async () => {
		const pc = install(
			[{ id: 'mine', title: 'Casual Message Tidy-up' }],
			['ai-prompt', 'email', 'work-chat', 'notes', 'concise'],
		);
		const first = await seedBuiltInStyles(pc.deps);
		// 0.9.13: and Clean, from the pack.
		expect(first.added).toEqual([...EVERYDAY, 'clean']);
		expect(pc.marker).toEqual(expect.arrayContaining([...EVERYDAY]));
		const second = await seedBuiltInStyles(pc.deps);
		expect(second.added).toEqual([]);
		expect(pc.creates).toBe(5);
		expect(pc.db.get('vf-builtin-friends')!.title).toBe('Friends');
		// His own style is untouched.
		expect(pc.db.get('mine')!.title).toBe('Casual Message Tidy-up');
	});

	test('a deleted one never comes back', async () => {
		const pc = install();
		await seedBuiltInStyles(pc.deps);
		for (const key of EVERYDAY) pc.db.delete(`vf-builtin-${key}`);
		expect((await seedBuiltInStyles(pc.deps)).added).toEqual([]);
		for (const key of EVERYDAY) expect(pc.db.has(`vf-builtin-${key}`)).toBe(false);
	});

	test('an edited one is never written over', async () => {
		const pc = install();
		await seedBuiltInStyles(pc.deps);
		const edited = { ...pc.db.get('vf-builtin-partner')!, title: 'Love notes', description: 'mine' };
		pc.db.set(edited.id, edited);
		const creates = pc.creates;
		await seedBuiltInStyles(pc.deps);
		expect(pc.db.get('vf-builtin-partner')).toBe(edited);
		expect(pc.creates).toBe(creates);
	});

	test('one he already has, by name or by Android preset, is skipped', async () => {
		const pc = install([
			{ id: 'a', title: 'Professional' },
			{ id: 'b', title: 'spouse' },
		]);
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).toEqual(expect.arrayContaining(['friends', 'family']));
		expect(result.added).not.toContain('work');
		expect(result.added).not.toContain('partner');
		expect(result.skipped.filter((s) => s.reason === 'same-name').map((s) => s.key)).toEqual(['work', 'partner']);
	});

	test('"Casual Message Tidy-up" is a general tidy-up, not the Friends preset: Friends is still added', () => {
		expect(presetKindOf('Casual Message Tidy-up')).toBe('custom');
		expect(isEquivalentName(byKey('friends'), 'Casual Message Tidy-up')).toBe(false);
		// A style named for the preset itself does count.
		expect(isEquivalentName(byKey('friends'), 'Casual')).toBe(true);
		expect(isEquivalentName(byKey('friends'), 'friend')).toBe(true);
		// A word-list style counts by its preset too (passed as the preset id).
		expect(isEquivalentName(byKey('partner'), 'partner')).toBe(true);
		expect(isEquivalentName(byKey('work'), 'business')).toBe(true);
		// Not the same thing.
		expect(isEquivalentName(byKey('work'), 'Work chat')).toBe(false);
		expect(isEquivalentName(byKey('family'), 'Family dinner plans')).toBe(false);
	});

	test('a word-list style counts by its preset id, whatever its label (seeding and the "Added" chip)', async () => {
		const names = styleNames(['Casual Message Tidy-up'], [
			{ id: 'work', label: 'Office' },
			{ id: 'custom_1', label: 'Grocery list' },
		]);
		expect(names).toEqual(['Casual Message Tidy-up', 'Office', 'Grocery list', 'business']);
		expect(isEquivalentName(byKey('work'), 'Office')).toBe(false);
		expect(names.some((n) => isEquivalentName(byKey('work'), n))).toBe(true);
		expect(names.some((n) => isEquivalentName(byKey('friends'), n))).toBe(false);
		const pc = install();
		pc.deps.names = async () => names;
		const result = await seedBuiltInStyles(pc.deps);
		expect(result.added).not.toContain('work');
		expect(result.added).toContain('friends');
		expect(pc.marker).toContain('work');
	});

	test('the fun ones are templates only: never added, even on a fresh install', async () => {
		const pc = install();
		const result = await seedBuiltInStyles(pc.deps);
		for (const key of FUN) {
			expect(result.added).not.toContain(key);
			expect(pc.marker).not.toContain(key);
		}
		const titles = [...pc.db.values()].map((s) => s.title);
		for (const t of FUN_STYLES) {
			expect(titles).not.toContain(t.title);
			expect(BUILT_IN_STYLES).not.toContain(t);
		}
		// As a template it gets a fresh id.
		expect(builtInStyle(byKey('fun-pirate'), { id: 'fresh' }).id).toBe('fresh');
	});
});
