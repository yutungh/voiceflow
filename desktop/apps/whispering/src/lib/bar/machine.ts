/**
 * VoiceFlow A2: the recording bar's state machine. Pure (no Tauri, no
 * Svelte), so it is unit tested; `controller.ts` wires it to the engine.
 *
 * Rules (plan v2, A2 and the 2026-11-17 addendum):
 * - Each recording has its own record, keyed by its id. The bar shows one
 *   recording at a time; a new recording supersedes whatever was shown, and
 *   later updates for the old one are kept but not shown.
 * - The style and expression are pinned when recording starts (the app's
 *   default) and can change on the bar only while listening. Stop freezes
 *   them; tidy-up gets the frozen values.
 * - A command for a recording other than the one shown, or not allowed in
 *   its current phase, is refused.
 * - "Use this recording as feedback" (or the feedback hotkey) pins the
 *   previous dictation at that moment. A feedback recording is transcribed
 *   only: Stop returns `purpose: 'feedback'` and the engine never tidies or
 *   delivers it.
 * - 0.9.8: it is offered only when there is a previous dictation to give
 *   feedback on, and "Back to dictation" (`use-as-dictation`) undoes it
 *   while the recording still listens, keeping the style and expression
 *   chosen before. A recording started with the feedback hotkey stays
 *   feedback (the native recorder knows it as feedback).
 * - 0.9.9: every notice hides by itself (`NOTICE_TIMES`): a success after
 *   a few seconds, a problem after eight. Hiding only hides: every action
 *   a notice offers is also on Home ("Needs you") and in History for that
 *   dictation. The pointer on the notice keeps it (`presence.ts`).
 * - 0.9.12: nothing is held any more (since 0.9.11), so the bar has no
 *   held state. "Nothing heard" offers Retry when the recording was long
 *   enough to have been misheard (`failed(..., { retry: true })`).
 */
import {
	RAW_STYLE_ID,
	type BarAction,
	type BarCommand,
	type BarErrorKind,
	type BarPhase,
	type BarStyleOption,
	type DictationPurpose,
	type DictationState,
	type FeedbackSubject,
} from './contract';
import { chipRow, nextKept } from './chips';
import { DEFAULT_EXPRESSION, sanitizeExpression } from './expression';

/** How long "Pasted" stays (longer while the pointer is on it; see `/bar`). */
export const NOTICE_MS = 3000;
/** How long "Copied" stays: the words wait on the clipboard and in History. */
export const COPIED_MS = 4000;
/** How long "Cancelled" stays. */
export const CANCELLED_MS = 1500;
/**
 * 0.9.9: how long a problem stays (Not pasted, Couldn't transcribe, a
 * refusal...). Its actions stay on Home and in History after it hides.
 */
export const PROBLEM_MS = 8000;

/** Every notice's time on screen, by phase (0.9.9: none stays for good). */
export const NOTICE_TIMES = {
	pasted: NOTICE_MS,
	copied: COPIED_MS,
	cancelled: CANCELLED_MS,
	failed: PROBLEM_MS,
} as const satisfies Partial<Record<BarPhase, number>>;
/** Records kept for "previous dictation" and late updates. */
const KEEP_RECORDS = 30;
/**
 * Overrides kept for Retry and Send anyway after the record is pruned: as
 * long as the word-list snapshot pinned for the dictation is kept
 * (`profile/pins.ts`), so a kept style always resolves against its lists.
 */
const KEEP_FROZEN = 100;

export type BeginInput = {
	recordingId: string;
	purpose: DictationPurpose;
	app: { name: string | null; icon: string | null };
	/** The style chosen for this app (the default). */
	style: BarStyleOption;
	/** Every style, in the Styles page's order (the chips come from it). */
	styles: BarStyleOption[];
	/**
	 * 0.9.9: the favourites' ids ("Show on the bar", else the most used),
	 * from `preferredStyleIds`; null or absent: the first five.
	 */
	preferred?: string[] | null;
	defaultExpression?: number;
	keys?: DictationState['keys'];
};

/** What Stop hands to tidy-up. */
export type FrozenOverrides = {
	recordingId: string;
	purpose: DictationPurpose;
	/** A style id, or `raw`. */
	styleId: string;
	styleName: string;
	/** Chosen on the bar; null: the style's own (nothing added to its prompt). */
	expression: number | null;
	feedbackFor: FeedbackSubject | null;
};

export type CommandResult =
	| { accepted: true; command: BarCommand }
	| { accepted: false; reason: 'stale' | 'not-allowed' | 'unknown-style' };

