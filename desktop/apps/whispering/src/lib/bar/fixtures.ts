/**
 * VoiceFlow A2: sample bar states, for looking at the bar outside the app
 * (`/bar?demo=<name>` in the web dev build, used for the design screenshots).
 * The desktop app never uses these.
 */
import { chipRow } from './chips';
import { NOTICE_TIMES } from './machine';
import { RAW_STYLE_ID, type BarStyleOption, type DictationState } from './contract';

const WORK: BarStyleOption = { id: 'work', name: 'Work', kind: 'business' };
const FRIENDS: BarStyleOption = { id: 'friends', name: 'Friends', kind: 'casual' };
const FAMILY: BarStyleOption = { id: 'family', name: 'Family', kind: 'family' };
const PARTNER: BarStyleOption = { id: 'partner', name: 'Partner', kind: 'partner' };
const HAIKU: BarStyleOption = { id: 'haiku', name: 'Haiku', kind: 'fun_haiku' };
const PIRATE: BarStyleOption = { id: 'pirate', name: 'Pirate', kind: 'fun_pirate' };
/** Seven styles in the Styles page's order (Raw last): five chips and More. */
const ALL: BarStyleOption[] = [FRIENDS, WORK, FAMILY, PARTNER, HAIKU, PIRATE];
const RAW: BarStyleOption = { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };
// 0.9.9: whole names that need a second chip row (demo names only).
const RELAXED: BarStyleOption = { id: 'relaxed', name: 'Relaxed Format Text', kind: 'custom' };
const AI: BarStyleOption = { id: 'ai', name: 'AI prompt', kind: 'custom' };
const EMAIL: BarStyleOption = { id: 'email', name: 'Email', kind: 'custom' };
const CHAT: BarStyleOption = { id: 'chat', name: 'Work chat', kind: 'custom' };
const NOTES: BarStyleOption = { id: 'notes', name: 'Notes', kind: 'custom' };
const CONCISE: BarStyleOption = { id: 'concise', name: 'Concise', kind: 'custom' };
const WRAP: BarStyleOption[] = [RELAXED, AI, EMAIL, CHAT, NOTES, CONCISE];
// Longer still: more than two rows' worth, and one absurdly long name.
const WIDE: BarStyleOption[] = [
	{ id: 'w1', name: 'Meeting Summary Bullets', kind: 'custom' },
	{ id: 'w2', name: 'Customer Support Reply', kind: 'custom' },
	{ id: 'w3', name: 'Formal Letters to the Homeowners Association Board', kind: 'business' },
	{ id: 'w4', name: 'Release Notes Draft', kind: 'custom' },
	{ id: 'w5', name: 'Quick Reply', kind: 'custom' },
	{ id: 'w6', name: 'Friends', kind: 'casual' },
];
const LONG: BarStyleOption = {
	id: 'long',
	name: 'Formal letters to the homeowners association',
	kind: 'business',
};

function base(now: number): DictationState {
	return {
		recordingId: 'demo',
		seq: 1,
		phase: 'listening',
		purpose: 'dictation',
		startedAt: now - 12_000,
		hidesAt: null,
		app: { name: 'Kite', icon: null },
		style: { id: FRIENDS.id, name: FRIENDS.name, kind: FRIENDS.kind },
		expression: null,
		defaultExpression: 2,
		favourites: [FRIENDS, WORK, FAMILY, PARTNER, HAIKU],
		styles: [...ALL, RAW],
		frozen: false,
		pastedInto: null,
		error: null,
		feedbackFor: null,
		actions: ['stop', 'cancel', 'set-style', 'set-expression', 'use-as-feedback'],
		keys: { stop: 'Control+Shift+;', cancel: "Control+Shift+'" },
	};
}

export const DEMO_NAMES = [
	'listening',
	'listening-lively',
	'listening-raw',
	'listening-menu',
	'feedback',
	// 0.9.8: the card.
	'listening-more',
	'listening-no-previous',
	'listening-pirate',
	'feedback-converted',
	'feedback-no-subject',
	'transcribing',
	'tidying',
	'pasted',
	'copied',
	'failed-tidy',
	'failed-transcription',
	'refused',
	'cancelled',
	// 0.9.9: whole names, wrapping to a second row.
	'listening-wrap',
	'listening-wrap-more',
	'listening-wide',
	// 0.9.5: the fixed-size proofs.
	'listening-long-style',
	'pasted-long-app',
	'failed-paste',
	'listening-quiet',
	'listening-note',
	// 0.9.12: Nothing heard, with Retry (a recording of 3 s or more).
	'failed-empty',
] as const;
export type DemoName = (typeof DEMO_NAMES)[number];

export function demoState(name: string, now = Date.now()): DictationState | null {
	const state = demoBase(name, now);
	if (!state) return null;
	// 0.9.9: every notice hides by itself, as the engine sends it.
	const ms = (NOTICE_TIMES as Partial<Record<string, number>>)[state.phase];
	const hidesAt = state.hidesAt ?? (ms === undefined ? null : now + ms);
	// The chips, as the engine would send them.
	return { ...state, hidesAt, favourites: chipRow(state.styles, state.style.id).chips };
}

