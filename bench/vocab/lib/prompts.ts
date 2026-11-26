/**
 * The requests exactly as VoiceFlow builds them, from VoiceFlow's own code.
 *
 * - Speech step: the `Vocabulary: A, B, C` line and `keywords[]`
 *   (desktop/.../profile/prompt.ts, .../transcription/cloud/keywords.ts).
 *   `buildPrompt` caps the transcriber at 40 words since 0.9.15, so the test
 *   builds the 0/50/80-word requests itself, in the same format and order;
 *   the self-test checks the 20- and 40-word requests match `buildPrompt`.
 * - Tidy-up: the default style "Clean" (pack/builtin/styles.json) wrapped as
 *   the desktop app sends it (`everyday()` in vf/builtin-styles.ts), then the
 *   word block from the real `tidyWordsFor` (profile/tidy-words.ts).
 *   builtin-styles.ts can't be imported outside the app (it needs the app's
 *   packages), so its three text constants are read from the source file.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { BENCH_DIR, VF_LIB } from './paths';
import { listOf, WORDS, type Word } from './data';
import { buildPrompt, PROMPT_PREFIX } from '../../../desktop/apps/whispering/src/lib/profile/prompt';
import { tidyWordsFor } from '../../../desktop/apps/whispering/src/lib/profile/tidy-words';
import { transcriptionKeywords } from '../../../desktop/apps/whispering/src/lib/services/isomorphic/transcription/cloud/keywords';
import type { ProfileSnapshot, ProfileTerm } from '../../../desktop/apps/whispering/src/lib/profile/types';

export const TRANSCRIBE_MODEL = 'gpt-transcribe';
export const TIDY_MODEL = 'gpt-5.4-mini';
export const OPUS_MODEL = 'claude-opus-5-5';
export const AUDIO_MODEL = 'gpt-audio-1.5';

/** A profile big enough to hold all 80 words (VoiceFlow's default is 600 characters / 40 words). */
export const PROFILE_CHARS = 1200;
export const PROFILE_TERMS = 80;

/** The words as VoiceFlow profile terms: every app, priority by position (first = most important). */
export function profileTerms(words: Word[], withHints: boolean): ProfileTerm[] {
	return words.map((w, i) => ({
		id: `t${String(i + 1).padStart(2, '0')}`,
		text: w.text,
		heardAs: withHints ? (w.heardAs ?? []) : [],
		kind: w.kind,
		priority: 1000 - i,
		apps: ['*'],
		layer: 'personal',
	}));
}

export function snapshot(words: Word[], withHints: boolean): Pick<ProfileSnapshot, 'terms' | 'defaults'> {
	return {
		terms: profileTerms(words, withHints),
		defaults: { promptCharsEffective: PROFILE_CHARS, promptTermsEffective: PROFILE_TERMS, fromLayer: {} },
	};
}

export type SpeechPayload = { prompt: string; keywords: string[] };

/**
 * The speech request for a list of n words: the first n profile words, sent
 * most important LAST (buildPrompt's order), plain (no hints), as both the
 * line and keywords[]. n = 0 sends neither.
 */
export function speechPayload(n: number): SpeechPayload {
	if (n === 0) return { prompt: '', keywords: [] };
	const sent = listOf(n)
		.map((w) => w.text)
		.reverse();
	return {
		prompt: `${PROMPT_PREFIX}${sent.join(', ')}`,
		keywords: transcriptionKeywords(TRANSCRIBE_MODEL, sent),
	};
}

/** buildPrompt's own output for comparison (it allows at most 40). */
export function speechPayloadFromApp(n: number): SpeechPayload {
	const built = buildPrompt(snapshot(listOf(80), false), null, n);
	return { prompt: built.prompt, keywords: transcriptionKeywords(TRANSCRIBE_MODEL, built.sent) };
}

// ---- tidy-up -----------------------------------------------------------------

