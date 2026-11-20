/**
 * VoiceFlow: the dictation hotkeys are handled in Rust (`src-tauri/src/hotkey.rs`).
 *
 * A press starts or stops the Rust recorder and captures the app in front
 * without this webview, so it works even while Windows or WebView2 has put
 * the webview to sleep (a minimized or hidden window). Rust then sends an
 * event (`vf-native-hotkey`) with a sequence number; this side acknowledges
 * it when it arrives and again once handled (`hotkey_ack`), handles each
 * number exactly once and in order, and runs the rest of the dictation
 * (privacy decision, transcription, tidy-up, delivery). Until the second
 * acknowledgement Rust keeps the event, so a page that reloads asks again.
 *
 * This file is the pure part (config and the event pump), so it is tested
 * without Tauri; `native-hotkeys.svelte.ts` wires it to the app.
 */
import type { DictationPurpose } from '$lib/bar/contract';
import type { TargetSnapshot } from '$lib/privacy/policy';
import type { DeviceAcquisitionOutcome } from '$lib/services/types';

/** The actions Rust handles, by command id (`$lib/commands`). */
export const NATIVE_ACTIONS = {
	toggleManualRecording: 'toggle',
	startManualRecording: 'start',
	stopManualRecording: 'stop',
	cancelManualRecording: 'cancel',
	pushToTalk: 'pushToTalk',
} as const;

export type NativeCommandId = keyof typeof NATIVE_ACTIONS;
export type NativeAction = (typeof NATIVE_ACTIONS)[NativeCommandId];

export function nativeActionOf(commandId: string): NativeAction | null {
	return Object.hasOwn(NATIVE_ACTIONS, commandId)
		? NATIVE_ACTIONS[commandId as NativeCommandId]
		: null;
}

export type NativeSource = 'hotkey' | 'app';

type EventBase = { seq: number; atMs: number; source: NativeSource };

export type NativeRecording = {
	sampleRate: number;
	channels: number;
	durationSeconds: number;
	filePath?: string | null;
};

export type NativeEvent = EventBase &
	(
		| {
				kind: 'started';
				recordingId: string;
				target: TargetSnapshot;
				device: DeviceAcquisitionOutcome;
				ptt: boolean;
				purpose: DictationPurpose | null;
		  }
		| {
				kind: 'stopped';
				recordingId: string;
				recording: NativeRecording;
				/** 0.9.11: the app in front at Stop (identity only). */
				target?: TargetSnapshot | null;
				purpose: DictationPurpose | null;
		  }
		| { kind: 'cancelled'; recordingId: string; discarded: boolean }
		| { kind: 'refused'; target: TargetSnapshot; purpose: DictationPurpose | null }
		| {
				kind: 'failed';
				step: 'start' | 'stop' | 'cancel';
				message: string;
				recordingId: string | null;
		  }
		| { kind: 'key'; action: NativeAction; state: 'Pressed' | 'Released' }
	);

// ---------------------------------------------------------------------------
// Config for Rust
// ---------------------------------------------------------------------------

export type NativeConfig = {
	bindings: { action: NativeAction; accelerator: string }[];
	/** null: the recording method is not the Rust recorder (keys are forwarded). */
	recorder: {
		deviceId: string | null;
		outputFolder: string;
		sampleRate: number | null;
	} | null;
	/** Built-in privacy list: lower-case exe names and bundle ids. */
	blockedApps: string[];
	/** Voice activated recording is on: keys go to the page, which stops it first. */
	forward: boolean;
};

/** "CommandOrControl" in older settings, as the JS registration did. */
export function platformAccelerator(accelerator: string, isMac: boolean): string {
	return accelerator.replace('CommandOrControl', isMac ? 'Command' : 'Control');
}

