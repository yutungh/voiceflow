/**
 * VoiceFlow: wires the native dictation hotkeys (`./native.ts`,
 * `src-tauri/src/hotkey.rs`) to the app. Main window only: it runs the
 * dictation engine.
 *
 * - Sends Rust the keys, the microphone settings and the built-in privacy
 *   list whenever they change (Rust registers the keys itself).
 * - Receives Rust's events, acknowledges each at once, and hands each to the
 *   dictation exactly once, in order (`createEventPump`).
 * - Pulls anything still waiting when the page starts or comes back.
 * - Holds a Web Lock for the page's lifetime: Chromium does not freeze a page
 *   that holds one (belt and braces with the WebView2 switches).
 */
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { PATHS } from '$lib/constants/paths';
import { IS_MACOS } from '$lib/constants/platform/is-macos';
import { BLOCKED_APPS } from '$lib/privacy/blocklist';
import { settings } from '$lib/stores/settings.svelte';
import { vadRecorder } from '$lib/stores/vad-recorder.svelte';
import {
	buildNativeConfig,
	createEventPump,
	type EventContext,
	NATIVE_ACTIONS,
	type NativeAction,
	type NativeCommandId,
	type NativeConfig,
	type NativeEvent,
	nativeActionOf,
} from './native';

const EVENT = 'vf-native-hotkey';

function isEngineWindow(): boolean {
	if (typeof window === 'undefined' || !window.__TAURI_INTERNALS__) return false;
	const internals = window.__TAURI_INTERNALS__ as {
		metadata?: { currentWebview?: { label?: string } };
	};
	return internals.metadata?.currentWebview?.label === 'main';
}

const engineWindow = isEngineWindow();

/** Keys a settings screen has just set, until the setting itself changes. */
const pendingKeys = new Map<NativeCommandId, string | null>();
let recorderIsNative = false;
let initialized = false;

function accelerators() {
	const out: Partial<Record<NativeCommandId, string | null>> = {};
	for (const commandId of Object.keys(NATIVE_ACTIONS) as NativeCommandId[]) {
		out[commandId] = pendingKeys.has(commandId)
			? (pendingKeys.get(commandId) ?? null)
			: (settings.value[`shortcuts.global.${commandId}`] as string | null);
	}
	return out;
}

async function currentConfig(): Promise<NativeConfig> {
	const method = settings.value['recording.method'];
	return buildNativeConfig({
		accelerators: accelerators(),
		method,
		deviceId: settings.value['recording.cpal.deviceId'] ?? null,
		outputFolder:
			method === 'cpal'
				? (settings.value['recording.cpal.outputFolder'] ?? (await PATHS.DB.RECORDINGS()))
				: '',
		sampleRate: settings.value['recording.cpal.sampleRate'] ?? null,
		blockedApps: BLOCKED_APPS.flatMap((app) => [...app.exe, ...app.bundleIds]),
		isMac: IS_MACOS,
		// Voice activated recording owns the microphone: the page's own path
		// stops it before a manual recording starts.
		forward: vadRecorder.state !== 'IDLE',
	});
}

let pushing: Promise<string[]> = Promise.resolve([]);

/** Sends the current config to Rust (serialized, so the last one wins). */
function push(): Promise<string[]> {
	pushing = pushing.then(
		async () => {
			const config = await currentConfig();
			recorderIsNative = config.recorder !== null && !config.forward;
			const problems = await invoke<string[]>('hotkey_configure', { config });
			for (const problem of problems) console.warn('[hotkey] not registered:', problem);
			return problems;
		},
		() => [],
	);
	return pushing.catch((error) => {
		console.error('[hotkey] configuring the native hotkeys failed', error);
		return [];
	});
}

export type NativeHandlers = {
	handle: (event: NativeEvent, context: EventContext) => Promise<void>;
};

export const nativeHotkeys = {
	/** True for the dictation commands in the main window (Rust registers them). */
	handles(commandId: string): boolean {
		return engineWindow && nativeActionOf(commandId) !== null;
	},

	/**
	 * True when a manual recording is run by Rust: the app's own start, stop
	 * and cancel go through `trigger` so every change is in one order.
	 */
	get recordsNatively(): boolean {
		return engineWindow && initialized && recorderIsNative;
	},

	/** A settings screen set (or cleared) one dictation key. */
	async bind(commandId: string, accelerator: string | null): Promise<string[]> {
		const id = commandId as NativeCommandId;
		if (!nativeActionOf(id)) return [];
		pendingKeys.set(id, accelerator);
		return push();
	},

	/** The key of a dictation command is being removed. */
	async unbindAccelerator(accelerator: string): Promise<boolean> {
		let found = false;
		for (const [commandId] of Object.entries(NATIVE_ACTIONS)) {
			const id = commandId as NativeCommandId;
			const current = accelerators()[id];
			if (current && current === accelerator) {
				pendingKeys.set(id, null);
				found = true;
			}
		}
		if (found) await push();
		return found;
	},

	sync: push,

	trigger(action: NativeAction, purpose?: string | null) {
		return invoke('hotkey_trigger', { action, purpose: purpose ?? null });
	},

	discard(recordingId: string) {
		return invoke('hotkey_discard', { recordingId });
	},

	/** Starts listening (main window only, once). */
	async init({ handle }: NativeHandlers) {
		if (!engineWindow || initialized) return;
		initialized = true;

		const pump = createEventPump({
			ack: (seq, done) =>
				invoke('hotkey_ack', { seq, done }).catch((error) =>
					console.warn('[hotkey] acknowledging', seq, 'failed', error),
				),
			handle,
			pending: () => invoke<NativeEvent[]>('hotkey_pending'),
		});
		await listen<NativeEvent>(EVENT, (event) => pump.receive(event.payload));

		// Keys and microphone settings follow the settings.
		$effect.root(() => {
			const previous = new Map<string, unknown>();
			$effect(() => {
				let changed = false;
				for (const commandId of Object.keys(NATIVE_ACTIONS) as NativeCommandId[]) {
					const value = settings.value[`shortcuts.global.${commandId}`];
					if (previous.has(commandId) && previous.get(commandId) !== value) {
						// The setting now says what the key is.
						pendingKeys.delete(commandId);
					}
					if (previous.get(commandId) !== value) changed = true;
					previous.set(commandId, value);
				}
				for (const key of [
					'recording.method',
					'recording.cpal.deviceId',
					'recording.cpal.outputFolder',
					'recording.cpal.sampleRate',
				] as const) {
					const value = settings.value[key];
					if (previous.get(key) !== value) changed = true;
					previous.set(key, value);
				}
				const vad = vadRecorder.state !== 'IDLE';
				if (previous.get('vad') !== vad) changed = true;
				previous.set('vad', vad);
				if (changed) void push();
			});
		});
		await push();

		const wake = () => void pump.sync().catch(() => {});
		document.addEventListener('visibilitychange', wake);
		window.addEventListener('focus', wake);
		await pump.sync().catch((error) => console.warn('[hotkey] pulling waiting events failed', error));

		// A page holding a Web Lock is not frozen by Chromium.
		void navigator.locks
			?.request('voiceflow-dictation-engine', () => new Promise<never>(() => {}))
			.catch(() => {});
	},
};
