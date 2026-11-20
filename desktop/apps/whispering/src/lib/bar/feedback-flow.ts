/**
 * VoiceFlow A2: what happens to a recording once it has stopped, and the
 * voice feedback flow. Pure (dependencies are passed in), so it is unit
 * tested.
 *
 * A feedback recording (the feedback hotkey, or "Use this recording as
 * feedback") is transcribed only: no spellings, no tidy-up, no delivery
 * (nothing is pasted or copied), not saved to History, and its audio is not
 * kept. The feedback window then opens with the editable transcript and the
 * dictation it is about. Everything else goes down the dictation pipeline.
 */
import type { FeedbackPayload, FeedbackSubject } from './contract';
import type { FrozenOverrides } from './machine';

export type StoppedRoute = 'feedback' | 'dictation';

/** Feedback recordings never enter the dictation pipeline. */
export function routeStopped(overrides: FrozenOverrides | null | undefined): StoppedRoute {
	return overrides?.purpose === 'feedback' ? 'feedback' : 'dictation';
}

/**
 * Sends a stopped recording down exactly one path: `feedback`, or
 * `dictation` (corrections, tidy-up, delivery).
 */
export async function finishStopped<T>(
	overrides: FrozenOverrides | null | undefined,
	paths: {
		feedback: (overrides: FrozenOverrides) => Promise<T>;
		dictation: () => Promise<T>;
	},
): Promise<T> {
	if (overrides && routeStopped(overrides) === 'feedback') return paths.feedback(overrides);
	return paths.dictation();
}

type TranscribeError = { name: string; title?: string; description?: string };

/** Only what the feedback flow may do: it has no way to paste, copy or tidy. */
export type FeedbackDeps = {
	/** Speech-to-text only (nothing saved to History). */
	transcribe: (
		blob: Blob,
		prompt: string,
	) => Promise<{ data: string; error: null } | { data: null; error: TranscribeError }>;
	removeAudio: (recordingId: string) => Promise<void>;
	hide: (recordingId: string) => void;
	payload: (
		subject: FeedbackSubject | null,
		transcript: { text: string; problem: string | null; recordingId: string | null },
	) => Promise<FeedbackPayload>;
	open: (payload: FeedbackPayload) => Promise<void>;
};

export const FEEDBACK_HELD_PROBLEM =
	"VoiceFlow couldn't tell which app you were in, so your feedback wasn't sent for transcription. Type it instead.";

export async function captureFeedback(
	{
		blob,
		recordingId,
		allowed,
		prompt,
		overrides,
	}: {
		blob: Blob;
		recordingId: string;
		/** The record-start decision allowed sending (held or unresolved: no). */
		allowed: boolean;
		/** The vocabulary prompt (a hint for the words; from the pinned lists). */
		prompt: string;
		overrides: FrozenOverrides;
	},
	deps: FeedbackDeps,
): Promise<FeedbackPayload> {
	let text = '';
	let problem: string | null = null;
	if (!allowed) {
		// Held or unresolved: nothing leaves this computer (spec §4.3).
		problem = FEEDBACK_HELD_PROBLEM;
	} else {
		const { data, error } = await deps.transcribe(blob, prompt);
		if (error) {
			problem = `It couldn't be transcribed (${
				error.name === 'WhisperingError'
					? [error.title, error.description].filter(Boolean).join(': ')
					: 'unknown error'
			}). Type it instead.`;
		} else {
			text = data.trim();
		}
	}
	await deps.removeAudio(recordingId);
	deps.hide(recordingId);
	const payload = await deps.payload(overrides.feedbackFor, {
		text,
		problem,
		recordingId,
	});
	await deps.open(payload);
	return payload;
}

/**
 * What the feedback hotkey does: stop a feedback recording, turn the
 * dictation being recorded into feedback, or start a feedback recording.
 * It keeps no state: the start carries its purpose itself, so a second
 * press while the microphone is still starting (ignored as busy) can't
 * clear the first press's purpose.
 */
export function feedbackHotkeyAction(
	live: { purpose: 'dictation' | 'feedback' } | null,
): 'stop' | 'convert' | 'start' {
	if (live?.purpose === 'feedback') return 'stop';
	return live ? 'convert' : 'start';
}
