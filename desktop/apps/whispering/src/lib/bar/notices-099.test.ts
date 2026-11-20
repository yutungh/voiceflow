/**
 * VoiceFlow 0.9.9: every notice hides by itself, the pointer pauses that,
 * and every action a notice offers stays reachable after it hides: on Home
 * ("Needs you") and in History for that dictation.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Recording } from '$lib/services/isomorphic/db';
import { problemOf, problemsOf } from '../vf/dictations';
import { BAR_PHASES, type BarErrorKind, type BarPhase } from './contract';
import { demoState } from './fixtures';
import {
	CANCELLED_MS,
	COPIED_MS,
	NOTICE_MS,
	NOTICE_TIMES,
	PROBLEM_MS,
	createBarMachine,
	republishDelay,
} from './machine';
import { noticeFor, type NoticeButton } from './notice';
import { LEAVE_GRACE_MS, graceLeft, presenceFor } from './presence';

const root = join(import.meta.dir, '../..');
const read = (path: string) => readFileSync(join(root, path), 'utf8');
const problemCard = read('lib/vf/ProblemCard.svelte');
const history = read('routes/(app)/(config)/history/+page.svelte');
const home = read('routes/(app)/+page.svelte');
const barPage = read('routes/bar/+page.svelte');

test('the times: success 3-4 s, a problem 8 s, Cancelled 1.5 s', () => {
	expect(NOTICE_MS).toBe(3000);
	expect(COPIED_MS).toBe(4000);
	expect(CANCELLED_MS).toBe(1500);
	expect(PROBLEM_MS).toBe(8000);
	expect(LEAVE_GRACE_MS).toBe(2000);
	// 0.9.12: no held state on the bar (nothing is held since 0.9.11).
	expect(NOTICE_TIMES).toEqual({ pasted: 3000, copied: 4000, cancelled: 1500, failed: 8000 });
});

/** Every notice kind: how the engine gets there, and the dictation it leaves in History. */
type Kind = {
	name: string;
	reach: (m: ReturnType<typeof createBarMachine>) => void;
	ms: number;
	/** The dictation as the engine saved it (null: nothing to recover). */
	saved: Partial<Recording> | null;
	demo: string;
};

const KINDS: Kind[] = [
	{
		name: 'Pasted',
		reach: (m) => m.delivered('r', 'pasted', { app: 'Kite', text: 'hi', styleName: 'Work' }),
		ms: NOTICE_MS,
		saved: { result: 'pasted', finalText: 'hi' },
		demo: 'pasted',
	},
	{
		name: 'Copied',
		reach: (m) => m.delivered('r', 'copied', { app: null, text: 'hi' }),
		ms: COPIED_MS,
		saved: { result: 'copied', finalText: 'hi' },
		demo: 'copied',
	},
	{
		name: 'Not pasted (tidy-up failed)',
		reach: (m) => m.failed('r', 'tidy-up', 'Nothing was pasted', 'Tidy-up timed out'),
		ms: PROBLEM_MS,
		saved: { result: 'not-pasted', problemKind: 'tidy-up', problem: 'Tidy-up timed out' },
		demo: 'failed-tidy',
	},
	{
		name: "Couldn't transcribe",
		reach: (m) => m.failed('r', 'transcription', "Couldn't turn your words into text"),
		ms: PROBLEM_MS,
		saved: { result: 'no-text', problemKind: 'transcription', transcriptionStatus: 'FAILED' },
		demo: 'failed-transcription',
	},
	{
		name: 'Not pasted (paste refused)',
		reach: (m) => m.failed('r', 'paste', "Couldn't paste your words"),
		ms: PROBLEM_MS,
		saved: { result: 'not-pasted', problemKind: 'paste', finalText: 'hi' },
		demo: 'failed-paste',
	},
	{
		name: 'Not saved (start failed)',
		reach: (m) => m.failed('r', 'start', "Your dictation was recorded but couldn't be saved."),
		ms: PROBLEM_MS,
		saved: null,
		demo: '',
	},
	{
		name: 'Cancelled',
		reach: (m) => m.setPhase('r', 'cancelled'),
		ms: CANCELLED_MS,
		saved: null,
		demo: 'cancelled',
	},
];

function machineAt(kind: Kind) {
	let t = 1_000_000;
	const m = createBarMachine({ now: () => t });
	m.begin({
		recordingId: 'r',
		purpose: 'dictation',
		app: { name: 'Kite', icon: null },
		style: { id: 's', name: 'Work', kind: 'business' },
		styles: [{ id: 's', name: 'Work', kind: 'business' }],
	});
	m.stop('r');
	kind.reach(m);
	return { m, at: t, advance: (ms: number) => (t += ms) };
}

