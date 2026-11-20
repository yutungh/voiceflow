import { describe, expect, test } from 'bun:test';
import type { Recording } from '$lib/services/isomorphic/db';
import {
	EMPTY_RETRY_KEEP_MS,
	audioFilesById,
	audioToRemove,
	inEmptyRetryWindow,
	needsAudio,
	shouldRemoveAudio,
} from './audio-retention';

const NOW = Date.parse('2026-11-17T12:00:00Z');
const DAY = 24 * 60 * 60 * 1000;

function rec(ageDays: number, extra: Partial<Recording> = {}): Recording {
	const t = new Date(NOW - ageDays * DAY).toISOString();
	return {
		id: `r${ageDays}`,
		title: '',
		subtitle: '',
		timestamp: t,
		createdAt: t,
		updatedAt: t,
		transcribedText: 'hello',
		transcriptionStatus: 'DONE',
		result: 'pasted',
		...extra,
	};
}

describe('needsAudio', () => {
	test('held, failed and untranscribed dictations need their audio', () => {
		expect(needsAudio(rec(1, { privacy: 'held' }))).toBe(true);
		expect(needsAudio(rec(1, { transcriptionStatus: 'FAILED' }))).toBe(true);
		expect(needsAudio(rec(1, { transcriptionStatus: 'UNPROCESSED' }))).toBe(true);
		expect(needsAudio(rec(1, { transcriptionStatus: 'TRANSCRIBING' }))).toBe(true);
		expect(needsAudio(rec(1, { result: 'no-text', problemKind: 'transcription' }))).toBe(true);
	});

	test('0.9.12: "Nothing heard" (no text, no problem) does not, after its Retry minute', () => {
		expect(needsAudio(rec(1, { result: 'no-text' }))).toBe(false);
		// A short tap: gone at once with Keep recordings off.
		const at = new Date(NOW).toISOString();
		const tap = rec(0, { result: 'no-text', durationMs: 1200, createdAt: at, updatedAt: at });
		expect(shouldRemoveAudio(tap, 'off', NOW)).toBe(true);
		// 3 s or more: kept while the bar offers Retry (from the last result), then gone.
		const long = { ...tap, durationMs: 4000 };
		expect(inEmptyRetryWindow(long, NOW + 59_000)).toBe(true);
		expect(shouldRemoveAudio(long, 'off', NOW + 59_000)).toBe(false);
		expect(shouldRemoveAudio(long, 'off', NOW + EMPTY_RETRY_KEEP_MS)).toBe(true);
		const retried = { ...long, updatedAt: new Date(NOW + 50_000).toISOString() };
		expect(shouldRemoveAudio(retried, 'off', NOW + 70_000)).toBe(false);
		// A real failure is not this.
		expect(inEmptyRetryWindow({ ...long, problemKind: 'transcription' }, NOW)).toBe(false);
	});

	test('transcribed dictations do not, even if not pasted', () => {
		expect(needsAudio(rec(1))).toBe(false);
		expect(needsAudio(rec(1, { result: 'not-pasted', problemKind: 'tidy-up' }))).toBe(false);
		expect(needsAudio(rec(1, { result: 'copied' }))).toBe(false);
	});
});

describe('shouldRemoveAudio', () => {
	test('keeps audio younger than the limit, removes older', () => {
		expect(shouldRemoveAudio(rec(6.9), '7d', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(7.1), '7d', NOW)).toBe(true);
		expect(shouldRemoveAudio(rec(0.5), '1d', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(1.5), '1d', NOW)).toBe(true);
		expect(shouldRemoveAudio(rec(29), '30d', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(31), '30d', NOW)).toBe(true);
	});

	test('Off removes audio of any transcribed dictation at once', () => {
		expect(shouldRemoveAudio(rec(0), 'off', NOW)).toBe(true);
	});

	test('Forever never removes', () => {
		expect(shouldRemoveAudio(rec(400), 'forever', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(400, { privacy: 'held' }), 'forever', NOW)).toBe(false);
	});

	test('audio a dictation still needs stays up to 30 days, whatever the setting', () => {
		const held = { privacy: 'held' as const };
		expect(shouldRemoveAudio(rec(0, held), 'off', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(10, held), '7d', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(29, { transcriptionStatus: 'FAILED' }), '1d', NOW)).toBe(false);
		expect(shouldRemoveAudio(rec(31, { transcriptionStatus: 'FAILED' }), '1d', NOW)).toBe(true);
		expect(shouldRemoveAudio(rec(31, held), '7d', NOW)).toBe(true);
	});

	test('never touches audio already removed or a bad date', () => {
		expect(shouldRemoveAudio(rec(100, { audioRemovedAt: 'x' }), '7d', NOW)).toBe(false);
		expect(
			shouldRemoveAudio(rec(100, { createdAt: 'nope', timestamp: 'nope' }), '7d', NOW),
		).toBe(false);
	});
});

describe('audioToRemove', () => {
	test('selects only what goes', () => {
		const list = [
			rec(1),
			rec(8),
			rec(8, { privacy: 'held' }),
			rec(40, { transcriptionStatus: 'FAILED' }),
			rec(9, { audioRemovedAt: '2026-11-10T00:00:00Z' }),
		];
		expect(audioToRemove(list, '7d', NOW).map((r) => r.id)).toEqual(['r8', 'r40']);
	});
});

describe('audioFilesById', () => {
	test('only <id>.<audio> files count; metadata and temp files never do', () => {
		const byId = audioFilesById([
			'a1.wav',
			'a1.md',
			'a1.md.tmp',
			'b2.flac',
			'b2.webm',
			'c3.md',
			'notes.txt',
			'.hidden',
			'd4.WAV',
		]);
		expect(byId.get('a1')).toEqual(['a1.wav']);
		expect(byId.get('b2')).toEqual(['b2.flac', 'b2.webm']);
		expect(byId.has('c3')).toBe(false);
		expect(byId.has('notes')).toBe(false);
		expect(byId.get('d4')).toEqual(['d4.WAV']);
	});
});
