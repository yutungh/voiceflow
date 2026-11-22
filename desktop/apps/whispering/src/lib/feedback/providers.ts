/**
 * VoiceFlow phase F: the fix engine's one model call, per provider. Each
 * returns the reply's raw JSON text; `contract.ts` decides whether it is
 * acceptable. Pure apart from the HTTP call, so the end-to-end check can run
 * it outside the app with its own `fetch`.
 *
 * Uses the same SDK clients and transports as tidy-up's completion services
 * (`$lib/services/isomorphic/completion`), which return plain text only: the
 * fix needs each provider's structured-output path, so the call is made here.
 *
 * VoiceFlow 0.9.17: the provider and model come from Settings > Feedback
 * ("Model for finding fixes", `fix-model.ts`).
 */
import OpenAI from 'openai';
import { FIX_RESPONSE_SCHEMA, nullableAsAnyOf } from './contract';
import type { FeedbackProvider } from './fix-model';

export { FEEDBACK_PROVIDERS, type FeedbackProvider } from './fix-model';

export type CompleteArgs = {
	apiKey: string;
	model: string;
	system: string;
	user: string;
	signal?: AbortSignal;
	/** For tests and the headless check; the app passes its pooled fetch. */
	fetch?: typeof fetch;
	/** The answer's JSON schema (default: the v1 contract) and its name. */
	schema?: Record<string, unknown>;
	schemaName?: string;
	/** The whole call's time limit, retries included (default `FIX_DEADLINE_MS`). */
	deadlineMs?: number;
};

export type CompleteResult = { ok: true; text: string } | { ok: false; message: string };

/**
 * One limit for the whole call, retries included. The SDKs' own timeout is
 * per attempt (90 s, one retry), and the feedback window stops waiting after
 * 120 s, so the call is cut at 105 s and says so plainly.
 */
export const FIX_DEADLINE_MS = 105_000;

function deadline(ms: number, outer?: AbortSignal) {
	const controller = new AbortController();
	let expired = false;
	const timer = setTimeout(() => {
		expired = true;
		controller.abort();
	}, ms);
	const onOuter = () => controller.abort();
	if (outer?.aborted) controller.abort();
	else outer?.addEventListener('abort', onOuter, { once: true });
	return {
		signal: controller.signal,
		expired: () => expired,
		done: () => {
			clearTimeout(timer);
			outer?.removeEventListener('abort', onOuter);
		},
	};
}

const tooSlow = (provider: string) =>
	`${provider} took too long to answer. Try again, or pick a faster model in Settings > Feedback.`;

function plainError(provider: string, error: unknown): string {
	const status = (error as { status?: number })?.status;
	if (status === 401) return `${provider} refused the API key. Check it in Settings.`;
	if (status === 403) return `Your ${provider} account can't use that model. Pick another one in Settings > Feedback.`;
	if (status === 404) return `${provider} doesn't offer that model. Pick another one in Settings > Feedback.`;
	if (status === 429) return `${provider} is busy or the account is out of credit. Try again in a moment.`;
	if (typeof status === 'number' && status >= 500)
		return `${provider} is having trouble (error ${status}). Try again in a few minutes.`;
	if (typeof status === 'number') return `${provider} couldn't take this request (error ${status}). Try again, or pick another model in Settings > Feedback.`;
	if ((error as { name?: string })?.name === 'AbortError') return 'Cancelled.';
	return `Couldn't reach ${provider}. Check the connection and try again.`;
}

/**
 * Reasoning effort per OpenAI model. 0.9.17 aims for high (finding fixes is
 * rare and quality-sensitive), but GPT-5.4 at high took about a minute on a
 * short dictation, so it runs at medium; GPT-5.4 mini stays at high.
 */
export const OPENAI_REASONING_EFFORT: Record<string, 'low' | 'medium' | 'high'> = {
	'gpt-5.4': 'medium',
	'gpt-5.4-mini': 'high',
};

/**
 * The chat completions request for a fix: a strict JSON schema, and an
 * explicit reasoning effort for the models listed above. A model id typed by
 * hand that isn't listed gets none (it may not be a reasoning model).
 */
export function openAIRequest(args: Pick<CompleteArgs, 'model' | 'system' | 'user' | 'schema' | 'schemaName'>) {
	const effort = OPENAI_REASONING_EFFORT[args.model];
	return {
		model: args.model,
		messages: [
			{ role: 'system' as const, content: args.system },
			{ role: 'user' as const, content: args.user },
		],
		response_format: {
			type: 'json_schema' as const,
			json_schema: {
				name: args.schemaName ?? 'voiceflow_fix',
				strict: true,
				schema: args.schema ?? (FIX_RESPONSE_SCHEMA as unknown as Record<string, unknown>),
			},
		},
		...(effort ? { reasoning_effort: effort } : {}),
	};
}

