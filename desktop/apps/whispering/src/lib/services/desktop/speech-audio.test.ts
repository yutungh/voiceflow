import { describe, expect, test } from 'bun:test';
import { unframeParts } from './speech-audio';

function frame(parts: number[][]): ArrayBuffer {
	const bytes: number[] = [];
	const u32 = (n: number) =>
		bytes.push(n & 255, (n >> 8) & 255, (n >> 16) & 255, n >>> 24);
	u32(parts.length);
	for (const part of parts) {
		u32(part.length);
		bytes.push(...part);
	}
	return new Uint8Array(bytes).buffer;
}

describe('unframeParts', () => {
	test('reads parts in order', () => {
		const parts = unframeParts(frame([[1, 2], [], [3]]));
		expect(parts.map((p) => Array.from(p))).toEqual([[1, 2], [], [3]]);
	});

	test('matches the Rust framing byte for byte', () => {
		// speech_audio::tests::frame_parts_layout
		const rust = [3, 0, 0, 0, 2, 0, 0, 0, 1, 2, 0, 0, 0, 0, 1, 0, 0, 0, 3];
		const parts = unframeParts(new Uint8Array(rust).buffer);
		expect(parts.map((p) => Array.from(p))).toEqual([[1, 2], [], [3]]);
	});

	test('refuses empty, zero-part and truncated replies', () => {
		expect(() => unframeParts(new ArrayBuffer(0))).toThrow();
		expect(() => unframeParts(frame([]))).toThrow();
		const truncated = frame([[1, 2, 3]]).slice(0, 9);
		expect(() => unframeParts(truncated)).toThrow();
	});
});
