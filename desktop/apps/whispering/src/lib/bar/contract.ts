/**
 * VoiceFlow A2: the contract between the dictation engine (main window) and
 * the floating recording bar (`/bar`).
 *
 * - The engine sends one event, `dictation://state`, with the bar's whole
 *   view (`null` hides it). Each message has a `seq`; the bar ignores an
 *   older one that arrives late.
 * - Rust sends `audio://level` (0..1, about 25 times a second) while the
 *   microphone records.
 * - The bar sends `dictation://command`. Every command names the recording
 *   it was clicked for; the engine ignores a command for any recording other
 *   than the one the bar shows, so a late click never touches another
 *   dictation.
 *
 * Phase B moves the engine to Rust; this payload stays the same.
 */
import type { PresetKind } from './expression';

export const DICTATION_STATE_EVENT = 'dictation://state';
export const DICTATION_COMMAND_EVENT = 'dictation://command';
export const BAR_READY_EVENT = 'dictation://bar-ready';
export const AUDIO_LEVEL_EVENT = 'audio://level';

export const MAIN_WINDOW = 'main';
export const BAR_WINDOW = 'bar';
export const FEEDBACK_WINDOW = 'feedback';

/** The id the bar uses for "no tidy-up". */
export const RAW_STYLE_ID = 'raw';

export const BAR_PHASES = [
	'listening',
	'transcribing',
	'tidying',
	'pasted',
	'copied',
	'failed',
	'cancelled',
] as const;
export type BarPhase = (typeof BAR_PHASES)[number];

/** What went wrong, for `failed` (and why a start was refused). */
export type BarErrorKind =
	| 'transcription'
	| 'tidy-up'
	| 'paste'
	| 'refused'
	| 'start'
	/** 0.9.11: no speech in the recording; nothing to deliver. */
	| 'empty';

/** A feedback recording is transcribed only, never tidied or delivered. */
export type DictationPurpose = 'dictation' | 'feedback';

export type BarStyleOption = {
	/** A style (transformation) id, or `raw`. */
	id: string;
	name: string;
	/** The Android preset it corresponds to (expression guidance). */
	kind: PresetKind;
	/** The style's own expression (0–4), when its word list sets one. */
	expression?: number;
	/**
	 * VoiceFlow phase F: the app rule's expression for this style (an
	 * approved feedback fix or the word lists' rule). Unlike the style's own
	 * level it is sent to tidy-up when nothing is chosen on the bar.
	 */
	appExpression?: number;
};

/** The dictation a feedback recording is about, pinned when it starts. */
export type FeedbackSubject = {
	recordingId: string;
	appName: string | null;
	/** When that dictation started (ISO). */
	at: string;
	/** The start of its text, when known. */
	snippet: string;
};

export const BAR_ACTIONS = [
	'stop',
	'cancel',
	'set-style',
	'set-expression',
	'use-as-feedback',
	/** 0.9.8: undoes `use-as-feedback` while it still records. */
	'use-as-dictation',
	'retry',
	'paste-raw',
	'feedback',
	'dismiss',
	/** 0.9.13: the tidy-up the language guard held back, to the clipboard. */
	'use-cleaned',
] as const;
export type BarAction = (typeof BAR_ACTIONS)[number];

export type DictationState = {
	recordingId: string;
	/** Increases with every message; older ones are ignored. */
	seq: number;
	phase: BarPhase;
	purpose: DictationPurpose;
	/** Epoch ms when listening started (for the timer). */
	startedAt: number;
	/** Epoch ms when the bar hides by itself, if it does. */
	hidesAt: number | null;
	app: { name: string | null; icon: string | null };
	/** The style this dictation uses (the app's default unless changed). */
	style: { id: string; name: string; kind: PresetKind };
	/** 0–4 when chosen on the bar for this dictation; null: the style's own. */
	expression: number | null;
	/** What the control shows while `expression` is null. */
	defaultExpression: number;
	/**
	 * 0.9.8: the style chips, at most five, the style in use always among
	 * them (`chips.ts`); the style hotkey cycles them.
	 */
	favourites: BarStyleOption[];
	/** Every style, in the Styles page's order; those not in the chips are under More. */
	styles: BarStyleOption[];
	/** True after Stop: style and expression can no longer change. */
	frozen: boolean;
	/** "Pasted into <app>", from the paste-time authorization. */
	pastedInto: string | null;
	/**
	 * 0.9.11: for `copied`, the paste keys ("Ctrl+V") and why the text was
	 * copied instead of pasted (null: pasting is off, or nothing to say).
	 */
	copied?: { keys: string; why: string | null } | null;
	/**
	 * 0.9.13: for `pasted` or `copied`, the language guard kept his own words
	 * (tidy-up changed the language of part of what he said) and holds the
	 * tidy-up back for Use cleaned: why, and the style.
	 */
	keptWords?: { reason: string; styleName: string } | null;
	error: { kind: BarErrorKind; title: string; detail: string | null } | null;
	feedbackFor: FeedbackSubject | null;
	/** The buttons that work in this state. */
	actions: BarAction[];
	/** Shortcut labels for the buttons that have one. */
	keys: { stop: string | null; cancel: string | null };
};

/**
 * 0.9.12: which edge of the screen the bar is on, so the page lays itself
 * out the right way up (top: the fixed row on top, the card growing down).
 */
export type BarEdge = 'bottom' | 'top';

export type BarStateMessage = {
	state: DictationState | null;
	/** Chosen with the dictation's place; absent: bottom. */
	edge?: BarEdge;
};

export type BarCommand =
	| { type: 'stop'; recordingId: string }
	| { type: 'cancel'; recordingId: string }
	| { type: 'set-style'; recordingId: string; styleId: string }
	| { type: 'set-expression'; recordingId: string; expression: number }
	| { type: 'use-as-feedback'; recordingId: string }
	| { type: 'use-as-dictation'; recordingId: string }
	| { type: 'retry'; recordingId: string }
	| { type: 'paste-raw'; recordingId: string }
	| { type: 'feedback'; recordingId: string }
	| { type: 'dismiss'; recordingId: string }
	| { type: 'use-cleaned'; recordingId: string };

/** Commands after which the bar gives the front back to the target app. */
export function restoresFocus(command: BarCommand['type']): boolean {
	// "feedback" opens the feedback window, which is meant to take focus.
	return command !== 'feedback';
}

/** The feedback window's input (sent through Rust, taken once). */
export type FeedbackPayload = {
	/** The feedback recording (its audio is not kept). */
	feedbackRecordingId: string | null;
	/** What the person said, transcribed; editable before Submit. */
	transcript: string;
	/** Why there is no transcript, when there isn't one. */
	transcriptProblem: string | null;
	subject: FeedbackSubject | null;
	/** The dictation's texts, read from History when the window opened. */
	subjectRawText: string;
	subjectFinalText: string;
	subjectStyleName: string | null;
	subjectAppId: string | null;
};
