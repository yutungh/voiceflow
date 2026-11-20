#!/usr/bin/env node
/**
 * VoiceFlow sound generator.
 *
 * Synthesises every app sound from code: no samples, no recordings, no
 * third-party audio. Deterministic: the same script always writes the same
 * bytes (seeded noise, seeded dither), so the WAVs in the app can be
 * regenerated and diffed.
 *
 * Three sets, five cues each:
 *   glass   - clear, airy: sine with soft upper partials, quick bright attack,
 *             short shimmer tail. Key: E major, around 660-1320 Hz.
 *   soft    - warm, muted mallet (marimba-like): rounded attack, low-passed,
 *             partials at 1x/4x/10x. Key: C major, around 390-780 Hz.
 *   minimal - tiny crisp ticks with a hint of pitch. Key: D/A, ticks of
 *             10-30 ms.
 * Cues: start (rising), stop (falling), done (gentle positive chime/pop),
 * error (low, soft double tap), cancel (short muted down-tick).
 *
 * Every file: 48 kHz, mono, 16-bit PCM WAV, <= 300 ms including the reverb
 * tail, faded in and out, loudness-matched (K-weighted energy over one
 * 400 ms block, i.e. EBU momentary loudness, to TARGET_LUFS) with sample
 * peaks <= PEAK_CEILING_DB.
 *
 * Usage (Node 18+ or Bun, no dependencies):
 *   node desktop/tools/sounds/generate.mjs [outDir]
 * Default outDir: the app's sound asset folder. Writes <outDir>/<set>/<cue>.wav
 * and prints one line per file (length, loudness, peak).
 *
 * The generated sounds are original works of the VoiceFlow project,
 * dedicated to the public domain under CC0 1.0. This script is part of
 * VoiceFlow Desktop (AGPL-3.0).
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const SR = 48_000;
export const MAX_MS = 300;
export const TARGET_LUFS = -20;
export const PEAK_CEILING_DB = -3;
export const SETS = ['glass', 'soft', 'minimal'];
export const CUES = ['start', 'stop', 'done', 'error', 'cancel'];

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_OUT = resolve(
	HERE,
	'../../apps/whispering/src/lib/services/isomorphic/sound/assets',
);

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

/** Seeded PRNG (mulberry32): deterministic noise and dither. */
function rng(seed) {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = a;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

const ms = (x) => Math.round((x / 1000) * SR);
const note = (name) => {
	const n = { C: -9, D: -7, E: -5, F: -4, G: -2, A: 0, B: 2 };
	const m = /^([A-G])(#|b)?(\d)$/.exec(name);
	if (!m) throw new Error(`bad note ${name}`);
	const semis = n[m[1]] + (m[2] === '#' ? 1 : m[2] === 'b' ? -1 : 0) + (Number(m[3]) - 4) * 12;
	return 440 * 2 ** (semis / 12);
};

/** RBJ biquad, run in place. */
function biquad(buf, kind, f0, q = Math.SQRT1_2) {
	const w = (2 * Math.PI * f0) / SR;
	const cos = Math.cos(w);
	const alpha = Math.sin(w) / (2 * q);
	let b0, b1, b2;
	if (kind === 'lp') {
		b0 = (1 - cos) / 2;
		b1 = 1 - cos;
		b2 = (1 - cos) / 2;
	} else if (kind === 'hp') {
		b0 = (1 + cos) / 2;
		b1 = -(1 + cos);
		b2 = (1 + cos) / 2;
	} else if (kind === 'bp') {
		b0 = alpha;
		b1 = 0;
		b2 = -alpha;
	} else throw new Error(kind);
	const a0 = 1 + alpha;
	const a1 = -2 * cos;
	const a2 = 1 - alpha;
	return iir(buf, [b0 / a0, b1 / a0, b2 / a0], [1, a1 / a0, a2 / a0]);
}

function iir(buf, b, a) {
	let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
	for (let i = 0; i < buf.length; i++) {
		const x = buf[i];
		const y = b[0] * x + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2;
		x2 = x1;
		x1 = x;
		y2 = y1;
		y1 = y;
		buf[i] = y;
	}
	return buf;
}

/**
 * One struck tone added into `out` at `atMs`.
 * - partials: [{ ratio, amp, decay }] (decay multiplies the time constant)
 * - attackMs: raised-cosine attack; tauMs: exponential decay time constant
 * - glide: optional end frequency ratio reached over glideMs (exponential)
 * - detune: second, slightly sharp copy of each partial (shimmer beating)
 */
function tone(out, atMs, freq, o) {
	const {
		partials = [{ ratio: 1, amp: 1, decay: 1 }],
		attackMs = 3,
		tauMs = 80,
		amp = 1,
		glide = 1,
		glideMs = 60,
		detune = 0,
		lengthMs = 260,
	} = o;
	const start = ms(atMs);
	const len = Math.min(ms(lengthMs), out.length - start);
	const att = Math.max(1, ms(attackMs));
	for (const p of partials) {
		const copies = detune ? [1, 1 + detune] : [1];
		for (const c of copies) {
			let phase = 0;
			const tau = (tauMs * p.decay) / 1000;
			for (let i = 0; i < len; i++) {
				const t = i / SR;
				const g = glide === 1 ? 1 : glide ** Math.min(1, (i / ms(glideMs)));
				const f = freq * p.ratio * c * g;
				if (f >= SR / 2 - 2000) break; // never alias
				phase += (2 * Math.PI * f) / SR;
				const env =
					(i < att ? 0.5 - 0.5 * Math.cos((Math.PI * i) / att) : 1) * Math.exp(-t / tau);
				out[start + i] += (amp * p.amp * env * Math.sin(phase)) / copies.length;
			}
		}
	}
}

/** A short noise burst (mallet thump or tick click), band-shaped. */
function burst(out, atMs, o) {
	const { seed = 1, tauMs = 3, amp = 0.2, lp = 0, hp = 0, bp = 0, q = 1, lengthMs = 30 } = o;
	const r = rng(seed);
	const len = ms(lengthMs);
	const b = new Float64Array(len);
	for (let i = 0; i < len; i++) b[i] = (r() * 2 - 1) * Math.exp(-i / SR / (tauMs / 1000));
	b[0] *= 0.25;
	if (lp) biquad(b, 'lp', lp);
	if (hp) biquad(b, 'hp', hp);
	if (bp) biquad(b, 'bp', bp, q);
	const start = ms(atMs);
	for (let i = 0; i < len && start + i < out.length; i++) out[start + i] += amp * b[i];
}

/**
 * Small, deterministic room: the dry sound convolved with an exponentially
 * decaying, band-shaped noise impulse (pre-delay, RT60 rtMs), mixed at `wet`.
 */
function reverb(dry, { seed, rtMs, wet, lp, preMs = 7 }) {
	const r = rng(seed);
	const irLen = ms(rtMs);
	const tau = rtMs / 1000 / 6.91; // -60 dB at rtMs
	const ir = new Float64Array(irLen);
	const pre = ms(preMs);
	for (let i = pre; i < irLen; i++) ir[i] = (r() * 2 - 1) * Math.exp(-(i - pre) / SR / tau);
	biquad(ir, 'lp', lp);
	biquad(ir, 'hp', 250);
	let e = 0;
	for (const v of ir) e += v * v;
	const norm = 1 / Math.sqrt(e);
	const out = new Float64Array(dry.length);
	for (let i = 0; i < dry.length; i++) {
		const x = dry[i];
		if (x === 0) continue;
		const n = Math.min(irLen, dry.length - i);
		for (let k = 0; k < n; k++) out[i + k] += x * ir[k] * norm;
	}
	for (let i = 0; i < dry.length; i++) out[i] = dry[i] * (1 - wet * 0.5) + out[i] * wet;
	return out;
}

/** Fades and trims: zero at both ends, so nothing clicks. */
function finish(buf, lengthMs) {
	const len = Math.min(ms(lengthMs), ms(MAX_MS));
	const out = buf.slice(0, len);
	const fadeIn = ms(0.5);
	const fadeOut = ms(Math.min(40, lengthMs / 4));
	for (let i = 0; i < fadeIn; i++) out[i] *= i / fadeIn;
	for (let i = 0; i < fadeOut; i++) out[len - 1 - i] *= 0.5 - 0.5 * Math.cos((Math.PI * i) / fadeOut);
	return out;
}

// ---------------------------------------------------------------------------
// Loudness (ITU-R BS.1770 K-weighting at 48 kHz) and normalisation
// ---------------------------------------------------------------------------

const K1 = {
	b: [1.53512485958697, -2.69169618940638, 1.19839281085285],
	a: [1, -1.69065929318241, 0.73248077421585],
};
const K2 = { b: [1, -2, 1], a: [1, -1.99004745483398, 0.99007225036621] };

/**
 * Momentary loudness of a clip that fits in one 400 ms block: the K-weighted
 * energy of the whole clip over a 400 ms window, in LUFS. For sounds this
 * short, this is the loudness a listener integrates (EBU momentary).
 */
export function momentaryLufs(samples) {
	const y = iir(iir(Float64Array.from(samples), K1.b, K1.a), K2.b, K2.a);
	let e = 0;
	for (const v of y) e += v * v;
	return -0.691 + 10 * Math.log10(e / ms(400) + 1e-20);
}

export function peakDb(samples) {
	let p = 0;
	for (const v of samples) p = Math.max(p, Math.abs(v));
	return 20 * Math.log10(p + 1e-20);
}

function normalise(buf) {
	const gain = 10 ** ((TARGET_LUFS - momentaryLufs(buf)) / 20);
	let out = buf.map((v) => v * gain);
	const over = peakDb(out) - PEAK_CEILING_DB;
	if (over > 0) out = out.map((v) => v * 10 ** (-over / 20));
	return out;
}

// ---------------------------------------------------------------------------
// The three sets
// ---------------------------------------------------------------------------

function blank() {
	return new Float64Array(ms(MAX_MS + 200));
}

/** Glass: sine + soft upper partials, 2 ms attack, shimmer (detune + room). */
const GLASS_PARTIALS = [
	{ ratio: 1, amp: 1, decay: 1 },
	{ ratio: 2, amp: 0.16, decay: 0.55 },
	{ ratio: 3, amp: 0.05, decay: 0.4 },
	{ ratio: 5.04, amp: 0.018, decay: 0.3 },
];
function glass(cue) {
	const b = blank();
	const t = (at, n, o = {}) =>
		tone(b, at, note(n), { partials: GLASS_PARTIALS, attackMs: 2, tauMs: 70, detune: 0.0022, ...o });
	let length = 240;
	switch (cue) {
		case 'start':
			t(0, 'E5', { amp: 0.8, tauMs: 55 });
			t(70, 'B5', { tauMs: 75 });
			length = 250;
			break;
		case 'stop':
			t(0, 'B5', { amp: 0.85, tauMs: 55 });
			t(70, 'E5', { tauMs: 70 });
			length = 250;
			break;
		case 'done':
			t(0, 'G#5', { amp: 0.7, tauMs: 45 });
			t(45, 'B5', { amp: 0.75, tauMs: 50 });
			t(90, 'E6', { tauMs: 85 });
			length = 290;
			break;
		case 'error': {
			const low = [
				{ ratio: 1, amp: 1, decay: 1 },
				{ ratio: 2, amp: 0.1, decay: 0.5 },
			];
			t(0, 'E4', { partials: low, tauMs: 40, attackMs: 4 });
			t(115, 'E4', { partials: low, tauMs: 45, attackMs: 4, amp: 0.85 });
			length = 260;
			break;
		}
		case 'cancel':
			t(0, 'B5', { tauMs: 40, glide: note('F#5') / note('B5'), glideMs: 70, attackMs: 3 });
			length = 170;
			break;
	}
	let out = reverb(b, { seed: 11, rtMs: 280, wet: 0.24, lp: 9000 });
	if (cue === 'error' || cue === 'cancel') biquad(out, 'lp', 3200);
	return finish(out, length);
}

/** Soft: mallet on wood - partials 1/4/10, rounded 5 ms attack, low-passed. */
const SOFT_PARTIALS = [
	{ ratio: 1, amp: 1, decay: 1 },
	{ ratio: 3.98, amp: 0.22, decay: 0.3 },
	{ ratio: 9.9, amp: 0.035, decay: 0.12 },
];
function soft(cue) {
	const b = blank();
	const t = (at, n, o = {}) => {
		tone(b, at, note(n), { partials: SOFT_PARTIALS, attackMs: 5, tauMs: 85, ...o });
		burst(b, at, { seed: 7 + at, tauMs: 2.5, amp: 0.05 * (o.amp ?? 1), lp: 1200 });
	};
	let length = 240;
	switch (cue) {
		case 'start':
			t(0, 'G4', { amp: 0.85, tauMs: 70 });
			t(75, 'C5', { tauMs: 90 });
			length = 260;
			break;
		case 'stop':
			t(0, 'C5', { amp: 0.85, tauMs: 70 });
			t(75, 'G4', { tauMs: 90 });
			length = 260;
			break;
		case 'done':
			t(0, 'C5', { amp: 0.7, tauMs: 55 });
			t(45, 'E5', { amp: 0.75, tauMs: 60 });
			t(90, 'G5', { tauMs: 100 });
			length = 290;
			break;
		case 'error':
			t(0, 'A3', { tauMs: 50, attackMs: 6 });
			t(120, 'A3', { tauMs: 55, attackMs: 6, amp: 0.85 });
			length = 270;
			break;
		case 'cancel':
			t(0, 'G4', { tauMs: 45, glide: note('E4') / note('G4'), glideMs: 70 });
			length = 170;
			break;
	}
	biquad(b, 'lp', cue === 'error' || cue === 'cancel' ? 1500 : 2400);
	const out = reverb(b, { seed: 23, rtMs: 240, wet: 0.16, lp: 3500 });
	return finish(out, length);
}

/** Minimal: 10-30 ms ticks - a pitched blip plus a faint band-passed click. */
function minimal(cue) {
	const b = blank();
	const tick = (at, n, o = {}) => {
		tone(b, at, note(n), {
			partials: [
				{ ratio: 1, amp: 1, decay: 1 },
				{ ratio: 2, amp: 0.12, decay: 0.5 },
			],
			attackMs: 0.6,
			tauMs: 14,
			lengthMs: 80,
			...o,
		});
		burst(b, at, { seed: 31 + at, tauMs: 1.2, amp: 0.12 * (o.amp ?? 1), bp: 5000, q: 1.2, lengthMs: 12 });
	};
	let length = 120;
	switch (cue) {
		case 'start':
			tick(0, 'A6', { amp: 0.85 });
			tick(55, 'D7');
			length = 110;
			break;
		case 'stop':
			tick(0, 'D7', { amp: 0.85 });
			tick(55, 'A6');
			length = 110;
			break;
		case 'done':
			// A tick, then a small rising "pop" (bubble-like pitch sweep).
			tick(0, 'A6', { amp: 0.7 });
			tone(b, 45, note('D6'), { attackMs: 1.5, tauMs: 26, glide: 2, glideMs: 22, lengthMs: 120 });
			length = 160;
			break;
		case 'error':
			tick(0, 'D5', { tauMs: 26, attackMs: 1.2 });
			tick(90, 'D5', { tauMs: 26, attackMs: 1.2, amp: 0.85 });
			length = 150;
			break;
		case 'cancel':
			tick(0, 'D6', { tauMs: 30, glide: note('A5') / note('D6'), glideMs: 40, attackMs: 1.5 });
			length = 110;
			break;
	}
	const out = reverb(b, { seed: 41, rtMs: 120, wet: 0.08, lp: 7000 });
	return finish(out, length);
}

const SYNTHS = { glass, soft, minimal };

/** All 15 clips as normalised Float64Arrays: { set: { cue: samples } }. */
export function renderAll() {
	const out = {};
	for (const set of SETS) {
		out[set] = {};
		for (const cue of CUES) out[set][cue] = normalise(SYNTHS[set](cue));
	}
	return out;
}

// ---------------------------------------------------------------------------
// WAV (16-bit PCM, mono, TPDF dither from a fixed seed)
// ---------------------------------------------------------------------------

export function encodeWav(samples, seed = 99) {
	const r = rng(seed);
	const n = samples.length;
	const buf = Buffer.alloc(44 + n * 2);
	buf.write('RIFF', 0);
	buf.writeUInt32LE(36 + n * 2, 4);
	buf.write('WAVE', 8);
	buf.write('fmt ', 12);
	buf.writeUInt32LE(16, 16);
	buf.writeUInt16LE(1, 20); // PCM
	buf.writeUInt16LE(1, 22); // mono
	buf.writeUInt32LE(SR, 24);
	buf.writeUInt32LE(SR * 2, 28);
	buf.writeUInt16LE(2, 32);
	buf.writeUInt16LE(16, 34);
	buf.write('data', 36);
	buf.writeUInt32LE(n * 2, 40);
	for (let i = 0; i < n; i++) {
		const d = r() - r(); // TPDF, +-1 LSB
		const edge = i < 48 || i >= n - 48; // keep the ends at exactly zero
		const v = Math.round(samples[i] * 32767 + (edge ? 0 : d));
		buf.writeInt16LE(Math.max(-32768, Math.min(32767, v)), 44 + i * 2);
	}
	return buf;
}

function main() {
	const outDir = resolve(process.argv[2] ?? DEFAULT_OUT);
	const clips = renderAll();
	for (const set of SETS) {
		mkdirSync(join(outDir, set), { recursive: true });
		for (const cue of CUES) {
			const s = clips[set][cue];
			writeFileSync(join(outDir, set, `${cue}.wav`), encodeWav(s));
			console.log(
				`${set}/${cue}.wav  ${((s.length / SR) * 1000).toFixed(0)} ms  ` +
					`${momentaryLufs(s).toFixed(1)} LUFS(M)  peak ${peakDb(s).toFixed(1)} dBFS`,
			);
		}
	}
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
