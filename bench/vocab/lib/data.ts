/** The script and the word list, loaded and checked. */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { BENCH_DIR } from './paths';

export type Word = {
	text: string;
	kind: 'person' | 'product';
	role: 'target' | 'padding';
	pair?: string;
	say?: string;
	heardAs?: string[];
};

export type Sentence = {
	id: string;
	type: 'target' | 'protected' | 'control';
	text: string;
	/** The designated word of a target or protected sentence. */
	word?: string;
	/** For a protected sentence: the listed name it sounds like. */
	pair?: string;
};

export const WORDS: Word[] = JSON.parse(readFileSync(path.join(BENCH_DIR, 'words.json'), 'utf8')).words;
export const SENTENCES: Sentence[] = JSON.parse(readFileSync(path.join(BENCH_DIR, 'script.json'), 'utf8')).sentences;

/** The list sizes tested in the speech step. */
export const LIST_SIZES = [0, 20, 40, 50, 80] as const;
export type ListSize = (typeof LIST_SIZES)[number];

/** Every listed word: the universe "never-said listed words" are counted against, in every condition. */
export const UNIVERSE: string[] = WORDS.map((w) => w.text);

/** The first n entries of the profile, most important first. */
export function listOf(n: number): Word[] {
	return WORDS.slice(0, n);
}

/** A target sentence's listed name made into the transcript of its sound-alike (the constructed panel). */
export function constructedTranscript(s: Sentence): string | null {
	if (s.type !== 'target' || !s.word) return null;
	const w = WORDS.find((x) => x.text === s.word);
	if (!w?.pair) return null;
	return s.text.replace(new RegExp(`\\b${s.word}\\b`), w.pair);
}

export function sentence(id: string): Sentence {
	const s = SENTENCES.find((x) => x.id === id);
	if (!s) throw new Error(`no sentence ${id}`);
	return s;
}
