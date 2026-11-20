#!/usr/bin/env node
// VoiceFlow Desktop speed benchmarks (Node 22+, no dependencies).
//
//   node bench.mjs <command> --config <config.json> --out <dir> [options]
//
// Commands
//   clips   Build test clips (short / medium / long) in each upload format from
//           your own recordings, with FFmpeg.
//   stt     Upload + transcription latency by format, length and model.
//   conn    Cold vs warm connections to api.openai.com.
//   tidy    Tidy-up latency by model, reasoning effort, prompt length and
//           output bound.
//   live    Realtime transcription: stream a clip at real time and time
//           end of speech -> final text.
//   local   Local costs: the tauri-plugin-http body encoding and FFmpeg
//           compression as the app runs it.
//   report  Print p50/p90 tables from the JSONL results in --out.
//
// Nothing here stores or prints transcript text or the API key. Results hold
// timings, byte sizes, token counts, word counts and per-term match counts.
// Keep audio, results and the config outside the repository.
//
// See README.md for the config format.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { freshAgent, multipart, preconnect, request, warmAgent } from './lib/http.mjs';
import { loadKey, redact } from './lib/key.mjs';
import { countTerms, fmtMs, sleep, summarize, wordCount } from './lib/stats.mjs';

// ---------------------------------------------------------------- arguments

const [command, ...rest] = process.argv.slice(2);
const args = {};
for (let i = 0; i < rest.length; i++) {
	const a = rest[i];
	if (!a.startsWith('--')) continue;
	const next = rest[i + 1];
	if (next === undefined || next.startsWith('--')) args[a.slice(2)] = true;
	else {
		args[a.slice(2)] = next;
		i++;
	}
}

const OUT = args.out ? path.resolve(args.out) : null;
const CONFIG = args.config ? JSON.parse(fs.readFileSync(args.config, 'utf8')) : {};
const FFMPEG = args.ffmpeg || CONFIG.ffmpeg || 'ffmpeg';
const REPS = Number(args.n || 5);
const TERMS = CONFIG.terms || [];
const STT_PROMPT = CONFIG.sttPrompt || '';
const LANGUAGE = CONFIG.language || 'en';

function need(cond, msg) {
	if (!cond) {
		console.error(msg);
		process.exit(2);
	}
}

let KEY = null;
function key() {
	KEY ??= loadKey(args.cred || CONFIG.credTarget);
	return KEY;
}

function out(...p) {
	need(OUT, '--out <dir> is required (keep it outside the repository).');
	const f = path.join(OUT, ...p);
	fs.mkdirSync(path.dirname(f), { recursive: true });
	return f;
}

function appendResult(file, record) {
	fs.appendFileSync(out('results', file), `${JSON.stringify(record)}\n`);
}

function readResults(file) {
	const f = path.join(OUT, 'results', file);
	if (!fs.existsSync(f)) return [];
	return fs
		.readFileSync(f, 'utf8')
		.split('\n')
		.filter(Boolean)
		.map((l) => JSON.parse(l));
}

function ffmpeg(argv) {
	const t0 = performance.now();
	const r = spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-y', ...argv], {
		windowsHide: true,
		encoding: 'utf8',
	});
	if (r.status !== 0) throw new Error(`ffmpeg failed: ${r.stderr || r.error}`);
	return performance.now() - t0;
}

// ---------------------------------------------------------------- clips

/** Upload formats. `opus` uses the app's default FFmpeg compression options. */
const APP_COMPRESSION =
	'-af silenceremove=start_periods=1:start_duration=0.1:start_threshold=-50dB:detection=peak,aformat=sample_fmts=s16:sample_rates=16000:channel_layouts=mono -c:a libopus -b:a 32k -ar 16000 -ac 1 -compression_level 10';

const FORMATS = {
	wav48f32: { ext: 'wav', type: 'audio/wav', ff: ['-c:a', 'pcm_f32le', '-ar', '48000', '-ac', '2'] },
	wav16: { ext: 'wav', type: 'audio/wav', ff: ['-c:a', 'pcm_s16le', '-ar', '16000', '-ac', '1'] },
	flac16: { ext: 'flac', type: 'audio/flac', ff: ['-c:a', 'flac', '-sample_fmt', 's16', '-ar', '16000', '-ac', '1'] },
	opus: { ext: 'ogg', type: 'audio/ogg', ff: APP_COMPRESSION.split(' ') },
};

