/**
 * VoiceFlow 0.9.17: "Model for finding fixes". The default for each key
 * combination, a choice that is kept (per device, never in the word lists),
 * the no-key state, routing to each provider (fetch mocked), the Anthropic
 * structured-output answer, and the line under Find fixes. Invented words only.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type } from 'arktype';
import { FIX_RESPONSE_SCHEMA_V2 } from './contract';
import { runFix } from './engine';
import type { DictationEvidence } from './evidence';
import {
	AUTOMATIC,
	defaultChoice,
	FEEDBACK_PROVIDERS,
	FIX_MODELS,
	findFixesLine,
	FIX_MODEL_SETTING,
	fixModelFromSettings,
	fixModelView,
	keyNeeded,
	resolveFixModel,
	storedChoice,
	type FixModelSettings,
} from './fix-model';
import { anthropicAnswer, anthropicRequest, completerFor, FIX_DEADLINE_MS, openAIRequest } from './providers';

const OPENAI = 'sk-test-openai';
const ANTHROPIC = 'sk-ant-test';

const values = (over: Partial<FixModelSettings> = {}): FixModelSettings => ({
	'feedback.fixModel': null,
	'feedback.provider': 'OpenAI',
	'feedback.model': 'gpt-5.4-mini',
	'apiKeys.openai': '',
	'apiKeys.anthropic': '',
	...over,
});

describe('default when nothing is chosen', () => {
	test('both keys: Claude Opus 5.5 (Anthropic comes first)', () => {
		const r = fixModelFromSettings(values({ 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }));
		expect(r).toEqual({ ok: true, choice: { provider: 'Anthropic', model: 'claude-opus-5-5' }, chosen: false });
	});
	test('OpenAI key only: GPT-5.4, the full model', () => {
		const r = fixModelFromSettings(values({ 'apiKeys.openai': OPENAI }));
		expect(r).toEqual({ ok: true, choice: { provider: 'OpenAI', model: 'gpt-5.4' }, chosen: false });
	});
	test('Anthropic key only: Claude Opus 5.5', () => {
		const r = fixModelFromSettings(values({ 'apiKeys.anthropic': ANTHROPIC }));
		expect(r).toEqual({ ok: true, choice: { provider: 'Anthropic', model: 'claude-opus-5-5' }, chosen: false });
	});
	test('0.9.16’s stored default (OpenAI gpt-5.4-mini, not a pick) moves to Opus 5.5 once an Anthropic key is added', () => {
		const old = values({ 'feedback.provider': 'OpenAI', 'feedback.model': 'gpt-5.4-mini', 'apiKeys.openai': OPENAI });
		expect(fixModelFromSettings(old)).toMatchObject({ choice: { provider: 'OpenAI', model: 'gpt-5.4' } });
		expect(fixModelFromSettings({ ...old, 'apiKeys.anthropic': ANTHROPIC })).toMatchObject({
			chosen: false,
			choice: { provider: 'Anthropic', model: 'claude-opus-5-5' },
		});
	});
	test('the picker lists Claude Opus 5.5 first', () => {
		expect(FEEDBACK_PROVIDERS[0]).toBe('Anthropic');
		expect(FIX_MODELS[0]).toMatchObject({ provider: 'Anthropic', model: 'claude-opus-5-5' });
		expect(FIX_MODELS.filter((m) => m.provider === 'OpenAI')[0]!.model).toBe('gpt-5.4');
	});
	test('no key: nothing can be used', () => {
		expect(fixModelFromSettings(values())).toEqual({ ok: false, reason: 'no-key' });
		expect(defaultChoice({ OpenAI: false, Anthropic: false })).toBeNull();
	});
	test('a blank key counts as no key', () => {
		expect(fixModelFromSettings(values({ 'apiKeys.anthropic': '   ' }))).toEqual({ ok: false, reason: 'no-key' });
	});
});

describe('a chosen model', () => {
	test('is used when its key is there, even with the other key too', () => {
		const r = fixModelFromSettings(
			values({ 'feedback.fixModel': 'Anthropic|claude-opus-5-5', 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }),
		);
		expect(r).toEqual({ ok: true, choice: { provider: 'Anthropic', model: 'claude-opus-5-5' }, chosen: true });
	});
	test('without its key waits for the key: it never goes to another provider', () => {
		const r = fixModelFromSettings(values({ 'feedback.fixModel': 'Anthropic|claude-sonnet-5-5', 'apiKeys.openai': OPENAI }));
		expect(r).toEqual({ ok: false, reason: 'chosen-needs-key', choice: { provider: 'Anthropic', model: 'claude-sonnet-5-5' } });
	});
	test('0.9.16’s stored default is not a choice; a different 0.9.16 pick is', () => {
		expect(storedChoice({ fixModel: null, legacyProvider: 'OpenAI', legacyModel: 'gpt-5.4-mini' })).toBeNull();
		expect(storedChoice({ fixModel: null, legacyProvider: 'Anthropic', legacyModel: 'claude-opus-5-5' })).toEqual({
			provider: 'Anthropic',
			model: 'claude-opus-5-5',
		});
		expect(storedChoice({ fixModel: null, legacyProvider: 'OpenAI', legacyModel: 'gpt-5.4' })).toEqual({
			provider: 'OpenAI',
			model: 'gpt-5.4',
		});
		// A 0.9.17 choice wins over the old fields.
		expect(storedChoice({ fixModel: 'OpenAI|gpt-5.4-mini', legacyProvider: 'Anthropic', legacyModel: 'x' })).toEqual({
			provider: 'OpenAI',
			model: 'gpt-5.4-mini',
		});
	});
	test('"Automatic" is no choice, and drops an earlier 0.9.16 pick', () => {
		expect(storedChoice({ fixModel: AUTOMATIC, legacyProvider: 'Anthropic', legacyModel: 'claude-opus-5-5' })).toBeNull();
		const r = fixModelFromSettings(
			values({ 'feedback.fixModel': AUTOMATIC, 'feedback.provider': 'Anthropic', 'feedback.model': 'claude-opus-5-5', 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }),
		);
		expect(r).toEqual({ ok: true, choice: { provider: 'Anthropic', model: 'claude-opus-5-5' }, chosen: false });
		expect(read('../vf/settings/FeedbackCard.svelte')).toContain("settings.updateKey('feedback.fixModel', AUTOMATIC)");
	});
	test('picking a model, even the one Automatic shows, stores it: adding a key later doesn’t move it', () => {
		const picked = values({ 'feedback.fixModel': 'OpenAI|gpt-5.4', 'apiKeys.openai': OPENAI });
		expect(fixModelFromSettings(picked)).toMatchObject({ ok: true, chosen: true, choice: { provider: 'OpenAI' } });
		expect(fixModelFromSettings({ ...picked, 'apiKeys.anthropic': ANTHROPIC })).toMatchObject({ ok: true, choice: { provider: 'OpenAI' } });
		// Automatic with only an OpenAI key moves to Claude Opus 5.5 once an Anthropic key is added.
		const auto = values({ 'feedback.fixModel': AUTOMATIC, 'apiKeys.openai': OPENAI });
		expect(fixModelFromSettings(auto)).toMatchObject({ choice: { provider: 'OpenAI', model: 'gpt-5.4' } });
		expect(fixModelFromSettings({ ...auto, 'apiKeys.anthropic': ANTHROPIC })).toMatchObject({
			choice: { provider: 'Anthropic', model: 'claude-opus-5-5' },
		});
	});
	test('an unreadable stored value falls back to the default', () => {
		expect(storedChoice({ fixModel: 'Mystery|m1' })).toBeNull();
		expect(resolveFixModel(storedChoice({ fixModel: 'nonsense' }), { OpenAI: true, Anthropic: false })).toEqual({
			ok: true,
			choice: { provider: 'OpenAI', model: 'gpt-5.4' },
			chosen: false,
		});
	});
});

/**
 * The setting as `parseStoredSettings` reads it: the schema fills a missing
 * key with its default and keeps a valid stored value. settings.ts imports
 * `$lib` modules that `bun test` can't resolve, so its piece is tested here.
 */
