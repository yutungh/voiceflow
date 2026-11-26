/**
 * VoiceFlow 0.9.18: invented feedback results for the browser preview of the
 * Feedback window (`/feedback-capture?demo=both|none`) and for tests. Never
 * used in the desktop app: the page loads it only outside Tauri, in a dev
 * build. Nothing here is real text; nothing is sent or saved anywhere.
 */
import type { FeedbackClient, FeedbackSetup } from './client';
import type { FixResult } from './engine';
import type { FeedbackRecord } from './log';

export type DemoCase = 'both' | 'none';

export const DEMO_APP = 'Telegram';
const DEMO_RECORDING = 'prev';
const NOW = '2026-01-01T12:00:00.000Z';

function demoSetup(): FeedbackSetup {
	return {
		subject: {
			recordingId: DEMO_RECORDING,
			appName: DEMO_APP,
			at: NOW,
			styleName: 'Work chat',
			deliveredText: 'Ask Zebbo to add milk to the list.',
			rawText: 'ask zibbo to add milk to the list',
			canPaste: true,
		},
		provider: 'Anthropic',
		model: 'demo-model',
		modelName: 'Demo model',
		disclosure: 'Sends this dictation’s text and your note to the model you chose, with your own API key.',
		details: 'Demo: nothing is sent.',
		ready: true,
	};
}

function demoResult(kind: DemoCase): FixResult {
	const base = {
		contract: 2 as const,
		correctedText: 'Ask Zibbo to add milk to the list.',
		proposals: [],
		reviewedAgainst: 'demo',
		dropped: [],
		observations: [
			{ stage: 'tidy-up' as const, change: 'replaced' as const, before: 'zibbo', after: 'Zebbo' },
			{ stage: 'tidy-up' as const, change: 'replaced' as const, before: 'ask', after: 'Ask' },
		],
	};
	if (kind === 'none')
		return {
			...base,
			explanation: 'The tidy-up changed “zibbo” to “Zebbo”. It happened once, so nothing lasting is suggested.',
			issues: [
				{
					layer: 'uncertain',
					summary: 'The tidy-up changed a name it heard correctly.',
					noFixReason: null,
				},
			],
			fixes: [],
		};
	return {
		...base,
		explanation:
			'Speech-to-text heard “zibbo” correctly, but the tidy-up changed it to “Zebbo”. The Work chat style also made the message more formal than a chat needs.',
		issues: [
			{ layer: 'spelling', summary: '“Zibbo” was written as “Zebbo”.', noFixReason: null },
			// 0.9.21: an app's style fix may name a built-in style (Friends).
			{ layer: 'app-context', summary: 'It came out too formal for a chat.', noFixReason: null },
		],
		fixes: [
			{
				kind: 'spelling',
				key: 'demo-spelling',
				issue: 0,
				from: ['Zebbo'],
				to: 'Zibbo',
				reason: 'You said the name is “Zibbo”.',
				appId: 'telegram.exe',
				appName: DEMO_APP,
				defaultScope: 'everywhere',
				checks: {
					app: { ok: true, alsoChanges: [], alsoCount: 0 },
					everywhere: {
						ok: true,
						alsoChanges: [{ recordingId: 'demo-other', before: 'Zebbo said yes.', after: 'Zibbo said yes.' }],
						alsoCount: 1,
					},
				},
			},
			{
				kind: 'style',
				key: 'demo-style',
				issue: 1,
				reason: 'You asked for something less formal in chats.',
				app: 'telegram.exe',
				appName: DEMO_APP,
				style: { id: 'vf-builtin-friends', name: 'Friends' },
				expression: null,
				replaces: `Replaces the current rule for ${DEMO_APP} (Work chat style).`,
				label: `In ${DEMO_APP}, use the Friends style`,
			},
		],
	};
}

/** A sent feedback record with its invented result. */
export function demoRecord(kind: DemoCase): FeedbackRecord {
	return {
		schema: 'voiceflow-feedback-draft',
		version: 2,
		id: `demo-record-${kind}`,
		device: 'demo',
		createdAt: NOW,
		recordingId: DEMO_RECORDING,
		appName: DEMO_APP,
		rawText: 'ask zibbo to add milk to the list',
		finalText: 'Ask Zebbo to add milk to the list.',
		feedback: 'You wrote Zebbo, it’s Zibbo. And it was too formal.',
		status: 'sent',
		sentAt: NOW,
		provider: 'Anthropic',
		model: 'demo-model',
		result: demoResult(kind),
	};
}

/** An in-memory client: apply and undo only change this record. */
export function demoClient(kind: DemoCase): FeedbackClient {
	let record = demoRecord(kind);
	let n = 0;
	const at = () => new Date(Date.parse(NOW) + ++n * 1000).toISOString();
	return {
		async setup() {
			return { ok: true, setup: demoSetup() };
		},
		async saveDraft() {
			return { ok: true, record };
		},
		async submit() {
			return { ok: true, record };
		},
		async apply({ selections }) {
			const live = new Set((record.applied ?? []).filter((a) => !a.undoneAt).map((a) => a.key));
			const fresh = selections.filter((s) => !live.has(s.key));
			if (fresh.length === 0) return { ok: false, message: 'Tick a fix to apply first.', record };
			record = {
				...record,
				applied: [
					...(record.applied ?? []),
					...fresh.map((s) => ({
						key: s.key,
						opId: `demo-op-${s.key}-${n + 1}`,
						...(s.scope ? { scope: s.scope } : {}),
						appliedAt: at(),
					})),
				],
			};
			return { ok: true, record };
		},
		async undo({ opId }) {
			const entry = record.applied?.find((a) => a.opId === opId && !a.undoneAt);
			if (!entry) return { ok: false, message: 'That fix is not applied.' };
			record = {
				...record,
				applied: (record.applied ?? []).map((a) => (a.opId === opId ? { ...a, undoneAt: at() } : a)),
			};
			return { ok: true, record };
		},
		async copy() {
			return { ok: true, message: 'Copied', record };
		},
		async paste() {
			return { ok: true, message: `Pasted into ${DEMO_APP}`, record };
		},
		async openSettings() {
			return { ok: true };
		},
	};
}
