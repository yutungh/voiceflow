/**
 * VoiceFlow 0.9.14: the tidy-up's word list. The shared vectors
 * (`pack/vectors/tidywords.json`, which the Android keyboard runs too) prove
 * both apps send the same block from the same words; the rest covers how
 * this app adds it to a style.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Transformation, TransformationStep } from '$lib/services/isomorphic/db';
import { buildPrompt } from './prompt';
import {
	TIDY_NOTE_CHARS,
	TIDY_WORDS_CHARS,
	TIDY_WORDS_HEADER,
	TIDY_WORDS_RULE,
	tidyNote,
	tidyWordsBlock,
	tidyWordsFor,
	withTidyWords,
} from './tidy-words';
import type { ProfileSnapshot, ProfileTerm } from './types';

type BlockCase = {
	name: string;
	promptChars: number;
	promptTerms: number;
	tidyChars: number;
	app: string | null;
	terms: Array<Partial<ProfileTerm> & Pick<ProfileTerm, 'id' | 'text' | 'priority' | 'apps' | 'layer'>>;
	expect: { lines: string[]; words: Array<{ text: string; heardAs: string[] }> };
};

const vectors = JSON.parse(
	readFileSync(join(import.meta.dir, '../../../../../../pack/vectors/tidywords.json'), 'utf8'),
) as { header: string; rule: string; block: BlockCase[] };

const snap = (c: Pick<BlockCase, 'terms' | 'promptChars' | 'promptTerms'>): Pick<ProfileSnapshot, 'terms' | 'defaults'> => ({
	terms: c.terms.map((t) => ({ heardAs: [], ...t }) as ProfileTerm),
	defaults: { promptCharsEffective: c.promptChars, promptTermsEffective: c.promptTerms, fromLayer: {} },
});

describe('the shared vectors (pack/vectors/tidywords.json)', () => {
	test('the header and the rule are the text both apps send', () => {
		expect(TIDY_WORDS_HEADER).toBe(vectors.header);
		expect(TIDY_WORDS_RULE).toBe(vectors.rule);
		expect(vectors.block.length).toBeGreaterThanOrEqual(6);
	});
	for (const c of vectors.block) {
		test(c.name, () => {
			const built = tidyWordsFor(buildPrompt(snap(c), c.app).chosen, c.tidyChars);
			expect(built.words).toEqual(c.expect.words);
			expect(built.text).toBe(
				c.expect.lines.length ? `${vectors.header}\n${c.expect.lines.join('\n')}\n\n${vectors.rule}` : '',
			);
		});
	}
});

describe('the block on this PC', () => {
	const words: BlockCase['terms'] = [
		{ id: 'a', text: 'ZDRL', heardAs: ['Z-drill'], kind: 'acronym', priority: 90, apps: ['*'], layer: 'personal' },
		{ id: 'b', text: 'Kestrel', priority: 10, apps: ['teamchat.exe'], layer: 'personal' },
	];

	test('built from the pinned snapshot, with the app’s words; none without a snapshot', () => {
		const pinned = snap({ terms: words, promptChars: 600, promptTerms: 40 });
		expect(tidyWordsBlock(pinned, 'teamchat.exe').words.map((w) => w.text)).toEqual(['Kestrel', 'ZDRL']);
		expect(tidyWordsBlock(pinned, 'notes.exe').words.map((w) => w.text)).toEqual(['ZDRL']);
		expect(tidyWordsBlock(null, 'teamchat.exe')).toEqual({ text: '', words: [] });
	});

	test('the list keeps to its budget by default', () => {
		const many = Array.from({ length: 400 }, (_, i) => ({
			id: `w${i}`,
			text: `Wordy${i}`,
			gloss: 'a fairly long gloss that says what this invented word is about',
			priority: 50,
			apps: ['*'],
			layer: 'personal' as const,
		}));
		const built = tidyWordsBlock(snap({ terms: many, promptChars: 100_000, promptTerms: 400 }), null);
		const list = built.text.slice(TIDY_WORDS_HEADER.length + 1, built.text.length - TIDY_WORDS_RULE.length - 2);
		expect(list.length).toBeLessThanOrEqual(TIDY_WORDS_CHARS);
		expect(built.words.length).toBeGreaterThan(100);
	});

	test('a note never passes its limit', () => {
		const note = tidyNote({
			text: 'Flumox',
			gloss: 'x'.repeat(200),
			heardAs: ['Flu Mocks', 'Flew Mox'],
		});
		expect(note!.length).toBeLessThanOrEqual(TIDY_NOTE_CHARS);
	});

	const step = (over: Partial<TransformationStep> = {}): TransformationStep =>
		({
			id: 's1',
			type: 'prompt_transform',
			'prompt_transform.systemPromptTemplate': 'Tidy it up.\n',
			'prompt_transform.userPromptTemplate': '<transcript>{{input}}</transcript>',
			...over,
		}) as TransformationStep;
	const style = (steps: TransformationStep[]): Transformation =>
		({ id: 't', title: 'Clean', description: '', createdAt: '', updatedAt: '', steps }) as Transformation;

	test('goes after each tidy-up prompt’s own instructions; the stored style is not changed', () => {
		const original = style([step(), step({ id: 's2', type: 'find_replace' } as Partial<TransformationStep>)]);
		const withWords = withTidyWords(original, 'BLOCK');
		expect(withWords.steps[0]!['prompt_transform.systemPromptTemplate']).toBe('Tidy it up.\n\nBLOCK');
		expect(withWords.steps[0]!['prompt_transform.userPromptTemplate']).toBe('<transcript>{{input}}</transcript>');
		expect(withWords.steps[1]).toBe(original.steps[1]);
		expect(original.steps[0]!['prompt_transform.systemPromptTemplate']).toBe('Tidy it up.\n');
		expect(withTidyWords(original, '')).toBe(original);
	});
});

describe('the Android source agrees', () => {
	const java = readFileSync(
		join(import.meta.dir, '../../../../../../app/src/main/java/com/voiceflowkeyboard/ime/TidyWords.java'),
		'utf8',
	);
	test('the same budgets', () => {
		expect(java).toContain(`static final int TIDY_WORDS_CHARS = ${TIDY_WORDS_CHARS};`);
		expect(java).toContain(`static final int TIDY_NOTE_CHARS = ${TIDY_NOTE_CHARS};`);
	});
});
