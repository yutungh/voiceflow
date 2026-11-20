/**
 * VoiceFlow phase F: the fix engine's one model call, per provider. Each
 * returns the reply's raw JSON text; `contract.ts` decides whether it is
 * acceptable. Pure apart from the HTTP call, so the end-to-end check can run
 * it outside the app with its own `fetch`.
 */
import OpenAI from 'openai';
import { FIX_RESPONSE_SCHEMA } from './contract';

export const FEEDBACK_PROVIDERS = ['OpenAI', 'Anthropic'] as const;
export type FeedbackProvider = (typeof FEEDBACK_PROVIDERS)[number];

export const DEFAULT_FEEDBACK_MODEL = 'gpt-5.4-mini';

/** The models offered in Settings > Feedback. */
export const FEEDBACK_MODELS: {
	provider: FeedbackProvider;
	model: string;
	label: string;
}[] = [
	{ provider: 'OpenAI', model: 'gpt-5.4-mini', label: 'OpenAI gpt-5.4-mini (default)' },
	{ provider: 'OpenAI', model: 'gpt-5.4', label: 'OpenAI gpt-5.4 (stronger, slower)' },
	{ provider: 'Anthropic', model: 'claude-opus-5-5', label: 'Anthropic Claude Opus 5.5' },
];

export type CompleteArgs = {
	apiKey: string;
	model: string;
	system: string;
	user: string;
	signal?: AbortSignal;
	/** For tests and the headless check; the app passes its pooled fetch. */
	fetch?: typeof fetch;
};

export type CompleteResult = { ok: true; text: string } | { ok: false; message: string };

function plainError(provider: string, error: unknown): string {
	const status = (error as { status?: number })?.status;
	if (status === 401) return `${provider} refused the API key. Check it in Settings.`;
	if (status === 404) return `${provider} doesn't offer that model. Pick another one in Settings > Feedback.`;
	if (status === 429) return `${provider} is busy or the account is out of credit. Try again in a moment.`;
	if (typeof status === 'number' && status >= 500)
		return `${provider} is having trouble (error ${status}). Try again in a few minutes.`;
	if ((error as { name?: string })?.name === 'AbortError') return 'Cancelled.';
	return `Couldn't reach ${provider}. Check the connection and try again.`;
}

export async function completeOpenAI(args: CompleteArgs): Promise<CompleteResult> {
	const client = new OpenAI({
		apiKey: args.apiKey,
		dangerouslyAllowBrowser: true,
		...(args.fetch ? { fetch: args.fetch } : {}),
		maxRetries: 1,
		timeout: 60_000,
	});
	try {
		const reply = await client.chat.completions.create(
			{
				model: args.model,
				messages: [
					{ role: 'system', content: args.system },
					{ role: 'user', content: args.user },
				],
				response_format: {
					type: 'json_schema',
					json_schema: {
						name: 'voiceflow_fix',
						strict: true,
						schema: FIX_RESPONSE_SCHEMA as unknown as Record<string, unknown>,
					},
				},
			},
			{ signal: args.signal },
		);
		const choice = reply.choices.at(0);
		if (choice?.message?.refusal)
			return { ok: false, message: 'The model declined to look at this dictation.' };
		const text = choice?.message?.content;
		if (!text) return { ok: false, message: 'OpenAI sent back an empty answer.' };
		return { ok: true, text };
	} catch (error) {
		return { ok: false, message: plainError('OpenAI', error) };
	}
}

/** Anthropic's structured output takes nullable fields as anyOf. */
function anthropicSchema(): Record<string, unknown> {
	const props = FIX_RESPONSE_SCHEMA.properties.proposals.items.properties;
	return {
		...FIX_RESPONSE_SCHEMA,
		properties: {
			...FIX_RESPONSE_SCHEMA.properties,
			proposals: {
				type: 'array',
				items: {
					...FIX_RESPONSE_SCHEMA.properties.proposals.items,
					properties: {
						...props,
						to: { anyOf: [{ type: 'string' }, { type: 'null' }] },
						scope: { anyOf: [{ type: 'string', enum: ['app', 'everywhere'] }, { type: 'null' }] },
						expression_delta: { anyOf: [{ type: 'integer' }, { type: 'null' }] },
						style_id: { anyOf: [{ type: 'string' }, { type: 'null' }] },
					},
				},
			},
		},
	};
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
	try {
		// The installed SDK predates `output_config` in its types; the API takes it.
		const body = {
			model: args.model,
			max_tokens: 8000,
			system: args.system,
			messages: [{ role: 'user' as const, content: args.user }],
			output_config: { effort: 'low', format: { type: 'json_schema', schema: anthropicSchema() } },
		};
		const reply = await client.messages.create(
			body as unknown as Parameters<typeof client.messages.create>[0],
			{ signal: args.signal },
		);
		const message = reply as unknown as {
			stop_reason?: string;
			content: { type: string; text?: string }[];
		};
		if (message.stop_reason === 'refusal')
			return { ok: false, message: 'The model declined to look at this dictation.' };
		if (message.stop_reason === 'max_tokens')
			return { ok: false, message: 'The answer was cut off. Try again.' };
		const text = message.content
			.filter((b) => b.type === 'text')
			.map((b) => b.text ?? '')
			.join('');
		if (!text) return { ok: false, message: 'Anthropic sent back an empty answer.' };
		return { ok: true, text };
	} catch (error) {
		return { ok: false, message: plainError('Anthropic', error) };
	}
}

export function completerFor(provider: FeedbackProvider) {
	return provider === 'Anthropic' ? completeAnthropic : completeOpenAI;
}
