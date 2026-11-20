import { describe, expect, test } from 'bun:test';
import {
	buildNativeConfig,
	createEventPump,
	endsLater,
	type NativeEvent,
	nativeActionOf,
} from './native';

const target = {
	platform: 'windows',
	resolved: true,
	isSelf: false,
	appName: 'Notepad',
	exeName: 'notepad.exe',
	exePath: null,
	bundleId: null,
	title: 'notes',
	windowId: '1',
	pid: 7,
	samples: 1,
	identityMs: 1,
	passwordCheck: 'not-password',
	passwordCheckMs: 1,
	error: null,
} as const;

function started(seq: number, recordingId = `rec${seq}`): NativeEvent {
	return {
		seq,
		atMs: 0,
		source: 'hotkey',
		kind: 'started',
		recordingId,
		target: { ...target },
		device: { outcome: 'success', deviceId: 'mic' as never },
		ptt: false,
		purpose: null,
	};
}
function stopped(seq: number, recordingId: string): NativeEvent {
	return {
		seq,
		atMs: 0,
		source: 'hotkey',
		kind: 'stopped',
		recordingId,
		recording: { sampleRate: 16000, channels: 1, durationSeconds: 1, filePath: 'x.wav' },
		purpose: null,
	};
}

function harness(rustPending: NativeEvent[] = []) {
	const acks: string[] = [];
	const handled: { seq: number; later: number[] }[] = [];
	let gate: Promise<void> = Promise.resolve();
	const pump = createEventPump({
		ack: (seq, done) => acks.push(`${seq}${done ? ' done' : ''}`),
		handle: async (event, { later }) => {
			await gate;
			handled.push({ seq: event.seq, later: later.map((e) => e.seq) });
		},
		pending: async () => rustPending,
	});
	return {
		pump,
		acks,
		handled,
		hold() {
			let release!: () => void;
			gate = new Promise((r) => (release = r));
			return () => release();
		},
	};
}

describe('native hotkey events reach the dictation once', () => {
	test('each event is acknowledged and handled once, even when re-sent', async () => {
		const h = harness();
		expect(h.pump.receive(started(1))).toBe(true);
		expect(h.pump.receive(started(1))).toBe(false); // the watchdog's re-send
		expect(h.pump.receive(stopped(2, 'rec1'))).toBe(true);
		await h.pump.idle();
		expect(h.handled.map((x) => x.seq)).toEqual([1, 2]);
		// Every copy is acknowledged; each is finished once, after handling.
		expect(h.acks).toEqual(['1', '1', '2', '1 done', '2 done']);
	});

	test('webview asleep: events queued while away are handled in order when it wakes', async () => {
		// Rust kept both events of a whole dictation; the page pulls them on wake.
		const h = harness([stopped(8, 'rec7'), started(7)]);
		await h.pump.sync();
		// The re-sends that were queued in the webview arrive after the pull.
		h.pump.receive(started(7));
		h.pump.receive(stopped(8, 'rec7'));
		await h.pump.idle();
		expect(h.handled).toEqual([
			{ seq: 7, later: [8] },
			{ seq: 8, later: [] },
		]);
		expect(h.pump.handledCount).toBe(2);
	});

	test('events are handled one at a time, in sequence order', async () => {
		const h = harness();
		const release = h.hold();
		h.pump.receive(started(3));
		h.pump.receive(started(5, 'rec5'));
		h.pump.receive(stopped(4, 'rec3')); // arrives late but sorts before 5
		expect(h.handled).toEqual([]);
		// Arrived, not handled: Rust keeps them for a page that reloads now.
		expect(h.acks.filter((a) => a.endsWith('done'))).toEqual([]);
		release();
		await h.pump.idle();
		expect(h.handled.map((x) => x.seq)).toEqual([3, 4, 5]);
		expect(h.acks.filter((a) => a.endsWith('done'))).toEqual(['3 done', '4 done', '5 done']);
	});

	test('a page reloaded mid-way handles what the old page had not finished', async () => {
		// Old page: received the stop, reloaded before finishing it.
		const old = harness();
		old.hold();
		old.pump.receive(stopped(9, 'rec8'));
		expect(old.acks).toEqual(['9']);
		// Rust still holds 9 (never finished); the new page pulls it.
		const fresh = harness([stopped(9, 'rec8')]);
		await fresh.pump.sync();
		await fresh.pump.idle();
		expect(fresh.handled.map((x) => x.seq)).toEqual([9]);
		expect(fresh.acks).toEqual(['9', '9 done']);
	});

	test('a failing handler does not stop the next event', async () => {
		const errors: unknown[] = [];
		const seen: number[] = [];
		const pump = createEventPump({
			ack: () => {},
			handle: async (event) => {
				seen.push(event.seq);
				if (event.seq === 1) throw new Error('boom');
			},
			pending: async () => [],
			onError: (e) => errors.push(e),
		});
		pump.receive(started(1));
		pump.receive(stopped(2, 'rec1'));
		await pump.idle();
		expect(seen).toEqual([1, 2]);
		expect(errors).toHaveLength(1);
	});

	test('a start whose stop is already queued is known to have ended', () => {
		expect(endsLater('rec1', [stopped(2, 'rec1')])).toBe(true);
		expect(endsLater('rec1', [stopped(2, 'rec9')])).toBe(false);
		expect(endsLater('rec1', [])).toBe(false);
	});
});

describe('config for the native hotkeys', () => {
	test('dictation keys, the Rust recorder and the privacy list', () => {
		const config = buildNativeConfig({
			accelerators: {
				toggleManualRecording: 'Control+Space',
				pushToTalk: 'CommandOrControl+Shift+D',
				cancelManualRecording: null,
			},
			method: 'cpal',
			deviceId: 'Mic',
			outputFolder: 'C:\\rec',
			sampleRate: '16000',
			blockedApps: ['1Password.exe', '1password.exe', 'com.agilebits.onepassword7'],
			isMac: false,
		});
		expect(config.bindings).toEqual([
			{ action: 'toggle', accelerator: 'Control+Space' },
			{ action: 'pushToTalk', accelerator: 'Control+Shift+D' },
		]);
		expect(config.recorder).toEqual({ deviceId: 'Mic', outputFolder: 'C:\\rec', sampleRate: 16000 });
		expect(config.blockedApps).toEqual(['1password.exe', 'com.agilebits.onepassword7']);
		expect(config.forward).toBe(false);
	});

	test('another recording method: keys only, forwarded to the page', () => {
		const config = buildNativeConfig({
			accelerators: { toggleManualRecording: 'CommandOrControl+Shift+;' },
			method: 'navigator',
			deviceId: null,
			outputFolder: '',
			sampleRate: null,
			blockedApps: [],
			isMac: true,
		});
		expect(config.recorder).toBeNull();
		expect(config.bindings).toEqual([{ action: 'toggle', accelerator: 'Command+Shift+;' }]);
	});

	test('only the dictation commands are native', () => {
		expect(nativeActionOf('toggleManualRecording')).toBe('toggle');
		expect(nativeActionOf('pushToTalk')).toBe('pushToTalk');
		expect(nativeActionOf('openTransformationPicker')).toBeNull();
		expect(nativeActionOf('toString')).toBeNull();
	});
});
