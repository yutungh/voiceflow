/**
 * Plan A: collect the speaker's recordings from VoiceFlow itself.
 *
 * The speaker reads each script sentence as an ordinary VoiceFlow dictation
 * (any text box will do). VoiceFlow keeps each dictation's audio as
 * <id>.wav (16 kHz mono) with an <id>.md beside it holding its time and text,
 * in %APPDATA%\com.voiceflowkeyboard.desktop\recordings. This script only
 * READS that folder:
 *
 *   bun collect.ts start      note the start time (run just before reading)
 *   bun collect.ts            match and copy the recordings made since then
 *
 * Matching: each dictation made after the start is compared with every script
 * sentence by the words they share (names are often misheard, the rest of the
 * sentence is not); it goes to the best-matching sentence when that match is
 * clear. A sentence read twice keeps the LATER reading. Dictations that match
 * nothing (anything else typed meanwhile) are ignored and never copied.
 * Nothing from the .md files is written anywhere except the match scores.
 *
 * Options: --dir <recordings folder> (default: VoiceFlow's), --since <ISO time>,
 * --until <ISO time>, --dry (report only).
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { prepare } from './lib/audio';
import { SENTENCES } from './lib/data';
import { DATA_DIR } from './lib/paths';
import { tokens } from './lib/score';

const argOf = (name: string) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1] : undefined;
};
const SESSION = path.join(DATA_DIR, 'session.json');
const OUT = path.join(DATA_DIR, 'recordings');
const SET = argOf('set') ?? 'real';

if (process.argv[2] === 'start') {
	mkdirSync(DATA_DIR, { recursive: true });
	const start = new Date().toISOString();
	writeFileSync(SESSION, `${JSON.stringify({ start }, null, 2)}\n`);
	console.log(`Start noted: ${start}. Read the sentences now, then run: bun collect.ts`);
	process.exit(0);
}

const dir = argOf('dir') ?? path.join(process.env.APPDATA ?? '', 'com.voiceflowkeyboard.desktop', 'recordings');
/** Any time Date can read ("2026-11-26 19:05" is local time) as a UTC ISO string, like VoiceFlow's timestamps. */
const iso = (t: string | undefined) => {
	if (!t) return undefined;
	const d = new Date(t);
	if (Number.isNaN(d.getTime())) throw new Error(`not a time: ${t}`);
	return d.toISOString();
};
const since = iso(argOf('since') ?? (existsSync(SESSION) ? JSON.parse(readFileSync(SESSION, 'utf8')).start : undefined));
if (!since) throw new Error('no start time: run "bun collect.ts start" first, or pass --since <time>');
const until = iso(argOf('until'));
const dry = process.argv.includes('--dry');

/** The frontmatter fields we need and the body text, from one VoiceFlow .md file. */
function readMd(file: string): { timestamp: string; texts: string[] } | null {
	const raw = readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
	const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(raw);
	if (!m) return null;
	const fields: Record<string, string> = {};
	for (const line of m[1]!.split('\n')) {
		const f = /^([A-Za-z_]+):\s*(.*)$/.exec(line);
		if (f) fields[f[1]!] = f[2]!.replace(/^'(.*)'$/, '$1').replace(/^"(.*)"$/, '$1');
	}
	if (!fields.timestamp) return null;
	return { timestamp: fields.timestamp, texts: [m[2] ?? '', fields.finalText ?? '', fields.transcribedText ?? ''].filter((t) => t.trim()) };
}

/** Shared words over all words (Dice), on the same tokens the scorer uses. */
function similarity(a: string, b: string): number {
	const ta = tokens(a);
	const tb = tokens(b);
	if (!ta.length || !tb.length) return 0;
	const bag = new Map<string, number>();
	for (const t of tb) bag.set(t, (bag.get(t) ?? 0) + 1);
	let shared = 0;
	for (const t of ta) {
		const n = bag.get(t) ?? 0;
		if (n > 0) {
			shared++;
			bag.set(t, n - 1);
		}
	}
	return (2 * shared) / (ta.length + tb.length);
}

const MIN = 0.6; // a dictation must share at least this much with its sentence
const MARGIN = 0.15; // ...and beat the next-best sentence by this much

type Take = { rec: string; timestamp: string; sentence: string; score: number };
const takes: Take[] = [];
let strays = 0;
let unclear = 0;
const files = readdirSync(dir).filter((f) => f.endsWith('.md'));
for (const f of files) {
	const id = f.slice(0, -3);
	const wav = path.join(dir, `${id}.wav`);
	if (!existsSync(wav) || statSync(wav).size <= 44) continue;
	const md = readMd(path.join(dir, f));
	if (!md || md.timestamp < since || (until && md.timestamp > until)) continue;
	const scored = SENTENCES.map((s) => ({ s, score: Math.max(...md.texts.map((t) => similarity(t, s.text))) })).sort((a, b) => b.score - a.score);
	const [best, next] = scored;
	if (!best || best.score < MIN) {
		strays++;
		continue;
	}
	if (next && best.score - next.score < MARGIN) {
		unclear++;
		continue;
	}
	takes.push({ rec: id, timestamp: md.timestamp, sentence: best.s.id, score: Number(best.score.toFixed(3)) });
}

// The later reading of a sentence wins.
takes.sort((a, b) => a.timestamp.localeCompare(b.timestamp));
const chosen = new Map<string, Take>();
const rereads: string[] = [];
for (const t of takes) {
	if (chosen.has(t.sentence)) rereads.push(t.sentence);
	chosen.set(t.sentence, t);
}
const missing = SENTENCES.filter((s) => !chosen.has(s.id)).map((s) => s.id);

console.log(`Dictations since ${since}: ${takes.length + strays + unclear} (matched ${takes.length}, unrelated ${strays}, unclear ${unclear}).`);
console.log(`Sentences matched: ${chosen.size} of ${SENTENCES.length}.${rereads.length ? ` Read twice (later one kept): ${[...new Set(rereads)].join(', ')}.` : ''}`);
if (missing.length) console.log(`Still to read: ${missing.map((id) => `${Number(id.slice(1))}`).join(', ')}`);

if (!dry) {
	mkdirSync(OUT, { recursive: true });
	for (const [sid, t] of chosen) {
		const dest = path.join(OUT, `${sid}.wav`);
		copyFileSync(path.join(dir, `${t.rec}.wav`), dest);
		prepare(SET, sid, dest);
	}
	writeFileSync(
		path.join(OUT, 'manifest.json'),
		`${JSON.stringify({ since, until: until ?? null, collected: new Date().toISOString(), sentences: Object.fromEntries([...chosen].map(([k, v]) => [k, { recording: v.rec, timestamp: v.timestamp, match: v.score }])), missing }, null, 2)}\n`,
	);
	console.log(`Copied ${chosen.size} recordings to ${OUT} and prepared them as set "${SET}".`);
}
