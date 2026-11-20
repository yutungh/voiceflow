import { describe, expect, test } from 'bun:test';
import {
	countWords,
	createDictationTiming,
	parseRecords,
	percentile,
	SEGMENTS,
	segmentDuration,
	summarize,
	type TimingRecord,
} from './dictation-timing';

function fakeClock() {
	let t = 1000;
	return {
		clock: { now: () => t, isoNow: () => '2026-11-17T12:00:00.000Z' },
		advance: (ms: number) => {
			t += ms;
		},
	};
}

function setup() {
	const { clock, advance } = fakeClock();
	const written: TimingRecord[] = [];
	const timing = createDictationTiming({ clock, write: (r) => written.push(r) });
	return { timing, advance, written };
}

describe('dictation timing', () => {
	test('records every stage as ms since the hotkey', () => {
		const { timing, advance, written } = setup();
		timing.hotkey();
		advance(5);
		timing.mark('target');
		advance(120);
		timing.mark('mic-ready');
		advance(10_000);
		timing.mark('stop');
		advance(80);
		timing.bind('rec1', { audioBytes: 1234 });
		advance(20);
		timing.markRecording('rec1', 'db-saved');
		timing.markRecording('rec1', 'transcribe-start');
		advance(30);
		timing.uploadStarting({ bytes: 500, model: 'gpt-transcribe' });
		advance(1300);
		timing.markRecording('rec1', 'transcribed');
		advance(10);
		timing.markRecording('rec1', 'transcript-saved');
		timing.markRecording('rec1', 'tidy-start');
		advance(900);
		timing.markRecording('rec1', 'tidy-done');
		advance(40);
		timing.markRecording('rec1', 'paste-checked');
		advance(160);
		timing.markRecording('rec1', 'paste-done');
		timing.finish('rec1', { words: 42 });

		expect(written).toHaveLength(1);
		const [r] = written;
		expect(r?.outcome).toBe('pasted');
		expect(r?.marks).toEqual({
			hotkey: 0,
			target: 5,
			'mic-ready': 125,
			stop: 10_125,
			'audio-saved': 10_205,
			'db-saved': 10_225,
			'transcribe-start': 10_225,
			'upload-start': 10_255,
			transcribed: 11_555,
			'transcript-saved': 11_565,
			'tidy-start': 11_565,
			'tidy-done': 12_465,
			'paste-checked': 12_505,
			'paste-done': 12_665,
		});
		expect(r?.audioBytes).toBe(1234);
		expect(r?.uploadBytes).toBe(500);
		expect(r?.uploads).toBe(1);
		expect(r?.model).toBe('gpt-transcribe');
		expect(r?.words).toBe(42);
		expect(timing.inFlight()).toEqual({ open: false, recordings: 0 });
	});

	test('keeps the first time a stage is reached', () => {
		const { timing, advance, written } = setup();
		timing.hotkey();
		advance(10);
		timing.mark('target');
		advance(10);
		timing.mark('target');
		timing.mark('stop');
		timing.bind('r');
		timing.finish('r');
		expect(written[0]?.marks.target).toBe(10);
		expect(written[0]?.marks.stop).toBe(20);
	});

	test('a new hotkey replaces a trace that never reached stop', () => {
		const { timing, advance, written } = setup();
		timing.hotkey();
		advance(50);
		timing.hotkey();
		advance(7);
		timing.mark('target');
		timing.mark('stop');
		timing.bind('r2');
		timing.finish('r2', { outcome: 'held' });
		expect(written[0]?.marks.target).toBe(7);
		expect(written[0]?.outcome).toBe('held');
	});

	test('marks without a trace do nothing', () => {
		const { timing, written } = setup();
		timing.mark('target');
		timing.bind('x');
		timing.markRecording('unknown', 'tidy-start');
		timing.uploadStarting({ bytes: 1 });
		timing.finish('unknown');
		expect(written).toHaveLength(0);
	});

	test('uploads go to the oldest transcribing trace; split parts add bytes', () => {
		const { timing, advance, written } = setup();
		for (const id of ['a', 'b']) {
			timing.hotkey();
			timing.mark('stop');
			timing.bind(id);
			timing.markRecording(id, 'transcribe-start');
			advance(10);
		}
		timing.uploadStarting({ bytes: 100 });
		timing.uploadStarting({ bytes: 200 });
		timing.markRecording('a', 'transcribed');
		timing.markRecording('b', 'transcribed');
		timing.finish('a');
		timing.finish('b');
		expect(written.map((r) => r.uploadBytes)).toEqual([100, 200]);

		timing.hotkey();
		timing.mark('stop');
		timing.bind('c');
		timing.markRecording('c', 'transcribe-start');
		timing.uploadStarting({ bytes: 10 });
		timing.uploadStarting({ bytes: 20 });
		timing.finish('c', { outcome: 'transcription-failed' });
		expect(written[2]?.uploadBytes).toBe(30);
		expect(written[2]?.uploads).toBe(2);
		expect(written[2]?.outcome).toBe('transcription-failed');
	});

	test('a trace without a paste is not-pasted', () => {
		const { timing, written } = setup();
		timing.hotkey();
		timing.mark('stop');
		timing.bind('n');
		timing.finish('n');
		expect(written[0]?.outcome).toBe('not-pasted');
	});
});

