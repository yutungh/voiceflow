/**
 * Candidate fixes for the tidy-up turning a correctly heard word into a listed
 * sound-alike (Beeper -> Bepra). Each builds the same system prompt as
 * VoiceFlow (Clean + the word block from tidyWordsFor) with one change:
 *
 * - Reworded instruction: the "Fixing misheard words" rules with the two
 *   bullets that decide when to replace a word rewritten, so a real word or
 *   name that fits the sentence is never changed. Three wordings (A, B, C)
 *   were written before any result; the one used on a person's recordings is
 *   picked on the synthetic pilot only (choose.ts).
 * - No everyday-word hints: a heard-as form is dropped when every word in it
 *   is a common English word, by SCOWL's "english" and "american" lists up to
 *   size 60 (the size SCOWL recommends for spell checking; npm
 *   wordlist-english 1.2.1). Proper names are not in those lists, so "Miro" or
 *   "Jethro" stay. Run `bun lib/tidyfix.ts` to print what the rule drops.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { WORDS, type Word } from './data';
import { snapshot, tidyPrompts } from './prompts';
import { buildPrompt } from '../../../desktop/apps/whispering/src/lib/profile/prompt';
import { TIDY_WORDS_RULE, tidyWordsFor } from '../../../desktop/apps/whispering/src/lib/profile/tidy-words';

// ---- the everyday-word rule ----------------------------------------------------

const SCOWL_SIZES = [10, 20, 35, 40, 50, 55, 60];
let common: Set<string> | null = null;

function commonWords(): Set<string> {
	if (common) return common;
	const dir = path.dirname(createRequire(import.meta.url).resolve('wordlist-english/package.json'));
	common = new Set<string>();
	for (const kind of ['english', 'american'])
		for (const n of SCOWL_SIZES) for (const w of JSON.parse(readFileSync(path.join(dir, `${kind}-words-${n}.json`), 'utf8')) as string[]) common.add(w);
	return common;
}

/** Every word of the form is a common English word (case ignored). */
export function isEverydayForm(form: string): boolean {
	const words = form.trim().split(/\s+/).filter(Boolean);
	return words.length > 0 && words.every((w) => commonWords().has(w.toLowerCase()));
}

/** The profile with every heard-as form that is an everyday word removed. */
export function withoutEverydayHints(words: Word[] = WORDS): Word[] {
	return words.map((w) => ({ ...w, heardAs: (w.heardAs ?? []).filter((h) => !isEverydayForm(h)) }));
}

export function droppedForms(words: Word[] = WORDS): { word: string; form: string }[] {
	return words.flatMap((w) => (w.heardAs ?? []).filter(isEverydayForm).map((form) => ({ word: w.text, form })));
}

// ---- reworded instruction ------------------------------------------------------

/** The two bullets of TIDY_WORDS_RULE that decide when a word is replaced (2nd and 3rd lines of it). */
const RULE_LINES = TIDY_WORDS_RULE.split('\n');
const DECIDING = RULE_LINES.slice(2, 4);

export const WORDINGS: Record<'A' | 'B' | 'C', string[]> = {
	A: [
		'- Replace a word or phrase in the transcript with a listed word only when the transcript\'s word is not a real word or name, or is a real one that does not fit the sentence, and the listed word sounds like it and fits the sentence and its context.',
		"- A real word or name that fits the sentence is correct as written: keep it, even when it sounds like a listed word, and even when a note says speech-to-text once wrote it for that word. If both readings are plausible, keep the transcript's words.",
	],
	B: [
		"- Before replacing anything, ask whether the transcript's word is a real word or a known name (a product, an app, a place, an everyday word) that makes sense in this sentence. If it is, keep it exactly. Only if it is not, and a listed word sounds like it and fits, write the listed word.",
		'- The notes on what speech-to-text wrote before describe past mistakes, not this transcript. A match with a note is never a reason on its own to change a word that fits the sentence.',
	],
	C: [
		'- Change a word to a listed word only to repair a clear mishearing: something that is not a real word, a name broken into pieces, or a word that makes no sense in the sentence.',
		"- Never change a real word or name that fits the sentence, even if it sounds like a listed word or was once written for one. When unsure, keep the transcript's words.",
	],
};

export function rule(wording: keyof typeof WORDINGS): string {
	return [...RULE_LINES.slice(0, 2), ...WORDINGS[wording], ...RULE_LINES.slice(4)].join('\n');
}

export type Variant = { hints: 'all' | 'no-everyday'; wording?: keyof typeof WORDINGS };

/** The tidy-up system prompt with one or both fixes. */
export function variantSystem(v: Variant): string {
	const words = v.hints === 'all' ? WORDS : withoutEverydayHints();
	const built = buildPrompt(snapshot(words, true), null);
	let block = tidyWordsFor(built.chosen).text;
	if (v.wording) {
		if (!block.includes(TIDY_WORDS_RULE)) throw new Error('word block no longer ends with TIDY_WORDS_RULE');
		block = block.replace(TIDY_WORDS_RULE, rule(v.wording));
	}
	return `${tidyPrompts().style.trimEnd()}\n\n${block}`;
}

if (import.meta.main) {
	console.log('Heard-as forms dropped by the everyday-word rule (SCOWL english+american, size <= 60):');
	for (const d of droppedForms()) console.log(`  ${d.word}: "${d.form}"`);
	console.log('\nKept:');
	for (const w of WORDS) for (const h of w.heardAs ?? []) if (!isEverydayForm(h)) console.log(`  ${w.text}: "${h}"`);
	console.log('\nReplaced bullets:\n' + DECIDING.join('\n'));
}
