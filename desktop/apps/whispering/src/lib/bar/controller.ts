/**
 * VoiceFlow A2: the recording bar's side of the dictation engine. Runs in
 * the main window only (the single engine). The engine calls the hooks below
 * at its existing points (start, refuse, stop, transcribe, tidy-up, deliver,
 * fail, cancel); this module keeps the state machine, sends the bar
 * its view, and turns the bar's clicks into engine actions.
 *
 * The bar shows and hides itself once it has drawn the view (`/bar`), so it
 * appears at the right size; this module only tells Rust, when a dictation
 * starts, where its bar goes (0.9.12: by "Show on" and "Position").
 */
import { settings } from '$lib/stores/settings.svelte';
import { services } from '$lib/services';
import type { TargetSnapshot } from '$lib/privacy/policy';
import { appIdOf } from '$lib/privacy/history';
import {
	BAR_READY_EVENT,
	BAR_WINDOW,
	DICTATION_COMMAND_EVENT,
	DICTATION_STATE_EVENT,
	type BarCommand,
	type BarEdge,
	type BarErrorKind,
	type BarStateMessage,
	type BarStyleOption,
	type DictationPurpose,
	type FeedbackPayload,
	type FeedbackSubject,
} from './contract';
import type { ProfileSnapshot } from '$lib/profile/types';
import { DEFAULT_EXPRESSION, presetKindOf } from './expression';
import { edgeOf } from './geometry';
import { createBarMachine, republishDelay, snippetOf, type FrozenOverrides } from './machine';
import {
	appRuleFor,
	barStyleChoice,
	profilePinnedIds,
	targetAppIds,
	withAppExpression,
} from './styles';
import { localAppRules } from '$lib/profile/op-store';
import { feedbackHotkeyAction } from './feedback-flow';
import {
	USAGE_DAYS,
	preferredStyleIds,
	styleUsage,
	type UsageEvent,
} from './chips';
import { barPinsOf, inPageOrder } from '$lib/vf/style-pins';

const isDesktop = () =>
	typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

export type BarHandlers = {
	stop: (recordingId: string) => Promise<unknown> | unknown;
	cancel: (recordingId: string) => Promise<unknown> | unknown;
	retry: (recordingId: string, kind: BarErrorKind | null) => Promise<unknown> | unknown;
	pasteRaw: (recordingId: string) => Promise<unknown> | unknown;
	feedback: (recordingId: string) => Promise<unknown> | unknown;
	/** 0.9.11: the bar's close on a problem notice. */
	dismiss?: (recordingId: string, kind: BarErrorKind | null) => Promise<unknown> | unknown;
	/** 0.9.13: Use cleaned, after the language guard kept the user's own words. */
	useCleaned?: (recordingId: string) => Promise<unknown> | unknown;
};

const machine = createBarMachine();
let handlers: BarHandlers | null = null;
let styleCache: BarStyleOption[] = [];
/** 0.9.21: true once this PC's styles were read (until then an app's built-in keeps its id). */
let stylesLoaded = false;
/** This PC's styles marked "Show on the bar" (their own `showOnBar`). */
let devicePins = new Set<string>();
/** Recent dictations' styles by name, one per recording (History, then each delivery). */
const usage = new Map<string, UsageEvent>();
let usageLoaded = false;
let hideTimer: ReturnType<typeof setTimeout> | undefined;
let wasListening = false;
const listeningListeners = new Set<(listening: boolean) => void>();
const iconCache = new Map<string, string | null>();
let started = false;
/**
 * 0.9.12: the edge the bar's place was chosen with (sent to the page with
 * every view), and the place being chosen: views wait for it, so the page
 * never shows the bar before Rust knows where this dictation's bar goes.
 */
let placedEdge: BarEdge = 'bottom';
let placing: Promise<unknown> = Promise.resolve();

async function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T | null> {
	if (!isDesktop()) return null;
	try {
		const { invoke } = await import('@tauri-apps/api/core');
		return await invoke<T>(command, args);
	} catch (error) {
		console.warn(`[bar] ${command} failed`, error);
		return null;
	}
}