describe('summaries', () => {
	const record = (marks: TimingRecord['marks']): TimingRecord => ({
		v: 1,
		at: '2026-11-17T12:00:00.000Z',
		outcome: 'pasted',
		marks,
	});

	test('percentile is nearest-rank', () => {
		expect(percentile([], 50)).toBeNull();
		expect(percentile([5], 90)).toBe(5);
		expect(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 50)).toBe(5);
		expect(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 90)).toBe(9);
		expect(percentile([10, 1, 3], 50)).toBe(3);
	});

	test('paste is timed from tidy-up when there was one, else from the saved transcript', () => {
		const segment = SEGMENTS.find((s) => s.id === 'paste-check');
		if (!segment) throw new Error('missing segment');
		expect(
			segmentDuration(record({ 'transcript-saved': 100, 'tidy-done': 900, 'paste-checked': 950 }), segment),
		).toBe(50);
		expect(segmentDuration(record({ 'transcript-saved': 100, 'paste-checked': 130 }), segment)).toBe(30);
		expect(segmentDuration(record({ 'paste-checked': 130 }), segment)).toBeNull();
	});

	test('summarize uses the last N records and skips missing spans', () => {
		const records = Array.from({ length: 60 }, (_, i) =>
			record({ stop: 0, 'paste-done': i < 10 ? 99_999 : 1000 + i }),
		);
		records.push(record({ hotkey: 0, target: 4 }));
		const stats = summarize(records, 50);
		const total = stats.find((s) => s.id === 'release-to-paste');
		expect(total?.n).toBe(49);
		expect(total?.p50).toBe(1035);
		const target = stats.find((s) => s.id === 'target');
		expect(target).toMatchObject({ n: 1, p50: 4, p90: 4 });
		expect(stats.find((s) => s.id === 'corrections')?.n).toBe(0);
	});

	test('parseRecords skips blank, torn and foreign lines', () => {
		const good = JSON.stringify(record({ stop: 0, 'paste-done': 10 }));
		const parsed = parseRecords(`${good}\n\n{"v":2,"marks":{}}\nnot json\n${good}\n{"v":1,"mar`);
		expect(parsed).toHaveLength(2);
	});

	test('countWords counts words only', () => {
		expect(countWords('')).toBe(0);
		expect(countWords(null)).toBe(0);
		expect(countWords('  one two\nthree ')).toBe(3);
	});
});

test('a feedback recording leaves no timing record and does not steal the next one', () => {
	const { timing, advance, written } = setup();
	timing.hotkey();
	timing.mark('stop');
	timing.discardOpen();
	timing.bind('feedback-1');
	timing.finish('feedback-1');
	expect(written).toHaveLength(0);
	expect(timing.inFlight()).toEqual({ open: false, recordings: 0 });
	timing.hotkey();
	advance(10);
	timing.mark('stop');
	timing.bind('dictation-1');
	timing.finish('dictation-1');
	expect(written).toHaveLength(1);
});
