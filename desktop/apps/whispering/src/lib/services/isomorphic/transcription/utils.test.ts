import { describe, expect, test } from 'bun:test';
import { getAudioExtension } from './utils';

describe('getAudioExtension', () => {
	test('FLAC uploads are named .flac (A0 sends audio/flac)', () => {
		expect(getAudioExtension('audio/flac')).toBe('flac');
		expect(getAudioExtension('audio/x-flac')).toBe('flac');
		expect(getAudioExtension('audio/FLAC; codecs=flac')).toBe('flac');
	});

	test('recorder and upload types keep their extensions', () => {
		expect(getAudioExtension('audio/wav')).toBe('wav');
		expect(getAudioExtension('audio/webm')).toBe('webm');
		expect(getAudioExtension('audio/ogg')).toBe('ogg');
		expect(getAudioExtension('audio/mpeg')).toBe('mpga');
	});

	test('unknown types fall back to mp3', () => {
		expect(getAudioExtension('')).toBe('mp3');
		expect(getAudioExtension('application/octet-stream')).not.toBe('flac');
	});
});