export async function completeOpenAI(args: CompleteArgs): Promise<CompleteResult> {
	const client = new OpenAI({
		apiKey: args.apiKey,
		dangerouslyAllowBrowser: true,
		...(args.fetch ? { fetch: args.fetch } : {}),
		maxRetries: 1,
		timeout: 90_000,
	});
	const limit = deadline(args.deadlineMs ?? FIX_DEADLINE_MS, args.signal);
	try {
		const reply = await client.chat.completions.create(openAIRequest(args), { signal: limit.signal });
		const choice = reply.choices.at(0);
		if (choice?.message?.refusal)
			return { ok: false, message: 'The model declined to look at this dictation.' };
		if (choice?.finish_reason === 'length') return { ok: false, message: 'The answer was cut off. Try again.' };
		const text = choice?.message?.content;
		if (!text) return { ok: false, message: 'OpenAI sent back an empty answer.' };
		return { ok: true, text };
	} catch (error) {
		return { ok: false, message: limit.expired() ? tooSlow('OpenAI') : plainError('OpenAI', error) };
	} finally {
		limit.done();
	}
}

/** Anthropic's structured output takes nullable fields as anyOf. */
export function anthropicSchema(schema?: Record<string, unknown>): Record<string, unknown> {
	return nullableAsAnyOf(schema ?? FIX_RESPONSE_SCHEMA) as Record<string, unknown>;
}

/**
 * The Messages API request for a fix: structured output (`output_config.format`
 * with the fix's JSON schema) at `effort: 'high'` (0.9.17: finding fixes is
 * rare and quality-sensitive; Opus 5.5 would otherwise default to medium).
 * Thinking is left to the model (adaptive, always on for Opus 5.5): no
 * `thinking` field, so never `disabled` or a `budget_tokens`, and no sampling
 * parameters. 16,000 tokens holds the thinking and the answer without
 * streaming. No beta features: the model named under Find fixes is the one
 * that answers, and a safety decline is a plain error.
 */
export const ANTHROPIC_EFFORT = 'high';
export const ANTHROPIC_MAX_TOKENS = 16_000;

export function anthropicRequest(args: Pick<CompleteArgs, 'model' | 'system' | 'user' | 'schema'>) {
	return {
		model: args.model,
		max_tokens: ANTHROPIC_MAX_TOKENS,
		system: args.system,
		messages: [{ role: 'user' as const, content: args.user }],
		output_config: {
			effort: ANTHROPIC_EFFORT,
			format: { type: 'json_schema', schema: anthropicSchema(args.schema) },
		},
	};
}

/** The answer's text, or why there is none. Exported for tests. */
export function anthropicAnswer(reply: unknown): CompleteResult {
	const message = reply as { stop_reason?: string | null; content?: { type: string; text?: string }[] } | null;
	if (!message || !Array.isArray(message.content))
		return { ok: false, message: 'Anthropic sent back an answer VoiceFlow couldn’t read.' };
	if (message.stop_reason === 'refusal')
		return { ok: false, message: 'The model declined to look at this dictation.' };
	if (message.stop_reason === 'max_tokens') return { ok: false, message: 'The answer was cut off. Try again.' };
	const text = message.content
		.filter((b) => b.type === 'text')
		.map((b) => b.text ?? '')
		.join('');
	if (!text) return { ok: false, message: 'Anthropic sent back an empty answer.' };
	return { ok: true, text };
}

export async function completeAnthropic(args: CompleteArgs): Promise<CompleteResult> {
	const { default: Anthropic } = await import('@anthropic-ai/sdk');
	const client = new Anthropic({
		apiKey: args.apiKey,
		dangerouslyAllowBrowser: true,
		...(args.fetch ? { fetch: args.fetch } : {}),
		maxRetries: 1,
		timeout: 90_000,
	});
	const limit = deadline(args.deadlineMs ?? FIX_DEADLINE_MS, args.signal);
	try {
		// The installed SDK predates `output_config` in its types; the API takes it.
		const reply = await client.messages.create(
			anthropicRequest(args) as unknown as Parameters<typeof client.messages.create>[0],
			{ signal: limit.signal },
		);
		return anthropicAnswer(reply);
	} catch (error) {
		return { ok: false, message: limit.expired() ? tooSlow('Anthropic') : plainError('Anthropic', error) };
	} finally {
		limit.done();
	}
}

export function completerFor(provider: FeedbackProvider) {
	return provider === 'Anthropic' ? completeAnthropic : completeOpenAI;
}