/** Clips live in --clips <dir> when given, else <out>/clips. */
function clipFile(name) {
	if (args.clips) return path.join(path.resolve(args.clips), name);
	return out('clips', name);
}

function clipPath(length, format) {
	return clipFile(`${length}.${format}.${FORMATS[format]?.ext ?? 'raw'}`);
}

function buildClips() {
	need(CONFIG.audioDir && CONFIG.clips, 'config needs audioDir and clips {short:[...], medium:[...], long:[...]}');
	for (const [length, files] of Object.entries(CONFIG.clips)) {
		const inputs = files.map((f) => path.join(CONFIG.audioDir, f));
		const master = clipFile(`${length}.master.wav`);
		if (inputs.length === 1) {
			ffmpeg(['-i', inputs[0], '-c:a', 'pcm_f32le', '-ar', '48000', '-ac', '2', master]);
		} else {
			const filter = `${inputs.map((_, i) => `[${i}:a]`).join('')}concat=n=${inputs.length}:v=0:a=1[a]`;
			ffmpeg([...inputs.flatMap((f) => ['-i', f]), '-filter_complex', filter, '-map', '[a]', '-c:a', 'pcm_f32le', '-ar', '48000', '-ac', '2', master]);
		}
		for (const [name, f] of Object.entries(FORMATS)) {
			ffmpeg(['-i', master, ...f.ff, clipPath(length, name)]);
		}
		// Raw 24 kHz PCM16 mono for the realtime API.
		ffmpeg(['-i', master, '-f', 's16le', '-acodec', 'pcm_s16le', '-ar', '24000', '-ac', '1', clipFile(`${length}.pcm24.raw`)]);
		const seconds = fs.statSync(clipFile(`${length}.pcm24.raw`)).size / 48000;
		const sizes = Object.keys(FORMATS).map((n) => `${n}=${(fs.statSync(clipPath(length, n)).size / 1e6).toFixed(2)}MB`);
		console.log(`${length}: ${seconds.toFixed(1)} s  ${sizes.join('  ')}`);
	}
}

function clipSeconds(length) {
	return fs.statSync(clipFile(`${length}.pcm24.raw`)).size / 48000;
}

// ---------------------------------------------------------------- stt

async function transcribe({ data, format, model, agent, prompt = STT_PROMPT }) {
	const fields = [
		['file', { filename: `recording.${FORMATS[format].ext}`, type: FORMATS[format].type, data }],
		['model', model],
		['language', LANGUAGE],
		['temperature', '0'],
	];
	if (prompt) fields.push(['prompt', prompt]);
	const { body, contentType } = multipart(fields);
	const r = await request({ path: '/v1/audio/transcriptions', key: key(), body, contentType, agent });
	const text = r.json?.text ?? null;
	return {
		status: r.status,
		t: r.t,
		bytes: body.length,
		words: wordCount(text),
		terms: countTerms(text, TERMS),
		error: r.status === 200 ? undefined : redact((r.json?.error?.message ?? r.text).slice(0, 200), KEY),
		text, // callers must not persist this
	};
}

const STT_MODELS = () => (args.models ? String(args.models).split(',') : ['gpt-transcribe', 'gpt-4o-mini-transcribe']);
const STT_LENGTHS = () => (args.lengths ? String(args.lengths).split(',') : ['short', 'medium', 'long']);
const STT_FORMATS = () => (args.formats ? String(args.formats).split(',') : Object.keys(FORMATS));
const MAX_UPLOAD = 25 * 1024 * 1024;

