/**
 * Scores the stored answers and writes the results.
 *
 *   bun report.ts --sets tts-coral,tts-ash --label pilot-synthetic
 *   bun report.ts --sets real --label real
 *
 * Writes results/<label>/results.json (every answer, scored), summary.md (the
 * tables) and chart.json (medians with min and max, ready for a chart).
 * Each number is per run; the summary shows the median of the runs and the
 * lowest and highest run.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { ARMS, type ArmId } from './lib/arms';
import { constructedTranscript, LIST_SIZES, SENTENCES, WORDS, type Sentence } from './lib/data';
import { BENCH_DIR } from './lib/paths';
import { designatedRight, median, neverSaid, unwrap, wordErrors, type Format } from './lib/score';
import { load, speechOutput } from './lib/store';

const argOf = (name: string, fallback?: string) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1]! : fallback;
};
const sets = argOf('sets', '')!.split(',').filter(Boolean);
const label = argOf('label', sets.join('+'))!;
const runs = Number(argOf('runs', '3'));
if (!sets.length) throw new Error('--sets a,b');

const isName = (w?: string) => WORDS.find((x) => x.text === w)?.kind === 'person';

type Row = { set: string; id: string; type: Sentence['type']; output: string | null; format?: Format; raw?: string | null; error?: string };

/** A stored answer as a scored row: the text inside any wrapper, and its shape. */
function row(set: string, s: Sentence, got: { text: string | null; error?: string }): Row {
	if (got.error) return { set, id: s.id, type: s.type, output: null, error: got.error };
	const u = unwrap(got.text);
	return { set, id: s.id, type: s.type, output: u.text, format: u.format, raw: u.format === 'ok' ? undefined : got.text };
}

/** The per-run numbers for one group of answers. */
function measure(rows: Row[]) {
	const s = (id: string) => SENTENCES.find((x) => x.id === id)!;
	let tRight = 0, tN = 0, nameRight = 0, nameN = 0, prodRight = 0, prodN = 0, pRight = 0, pN = 0;
	let never = 0, neverSentences = 0, neverControls = 0, controlN = 0, errs = 0, words = 0, empty = 0, wrapped = 0, failed = 0;
	const neverWords: Record<string, number> = {};
	for (const r of rows) {
		const sen = s(r.id);
		if (r.output === null) {
			failed++;
			continue;
		}
		if (r.format === 'empty') empty++;
		if (r.format === 'wrapped') wrapped++;
		const right = designatedRight(sen, r.output);
		if (sen.type === 'target') {
			tN++;
			if (right) tRight++;
			if (isName(sen.word)) {
				nameN++;
				if (right) nameRight++;
			} else {
				prodN++;
				if (right) prodRight++;
			}
		}
		if (sen.type === 'protected') {
			pN++;
			if (right) pRight++;
		}
		const ns = neverSaid(sen, r.output);
		const n = Object.values(ns).reduce((a, b) => a + b, 0);
		never += n;
		if (n) neverSentences++;
		if (sen.type === 'control') {
			controlN++;
			if (n) neverControls++;
		}
		for (const [w, c] of Object.entries(ns)) neverWords[w] = (neverWords[w] ?? 0) + c;
		const we = wordErrors(sen, r.output);
		errs += we.errors;
		words += we.words;
	}
	return {
		targetsRight: tRight, targets: tN,
		namesRight: nameRight, names: nameN,
		productsRight: prodRight, products: prodN,
		protectedRight: pRight, protected: pN,
		neverSaid: never, sentencesWithNeverSaid: neverSentences,
		controlsWithNeverSaid: neverControls, controls: controlN,
		wer: words ? errs / words : 0,
		neverSaidWords: neverWords, empty, wrapped, failed,
	};
}

/** Right->wrong and wrong->right against the frozen transcript, per designated word. */
function transitions(rows: (Row & { input: string | null })[]) {
	let rightIn = 0, wrongIn = 0, wrongCorrections = 0, intoPair = 0, fixes = 0;
	const examples: string[] = [];
	for (const r of rows) {
		const sen = SENTENCES.find((x) => x.id === r.id)!;
		if (!sen.word || r.output === null || r.input === null) continue;
		const before = designatedRight(sen, r.input);
		const after = designatedRight(sen, r.output);
		if (before) {
			rightIn++;
			if (!after) {
				wrongCorrections++;
				if (sen.type === 'protected' && sen.pair && neverSaid(sen, r.output)[sen.pair]) intoPair++;
				if (examples.length < 8) examples.push(`${r.set}/${r.id}: "${r.input}" -> "${r.output}"`);
			}
		} else {
			wrongIn++;
			if (after) fixes++;
		}
	}
	return { rightIn, wrongIn, wrongCorrections, intoPair, fixes, examples };
}