const FixModelSchema = type(FIX_MODEL_SETTING);
function parseSetting(stored: Record<string, unknown>) {
	const result = FixModelSchema(stored);
	if (result instanceof type.errors) throw new Error(result.summary);
	return result;
}
const read = (path: string) => readFileSync(join(import.meta.dir, path), 'utf8').replace(/\r\n/g, '\n');

describe('the setting persists, on this device only', () => {
	test('settings.ts uses this schema', () => {
		expect(read('../settings/settings.ts')).toContain('...FIX_MODEL_SETTING,');
	});
	test('new installs start with nothing chosen; older settings without it read as unset', () => {
		expect(parseSetting({})['feedback.fixModel']).toBeNull();
	});
	test('a stored choice survives a reload (saved as JSON, read back)', () => {
		const saved = JSON.parse(JSON.stringify(parseSetting({ 'feedback.fixModel': 'Anthropic|claude-sonnet-5-5' })));
		expect(parseSetting(saved)['feedback.fixModel']).toBe('Anthropic|claude-sonnet-5-5');
		expect(fixModelFromSettings(values({ ...saved, 'apiKeys.anthropic': ANTHROPIC }))).toMatchObject({
			ok: true,
			chosen: true,
			choice: { provider: 'Anthropic', model: 'claude-sonnet-5-5' },
		});
	});
	test('the picker writes it to the desktop settings, not the word lists', () => {
		const card = read('../vf/settings/FeedbackCard.svelte');
		expect(card).toContain("settings.updateKey('feedback.fixModel', choiceKey(choice))");
		expect(card).not.toMatch(/profile|op-store|saveOp/);
	});
	test('it is not a word-list (NAS profile) field', () => {
		const owned = read('../profile/ownership.ts');
		const list = owned.slice(owned.indexOf('PROFILE_OWNED_KEYS'), owned.indexOf('] as const', owned.indexOf('PROFILE_OWNED_KEYS')));
		expect(list).not.toContain('feedback.');
		for (const v of ['v1', 'v2'])
			expect(read(`../../../../../../pack/schema/voiceflow-profile.${v}.schema.json`)).not.toContain('fixModel');
	});
});

