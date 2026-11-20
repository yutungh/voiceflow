import { describe, expect, test } from 'bun:test';
import { RAW_STYLE_ID, type BarStyleOption } from './contract';
import { createBarMachine, republishDelay, snippetOf, type BeginInput } from './machine';

const WORK: BarStyleOption = { id: 's-work', name: 'Work', kind: 'business' };
const FRIENDS: BarStyleOption = { id: 's-friends', name: 'Friends', kind: 'casual' };
const RAW: BarStyleOption = { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };

function setup() {
	let t = 1_000_000;
	const machine = createBarMachine({ now: () => t, noticeMs: 6000, cancelledMs: 1500, problemMs: 60_000 });
	const begin = (id: string, extra: Partial<BeginInput> = {}) =>
		machine.begin({
			recordingId: id,
			purpose: 'dictation',
			app: { name: 'Kite', icon: null },
			style: WORK,
			styles: [WORK, FRIENDS, RAW],
			...extra,
		});
	return {
		machine,
		begin,
		advance: (ms: number) => {
			t += ms;
		},
	};
}

describe('recording ids', () => {
	test('a command for another recording is ignored', () => {
		const { machine, begin } = setup();
		begin('a');
		begin('b');
		expect(machine.command({ type: 'stop', recordingId: 'a' })).toEqual({
			accepted: false,
			reason: 'stale',
		});
		expect(machine.command({ type: 'set-style', recordingId: 'a', styleId: FRIENDS.id })).toEqual({
			accepted: false,
			reason: 'stale',
		});
		expect(machine.command({ type: 'stop', recordingId: 'b' }).accepted).toBe(true);
	});

	test('a new recording supersedes the shown notice; the old one keeps updating unseen', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.stop('a');
		begin('b');
		machine.delivered('a', 'pasted', { app: 'Kite', text: 'hello there' });
		const view = machine.view();
		expect(view?.recordingId).toBe('b');
		expect(view?.phase).toBe('listening');
		// The old recording's outcome was kept.
		expect(machine.previousDictation('b')?.snippet).toBe('hello there');
	});

	test('a late click on a superseded notice does nothing', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.stop('a');
		machine.failed('a', 'tidy-up', 'Nothing was pasted into Kite');
		begin('b');
		expect(machine.command({ type: 'retry', recordingId: 'a' }).accepted).toBe(false);
		expect(machine.command({ type: 'paste-raw', recordingId: 'a' }).accepted).toBe(false);
	});

	test('seq increases with every visible change', () => {
		const { machine, begin } = setup();
		begin('a');
		const s1 = machine.view()!.seq;
		machine.command({ type: 'set-expression', recordingId: 'a', expression: 3 });
		const s2 = machine.view()!.seq;
		machine.stop('a');
		const s3 = machine.view()!.seq;
		expect(s2).toBeGreaterThan(s1);
		expect(s3).toBeGreaterThan(s2);
	});
});