function recording(extra: Partial<Recording>): Recording {
	return {
		id: 'r',
		title: '',
		subtitle: '',
		timestamp: '2026-11-18T09:00:00.000Z',
		createdAt: '2026-11-18T09:00:00.000Z',
		updatedAt: '2026-11-18T09:00:00.000Z',
		transcribedText: 'hello there',
		transcriptionStatus: 'DONE',
		...extra,
	};
}

/**
 * Where each bar action is once the notice has gone. Home's "Needs you"
 * and History's detail both show the dictation's ProblemCard; History's
 * "Give feedback" is there for every dictation.
 */
const AFTER: Record<NoticeButton['action'], { card: RegExp | null; history: RegExp | null }> = {
	feedback: { card: null, history: /<span>Give feedback<\/span>/ },
	'paste-raw': { card: /recovery\.pasteRaw\(r\.id\)/, history: null },
	retry: { card: /recovery\.retry(TidyUp|Transcription)\(r\.id\)/, history: null },
	stop: { card: null, history: null },
	cancel: { card: null, history: null },
	'use-as-feedback': { card: null, history: null },
	'use-as-dictation': { card: null, history: null },
	// 0.9.13: History's detail offers Use cleaned while the tidy-up is held back.
	'use-cleaned': { card: null, history: /recovery\.useCleaned\(selected\.id\)/ },
};

/** The ProblemCard branch each problem kind shows, and the fix in it. */
const CARD_FIX: Record<string, Partial<Record<NoticeButton['action'], RegExp>>> = {
	'tidy-up': {
		retry: /problem\.kind === 'tidy-up'\}[\s\S]*?recovery\.retryTidyUp\(r\.id\)/,
		'paste-raw': /problem\.kind === 'tidy-up'\}[\s\S]*?recovery\.pasteRaw\(r\.id\)/,
	},
	transcription: { retry: /problem\.kind === 'transcription'\}[\s\S]*?recovery\.retryTranscription\(r\.id\)/ },
};