describe('the Anthropic key is stored like the OpenAI key', () => {
	test('same setting path into the OS credential store, same store label', async () => {
		const { API_KEY_PROVIDERS, secretStoreLabel } = await import('../services/desktop/secrets');
		expect(API_KEY_PROVIDERS['apiKeys.anthropic']).toBe('anthropic');
		expect(secretStoreLabel('windows')).toBe('Windows Credential Manager');
		const card = read('../vf/settings/FeedbackCard.svelte');
		const page = read('../../routes/(app)/(config)/settings/+page.svelte');
		const label = "settings.apiKeyStoreKind === 'loading' ? 'the credential store' : secretStoreLabel(settings.apiKeyStoreKind)";
		expect(card).toContain(label);
		expect(page).toContain(label);
		expect(card).toContain("settings.updateKey('apiKeys.anthropic', newKey.trim())");
		expect(page).toContain("settings.updateKey('apiKeys.openai', newKey.trim())");
		// The Rust side accepts this provider name (entry api-key-anthropic).
		expect(read('../../../src-tauri/src/secrets.rs')).toMatch(/PROVIDERS: &\[&str\] = &\[[^\]]*"anthropic"/);
	});
});

describe('no key', () => {
	test('names where each key goes', () => {
		const none = keyNeeded({ ok: false, reason: 'no-key' });
		expect(none.message).toContain('Settings > Transcription');
		expect(none.message).toContain('Settings > Feedback');
		expect(none.section).toBe('feedback');
	});
	test('a chosen model without its key points at that key', () => {
		const r = keyNeeded({ ok: false, reason: 'chosen-needs-key', choice: { provider: 'OpenAI', model: 'gpt-5.4' } });
		expect(r.section).toBe('transcription');
		expect(r.message).toContain('OpenAI (GPT-5.4)');
		const a = keyNeeded({ ok: false, reason: 'chosen-needs-key', choice: { provider: 'Anthropic', model: 'claude-sonnet-5-5' } });
		expect(a.section).toBe('feedback');
		expect(a.message).toContain('Anthropic API key');
	});
	test('the window gets no disclosure and is not ready', () => {
		const view = fixModelView(fixModelFromSettings(values()), () => 'unused');
		expect(view.ready).toBe(false);
		expect(view.disclosure).toBe('');
		expect(view.provider).toBe('');
		expect(view.keyNeeded?.section).toBe('feedback');
	});
});