describe('overrides', () => {
	test('the default style is pinned at start and expression starts as the style own', () => {
		const { machine, begin } = setup();
		begin('a');
		const view = machine.view()!;
		expect(view.style.id).toBe(WORK.id);
		expect(view.expression).toBeNull();
		expect(view.defaultExpression).toBe(2);
		expect(view.actions).toContain('set-expression');
	});

	test('overrides change while listening and are frozen at Stop', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.command({ type: 'set-style', recordingId: 'a', styleId: FRIENDS.id });
		machine.command({ type: 'set-expression', recordingId: 'a', expression: 4 });
		const frozen = machine.stop('a');
		expect(frozen).toMatchObject({ purpose: 'dictation', styleId: FRIENDS.id, expression: 4 });
		// After Stop nothing changes them any more.
		expect(machine.command({ type: 'set-style', recordingId: 'a', styleId: WORK.id }).accepted).toBe(false);
		expect(machine.command({ type: 'set-expression', recordingId: 'a', expression: 0 }).accepted).toBe(false);
		expect(machine.nudgeExpression(+1)).toBeNull();
		expect(machine.cycleStyle()).toBeNull();
		expect(machine.frozen('a')).toMatchObject({ styleId: FRIENDS.id, expression: 4 });
		expect(machine.view()!.frozen).toBe(true);
	});

	test('expression is clamped to 0–4 and disabled for Raw', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.command({ type: 'set-expression', recordingId: 'a', expression: 9 });
		expect(machine.view()!.expression).toBe(4);
		machine.nudgeExpression(+1);
		expect(machine.view()!.expression).toBe(4);
		machine.command({ type: 'set-style', recordingId: 'a', styleId: RAW_STYLE_ID });
		expect(machine.view()!.actions).not.toContain('set-expression');
		expect(machine.command({ type: 'set-expression', recordingId: 'a', expression: 1 }).accepted).toBe(false);
		expect(machine.stop('a')).toMatchObject({ styleId: RAW_STYLE_ID, expression: null });
	});

	test('the style hotkey cycles through the chips', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.cycleStyle();
		expect(machine.view()!.style.id).toBe(FRIENDS.id);
		machine.cycleStyle();
		machine.cycleStyle();
		expect(machine.view()!.style.id).toBe(WORK.id);
		machine.nudgeExpression(-1);
		expect(machine.view()!.expression).toBe(1);
	});

	test('an unknown style is refused', () => {
		const { machine, begin } = setup();
		begin('a');
		expect(machine.command({ type: 'set-style', recordingId: 'a', styleId: 'gone' })).toEqual({
			accepted: false,
			reason: 'unknown-style',
		});
	});

	test('Stop twice returns nothing the second time', () => {
		const { machine, begin } = setup();
		begin('a');
		expect(machine.stop('a')).not.toBeNull();
		expect(machine.stop('a')).toBeNull();
	});
});

describe('feedback', () => {
	test('"use this recording as feedback" pins the previous dictation at that moment', () => {
		const { machine, begin, advance } = setup();
		begin('first', { app: { name: 'Telegram', icon: null } });
		machine.stop('first');
		machine.delivered('first', 'pasted', { app: 'Telegram', text: 'see you at eight' });
		advance(10_000);
		begin('second');
		expect(machine.command({ type: 'use-as-feedback', recordingId: 'second' }).accepted).toBe(true);
		const view = machine.view()!;
		expect(view.purpose).toBe('feedback');
		expect(view.feedbackFor).toMatchObject({
			recordingId: 'first',
			appName: 'Telegram',
			snippet: 'see you at eight',
		});
		// A dictation made later doesn't change what it is about.
		const frozen = machine.stop('second');
		expect(frozen?.purpose).toBe('feedback');
		expect(frozen?.feedbackFor?.recordingId).toBe('first');
		begin('third');
		expect(machine.frozen('second')?.feedbackFor?.recordingId).toBe('first');
		// The feedback recording is never "the previous dictation".
		expect(machine.previousDictation('third')?.recordingId).toBe('first');
	});

	test('a recording started as feedback pins the previous dictation and offers only Stop and Cancel', () => {
		const { machine, begin } = setup();
		begin('d1');
		machine.stop('d1');
		begin('f1', { purpose: 'feedback' });
		const view = machine.view()!;
		expect(view.feedbackFor?.recordingId).toBe('d1');
		expect(view.actions).toEqual(['stop', 'cancel']);
		expect(machine.command({ type: 'set-style', recordingId: 'f1', styleId: FRIENDS.id }).accepted).toBe(false);
	});

	test('with no dictation yet this session, the fallback is used', () => {
		const { machine, begin } = setup();
		machine.setFallbackPrevious({ recordingId: 'old', appName: 'Mail', at: 'x', snippet: '' });
		begin('f', { purpose: 'feedback' });
		expect(machine.view()!.feedbackFor?.recordingId).toBe('old');
	});

	test('feedback is not offered twice and is not available after Stop', () => {
		const { machine, begin } = setup();
		begin('d');
		machine.stop('d');
		begin('a');
		expect(machine.command({ type: 'use-as-feedback', recordingId: 'a' }).accepted).toBe(true);
		expect(machine.command({ type: 'use-as-feedback', recordingId: 'a' }).accepted).toBe(false);
		machine.stop('a');
		expect(machine.command({ type: 'use-as-dictation', recordingId: 'a' }).accepted).toBe(false);
	});
});

