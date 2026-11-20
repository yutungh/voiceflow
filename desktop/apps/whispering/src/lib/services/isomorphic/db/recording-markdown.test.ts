import { describe, expect, test } from 'bun:test';
import { appIdOf } from '../../../privacy/history';
import { shouldRemoveAudio } from '../../../vf/audio-retention';
import { hasProblem, problemsOf, resultOf } from '../../../vf/dictations';
import { parseRecordingMarkdown, recordingToMarkdown } from './recording-markdown';

// A recording file exactly as Whispering 7.11 writes it (made-up text): only
// the base fields, ids and dates quoted, the transcript as the body.
const WHISPERING_FILE = [
	'---',
	"id: '_abcDEF123ghiJKL456mn'",
	"title: ''",
	"subtitle: ''",
	"timestamp: '2025-03-04T15:16:17.181Z'",
	"createdAt: '2025-03-04T15:16:17.181Z'",
	"updatedAt: '2025-03-04T15:16:19.020Z'",
	'transcriptionStatus: DONE',
	'---',
	'Remind me to buy coffee filters on the way home.',
	'',
].join('\r\n');

// The same file after the upgrade kit's import: the audio is gone, so it is
// marked as removed; a dictation Whispering left "transcribing" is failed.
function imported(file: string, importedAt = '2026-11-17T18:00:00.000Z') {
	return file
		.replace('transcriptionStatus: TRANSCRIBING', 'transcriptionStatus: FAILED')
		.replace(/\r?\n---\r?\n/, `\r\naudioRemovedAt: '${importedAt}'\r\n---\r\n`);
}

describe("Whispering's recording files", () => {
	test('read as VoiceFlow recordings, unchanged', () => {
		const r = parseRecordingMarkdown(WHISPERING_FILE);
		expect(r).not.toBeNull();
		expect(r?.id).toBe('_abcDEF123ghiJKL456mn');
		expect(r?.transcriptionStatus).toBe('DONE');
		expect(r?.transcribedText.trim()).toBe(
			'Remind me to buy coffee filters on the way home.',
		);
		// No VoiceFlow fields: no app, no privacy, shown as saved.
		expect(r?.privacy).toBeUndefined();
		expect(appIdOf(r!)).toBeNull();
		expect(resultOf(r!)).toBe('saved');
		expect(hasProblem(r!)).toBe(false);
	});

	test('an unquoted id and an empty body still read', () => {
		const file = WHISPERING_FILE.replace("id: '_abcDEF123ghiJKL456mn'", 'id: 9X-Q2w3E4r5T6y7U8i9O0').replace(
			'Remind me to buy coffee filters on the way home.',
			'',
		);
		const r = parseRecordingMarkdown(file);
		expect(r?.id).toBe('9X-Q2w3E4r5T6y7U8i9O0');
		expect(r?.transcribedText.trim()).toBe('');
	});

	test('imported copies: audio marked removed, never cleaned up or retried', () => {
		const r = parseRecordingMarkdown(imported(WHISPERING_FILE));
		expect(r?.audioRemovedAt).toBe('2026-11-17T18:00:00.000Z');
		expect(shouldRemoveAudio(r!, '7d', Date.parse('2027-01-01'))).toBe(false);
		expect(resultOf(r!)).toBe('saved');

		const stuck = parseRecordingMarkdown(
			imported(WHISPERING_FILE.replace('transcriptionStatus: DONE', 'transcriptionStatus: TRANSCRIBING')),
		);
		expect(stuck?.transcriptionStatus).toBe('FAILED');
		// Shown under History's problem filter, but not as a Home problem to fix.
		expect(resultOf(stuck!)).toBe('no-text');
		expect(problemsOf([stuck!])).toHaveLength(0);
	});

	test('what VoiceFlow writes reads back the same', () => {
		const r = parseRecordingMarkdown(imported(WHISPERING_FILE))!;
		const again = parseRecordingMarkdown(recordingToMarkdown(r));
		expect(again).toEqual(r);
	});

	test('a file with a bad status is skipped, not read wrongly', () => {
		expect(
			parseRecordingMarkdown(WHISPERING_FILE.replace('transcriptionStatus: DONE', 'transcriptionStatus: LOST')),
		).toBeNull();
	});
});