describe('the line under Find fixes', () => {
	test('names the provider and model actually chosen', () => {
		expect(findFixesLine({ provider: 'Anthropic', model: 'claude-sonnet-5-5' })).toBe(
			"Sends this dictation's text and your note to Anthropic (Claude Sonnet 5.5) with your own API key, to suggest fixes to your spellings and styles. Nothing is sent to VoiceFlow.",
		);
		const view = fixModelView(
			fixModelFromSettings(values({ 'feedback.fixModel': 'Anthropic|claude-opus-5-5', 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC })),
			(p, name) => `details for ${p} ${name}`,
		);
		expect(view.disclosure).toContain('to Anthropic (Claude Opus 5.5)');
		expect(view.disclosure).not.toContain('OpenAI');
		expect(view.details).toBe('details for Anthropic Claude Opus 5.5');
		expect(view).toMatchObject({ provider: 'Anthropic', model: 'claude-opus-5-5', modelName: 'Claude Opus 5.5', ready: true });
	});
	test('with only an OpenAI key it names OpenAI', () => {
		const view = fixModelView(fixModelFromSettings(values({ 'apiKeys.openai': OPENAI })), () => '');
		expect(view.disclosure).toContain('to OpenAI (GPT-5.4)');
		expect(view.model).toBe('gpt-5.4');
	});
	test('a model not in the list is named by its id', () => {
		expect(findFixesLine({ provider: 'OpenAI', model: 'gpt-9-test' })).toContain('to OpenAI (gpt-9-test)');
	});
});

// ---------------------------------------------------------------------------
// Routing (fetch mocked)
// ---------------------------------------------------------------------------

type Seen = { url: string; body: Record<string, unknown>; headers: Headers };

function mockFetch(reply: (seen: Seen) => unknown, status = 200) {
	const seen: Seen[] = [];
	const fn = (async (input: string | URL | Request, init?: RequestInit) => {
		const request = new Request(input, init);
		const text = await request.text();
		const entry = { url: request.url, body: text ? JSON.parse(text) : {}, headers: request.headers };
		seen.push(entry);
		return new Response(JSON.stringify(reply(entry)), { status, headers: { 'content-type': 'application/json' } });
	}) as unknown as typeof fetch;
	return { fn, seen };
}

const anthropicMessage = (text: string, stop_reason = 'end_turn') => ({
	id: 'msg_test',
	type: 'message',
	role: 'assistant',
	model: 'claude-sonnet-5-5',
	content: [{ type: 'text', text }],
	stop_reason,
	stop_sequence: null,
	usage: { input_tokens: 1, output_tokens: 1 },
});

const openAIChat = (content: string) => ({
	id: 'chatcmpl-test',
	object: 'chat.completion',
	created: 0,
	model: 'gpt-5.4-mini',
	choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content, refusal: null } }],
});

const ANSWER = {
	explanation: 'Zibbo came out as Zebbo.',
	corrected_text: 'Ask Zibbo to bring the deck.',
	issues: [{ layer: 'uncertain', summary: 'Not sure what went wrong.', proposals: [] }],
};

