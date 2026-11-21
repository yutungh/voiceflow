/**
 * VoiceFlow C1: what Home and History show about dictations, computed from
 * local history only (the recordings on this PC). Pure functions.
 */
import type { Recording } from '$lib/services/isomorphic/db';

/** Typing speed the "Time saved" stat compares against. */
export const TYPING_WPM = 40;

const DAY_MS = 24 * 60 * 60 * 1000;
const DAY_LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const DAY_NAMES = [
	'Sunday',
	'Monday',
	'Tuesday',
	'Wednesday',
	'Thursday',
	'Friday',
	'Saturday',
];

/** Recently deleted dictations are kept this long, then removed. */
export const DELETED_KEEP_DAYS = 30;

export function isDeleted(r: Recording): boolean {
	return !!r.deletedAt;
}

/** The text a dictation ended up as: tidied, else as heard. */
export function finalTextOf(r: Recording): string {
	return (r.finalText ?? r.transcribedText ?? '').trim();
}

export function wordCount(text: string): number {
	const words = text.trim().match(/[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu);
	return words ? words.length : 0;
}

function startOfDay(date: Date): Date {
	return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

export function createdAt(r: Recording): Date {
	return new Date(r.createdAt || r.timestamp);
}

export type DayStat = {
	/** "M", "T", ... */
	letter: string;
	/** "Monday", ..., or "Today". */
	name: string;
	words: number;
	/** Words per minute that day, 0 without timed dictations. */
	wpm: number;
	/** Minutes saved against typing that day. */
	savedMin: number;
};

export type HomeStats = {
	days: DayStat[];
	wordsThisWeek: number;
	wordsLastWeek: number;
	/** Percent change, null when last week had no words. */
	changePct: number | null;
	/** Words per minute over the last 7 days, null without timed dictations. */
	wpm: number | null;
	/** Minutes saved over the last 7 days. */
	savedMin: number;
	hasData: boolean;
};

/** Dictations that count: not deleted, not held, with text. */
function counted(recordings: readonly Recording[]): Recording[] {
	return recordings.filter(
		(r) => !isDeleted(r) && r.privacy !== 'held' && finalTextOf(r).length > 0,
	);
}

/**
 * The three Home stats over the last 7 days (today and the 6 days before),
 * against the 7 days before that.
 */
export function homeStats(
	recordings: readonly Recording[],
	now: Date = new Date(),
): HomeStats {
	const today = startOfDay(now).getTime();
	const firstDay = today - 6 * DAY_MS;
	const days = Array.from({ length: 7 }, (_, i) => {
		const d = new Date(firstDay + i * DAY_MS);
		return {
			letter: DAY_LETTERS[d.getDay()] ?? '',
			name: i === 6 ? 'Today' : (DAY_NAMES[d.getDay()] ?? ''),
			words: 0,
			timedWords: 0,
			speakingMs: 0,
		};
	});
	let wordsLastWeek = 0;
	for (const r of counted(recordings)) {
		const t = startOfDay(createdAt(r)).getTime();
		const words = wordCount(finalTextOf(r));
		if (t >= firstDay && t <= today) {
			const day = days[Math.round((t - firstDay) / DAY_MS)];
			if (!day) continue;
			day.words += words;
			if (r.durationMs && r.durationMs > 0) {
				day.timedWords += words;
				day.speakingMs += r.durationMs;
			}
		} else if (t >= firstDay - 7 * DAY_MS && t < firstDay) {
			wordsLastWeek += words;
		}
	}
	const savedOf = (words: number, speakingMs: number) =>
		Math.max(0, words / TYPING_WPM - speakingMs / 60000);
	const out: DayStat[] = days.map((d) => ({
		letter: d.letter,
		name: d.name,
		words: d.words,
		wpm: d.speakingMs > 0 ? Math.round(d.timedWords / (d.speakingMs / 60000)) : 0,
		savedMin: d.speakingMs > 0 ? savedOf(d.timedWords, d.speakingMs) : 0,
	}));
	const wordsThisWeek = days.reduce((sum, d) => sum + d.words, 0);
	const timedWords = days.reduce((sum, d) => sum + d.timedWords, 0);
	const speakingMs = days.reduce((sum, d) => sum + d.speakingMs, 0);
	return {
		days: out,
		wordsThisWeek,
		wordsLastWeek,
		changePct:
			wordsLastWeek > 0
				? Math.round(((wordsThisWeek - wordsLastWeek) / wordsLastWeek) * 100)
				: null,
		wpm: speakingMs > 0 ? Math.round(timedWords / (speakingMs / 60000)) : null,
		savedMin: out.reduce((sum, d) => sum + d.savedMin, 0),
		hasData: counted(recordings).length > 0,
	};
}

/** "1 h 52 m", "14 m", "under a minute". */
export function formatMinutes(min: number): string {
	if (min < 1) return min > 0 ? 'under a minute' : '0 m';
	const h = Math.floor(min / 60);
	const m = Math.round(min % 60);
	return h > 0 ? `${h} h ${m} m` : `${m} m`;
}

/** "0:09", "1:24". */
export function formatLength(ms: number | undefined): string {
	if (!ms || ms <= 0) return '–';
	const s = Math.round(ms / 1000);
	return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** "10:42". */
export function formatTime(date: Date): string {
	return date.toLocaleTimeString(undefined, {
		hour: '2-digit',
		minute: '2-digit',
	});
}

/** "Today", "Yesterday", "Monday 10 Nov". */
export function dayLabel(date: Date, now: Date = new Date()): string {
	const diff = Math.round(
		(startOfDay(now).getTime() - startOfDay(date).getTime()) / DAY_MS,
	);
	if (diff === 0) return 'Today';
	if (diff === 1) return 'Yesterday';
	return date.toLocaleDateString(undefined, {
		weekday: 'long',
		day: 'numeric',
		month: 'short',
	});
}

/** "Today 10:42", "Yesterday 16:20". */
export function whenLabel(date: Date, now: Date = new Date()): string {
	return `${dayLabel(date, now)} ${formatTime(date)}`;
}

export type DictationResult = 'pasted' | 'copied' | 'not-pasted' | 'held' | 'no-text' | 'working' | 'saved';

/** The Result column: what happened to a dictation, in one word or two. */
export function resultOf(r: Recording): DictationResult {
	if (r.privacy === 'held') return 'held';
	if (r.result === 'no-text' || r.transcriptionStatus === 'FAILED') return 'no-text';
	if (r.result) return r.result;
	if (r.transcriptionStatus === 'TRANSCRIBING' || r.transcriptionStatus === 'UNPROCESSED')
		return 'working';
	return 'saved';
}

export type Problem = {
	recording: Recording;
	kind: 'tidy-up' | 'paste' | 'transcription' | 'held';
};

/** 0.9.12 (F4c): Home's "Needs you" leaves out problems older than this. */
export const HOME_PROBLEM_MS = DAY_MS;

/** 0.9.12 (F5): "Nothing heard" offers Retry from this length up. */
export const EMPTY_RETRY_MIN_MS = 3000;

/**
 * True when a recording with no speech is long enough to be worth Retry
 * (a misheard recording, not an accidental tap). Unknown length: offered.
 */
export function worthRetrying(durationMs: number | undefined | null): boolean {
	return typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs >= EMPTY_RETRY_MIN_MS;
}

/** True for a recording with no speech in it ("Nothing heard"): no text, no problem. */
export function isNothingHeard(r: Recording): boolean {
	return r.result === 'no-text' && !r.problemKind && r.transcriptionStatus !== 'FAILED';
}

/**
 * One dictation's problem, if it still has one (not fixed, not deleted)
 * and it can still be fixed (0.9.12, F6: a failed transcription or a held
 * dictation whose audio is gone cannot be).
 *
 * History (`includeDismissed`) also offers dismissed problems, and tidy-ups
 * that failed but whose words were copied (0.9.12, F4b), since the bar's
 * notice that offered their fixes hides by itself.
 */
export function problemOf(
	r: Recording,
	{ includeDismissed = false }: { includeDismissed?: boolean } = {},
): Problem | undefined {
	if (isDeleted(r) || (r.problemDismissed && !includeDismissed)) return undefined;
	if (r.privacy === 'held')
		return r.audioRemovedAt ? undefined : { recording: r, kind: 'held' };
	if (!r.problemKind) return undefined;
	if (r.problemKind === 'transcription' && r.audioRemovedAt) return undefined;
	const open = r.result === 'not-pasted' || r.result === 'no-text';
	const copiedTidyUp = includeDismissed && r.result === 'copied' && r.problemKind === 'tidy-up';
	return open || copiedTidyUp ? { recording: r, kind: r.problemKind } : undefined;
}

/**
 * Home's "Needs you": problems that still need the person (not fixed, not
 * dismissed, not deleted), from the last 24 hours (0.9.12, F4c), newest first.
 */
export function problemsOf(recordings: readonly Recording[], now: number = Date.now()): Problem[] {
	const out: Problem[] = [];
	for (const r of recordings) {
		const problem = problemOf(r);
		if (problem && now - createdAt(r).getTime() <= HOME_PROBLEM_MS) out.push(problem);
	}
	return out.sort((a, b) => createdAt(b.recording).getTime() - createdAt(a.recording).getTime());
}

/**
 * History's "Problems only": dictations with a problem the user can still act on
 * (0.9.12, F6). Not dismissed ones, not "Nothing heard", not failures whose
 * audio is gone, not copied ones; any age.
 */
export function hasProblem(r: Recording): boolean {
	return problemOf(r) !== undefined;
}

/**
 * 0.9.12 (F7): dictations 0.9.10 recorded as a failed tidy-up of an empty
 * transcript ("Empty input"): nothing was heard, so they become no-text with
 * no problem. Deleted ones are left alone.
 */
export function emptyInputLeftovers(recordings: readonly Recording[]): Recording[] {
	return recordings.filter(
		(r) =>
			!isDeleted(r) &&
			r.problemKind === 'tidy-up' &&
			(r.result === 'not-pasted' || r.result === 'copied') &&
			!(r.transcribedText ?? '').trim(),
	);
}

export type WordChange = { heard: string; written: string };

function norm(word: string) {
	return word.toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

/**
 * The words tidy-up and spellings changed: a word diff of what was heard
 * against the final text, ignoring case and punctuation. Each change pairs
 * the heard words with the words written in their place.
 */
export function wordChanges(heard: string, written: string): WordChange[] {
	const a = heard.split(/\s+/).filter(Boolean);
	const b = written.split(/\s+/).filter(Boolean);
	if (a.length === 0 || b.length === 0 || a.length * b.length > 250_000) return [];
	const na = a.map(norm);
	const nb = b.map(norm);
	const width = b.length + 1;
	const table = new Int32Array((a.length + 1) * width);
	const lcs = (i: number, j: number) => table[i * width + j] ?? 0;
	for (let i = a.length - 1; i >= 0; i--)
		for (let j = b.length - 1; j >= 0; j--)
			table[i * width + j] =
				na[i] === nb[j]
					? lcs(i + 1, j + 1) + 1
					: Math.max(lcs(i + 1, j), lcs(i, j + 1));
	const changes: WordChange[] = [];
	let i = 0;
	let j = 0;
	let gone: string[] = [];
	let added: string[] = [];
	const flush = () => {
		const h = gone.join(' ');
		const w = added.join(' ');
		if (norm(h) !== norm(w) && (norm(h) || norm(w)) && norm(w))
			changes.push({ heard: h, written: w });
		gone = [];
		added = [];
	};
	while (i < a.length || j < b.length) {
		if (i < a.length && j < b.length && na[i] === nb[j]) {
			flush();
			i++;
			j++;
		} else if (j < b.length && (i >= a.length || lcs(i, j + 1) >= lcs(i + 1, j))) {
			added.push(b[j++] ?? '');
		} else {
			gone.push(a[i++] ?? '');
		}
	}
	flush();
	return changes;
}

/** The final text split so changed words can be marked. */
export function markChanges(
	heard: string,
	written: string,
): { text: string; heard?: string }[] {
	const changes = wordChanges(heard, written);
	if (changes.length === 0) return [{ text: written }];
	const parts: { text: string; heard?: string }[] = [];
	let rest = written;
	for (const change of changes) {
		const at = rest.indexOf(change.written);
		if (at < 0) continue;
		if (at > 0) parts.push({ text: rest.slice(0, at) });
		parts.push({ text: change.written, heard: change.heard || undefined });
		rest = rest.slice(at + change.written.length);
	}
	if (rest) parts.push({ text: rest });
	return parts;
}
