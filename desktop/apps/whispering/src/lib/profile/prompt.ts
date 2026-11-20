/**
 * VoiceFlow word lists: the transcription prompt for one dictation
 * (SPEC §3.4, as revised by plan v2).
 *
 * 1. Select: words for the app being dictated into first (words whose
 *    `apps` name it), then words for every app by priority, within the
 *    budget (`promptChars` characters for the whole line, `promptTerms`
 *    words). A word that doesn't fit is skipped and the next one tried.
 * 2. Order: most relevant last: every-app words first (lowest priority
 *    first), the app's own words at the end. Each word appears once.
 * 3. Format: one line, `Vocabulary: A, B, C`. No sentences, which a model
 *    can echo back on silence.
 * 4. VoiceFlow 0.9.11, pronunciation hints: a word can carry a short hint in
 *    brackets, `ZDRL (said "zee-drul"; not "Z-drill")` (`termHint`). A word
 *    whose hint doesn't fit the budget goes in plain.
 * 5. VoiceFlow 0.9.15, two lists: every word chosen in step 1 goes to the
 *    tidy-up (`chosen`, `tidy-words.ts`), but the transcription request (the
 *    line and its `keywords[]`) carries only the first `TRANSCRIBE_TERMS` of
 *    them, in the same order (the app's own words first, then priority). On
 *    real recordings 50 or 80 words made gpt-transcribe miss or invent words,
 *    and 20 got them right; the break came between 40 and 50.
 */
import type { ProfileSnapshot, ProfileTerm } from './types';

export const PROMPT_PREFIX = 'Vocabulary: ';

/**
 * VoiceFlow 0.9.15: the most words the transcription request carries (the
 * line and `keywords[]`). A code constant, not a profile field: Android 0.8.x
 * refuses a profile with a field it doesn't know. A later profile version may
 * override it (see pack/README.md), never past `TRANSCRIBE_TERMS_MAX`.
 */
export const TRANSCRIBE_TERMS = 20;
/** The hard cap on the transcription request's words, whatever asks for more. */
export const TRANSCRIBE_TERMS_MAX = 40;

/** The transcription request's word count: the default, or an override within 1..TRANSCRIBE_TERMS_MAX. */
export function transcribeTermsLimit(requested?: number | null): number {
	if (requested == null || !Number.isFinite(requested)) return TRANSCRIBE_TERMS;
	return Math.min(TRANSCRIBE_TERMS_MAX, Math.max(1, Math.floor(requested)));
}
const EVERY_APP = '*';

const norm = (s: string) => s.trim().replace(/\s+/g, ' ').toLocaleLowerCase();

/**
 * A word as it goes into the one-line prompt: line breaks, other control and
 * format characters (line/paragraph separators, bidi marks) become a space.
 * The desktop app already refuses them in word lists; this is the last guard.
 */
export function promptWord(text: string): string {
	return text
		.replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+/gu, ' ')
		.replace(/\s+/g, ' ')
		.trim();
}

/**
 * A gloss that says how the word sounds (any layer may give one): a
 * pronunciation word, or a matched quotation ("win", 'zee-drul').
 */
export const PRONUNCIATION =
	/\b(said|pronounced|pronounce|sounds? like|rhymes? with|spoken as|one word|spelled)\b|"[^"]+"|“[^”]+”|(^|\s)'[^'\s][^']*'/i;

/** The longest hint, so one word can't take the whole budget. */
export const MAX_HINT_CHARS = 120;