function demoBase(name: string, now: number): DictationState | null {
	const s = base(now);
	switch (name as DemoName) {
		case 'listening':
		case 'listening-menu':
		case 'listening-quiet':
		case 'listening-note':
			return s;
		case 'listening-more':
			return s;
		case 'listening-no-previous':
			// Nothing to give feedback on yet: Feedback shows, but can't be used.
			return { ...s, actions: ['stop', 'cancel', 'set-style', 'set-expression'] };
		case 'listening-pirate':
			// Picked from More: it takes the fifth chip.
			return { ...s, style: { id: PIRATE.id, name: PIRATE.name, kind: PIRATE.kind }, expression: 4 };
		case 'feedback-converted':
			return {
				...s,
				purpose: 'feedback',
				actions: ['stop', 'cancel', 'use-as-dictation'],
				feedbackFor: {
					recordingId: 'prev',
					appName: 'Telegram',
					at: new Date(now - 90_000).toISOString(),
					snippet: 'Running ten minutes late, save me a seat by the window',
				},
			};
		case 'feedback-no-subject':
			return { ...s, purpose: 'feedback', actions: ['stop', 'cancel'], feedbackFor: null };
		case 'listening-wrap':
		case 'listening-wrap-more':
			return {
				...s,
				style: { id: RELAXED.id, name: RELAXED.name, kind: RELAXED.kind },
				styles: [...WRAP, RAW],
			};
		case 'listening-wide':
			return {
				...s,
				style: { id: 'w3', name: WIDE[2]!.name, kind: 'business' },
				styles: [...WIDE, RAW],
			};
		case 'listening-long-style':
			return { ...s, style: { ...LONG }, styles: [FRIENDS, WORK, FAMILY, PARTNER, LONG, RAW] };
		case 'pasted-long-app':
			return {
				...s,
				phase: 'pasted',
				frozen: true,
				app: { name: 'Microsoft Visual Studio Code - Insiders (Administrator)', icon: null },
				pastedInto: 'Microsoft Visual Studio Code - Insiders (Administrator)',
				hidesAt: now + 3000,
				actions: ['feedback', 'dismiss'],
			};
		case 'failed-paste':
			return {
				...s,
				phase: 'failed',
				frozen: true,
				app: { name: 'Telegram', icon: null },
				error: {
					kind: 'paste',
					title: "Couldn't paste your words",
					detail: 'Telegram refused the paste. Your words are saved in History and on the clipboard.',
				},
				actions: ['feedback', 'dismiss'],
			};
		case 'listening-lively':
			return { ...s, style: { ...WORK }, expression: 3 };
		case 'listening-raw':
			return {
				...s,
				style: { ...RAW },
				actions: ['stop', 'cancel', 'set-style', 'use-as-feedback'],
			};
		case 'feedback':
			return {
				...s,
				purpose: 'feedback',
				actions: ['stop', 'cancel'],
				feedbackFor: {
					recordingId: 'prev',
					appName: 'Telegram',
					at: new Date(now - 90_000).toISOString(),
					snippet: 'Running ten minutes late, save me a seat by the window',
				},
			};
		case 'transcribing':
			return { ...s, phase: 'transcribing', frozen: true, actions: [] };
		case 'tidying':
			return { ...s, phase: 'tidying', frozen: true, expression: 3, actions: [] };
		case 'pasted':
			return {
				...s,
				phase: 'pasted',
				frozen: true,
				pastedInto: 'Kite',
				hidesAt: now + 3000,
				actions: ['feedback', 'dismiss'],
			};
		case 'copied':
			return {
				...s,
				phase: 'copied',
				frozen: true,
				hidesAt: null,
				actions: ['feedback', 'dismiss'],
			};
		case 'failed-tidy':
			return {
				...s,
				phase: 'failed',
				frozen: true,
				app: { name: 'Telegram', icon: null },
				error: {
					kind: 'tidy-up',
					title: 'Nothing was pasted into Telegram',
					detail: 'Tidy-up took over 10 seconds, so your words were kept instead of pasted.',
				},
				actions: ['retry', 'paste-raw', 'feedback', 'dismiss'],
			};
		case 'failed-transcription':
			return {
				...s,
				phase: 'failed',
				frozen: true,
				error: {
					kind: 'transcription',
					title: "Couldn't turn your words into text",
					detail: 'No internet connection. Your audio is saved in History.',
				},
				actions: ['retry', 'dismiss'],
			};
		case 'refused':
			return {
				...s,
				phase: 'failed',
				frozen: true,
				app: { name: '1Password', icon: null },
				error: {
					kind: 'refused',
					title: 'VoiceFlow is off in 1Password',
					detail: 'Nothing was recorded.',
				},
				actions: ['dismiss'],
			};
		case 'cancelled':
			return { ...s, phase: 'cancelled', frozen: true, actions: ['dismiss'] };
		case 'failed-empty':
			return {
				...s,
				phase: 'failed',
				frozen: true,
				hidesAt: now + 4000,
				error: {
					kind: 'empty',
					title: 'Nothing heard',
					detail: 'No speech was heard, so there was nothing to paste.',
				},
				actions: ['retry', 'dismiss'],
			};
		default:
			return null;
	}
}
