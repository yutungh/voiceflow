/**
 * The shared prompt vectors in `pack/vectors/wordlists.json` (added with
 * VoiceFlow Android 0.8.2): the Android keyboard and this app must build the
 * same vocabulary line and keywords from the same words.
 */
import { describe, expect, test } from 'bun:test';
import vectors from '../../../../../../pack/vectors/wordlists.json';
import { transcriptionKeywords } from '../services/isomorphic/transcription/cloud/keywords';
import { buildPrompt } from './prompt';
import { tidyWordsFor } from './tidy-words';
import type { ProfileSnapshot, ProfileTerm } from './types';

type PromptCase = {
	name: string;
	promptChars: number;
	promptTerms: number;
	app: string | null;
	terms: Array<Partial<ProfileTerm> & Pick<ProfileTerm, 'id' | 'text' | 'priority' | 'apps' | 'layer'>>;
	expect: { prompt: string; sent: string[]; keywords: string[] };
};

type SplitCase = Omit<PromptCase, 'expect'> & {
	expect: { prompt: string; sent: string[]; keywords: string[]; tidyWords: string[] };
};

describe('pack/vectors/wordlists.json prompt cases', () => {
	const cases = (vectors as { prompt: PromptCase[] }).prompt;
	test('there are cases', () => {
		expect(cases.length).toBeGreaterThan(0);
	});
	for (const c of cases) {
		test(c.name, () => {
			const terms = c.terms.map((t) => ({ heardAs: [], ...t }) as ProfileTerm);
			const snapshot: Pick<ProfileSnapshot, 'terms' | 'defaults'> = {
				terms,
				defaults: { promptCharsEffective: c.promptChars, promptTermsEffective: c.promptTerms, fromLayer: {} },
			};
			const built = buildPrompt(snapshot, c.app);
			expect(built.prompt).toBe(c.expect.prompt);
			expect(built.sent).toEqual(c.expect.sent);
			expect(transcriptionKeywords('gpt-transcribe', built.sent)).toEqual(c.expect.keywords);
		});
	}
});

describe('pack/vectors/wordlists.json split cases (0.9.15: 20 words to the transcriber, every chosen word to the tidy-up)', () => {
	const cases = (vectors as unknown as { split: SplitCase[] }).split;
	test('there are cases', () => {
		expect(cases.length).toBeGreaterThan(0);
	});
	for (const c of cases) {
		test(c.name, () => {
			const terms = c.terms.map((t) => ({ heardAs: [], ...t }) as ProfileTerm);
			const snapshot: Pick<ProfileSnapshot, 'terms' | 'defaults'> = {
				terms,
				defaults: { promptCharsEffective: c.promptChars, promptTermsEffective: c.promptTerms, fromLayer: {} },
			};
			const built = buildPrompt(snapshot, c.app);
			expect(built.prompt).toBe(c.expect.prompt);
			expect(built.sent).toEqual(c.expect.sent);
			expect(transcriptionKeywords('gpt-transcribe', built.sent)).toEqual(c.expect.keywords);
			expect(tidyWordsFor(built.chosen).words.map((w) => w.text)).toEqual(c.expect.tidyWords);
		});
	}
});