async function sttMatrix() {
	const cells = [];
	for (const length of STT_LENGTHS())
		for (const format of STT_FORMATS())
			for (const model of STT_MODELS()) cells.push({ length, format, model });
	const data = {};
	for (const c of cells) data[`${c.length}.${c.format}`] ??= fs.readFileSync(clipPath(c.length, c.format));
	const agent = args.cold ? null : warmAgent;
	if (agent) await preconnect(key(), agent);
	for (let rep = 0; rep < REPS; rep++) {
		for (const c of cells) {
			const buf = data[`${c.length}.${c.format}`];
			if (buf.length > MAX_UPLOAD) {
				if (rep === 0) {
					appendResult('stt.jsonl', { ...c, rep, status: 'skipped-over-25MB', bytes: buf.length, seconds: clipSeconds(c.length) });
					console.log(`${c.length} ${c.format} ${c.model}: ${(buf.length / 1e6).toFixed(1)} MB is over the 25 MB limit (skipped)`);
				}
				continue;
			}
			if (agent) await preconnect(key(), agent); // keep it warm; measures upload + server only
			const r = await transcribe({ data: buf, format: c.format, model: c.model, agent: agent ?? freshAgent() });
			const { text, ...rec } = r;
			appendResult('stt.jsonl', { ...c, rep, seconds: clipSeconds(c.length), ...rec });
			console.log(`${c.length} ${c.format} ${c.model} rep${rep}: ${r.status} total=${fmtMs(r.t.total)} ttfb=${fmtMs(r.t.ttfb)} sent=${fmtMs(r.t.sent)}${r.error ? ` ${r.error}` : ''}`);
			if (r.status === 401 || r.status === 429) {
				console.log('Stopping: key or rate-limit problem.');
				return;
			}
		}
	}
}

// ---------------------------------------------------------------- conn

async function connTest() {
	const n = Number(args.n || 8);
	// 1) Connection setup alone: a free GET on a fresh connection.
	for (let i = 0; i < n; i++) {
		const r = await request({ method: 'GET', path: '/v1/models/gpt-5.4-mini', key: key(), agent: freshAgent() });
		appendResult('conn.jsonl', { kind: 'setup', i, status: r.status, t: r.t });
		console.log(`setup ${i}: dns=${fmtMs(r.t.dns)} tcp=${fmtMs(r.t.connect)} tls=${fmtMs(r.t.tls)} ttfb=${fmtMs(r.t.ttfb)}`);
		await sleep(300);
	}
	// 2) A short transcription and a short tidy-up, cold vs warm (pre-connected).
	const audio = fs.readFileSync(clipPath('short', args.format || 'flac16'));
	for (let i = 0; i < n; i++) {
		for (const mode of i % 2 ? ['warm', 'cold'] : ['cold', 'warm']) {
			let agent = freshAgent();
			if (mode === 'warm') {
				agent = warmAgent;
				await preconnect(key(), agent);
			}
			const s = await transcribe({ data: audio, format: args.format || 'flac16', model: 'gpt-transcribe', agent });
			appendResult('conn.jsonl', { kind: 'stt-short', mode, i, status: s.status, t: s.t });
			const tidyInput = 'so I think we should um move the meeting to Thursday and like tell everyone';
			if (mode === 'warm') await preconnect(key(), agent);
			const c = await chat({ model: 'gpt-5.4-mini', system: 'Lightly clean this dictation. Return only the cleaned text.', user: tidyInput, agent: mode === 'warm' ? agent : freshAgent(), effort: 'none' });
			appendResult('conn.jsonl', { kind: 'tidy-short', mode, i, status: c.status, t: c.t });
			console.log(`${mode} ${i}: stt total=${fmtMs(s.t.total)} (tls at ${fmtMs(s.t.tls)})  tidy total=${fmtMs(c.t.total)}`);
		}
	}
}

// ---------------------------------------------------------------- tidy

async function chat({ model, system, user, agent, effort, maxTokens }) {
	const payload = {
		model,
		messages: [
			{ role: 'system', content: system },
			{ role: 'user', content: user },
		],
	};
	if (effort) payload.reasoning_effort = effort;
	if (maxTokens) payload.max_completion_tokens = maxTokens;
	const body = Buffer.from(JSON.stringify(payload));
	const r = await request({ path: '/v1/chat/completions', key: key(), body, contentType: 'application/json', agent });
	const text = r.json?.choices?.[0]?.message?.content ?? null;
	return {
		status: r.status,
		t: r.t,
		usage: r.json?.usage
			? {
					prompt: r.json.usage.prompt_tokens,
					completion: r.json.usage.completion_tokens,
					reasoning: r.json.usage.completion_tokens_details?.reasoning_tokens ?? null,
					cached: r.json.usage.prompt_tokens_details?.cached_tokens ?? null,
				}
			: null,
		finish: r.json?.choices?.[0]?.finish_reason ?? null,
		words: wordCount(text),
		terms: countTerms(text, TERMS),
		error: r.status === 200 ? undefined : redact((r.json?.error?.message ?? r.text).slice(0, 200), KEY),
		text, // callers must not persist this
	};
}

