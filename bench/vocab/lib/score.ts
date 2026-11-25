/**
 * Scoring, kept deliberately plain:
 * - Words are compared after Unicode normalisation, lower-casing and removing
 *   punctuation; apostrophes are dropped ("Bepra's" -> "bepras", which is NOT
 *   "bepra"). No fuzzy matching, no aliases: "Zora Vin" is not "Zoravyn".
 * - A designated word (a target or protected word) is RIGHT in an output when
 *   it appears at least as often as in the script sentence.
 * - A never-said listed word is a word from the full 80-word list that the
 *   output has more times than the script sentence (substitutions count too).
 * - Word error rate: word-level edit distance over the script's word count.
 */
import { UNIVERSE, type Sentence } from './data';

export type Format = 'ok' | 'wrapped' | 'empty';

/**
 * The answer's text, and whether it came back in the shape asked for. The
 * gpt-audio models sometimes answer in a JSON wrapper ({"final_text": "..."},
 * {"..."}), with a bare control token (<|im_sep|>) or with nothing. Scoring
 * reads the text inside a wrapper, so word accuracy and format are measured
 * apart; every unusual shape is counted and reported.
 */
export function unwrap(raw: string | null): { text: string; format: Format } {
	const t = (raw ?? '').trim();
	if (!t || /^(<\|[^|>]*\|>\s*)+$/.test(t)) return { text: '', format: 'empty' };
	if (t.startsWith('{') && t.endsWith('}')) {
		try {
			const obj = JSON.parse(t) as Record<string, unknown>;
			for (const k of ['final_text', 'result', 'text', 'transcription', 'output', 'cleaned_text']) {
				if (typeof obj[k] === 'string') return { text: obj[k] as string, format: 'wrapped' };
			}
			const strings = Object.values(obj).filter((v): v is string => typeof v === 'string');
			if (strings.length) return { text: strings.at(-1)!, format: 'wrapped' };
		} catch {
			const inner = /^\{\s*"([\s\S]*)"\s*\}$/.exec(t);
			if (inner) return { text: inner[1]!, format: 'wrapped' };
		}
	}
	return { text: t, format: 'ok' };
}

export function tokens(text: string): string[] {
	return text
		.normalize('NFKC')
		.toLowerCase()
		.replace(/['’‘]/g, '')
		.split(/[^\p{L}\p{N}]+/u)
		.filter(Boolean);
}

/** How many times a (possibly multi-word) term occurs in a token list. */
export function count(toks: string[], term: string): number {
	const t = tokens(term);
	if (!t.length) return 0;
	let n = 0;
	for (let i = 0; i + t.length <= toks.length; i++) {
		if (t.every((w, j) => toks[i + j] === w)) n++;
	}
	return n;
}

/** Is the sentence's designated word right in this output? Null for controls. */
export function designatedRight(s: Sentence, output: string): boolean | null {
	if (!s.word) return null;
	return count(tokens(output), s.word) >= count(tokens(s.text), s.word);
}

/** Never-said listed words in the output: word -> extra count. */
export function neverSaid(s: Sentence, output: string): Record<string, number> {
	const out = tokens(output);
	const ref = tokens(s.text);
	const found: Record<string, number> = {};
	for (const w of UNIVERSE) {
		const extra = count(out, w) - count(ref, w);
		if (extra > 0) found[w] = extra;
	}
	return found;
}

export function editDistance(a: string[], b: string[]): number {
	const prev = Array.from({ length: b.length + 1 }, (_, j) => j);
	for (let i = 1; i <= a.length; i++) {
		let diag = prev[0]!;
		prev[0] = i;
		for (let j = 1; j <= b.length; j++) {
			const tmp = prev[j]!;
			prev[j] = Math.min(prev[j]! + 1, prev[j - 1]! + 1, diag + (a[i - 1] === b[j - 1] ? 0 : 1));
			diag = tmp;
		}
	}
	return prev[b.length]!;
}

/** Word errors and script words for one output (summed over sentences for a WER). */
export function wordErrors(s: Sentence, output: string): { errors: number; words: number } {
	const ref = tokens(s.text);
	return { errors: editDistance(ref, tokens(output)), words: ref.length };
}

export function median(xs: number[]): number {
	const s = [...xs].sort((a, b) => a - b);
	const m = Math.floor(s.length / 2);
	return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}