function evidence(): DictationEvidence {
	return {
		schema: 'voiceflow-dictation-evidence',
		version: 1,
		recordingId: 'rec1',
		updatedAt: '2026-11-21T00:00:00Z',
		appName: 'Kite',
		appId: 'kite.exe',
		rawText: 'ask zebbo to bring the deck',
		tidyOutput: 'Ask Zebbo to bring the deck.',
		deliveredText: 'Ask Zebbo to bring the deck.',
		corrections: [],
		styleId: 'raw',
		styleName: 'Raw',
		expression: 3,
		outcome: 'pasted',
	};
}

/** The whole round as the service runs it: settings -> provider -> call -> parsed fix. */
async function roundWith(settingsValues: FixModelSettings, fetchFn: typeof fetch) {
	const resolved = fixModelFromSettings(settingsValues);
	if (!resolved.ok) throw new Error('not ready');
	const { provider, model } = resolved.choice;
	const apiKey = provider === 'Anthropic' ? settingsValues['apiKeys.anthropic']! : settingsValues['apiKeys.openai']!;
	return runFix({
		evidence: evidence(),
		feedback: 'It wrote Zebbo, it is Zibbo.',
		review: { snapshot: null, active: [], history: [], revision: 'local:' },
		provider: { apiKey, model, fetch: fetchFn },
		complete: completerFor(provider),
	});
}

