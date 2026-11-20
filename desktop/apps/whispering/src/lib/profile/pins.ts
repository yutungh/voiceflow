/**
 * VoiceFlow word lists: one snapshot per dictation (plan v2, Decisions,
 * "Profiles"). A dictation pins the merged profile it started with, so a
 * refresh in the middle never changes its words, spellings or style; while
 * any dictation is pinned the service is told a dictation is running, and
 * it waits to refresh. Pure, so it is unit-tested; `service.svelte.ts`
 * wires it to the real snapshot and the Rust service.
 */
import type { ProfileSnapshot } from './types';

export type ProfilePin = {
	token: number;
	snapshot: ProfileSnapshot | null;
	at: number;
};

/** A pin never released (a crash mid-dictation) stops counting after this. */
export const PIN_EXPIRY_MS = 15 * 60 * 1000;
const MAX_BOUND = 100;

export function createPins(deps: {
	/** The current snapshot (null: word lists off). */
	current: () => ProfileSnapshot | null;
	/** Word lists on. */
	enabled: () => boolean;
	/** Tell the service whether a dictation is running. */
	setBusy: (busy: boolean) => void;
	now?: () => number;
}) {
	const now = deps.now ?? Date.now;
	let nextToken = 1;
	const active = new Map<number, number>();
	const byRecording = new Map<string, ProfileSnapshot | null>();

	return {
		/** At record start: the words, spellings and style this dictation uses. */
		pin(): ProfilePin {
			const at = now();
			for (const [token, since] of active)
				if (at - since > PIN_EXPIRY_MS) active.delete(token);
			const pin = { token: nextToken++, snapshot: deps.current(), at };
			active.set(pin.token, at);
			deps.setBusy(true);
			return pin;
		},
		/** Keeps a pinned snapshot with its recording (for retries later). */
		bind(recordingId: string, pin: ProfilePin | null | undefined) {
			if (!pin) return;
			byRecording.delete(recordingId);
			byRecording.set(recordingId, pin.snapshot);
			while (byRecording.size > MAX_BOUND) {
				const oldest = byRecording.keys().next().value;
				if (oldest === undefined) break;
				byRecording.delete(oldest);
			}
		},
		/** The dictation is finished (delivered, held, failed or cancelled). */
		release(pin: ProfilePin | null | undefined) {
			if (!pin || !active.delete(pin.token)) return;
			if (active.size === 0) deps.setBusy(false);
		},
		/**
		 * The snapshot for a recording: the one pinned when it started, else
		 * the current one (a retry of an older dictation). Null: word lists
		 * are off (then the device's own settings and spellings apply).
		 */
		snapshotFor(recordingId: string | null | undefined): ProfileSnapshot | null {
			if (!deps.enabled()) return null;
			if (recordingId && byRecording.has(recordingId))
				return byRecording.get(recordingId) ?? deps.current();
			return deps.current();
		},
		get running(): number {
			return active.size;
		},
	};
}