/** Sends the bar its view (or null to hide it). */
function publish() {
	const view = settings.value['bar.show'] ? machine.view() : null;
	if (isDesktop()) {
		const message: BarStateMessage = { state: view, edge: placedEdge };
		void placing
			.then(() => import('@tauri-apps/api/event'))
			.then(({ emitTo }) => emitTo(BAR_WINDOW, DICTATION_STATE_EVENT, message))
			.catch((error) => console.warn('[bar] could not send the state', error));
	}
	clearTimeout(hideTimer);
	// From the view just sent: if it shows a notice, it is sent again (as
	// hidden) when the notice's time is up.
	const delay = republishDelay(view, Date.now());
	if (delay !== null) hideTimer = setTimeout(publish, delay);
	const listening = machine.listening() !== null;
	if (listening !== wasListening) {
		wasListening = listening;
		for (const listener of listeningListeners) listener(listening);
	}
}

/**
 * 0.9.12: tells Rust where a new dictation's bar goes (its monitor, by
 * "Show on", and its edge and side) and keeps the edge for the page.
 * Views sent after this wait until Rust has it.
 */
function placeBar(target: { windowId?: string | null; pid?: number | null; external: boolean }) {
	const position = settings.value['bar.position'];
	placedEdge = edgeOf(position);
	placing = invoke('bar_target', {
		windowId: target.external ? (target.windowId ?? null) : null,
		pid: target.external ? (target.pid ?? null) : null,
		position,
		monitor: settings.value['bar.monitor'],
	});
}

async function loadStyles(): Promise<BarStyleOption[]> {
	const { data } = await services.db.transformations.getAll();
	if (data) {
		const ordered = inPageOrder(data);
		styleCache = ordered.map((s) => ({
			id: s.id,
			name: s.title || 'Untitled style',
			kind: presetKindOf(s.title),
		}));
		devicePins = new Set(ordered.filter((s) => s.showOnBar === true).map((s) => s.id));
		stylesLoaded = true;
	}
	return styleCache;
}

/** 0.9.9: History's style use, for the default chips (loaded once, then kept up). */
async function loadUsage() {
	const { data } = await services.db.recordings.getAll();
	if (!data) return;
	const since = Date.now() - (USAGE_DAYS + 1) * 24 * 60 * 60 * 1000;
	for (const r of data) {
		// A delivery counted while History was loading is newer: it stays.
		if (!r.styleName || r.deletedAt || usage.has(r.id)) continue;
		const at = Date.parse(r.createdAt);
		if (Number.isFinite(at) && at >= since) usage.set(r.id, { styleName: r.styleName, at });
	}
	usageLoaded = true;
}

/**
 * 0.9.9: the chips' favourites for a dictation (pins, else the most used).
 * 0.9.13: the word lists' pins (`defaults.pinnedStyles`) come first, then
 * this PC's own.
 */
function preferredFor(styles: BarStyleOption[], snapshot: ProfileSnapshot | null): string[] | null {
	const shared = profilePinnedIds(snapshot);
	const pinned = new Set([...devicePins, ...barPinsOf(settings.value['bar.pinnedStyles'])]);
	if (shared.length === 0 && pinned.size === 0 && !usageLoaded) return null;
	return preferredStyleIds(styles, pinned, styleUsage([...usage.values()], Date.now()), undefined, shared);
}


function appIcon(target: TargetSnapshot, recordingId: string) {
	const key = (target.exePath || target.bundleId || '').toLowerCase();
	if (!key) return null;
	if (iconCache.has(key)) return iconCache.get(key) ?? null;
	const looksLikeBundle =
		!!target.bundleId && target.bundleId.includes('.') && !target.bundleId.endsWith('.exe');
	void invoke<string | null>('app_icon', {
		exePath: target.exePath,
		bundleId: looksLikeBundle ? target.bundleId : null,
	}).then((url) => {
		iconCache.set(key, url ?? null);
		if (url && machine.setAppIcon(recordingId, url)) publish();
	});
	return null;
}

function keysLabel(accelerator: string | null | undefined): string | null {
	return accelerator ? accelerator : null;
}