describe('0.9.8: Feedback and Back to dictation', () => {
	test('with no dictation to give feedback on, Feedback is not available', () => {
		const { machine, begin } = setup();
		begin('a');
		expect(machine.view()!.actions).not.toContain('use-as-feedback');
		expect(machine.command({ type: 'use-as-feedback', recordingId: 'a' })).toEqual({
			accepted: false,
			reason: 'not-allowed',
		});
		expect(machine.view()!.purpose).toBe('dictation');
		// The fallback from History counts as a previous dictation.
		machine.setFallbackPrevious({ recordingId: 'old', appName: 'Mail', at: 'x', snippet: 'hi' });
		expect(machine.view()!.actions).toContain('use-as-feedback');
	});

	test('feedback and back again: the dictation keeps its style and expression', () => {
		const { machine, begin } = setup();
		begin('first');
		machine.stop('first');
		machine.delivered('first', 'pasted', { app: 'Kite', text: 'see you at eight' });
		begin('second');
		machine.command({ type: 'set-style', recordingId: 'second', styleId: FRIENDS.id });
		machine.command({ type: 'set-expression', recordingId: 'second', expression: 3 });

		expect(machine.command({ type: 'use-as-feedback', recordingId: 'second' }).accepted).toBe(true);
		let view = machine.view()!;
		expect(view.purpose).toBe('feedback');
		expect(view.feedbackFor?.snippet).toBe('see you at eight');
		expect(view.actions).toEqual(['stop', 'cancel', 'use-as-dictation']);
		// While it is feedback, its style can't change.
		expect(machine.command({ type: 'set-style', recordingId: 'second', styleId: WORK.id }).accepted).toBe(false);
		expect(machine.cycleStyle()).toEqual({ accepted: false, reason: 'not-allowed' });

		expect(machine.command({ type: 'use-as-dictation', recordingId: 'second' }).accepted).toBe(true);
		view = machine.view()!;
		expect(view.purpose).toBe('dictation');
		expect(view.feedbackFor).toBeNull();
		expect(view.style.id).toBe(FRIENDS.id);
		expect(view.expression).toBe(3);
		expect(view.actions).toContain('use-as-feedback');
		expect(view.actions).not.toContain('use-as-dictation');
		// Stop: it goes down the dictation path with what was chosen.
		expect(machine.stop('second')).toMatchObject({
			purpose: 'dictation',
			styleId: FRIENDS.id,
			expression: 3,
			feedbackFor: null,
		});
		// It is a dictation again: the next feedback is about it.
		begin('third');
		expect(machine.previousDictation('third')?.recordingId).toBe('second');
	});

	test('back in its place among the dictations, by when it started', () => {
		const { machine, begin, advance } = setup();
		begin('one');
		machine.stop('one');
		advance(1000);
		begin('two');
		machine.command({ type: 'use-as-feedback', recordingId: 'two' });
		expect(machine.previousDictation(null)?.recordingId).toBe('one');
		machine.command({ type: 'use-as-dictation', recordingId: 'two' });
		expect(machine.previousDictation(null)?.recordingId).toBe('two');
		// Toggling many times never duplicates it.
		for (let i = 0; i < 3; i++) {
			machine.command({ type: 'use-as-feedback', recordingId: 'two' });
			machine.command({ type: 'use-as-dictation', recordingId: 'two' });
		}
		machine.stop('two');
		begin('three');
		machine.command({ type: 'use-as-feedback', recordingId: 'three' });
		expect(machine.view()!.feedbackFor?.recordingId).toBe('two');
	});

	test('a recording started with the feedback key stays feedback', () => {
		const { machine, begin } = setup();
		begin('d');
		machine.stop('d');
		begin('f', { purpose: 'feedback' });
		expect(machine.view()!.actions).not.toContain('use-as-dictation');
		expect(machine.command({ type: 'use-as-dictation', recordingId: 'f' }).accepted).toBe(false);
		expect(machine.stop('f')?.purpose).toBe('feedback');
	});

	test('Back to dictation for another recording is ignored', () => {
		const { machine, begin } = setup();
		begin('d');
		machine.stop('d');
		begin('a');
		machine.command({ type: 'use-as-feedback', recordingId: 'a' });
		begin('b');
		expect(machine.command({ type: 'use-as-dictation', recordingId: 'a' })).toEqual({
			accepted: false,
			reason: 'stale',
		});
	});

	test('after Stop is clicked nothing else changes, even before the recorder stops', () => {
		const { machine, begin } = setup();
		begin('d');
		machine.stop('d');
		begin('a');
		expect(machine.command({ type: 'stop', recordingId: 'a' }).accepted).toBe(true);
		// Still listening until the recorder reports it stopped.
		expect(machine.view()!.phase).toBe('listening');
		expect(machine.view()!.actions).toEqual(['stop', 'cancel']);
		for (const command of [
			{ type: 'use-as-feedback', recordingId: 'a' },
			{ type: 'set-style', recordingId: 'a', styleId: FRIENDS.id },
			{ type: 'set-expression', recordingId: 'a', expression: 0 },
		] as const) {
			expect(machine.command(command).accepted).toBe(false);
		}
		expect(machine.cycleStyle()?.accepted).toBe(false);
		expect(machine.nudgeExpression(1)?.accepted).toBe(false);
		expect(machine.stop('a')).toMatchObject({ purpose: 'dictation', styleId: WORK.id, expression: null });
	});
});

