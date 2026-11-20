import { describe, expect, test } from 'bun:test';
import type { FeedbackPayload } from './contract';
import {
	captureFeedback,
	FEEDBACK_HELD_PROBLEM,
	finishStopped,
	routeStopped,
	type FeedbackDeps,
} from './feedback-flow';
import type { FrozenOverrides } from './machine';

const SUBJECT = { recordingId: 'prev', appName: 'Kite', at: '2026-11-17T10:00:00.000Z', snippet: 'Hello' };

function frozen(purpose: FrozenOverrides['purpose']): FrozenOverrides {
	return {
		recordingId: 'rec-1',
		purpose,
		styleId: 's-work',
		styleName: 'Work',
		expression: 3,
		feedbackFor: purpose === 'feedback' ? SUBJECT : null,
	};
}

function fakeDeps(transcript: { data: string; error: null } | { data: null; error: { name: string; title?: string } }) {
	const calls: string[] = [];
	const deps: FeedbackDeps = {
		async transcribe(_blob, prompt) {
			calls.push(`transcribe:${prompt}`);
			return transcript;
		},
		async removeAudio(id) {
			calls.push(`remove:${id}`);
		},
		hide(id) {
			calls.push(`hide:${id}`);
		},
		async payload(subject, t) {
			calls.push('payload');
			return {
				feedbackRecordingId: t.recordingId,
				transcript: t.text,
				transcriptProblem: t.problem,
				subject,
				subjectRawText: '',
				subjectFinalText: '',
				subjectStyleName: null,
				subjectAppId: null,
			} satisfies FeedbackPayload;
		},
		async open() {
			calls.push('open');
		},
	};
	return { deps, calls };
}

describe('a stopped recording goes down exactly one path', () => {
	test('feedback never reaches the dictation pipeline (spellings, tidy-up, delivery)', async () => {
		const taken: string[] = [];
		await finishStopped(frozen('feedback'), {
			feedback: async (o) => void taken.push(`feedback:${o.recordingId}`),
			dictation: async () => void taken.push('dictation'),
		});
		expect(taken).toEqual(['feedback:rec-1']);
		expect(routeStopped(frozen('feedback'))).toBe('feedback');
	});

	test('a dictation, or a recording the bar never saw, takes the pipeline', async () => {
		for (const overrides of [frozen('dictation'), null, undefined]) {
			const taken: string[] = [];
			await finishStopped(overrides, {
				feedback: async () => void taken.push('feedback'),
				dictation: async () => void taken.push('dictation'),
			});
			expect(taken).toEqual(['dictation']);
		}
	});
});

describe('voice feedback capture', () => {
	test('allowed: transcribed with the pinned prompt, audio removed, window opened with the subject', async () => {
		const { deps, calls } = fakeDeps({ data: '  The comma was wrong.  ', error: null });
		const payload = await captureFeedback(
			{ blob: new Blob(['x']), recordingId: 'rec-1', allowed: true, prompt: 'Vocabulary: Zorb', overrides: frozen('feedback') },
			deps,
		);
		expect(calls).toEqual(['transcribe:Vocabulary: Zorb', 'remove:rec-1', 'hide:rec-1', 'payload', 'open']);
		expect(payload.transcript).toBe('The comma was wrong.');
		expect(payload.subject).toEqual(SUBJECT);
		expect(payload.feedbackRecordingId).toBe('rec-1');
	});

	test('held or unresolved: nothing is sent; the window opens to type it', async () => {
		const { deps, calls } = fakeDeps({ data: 'never', error: null });
		const payload = await captureFeedback(
			{ blob: new Blob(['x']), recordingId: 'rec-2', allowed: false, prompt: '', overrides: frozen('feedback') },
			deps,
		);
		expect(calls.some((c) => c.startsWith('transcribe'))).toBe(false);
		expect(calls).toEqual(['remove:rec-2', 'hide:rec-2', 'payload', 'open']);
		expect(payload.transcriptProblem).toBe(FEEDBACK_HELD_PROBLEM);
		expect(payload.transcript).toBe('');
	});

	test('a failed transcription still opens the window, with the reason', async () => {
		const { deps, calls } = fakeDeps({ data: null, error: { name: 'WhisperingError', title: 'No key' } });
		const payload = await captureFeedback(
			{ blob: new Blob(['x']), recordingId: 'rec-3', allowed: true, prompt: '', overrides: frozen('feedback') },
			deps,
		);
		expect(calls.at(-1)).toBe('open');
		expect(payload.transcriptProblem).toContain('No key');
	});
});

test('the feedback hotkey keeps no state between presses', async () => {
	const { feedbackHotkeyAction } = await import('./feedback-flow');
	// Two quick presses before the microphone is ready: both ask for a
	// feedback start; the second is ignored as busy and changes nothing.
	expect(feedbackHotkeyAction(null)).toBe('start');
	expect(feedbackHotkeyAction(null)).toBe('start');
	expect(feedbackHotkeyAction({ purpose: 'dictation' })).toBe('convert');
	expect(feedbackHotkeyAction({ purpose: 'feedback' })).toBe('stop');
});