type Record_ = {
	id: string;
	purpose: DictationPurpose;
	/** What it was when it started (the native recorder knows only this). */
	startedAs: DictationPurpose;
	startedAt: number;
	app: { name: string | null; icon: string | null };
	style: BarStyleOption;
	expression: number | null;
	defaultExpression: number;
	styles: BarStyleOption[];
	/** A style picked from More: it keeps its chip for this recording (`chips.ts`). */
	kept: string | null;
	/** 0.9.9: the favourites' ids (pinned or most used); null: the first five. */
	preferred: string[] | null;
	frozen: boolean;
	/** Stop was clicked; the recorder hasn't stopped yet. Nothing changes now. */
	stopping: boolean;
	phase: BarPhase;
	error: DictationState['error'];
	/** 0.9.12: "Nothing heard" offers Retry (a recording long enough to retry). */
	retryEmpty: boolean;
	pastedInto: string | null;
	/** 0.9.11: for `copied`, the paste keys and why it was not pasted. */
	copied: { keys: string; why: string | null } | null;
	/** 0.9.13: the user's own words were kept; the tidy-up is held back. */
	keptWords: { reason: string; styleName: string } | null;
	feedbackFor: FeedbackSubject | null;
	snippet: string;
	keys: DictationState['keys'];
	/** When the shown notice hides by itself. */
	hidesAt: number | null;
};

export type BarMachine = ReturnType<typeof createBarMachine>;

