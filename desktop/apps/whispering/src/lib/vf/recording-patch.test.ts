import { describe, expect, test } from 'bun:test';
import type { Recording } from '$lib/services/isomorphic/db';
import { applyRecordingPatch } from './recording-patch';

const base: Recording = {
	id: 'r1',
	title: '',
	subtitle: '',
	timestamp: '2026-11-17T10:00:00.000Z',
	createdAt: '2026-11-17T10:00:00.000Z',
	updatedAt: '2026-11-17T10:00:00.000Z',
	transcribedText: '',
	transcriptionStatus: 'TRANSCRIBING',
};

describe('Changing a dictation field by field', () => {
	test('a dictation deleted while it was transcribed stays deleted', () => {
		// History deleted it while the upload ran...
		const stored = applyRecordingPatch(base, { deletedAt: '2026-11-17T10:00:05.000Z' });
		// ...then transcription finished and saved its text.
		const next = applyRecordingPatch(stored, {
			transcribedText: 'hello there',
			transcriptionStatus: 'DONE',
		});
		expect(next.deletedAt).toBe('2026-11-17T10:00:05.000Z');
		expect(next.transcribedText).toBe('hello there');
		expect(next.transcriptionStatus).toBe('DONE');
	});

	test('other fields set meanwhile are kept', () => {
		const stored = { ...base, result: 'not-pasted' as const, finalText: 'x', styleName: 'Casual' };
		const next = applyRecordingPatch(stored, { transcriptionStatus: 'FAILED' });
		expect(next).toMatchObject({ result: 'not-pasted', finalText: 'x', styleName: 'Casual' });
	});

	test('undefined removes a field; the id never changes', () => {
		const stored = { ...base, deletedAt: 'then', problem: 'p' };
		const next = applyRecordingPatch(stored, { deletedAt: undefined, id: 'other' });
		expect('deletedAt' in next).toBe(false);
		expect(next.problem).toBe('p');
		expect(next.id).toBe('r1');
	});
});
