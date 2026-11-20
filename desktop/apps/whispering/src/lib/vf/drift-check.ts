/**
 * VoiceFlow 0.9.13: the language guard. After tidy-up, a drift check; if
 * part of what was said came back in the other language (a clause of mixed
 * Chinese and English speech translated), one strict retry; if that drifts
 * too, his own words are delivered and the tidied text is held back for
 * "Use cleaned". It never asks a blocking question, and the fallback never
 * strips fillers by a list (那个, 就是 and 嗯 can be real words).
 *
 * The same logic and thresholds as the Android app (0.8.0: `DriftCheck`,
 * `DictationText.translatedAway`, `TransformClient.checkedTransform`), so
 * both apps hold back the same tidy-ups. The shared cases are in
 * `pack/vectors/drift.json`, which both apps' tests run. This is an
 * independent implementation of that behaviour, not a copy of the Android
 * source.
 *
 * It is a suspicion detector, not a guarantee. A tidy-up may delete
 * (fillers in either language, false starts, the abandoned half of a
 * self-correction), so a missing word alone never counts. What counts is a
 * word or character the speaker never said appearing in the other script.
 * It compares the transcript as captured, before saved spellings apply.
 *
 * Pure, so it is unit tested.
 */
import type { Transformation, TransformationStep } from '$lib/services/isomorphic/db';

export const REASON_NONE = '';
/** Most of one script the speaker used is gone. */
export const REASON_TRANSLATED = 'translated';
/** Chinese the speaker did not say. */
export const REASON_HAN_ADDED = 'han-added';
/** English the speaker did not say, in speech that has Chinese. */
export const REASON_LATIN_ADDED = 'latin-added';
/** One word swapped for the other script ("because" for "因为"). */
export const REASON_SWAPPED = 'swapped';

export type DriftReason =
	| typeof REASON_TRANSLATED
	| typeof REASON_HAN_ADDED
	| typeof REASON_LATIN_ADDED
	| typeof REASON_SWAPPED;

/** Added characters or words in the other script at which the result counts as drifted. */
export const ADDED_MIN = 2;
/** A tidy-up must keep at least a quarter of each script the speaker used... */
const KEEP_DIVISOR = 4;
/** ...once there is this much of it (below, a share means nothing). */
const MIN_HAN = 4;
const MIN_LATIN_WORDS = 4;

