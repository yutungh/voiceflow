/**
 * VoiceFlow phase F: the feedback window's client. Each call is one event to
 * the main window (which owns the engine, the keys, the word lists and the
 * paste path) and one reply back, matched by a random request id. A reply
 * that doesn't come within the time limit is reported plainly.
 */
import type { FeedbackClient, FeedbackRequest, Reply } from './client';
import { FEEDBACK_REQUEST_EVENT, FEEDBACK_RESPONSE_EVENT } from './client';

const TIMEOUT_MS = 120_000;

async function call<T>(request: FeedbackRequest): Promise<Reply<T>> {
	const { emitTo, listen } = await import('@tauri-apps/api/event');
	const reqId = crypto.randomUUID();
	let stop: (() => void) | null = null;
	const answer = new Promise<Reply<T>>((resolve) => {
		const timer = setTimeout(
			() => resolve({ ok: false, message: 'VoiceFlow didn’t answer. Open VoiceFlow and try again.' }),
			TIMEOUT_MS,
		);
		void listen<{ reqId: string; reply: Reply<T> }>(FEEDBACK_RESPONSE_EVENT, (event) => {
			if (event.payload?.reqId !== reqId) return;
			clearTimeout(timer);
			resolve(event.payload.reply);
		}).then((unlisten) => {
			stop = unlisten;
		});
	});
	// Listen first, then ask, so a fast reply isn't missed.
	await new Promise((r) => setTimeout(r, 0));
	await emitTo('main', FEEDBACK_REQUEST_EVENT, { reqId, request });
	const reply = await answer;
	(stop as (() => void) | null)?.();
	return reply;
}

export const windowFeedbackClient: FeedbackClient = {
	setup: (recordingId) => call({ op: 'setup', recordingId }),
	saveDraft: (a) => call({ op: 'saveDraft', ...a }),
	submit: (a) => call({ op: 'submit', ...a }),
	apply: (a) => call({ op: 'apply', ...a }),
	undo: (a) => call({ op: 'undo', ...a }),
	copy: (a) => call({ op: 'copy', ...a }),
	paste: (a) => call({ op: 'paste', ...a }),
	openSettings: (section) => call({ op: 'openSettings', section }),
};