/** Reads the string literals of a Java method that returns one concatenated string. */
function javaPrompt(file, method) {
	const src = fs.readFileSync(file, 'utf8');
	const start = src.indexOf(`${method}() {`);
	need(start >= 0, `method ${method} not found in ${file}`);
	// Walk from `return` to the first `;` outside a string literal.
	const literals = [];
	const re = /"(?:[^"\\]|\\.)*"|;/g;
	re.lastIndex = src.indexOf('return', start);
	for (let m = re.exec(src); m && m[0] !== ';'; m = re.exec(src)) literals.push(m[0]);
	return literals.map((l) => JSON.parse(l)).join('');
}

function tidyPrompts() {
	const full = CONFIG.tidyPromptJava
		? javaPrompt(CONFIG.tidyPromptJava.file, CONFIG.tidyPromptJava.method)
		: fs.readFileSync(CONFIG.tidyPromptFile, 'utf8');
	const glossary = TERMS.length
		? `\n\nGlossary. Spell these names and terms exactly like this when they are meant: ${TERMS.join(', ')}.${CONFIG.glossaryNotes ? ` ${CONFIG.glossaryNotes}` : ''}`
		: '';
	const trimmed =
		'Lightly clean this dictation transcript. Remove filler (um, uh, filler "like", "you know"), false starts and accidental repeats; fix punctuation and capitalization. Keep the speaker\'s words, tone, order and hedges such as "I think". Do not summarize, rephrase or answer. Return only the cleaned text.';
	return { full: full + glossary, trimmed: trimmed + glossary };
}

const TIDY_VARIANTS = {
	'mini-default': { model: 'gpt-5.4-mini', prompt: 'full' },
	'mini-none': { model: 'gpt-5.4-mini', prompt: 'full', effort: 'none' },
	'mini-minimal': { model: 'gpt-5.4-mini', prompt: 'full', effort: 'minimal' },
	'mini-low': { model: 'gpt-5.4-mini', prompt: 'full', effort: 'low' },
	'nano-default': { model: 'gpt-5.4-nano', prompt: 'full' },
	'nano-none': { model: 'gpt-5.4-nano', prompt: 'full', effort: 'none' },
	'mini-none-trimmed': { model: 'gpt-5.4-mini', prompt: 'trimmed', effort: 'none' },
	'mini-none-bounded': { model: 'gpt-5.4-mini', prompt: 'full', effort: 'none', bound: true },
};

async function tidyTest() {
	need(CONFIG.audioDir && CONFIG.tidyInputs, 'config needs audioDir and tidyInputs (audio files to transcribe as inputs)');
	const prompts = tidyPrompts();
	await preconnect(key(), warmAgent);
	// Inputs: transcribe each clip once, kept in memory only.
	const inputs = [];
	for (const f of CONFIG.tidyInputs) {
		const data = fs.readFileSync(path.join(CONFIG.audioDir, f));
		const ext = path.extname(f).slice(1);
		const format = ext === 'ogg' || ext === 'opus' ? 'opus' : ext === 'flac' ? 'flac16' : 'wav16';
		const r = await transcribe({ data, format, model: 'gpt-transcribe', agent: warmAgent });
		if (r.status === 200) inputs.push({ id: inputs.length, text: r.text, words: r.words, terms: r.terms });
	}
	console.log(`inputs: ${inputs.map((i) => i.words).join(', ')} words`);
	const variants = (args.variants ? String(args.variants).split(',') : Object.keys(TIDY_VARIANTS)).map((name) => [name, TIDY_VARIANTS[name]]);
	const supported = [];
	for (const [name, v] of variants) {
		const probe = await chat({ model: v.model, system: prompts[v.prompt], user: `<transcript>${inputs[0].text}</transcript>`, agent: warmAgent, effort: v.effort });
		if (probe.status !== 200) {
			console.log(`${name}: not supported (${probe.status} ${probe.error})`);
			appendResult('tidy.jsonl', { variant: name, status: probe.status, error: probe.error, probe: true });
			continue;
		}
		supported.push([name, v]);
	}
	for (let rep = 0; rep < REPS; rep++) {
		for (const input of inputs) {
			for (const [name, v] of supported) {
				await preconnect(key(), warmAgent);
				const maxTokens = v.bound ? Math.ceil(input.words * 1.6) + 64 : undefined;
				const r = await chat({ model: v.model, system: prompts[v.prompt], user: `<transcript>${input.text}</transcript>`, agent: warmAgent, effort: v.effort, maxTokens });
				const { text, ...rec } = r;
				appendResult('tidy.jsonl', { variant: name, rep, input: input.id, inputWords: input.words, inputTerms: input.terms, promptChars: prompts[v.prompt].length, ...rec });
				console.log(`${name} in=${input.words}w rep${rep}: ${r.status} total=${fmtMs(r.t.total)} reasoning=${r.usage?.reasoning ?? '-'} finish=${r.finish}`);
			}
		}
	}
}

