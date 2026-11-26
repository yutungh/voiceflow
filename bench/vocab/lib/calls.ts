/**
 * The API calls, each shaped like VoiceFlow's own request where VoiceFlow
 * makes one:
 * - transcribe: services/isomorphic/transcription/cloud/openai.ts
 *   (file, model, prompt, temperature 0 from the '0.0' default, keywords[],
 *   languages ['en'] from the 'en' default)
 * - tidyOpenAI: services/isomorphic/completion/openai-compatible.ts
 *   (system + user, nothing else)
 * - tidyAnthropic: services/isomorphic/completion/anthropic.ts
 *   (system + user, max_tokens 1024, no effort or temperature)
 * - audioChat: not in VoiceFlow; Chat Completions with input_audio and text
 *   output only.
 * Transport failures (429, 5xx, network) are retried; anything else is
 * returned as an error and kept in the results, never retried into a pass.
 */
import OpenAI, { toFile } from 'openai';
import Anthropic from '@anthropic-ai/sdk';
import { anthropicKey, openaiKey } from './env';
import { OPUS_MODEL, TIDY_MODEL, TRANSCRIBE_MODEL, type SpeechPayload } from './prompts';

export type CallResult = {
	text: string | null;
	model: string;
	// biome-ignore lint: provider usage objects
	usage: any;
	stop?: string | null;
	error?: string;
	/** Wall time of the whole call, retries and waits included. */
	ms: number;
	/** Time of the attempt that answered (the latency a dictation would see). */
	latencyMs?: number;
	attempts?: number;
};

let oa: OpenAI | null = null;
let an: Anthropic | null = null;
const openai = () => (oa ??= new OpenAI({ apiKey: openaiKey(), maxRetries: 0, timeout: 120_000 }));
const anthropic = () => (an ??= new Anthropic({ apiKey: anthropicKey(), maxRetries: 0, timeout: 120_000 }));

function transient(e: unknown): boolean {
	const status = (e as { status?: number })?.status;
	if (status === 429 || (status !== undefined && status >= 500)) return true;
	return status === undefined; // connection error / timeout
}

async function withRetry(model: string, fn: () => Promise<Omit<CallResult, 'ms'>>): Promise<CallResult> {
	const start = Date.now();
	let last: unknown;
	for (let attempt = 0; attempt < 4; attempt++) {
		const tried = Date.now();
		try {
			const r = await fn();
			const now = Date.now();
			return { ...r, ms: now - start, latencyMs: now - tried, attempts: attempt + 1 };
		} catch (e) {
			last = e;
			if (!transient(e)) break;
			await new Promise((r) => setTimeout(r, 2000 * 2 ** attempt));
		}
	}
	const err = last as { status?: number; message?: string };
	return { text: null, model, usage: null, error: `${err?.status ?? 'network'}: ${err?.message ?? String(last)}`.slice(0, 500), ms: Date.now() - start };
}

export function transcribe(flac: Buffer, payload: SpeechPayload): Promise<CallResult> {
	return withRetry(TRANSCRIBE_MODEL, async () => {
		const r = await openai().audio.transcriptions.create({
			file: await toFile(flac, 'recording.flac', { type: 'audio/flac' }),
			model: TRANSCRIBE_MODEL,
			prompt: payload.prompt || undefined,
			temperature: 0,
			...(payload.keywords.length ? { keywords: payload.keywords } : {}),
			languages: ['en'],
		} as unknown as OpenAI.Audio.TranscriptionCreateParamsNonStreaming);
		// biome-ignore lint: usage not in older types
		const any = r as any;
		return { text: String(any.text ?? '').trim(), model: TRANSCRIBE_MODEL, usage: any.usage ?? null };
	});
}

export function tidyOpenAI(system: string, user: string, model = TIDY_MODEL): Promise<CallResult> {
	return withRetry(model, async () => {
		const r = await openai().chat.completions.create({
			model,
			messages: [
				{ role: 'system', content: system },
				{ role: 'user', content: user },
			],
		});
		return { text: r.choices[0]?.message?.content ?? '', model: r.model, usage: r.usage, stop: r.choices[0]?.finish_reason };
	});
}

/**
 * `effort` unset: as VoiceFlow sends it (the model's default). Set: sent as
 * output_config.effort (thinking stays adaptive; it can't be turned off on
 * Opus 5.5, and Sonnet 5.5 only turns it off with between_tools).
 */
export function tidyAnthropic(system: string, user: string, opts: { model?: string; effort?: 'low' | 'medium' | 'high' } = {}): Promise<CallResult> {
	const model = opts.model ?? OPUS_MODEL;
	return withRetry(model, async () => {
		const r = await anthropic().messages.create({
			model,
			max_tokens: 1024,
			system,
			messages: [{ role: 'user', content: user }],
			...(opts.effort ? { output_config: { effort: opts.effort } } : {}),
		} as Anthropic.MessageCreateParamsNonStreaming);
		const text = r.content
			.filter((b): b is Anthropic.TextBlock => b.type === 'text')
			.map((b) => b.text)
			.join('');
		return { text, model: r.model, usage: r.usage, stop: r.stop_reason };
	});
}

/**
 * An audio-capable chat model; `wav` is base64, or null for the text-only
 * control. With a recording the answer is text only. Without one, the gpt-audio
 * models refuse a text-only request ("either input content or output modality
 * must contain audio"), so the control asks for text and audio and keeps the
 * text of the spoken answer (`message.audio.transcript`); the audio is dropped.
 * `spoken` asks for that same text-and-audio answer even with a recording, so
 * all gpt-audio arms can be run in one output mode.
 */
export function audioChat(model: string, system: string, user: string, wav: string | null, spoken = !wav): Promise<CallResult> {
	return withRetry(model, async () => {
		const content: OpenAI.Chat.ChatCompletionContentPart[] = [{ type: 'text', text: user }];
		if (wav) content.push({ type: 'input_audio', input_audio: { data: wav, format: 'wav' } });
		const r = await openai().chat.completions.create({
			model,
			...(spoken ? { modalities: ['text', 'audio'], audio: { voice: 'alloy', format: 'wav' } } : { modalities: ['text'] }),
			messages: [
				{ role: 'system', content: system },
				{ role: 'user', content },
			],
		} as OpenAI.Chat.ChatCompletionCreateParamsNonStreaming);
		const msg = r.choices[0]?.message;
		const text = spoken ? (msg?.audio?.transcript ?? msg?.content ?? '') : (msg?.content ?? '');
		return { text, model: r.model, usage: r.usage, stop: r.choices[0]?.finish_reason };
	});
}

export function tts(text: string, voice: string, instructions: string): Promise<ArrayBuffer> {
	return openai()
		.audio.speech.create({ model: 'gpt-4o-mini-tts', voice, input: text, instructions, response_format: 'wav' })
		.then((r) => r.arrayBuffer());
}
