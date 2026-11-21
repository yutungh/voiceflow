/**
 * VoiceFlow feedback v2 (phase 1): the smallest word fix that would work.
 *
 * When speech-to-text got a word wrong, the fixes go from smallest to
 * largest:
 *
 * 1. a spelling: "always write Zebbo as Zibbo" (exact, the safest);
 * 2. a heard-as form on a listed word: tells the tidy-up (and the
 *    transcriber's hint) that "Zebbo" is how Zibbo comes out;
 * 3. adding the word to the lists: it joins the tidy-up's list, so the
 *    tidy-up can fix it from context;
 * 4. raising its priority: it joins the 20 words the transcriber itself is
 *    given for this app. The 20 stays 20, so another word drops out, and the
 *    card says which.
 *
 * Steps 2–4 write a `term` edit. The merge keeps one entry per written word,
 * the highest layer's, so an edit for a listed word is the whole entry (its
 * other heard forms, note, kind, scope and priority copied) and replaces the
 * listed one until it is undone.
 *
 * Pure: the caller passes the merged snapshot.
 */
import { buildPrompt } from '../profile/prompt';
import type { ProfileSnapshot, ProfileTerm } from '../profile/types';

export const WORD_STEPS = ['heard_as', 'add_word', 'raise_priority'] as const;
export type WordStep = (typeof WORD_STEPS)[number];

/** Priority a new word gets (the lists' default). */
export const DEFAULT_PRIORITY = 50;
export const MAX_PRIORITY = 100;

const EVERY_APP = '*';
const norm = (s: string) => s.trim().replace(/\s+/g, ' ').toLocaleLowerCase();

type Lists = Pick<ProfileSnapshot, 'terms' | 'defaults'>;

/** The listed word with this spelling (case and spacing ignored), or null. */
export function findWord(snapshot: Lists, text: string): ProfileTerm | null {
	const key = norm(text);
	return snapshot.terms.find((t) => norm(t.text) === key) ?? null;
}

/** The word applies in this app. */
export function coversApp(term: Pick<ProfileTerm, 'apps'>, appId: string | null): boolean {
	return term.apps.includes(EVERY_APP) || (!!appId && term.apps.includes(appId));
}

export type Place = {
	/** In the tidy-up's list for this app. */
	tidy: boolean;
	/** Among the words the transcriber is given for this app (20). */
	transcriber: boolean;
};

/** Where a word stands in this app's two lists. */
export function placeOf(snapshot: Lists, appId: string | null, text: string): Place {
	const built = buildPrompt(snapshot, appId);
	const key = norm(text);
	return {
		tidy: built.chosen.some((t) => norm(t.text) === key),
		transcriber: built.sent.some((w) => norm(w) === key),
	};
}

/** The lists with this word in place of any listed entry for the same spelling. */
export function withWord(snapshot: Lists, term: ProfileTerm): Lists {
	const key = norm(term.text);
	return { ...snapshot, terms: [...snapshot.terms.filter((t) => norm(t.text) !== key), term] };
}

export type Effect = {
	before: Place;
	after: Place;
	/** Words the transcriber stops being given in this app (the 20 stays 20). */
	dropsOut: string[];
};

/** What writing this word would do to this app's lists. */
export function effectOf(snapshot: Lists, appId: string | null, term: ProfileTerm): Effect {
	const before = buildPrompt(snapshot, appId);
	const next = withWord(snapshot, term);
	const after = buildPrompt(next, appId);
	const key = norm(term.text);
	const kept = new Set(after.sent.map(norm));
	return {
		before: placeOf(snapshot, appId, term.text),
		after: {
			tidy: after.chosen.some((t) => norm(t.text) === key),
			transcriber: after.sent.some((w) => norm(w) === key),
		},
		dropsOut: before.sent.filter((w) => norm(w) !== key && !kept.has(norm(w))),
	};
}

/**
 * The smallest priority that puts the word among the transcriber's words for
 * this app, or null when even the highest doesn't (or it already is there).
 */
export function raisedPriority(
	snapshot: Lists,
	appId: string | null,
	term: ProfileTerm,
): { priority: number; effect: Effect } | null {
	if (placeOf(snapshot, appId, term.text).transcriber) return null;
	const at = (p: number) => effectOf(snapshot, appId, { ...term, priority: p });
	if (!at(MAX_PRIORITY).after.transcriber) return null;
	// Higher priority is never further back, so the smallest that works is found by halving.
	let lo = Math.max(0, term.priority) + 1;
	let hi = MAX_PRIORITY;
	if (lo > hi) return null;
	while (lo < hi) {
		const mid = Math.floor((lo + hi) / 2);
		if (at(mid).after.transcriber) hi = mid;
		else lo = mid + 1;
	}
	return { priority: lo, effect: at(lo) };
}

