/**
 * VoiceFlow 0.9.21: what Settings > Tidy-up > Style for each app shows.
 */
import { describe, expect, test } from 'bun:test';
import type { ProfileSnapshot, ProfileStyle } from '$lib/profile/types';
import {
	appNames,
	appRows,
	levelOptions,
	nameFromId,
	programId,
	recentApps,
	rulesIn,
	styleChoices,
} from './app-styles';

function style(id: string, label: string, extra: Partial<ProfileStyle> = {}): ProfileStyle {
	return { id, label, prompt: 'p', layer: 'personal', ...extra };
}

function snapshot(apps: unknown[], styles = [style('work', 'Work chat (team)', { expression: 1 })]): ProfileSnapshot {
	return {
		id: 's',
		user: 'sam',
		terms: [],
		corrections: [],
		styles,
		defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {} },
		privacy: {},
		apps,
		issues: [],
	};
}

const DEVICE = [
	{ id: 'vf-builtin-clean', title: 'Clean' },
	{ id: 'vf-builtin-friends', title: 'Friends' },
	{ id: 'vf-builtin-ai-prompt', title: 'AI prompt' },
	{ id: 'Ab3xYz', title: 'My memo' },
];
const NAMES = new Map([
	['telegram.exe', 'Telegram'],
	['chatgpt.exe', 'ChatGPT'],
]);

const rows = (snap: ProfileSnapshot | null, local = new Map(), deviceLoaded = true) =>
	appRows({
		rules: rulesIn(snap, local),
		snapshot: snap,
		device: DEVICE,
		deviceLoaded,
		names: NAMES,
		defaultLabel: 'Clean',
	});

describe('the styles to choose from', () => {
	test('word lists first, then built in, then made on this PC, then Raw', () => {
		const c = styleChoices(snapshot([]), DEVICE);
		expect(c.map((x) => [x.group, x.label, x.ref])).toEqual([
			['lists', 'Work chat (team)', 'work'],
			['builtin', 'Clean', 'vf-builtin-clean'],
			['builtin', 'Friends', 'vf-builtin-friends'],
			['builtin', 'AI prompt', 'vf-builtin-ai-prompt'],
			['pc', 'My memo', 'vf-pc-.ab3x.yz'],
			['raw', 'Raw (no tidy-up)', 'raw'],
		]);
		expect(c[0]?.ownLevel).toBe(1);
	});

	test('a built-in the word lists shadow (same id) is listed once, as theirs', () => {
		const c = styleChoices(snapshot([], [style('vf-builtin-friends', 'Our friends')]), DEVICE);
		expect(c.filter((x) => x.ref === 'vf-builtin-friends').map((x) => x.label)).toEqual(['Our friends']);
	});

	test('a hidden word-list style still takes its id: the built-in with that id isn’t offered', () => {
		// The reference would find the hidden word-list style, not the built-in.
		const c = styleChoices(snapshot([], [style('vf-builtin-friends', 'Old friends', { hidden: true })]), DEVICE);
		expect(c.some((x) => x.ref === 'vf-builtin-friends')).toBe(false);
		expect(c.some((x) => x.ref === 'vf-builtin-clean')).toBe(true);
	});
});