describe('every notice hides by itself', () => {
	for (const kind of KINDS) {
		test(`${kind.name}: shown, then gone after ${kind.ms} ms`, () => {
			const { m, at, advance } = machineAt(kind);
			const view = m.view()!;
			expect(view).not.toBeNull();
			expect(view.hidesAt).toBe(at + kind.ms);
			expect(m.nextHideAt()).toBe(at + kind.ms);
			// The engine sends it once more, as hidden, at its deadline.
			expect(republishDelay(view, at)).toBe(kind.ms + 20);
			advance(kind.ms - 1);
			expect(m.view()).not.toBeNull();
			advance(1);
			expect(m.view()).toBeNull();
			expect(m.nextHideAt()).toBeNull();
		});

		test(`${kind.name}: its actions stay reachable after it hides`, () => {
			const { m } = machineAt(kind);
			const notice = noticeFor(m.view()!)!;
			for (const button of notice.buttons) {
				const where = AFTER[button.action];
				expect(where.card ?? where.history).not.toBeNull();
				if (where.history) expect(history).toMatch(where.history);
				if (where.card) {
					// The dictation the engine saved is a problem on Home and in History...
					const saved = recording(kind.saved ?? {});
					const problem = problemOf(saved);
					expect(problem).toBeDefined();
					// (0.9.12: Home shows the last 24 hours; this one is a minute old.)
					const soon = Date.parse(saved.createdAt) + 60_000;
					expect(problemsOf([saved], soon).map((p) => p.kind)).toEqual([problem!.kind]);
					// ...whose card offers the same fix.
					const fix = CARD_FIX[problem!.kind]?.[button.action];
					expect(fix).toBeDefined();
					expect(problemCard).toMatch(fix!);
					// Dismissed on Home, History still offers it.
					expect(problemOf({ ...saved, problemDismissed: true })).toBeUndefined();
					expect(problemOf({ ...saved, problemDismissed: true }, { includeDismissed: true })?.kind).toBe(
						problem!.kind,
					);
				}
			}
			if (kind.demo) {
				// The demo the screenshots use offers the same buttons.
				const demo = noticeFor(demoState(kind.demo, 1)!)!;
				expect(demo.buttons.map((b) => b.action)).toEqual(notice.buttons.map((b) => b.action));
			}
		});
	}

	test('0.9.12: a dictation held by 0.9.10 keeps Send anyway on Home and in History', () => {
		// The bar never shows a held state any more...
		expect(BAR_PHASES as readonly string[]).not.toContain('held');
		expect(barPage).not.toContain('send-anyway');
		// ...but the saved dictation is still held, so its card offers Send anyway.
		expect(problemOf(recording({ privacy: 'held' }))?.kind).toBe('held');
		expect(problemCard).toMatch(/problem\.kind === 'held'\}[\s\S]*?recovery\.sendAnyway\(r\.id\)/);
		// Its audio gone (it can't be sent): no longer a problem.
		expect(problemOf(recording({ privacy: 'held', audioRemovedAt: '2026-11-19T00:00:00Z' }))).toBeUndefined();
	});

	test('a refusal hides by itself too (nothing was recorded, nothing to recover)', () => {
		let t = 0;
		const m = createBarMachine({ now: () => t });
		m.refused('x', { name: 'Vault', icon: null }, 'VoiceFlow is off in Vault', null);
		expect(m.view()!.hidesAt).toBe(PROBLEM_MS);
		expect(noticeFor(m.view()!)!.buttons).toEqual([]);
		t = PROBLEM_MS;
		expect(m.view()).toBeNull();
	});

	test('Retry after the time is up (the pointer kept it): still the right retry', () => {
		const { m, advance } = machineAt(KINDS.find((k) => k.name === "Couldn't transcribe")!);
		advance(PROBLEM_MS + 500);
		expect(m.view()).toBeNull();
		expect(m.errorKind('r')).toBe('transcription' satisfies BarErrorKind);
		expect(m.command({ type: 'retry', recordingId: 'r' }).accepted).toBe(true);
		const controller = read('lib/bar/controller.ts');
		expect(controller).toContain('const kind = machine.errorKind(command.recordingId);');
		expect(controller).toContain('return handlers.retry(id, kind);');
	});

	test('Home keeps showing problems the bar no longer shows; History shows dismissed ones', () => {
		expect(home).toMatch(/<ProblemCard \{problem\} \/>/);
		expect(history).toContain('problemOf(selected, { includeDismissed: true })');
		// No "Dismiss" twice: a dismissed problem's card has none.
		expect(problemCard).toMatch(/\{#if !r\.problemDismissed\}[\s\S]*?recovery\.dismiss\(r\.id\)/);
	});
});

describe('the pointer pauses a notice; leaving restarts a short wait', () => {
	const pasted = { hidesAt: 10_000 };

	test('on the notice at its deadline: it lingers', () => {
		expect(presenceFor(pasted, null, { inside: true, leftAt: null }, 10_000)).toBe('linger');
	});

	test('left just before the deadline: it waits LEAVE_GRACE_MS from leaving, then goes', () => {
		const pointer = { inside: false, leftAt: 9_500 };
		expect(presenceFor(pasted, null, pointer, 10_000)).toBe('linger');
		expect(graceLeft(pointer, 10_000)).toBe(1_500);
		expect(presenceFor(pasted, null, pointer, 11_600)).toBe('leave');
	});

	test('never on it, or left long ago: it goes on time', () => {
		expect(presenceFor(pasted, null, { inside: false, leftAt: null }, 10_000)).toBe('leave');
		expect(presenceFor(pasted, null, { inside: false, leftAt: 1_000 }, 10_000)).toBe('leave');
	});

	test('a hide asked for (×, Feedback) goes at once, even under the pointer', () => {
		expect(presenceFor(pasted, null, { inside: true, leftAt: null }, 5_000)).toBe('leave');
	});

	test('the page restarts the wait on leaving and cancels it on coming back', () => {
		expect(barPage).toMatch(/function cardLeave\(\) \{[\s\S]*?leftAt = Date\.now\(\);[\s\S]*?if \(lingering\) waitThenLeave\(\);/);
		expect(barPage).toMatch(/function cardEnter\(\) \{[\s\S]*?clearTimeout\(graceTimer\);/);
		expect(barPage).toMatch(/if \(decision === 'linger'\) \{\s*lingering = true;\s*if \(!hovered\) waitThenLeave\(\);/);
		// Coming back during the time-out's fade keeps it too.
		expect(barPage).toMatch(/if \(leaving && timedOut && view\) \{\s*clearTimeout\(leaveTimer\);\s*leaving = false;/);
		// A click on Stop does not stop the next notice from lingering under the pointer.
		expect(barPage).toMatch(/layerKey\(next\) !== layerKey\(view\)\) \{[\s\S]*?actedOn = null;/);
	});
});

test('phases with a notice are exactly the ones with a time', () => {
	const phases: BarPhase[] = ['pasted', 'copied', 'failed', 'cancelled'];
	expect(Object.keys(NOTICE_TIMES).sort()).toEqual([...phases].sort());
});
