import { describe, expect, test } from 'bun:test';
import { responseFromFrame, usesOpenAIOrigin } from './openai-pool';

function frame(status: number, headers: [string, string][], body: string): ArrayBuffer {
	const headerBytes = new TextEncoder().encode(JSON.stringify(headers));
	const bodyBytes = new TextEncoder().encode(body);
	const out = new Uint8Array(6 + headerBytes.length + bodyBytes.length);
	const view = new DataView(out.buffer);
	view.setUint16(0, status, true);
	view.setUint32(2, headerBytes.length, true);
	out.set(headerBytes, 6);
	out.set(bodyBytes, 6 + headerBytes.length);
	return out.buffer;
}

describe('OpenAI connection pool', () => {
	test('only the default OpenAI endpoint uses the pool', () => {
		expect(usesOpenAIOrigin(undefined)).toBe(true);
		expect(usesOpenAIOrigin(null)).toBe(true);
		expect(usesOpenAIOrigin('')).toBe(true);
		expect(usesOpenAIOrigin('https://api.openai.com/v1')).toBe(true);
		expect(usesOpenAIOrigin('https://openrouter.ai/api/v1')).toBe(false);
		expect(usesOpenAIOrigin('http://localhost:8000/v1')).toBe(false);
		expect(usesOpenAIOrigin('https://api.openai.com.evil.test/v1')).toBe(false);
		expect(usesOpenAIOrigin('not a url')).toBe(false);
	});

	test('a framed reply becomes a Response the SDK can read', async () => {
		const response = responseFromFrame(
			frame(
				401,
				[
					['content-type', 'application/json'],
					['content-length', '999'],
					['x-request-id', 'req_1'],
				],
				'{"error":{"message":"bad key"}}',
			),
		);
		expect(response.status).toBe(401);
		expect(response.headers.get('content-type')).toBe('application/json');
		// The body is complete: a stale length must not cut it.
		expect(response.headers.get('content-length')).toBeNull();
		expect(response.headers.get('x-request-id')).toBe('req_1');
		expect(await response.json()).toEqual({ error: { message: 'bad key' } });
	});

	test('a no-body status gives an empty Response, not an error', () => {
		const response = responseFromFrame(frame(204, [], ''));
		expect(response.status).toBe(204);
		expect(response.body).toBeNull();
	});

	test('a truncated reply is a network error (TypeError)', () => {
		expect(() => responseFromFrame(new ArrayBuffer(3))).toThrow(TypeError);
		const bad = new Uint8Array(8);
		new DataView(bad.buffer).setUint32(2, 100, true);
		expect(() => responseFromFrame(bad.buffer)).toThrow(TypeError);
	});
});