describe('each app’s row', () => {
	test('word lists off: Telegram starts in Friends, ChatGPT in AI prompt (Expressive)', () => {
		const local = new Map([
			['telegram.exe', { style: 'vf-builtin-friends', editId: 'a', editIds: ['a'] }],
			['chatgpt.exe', { style: 'vf-builtin-ai-prompt', expression: 4, editId: 'b', editIds: ['b'] }],
		]);
		const r = rows(null, local);
		expect(r.map((x) => x.summary)).toEqual(['ChatGPT starts in AI prompt, Expressive', 'Telegram starts in Friends']);
		expect(r.map((x) => x.removeLabel)).toEqual(['Remove', 'Remove']);
		expect(r[1]).toMatchObject({ source: 'mine', level: null, status: 'ok', afterRemove: 'Back to your default style (Clean)' });
	});

	test('over a word-list rule: “Restore word-list rule”, and what comes back', () => {
		const snap = snapshot([
			{ id: 'telegram.exe', style: 'vf-builtin-friends', editId: 'a', editIds: ['a'], listsRule: { style: 'work' } },
		]);
		const [row] = rows(snap);
		expect(row).toMatchObject({
			source: 'mine',
			summary: 'Telegram starts in Friends',
			removeLabel: 'Restore word-list rule',
			afterRemove: 'Back to your word lists’ choice: Work chat (team)',
			editIds: ['a'],
		});
	});

	test('a word-list rule alone is shown, can be changed, not removed', () => {
		const [row] = rows(snapshot([{ id: 'slack.exe', style: 'work', expression: 1 }]));
		expect(row).toMatchObject({ name: 'Slack', source: 'lists', removeLabel: null, level: 1 });
		expect(row?.summary).toBe('Slack starts in Work chat (team), Subtle');
	});

	test('Automatic over the word lists’ level says so', () => {
		const snap = snapshot([
			{
				id: 'telegram.exe',
				style: 'vf-builtin-friends',
				expression: 3,
				editId: 'a',
				editIds: ['a'],
				listsRule: { style: 'work', expression: 3 },
			},
		]);
		const [row] = rows(snap);
		expect(row?.level).toBeNull();
		expect(row?.autoNote).toBe('Lively, from your word lists');
		expect(row?.summary).toBe('Telegram starts in Friends, Lively');
		// When an edit set the level (the same value, even another device's
		// cached one), it is the person's choice.
		const [mine] = rows(snapshot([{ ...(snap.apps[0] as object), expressionEditId: 'a' }]));
		expect(mine?.level).toBe(3);
		expect(mine?.autoNote).toBe('');
	});

	test('a style that isn’t on this PC: says so, and that the default is used', () => {
		const local = new Map([['telegram.exe', { style: 'vf-builtin-family', editId: 'a', editIds: ['a'] }]]);
		const [row] = rows(null, local);
		expect(row?.status).toBe('missing');
		expect(row?.summary).toBe('Telegram starts in your default style (Clean): Family isn’t on this PC');
		// Still loading: not called missing yet.
		expect(rows(null, local, false)[0]?.status).toBe('pending');
	});

	test('a rule with words only is not a starting style; Raw has no level', () => {
		expect(rows(snapshot([{ id: 'teams.exe', terms: [] }]))).toEqual([]);
		const [raw] = rows(null, new Map([['notes.exe', { style: 'raw', expression: 2, editIds: ['x'] }]]));
		expect(raw?.summary).toBe('Notes starts in Raw');
	});

	test('the level options', () => {
		expect(levelOptions().map((o) => o.label)).toEqual(['Automatic', 'Reserved', 'Subtle', 'Natural', 'Lively', 'Expressive']);
	});
});

describe('adding an app', () => {
	const recs = [
		{ appName: 'Telegram', appExe: 'Telegram.exe', createdAt: '2026-11-20T10:00:00Z' },
		{ appName: 'ChatGPT', appExe: 'ChatGPT.exe', createdAt: '2026-11-21T10:00:00Z' },
		{ appName: 'Telegram', appExe: 'Telegram.exe', createdAt: '2026-11-22T10:00:00Z' },
		{ appName: 'Gone', appExe: 'gone.exe', createdAt: '2026-11-23T10:00:00Z', deletedAt: '2026-11-24T00:00:00Z' },
		{ appName: 'VoiceFlow', appExe: 'voiceflow.exe', createdAt: '2026-11-23T10:00:00Z' },
		{ appName: null, appExe: null, createdAt: '2026-11-23T10:00:00Z' },
		{ appName: 'Notes', appBundleId: 'com.apple.Notes', appExe: 'Notes', createdAt: '2026-11-19T10:00:00Z' },
	];

	test('recent apps: newest first, once each, not deleted, not already listed, not VoiceFlow', () => {
		expect(recentApps(recs, new Set())).toEqual([
			{ id: 'telegram.exe', name: 'Telegram' },
			{ id: 'chatgpt.exe', name: 'ChatGPT' },
			{ id: 'com.apple.notes', name: 'Notes' },
		]);
		expect(recentApps(recs, new Set(['telegram.exe'])).map((a) => a.id)).toEqual(['chatgpt.exe', 'com.apple.notes']);
		expect(appNames(recs).get('chatgpt.exe')).toBe('ChatGPT');
	});

	test('a typed program name', () => {
		expect(programId('  Telegram ', true)).toEqual({ ok: true, id: 'telegram.exe' });
		expect(programId('Code.exe', true)).toEqual({ ok: true, id: 'code.exe' });
		expect(programId('com.example.App', false)).toEqual({ ok: true, id: 'com.example.app' });
		expect(programId('', true).ok).toBe(false);
		expect(programId('my app', true).ok).toBe(false);
		expect(nameFromId('telegram.exe')).toBe('Telegram');
		expect(nameFromId('com.tinyspeck.slackmacgap')).toBe('Slackmacgap');
	});
});