const LATIN_WORD = /[A-Za-z][A-Za-z'’-]*/g;
const HAN = /\p{Script=Han}/u;

/** Spoken English a tidy-up may delete on its own. */
const LATIN_FILLERS = new Set([
	'um', 'umm', 'uh', 'uhh', 'er', 'erm', 'hmm', 'mm', 'like', 'you', 'know', 'i', 'mean',
	'so', 'basically', 'actually', 'literally', 'okay', 'ok', 'anyway', 'yeah', 'oh', 'and',
	'also', 'well', 'right', 'just', 'side', 'note', 'no', 'wait', 'sorry', 'then', 'the', 'a',
]);

/** Chinese hesitation words and correction markers a tidy-up may delete. */
const HAN_FILLERS = ['那个', '这个', '就是', '嗯', '呃', '额', '啊', '哦', '不对', '然后'];

/**
 * Said again on the strict retry, before the style's own input: the same
 * words as Android's retry.
 */
export const STRICT_LANGUAGE_NOTE =
	'Language check: an earlier attempt translated part of this transcript. ' +
	'Return every part in the language it was spoken: Chinese stays Chinese in Simplified characters, English stays English. ' +
	'Do not translate any word, phrase or sentence.';

export type DriftResult = {
	drift: boolean;
	reason: DriftReason | typeof REASON_NONE;
	/** The transcript holds both Chinese and English. */
	mixed: boolean;
};

export function countHan(text: string | null | undefined): number {
	let n = 0;
	for (const ch of text ?? '') if (HAN.test(ch)) n++;
	return n;
}

export function countLatinWords(text: string | null | undefined): number {
	return (text ?? '').match(LATIN_WORD)?.length ?? 0;
}

export function isMixed(source: string | null | undefined): boolean {
	return countHan(source) > 0 && countLatinWords(source) > 0;
}

/** Whether the output dropped most of a script the speaker used (a translation). */
export function translatedAway(source: string | null | undefined, output: string | null | undefined): boolean {
	return translatedAwayFrom(source, source, output);
}

/**
 * 0.9.14: the same, with credits for the word list: whether there is enough
 * of a script to judge is decided on the transcript as it was (`original`),
 * the share on the texts with the credited words taken out.
 */
function translatedAwayFrom(
	original: string | null | undefined,
	source: string | null | undefined,
	output: string | null | undefined,
): boolean {
	if (countHan(original) >= MIN_HAN && countHan(output) * KEEP_DIVISOR < countHan(source)) return true;
	return (
		countLatinWords(original) >= MIN_LATIN_WORDS && countLatinWords(output) * KEEP_DIVISOR < countLatinWords(source)
	);
}

function bump(counts: Map<string, number>, key: string) {
	counts.set(key, (counts.get(key) ?? 0) + 1);
}

function hanCounts(text: string): Map<string, number> {
	const counts = new Map<string, number>();
	for (const ch of text) if (HAN.test(ch)) bump(counts, ch);
	return counts;
}

function normalizeWord(word: string): string {
	let w = word.toLowerCase().replaceAll('’', "'");
	while (w.endsWith("'") || w.endsWith('-')) w = w.slice(0, -1);
	return w === 'ok' ? 'okay' : w;
}

function latinCounts(text: string): Map<string, number> {
	const counts = new Map<string, number>();
	for (const word of text.match(LATIN_WORD) ?? []) bump(counts, normalizeWord(word));
	return counts;
}

/** How many items `out` holds beyond what `src` holds. */
function added(src: Map<string, number>, out: Map<string, number>): number {
	let extra = 0;
	for (const [key, n] of out) extra += Math.max(0, n - (src.get(key) ?? 0));
	return extra;
}

function stripHanFillers(text: string): string {
	let result = text;
	for (const filler of HAN_FILLERS) result = result.replaceAll(filler, '');
	return result;
}

function missingContentHan(src: string, out: string): number {
	return added(hanCounts(out), hanCounts(stripHanFillers(src)));
}

function missingContentLatin(src: string, out: string): number {
	const source = latinCounts(src);
	for (const filler of LATIN_FILLERS) source.delete(filler);
	return added(latinCounts(out), source);
}

// ---- 0.9.14: credits for the tidy-up's word list ---------------------------

/** One word from the tidy-up's word list (`tidy-words.ts`). */
export type ListedWord = { text: string; heardAs: readonly string[] };

const isWordChar = (c: string | undefined) => !!c && /[A-Za-z0-9]/.test(c);
/** What can continue an English word as the guard counts words (`LATIN_WORD`), and digits. */
const isTokenChar = (c: string | undefined) => !!c && /[A-Za-z0-9'’-]/.test(c);
/** Upper-case ASCII letters lowered; the length never changes. */
const foldAscii = (s: string) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());

/**
 * Where `needle` occurs in `text`: ASCII letters in any case, a whole word
 * where it starts or ends with a Latin letter or digit (so never part of a
 * longer word such as "ZDRL-ready"), never overlapping `taken`. Spans are
 * [start, end).
 */
function occurrences(text: string, needle: string, taken: Array<[number, number]>): Array<[number, number]> {
	const found: Array<[number, number]> = [];
	const n = foldAscii(needle.trim());
	if (!n) return found;
	const t = foldAscii(text);
	const edgeStart = isWordChar(n[0]);
	const edgeEnd = isWordChar(n[n.length - 1]);
	let from = 0;
	while (from <= t.length - n.length) {
		const at = t.indexOf(n, from);
		if (at < 0) break;
		const end = at + n.length;
		const whole = (!edgeStart || !isTokenChar(t[at - 1])) && (!edgeEnd || !isTokenChar(t[end]));
		const free = ![...taken, ...found].some(([s, e]) => at < e && s < end);
		if (whole && free) {
			found.push([at, end]);
			from = end;
		} else from = at + 1;
	}
	return found;
}

/** `text` with each span replaced by one space. */
function blank(text: string, spans: Array<[number, number]>): string {
	let out = text;
	for (const [s, e] of [...spans].sort((a, b) => b[0] - a[0])) out = `${out.slice(0, s)} ${out.slice(e)}`;
	return out;
}

/** A listed word (`form` false) or one of its heard-as forms, found in a text. */
type Hit = { word: number; form: boolean; span: [number, number] };

/**
 * Every listed word and heard-as form in `text`, found in the text as it is:
 * the longest first, no two overlapping. On a tie (a heard form that is also
 * another listed word's spelling), the transcript gives the span to a heard
 * form (`formsFirst`: what was misheard) and the tidy-up to a spelling (what
 * was written); then list order. Sorted by position.
 */
function hits(text: string, words: readonly ListedWord[], formsFirst: boolean): Hit[] {
	const needles: Array<{ word: number; form: boolean; text: string }> = [];
	words.forEach((w, i) => {
		const spelling = w.text.trim();
		needles.push({ word: i, form: false, text: spelling });
		for (const raw of w.heardAs) {
			const form = raw.trim();
			if (form && foldAscii(form) !== foldAscii(spelling)) needles.push({ word: i, form: true, text: form });
		}
	});
	const order = needles
		.map((needle, k) => ({ needle, k }))
		.sort(
			(a, b) =>
				b.needle.text.length - a.needle.text.length ||
				(a.needle.form === b.needle.form ? 0 : a.needle.form === formsFirst ? -1 : 1) ||
				a.k - b.k,
		);
	const taken: Array<[number, number]> = [];
	const found: Hit[] = [];
	for (const { needle } of order)
		for (const span of occurrences(text, needle.text, taken)) {
			taken.push(span);
			found.push({ word: needle.word, form: needle.form, span });
		}
	return found.sort((a, b) => a.span[0] - b.span[0]);
}

/**
 * A listed word the tidy-up wrote in place of one of its known mishearings
 * is a spelling fix, not drift. For each listed word, the credit is what
 * actually changed: no more than the heard-as forms the tidy-up removed and
 * no more than the listed word's occurrences it added. That many forms
 * (in the transcript) and occurrences of the word (in the tidy-up) are
 * taken out, each replaced by a space. A listed word with no known form
 * removed (one the model added, or a mishearing never seen before) earns no
 * credit and is checked as before. Matches are found on the texts as they
 * are, never on a text already masked.
 */
export function creditListedWords(
	source: string,
	output: string,
	words: readonly ListedWord[],
): { source: string; output: string } {
	if (words.length === 0) return { source, output };
	const inSource = hits(source, words, true);
	const inOutput = hits(output, words, false);
	const maskSource: Array<[number, number]> = [];
	const maskOutput: Array<[number, number]> = [];
	words.forEach((_, i) => {
		const formsIn = inSource.filter((h) => h.word === i && h.form);
		const formsOut = inOutput.filter((h) => h.word === i && h.form);
		const wordIn = inSource.filter((h) => h.word === i && !h.form);
		const wordOut = inOutput.filter((h) => h.word === i && !h.form);
		const credit = Math.min(
			Math.max(0, formsIn.length - formsOut.length),
			Math.max(0, wordOut.length - wordIn.length),
		);
		if (credit === 0) return;
		maskSource.push(...formsIn.slice(0, credit).map((h) => h.span));
		maskOutput.push(...wordOut.slice(0, credit).map((h) => h.span));
	});
	return { source: blank(source, maskSource), output: blank(output, maskOutput) };
}

/**
 * Whether a tidy-up changed the language of part of what was said. 0.9.14:
 * `words` (the tidy-up's word list) earns a listed word written in place of
 * one of its known mishearings a credit (`creditListedWords`); whether the
 * speech was mixed is still judged on the transcript as it was.
 */
export function checkDrift(
	source: string | null | undefined,
	output: string | null | undefined,
	words: readonly ListedWord[] = [],
): DriftResult {
	const original = source ?? '';
	const sourceHasHan = countHan(original) > 0;
	const mixed = isMixed(original);
	const credited = creditListedWords(original, output ?? '', words);
	return checkCredited(original, credited.source, credited.output, sourceHasHan, mixed);
}

function checkCredited(
	original: string,
	src: string,
	out: string,
	sourceHasHan: boolean,
	mixed: boolean,
): DriftResult {
	if (translatedAwayFrom(original, src, out)) return { drift: true, reason: REASON_TRANSLATED, mixed };
	// Chinese added to speech that was English or mixed. Pure Chinese speech
	// is edited freely in Chinese, so added characters there mean nothing.
	let hanAdded = 0;
	if (mixed || !sourceHasHan) {
		hanAdded = added(hanCounts(src), hanCounts(out));
		if (hanAdded >= ADDED_MIN) return { drift: true, reason: REASON_HAN_ADDED, mixed };
	}
	if (!sourceHasHan) return { drift: false, reason: REASON_NONE, mixed: false };
	// English added to speech that has Chinese.
	const latinAdded = added(latinCounts(src), latinCounts(out));
	if (latinAdded >= ADDED_MIN) return { drift: true, reason: REASON_LATIN_ADDED, mixed };
	// One word swapped across scripts: something added on one side while a
	// real (non-filler) word went missing on the other.
	if (latinAdded >= 1 && missingContentHan(src, out) >= 1) return { drift: true, reason: REASON_SWAPPED, mixed };
	if (hanAdded >= 1 && missingContentLatin(src, out) >= 1) return { drift: true, reason: REASON_SWAPPED, mixed };
	return { drift: false, reason: REASON_NONE, mixed };
}

// ---- the guard around a tidy-up -------------------------------------------

/** One run of the style; `strict` says the keep-the-language rule again. */
export type TidyAttempt = (
	strict: boolean,
) => Promise<{ ok: true; output: string } | { ok: false; reason: string; timedOut?: boolean }>;

export type GuardedTidyUp =
	| {
			ok: true;
			/** What to deliver: the tidy-up, or his own words after a fallback. */
			text: string;
			/** The tidy-up held back, offered as "Use cleaned"; empty when none. */
			heldBack: string;
			/** Why it fell back (a drift reason); empty when it did not. */
			fallbackReason: DriftReason | typeof REASON_NONE;
			mixed: boolean;
			retried: boolean;
	  }
	| { ok: false; reason: string; timedOut?: boolean };

/**
 * Tidy-up, then the drift check, then one strict retry. If the retry drifts
 * too, his own words win and the retry is held back. If the retry itself
 * fails (an error or a time-out), his own words win and the first tidy-up is
 * held back: nothing is lost, and nothing translated is pasted. A failure of
 * the first run is returned as it is (the usual tidy-up problem).
 *
 * `transcript` is what is delivered on a fallback (exactly as transcribed:
 * no filler is stripped by a list). `source` is what the output is compared
 * with: the transcript, with the style's own find-and-replace steps applied
 * so a replacement the style makes is not counted as drift.
 */
export async function guardedTidyUp({
	transcript,
	source = transcript,
	funStyle,
	words = [],
	attempt,
}: {
	transcript: string;
	source?: string;
	/** The fun styles are defined in English and are never checked. */
	funStyle: boolean;
	attempt: TidyAttempt;
	/** 0.9.14: the tidy-up's word list, for its credits (`checkDrift`). */
	words?: readonly ListedWord[];
}): Promise<GuardedTidyUp> {
	const first = await attempt(false);
	if (!first.ok) return first;
	if (funStyle) return { ok: true, text: first.output, heldBack: '', fallbackReason: REASON_NONE, mixed: false, retried: false };
	const firstCheck = checkDrift(source, first.output, words);
	if (!firstCheck.drift)
		return { ok: true, text: first.output, heldBack: '', fallbackReason: REASON_NONE, mixed: firstCheck.mixed, retried: false };
	const spoken = transcript.trim();
	const retry = await attempt(true);
	if (!retry.ok)
		return {
			ok: true,
			text: spoken,
			heldBack: first.output.trim(),
			fallbackReason: firstCheck.reason,
			mixed: firstCheck.mixed,
			retried: true,
		};
	const retryCheck = checkDrift(source, retry.output, words);
	if (!retryCheck.drift)
		return { ok: true, text: retry.output, heldBack: '', fallbackReason: REASON_NONE, mixed: retryCheck.mixed, retried: true };
	return {
		ok: true,
		text: spoken,
		heldBack: retry.output.trim(),
		fallbackReason: retryCheck.reason,
		mixed: retryCheck.mixed,
		retried: true,
	};
}

/**
 * The style for the strict retry: the note goes before each tidy-up
 * prompt's input (its user prompt), as Android puts it before the
 * transcript. The stored style is not changed.
 */
export function strictTransformation(style: Transformation): Transformation {
	return {
		...style,
		steps: style.steps.map((step) =>
			step.type === 'prompt_transform'
				? {
						...step,
						'prompt_transform.userPromptTemplate': `${STRICT_LANGUAGE_NOTE}\n\n${step['prompt_transform.userPromptTemplate']}`,
					}
				: step,
		),
	};
}

/**
 * The transcript with the style's find-and-replace steps applied, in order
 * (the same rules the tidy-up runs them by), for the drift check. A step
 * whose pattern is not valid is left out.
 */
export function withFindReplace(text: string, steps: readonly TransformationStep[]): string {
	let out = text;
	for (const step of steps) {
		if (step.type !== 'find_replace') continue;
		const find = step['find_replace.findText'];
		const replace = step['find_replace.replaceText'];
		if (!find) continue;
		if (step['find_replace.useRegex']) {
			try {
				out = out.replace(new RegExp(find, 'g'), replace);
			} catch {
				// An invalid pattern fails the tidy-up itself; nothing to compare.
			}
		} else out = out.replaceAll(find, replace);
	}
	return out;
}

/** Plain words for a fallback reason, for History and the notice's tooltip. */
export function driftReasonText(reason: string | null | undefined): string {
	switch (reason) {
		case REASON_TRANSLATED:
			return 'Tidy-up translated what you said';
		case REASON_HAN_ADDED:
			return 'Tidy-up put part of what you said into Chinese';
		case REASON_LATIN_ADDED:
			return 'Tidy-up put part of what you said into English';
		case REASON_SWAPPED:
			return 'Tidy-up swapped a word into the other language';
		default:
			return 'Tidy-up changed the language of part of what you said';
	}
}