export function buildNativeConfig({
	accelerators,
	method,
	deviceId,
	outputFolder,
	sampleRate,
	blockedApps,
	isMac,
	forward = false,
}: {
	/** The global shortcut per command id (null: none). */
	accelerators: Partial<Record<NativeCommandId, string | null | undefined>>;
	method: string;
	deviceId: string | null;
	outputFolder: string;
	sampleRate: string | null;
	blockedApps: readonly string[];
	isMac: boolean;
	forward?: boolean;
}): NativeConfig {
	const bindings: NativeConfig['bindings'] = [];
	for (const [commandId, action] of Object.entries(NATIVE_ACTIONS)) {
		const accelerator = accelerators[commandId as NativeCommandId];
		if (accelerator) bindings.push({ action, accelerator: platformAccelerator(accelerator, isMac) });
	}
	const rate = sampleRate ? Number.parseInt(sampleRate, 10) : Number.NaN;
	return {
		bindings,
		recorder:
			method === 'cpal'
				? { deviceId, outputFolder, sampleRate: Number.isFinite(rate) ? rate : null }
				: null,
		blockedApps: [...new Set(blockedApps.map((a) => a.toLowerCase()))],
		forward,
	};
}

// ---------------------------------------------------------------------------
// The event pump: each event once, in order
// ---------------------------------------------------------------------------

export type EventContext = {
	/** Events received after this one and not handled yet. */
	later: readonly NativeEvent[];
};

export function createEventPump({
	ack,
	handle,
	pending,
	onError = (error) => console.error('[hotkey] handling an event failed', error),
}: {
	/**
	 * Tells Rust the event arrived (`done` false: stops the re-sends) and
	 * that it was handled (`done` true: Rust forgets it).
	 */
	ack: (seq: number, done: boolean) => unknown;
	handle: (event: NativeEvent, context: EventContext) => Promise<void>;
	/** Rust's list of unacknowledged events (a start, a wake). */
	pending: () => Promise<NativeEvent[]>;
	onError?: (error: unknown) => void;
}) {
	const SEEN_LIMIT = 1024;
	const seen = new Set<number>();
	const queue: NativeEvent[] = [];
	let draining = false;
	let handled = 0;

	function remember(seq: number) {
		seen.add(seq);
		if (seen.size > SEEN_LIMIT) {
			const oldest = seen.values().next().value;
			if (oldest !== undefined) seen.delete(oldest);
		}
	}

	async function drain() {
		if (draining) return;
		draining = true;
		try {
			while (queue.length > 0) {
				const event = queue.shift() as NativeEvent;
				try {
					await handle(event, { later: [...queue] });
				} catch (error) {
					onError(error);
				}
				handled += 1;
				ack(event.seq, true);
			}
		} finally {
			draining = false;
		}
	}

	function receive(event: NativeEvent): boolean {
		// Acknowledge every copy that is not handled yet, so a lost
		// acknowledgement is made good; a handled one gets its final one again.
		ack(event.seq, false);
		if (seen.has(event.seq)) return false;
		remember(event.seq);
		const at = queue.findIndex((queued) => queued.seq > event.seq);
		if (at === -1) queue.push(event);
		else queue.splice(at, 0, event);
		// After the current task, so events that arrive together are queued
		// together (a stop that is already here is visible to its start).
		queueMicrotask(() => void drain());
		return true;
	}

	return {
		receive,
		/** Pulls anything Rust still holds (call at start and when the page wakes). */
		async sync() {
			const events = await pending();
			for (const event of [...events].sort((a, b) => a.seq - b.seq)) receive(event);
		},
		/** Resolves when everything received so far is handled (tests). */
		async idle() {
			while (draining || queue.length > 0) await new Promise((r) => setTimeout(r, 0));
		},
		get handledCount() {
			return handled;
		},
	};
}

/** True when the recording ends in an event that is already queued. */
export function endsLater(recordingId: string, later: readonly NativeEvent[]): boolean {
	return later.some(
		(e) =>
			(e.kind === 'stopped' || e.kind === 'cancelled') && e.recordingId === recordingId,
	);
}