export const bar = {
	/** Starts listening for the bar's clicks. Main window only, once. */
	async init(next: BarHandlers) {
		handlers = next;
		if (started || !isDesktop()) return;
		started = true;
		const { listen } = await import('@tauri-apps/api/event');
		await listen<BarCommand>(DICTATION_COMMAND_EVENT, (event) => {
			void bar.handle(event.payload);
		});
		await listen(BAR_READY_EVENT, () => publish());
		void loadStyles();
		void loadUsage();
		const { data: latest } = await services.db.recordings.getLatest();
		if (latest) {
			machine.setFallbackPrevious({
				recordingId: latest.id,
				appName: latest.appName ?? null,
				at: latest.createdAt,
				snippet: snippetOf(latest.finalText || latest.transcribedText || ''),
			});
			// A recording that started before History answered can now offer Feedback.
			publish();
		}
	},

	/** Runs a command from the bar (or a hotkey that maps to one). */
	async handle(command: BarCommand) {
		// From the record, not the view: a notice the pointer kept is past its time.
		const kind = machine.errorKind(command.recordingId);
		const result = machine.command(command);
		if (!result.accepted) {
			console.info(`[bar] ignored ${command.type} for ${command.recordingId}: ${result.reason}`);
			return;
		}
		publish();
		if (!handlers) return;
		const id = command.recordingId;
		switch (command.type) {
			case 'stop':
				return handlers.stop(id);
			case 'cancel':
				return handlers.cancel(id);
			case 'retry':
				return handlers.retry(id, kind);
			case 'paste-raw':
				return handlers.pasteRaw(id);
			case 'feedback':
				machine.hide(id);
				publish();
				return handlers.feedback(id);
			case 'dismiss':
				return handlers.dismiss?.(id, kind);
			case 'use-cleaned':
				return handlers.useCleaned?.(id);
			default:
				return;
		}
	},

	/**
	 * Listening started (the microphone is on). Synchronous, so a Stop that
	 * follows at once always finds this recording.
	 */
	begin({
		recordingId,
		purpose,
		target,
		snapshot,
	}: {
		recordingId: string;
		/** `feedback` when the feedback hotkey started this recording. */
		purpose: DictationPurpose;
		target: TargetSnapshot;
		/** The word lists pinned for this dictation (null: off). */
		snapshot: ProfileSnapshot | null;
	}) {
		// The word lists' styles and app rule, from the pinned snapshot.
		// VoiceFlow phase F: the app rule's expression (an approved feedback fix).
		// 0.9.21: the app rule in force (Settings > Tidy-up > Style for each
		// app over the word lists'; with word lists off, this PC's own) gives
		// both the style, a built-in or this PC's own included, and its level.
		const rule = appRuleFor(snapshot, targetAppIds(target), localAppRules());
		const { style, styles } = withAppExpression(
			barStyleChoice({
				target,
				snapshot,
				deviceStyles: styleCache,
				deviceDefaultId: settings.value['transformations.selectedTransformationId'],
				appRule: rule,
				deviceStylesLoaded: stylesLoaded,
			}),
			rule,
			snapshot,
			stylesLoaded ? styleCache : null,
		);
		machine.begin({
			recordingId,
			purpose,
			app: { name: target.isSelf ? 'VoiceLnq' : target.appName, icon: appIcon(target, recordingId) },
			style,
			styles,
			preferred: preferredFor(styles, snapshot),
			defaultExpression: DEFAULT_EXPRESSION,
			keys: {
				stop: keysLabel(settings.value['shortcuts.global.toggleManualRecording']),
				cancel: keysLabel(settings.value['shortcuts.global.cancelManualRecording']),
			},
		});
		// Before the view: Rust places the bar by "Show on" (the target's
		// monitor is one choice) and the page learns its edge.
		placeBar({
			windowId: target.windowId,
			pid: target.pid,
			external: target.resolved && !target.isSelf,
		});
		publish();
		void loadStyles();
	},

	/** A start the privacy rules refused (nothing recorded). */
	refused(target: TargetSnapshot, notice: { title: string; description: string } | null) {
		if (!notice) return;
		// 0.9.12: a refusal is a new dictation for the bar's place too.
		placeBar({
			windowId: target.windowId,
			pid: target.pid,
			external: target.resolved && !target.isSelf,
		});
		machine.refused(
			`refused-${Date.now()}`,
			{ name: target.appName, icon: null },
			notice.title,
			notice.description,
		);
		publish();
	},

	/** The microphone could not start (nothing to show; the notice says why). */
	startFailed() {},

	/** Stop: freezes this dictation's style and expression for tidy-up. */
	stop(recordingId: string): FrozenOverrides | null {
		const frozen = machine.stop(recordingId);
		publish();
		return frozen;
	},

	/** The overrides frozen at Stop, for Retry and Send anyway. */
	frozen(recordingId: string): FrozenOverrides | null {
		return machine.frozen(recordingId);
	},

	cancelled(recordingId: string | null) {
		if (!recordingId) return;
		machine.setPhase(recordingId, 'cancelled');
		publish();
	},

	transcribing(recordingId: string) {
		machine.setPhase(recordingId, 'transcribing');
		publish();
	},

	tidying(recordingId: string) {
		machine.setPhase(recordingId, 'tidying');
		publish();
	},

	delivered(
		recordingId: string,
		outcome: 'pasted' | 'copied',
		details: {
			app: string | null;
			text: string;
			styleName?: string | null;
			/** 0.9.11: for `copied`, the paste keys and why it was not pasted. */
			copied?: { keys: string; why: string | null };
			/** 0.9.13: the language guard kept the user's own words. */
			keptWords?: { reason: string; styleName: string } | null;
		},
	) {
		machine.delivered(recordingId, outcome, details);
		// One count per dictation, whatever its retries; Raw has no name and no count.
		if (details.styleName) usage.set(recordingId, { styleName: details.styleName, at: Date.now() });
		else if (details.styleName === null) usage.delete(recordingId);
		publish();
	},

	failed(
		recordingId: string,
		kind: BarErrorKind,
		title: string,
		detail: string | null = null,
		options: { retry?: boolean } = {},
	) {
		machine.failed(recordingId, kind, title, detail, options);
		publish();
	},

	/** Hides the bar if it shows this recording. */
	hide(recordingId: string) {
		machine.hide(recordingId);
		publish();
	},

	/** The recording the bar shows, while it listens. */
	listening() {
		return machine.listening();
	},

	/** Style hotkey while listening. False when not listening. */
	cycleStyle(): boolean {
		const result = machine.cycleStyle();
		if (result?.accepted) publish();
		return result !== null;
	},

	/** Expression hotkeys while listening. */
	nudgeExpression(delta: number): boolean {
		const result = machine.nudgeExpression(delta);
		if (result?.accepted) publish();
		return result !== null;
	},

	/**
	 * The feedback hotkey: turns the dictation being recorded into feedback,
	 * or says to start a feedback recording (the start itself carries the
	 * purpose, so nothing here can leak into another start). Returns what
	 * to do.
	 */
	feedbackHotkey(): 'converted' | 'stop' | 'start' {
		const live = machine.listening();
		const action = feedbackHotkeyAction(live);
		if (action === 'convert' && live) {
			void bar.handle({ type: 'use-as-feedback', recordingId: live.recordingId });
			return 'converted';
		}
		return action === 'stop' ? 'stop' : 'start';
	},

	/** Called with true when listening starts and false when it ends. */
	onListeningChange(listener: (listening: boolean) => void): () => void {
		listeningListeners.add(listener);
		return () => listeningListeners.delete(listener);
	},

	/** 0.9.9: the styles or their pins changed (Styles page, built-in styles added). */
	async reloadStyles() {
		await loadStyles();
	},

	/** Re-sends the view (a setting changed). */
	refresh() {
		publish();
	},

	/** For the feedback window: the dictation's texts, from History. */
	async feedbackPayload(
		subject: FeedbackSubject | null,
		transcript: { text: string; problem: string | null; recordingId: string | null },
	): Promise<FeedbackPayload> {
		const payload: FeedbackPayload = {
			feedbackRecordingId: transcript.recordingId,
			transcript: transcript.text,
			transcriptProblem: transcript.problem,
			subject,
			subjectRawText: '',
			subjectFinalText: '',
			subjectStyleName: null,
			subjectAppId: null,
		};
		if (!subject) return payload;
		const { data: recording } = await services.db.recordings.getById(subject.recordingId);
		if (!recording) return payload;
		return {
			...payload,
			subject: {
				...subject,
				appName: subject.appName ?? recording.appName ?? null,
				snippet: subject.snippet || snippetOf(recording.finalText || recording.transcribedText || ''),
			},
			subjectRawText: recording.transcribedText ?? '',
			subjectFinalText: recording.finalText ?? '',
			subjectStyleName: recording.styleName ?? null,
			subjectAppId: appIdOf(recording),
		};
	},

	/** Opens the feedback window (it takes focus: the person asked for it). */
	async openFeedbackWindow(payload: FeedbackPayload) {
		await invoke('feedback_window_open', { payload });
	},

	/** The subject for a "Feedback" click on a delivery notice. */
	subjectFor(recordingId: string): FeedbackSubject | null {
		return machine.subject(recordingId);
	},
};

