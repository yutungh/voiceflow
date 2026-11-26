/**
 * What each call costs, from the usage the API returns, at list prices
 * checked 2026-11-25 (developers.openai.com/api/docs/pricing,
 * platform.claude.com pricing). Every call is appended to a ledger outside
 * the repository, so the running total and the estimates come from real bills.
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './paths';

const M = 1_000_000;

/** USD. Per 1M tokens unless noted. */
export const PRICES = {
	'gpt-transcribe': { perMinute: 0.0045 },
	'gpt-5.4-mini': { input: 0.75, cachedInput: 0.075, output: 4.5 },
	'gpt-5.4': { input: 2.5, cachedInput: 0.25, output: 15 },
	'gpt-audio-1.5': { textInput: 2.5, audioInput: 32, output: 10, audioOutput: 64 },
	'gpt-audio-mini': { textInput: 0.6, audioInput: 10, output: 2.4, audioOutput: 20 },
	'claude-opus-5-5': { input: 4, cacheRead: 0.4, cacheWrite: 5, output: 20 },
	'claude-sonnet-5-5': { input: 2, cacheRead: 0.1, cacheWrite: 2.5, output: 10 },
	// gpt-4o-mini-tts bills $0.60/1M text in and $12/1M audio out; the speech
	// endpoint returns no usage, so it is charged by audio length (~$0.015/min).
	'gpt-4o-mini-tts': { perMinute: 0.015 },
} as const;

// biome-ignore lint: usage shapes differ by API
export function costOf(model: string, usage: any, extra: { seconds?: number } = {}): number {
	if (!usage && extra.seconds === undefined) return 0;
	switch (model) {
		case 'gpt-transcribe': {
			const seconds = usage?.type === 'duration' ? Number(usage.seconds) : (extra.seconds ?? 0);
			return (seconds / 60) * PRICES['gpt-transcribe'].perMinute;
		}
		case 'gpt-4o-mini-tts':
			return ((extra.seconds ?? 0) / 60) * PRICES['gpt-4o-mini-tts'].perMinute;
		case 'gpt-5.4-mini':
		case 'gpt-5.4': {
			const p = PRICES[model];
			const cached = usage.prompt_tokens_details?.cached_tokens ?? 0;
			return ((usage.prompt_tokens - cached) * p.input + cached * p.cachedInput + usage.completion_tokens * p.output) / M;
		}
		case 'gpt-audio-1.5':
		case 'gpt-audio-mini': {
			const p = PRICES[model];
			const audio = usage.prompt_tokens_details?.audio_tokens ?? 0;
			const audioOut = usage.completion_tokens_details?.audio_tokens ?? 0;
			return (
				((usage.prompt_tokens - audio) * p.textInput +
					audio * p.audioInput +
					(usage.completion_tokens - audioOut) * p.output +
					audioOut * p.audioOutput) /
				M
			);
		}
		case 'claude-opus-5-5':
		case 'claude-sonnet-5-5': {
			const p = PRICES[model];
			return (
				(usage.input_tokens * p.input +
					(usage.cache_read_input_tokens ?? 0) * p.cacheRead +
					(usage.cache_creation_input_tokens ?? 0) * p.cacheWrite +
					usage.output_tokens * p.output) /
				M
			);
		}
		default:
			throw new Error(`no price for ${model}`);
	}
}

const LEDGER = path.join(DATA_DIR, 'ledger.jsonl');

export type LedgerRow = { ts: string; set: string; step: string; arm: string; model: string; cost: number };

export function record(row: Omit<LedgerRow, 'ts'>): void {
	mkdirSync(DATA_DIR, { recursive: true });
	appendFileSync(LEDGER, `${JSON.stringify({ ts: new Date().toISOString(), ...row })}\n`);
}

export function ledger(): LedgerRow[] {
	if (!existsSync(LEDGER)) return [];
	return readFileSync(LEDGER, 'utf8')
		.split('\n')
		.filter(Boolean)
		.map((l) => JSON.parse(l));
}

export function spent(filter: (r: LedgerRow) => boolean = () => true): number {
	return ledger()
		.filter(filter)
		.reduce((a, r) => a + r.cost, 0);
}

/** Mean cost per call of one arm, from the ledger; `fallback` until there are calls. */
export function perCall(arm: string, fallback: number): number {
	const rows = ledger().filter((r) => r.arm === arm && r.cost > 0);
	return rows.length ? rows.reduce((a, r) => a + r.cost, 0) / rows.length : fallback;
}
