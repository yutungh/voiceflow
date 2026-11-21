/**
 * VoiceFlow feedback v2 (phase 1): what changed at each stage of one
 * dictation, computed by code, never by the model.
 *
 * The stages are: what speech-to-text heard (raw) → what tidy-up wrote →
 * what was delivered (after saved spellings) → what the person says it
 * should have been ("wanted", when they typed it). Each pair is compared
 * word by word and only what changed is recorded, as an *observation*: a
 * plain label of what happened, never a verdict on whose fault it was. A
 * dropped phrase may be a correct false-start removal; a missing word may
 * have been the transcriber's or never said. The model decides the layer;
 * the app only refuses fixes the observations don't support.
 *
 * The algorithm is part of the shared contract (`pack/feedback/README.md`,
 * "Observations"), so the Android app computes the same observations from
 * the same texts. It must stay exactly as written there:
 *
 * - Words: maximal runs of letters and digits, joined by an inner apostrophe
 *   (`’` counts as `'`). Comparison ignores case (Unicode lower case, no
 *   locale) and punctuation; punctuation-only and case-only changes are not
 *   observations.
 * - Alignment: the longest common subsequence of the two word lists, walked
 *   from the start: equal words match; otherwise the side whose skip keeps
 *   the longer remaining common subsequence is skipped, the earlier text's
 *   word on a tie.
 * - A run of unmatched words between matches is one observation:
 *   `replaced` (words on both sides), `removed` (earlier side only) or
 *   `added` (later side only). `before` and `after` are the original text
 *   from the first word of the run to the last, punctuation inside kept.
 *
 * Pure and bounded: texts are capped, and a pair too long to align is
 * reported as `truncated`, never as a guess.
 */
import { sameForm } from '../profile/ops';

export const OBSERVATION_STAGES = ['tidy-up', 'spelling', 'tidy-up-or-spelling', 'wanted'] as const;
export type ObservationStage = (typeof OBSERVATION_STAGES)[number];

export const OBSERVATION_CHANGES = ['replaced', 'removed', 'added'] as const;
export type ObservationChange = (typeof OBSERVATION_CHANGES)[number];

/**
 * For a change between delivered and wanted: where the delivered words came
 * from. `speech-to-text`: unchanged since raw. `tidy-up`: tidy-up wrote them
 * (or, for words only the wanted text has, tidy-up removed them). `spelling`:
 * a saved spelling wrote them. `ambiguous`: mixed, or the alignment can't
 * tell (a word only the wanted text has and nobody removed).
 */
export const OBSERVATION_ORIGINS = ['speech-to-text', 'tidy-up', 'spelling', 'ambiguous'] as const;
export type ObservationOrigin = (typeof OBSERVATION_ORIGINS)[number];

export type Observation = {
	stage: ObservationStage;
	change: ObservationChange;
	before: string;
	after: string;
	/** stage `spelling`: the applied spelling that explains it (`s1`…), else null. */
	spelling?: string | null;
	/** stage `wanted`: where the delivered words came from. */
	origin?: ObservationOrigin;
};

export type StageTexts = {
	raw: string;
	/** Tidy-up's output before spellings; null: no tidy-up ran. undefined: not known (older dictation). */
	tidy: string | null | undefined;
	delivered: string;
	/** What the person says it should have been; null or empty: not given. */
	wanted?: string | null;
	/** The spellings that changed this dictation, with the refs the request uses. */
	spellings?: readonly { ref: string; heard: string; written: string }[];
};

export type Observations = {
	observations: Observation[];
	/** A pair was too long to align, or a stage had more changes than are kept. */
	truncated: boolean;
};

/** Each stage keeps at most this many observations. */
export const MAX_OBSERVATIONS_PER_STAGE = 20;
/** Before and after are cut to this many characters (an ellipsis marks a cut). */
export const MAX_OBSERVATION_CHARS = 120;
/** Words per text; above this a pair is not aligned. */
export const MAX_WORDS = 1500;

type Token = { key: string; start: number; end: number };

