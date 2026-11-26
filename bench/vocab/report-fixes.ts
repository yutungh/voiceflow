/**
 * Scores the tidy-up fixes (lib/tidyfix.ts, the fx-* arms) and writes
 * results/tidy-fixes/{summary.md,results.json,chart.json}. Counts only: no
 * transcript text is written for a person's recordings.
 *
 *   bun report-fixes.ts --pick tts-coral    which reworded instruction wins on the pilot
 *   bun report-fixes.ts --set real --pilot tts-coral --arms fx-mini-base,...
 *
 * Per arm and run, over the 40 sentences (input: the frozen 20-word, run-1
 * transcript) and the 10 constructed repair cases:
 * - sound-alike -> listed name: right in the transcript, the listed name after;
 * - sound-alikes broken / targets broken: right before, wrong after, any way;
 * - repaired: constructed cases where the listed name came back;
 * - never-said listed words; word error rate.
 * Latency: the answering attempt of every call (natural and constructed, all
 * runs), median and 90th percentile, in seconds, measured from this PC with 4
 * calls in flight.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { ARMS } from './lib/arms';
import { constructedTranscript, SENTENCES, WORDS } from './lib/data';
import { BENCH_DIR } from './lib/paths';
import { designatedRight, median, neverSaid, unwrap, wordErrors } from './lib/score';
import { load, speechOutput } from './lib/store';
import { droppedForms, WORDINGS } from './lib/tidyfix';

const argOf = (n: string, d?: string) => {
	const i = process.argv.indexOf(`--${n}`);
	return i > 0 ? process.argv[i + 1]! : d;
};
const RUNS = 3;

type ArmResult = {
	arm: string;
	label: string;
	model: string;
	perRun: { soundAlikesRightIn: number; intoListedName: number; soundAlikesBroken: number; targetsRightIn: number; targetsBroken: number; repaired: number; repairOf: number; neverSaid: number; wer: number; failed: number }[];
	latency: { median: number; p90: number; calls: number; retried: number };
	costPerCall: number;
};

function quantile(xs: number[], q: number): number {
	const s = [...xs].sort((a, b) => a - b);
	if (!s.length) return Number.NaN;
	const i = (s.length - 1) * q;
	const lo = Math.floor(i);
	const hi = Math.ceil(i);
	return s[lo]! + (s[hi]! - s[lo]!) * (i - lo);
}

function score(set: string, arm: string): ArmResult | null {
	const perRun: ArmResult['perRun'] = [];
	const lat: number[] = [];
	let retried = 0;
	let cost = 0;
	let calls = 0;
	for (let r = 1; r <= RUNS; r++) {
		const x = { soundAlikesRightIn: 0, intoListedName: 0, soundAlikesBroken: 0, targetsRightIn: 0, targetsBroken: 0, repaired: 0, repairOf: 0, neverSaid: 0, wer: 0, failed: 0 };
		let errs = 0;
		let words = 0;
		let any = false;
		for (const s of SENTENCES) {
			const got = load(set, ['correction', 'natural', arm, `r${r}`, s.id]);
			if (!got) continue;
			any = true;
			calls++;
			cost += got.cost;
			if (got.latencyMs !== undefined) lat.push(got.latencyMs / 1000);
			if ((got.attempts ?? 1) > 1) retried++;
			if (got.error) {
				x.failed++;
				continue;
			}
			const out = unwrap(got.text).text;
			const input = speechOutput(set, 20, 1, s.id) ?? '';
			const we = wordErrors(s, out);
			errs += we.errors;
			words += we.words;
			x.neverSaid += Object.values(neverSaid(s, out)).reduce((a, b) => a + b, 0);
			if (!s.word || !designatedRight(s, input)) continue;
			const after = designatedRight(s, out);
			if (s.type === 'protected') {
				x.soundAlikesRightIn++;
				if (!after) {
					x.soundAlikesBroken++;
					if (s.pair && neverSaid(s, out)[s.pair]) x.intoListedName++;
				}
			} else if (s.type === 'target') {
				x.targetsRightIn++;
				if (!after) x.targetsBroken++;
			}
		}
		for (const s of SENTENCES.filter((y) => constructedTranscript(y))) {
			const got = load(set, ['correction', 'constructed', arm, `r${r}`, s.id]);
			if (!got) continue;
			calls++;
			cost += got.cost;
			if (got.latencyMs !== undefined) lat.push(got.latencyMs / 1000);
			if ((got.attempts ?? 1) > 1) retried++;
			if (got.error) {
				x.failed++;
				continue;
			}
			x.repairOf++;
			if (designatedRight(s, unwrap(got.text).text)) x.repaired++;
		}
		x.wer = words ? errs / words : 0;
		if (any) perRun.push(x);
	}
	if (!perRun.length) return null;
	return {
		arm,
		label: ARMS[arm]!.label,
		model: ARMS[arm]!.model,
		perRun,
		latency: { median: quantile(lat, 0.5), p90: quantile(lat, 0.9), calls: lat.length, retried },
		costPerCall: calls ? cost / calls : 0,
	};
}

type St = { median: number; min: number; max: number };
const st = (xs: number[]): St => ({ median: median(xs), min: Math.min(...xs), max: Math.max(...xs) });
const f = (x: St) => `${x.median} [${x.min}–${x.max}]`;

/** Lower is better: listed-name changes, then other breaks, then fewer repairs. */
function badness(a: ArmResult): number[] {
	const sum = (k: keyof ArmResult['perRun'][number]) => a.perRun.reduce((t, r) => t + (r[k] as number), 0);
	return [sum('intoListedName'), sum('soundAlikesBroken') + sum('targetsBroken'), -sum('repaired')];
}
const cmp = (x: number[], y: number[]) => x.findIndex((v, i) => v !== y[i]) < 0 ? 0 : x[x.findIndex((v, i) => v !== y[i])]! - y[x.findIndex((v, i) => v !== y[i])]!;