describe('routing to the chosen provider', () => {
	test('Anthropic chosen: one call to Anthropic’s Messages API with structured output, none to OpenAI', async () => {
		const { fn, seen } = mockFetch(() => anthropicMessage(JSON.stringify(ANSWER)));
		const outcome = await roundWith(
			values({ 'feedback.fixModel': 'Anthropic|claude-sonnet-5-5', 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }),
			fn,
		);
		expect(outcome.ok).toBe(true);
		expect(seen).toHaveLength(1);
		expect(new URL(seen[0]!.url).origin).toBe('https://api.anthropic.com');
		expect(new URL(seen[0]!.url).pathname).toBe('/v1/messages');
		expect(seen[0]!.headers.get('x-api-key')).toBe(ANTHROPIC);
		expect(seen[0]!.body.model).toBe('claude-sonnet-5-5');
		const config = seen[0]!.body.output_config as { format: { type: string; schema: Record<string, unknown> } };
		expect(config.format.type).toBe('json_schema');
		expect(config.format.schema.required).toEqual(['explanation', 'corrected_text', 'issues']);
		expect(seen[0]!.body.thinking).toBeUndefined();
		// No beta features: the named model answers, nothing reroutes it.
		expect(seen[0]!.body.fallbacks).toBeUndefined();
		expect(seen[0]!.body.betas).toBeUndefined();
		expect(seen[0]!.headers.get('anthropic-beta')).toBeNull();
		expect(new URL(seen[0]!.url).search).toBe('');
		expect(seen[0]!.body.temperature).toBeUndefined();
		if (outcome.ok) expect(outcome.result.correctedText).toBe('Ask Zibbo to bring the deck.');
	});

	test('nothing chosen with only an Anthropic key: Anthropic, Claude Opus 5.5 at high effort', async () => {
		const { fn, seen } = mockFetch(() => anthropicMessage(JSON.stringify(ANSWER)));
		const outcome = await roundWith(values({ 'apiKeys.anthropic': ANTHROPIC }), fn);
		expect(outcome.ok).toBe(true);
		expect(new URL(seen[0]!.url).origin).toBe('https://api.anthropic.com');
		expect(seen[0]!.body.model).toBe('claude-opus-5-5');
		expect((seen[0]!.body.output_config as { effort: string }).effort).toBe('high');
		// Thinking is left to the model: never disabled, never a budget, no sampling parameters.
		const raw = JSON.stringify(seen[0]!.body);
		expect(seen[0]!.body.thinking).toBeUndefined();
		expect(raw).not.toContain('budget_tokens');
		expect(raw).not.toContain('"disabled"');
		for (const p of ['temperature', 'top_p', 'top_k']) expect(seen[0]!.body[p]).toBeUndefined();
	});

	test('OpenAI chosen: one call to OpenAI chat completions with a strict JSON schema, none to Anthropic', async () => {
		const { fn, seen } = mockFetch(() => openAIChat(JSON.stringify(ANSWER)));
		const outcome = await roundWith(
			values({ 'feedback.fixModel': 'OpenAI|gpt-5.4', 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }),
			fn,
		);
		expect(outcome.ok).toBe(true);
		expect(seen).toHaveLength(1);
		expect(new URL(seen[0]!.url).origin).toBe('https://api.openai.com');
		expect(new URL(seen[0]!.url).pathname).toBe('/v1/chat/completions');
		expect(seen[0]!.headers.get('authorization')).toBe(`Bearer ${OPENAI}`);
		expect(seen[0]!.body.model).toBe('gpt-5.4');
		const format = seen[0]!.body.response_format as { type: string; json_schema: { strict: boolean; schema: unknown } };
		expect(format.type).toBe('json_schema');
		expect(format.json_schema.strict).toBe(true);
		expect(format.json_schema.schema).toEqual(JSON.parse(JSON.stringify(FIX_RESPONSE_SCHEMA_V2)));
	});

	test('both keys, nothing chosen: Anthropic, Claude Opus 5.5', async () => {
		const { fn, seen } = mockFetch(() => anthropicMessage(JSON.stringify(ANSWER)));
		await roundWith(values({ 'apiKeys.openai': OPENAI, 'apiKeys.anthropic': ANTHROPIC }), fn);
		expect(new URL(seen[0]!.url).origin).toBe('https://api.anthropic.com');
		expect(seen[0]!.body.model).toBe('claude-opus-5-5');
	});

	test('OpenAI key only, nothing chosen: GPT-5.4 with its reasoning effort (medium)', async () => {
		const { fn, seen } = mockFetch(() => openAIChat(JSON.stringify(ANSWER)));
		await roundWith(values({ 'apiKeys.openai': OPENAI }), fn);
		expect(new URL(seen[0]!.url).origin).toBe('https://api.openai.com');
		expect(seen[0]!.body.model).toBe('gpt-5.4');
		expect(seen[0]!.body.reasoning_effort).toBe('medium');
	});

	test('GPT-5.4 mini chosen: reasoning effort high', async () => {
		const { fn, seen } = mockFetch(() => openAIChat(JSON.stringify(ANSWER)));
		await roundWith(values({ 'feedback.fixModel': 'OpenAI|gpt-5.4-mini', 'apiKeys.openai': OPENAI }), fn);
		expect(seen[0]!.body.model).toBe('gpt-5.4-mini');
		expect(seen[0]!.body.reasoning_effort).toBe('high');
	});

	test('one limit for the whole call, retries included: a hung provider ends in a plain message', async () => {
		expect(FIX_DEADLINE_MS).toBeLessThan(120_000); // the feedback window stops waiting at 120 s
		let calls = 0;
		const hang = (async (_input: string | URL | Request, init?: RequestInit) => {
			calls += 1;
			return new Promise<Response>((_resolve, reject) => {
				const signal = init?.signal ?? (_input instanceof Request ? _input.signal : undefined);
				signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
			});
		}) as unknown as typeof fetch;
		for (const provider of ['Anthropic', 'OpenAI'] as const) {
			const started = Date.now();
			const result = await completerFor(provider)({
				apiKey: 'k',
				model: provider === 'Anthropic' ? 'claude-opus-5-5' : 'gpt-5.4',
				system: 's',
				user: 'u',
				fetch: hang,
				deadlineMs: 50,
			});
			expect(result).toEqual({
				ok: false,
				message: `${provider} took too long to answer. Try again, or pick a faster model in Settings > Feedback.`,
			});
			expect(Date.now() - started).toBeLessThan(5_000);
		}
		expect(calls).toBeGreaterThan(0);
	});

	test('a malformed Anthropic answer is a plain error, never a crash', async () => {
		const { fn } = mockFetch(() => anthropicMessage('{"explanation": "half'));
		const outcome = await roundWith(values({ 'apiKeys.anthropic': ANTHROPIC }), fn);
		expect(outcome.ok).toBe(false);
		if (!outcome.ok) expect(outcome.message).toContain('Nothing was changed.');
	});

	test('a refused key is reported in plain words', async () => {
		const { fn } = mockFetch(() => ({ type: 'error', error: { type: 'authentication_error', message: 'invalid x-api-key' } }), 401);
		const outcome = await roundWith(values({ 'apiKeys.anthropic': ANTHROPIC }), fn);
		expect(outcome).toEqual({ ok: false, message: 'Anthropic refused the API key. Check it in Settings.' });
	});
});