describe('notices', () => {
	test('Pasted hides after six seconds; a failure stays longer', () => {
		const { machine, begin, advance } = setup();
		begin('a');
		machine.stop('a');
		machine.setPhase('a', 'tidying');
		machine.delivered('a', 'pasted', { app: 'Kite', text: 'x', styleName: 'Work' });
		const view = machine.view()!;
		expect(view.phase).toBe('pasted');
		expect(view.pastedInto).toBe('Kite');
		expect(view.actions).toEqual(['feedback', 'dismiss']);
		advance(5999);
		expect(machine.view()).not.toBeNull();
		advance(2);
		expect(machine.view()).toBeNull();

		begin('b');
		machine.stop('b');
		machine.failed('b', 'tidy-up', 'Nothing was pasted into Kite', 'Tidy-up timed out');
		advance(59_999);
		const failed = machine.view()!;
		expect(failed.phase).toBe('failed');
		expect(failed.actions).toEqual(['retry', 'paste-raw', 'feedback', 'dismiss']);
		advance(2);
		expect(machine.view()).toBeNull();
	});

	test('0.9.9: Pasted hides after three seconds by default, Copied after four', () => {
		let t = 0;
		const machine = createBarMachine({ now: () => t });
		const begin = (id: string) =>
			machine.begin({
				recordingId: id,
				purpose: 'dictation',
				app: { name: 'Kite', icon: null },
				style: WORK,
				styles: [WORK],
			});
		begin('a');
		machine.stop('a');
		machine.delivered('a', 'pasted', { app: 'Kite', text: 'x' });
		t = 2999;
		expect(machine.view()?.phase).toBe('pasted');
		t = 3000;
		expect(machine.view()).toBeNull();

		begin('b');
		machine.stop('b');
		machine.delivered('b', 'copied', { app: null, text: 'y' });
		const shownAt = t;
		t = shownAt + 3999;
		expect(machine.view()?.phase).toBe('copied');
		expect(machine.view()?.hidesAt).toBe(shownAt + 4000);
		expect(machine.command({ type: 'dismiss', recordingId: 'b' }).accepted).toBe(true);
		expect(machine.view()).toBeNull();
	});

	test('0.9.12: Nothing heard offers Retry only for a recording long enough to retry', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.stop('a');
		machine.failed('a', 'empty', 'Nothing heard', null, { retry: true });
		expect(machine.view()!.actions).toEqual(['retry', 'dismiss']);
		expect(machine.command({ type: 'retry', recordingId: 'a' }).accepted).toBe(true);
		begin('b');
		machine.stop('b');
		machine.failed('b', 'empty', 'Nothing heard');
		expect(machine.view()!.actions).toEqual(['dismiss']);
		expect(machine.command({ type: 'retry', recordingId: 'b' }).accepted).toBe(false);
		// Dismissed: later clicks for it are stale.
		machine.command({ type: 'dismiss', recordingId: 'b' });
		expect(machine.view()).toBeNull();
	});

	test('a retry shows the same recording again', () => {
		const { machine, begin } = setup();
		begin('a');
		machine.stop('a');
		machine.failed('a', 'transcription', "Couldn't turn your words into text");
		expect(machine.command({ type: 'retry', recordingId: 'a' }).accepted).toBe(true);
		machine.setPhase('a', 'transcribing');
		expect(machine.view()!.phase).toBe('transcribing');
		expect(machine.view()!.error).toBeNull();
	});

	test('Cancelled shows briefly', () => {
		const { machine, begin, advance } = setup();
		begin('a');
		machine.setPhase('a', 'cancelled');
		expect(machine.view()!.phase).toBe('cancelled');
		advance(1600);
		expect(machine.view()).toBeNull();
	});

	test('a refusal shows with only Dismiss', () => {
		const { machine } = setup();
		machine.refused('r1', { name: '1Password', icon: null }, 'VoiceFlow is off in 1Password', null);
		const view = machine.view()!;
		expect(view.phase).toBe('failed');
		expect(view.error?.kind).toBe('refused');
		expect(view.actions).toEqual(['dismiss']);
	});

	test('updates for unknown recordings are ignored', () => {
		const { machine } = setup();
		machine.setPhase('nope', 'pasted');
		machine.failed('nope', 'paste', 'x');
		expect(machine.view()).toBeNull();
	});
});