function row(a: ArmResult): string {
	const r0 = a.perRun[0]!;
	return `| ${a.label} | ${f(st(a.perRun.map((r) => r.intoListedName)))} of ${r0.soundAlikesRightIn} | ${f(st(a.perRun.map((r) => r.soundAlikesBroken)))} | ${f(st(a.perRun.map((r) => r.targetsBroken)))} of ${r0.targetsRightIn} | ${f(st(a.perRun.map((r) => r.repaired)))} of ${r0.repairOf} | ${f(st(a.perRun.map((r) => r.neverSaid)))} | ${a.latency.median.toFixed(2)} / ${a.latency.p90.toFixed(2)} | $${a.costPerCall.toFixed(4)} |`;
}
const HEAD = [
	'| Arm | Sound-alike → listed name | Sound-alikes broken (any way) | Targets broken | Made-up mishearings repaired | Never-said listed words | Latency median / p90 (s) | Cost per call |',
	'|---|---|---|---|---|---|---|---|',
];

// ---- pick the wording on the pilot ---------------------------------------------

const pickSet = argOf('pick');
if (pickSet) {
	const cands = (Object.keys(WORDINGS) as (keyof typeof WORDINGS)[]).map((w) => ({ w, r: score(pickSet, `fx-mini-reword-${w}`) })).filter((c) => c.r);
	cands.sort((a, b) => cmp(badness(a.r!), badness(b.r!)));
	const base = score(pickSet, 'fx-mini-base');
	console.log([...HEAD, ...(base ? [row(base)] : []), ...cands.map((c) => row(c.r!))].join('\n'));
	console.log(`\nPicked wording: ${cands[0]?.w}`);
	process.exit(0);
}

// ---- the full write-up -----------------------------------------------------------

const set = argOf('set', 'real')!;
const pilot = argOf('pilot', 'tts-coral')!;
const arms = argOf('arms', '')!.split(',').filter(Boolean);
const picked = argOf('picked', '')!;
const results = arms.map((a) => score(set, a)).filter((x): x is ArmResult => !!x);
const tuning = (['base', ...Object.keys(WORDINGS).map((w) => `reword-${w}`)] as string[]).map((v) => score(pilot, `fx-mini-${v}`)).filter((x): x is ArmResult => !!x);

const md: string[] = [];
md.push('# Results: tidy-up fixes', '', `**Human recordings**: one speaker (set ${set}), the same frozen 20-word transcript, 80-word list and scoring as results/real. n = 40 sentences × 3 runs, plus 10 made-up mishearings × 3 runs. Counts only, no transcripts. Each cell: median of the runs [lowest–highest].`, '');
const notes = path.join(BENCH_DIR, 'results', 'tidy-fixes', 'notes.md');
if (existsSync(notes)) md.push(readFileSync(notes, 'utf8').trim(), '');
md.push('## On his voice', '', ...HEAD, ...results.map(row), '');
md.push('Latency is the time of the answering attempt, from this PC, with 4 calls in flight; a call that had to be retried counts only its last attempt.', '');
md.push('## Picking the reworded instruction (synthetic pilot only)', '', `The three wordings were written before any result and compared on the synthetic voice "coral" only, so the choice was not tuned on his voice. Picked: **${picked || '(see --picked)'}**.`, '', ...HEAD, ...tuning.map(row), '');
for (const [w, lines] of Object.entries(WORDINGS)) md.push(`<details><summary>Wording ${w}${w === picked ? ' (picked)' : ''}</summary>`, '', ...lines, '', '</details>', '');
md.push('## The everyday-word rule', '', 'A heard-as form is dropped when every word in it is in SCOWL\'s "english" and "american" lists up to size 60, the size SCOWL recommends for spell checking (npm wordlist-english 1.2.1). Proper names are not in those lists. In this test list it drops:', '', ...droppedForms().map((d) => `- ${d.word}: "${d.form}"`), '', `It keeps every other form, including Mirva: "Miro" (a proper name).`, '');

const out = path.join(BENCH_DIR, 'results', 'tidy-fixes');
mkdirSync(out, { recursive: true });
const chart = results.map((a) => ({
	arm: a.arm,
	label: a.label,
	model: a.model,
	soundAlikesIntoListedName: st(a.perRun.map((r) => r.intoListedName)),
	soundAlikesRightInTranscript: a.perRun[0]!.soundAlikesRightIn,
	repaired: st(a.perRun.map((r) => r.repaired)),
	repairOf: a.perRun[0]!.repairOf,
	latencySeconds: { median: a.latency.median, p90: a.latency.p90 },
	costPerCall: a.costPerCall,
}));
writeFileSync(path.join(out, 'results.json'), `${JSON.stringify({ set, pilot, picked, generated: new Date().toISOString(), runs: RUNS, droppedHints: droppedForms(), wordings: WORDINGS, arms: results, pilotTuning: tuning }, null, 1)}\n`);
writeFileSync(path.join(out, 'chart.json'), `${JSON.stringify({ audio: 'human recordings', note: 'median of 3 runs with min and max; latency in seconds', arms: chart }, null, 1)}\n`);
writeFileSync(path.join(out, 'summary.md'), `${md.join('\n')}\n`);
console.log(md.join('\n'));
void WORDS;