describe('the Anthropic structured-output answer', () => {
	test('the request: the model, the fix schema as structured output, room for the answer', () => {
		const req = anthropicRequest({ model: 'claude-sonnet-5-5', system: 's', user: 'u', schema: FIX_RESPONSE_SCHEMA_V2 as unknown as Record<string, unknown> });
		expect(req.model).toBe('claude-sonnet-5-5');
		expect(req.max_tokens).toBeGreaterThanOrEqual(16_000);
		expect(req.output_config.format.type).toBe('json_schema');
		// Nullable fields become anyOf for Anthropic; the shape is otherwise the same.
		expect(JSON.stringify(req.output_config.format.schema)).not.toContain('"type":["');
		expect(req.messages).toEqual([{ role: 'user', content: 'u' }]);
		expect('fallbacks' in req).toBe(false);
		expect('betas' in req).toBe(false);
	});
	test('effort is set explicitly: high on Opus 5.5 and Sonnet 5.5; no thinking field, budget or sampling', () => {
		for (const model of ['claude-opus-5-5', 'claude-sonnet-5-5']) {
			const req = anthropicRequest({ model, system: 's', user: 'u' }) as Record<string, unknown>;
			expect((req.output_config as { effort: string }).effort).toBe('high');
			expect(req.max_tokens).toBe(16_000);
			expect('thinking' in req).toBe(false);
			expect(JSON.stringify(req)).not.toContain('budget_tokens');
			for (const p of ['temperature', 'top_p', 'top_k']) expect(p in req).toBe(false);
		}
	});
	test('OpenAI reasoning effort: GPT-5.4 medium, GPT-5.4 mini high; a hand-typed model gets none', () => {
		expect(openAIRequest({ model: 'gpt-5.4', system: 's', user: 'u' }).reasoning_effort).toBe('medium');
		expect(openAIRequest({ model: 'gpt-5.4-mini', system: 's', user: 'u' }).reasoning_effort).toBe('high');
		expect('reasoning_effort' in openAIRequest({ model: 'some-other-model', system: 's', user: 'u' })).toBe(false);
		expect(openAIRequest({ model: 'gpt-5.4', system: 's', user: 'u' }).response_format.json_schema.strict).toBe(true);
	});
	test('text blocks are joined into the JSON; other blocks are skipped', () => {
		const reply = { stop_reason: 'end_turn', content: [{ type: 'thinking', thinking: '' }, { type: 'text', text: '{"a":' }, { type: 'text', text: '1}' }] };
		expect(anthropicAnswer(reply)).toEqual({ ok: true, text: '{"a":1}' });
	});
	test('a refusal, a cut-off and an empty answer are plain errors', () => {
		expect(anthropicAnswer(anthropicMessage('{}', 'refusal'))).toEqual({ ok: false, message: 'The model declined to look at this dictation.' });
		expect(anthropicAnswer(anthropicMessage('{"a', 'max_tokens'))).toEqual({ ok: false, message: 'The answer was cut off. Try again.' });
		expect(anthropicAnswer({ stop_reason: 'end_turn', content: [] })).toEqual({ ok: false, message: 'Anthropic sent back an empty answer.' });
		expect(anthropicAnswer(null).ok).toBe(false);
	});
});