const STYLES_SRC = path.join(VF_LIB, 'vf', 'builtin-styles.ts');
const PACK = path.resolve(BENCH_DIR, '..', '..', 'pack', 'builtin', 'styles.json');

/** A `const NAME = \`...\`;` template literal from builtin-styles.ts, with ${OTHER} filled in. */
function templateConst(src: string, name: string, seen = new Set<string>()): string {
	const m = new RegExp(`const ${name} = \`([\\s\\S]*?)\`;`).exec(src);
	if (!m) throw new Error(`builtin-styles.ts: ${name} not found`);
	if (seen.has(name)) throw new Error(`cycle at ${name}`);
	seen.add(name);
	// JavaScript turns a CRLF inside a template literal into LF; do the same
	// for a Windows checkout.
	return m[1]!.replace(/\r\n?/g, '\n').replace(/\$\{([A-Z_]+)\}/g, (_, other: string) => templateConst(src, other, seen));
}

export type TidyPrompts = {
	/** Clean as the desktop app stores it: prompt + language contract + output contract. */
	style: string;
	/** The user message template, with {{input}}. */
	user: string;
	/** The word block for the 80-word profile. */
	block: string;
	/** Words whose note fitted in the block. */
	noted: string[];
};

const cached = new Map<boolean, TidyPrompts>();

/** `hints` false: the same list with no heard-as notes (the no-hint variant). */
export function tidyPrompts(hints = true): TidyPrompts {
	const hit = cached.get(hints);
	if (hit) return hit;
	const src = readFileSync(STYLES_SRC, 'utf8');
	const language = templateConst(src, 'ANDROID_LANGUAGE_CONTRACT');
	const output = templateConst(src, 'ANDROID_OUTPUT_CONTRACT');
	const user = templateConst(src, 'USER_PROMPT');
	const pack = JSON.parse(readFileSync(PACK, 'utf8'));
	const clean = pack.styles.find((s: { id: string }) => s.id === 'clean');
	if (!clean) throw new Error('pack: no Clean style');
	// everyday(): `${prompt}\n\n${ANDROID_LANGUAGE_CONTRACT}\n\n${ANDROID_OUTPUT_CONTRACT}` (prompt trimmed, packBuiltIn)
	const style = `${String(clean.prompt).trim()}\n\n${language}\n\n${output}`;
	const built = buildPrompt(snapshot(WORDS, hints), null);
	if (built.chosen.length !== WORDS.length || built.skipped.length) {
		throw new Error(`profile budget dropped words: chosen ${built.chosen.length}, skipped ${built.skipped.join(', ')}`);
	}
	const tidy = tidyWordsFor(built.chosen);
	if (tidy.words.length !== WORDS.length) throw new Error(`tidy block holds ${tidy.words.length} of ${WORDS.length} words`);
	const noted = tidy.text
		.split('\n')
		.filter((l) => /^- .+ \(.+\)$/.test(l))
		.map((l) => l.slice(2, l.indexOf(' (')));
	const result = { style, user, block: tidy.text, noted };
	cached.set(hints, result);
	return result;
}

/** withTidyWords(): the style, trimmed, then a blank line and the block. */
export function tidySystem(hints = true): string {
	const t = tidyPrompts(hints);
	return `${t.style.trimEnd()}\n\n${t.block}`;
}

export function tidyUser(transcript: string): string {
	return tidyPrompts().user.replace('{{input}}', transcript);
}

/**
 * Added to the matched-model arms (same model with and without the recording),
 * so the recording is the only difference between them. Agreed in the design
 * review; not part of VoiceFlow today.
 */
export const AUDIO_ADDENDUM =
	'The transcript is a fallible draft. If a recording is attached, use it as the authority for what was spoken, including when text alone is ambiguous. Return only the cleaned text.';

/** The user message of the direct-audio arm, which gets the recording and no transcript. */
export const DIRECT_AUDIO_USER =
	'Transcribe the attached recording, then apply the cleaning and vocabulary rules. Return only the final text.';