/** Hint text: one line, no brackets (they delimit the hint) and no double quotes. */
function hintText(text: string): string {
	return promptWord(text).replace(/[()]/g, '').replace(/"/g, "'").trim();
}

/**
 * VoiceFlow 0.9.11: the pronunciation hint for a word, or null.
 *
 * - The gloss, when it comes from the person's own layers (personal, edit)
 *   or, from any layer, when it says how the word sounds (`PRONUNCIATION`).
 *   A generated or household gloss such as "work acronym" adds nothing and
 *   is left out.
 * - The `heardAs` forms, as `not "Z-drill"`: what the speech model heard
 *   before. Whole forms only, as many as fit in `MAX_HINT_CHARS` after the
 *   gloss (a gloss is at most 80 characters); one is never cut in half.
 */
export function termHint(term: Pick<ProfileTerm, 'gloss' | 'heardAs' | 'layer' | 'text'>): string | null {
	const own = term.layer === 'personal' || term.layer === 'edit';
	const glossText = term.gloss ? hintText(term.gloss).slice(0, 80) : '';
	const gloss = glossText && (own || PRONUNCIATION.test(term.gloss ?? '')) ? glossText : '';
	const word = norm(term.text);
	const heard = [...new Set((term.heardAs ?? []).map(hintText).filter((h) => h && norm(h) !== word))];
	let hint = gloss;
	let forms = '';
	for (const form of heard) {
		const nextForms = forms ? `${forms} or "${form}"` : `not "${form}"`;
		const next = gloss ? `${gloss}; ${nextForms}` : nextForms;
		if (next.length > MAX_HINT_CHARS) break;
		forms = nextForms;
		hint = next;
	}
	return hint || null;
}

function byPriority(a: ProfileTerm, b: ProfileTerm) {
	return (
		b.priority - a.priority ||
		// English rules, not the computer's locale: the Android keyboard sorts
		// ties with an English collator, and since 0.9.15 a tie at the 20th word
		// decides which word the transcriber gets.
		a.text.localeCompare(b.text, 'en', { sensitivity: 'base' })
	);
}

export type BuiltPrompt = {
	/** The transcription request's line (at most `TRANSCRIBE_TERMS` words). */
	prompt: string;
	/** Words sent to the transcriber, in prompt order (also its `keywords[]`). */
	sent: string[];
	/** Words left out of `chosen` because of the budget (`promptChars`, `promptTerms`). */
	skipped: string[];
	/**
	 * 0.9.14: every word chosen within the budget, as entries, most important
	 * first (the order they were chosen in), for the tidy-up's word list
	 * (`tidy-words.ts`). 0.9.15: the transcriber gets only the first
	 * `TRANSCRIBE_TERMS` of them; the tidy-up gets them all.
	 */
	chosen: ProfileTerm[];
};

export function buildPrompt(
	snapshot: Pick<ProfileSnapshot, 'terms' | 'defaults'>,
	appId: string | null | undefined,
	transcribeTerms: number = TRANSCRIBE_TERMS,
): BuiltPrompt {
	const maxChars = snapshot.defaults.promptCharsEffective;
	const maxTerms = snapshot.defaults.promptTermsEffective;
	const app = appId?.toLowerCase() ?? null;

	const forApp = app
		? snapshot.terms
				.filter((t) => !t.apps.includes(EVERY_APP) && t.apps.includes(app))
				.sort(byPriority)
		: [];
	const forAll = snapshot.terms
		.filter((t) => t.apps.includes(EVERY_APP))
		.sort(byPriority);

	// 1. Select, within the whole budget: the tidy-up's list.
	const seen = new Set<string>();
	const skipped: string[] = [];
	let used = PROMPT_PREFIX.length;
	let count = 0;
	const chosenTerms: Array<{ text: string; term: ProfileTerm; own: boolean }> = [];
	const consider = (term: ProfileTerm, own: boolean) => {
		const text = promptWord(term.text);
		const key = norm(text);
		if (!text || seen.has(key)) return;
		const cost = text.length + (count > 0 ? 2 : 0);
		if (count >= maxTerms || used + cost > maxChars) {
			skipped.push(text);
			return;
		}
		seen.add(key);
		chosenTerms.push({ text, term, own });
		used += cost;
		count += 1;
	};
	for (const term of forApp) consider(term, true);
	for (const term of forAll) consider(term, false);

	// 2. The transcription request: the first words chosen, plain, so hints
	// never push a word out. Selection is cumulative, so these are exactly the
	// words a budget of this many words would have chosen.
	const request = chosenTerms.slice(0, transcribeTermsLimit(transcribeTerms));
	let line = PROMPT_PREFIX.length;
	request.forEach(({ text }, i) => {
		line += text.length + (i > 0 ? 2 : 0);
	});

	// 3. Then hints, most important word first, while they fit.
	const entries = new Map<string, string>();
	for (const { text, term } of request) {
		const hint = termHint(term);
		if (!hint) continue;
		const extra = hint.length + 3; // " (" and ")"
		if (line + extra > maxChars) continue;
		entries.set(text, `${text} (${hint})`);
		line += extra;
	}

	// Most relevant last: every-app words, then the app's own words.
	const sent = [
		...request.filter((c) => !c.own).map((c) => c.text).reverse(),
		...request.filter((c) => c.own).map((c) => c.text).reverse(),
	];
	return {
		prompt:
			sent.length > 0
				? `${PROMPT_PREFIX}${sent.map((w) => entries.get(w) ?? w).join(', ')}`
				: '',
		sent,
		skipped,
		chosen: chosenTerms.map(({ term }) => term),
	};
}
