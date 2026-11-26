/** One JSON file per call under DATA_DIR/runs, so a rerun resumes and never pays twice. */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './paths';
import type { CallResult } from './calls';

export type Stored = CallResult & { input?: string | null; cost: number; at: string };

export function keyPath(set: string, parts: string[]): string {
	return path.join(DATA_DIR, 'runs', set, ...parts.slice(0, -1), `${parts.at(-1)}.json`);
}

export function load(set: string, parts: string[]): Stored | null {
	const p = keyPath(set, parts);
	return existsSync(p) ? (JSON.parse(readFileSync(p, 'utf8')) as Stored) : null;
}

export function save(set: string, parts: string[], value: Stored): void {
	const p = keyPath(set, parts);
	mkdirSync(path.dirname(p), { recursive: true });
	writeFileSync(p, `${JSON.stringify(value, null, 2)}\n`);
}

/** The speech step's output for one sentence, as the correction step's frozen input. */
export function speechOutput(set: string, list: number, run: number, id: string): string | null {
	const r = load(set, ['speech', `list${list}`, `r${run}`, id]);
	return r && !r.error ? (r.text ?? '') : null;
}