type Stat = { median: number; min: number; max: number; runs: number[] };
const stat = (xs: number[]): Stat => ({ median: median(xs), min: Math.min(...xs), max: Math.max(...xs), runs: xs });
const fmt = (x: Stat, d = 0) => `${x.median.toFixed(d)} [${x.min.toFixed(d)}–${x.max.toFixed(d)}]`;
const pct = (x: Stat) => `${(x.median * 100).toFixed(1)}% [${(x.min * 100).toFixed(1)}–${(x.max * 100).toFixed(1)}]`;

// ---- speech ------------------------------------------------------------------

const all: Record<string, unknown> = { label, sets, runs, generated: new Date().toISOString() };
const md: string[] = [];
const chart: Record<string, unknown> = {};
const nSent = (rows: number) => `n = ${rows} sentences × ${runs} runs`;

const speechRows: Record<string, Row[][]> = {};
const speech: Record<string, unknown> = {};
for (const n of LIST_SIZES) {
	speechRows[n] = [];
	for (let r = 1; r <= runs; r++) {
		const rows: Row[] = [];
		for (const set of sets)
			for (const s of SENTENCES) {
				const got = load(set, ['speech', `list${n}`, `r${r}`, s.id]);
				if (!got) continue;
				rows.push(row(set, s, got));
			}
		speechRows[n]!.push(rows);
	}
	const per = speechRows[n]!.map(measure);
	speech[`list${n}`] = { perRun: per, answers: speechRows[n] };
}
all.speech = speech;

if (Object.values(speechRows).some((x) => x.some((r) => r.length))) {
	const sentencesPerRun = speechRows['20']![0]!.length;
	md.push(`## Speech step: gpt-transcribe with 0 to 80 listed words`, '', `${nSent(sentencesPerRun)} (${sets.join(', ')}). Each cell: median of the runs [lowest–highest run].`, '');
	md.push('| Words listed | Targets right | Names right | Products right | Sound-alikes kept right | Never-said listed words | Control sentences with one | Word error rate |');
	md.push('|---:|---|---|---|---|---|---|---|');
	const rowsChart: unknown[] = [];
	for (const n of LIST_SIZES) {
		const per = (speech[`list${n}`] as { perRun: ReturnType<typeof measure>[] }).perRun;
		const t = stat(per.map((p) => p.targetsRight)), nm = stat(per.map((p) => p.namesRight)), pr = stat(per.map((p) => p.productsRight));
		const pt = stat(per.map((p) => p.protectedRight)), ns = stat(per.map((p) => p.neverSaid)), nc = stat(per.map((p) => p.controlsWithNeverSaid)), w = stat(per.map((p) => p.wer));
		const p0 = per[0]!;
		md.push(`| ${n} | ${fmt(t)} of ${p0.targets} | ${fmt(nm)} of ${p0.names} | ${fmt(pr)} of ${p0.products} | ${fmt(pt)} of ${p0.protected} | ${fmt(ns)} | ${fmt(nc)} of ${p0.controls} | ${pct(w)} |`);
		rowsChart.push({ words: n, targetsRight: t, targetsOf: p0.targets, protectedRight: pt, protectedOf: p0.protected, neverSaid: ns, wer: w });
	}
	chart.speech = rowsChart;
	const failures = Object.values(speechRows).flat(2).filter((r) => r.output === null).length;
	md.push('', `API failures (not scored): ${failures}.`);
	const seen: Record<string, number> = {};
	for (const n of LIST_SIZES) for (const p of (speech[`list${n}`] as { perRun: ReturnType<typeof measure>[] }).perRun) for (const [w, c] of Object.entries(p.neverSaidWords)) seen[`${w} (${n})`] = (seen[`${w} (${n})`] ?? 0) + c;
	if (Object.keys(seen).length) md.push('', `Never-said listed words seen (word (list size): times, all runs): ${Object.entries(seen).map(([k, v]) => `${k}: ${v}`).join('; ')}.`);
	md.push('');
}