// ---------------------------------------------------------------- live

async function liveOnce({ length, delay, useContext, run }) {
	const pcm = fs.readFileSync(clipFile(`${length}.pcm24.raw`));
	const url = 'wss://api.openai.com/v1/realtime?intent=transcription';
	const ws = globalThis.Bun
		? new WebSocket(url, { headers: { Authorization: `Bearer ${key()}` } })
		: new WebSocket(url, ['realtime', `openai-insecure-api-key.${key()}`]);
	const events = [];
	let finalText = '';
	let deltas = '';
	let tEnd = null;
	let tFirstDelta = null;
	let tLastDelta = null;
	let tCompleted = null;
	let error = null;
	const t0 = performance.now();
	const done = new Promise((resolve) => {
		ws.onmessage = (m) => {
			const e = JSON.parse(typeof m.data === 'string' ? m.data : Buffer.from(m.data).toString('utf8'));
			const now = performance.now() - t0;
			events.push(e.type);
			if (e.type === 'conversation.item.input_audio_transcription.delta') {
				tFirstDelta ??= now;
				tLastDelta = now;
				deltas += e.delta ?? '';
			} else if (e.type === 'conversation.item.input_audio_transcription.completed') {
				tCompleted = now;
				finalText = e.transcript ?? '';
				resolve();
			} else if (e.type === 'error') {
				error = redact(JSON.stringify(e.error).slice(0, 300), KEY);
				if (tEnd !== null) resolve();
			}
		};
		ws.onclose = () => resolve();
		ws.onerror = () => {
			error ??= 'websocket error';
			resolve();
		};
	});
	await new Promise((resolve, reject) => {
		ws.onopen = resolve;
		setTimeout(() => reject(new Error('connect timeout')), 10_000);
	});
	const tOpen = performance.now() - t0;
	const transcription = { model: 'gpt-live-transcribe', languages: [LANGUAGE] };
	if (delay) transcription.delay = delay;
	if (useContext && STT_PROMPT) transcription.prompt = STT_PROMPT;
	if (useContext && TERMS.length) transcription.keywords = TERMS;
	ws.send(
		JSON.stringify({
			type: 'session.update',
			session: { type: 'transcription', audio: { input: { format: { type: 'audio/pcm', rate: 24000 }, transcription, turn_detection: null } } },
		}),
	);
	// Stream at real time in 100 ms chunks.
	const chunk = 4800; // 100 ms of 24 kHz 16-bit mono
	const start = performance.now();
	for (let off = 0, i = 0; off < pcm.length; off += chunk, i++) {
		ws.send(JSON.stringify({ type: 'input_audio_buffer.append', audio: pcm.subarray(off, off + chunk).toString('base64') }));
		const wait = start + (i + 1) * 100 - performance.now();
		if (wait > 0) await sleep(wait);
		if (error) break;
	}
	tEnd = performance.now() - t0; // "release": the last audio has been sent
	ws.send(JSON.stringify({ type: 'input_audio_buffer.commit' }));
	await Promise.race([done, sleep(20_000)]);
	ws.close();
	const rec = {
		length,
		delay: delay ?? null,
		context: !!useContext,
		run,
		seconds: pcm.length / 48000,
		openMs: tOpen,
		endToFinalMs: tCompleted !== null ? tCompleted - tEnd : null,
		endToLastDeltaMs: tLastDelta !== null ? tLastDelta - tEnd : null,
		firstDeltaAfterStartMs: tFirstDelta,
		/** True when the streamed deltas already equal the final text (a count of words differs otherwise). */
		deltasMatchFinal: deltas.trim() === finalText.trim(),
		deltaWordsMissing: wordCount(finalText) - wordCount(deltas),
		words: wordCount(finalText),
		terms: countTerms(finalText, TERMS),
		error,
		eventTypes: [...new Set(events)],
	};
	return rec;
}

