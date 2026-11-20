import { describe, expect, test } from 'bun:test';
import type { Recording } from '$lib/services/isomorphic/db';
import {
	formatLength,
	formatMinutes,
	hasProblem,
	homeStats,
	problemsOf,
	resultOf,
	wordChanges,
	wordCount,
} from './dictations';

const NOW = new Date(2026, 10, 17, 12, 0, 0); // Tuesday

function rec(daysAgo: number, words: number, extra: Partial<Recording> = {}): Recording {
	const t = new Date(NOW);
	t.setDate(t.getDate() - daysAgo);
	t.setHours(9);
	const text = Array.from({ length: words }, () => 'word').join(' ');
	return {
		id: `r${daysAgo}-${words}-${Math.random()}`,
		title: '',
		subtitle: '',
		timestamp: t.toISOString(),
		createdAt: t.toISOString(),
		updatedAt: t.toISOString(),
		transcribedText: text,
		transcriptionStatus: 'DONE',
		...extra,
	};
}

describe('wordCount', () => {
	test('counts words, not punctuation', () => {
		expect(wordCount("Hey Sam, it's late — ship it!")).toBe(6);
		expect(wordCount('')).toBe(0);
	});
});

describe('homeStats', () => {
	test('compares the last 7 days with the 7 before', () => {
		const stats = homeStats(
			[rec(0, 100), rec(6, 50), rec(7, 100), rec(13, 20), rec(14, 999)],
			NOW,
		);
		expect(stats.wordsThisWeek).toBe(150);
		expect(stats.wordsLastWeek).toBe(120);
		expect(stats.changePct).toBe(25);
		expect(stats.days).toHaveLength(7);
		expect(stats.days[6]?.name).toBe('Today');
		expect(stats.days[6]?.letter).toBe('T');
		expect(stats.days[6]?.words).toBe(100);
		expect(stats.days[0]?.words).toBe(50);
	});

	test('speaking speed and time saved come from timed dictations', () => {
		// 150 words in 60 s = 150 wpm; typing them at 40 wpm takes 3.75 min.
		const stats = homeStats([rec(0, 150, { durationMs: 60_000 })], NOW);
		expect(stats.wpm).toBe(150);
		expect(stats.savedMin).toBeCloseTo(2.75, 5);
	});

	test('leaves out deleted, held and empty dictations', () => {
		const stats = homeStats(
			[
				rec(0, 10, { deletedAt: NOW.toISOString() }),
				rec(0, 10, { privacy: 'held' }),
				rec(0, 0),
			],
			NOW,
		);
		expect(stats.hasData).toBe(false);
		expect(stats.wordsThisWeek).toBe(0);
		expect(stats.wpm).toBeNull();
		expect(stats.changePct).toBeNull();
	});

	test('uses the final text when there is one', () => {
		const stats = homeStats([rec(0, 3, { finalText: 'one two' })], NOW);
		expect(stats.wordsThisWeek).toBe(2);
	});
});

describe('results and problems', () => {
	test('result labels', () => {
		expect(resultOf(rec(0, 1, { privacy: 'held' }))).toBe('held');
		expect(resultOf(rec(0, 1, { transcriptionStatus: 'FAILED' }))).toBe('no-text');
		expect(resultOf(rec(0, 1, { result: 'pasted' }))).toBe('pasted');
		expect(resultOf(rec(0, 1))).toBe('saved');
	});

	test('problems skip dismissed and deleted ones, newest first', () => {
		const hoursAgo = (h: number) => {
			const at = new Date(NOW.getTime() - h * 3_600_000).toISOString();
			return { createdAt: at, timestamp: at };
		};
		const older = rec(0, 1, { result: 'not-pasted', problemKind: 'tidy-up', problem: 'x', ...hoursAgo(5) });
		const newer = rec(0, 1, { result: 'no-text', problemKind: 'transcription', ...hoursAgo(1) });
		const dismissed = rec(0, 1, { result: 'not-pasted', problemKind: 'paste', problemDismissed: true });
		const gone = rec(0, 1, { privacy: 'held', deletedAt: NOW.toISOString() });
		const held = rec(0, 1, { privacy: 'held', ...hoursAgo(20) });
		const problems = problemsOf([older, newer, dismissed, gone, held], NOW.getTime());
		expect(problems.map((p) => p.kind)).toEqual(['transcription', 'tidy-up', 'held']);
		// 0.9.12 (F6): History's badge counts only what he can act on.
		expect(hasProblem(dismissed)).toBe(false);
		expect(hasProblem(older)).toBe(true);
	});
});

describe('wordChanges', () => {
	test('pairs heard words with what was written', () => {
		expect(
			wordChanges(
				'hey phoebe check the pinewud preview on versal',
				'Hey Febe, check the Pinewood preview on Vercel.',
			),
		).toEqual([
			{ heard: 'phoebe', written: 'Febe,' },
			{ heard: 'pinewud', written: 'Pinewood' },
			{ heard: 'versal', written: 'Vercel.' },
		]);
	});

	test('ignores case and punctuation only changes', () => {
		expect(wordChanges('hello there', 'Hello, there.')).toEqual([]);
	});
});

describe('formatting', () => {
	test('lengths and minutes', () => {
		expect(formatLength(9_200)).toBe('0:09');
		expect(formatLength(84_000)).toBe('1:24');
		expect(formatLength(undefined)).toBe('–');
		expect(formatMinutes(112)).toBe('1 h 52 m');
		expect(formatMinutes(14)).toBe('14 m');
	});
});