// ---- correction --------------------------------------------------------------

const correction: Record<string, unknown> = {};
const corrMd: string[] = [];
const armOrder: (ArmId | 'none')[] = ['none', 'text-mini', 'text-opus', 'text-audio15', 'audio15-fix', 'audio15-direct'];
const chartCorr: unknown[] = [];
for (const arm of armOrder) {
	const perRun: unknown[] = [];
	const ms: ReturnType<typeof measure>[] = [];
	const ts: ReturnType<typeof transitions>[] = [];
	for (let r = 1; r <= runs; r++) {
		const rows: (Row & { input: string | null })[] = [];
		for (const set of sets)
			for (const s of SENTENCES) {
				const input = speechOutput(set, 20, 1, s.id);
				if (arm === 'none') {
					// the baseline: only for sentences some arm was run on
					const any = armOrder.slice(1).some((a) => load(set, ['correction', 'natural', a, 'r1', s.id]));
					if (any && input !== null) rows.push({ set, id: s.id, type: s.type, output: input, input });
					continue;
				}
				const got = load(set, ['correction', 'natural', arm, `r${r}`, s.id]);
				if (!got) continue;
				rows.push({ ...row(set, s, got), input });
			}
		if (!rows.length) continue;
		const m = measure(rows);
		const t = transitions(rows);
		ms.push(m);
		ts.push(t);
		perRun.push({ ...m, ...t, answers: rows });
	}
	if (!ms.length) continue;
	correction[arm] = perRun;
	const label = arm === 'none' ? 'No tidy-up (the transcript)' : ARMS[arm].label;
	const tr = stat(ms.map((m) => m.targetsRight)), pr = stat(ms.map((m) => m.protectedRight)), ns = stat(ms.map((m) => m.neverSaid)), w = stat(ms.map((m) => m.wer));
	const wc = stat(ts.map((t) => t.wrongCorrections)), ip = stat(ts.map((t) => t.intoPair)), fx = stat(ts.map((t) => t.fixes));
	const t0 = ts[0]!, m0 = ms[0]!;
	corrMd.push(`| ${label} | ${fmt(tr)} of ${m0.targets} | ${fmt(pr)} of ${m0.protected} | ${arm === 'none' ? '–' : `${fmt(wc)} of ${t0.rightIn}`} | ${arm === 'none' ? '–' : `${fmt(ip)}`} | ${arm === 'none' ? '–' : `${fmt(fx)} of ${t0.wrongIn}`} | ${fmt(ns)} | ${pct(w)} | ${ms.reduce((a, m) => a + m.wrapped, 0)} / ${ms.reduce((a, m) => a + m.empty, 0)} / ${ms.reduce((a, m) => a + m.failed, 0)} |`);
	chartCorr.push({ arm, label, targetsRight: tr, targetsOf: m0.targets, protectedRight: pr, protectedOf: m0.protected, wrongCorrections: wc, rightIn: t0.rightIn, intoSoundAlike: ip, fixes: fx, wrongIn: t0.wrongIn, neverSaid: ns, wer: w, wrapped: ms.reduce((a, m) => a + m.wrapped, 0), empty: ms.reduce((a, m) => a + m.empty, 0), failed: ms.reduce((a, m) => a + m.failed, 0) });
}
all.correction = correction;
if (corrMd.length) {
	const per = (correction['none'] as { answers: unknown[] }[] | undefined)?.[0]?.answers.length ?? 0;
	md.push(`## Correction step: the same transcript, tidied with the 80-word list`, '', `Input: the 20-word speech answer (run 1) for each sentence, frozen before any result was seen. ${nSent(per)}.`);
	md.push('"Wrong corrections" counts words that were right in the transcript and wrong after the tidy-up; "into the sound-alike" is how many of those became the listed name that sounds like it (the Beeper case). "Fixes" counts words that were wrong in the transcript and right after. For the direct-audio arm, which never sees the transcript, the same columns compare its answer with the transcript. An answer wrapped in JSON is scored on the text inside it; an empty answer (or a bare control token) scores as wrong; failed API calls are not scored. All three are counted in the last column.', '');
	md.push('| Arm | Targets right | Sound-alikes kept right | Wrong corrections | …into the sound-alike | Fixes | Never-said listed words | Word error rate | Wrapped / empty / failed answers (all runs) |');
	md.push('|---|---|---|---|---|---|---|---|---|');
	md.push(...corrMd);
	const examples = Object.entries(correction)
		.filter(([a]) => a !== 'none')
		.flatMap(([a, per]) => ((per as ReturnType<typeof transitions>[])[0]?.examples ?? []).slice(0, 4).map((e) => `- ${a}: ${e}`));
	if (examples.length) md.push('', 'Examples of wrong corrections (run 1):', '', ...examples);
	md.push('');
	chart.correction = chartCorr;
}