async function liveTest() {
	const lengths = args.lengths ? String(args.lengths).split(',') : ['short', 'medium'];
	const delays = args.delays ? String(args.delays).split(',').map((d) => (d === 'default' ? null : d)) : ['low'];
	const runs = Number(args.n || 3);
	for (let run = 0; run < runs; run++)
		for (const length of lengths)
			for (const delay of delays) {
				const rec = await liveOnce({ length, delay, useContext: !args['no-context'], run });
				appendResult('live.jsonl', rec);
				console.log(`${length} delay=${delay} run${run}: end->final=${fmtMs(rec.endToFinalMs)} ms, open=${fmtMs(rec.openMs)}${rec.error ? ` error=${rec.error}` : ''}`);
				if (rec.error && /auth|invalid_api_key|not.*found|model/i.test(rec.error) && rec.endToFinalMs === null) return;
			}
}

// ---------------------------------------------------------------- local

function localTest() {
	const n = Number(args.n || 5);
	// 1) tauri-plugin-http 2.5.x sends a request body over IPC as a JSON array
	//    of numbers: Array.from(new Uint8Array(body)). Time that encode and a
	//    JSON parse (stand-in for the Rust side's serde parse).
	for (const length of ['short', 'medium', 'long']) {
		for (const format of Object.keys(FORMATS)) {
			const buf = fs.readFileSync(clipPath(length, format));
			if (buf.length > MAX_UPLOAD * 2) continue;
			const times = [];
			const parses = [];
			for (let i = 0; i < n; i++) {
				const t0 = performance.now();
				const arr = Array.from(new Uint8Array(buf.buffer, buf.byteOffset, buf.length));
				const json = JSON.stringify({ clientConfig: { data: arr } });
				const t1 = performance.now();
				JSON.parse(json);
				const t2 = performance.now();
				times.push(t1 - t0);
				parses.push(t2 - t1);
			}
			appendResult('local.jsonl', { kind: 'ipc-body', length, format, bytes: buf.length, encode: times, parse: parses });
			console.log(`ipc ${length} ${format} ${(buf.length / 1e6).toFixed(2)} MB: encode p50=${fmtMs(summarize(times).p50)} parse p50=${fmtMs(summarize(parses).p50)}`);
		}
	}
	// 2) FFmpeg compression the way the app does it today: write the WAV to a
	//    temp file, spawn ffmpeg with the default options, read the result.
	const spawnOnly = [];
	for (let i = 0; i < n; i++) {
		const t0 = performance.now();
		spawnSync(FFMPEG, ['-version'], { windowsHide: true });
		spawnOnly.push(performance.now() - t0);
	}
	appendResult('local.jsonl', { kind: 'ffmpeg-spawn', times: spawnOnly });
	console.log(`ffmpeg spawn only p50=${fmtMs(summarize(spawnOnly).p50)}`);
	const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vfbench-'));
	for (const length of ['short', 'medium', 'long']) {
		const wav = fs.readFileSync(clipPath(length, 'wav48f32'));
		const times = [];
		for (let i = 0; i < n; i++) {
			const t0 = performance.now();
			const input = path.join(tmp, `in-${i}.wav`);
			const output = path.join(tmp, `out-${i}.opus`);
			fs.writeFileSync(input, wav);
			fs.readFileSync(input); // the app reads it back once to "verify"
			ffmpeg(['-i', input, ...APP_COMPRESSION.split(' '), output]);
			fs.readFileSync(output);
			times.push(performance.now() - t0);
		}
		appendResult('local.jsonl', { kind: 'ffmpeg-compress', length, bytes: wav.length, times });
		console.log(`ffmpeg compress ${length}: p50=${fmtMs(summarize(times).p50)} p90=${fmtMs(summarize(times).p90)}`);
	}
}