test('snippets are one line and short', () => {
	expect(snippetOf('  hello\n\nthere  ')).toBe('hello there');
	expect(snippetOf('a'.repeat(200)).length).toBe(80);
});

test("the expression control starts at the style's own level and follows a style change", () => {
	const { machine, begin } = setup();
	const LISTS: BarStyleOption = { id: 'profile:work', name: 'Work', kind: 'business', expression: 1 };
	begin('a', { style: LISTS, styles: [LISTS, FRIENDS, RAW] });
	expect(machine.view()!.defaultExpression).toBe(1);
	machine.nudgeExpression(+1);
	expect(machine.view()!.expression).toBe(2);
	machine.command({ type: 'set-style', recordingId: 'a', styleId: FRIENDS.id });
	// A chosen level stays; the shown default is the new style's.
	expect(machine.view()!.expression).toBe(2);
	expect(machine.view()!.defaultExpression).toBe(2);
	// Nothing chosen: tidy-up gets null and uses the style's own level.
	begin('b', { style: LISTS, styles: [LISTS, RAW] });
	expect(machine.stop('b')!.expression).toBeNull();
});

test('the overrides frozen at Stop outlive the bar records (Retry from Home much later)', () => {
	const { machine, begin } = setup();
	begin('first');
	machine.command({ type: 'set-style', recordingId: 'first', styleId: FRIENDS.id });
	machine.command({ type: 'set-expression', recordingId: 'first', expression: 4 });
	machine.stop('first');
	for (let i = 0; i < 40; i++) {
		begin(`later-${i}`);
		machine.stop(`later-${i}`);
	}
	expect(machine.subject('first')).toBeNull();
	expect(machine.frozen('first')).toMatchObject({ styleId: FRIENDS.id, expression: 4 });
});

test('an expired notice leaves nothing to schedule (no hidden publish loop)', () => {
	const { machine, begin, advance } = setup();
	begin('a');
	machine.stop('a');
	machine.delivered('a', 'pasted', { app: 'Notepad', text: 'hi' });
	expect(machine.nextHideAt()).not.toBeNull();
	advance(6001);
	expect(machine.view()).toBeNull();
	expect(machine.nextHideAt()).toBeNull();
});

test('a notice is sent once more, as hidden, at its own deadline (even if it passed while sending)', () => {
	const { machine, begin, advance } = setup();
	begin('a');
	machine.stop('a');
	machine.delivered('a', 'pasted', { app: 'Notepad', text: 'hi' });
	const view = machine.view()!;
	// The deadline passes while the view is being sent.
	advance(7000);
	expect(machine.nextHideAt()).toBeNull();
	// The schedule comes from the view that was sent, so the hide still goes out.
	expect(republishDelay(view, view.hidesAt! + 1000)).toBe(20);
	expect(republishDelay(view, view.hidesAt! - 500)).toBe(520);
	expect(republishDelay(machine.view(), 0)).toBeNull();
	expect(republishDelay(null, 0)).toBeNull();
});

test('overrides are kept exactly as long as the pinned word lists (100 dictations)', () => {
	const { machine, begin } = setup();
	for (let i = 0; i < 101; i++) {
		begin(`d-${i}`);
		machine.stop(`d-${i}`);
	}
	expect(machine.frozen('d-0')).toBeNull();
	expect(machine.frozen('d-1')).not.toBeNull();
});