// ---- constructed repair cases ------------------------------------------------

const constructed: Record<string, unknown> = {};
const conMd: string[] = [];
for (const arm of armOrder.slice(1) as ArmId[]) {
	const per: number[] = [];
	const left: number[] = [];
	let of = 0;
	for (let r = 1; r <= runs; r++) {
		let ok = 0, kept = 0, n = 0;
		for (const set of sets)
			for (const s of SENTENCES.filter((x) => constructedTranscript(x))) {
				const got = ARMS[arm].usesTranscript ? load(set, ['correction', 'constructed', arm, `r${r}`, s.id]) : load(set, ['correction', 'natural', arm, `r${r}`, s.id]);
				if (!got || got.error) continue;
				n++;
				const out = unwrap(got.text).text;
				if (designatedRight(s, out)) ok++;
				const w = WORDS.find((x) => x.text === s.word)!;
				if (w.pair && new RegExp(`\\b${w.pair}\\b`, 'i').test(out)) kept++;
			}
		if (n) {
			per.push(ok);
			left.push(kept);
			of = n;
		}
	}
	if (!per.length) continue;
	constructed[arm] = { repaired: per, leftAsSoundAlike: left, of };
	conMd.push(`| ${ARMS[arm].label}${ARMS[arm].usesTranscript ? '' : ' (its own answers; it never sees the transcript)'} | ${fmt(stat(per))} of ${of} | ${fmt(stat(left))} |`);
}
all.constructed = constructed;
if (conMd.length) {
	md.push('## Repair cases: the listed name, written as its sound-alike', '', 'Each recording says a listed name (e.g. Bepra), but the transcript given to the tidy-up says the everyday sound-alike instead ("Can you ask Beeper to send the slides…"). These errors are constructed, so they are reported apart from the real transcripts above.', '');
	md.push('| Arm | Repaired to the listed name | Left as the sound-alike |', '|---|---|---|', ...conMd, '');
	chart.constructed = constructed;
}

// ---- Opus smoke calls --------------------------------------------------------

const smoke: unknown[] = [];
for (const set of sets)
	for (const s of SENTENCES) {
		const got = load(set, ['correction', 'smoke', 'text-opus', 'r1', s.id]);
		if (got) smoke.push({ set, id: s.id, input: got.input, output: got.text, stop: got.stop, model: got.model, cost: got.cost, error: got.error });
	}
if (smoke.length) {
	all.opusSmoke = smoke;
	md.push(`## Opus smoke test (unscored)`, '', `${smoke.length} calls; stop reasons: ${[...new Set(smoke.map((x) => (x as { stop: string }).stop))].join(', ')}.`, '');
}

const out = path.join(BENCH_DIR, 'results', label);
mkdirSync(out, { recursive: true });
const synthetic = sets.every((s) => s.startsWith('tts-'));
const banner = synthetic
	? `**Synthetic voices** (OpenAI text-to-speech: ${sets.map((s) => s.slice(4)).join(', ')}), not human speech. A check of the harness and a first look only.`
	: `Recordings: ${sets.join(', ')}.`;
all.audio = chart.audio = synthetic ? 'synthetic voices (OpenAI gpt-4o-mini-tts)' : 'human recordings';
chart.note = 'Each value: median of the runs, with the lowest (min) and highest (max) run and every run.';
writeFileSync(path.join(out, 'results.json'), `${JSON.stringify(all, null, 1)}\n`);
writeFileSync(path.join(out, 'chart.json'), `${JSON.stringify(chart, null, 1)}\n`);
writeFileSync(path.join(out, 'summary.md'), `# Results: ${label}\n\n${banner}\n\n${md.join('\n')}\n`);
console.log(md.join('\n'));