export type LadderInput = {
	/** The word as it should be written. */
	word: string;
	/** Forms speech-to-text wrote for it in this dictation. */
	heard: readonly string[];
	/** The app's id (spelling scope convention), or null when unknown. */
	appId: string | null;
	/** For a new word: this app only, or every app. */
	scope: 'app' | 'everywhere';
};

export type LadderStep =
	| {
			ok: true;
			step: WordStep;
			/** The entry to write. */
			term: ProfileTerm;
			/** The listed entry it replaces (null: a new word). */
			replaces: ProfileTerm | null;
			/** Heard forms this adds. */
			added: string[];
			priority: { from: number; to: number } | null;
			/** The word starts applying in this app too (a listed word for other apps). */
			widened: boolean;
			effect: Effect;
	  }
	| { ok: false; message: string };

function draft(base: ProfileTerm | null, text: string): ProfileTerm {
	return base
		? { ...base, heardAs: [...base.heardAs], apps: [...base.apps] }
		: { id: 'candidate', text, heardAs: [], priority: DEFAULT_PRIORITY, apps: [EVERY_APP], layer: 'edit' };
}

const quote = (s: string) => `“${s}”`;

/**
 * The fix at a step of the ladder, or the next step up that still does
 * something when the asked one would change nothing:
 *
 * - `add_word` for a word already listed becomes `heard_as` (new heard
 *   forms) or `raise_priority`;
 * - `heard_as` with no new form becomes `raise_priority`;
 * - `heard_as` or `raise_priority` for a word not listed becomes `add_word`.
 *
 * A word listed only for other apps also starts applying in this one.
 */
export function ladderStep(snapshot: Lists, asked: WordStep, input: LadderInput): LadderStep {
	const listed = findWord(snapshot, input.word);
	const appId = input.appId;
	const newForms = (term: ProfileTerm | null) =>
		input.heard.filter(
			(h) => norm(h) !== norm(input.word) && !(term?.heardAs ?? []).some((g) => norm(g) === norm(h)),
		);

	let step: WordStep = asked;
	if (!listed) step = 'add_word';
	else if (step === 'add_word') step = newForms(listed).length > 0 ? 'heard_as' : 'raise_priority';
	else if (step === 'heard_as' && newForms(listed).length === 0) step = 'raise_priority';

	const term = draft(listed, input.word.trim().replace(/\s+/g, ' '));
	let widened = false;
	if (listed && appId && !coversApp(listed, appId)) {
		term.apps = [...term.apps, appId];
		widened = true;
	}
	if (!listed && input.scope === 'app') {
		if (!appId) return { ok: false, message: 'This dictation’s app isn’t known, so the word can only be added for every app.' };
		term.apps = [appId];
	}
	const added = step === 'raise_priority' ? [] : newForms(listed);
	term.heardAs = [...term.heardAs, ...added];

	if (step === 'raise_priority') {
		if (!listed) return { ok: false, message: `${quote(input.word)} isn’t in your words yet.` };
		if (widened) {
			// Using it in this app may be enough on its own.
			const asIs = effectOf(snapshot, appId, term);
			if (asIs.after.transcriber)
				return { ok: true, step, term, replaces: listed, added, priority: null, widened, effect: asIs };
		}
		const raised = raisedPriority(snapshot, appId, term);
		if (!raised) {
			const place = placeOf(snapshot, appId, term.text);
			return {
				ok: false,
				message: place.transcriber && coversApp(listed, appId)
					? `${quote(listed.text)} is already one of the words the transcriber is given here, so a spelling is the fix left.`
					: `${quote(listed.text)} can’t be raised far enough to reach the transcriber here.`,
			};
		}
		term.priority = raised.priority;
		return {
			ok: true,
			step,
			term,
			replaces: listed,
			added,
			priority: { from: listed.priority, to: raised.priority },
			widened,
			effect: raised.effect,
		};
	}
	return {
		ok: true,
		step,
		term,
		replaces: listed,
		added,
		priority: null,
		widened,
		effect: effectOf(snapshot, appId, term),
	};
}
