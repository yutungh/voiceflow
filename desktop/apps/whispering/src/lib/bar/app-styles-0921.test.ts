/**
 * VoiceFlow 0.9.21: an app's starting style may be a built-in or a style
 * made on this PC, and the app's own setting comes before the word lists'
 * rule. References, precedence, and the bar keeping the person's pick.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import type { ProfileSnapshot, ProfileStyle } from '$lib/profile/types';
import {
	BUILT_IN_STYLE_REFS,
	decodePcStyleRef,
	encodePcStyleRef,
	isDeviceStyleRef,
	resolveStyleRef,
	styleRefOf,
} from '../profile/style-refs';
import { validRuleId } from '../profile/ops';
import type { BarStyleOption } from './contract';
import { createBarMachine } from './machine';
import { appRuleFor, appRuleStyleId, barStyleChoice, withAppExpression } from './styles';

function style(id: string, label: string, extra: Partial<ProfileStyle> = {}): ProfileStyle {
	return { id, label, prompt: `Tidy as ${label}.`, layer: 'personal', ...extra };
}

function snapshot(apps: unknown[], extra: Partial<ProfileSnapshot> = {}): ProfileSnapshot {
	return {
		id: 'snap-1',
		user: 'sam',
		terms: [],
		corrections: [],
		styles: [style('work', 'Work', { expression: 1 }), style('notes', 'Notes')],
		defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {} },
		privacy: {},
		apps,
		issues: [],
		...extra,
	};
}

const DEVICE: BarStyleOption[] = [
	{ id: 'vf-builtin-friends', name: 'Friends', kind: 'casual' },
	{ id: 'vf-builtin-ai-prompt', name: 'AI prompt', kind: 'custom' },
	{ id: 'V1StGXR8_Z5jdHi6B-myT', name: 'My memo', kind: 'custom' },
	{ id: 'dev-default', name: 'Clean', kind: 'casual' },
];
const TELEGRAM = { exeName: 'Telegram.exe', bundleId: null };
const CHATGPT = { exeName: 'ChatGPT.exe', bundleId: null };

describe('style references', () => {
	test('a PC style id round-trips, is a valid rule id, and decodes only canonically', () => {
		const ref = encodePcStyleRef('V1StGXR8_Z5jdHi6B-myT')!;
		expect(ref).toBe('vf-pc-.v1.st.g.x.r8_.z5jd.hi6.b-my.t');
		expect(validRuleId(ref)).toBe(true);
		expect(decodePcStyleRef(ref)).toBe('V1StGXR8_Z5jdHi6B-myT');
		// Every nanoid-shaped id round-trips.
		const alphabet = 'useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict';
		for (let i = 0; i < 500; i++) {
			let id = '';
			for (let j = 0; j < 21; j++) id += alphabet[(i * 7 + j * 13 + ((i * j) % 11)) % alphabet.length];
			const r = encodePcStyleRef(id);
			expect(r !== null && validRuleId(r)).toBe(true);
			expect(decodePcStyleRef(r!)).toBe(id);
		}
		expect(encodePcStyleRef('a.b')).toBeNull();
		expect(encodePcStyleRef('x'.repeat(41))).toBeNull();
		for (const bad of ['vf-pc-', 'vf-pc-ab.', 'vf-pc-a.1', 'vf-pc-a..b', 'vf-pc-Ab', 'friends'])
			expect(decodePcStyleRef(bad)).toBeNull();
	});

	test('what a rule stores for each kind of style the bar knows', () => {
		expect(styleRefOf('raw')).toBe('raw');
		expect(styleRefOf('profile:work')).toBe('work');
		expect(styleRefOf('vf-builtin-friends')).toBe('vf-builtin-friends');
		expect(styleRefOf('V1StGXR8_Z5jdHi6B-myT')).toBe('vf-pc-.v1.st.g.x.r8_.z5jd.hi6.b-my.t');
		expect(styleRefOf(null)).toBeNull();
		expect(isDeviceStyleRef('vf-builtin-friends')).toBe(true);
		expect(isDeviceStyleRef('vf-builtin-pirate')).toBe(false);
	});

	test('the built-in ids are the same list in TypeScript and in Rust', () => {
		const rust = readFileSync(new URL('../../../src-tauri/src/profile/merge.rs', import.meta.url), 'utf8');
		const block = rust.match(/BUILTIN_STYLE_REFS: &\[&str\] = &\[([\s\S]*?)\];/)?.[1] ?? '';
		const listed = [...block.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
		expect(listed).toEqual([...BUILT_IN_STYLE_REFS]);
		expect(BUILT_IN_STYLE_REFS).toContain('vf-builtin-clean');
		expect(BUILT_IN_STYLE_REFS).toContain('vf-builtin-friends');
		expect(BUILT_IN_STYLE_REFS).toContain('vf-builtin-ai-prompt');
		for (const ref of BUILT_IN_STYLE_REFS) expect(validRuleId(ref)).toBe(true);
	});

	test('lookup order: the word lists, Raw, then this PC; a missing one is missing', () => {
		const lookup = { profileStyleIds: ['work', 'vf-builtin-email'], deviceStyleIds: DEVICE.map((d) => d.id) };
		expect(resolveStyleRef('work', lookup)).toEqual({ status: 'ok', barId: 'profile:work' });
		expect(resolveStyleRef('raw', lookup)).toEqual({ status: 'ok', barId: 'raw' });
		expect(resolveStyleRef('vf-builtin-friends', lookup)).toEqual({ status: 'ok', barId: 'vf-builtin-friends' });
		// A word-list style with a built-in's id wins.
		expect(resolveStyleRef('vf-builtin-email', lookup)).toEqual({ status: 'ok', barId: 'profile:vf-builtin-email' });
		expect(resolveStyleRef('vf-builtin-work', lookup)).toEqual({ status: 'missing' });
		expect(resolveStyleRef('nope', lookup)).toEqual({ status: 'missing' });
		// Still loading: keep the id.
		expect(resolveStyleRef('vf-builtin-work', { ...lookup, deviceStyleIds: null })).toEqual({
			status: 'pending',
			barId: 'vf-builtin-work',
		});
	});
});

describe('precedence when a dictation starts', () => {
	const pick = (
		target: { exeName: string; bundleId: null },
		snap: ProfileSnapshot | null,
		local: ReadonlyMap<string, { style?: string; expression?: number }> | null = null,
		loaded = true,
	) => {
		const rule = appRuleFor(snap, [target.exeName.toLowerCase()], local);
		return withAppExpression(
			barStyleChoice({
				target,
				snapshot: snap,
				deviceStyles: DEVICE,
				deviceDefaultId: 'dev-default',
				appRule: rule,
				deviceStylesLoaded: loaded,
			}),
			rule,
			snap,
			loaded ? DEVICE : null,
		).style;
	};

	test('Telegram in Friends and ChatGPT in AI prompt, other apps in the default', () => {
		const snap = snapshot([
			{ id: 'telegram.exe', style: 'vf-builtin-friends', editId: 'pc1-a', editIds: ['pc1-a'] },
			{ id: 'chatgpt.exe', style: 'vf-builtin-ai-prompt', editId: 'pc1-b', editIds: ['pc1-b'] },
		]);
		expect(pick(TELEGRAM, snap)).toMatchObject({ id: 'vf-builtin-friends', name: 'Friends' });
		expect(pick(CHATGPT, snap)).toMatchObject({ id: 'vf-builtin-ai-prompt' });
		expect(pick({ exeName: 'Notepad.exe', bundleId: null }, snap).id).toBe('dev-default');
	});

	test('the person’s own setting wins over the word lists’ rule (the merge put it on top)', () => {
		// The word lists said Work for Telegram; the person's edit says Friends.
		const snap = snapshot([
			{
				id: 'telegram.exe',
				style: 'vf-builtin-friends',
				editId: 'pc1-a',
				editIds: ['pc1-a'],
				listsRule: { style: 'work' },
			},
		]);
		expect(pick(TELEGRAM, snap).id).toBe('vf-builtin-friends');
		// Removed: the word lists' rule is back.
		expect(pick(TELEGRAM, snapshot([{ id: 'telegram.exe', style: 'work' }])).id).toBe('profile:work');
	});

	test('word lists off: this PC’s own setting gives the style too (not only the level)', () => {
		const local = new Map([['telegram.exe', { style: 'vf-builtin-friends', expression: 3 }]]);
		const s = pick(TELEGRAM, null, local);
		expect(s.id).toBe('vf-builtin-friends');
		expect(s.appExpression).toBe(3);
		// A style made on this PC.
		const mine = new Map([['telegram.exe', { style: 'vf-pc-.v1.st.g.x.r8_.z5jd.hi6.b-my.t' }]]);
		expect(pick(TELEGRAM, null, mine).id).toBe('V1StGXR8_Z5jdHi6B-myT');
	});

	test('a style that isn’t here any more falls back to the default; still loading keeps it', () => {
		const snap = snapshot([{ id: 'telegram.exe', style: 'vf-builtin-family', expression: 4 }]);
		const gone = pick(TELEGRAM, snap);
		expect(gone.id).toBe('dev-default');
		expect(gone.appExpression).toBeUndefined();
		const loading = pick(TELEGRAM, snap, null, false);
		expect(loading).toMatchObject({ id: 'vf-builtin-family', name: 'Family', appExpression: 4 });
	});

	test('Automatic: no level unless one is set (the style’s own prompt only)', () => {
		const snap = snapshot([{ id: 'telegram.exe', style: 'vf-builtin-friends' }]);
		expect(pick(TELEGRAM, snap).appExpression).toBeUndefined();
	});

	test('the older entry points still read only the word lists', () => {
		const snap = snapshot([{ id: 'telegram.exe', style: 'work' }]);
		expect(appRuleStyleId(snap, ['telegram.exe'])).toBe('profile:work');
		expect(appRuleStyleId(snapshot([{ id: 'telegram.exe', style: 'vf-builtin-friends' }]), ['telegram.exe'])).toBeNull();
		expect(
			appRuleStyleId(snapshot([{ id: 'telegram.exe', style: 'vf-builtin-friends' }]), ['telegram.exe'], DEVICE),
		).toBe('vf-builtin-friends');
	});

	test('a style picked on the bar wins for that dictation', () => {
		const snap = snapshot([{ id: 'telegram.exe', style: 'vf-builtin-friends', expression: 3 }]);
		const rule = appRuleFor(snap, ['telegram.exe'], null);
		const { style, styles } = withAppExpression(
			barStyleChoice({ target: TELEGRAM, snapshot: snap, deviceStyles: DEVICE, deviceDefaultId: 'dev-default', appRule: rule }),
			rule,
			snap,
			DEVICE,
		);
		const machine = createBarMachine();
		machine.begin({ recordingId: 'r1', purpose: 'dictation', app: { name: 'Telegram', icon: null }, style, styles });
		expect(machine.command({ type: 'set-style', recordingId: 'r1', styleId: 'profile:work' }).accepted).toBe(true);
		const frozen = machine.stop('r1');
		expect(frozen?.styleId).toBe('profile:work');
	});
});