// ---------------------------------------------------------------- report

function table(headers, rows) {
	const lines = [`| ${headers.join(' | ')} |`, `|${headers.map(() => '---').join('|')}|`];
	for (const r of rows) lines.push(`| ${r.join(' | ')} |`);
	return lines.join('\n');
}

function sumTerms(records) {
	return records.map((r) => Object.values(r.terms ?? {}).reduce((a, b) => a + b, 0));
}

function report() {
	const sections = [];
	const stt = readResults('stt.jsonl');
	if (stt.length) {
		const groups = new Map();
		for (const r of stt) {
			const k = `${r.length}|${r.format}|${r.model}`;
			if (!groups.has(k)) groups.set(k, []);
			groups.get(k).push(r);
		}
		const rows = [];
		for (const [k, rs] of groups) {
			const [length, format, model] = k.split('|');
			const ok = rs.filter((r) => r.status === 200);
			if (!ok.length) {
				rows.push([length, `${rs[0].seconds?.toFixed(0)} s`, format, model, `${(rs[0].bytes / 1e6).toFixed(2)}`, rs[0].status, '-', '-', '-', '-', '-']);
				continue;
			}
			const total = summarize(ok.map((r) => r.t.total));
			const server = summarize(ok.map((r) => r.t.ttfb - r.t.sent));
			const sent = summarize(ok.map((r) => r.t.sent));
			const names = summarize(sumTerms(ok));
			rows.push([length, `${ok[0].seconds.toFixed(0)} s`, format, model, (ok[0].bytes / 1e6).toFixed(2), `${ok.length}/${rs.length}`, `${fmtMs(total.p50)} / ${fmtMs(total.p90)}`, fmtMs(sent.p50), fmtMs(server.p50), names.p50, names.min === names.max ? `${names.min}` : `${names.min}-${names.max}`]);
		}
		sections.push(`## Upload + transcription (warm connection)\n\n${table(['Clip', 'Audio', 'Format', 'Model', 'MB', 'OK', 'Total p50 / p90 ms', 'Body sent p50 ms', 'After upload p50 ms', 'Name hits p50', 'Name hits range'], rows)}`);
	}
	const conn = readResults('conn.jsonl');
	if (conn.length) {
		const setup = conn.filter((r) => r.kind === 'setup');
		const rows = [];
		if (setup.length) {
			rows.push(['Fresh connection: DNS', `${fmtMs(summarize(setup.map((r) => r.t.dns)).p50)} / ${fmtMs(summarize(setup.map((r) => r.t.dns)).p90)}`]);
			rows.push(['Fresh connection: TCP connected', `${fmtMs(summarize(setup.map((r) => r.t.connect)).p50)} / ${fmtMs(summarize(setup.map((r) => r.t.connect)).p90)}`]);
			rows.push(['Fresh connection: TLS done', `${fmtMs(summarize(setup.map((r) => r.t.tls)).p50)} / ${fmtMs(summarize(setup.map((r) => r.t.tls)).p90)}`]);
			rows.push(['GET on fresh connection, total', `${fmtMs(summarize(setup.map((r) => r.t.total)).p50)} / ${fmtMs(summarize(setup.map((r) => r.t.total)).p90)}`]);
		}
		for (const kind of ['stt-short', 'tidy-short'])
			for (const mode of ['cold', 'warm']) {
				const rs = conn.filter((r) => r.kind === kind && r.mode === mode && r.status === 200);
				if (!rs.length) continue;
				const s = summarize(rs.map((r) => r.t.total));
				rows.push([`${kind} ${mode} (n=${rs.length})`, `${fmtMs(s.p50)} / ${fmtMs(s.p90)}`]);
			}
		sections.push(`## Connections to api.openai.com\n\n${table(['Measure', 'p50 / p90 ms'], rows)}`);
	}
	const tidy = readResults('tidy.jsonl').filter((r) => !r.probe);
	const probes = readResults('tidy.jsonl').filter((r) => r.probe);
	if (tidy.length || probes.length) {
		const rows = [];
		const names = [...new Set(tidy.map((r) => r.variant))];
		for (const v of names) {
			const rs = tidy.filter((r) => r.variant === v && r.status === 200);
			const s = summarize(rs.map((r) => r.t.total));
			const reasoning = summarize(rs.map((r) => r.usage?.reasoning ?? 0));
			const prompt = summarize(rs.map((r) => r.usage?.prompt));
			const cut = rs.filter((r) => r.finish === 'length').length;
			const lostNames = rs.filter((r) => sumTerms([{ terms: r.inputTerms }])[0] > sumTerms([r])[0]).length;
			rows.push([v, rs.length, `${fmtMs(s.p50)} / ${fmtMs(s.p90)}`, prompt.p50 ?? '-', reasoning.p50 ?? '-', cut, lostNames]);
		}
		for (const p of probes) rows.push([p.variant, 0, `not supported (${p.status})`, '-', '-', '-', '-']);
		const short = tidy.filter((r) => r.status === 200 && r.inputWords <= 25);
		sections.push(`## Tidy-up (warm connection)\n\n${table(['Variant', 'n', 'Total p50 / p90 ms', 'Prompt tokens p50', 'Reasoning tokens p50', 'Cut off by bound', 'Outputs with fewer name hits than input'], rows)}${short.length ? `\n\nInputs of 25 words or fewer: ${short.length} samples.` : ''}`);
	}
	const live = readResults('live.jsonl');
	if (live.length) {
		const groups = new Map();
		for (const r of live) {
			const k = `${r.length}|${r.delay}|${r.context}`;
			if (!groups.has(k)) groups.set(k, []);
			groups.get(k).push(r);
		}
		const rows = [];
		for (const [k, rs] of groups) {
			const [length, delay, context] = k.split('|');
			const ok = rs.filter((r) => r.endToFinalMs !== null);
			const s = summarize(ok.map((r) => r.endToFinalMs));
			const names = summarize(sumTerms(ok));
			rows.push([length, `${rs[0].seconds.toFixed(0)} s`, delay, context, `${ok.length}/${rs.length}`, `${fmtMs(s.p50)} / ${fmtMs(s.p90)}`, fmtMs(summarize(ok.map((r) => r.openMs)).p50), names.p50 ?? '-', rs.find((r) => r.error)?.error ?? '']);
		}
		sections.push(`## Realtime transcription (gpt-live-transcribe)\n\n${table(['Clip', 'Audio', 'Delay', 'Prompt + keywords', 'OK', 'End of audio -> final p50 / p90 ms', 'Connect + open p50 ms', 'Name hits p50', 'Error'], rows)}`);
	}
	const local = readResults('local.jsonl');
	if (local.length) {
		const rows = [];
		for (const r of local.filter((r) => r.kind === 'ipc-body')) rows.push([`IPC body encode (Array.from + JSON) ${r.length} ${r.format} ${(r.bytes / 1e6).toFixed(2)} MB`, `${fmtMs(summarize(r.encode).p50)} / ${fmtMs(summarize(r.encode).p90)}`, `${fmtMs(summarize(r.parse).p50)}`]);
		for (const r of local.filter((r) => r.kind === 'ffmpeg-spawn')) rows.push(['FFmpeg process spawn only', `${fmtMs(summarize(r.times).p50)} / ${fmtMs(summarize(r.times).p90)}`, '']);
		for (const r of local.filter((r) => r.kind === 'ffmpeg-compress')) rows.push([`FFmpeg compression (app default options) ${r.length}`, `${fmtMs(summarize(r.times).p50)} / ${fmtMs(summarize(r.times).p90)}`, '']);
		sections.push(`## Local costs\n\n${table(['Measure', 'p50 / p90 ms', 'JSON parse p50 ms'], rows)}`);
	}
	console.log(sections.join('\n\n'));
}

// ---------------------------------------------------------------- main

const COMMANDS = { clips: buildClips, stt: sttMatrix, conn: connTest, tidy: tidyTest, live: liveTest, local: localTest, report };
if (!COMMANDS[command]) {
	console.log('usage: node bench.mjs <clips|stt|conn|tidy|live|local|report> --config <file> --out <dir> [--n 5] [--cred <target>]');
	process.exit(command ? 2 : 0);
}
await COMMANDS[command]();