export function createBarMachine({
	now = () => Date.now(),
	noticeMs = NOTICE_MS,
	copiedMs = COPIED_MS,
	cancelledMs = CANCELLED_MS,
	problemMs = PROBLEM_MS,
}: {
	now?: () => number;
	noticeMs?: number;
	copiedMs?: number;
	cancelledMs?: number;
	problemMs?: number;
} = {}) {
	const records = new Map<string, Record_>();
	/** What Stop froze, kept longer than the records (Retry from Home later). */
	const frozenById = new Map<string, FrozenOverrides>();
	/** Dictations (not feedback) in the order they started. */
	const dictationOrder: string[] = [];
	let shownId: string | null = null;
	let dismissed = false;
	let seq = 0;
	/** "Previous dictation" when none was made since the app started. */
	let fallbackPrevious: FeedbackSubject | null = null;

	function subjectOf(record: Record_): FeedbackSubject {
		return {
			recordingId: record.id,
			appName: record.app.name,
			at: new Date(record.startedAt).toISOString(),
			snippet: record.snippet,
		};
	}

	function previousDictation(excludingId: string | null): FeedbackSubject | null {
		for (let i = dictationOrder.length - 1; i >= 0; i--) {
			const id = dictationOrder[i];
			if (id === excludingId) continue;
			const record = id ? records.get(id) : undefined;
			if (record && record.purpose === 'dictation') return subjectOf(record);
		}
		return fallbackPrevious;
	}

	function prune() {
		if (records.size <= KEEP_RECORDS) return;
		for (const id of records.keys()) {
			if (records.size <= KEEP_RECORDS) break;
			if (id === shownId) continue;
			records.delete(id);
			const at = dictationOrder.indexOf(id);
			if (at >= 0) dictationOrder.splice(at, 1);
		}
	}

	function actionsFor(record: Record_): BarAction[] {
		switch (record.phase) {
			case 'listening': {
				if (record.stopping) return ['stop', 'cancel'];
				if (record.purpose === 'feedback') {
					return record.startedAs === 'dictation'
						? ['stop', 'cancel', 'use-as-dictation']
						: ['stop', 'cancel'];
				}
				const actions: BarAction[] =
					record.style.id === RAW_STYLE_ID
						? ['stop', 'cancel', 'set-style']
						: ['stop', 'cancel', 'set-style', 'set-expression'];
				if (previousDictation(record.id)) actions.push('use-as-feedback');
				return actions;
			}
			case 'transcribing':
			case 'tidying':
				return [];
			case 'pasted':
			case 'copied':
				return record.keptWords ? ['use-cleaned', 'feedback', 'dismiss'] : ['feedback', 'dismiss'];
			case 'failed':
				switch (record.error?.kind) {
					case 'tidy-up':
						return ['retry', 'paste-raw', 'feedback', 'dismiss'];
					case 'transcription':
						return ['retry', 'dismiss'];
					case 'paste':
						return ['feedback', 'dismiss'];
					case 'empty':
						return record.retryEmpty ? ['retry', 'dismiss'] : ['dismiss'];
					default:
						return ['dismiss'];
				}
			case 'cancelled':
				return ['dismiss'];
		}
	}

	function hideTimeFor(phase: BarPhase, emptyRecording = false): number | null {
		// 0.9.11: "Nothing heard" is quiet and short.
		if (phase === 'failed' && emptyRecording) return now() + copiedMs;
		switch (phase) {
			case 'pasted':
				return now() + noticeMs;
			case 'copied':
				return now() + copiedMs;
			case 'cancelled':
				return now() + cancelledMs;
			case 'failed':
				return now() + problemMs;
			default:
				return null;
		}
	}

	/** What the expression control shows before one is chosen: the style's own. */
	function defaultExpressionOf(record: Record_): number {
		if (record.style.appExpression !== undefined)
			return sanitizeExpression(record.style.appExpression);
		return record.style.expression === undefined
			? record.defaultExpression
			: sanitizeExpression(record.style.expression);
	}

	function changed(id: string) {
		if (id === shownId) seq += 1;
	}

	function view(): DictationState | null {
		if (!shownId || dismissed) return null;
		const record = records.get(shownId);
		if (!record) return null;
		if (record.hidesAt !== null && now() >= record.hidesAt) return null;
		return {
			recordingId: record.id,
			seq,
			phase: record.phase,
			purpose: record.purpose,
			startedAt: record.startedAt,
			hidesAt: record.hidesAt,
			app: record.app,
			style: { id: record.style.id, name: record.style.name, kind: record.style.kind },
			expression: record.expression,
			defaultExpression: defaultExpressionOf(record),
			favourites: chipRow(record.styles, record.style.id, record.kept, undefined, record.preferred).chips,
			styles: record.styles,
			frozen: record.frozen,
			pastedInto: record.pastedInto,
			copied: record.copied,
			keptWords: record.keptWords,
			error: record.error,
			feedbackFor: record.feedbackFor,
			actions: actionsFor(record),
			keys: record.keys,
		};
	}

	return {
		view,

		/** The recording the bar shows, if any. */
		get shownId() {
			return shownId;
		},

		/** Sets "previous dictation" for before the first one this session. */
		setFallbackPrevious(subject: FeedbackSubject | null) {
			fallbackPrevious = subject;
		},

		previousDictation,

		/** One recording as a feedback subject, if it is still kept. */
		subject(recordingId: string): FeedbackSubject | null {
			const record = records.get(recordingId);
			return record ? subjectOf(record) : null;
		},

		/** The target app's icon arrived. True when the shown view changed. */
		setAppIcon(recordingId: string, icon: string): boolean {
			const record = records.get(recordingId);
			if (!record) return false;
			record.app = { ...record.app, icon };
			changed(recordingId);
			return recordingId === shownId;
		},

		/** A recording started listening: it supersedes the shown one. */
		begin(input: BeginInput) {
			const feedbackFor =
				input.purpose === 'feedback' ? previousDictation(input.recordingId) : null;
			const record: Record_ = {
				id: input.recordingId,
				purpose: input.purpose,
				startedAs: input.purpose,
				startedAt: now(),
				app: input.app,
				style: input.style,
				expression: null,
				defaultExpression: sanitizeExpression(
					input.defaultExpression ?? DEFAULT_EXPRESSION,
				),
				styles: input.styles,
				kept: nextKept(input.styles, input.style.id, null, undefined, input.preferred ?? null),
				preferred: input.preferred ?? null,
				frozen: false,
				stopping: false,
				phase: 'listening',
				error: null,
				retryEmpty: false,
				pastedInto: null,
				copied: null,
				keptWords: null,
				feedbackFor,
				snippet: '',
				keys: input.keys ?? { stop: null, cancel: null },
				hidesAt: null,
			};
			records.set(record.id, record);
			if (record.purpose === 'dictation') dictationOrder.push(record.id);
			shownId = record.id;
			dismissed = false;
			seq += 1;
			prune();
		},

		/**
		 * A refusal at the hotkey (blocked app, password field): nothing was
		 * recorded, so it gets its own id and shows as a failure.
		 */
		refused(id: string, app: { name: string | null; icon: string | null }, title: string, detail: string | null) {
			records.set(id, {
				id,
				purpose: 'dictation',
				startedAs: 'dictation',
				startedAt: now(),
				app,
				style: { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' },
				expression: null,
				defaultExpression: DEFAULT_EXPRESSION,
				styles: [],
				kept: null,
				preferred: null,
				frozen: true,
				stopping: false,
				phase: 'failed',
				error: { kind: 'refused', title, detail },
				retryEmpty: false,
				pastedInto: null,
				copied: null,
				keptWords: null,
				feedbackFor: null,
				snippet: '',
				keys: { stop: null, cancel: null },
				hidesAt: hideTimeFor('failed'),
			});
			shownId = id;
			dismissed = false;
			seq += 1;
			prune();
		},

		/** Validates and applies a command from the bar or a hotkey. */
		command(command: BarCommand): CommandResult {
			const record = records.get(command.recordingId);
			if (!record || command.recordingId !== shownId || dismissed) {
				return { accepted: false, reason: 'stale' };
			}
			if (!actionsFor(record).includes(command.type)) {
				return { accepted: false, reason: 'not-allowed' };
			}
			switch (command.type) {
				case 'set-style': {
					const style = record.styles.find((s) => s.id === command.styleId);
					if (!style) return { accepted: false, reason: 'unknown-style' };
					record.style = style;
					record.kept = nextKept(record.styles, style.id, record.kept, undefined, record.preferred);
					break;
				}
				case 'set-expression':
					record.expression = sanitizeExpression(command.expression);
					break;
				case 'use-as-feedback':
					record.purpose = 'feedback';
					record.feedbackFor = previousDictation(record.id);
					{
						const at = dictationOrder.indexOf(record.id);
						if (at >= 0) dictationOrder.splice(at, 1);
					}
					break;
				case 'use-as-dictation': {
					record.purpose = 'dictation';
					record.feedbackFor = null;
					// Back in its place among the dictations, by when it started.
					if (!dictationOrder.includes(record.id)) {
						const after = dictationOrder.findIndex(
							(id) => (records.get(id)?.startedAt ?? 0) > record.startedAt,
						);
						if (after < 0) dictationOrder.push(record.id);
						else dictationOrder.splice(after, 0, record.id);
					}
					break;
				}
				case 'stop':
					// Until the recorder reports it stopped, nothing else may change.
					record.stopping = true;
					break;
				case 'dismiss':
					dismissed = true;
					break;
				default:
					break;
			}
			seq += 1;
			return { accepted: true, command };
		},

		/** Cycles the shown recording's style through the chips shown (hotkey). */
		cycleStyle(): CommandResult | null {
			const record = shownId ? records.get(shownId) : undefined;
			if (!record || dismissed || record.phase !== 'listening') return null;
			// A feedback recording has no style, but the key is still taken.
			if (record.purpose !== 'dictation' || record.stopping) return { accepted: false, reason: 'not-allowed' };
			const row = chipRow(record.styles, record.style.id, record.kept, undefined, record.preferred).chips;
			if (row.length === 0) return null;
			const at = row.findIndex((s) => s.id === record.style.id);
			const next = row[(at + 1) % row.length];
			if (!next) return null;
			return this.command({ type: 'set-style', recordingId: record.id, styleId: next.id });
		},

		/** Moves the shown recording's expression one detent (hotkey). */
		nudgeExpression(delta: number): CommandResult | null {
			const record = shownId ? records.get(shownId) : undefined;
			if (!record || dismissed || record.phase !== 'listening') return null;
			const current = record.expression ?? defaultExpressionOf(record);
			return this.command({
				type: 'set-expression',
				recordingId: record.id,
				expression: sanitizeExpression(current + delta),
			});
		},

		/** The shown recording, while it is listening. */
		listening(): { recordingId: string; purpose: DictationPurpose } | null {
			const record = shownId ? records.get(shownId) : undefined;
			if (!record || record.phase !== 'listening') return null;
			return { recordingId: record.id, purpose: record.purpose };
		},

		/** Stop: freezes the overrides. Null when it isn't listening. */
		stop(recordingId: string): FrozenOverrides | null {
			const record = records.get(recordingId);
			if (!record || record.phase !== 'listening') return null;
			record.frozen = true;
			record.phase = 'transcribing';
			changed(recordingId);
			const frozen = this.frozen(recordingId);
			if (frozen) {
				frozenById.delete(recordingId);
				frozenById.set(recordingId, frozen);
				while (frozenById.size > KEEP_FROZEN) {
					const oldest = frozenById.keys().next().value;
					if (oldest === undefined) break;
					frozenById.delete(oldest);
				}
			}
			return frozen;
		},

		/** The overrides frozen at Stop (for Retry and Send anyway). */
		frozen(recordingId: string): FrozenOverrides | null {
			const record = records.get(recordingId);
			if (!record || !record.frozen) return frozenById.get(recordingId) ?? null;
			return {
				recordingId,
				purpose: record.purpose,
				styleId: record.style.id,
				styleName: record.style.name,
				expression:
					record.style.id === RAW_STYLE_ID
						? null
						: (record.expression ??
							(record.style.appExpression === undefined
								? null
								: sanitizeExpression(record.style.appExpression))),
				feedbackFor: record.feedbackFor,
			};
		},

		/** The engine moved a recording on (transcribing, tidying...). */
		setPhase(recordingId: string, phase: BarPhase): void {
			const record = records.get(recordingId);
			if (!record) return;
			record.phase = phase;
			record.hidesAt = hideTimeFor(phase, record.error?.kind === 'empty');
			if (phase !== 'failed') record.error = null;
			if (phase === 'transcribing' || phase === 'tidying') record.frozen = true;
			if (recordingId === shownId && phase !== 'cancelled') dismissed = false;
			changed(recordingId);
		},

		/** Delivered: pasted into an app, or copied. */
		delivered(
			recordingId: string,
			outcome: 'pasted' | 'copied',
			{
				app,
				text,
				styleName,
				copied,
				keptWords,
			}: {
				app: string | null;
				text: string;
				styleName?: string | null;
				copied?: { keys: string; why: string | null };
				/** 0.9.13: the language guard kept the user's own words. */
				keptWords?: { reason: string; styleName: string } | null;
			},
		): void {
			const record = records.get(recordingId);
			if (!record) return;
			record.pastedInto = outcome === 'pasted' ? app : null;
			record.copied = outcome === 'copied' ? (copied ?? null) : null;
			record.keptWords = keptWords ?? null;
			record.snippet = snippetOf(text);
			if (styleName !== undefined) {
				record.style = styleName
					? { ...record.style, name: styleName }
					: { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };
			}
			this.setPhase(recordingId, outcome);
			// 0.9.13: "Kept your words" stays as long as a problem does, so
			// Use cleaned can be reached; History keeps it after that.
			if (record.keptWords) record.hidesAt = now() + problemMs;
		},

		/**
		 * Something went wrong; the notice hides by itself. `retry`: for
		 * "Nothing heard" (`empty`), offer Retry (0.9.12).
		 */
		failed(
			recordingId: string,
			kind: BarErrorKind,
			title: string,
			detail: string | null = null,
			{ retry = false }: { retry?: boolean } = {},
		): void {
			const record = records.get(recordingId);
			if (!record) return;
			record.error = { kind, title, detail };
			record.retryEmpty = kind === 'empty' && retry;
			this.setPhase(recordingId, 'failed');
		},

		/**
		 * What went wrong for a recording, read from its record (0.9.9: a notice
		 * kept on screen by the pointer is past its time, so the view is null).
		 */
		errorKind(recordingId: string): BarErrorKind | null {
			return records.get(recordingId)?.error?.kind ?? null;
		},

		/** Keeps the text's start for "previous dictation". */
		noteText(recordingId: string, text: string) {
			const record = records.get(recordingId);
			if (record) record.snippet = snippetOf(text);
		},

		/** Hides the shown recording (feedback window opened, setting off...). */
		hide(recordingId?: string) {
			if (recordingId && recordingId !== shownId) return;
			if (!dismissed) seq += 1;
			dismissed = true;
		},

		/** Epoch ms when the shown notice hides itself, if it does. */
		nextHideAt(): number | null {
			const record = shownId ? records.get(shownId) : undefined;
			if (!record || dismissed || record.hidesAt === null) return null;
			// Already hidden by time: nothing left to schedule.
			return now() >= record.hidesAt ? null : record.hidesAt;
		},
	};
}

/**
 * When to send the bar its view again: at the shown notice's own deadline
 * (taken from the view that was just sent, so a deadline passed while
 * sending still gets its final, hidden update). Null: nothing to schedule.
 */
export function republishDelay(view: Pick<DictationState, 'hidesAt'> | null, now: number): number | null {
	if (!view || view.hidesAt === null) return null;
	return Math.max(0, view.hidesAt - now) + 20;
}

/** The first words of a text, for the bar and the feedback window. */
export function snippetOf(text: string, max = 80): string {
	const flat = text.replace(/\s+/g, ' ').trim();
	return flat.length > max ? `${flat.slice(0, max - 1).trimEnd()}…` : flat;
}
