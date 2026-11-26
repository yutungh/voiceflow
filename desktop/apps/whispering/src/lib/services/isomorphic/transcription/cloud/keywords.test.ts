import { describe, expect, test } from 'bun:test';
import OpenAI from 'openai';
import { buildPrompt } from '../../../../profile/prompt';
import { keywordsParam, languageParam, supportsKeywords, transcriptionKeywords } from './keywords';

const snapshot = {
	defaults: { promptCharsEffective: 500, promptTermsEffective: 50 },
	terms: [
		{ text: 'Pinewood', apps: ['*'], priority: 5 },
		{ text: 'Quillon', apps: ['*'], priority: 3 },
		{ text: 'Kite', apps: ['code.exe'], priority: 1 },
		{ text: 'pinewood', apps: ['*'], priority: 1 },
	],
} as never;

describe('keywords[] from the vocabulary (VoiceFlow 0.9.11)', () => {
	test('the same merged words as the prompt, each once, in prompt order', () => {
		const built = buildPrompt(snapshot, 'code.exe');
		expect(built.prompt).toBe('Vocabulary: Quillon, Pinewood, Kite');
		expect(transcriptionKeywords('gpt-transcribe', built.sent)).toEqual(['Quillon', 'Pinewood', 'Kite']);
	});

	test('only for gpt-transcribe models', () => {
		expect(supportsKeywords('gpt-transcribe')).toBe(true);
		expect(supportsKeywords(' GPT-Transcribe-2026-10 ')).toBe(true);
		expect(supportsKeywords('gpt-4o-transcribe')).toBe(false);
		expect(supportsKeywords('whisper-1')).toBe(false);
		expect(transcriptionKeywords('whisper-1', ['Pinewood'])).toEqual([]);
	});

	test('each word once, never its hint; odd words dropped', () => {
		expect(transcriptionKeywords('gpt-transcribe', ['A', '<b>', 'a', ' B ', ''])).toEqual(['A', 'B']);
		expect(keywordsParam('gpt-transcribe', [])).toEqual({});
		const hinted = buildPrompt(
			{
				defaults: { promptCharsEffective: 500, promptTermsEffective: 50 },
				terms: [{ text: 'ZDRL', heardAs: ['Z-drill'], apps: ['*'], priority: 9, layer: 'edit' }],
			} as never,
			null,
		);
		expect(hinted.prompt).toBe('Vocabulary: ZDRL (not "Z-drill")');
		expect(transcriptionKeywords('gpt-transcribe', hinted.sent)).toEqual(['ZDRL']);
		// Words with brackets or commas stay whole.
		expect(transcriptionKeywords('gpt-transcribe', ['ACME (US)', 'Smith, Inc.'])).toEqual(['ACME (US)', 'Smith, Inc.']);
	});

	test('the request carries one keywords[] part per word', async () => {
		let body: FormData | null = null;
		const fakeFetch = (async (_url: unknown, init?: { body?: unknown }) => {
			body = init?.body as FormData;
			return new Response(JSON.stringify({ text: 'ok' }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		}) as unknown as typeof fetch;
		const client = new OpenAI({ apiKey: 'test', baseURL: 'http://localhost.invalid/v1', fetch: fakeFetch });
		const built = buildPrompt(snapshot, 'code.exe');
		const prompt = built.prompt;
		await client.audio.transcriptions.create({
			file: new File([new Uint8Array([1, 2, 3])], 'a.flac', { type: 'audio/flac' }),
			model: 'gpt-transcribe',
			prompt,
			...keywordsParam('gpt-transcribe', built.sent),
		});
		expect(body).toBeInstanceOf(FormData);
		const form = body as unknown as FormData;
		expect(form.getAll('keywords[]')).toEqual(['Quillon', 'Pinewood', 'Kite']);
		expect(form.get('prompt')).toBe(prompt);
	});
});

describe('the language field (VoiceFlow 0.9.15)', () => {
	test('languages[] for gpt-transcribe, the singular language for other models, nothing for Auto', () => {
		expect(languageParam('gpt-transcribe', 'en')).toEqual({ languages: ['en'] });
		expect(languageParam('gpt-transcribe-2026-10', 'ja')).toEqual({ languages: ['ja'] });
		expect(languageParam('gpt-4o-transcribe', 'en')).toEqual({ language: 'en' });
		expect(languageParam('whisper-1', 'en')).toEqual({ language: 'en' });
		expect(languageParam('gpt-transcribe', 'auto')).toEqual({});
		expect(languageParam('whisper-1', 'auto')).toEqual({});
	});

	test('0.9.22: Chinese sends zh-cn and en to gpt-transcribe, as the Android keyboard does', () => {
		expect(languageParam('gpt-transcribe', 'zh')).toEqual({ languages: ['zh-cn', 'en'] });
		expect(languageParam('gpt-transcribe-2026-10', 'zh')).toEqual({ languages: ['zh-cn', 'en'] });
		// Other models keep the singular code.
		expect(languageParam('gpt-4o-transcribe', 'zh')).toEqual({ language: 'zh' });
		expect(languageParam('whisper-1', 'zh')).toEqual({ language: 'zh' });
	});

	test('the request carries one languages[] part and no language', async () => {
		let body: FormData | null = null;
		const fakeFetch = (async (_url: unknown, init?: { body?: unknown }) => {
			body = init?.body as FormData;
			return new Response(JSON.stringify({ text: 'ok' }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		}) as unknown as typeof fetch;
		const client = new OpenAI({ apiKey: 'test', baseURL: 'http://localhost.invalid/v1', fetch: fakeFetch });
		await client.audio.transcriptions.create({
			file: new File([new Uint8Array([1, 2, 3])], 'a.flac', { type: 'audio/flac' }),
			model: 'gpt-transcribe',
			...languageParam('gpt-transcribe', 'en'),
		});
		const form = body as unknown as FormData;
		expect(form.getAll('languages[]')).toEqual(['en']);
		expect(form.has('language')).toBe(false);
		expect(form.has('languages')).toBe(false);
	});
});