const WORD = /[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu;

/** The words of a text, with their positions. */
export function words(text: string): Token[] {
	const out: Token[] = [];
	for (const m of text.matchAll(WORD)) {
		const start = m.index ?? 0;
		out.push({ key: m[0].replace(/’/g, "'").toLowerCase(), start, end: start + m[0].length });
	}
	return out;
}

type Pair = { a: number; b: number };
type Alignment = { matches: Pair[]; aMatch: Int32Array; bMatch: Int32Array };

/** The contract's alignment (see the header). Null when the pair is too long. */
export function align(a: readonly Token[], b: readonly Token[]): Alignment | null {
	const n = a.length;
	const m = b.length;
	if (n > MAX_WORDS || m > MAX_WORDS) return null;
	// L[i][j]: the longest common subsequence of a[i..] and b[j..].
	const w = m + 1;
	const L = new Uint16Array((n + 1) * w);
	for (let i = n - 1; i >= 0; i--)
		for (let j = m - 1; j >= 0; j--)
			L[i * w + j] =
				a[i]!.key === b[j]!.key
					? L[(i + 1) * w + j + 1]! + 1
					: Math.max(L[(i + 1) * w + j]!, L[i * w + j + 1]!);
	const aMatch = new Int32Array(n).fill(-1);
	const bMatch = new Int32Array(m).fill(-1);
	const matches: Pair[] = [];
	let i = 0;
	let j = 0;
	while (i < n && j < m) {
		if (a[i]!.key === b[j]!.key) {
			matches.push({ a: i, b: j });
			aMatch[i] = j;
			bMatch[j] = i;
			i++;
			j++;
		} else if (L[(i + 1) * w + j]! >= L[i * w + j + 1]!) i++;
		else j++;
	}
	return { matches, aMatch, bMatch };
}

type Hunk = { a0: number; a1: number; b0: number; b1: number };

/** Runs of unmatched words: [a0, a1) in the earlier text, [b0, b1) in the later. */
function hunks(al: Alignment, n: number, m: number): Hunk[] {
	const out: Hunk[] = [];
	let pa = 0;
	let pb = 0;
	for (const { a, b } of [...al.matches, { a: n, b: m }]) {
		if (a > pa || b > pb) out.push({ a0: pa, a1: a, b0: pb, b1: b });
		pa = a + 1;
		pb = b + 1;
	}
	return out;
}

function clip(text: string): string {
	const t = text.replace(/\s+/g, ' ').trim();
	return t.length > MAX_OBSERVATION_CHARS ? `${t.slice(0, MAX_OBSERVATION_CHARS - 1)}…` : t;
}

function span(text: string, tokens: readonly Token[], from: number, to: number): string {
	if (to <= from) return '';
	return clip(text.slice(tokens[from]!.start, tokens[to - 1]!.end));
}

function changeOf(h: Hunk): ObservationChange {
	if (h.a1 > h.a0 && h.b1 > h.b0) return 'replaced';
	return h.a1 > h.a0 ? 'removed' : 'added';
}

type Compared = {
	al: Alignment;
	hunks: Hunk[];
	a: Token[];
	b: Token[];
};

function compare(aText: string, bText: string): Compared | null {
	const a = words(aText);
	const b = words(bText);
	const al = align(a, b);
	if (!al) return null;
	return { al, hunks: hunks(al, a.length, b.length), a, b };
}

/** Where each delivered word came from. */
type Source = 'raw' | 'tidy' | 'spelling' | 'unknown';

/**
 * The observations for one dictation. Stages that aren't known are left out
 * (a dictation from before tidy-up outputs were kept compares raw with
 * delivered as `tidy-up-or-spelling`).
 */
export function observe(texts: StageTexts): Observations {
	const out: Observation[] = [];
	let truncated = false;
	const push = (stage: ObservationStage, list: Observation[]) => {
		if (list.length > MAX_OBSERVATIONS_PER_STAGE) truncated = true;
		out.push(...list.slice(0, MAX_OBSERVATIONS_PER_STAGE));
	};
	const raw = texts.raw ?? '';
	const delivered = texts.delivered ?? '';
	const spellings = texts.spellings ?? [];

	// Provenance of the pre-spelling text's words: raw or tidy-up.
	let pre: string | null;
	let preSource: Source[] = [];
	/** Words tidy-up removed or replaced (their keys), for words only the wanted text has. */
	const tidyDropped = new Set<string>();
	if (texts.tidy === null) {
		pre = raw;
		preSource = words(raw).map(() => 'raw');
	} else if (typeof texts.tidy === 'string') {
		pre = texts.tidy;
		const c = compare(raw, pre);
		if (!c) {
			truncated = true;
			preSource = words(pre).map(() => 'unknown');
		} else {
			preSource = c.b.map((_, j) => (c.al.bMatch[j]! >= 0 ? 'raw' : 'tidy'));
			const list: Observation[] = [];
			for (const h of c.hunks) {
				for (let i = h.a0; i < h.a1; i++) tidyDropped.add(c.a[i]!.key);
				list.push({
					stage: 'tidy-up',
					change: changeOf(h),
					before: span(raw, c.a, h.a0, h.a1),
					after: span(pre, c.b, h.b0, h.b1),
				});
			}
			push('tidy-up', list);
		}
	} else {
		pre = null;
	}

	// Pre-spelling → delivered: the spellings (or, unknown pre, both stages).
	let deliveredSource: Source[];
	const base = pre ?? raw;
	const d = compare(base, delivered);
	if (!d) {
		truncated = true;
		deliveredSource = words(delivered).map(() => 'unknown');
	} else {
		const baseSource: Source[] = pre === null ? d.a.map(() => 'unknown') : preSource;
		deliveredSource = d.b.map((_, j) => {
			const i = d.al.bMatch[j]!;
			if (i < 0) return pre === null ? 'unknown' : 'spelling';
			return pre === null ? 'raw' : (baseSource[i] ?? 'unknown');
		});
		const list: Observation[] = [];
		for (const h of d.hunks) {
			const before = span(base, d.a, h.a0, h.a1);
			const after = span(delivered, d.b, h.b0, h.b1);
			if (pre === null) {
				list.push({ stage: 'tidy-up-or-spelling', change: changeOf(h), before, after });
				continue;
			}
			const hit = spellings.find((s) => sameForm(s.heard, before) && s.written.trim() === after);
			if (!hit) for (let j = h.b0; j < h.b1; j++) deliveredSource[j] = 'unknown';
			list.push({ stage: 'spelling', change: changeOf(h), before, after, spelling: hit?.ref ?? null });
		}
		push(pre === null ? 'tidy-up-or-spelling' : 'spelling', list);
	}

	// Delivered → wanted, each change labelled by where the delivered words came from.
	const wanted = texts.wanted?.trim() ? texts.wanted : null;
	if (wanted !== null) {
		const w = compare(delivered, wanted);
		if (!w) truncated = true;
		else {
			const list: Observation[] = [];
			for (const h of w.hunks) {
				let origin: ObservationOrigin;
				if (h.a1 > h.a0) {
					const sources = new Set<Source>();
					for (let i = h.a0; i < h.a1; i++) sources.add(deliveredSource[i] ?? 'unknown');
					const only = sources.size === 1 ? [...sources][0] : null;
					origin =
						only === 'raw'
							? 'speech-to-text'
							: only === 'tidy'
								? 'tidy-up'
								: only === 'spelling'
									? 'spelling'
									: 'ambiguous';
				} else {
					// Words only the wanted text has: tidy-up's if it removed all of them.
					const added = w.b.slice(h.b0, h.b1).map((t) => t.key);
					origin = added.length > 0 && added.every((k) => tidyDropped.has(k)) ? 'tidy-up' : 'ambiguous';
				}
				list.push({
					stage: 'wanted',
					change: changeOf(h),
					before: span(delivered, w.a, h.a0, h.a1),
					after: span(wanted, w.b, h.b0, h.b1),
					origin,
				});
			}
			push('wanted', list);
		}
	}
	return { observations: out, truncated };
}

/** Whether a form occurs in a text as whole words (case and spacing ignored). */
export function hasWords(text: string | null | undefined, form: string): boolean {
	if (!text) return false;
	const f = words(form).map((t) => t.key);
	if (f.length === 0) return false;
	const t = words(text).map((x) => x.key);
	outer: for (let i = 0; i + f.length <= t.length; i++) {
		for (let k = 0; k < f.length; k++) if (t[i + k] !== f[k]) continue outer;
		return true;
	}
	return false;
}
