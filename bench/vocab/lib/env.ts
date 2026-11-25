/**
 * API keys, read from the repository's `.env.local` (never printed or logged).
 *
 * Looks in this order: the file named by VOCAB_ENV_FILE, `.env.local` at this
 * checkout's root, then `.env.local` in the main checkout (when this is a git
 * worktree). Lines are `Name = value`; the names VoiceFlow's maintainer uses
 * are OpenAIAPIKey and AnthropicAPIKey, and the usual OPENAI_API_KEY /
 * ANTHROPIC_API_KEY environment variables also work.
 */
import { existsSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { BENCH_DIR } from './paths';

function candidates(): string[] {
	const out: string[] = [];
	if (process.env.VOCAB_ENV_FILE) out.push(process.env.VOCAB_ENV_FILE);
	const root = path.resolve(BENCH_DIR, '..', '..');
	out.push(path.join(root, '.env.local'));
	try {
		const common = execFileSync('git', ['-C', root, 'rev-parse', '--path-format=absolute', '--git-common-dir'], {
			encoding: 'utf8',
			windowsHide: true,
			stdio: ['ignore', 'pipe', 'ignore'],
		}).trim();
		out.push(path.join(path.dirname(common), '.env.local'));
	} catch {
		// not a git checkout: only the paths above
	}
	return out;
}

function parse(file: string): Record<string, string> {
	const vars: Record<string, string> = {};
	for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
		const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
		if (!m) continue;
		vars[m[1]!] = m[2]!.replace(/^["']|["']$/g, '');
	}
	return vars;
}

let cache: Record<string, string> | null = null;

function vars(): Record<string, string> {
	if (cache) return cache;
	for (const file of candidates()) {
		if (existsSync(file)) return (cache = parse(file));
	}
	return (cache = {});
}

export function openaiKey(): string {
	const key = process.env.OPENAI_API_KEY || vars().OpenAIAPIKey || vars().OPENAI_API_KEY;
	if (!key) throw new Error('No OpenAI key: set OPENAI_API_KEY or OpenAIAPIKey in .env.local');
	return key;
}

export function anthropicKey(): string {
	const key = process.env.ANTHROPIC_API_KEY || vars().AnthropicAPIKey || vars().ANTHROPIC_API_KEY;
	if (!key) throw new Error('No Anthropic key: set ANTHROPIC_API_KEY or AnthropicAPIKey in .env.local');
	return key;
}
