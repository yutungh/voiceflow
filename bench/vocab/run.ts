/**
 * Runs the test against one set of recordings and stores every answer.
 *
 *   bun run.ts --phase pilot --set tts-coral --step speech
 *   bun run.ts --phase pilot --set tts-coral --step correction --arms text-mini,audio15-fix --types target,protected
 *   bun run.ts --phase real  --set real --step all
 *   add --estimate to print the plan and its cost without calling anything
 *
 * --step      speech | correction | constructed | smoke | all
 * --types     which sentences the correction step covers (default: target,protected,control)
 * --arms      correction arms (default: text-mini,text-opus,text-audio15,audio15-fix,audio15-direct)
 * --runs      repeats (default 3)
 * --budget    USD cap for the phase, counting what the ledger already spent
 *             (default 5 for pilot, 10 for real)
 * --concurrency  parallel requests (default 4)
 *
 * The correction step's input is frozen by rule before any result is seen:
 * the 20-word speech condition, run 1. A rerun skips calls already stored.
 */
import { ARMS, type ArmId } from './lib/arms';
import { flacBytes, hasAudio, seconds, wavBase64 } from './lib/audio';
import { transcribe } from './lib/calls';
import { costOf, perCall, record, spent } from './lib/cost';
import { constructedTranscript, LIST_SIZES, SENTENCES, type Sentence } from './lib/data';
import { speechPayload, TRANSCRIBE_MODEL } from './lib/prompts';
import { load, save, speechOutput } from './lib/store';

const args = new Map<string, string>();
for (let i = 2; i < process.argv.length; i++) {
	const a = process.argv[i]!;
	if (!a.startsWith('--')) continue;
	const next = process.argv[i + 1];
	if (next && !next.startsWith('--')) {
		args.set(a.slice(2), next);
		i++;
	} else args.set(a.slice(2), 'true');
}

const phase = args.get('phase') ?? '';
if (phase !== 'pilot' && phase !== 'real') throw new Error('--phase pilot|real');
const set = args.get('set') ?? '';
if (!set) throw new Error('--set <name> (e.g. tts-coral, real)');
const step = args.get('step') ?? 'all';
const runs = Number(args.get('runs') ?? 3);
const budget = Number(args.get('budget') ?? (phase === 'pilot' ? 5 : 10));
const concurrency = Number(args.get('concurrency') ?? 4);
const estimateOnly = args.has('estimate');
const types = (args.get('types') ?? 'target,protected,control').split(',');
const armIds = (args.get('arms') ?? 'text-mini,text-opus,text-audio15,audio15-fix,audio15-direct').split(',') as ArmId[];
for (const a of armIds) if (!ARMS[a]) throw new Error(`unknown arm ${a}`);
const SEED = 20261125;

type Job = { key: string[]; arm: string; model: string; estimate: number; run: () => Promise<void> };

function shuffled<T>(xs: T[], seed: number): T[] {
	let s = seed >>> 0;
	const rand = () => {
		s = (s + 0x6d2b79f5) >>> 0;
		let t = s;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
	const out = [...xs];
	for (let i = out.length - 1; i > 0; i--) {
		const j = Math.floor(rand() * (i + 1));
		[out[i], out[j]] = [out[j]!, out[i]!];
	}
	return out;
}

const available = SENTENCES.filter((s) => hasAudio(set, s.id));
if (!available.length) throw new Error(`no audio for set ${set}: run tts.ts or collect.ts first`);
const missing = SENTENCES.filter((s) => !hasAudio(set, s.id)).map((s) => s.id);
if (missing.length) console.log(`note: no audio yet for ${missing.join(', ')}; those sentences are skipped`);

function speechJobs(): Job[] {
	const jobs: Job[] = [];
	for (let r = 1; r <= runs; r++) {
		const batch: Job[] = [];
		for (const n of LIST_SIZES)
			for (const s of available) {
				const key = ['speech', `list${n}`, `r${r}`, s.id];
				batch.push({
					key,
					arm: `speech-${n}`,
					model: TRANSCRIBE_MODEL,
					estimate: (seconds(set, s.id) / 60) * 0.0045,
					run: async () => {
						const res = await transcribe(flacBytes(set, s.id), speechPayload(n));
						const cost = costOf(TRANSCRIBE_MODEL, res.usage, { seconds: seconds(set, s.id) });
						save(set, key, { ...res, cost, at: new Date().toISOString() });
						record({ set, step: `${phase}:speech`, arm: `speech-${n}`, model: TRANSCRIBE_MODEL, cost });
					},
				});
			}
		jobs.push(...shuffled(batch, SEED + r));
	}
	return jobs;
}

function correctionJob(panel: string, armId: ArmId, r: number, s: Sentence, input: string | null): Job {
	const arm = ARMS[armId];
	const key = ['correction', panel, armId, `r${r}`, s.id];
	return {
		key,
		arm: armId,
		model: arm.model,
		estimate: perCall(armId, arm.estimate),
		run: async () => {
			if (arm.usesTranscript && input === null) throw new Error(`no frozen transcript for ${s.id}`);
			const res = await arm.call(input ?? '', () => wavBase64(set, s.id));
			const cost = res.usage ? costOf(arm.model, res.usage) : 0;
			save(set, key, { ...res, input, cost, at: new Date().toISOString() });
			record({ set, step: `${phase}:${panel}`, arm: armId, model: arm.model, cost });
		},
	};
}

function naturalJobs(): Job[] {
	const jobs: Job[] = [];
	const sentences = available.filter((s) => types.includes(s.type));
	for (let r = 1; r <= runs; r++) {
		const batch: Job[] = [];
		for (const armId of armIds)
			for (const s of sentences) batch.push(correctionJob('natural', armId, r, s, speechOutput(set, 20, 1, s.id)));
		jobs.push(...shuffled(batch, SEED + 100 + r));
	}
	return jobs;
}

/** The ten paired-name recordings with their sound-alike written in instead (repair cases). */
function constructedJobs(): Job[] {
	const jobs: Job[] = [];
	const sentences = available.filter((s) => constructedTranscript(s) !== null);
	for (let r = 1; r <= runs; r++) {
		const batch: Job[] = [];
		for (const armId of armIds) {
			if (!ARMS[armId].usesTranscript) continue; // direct audio never sees a transcript: its natural answers are reused
			for (const s of sentences) batch.push(correctionJob('constructed', armId, r, s, constructedTranscript(s)));
		}
		jobs.push(...shuffled(batch, SEED + 200 + r));
	}
	return jobs;
}

/** Ten unscored Opus calls, to check the request and its cost before a full run. */
function smokeJobs(): Job[] {
	const sentences = available.filter((s) => s.type !== 'control').slice(0, 10);
	return sentences.map((s) => correctionJob('smoke', 'text-opus', 1, s, speechOutput(set, 20, 1, s.id)));
}

let jobs: Job[] = [];
if (step === 'speech' || step === 'all') jobs.push(...speechJobs());
if (step === 'correction' || step === 'all') jobs.push(...naturalJobs());
if (step === 'constructed' || step === 'all') jobs.push(...constructedJobs());
if (step === 'smoke') jobs.push(...smokeJobs());
const todo = jobs.filter((j) => !load(set, j.key) || load(set, j.key)?.error);

const byArm = new Map<string, { n: number; usd: number }>();
for (const j of todo) {
	const a = byArm.get(j.arm) ?? { n: 0, usd: 0 };
	a.n++;
	a.usd += j.estimate;
	byArm.set(j.arm, a);
}
const estimate = [...byArm.values()].reduce((a, b) => a + b.usd, 0);
const already = spent((r) => r.step.startsWith(`${phase}:`));
console.log(`set ${set}, step ${step}: ${todo.length} calls to make (${jobs.length - todo.length} already stored)`);
for (const [arm, a] of byArm) console.log(`  ${arm.padEnd(16)} ${String(a.n).padStart(4)} calls  ~$${a.usd.toFixed(3)}`);
console.log(`estimate $${estimate.toFixed(2)}; ${phase} spent so far $${already.toFixed(2)}; cap $${budget.toFixed(2)}`);
if (estimateOnly) process.exit(0);
if (already + estimate > budget) {
	console.error(`STOP: this step would take the ${phase} total to about $${(already + estimate).toFixed(2)}, over the $${budget} cap.`);
	process.exit(3);
}
// Correction steps need the frozen transcripts first.
if (todo.some((j) => j.key[0] === 'correction' && j.key[1] !== 'constructed')) {
	const need = new Set(todo.filter((j) => j.key[1] !== 'constructed' && ARMS[j.arm as ArmId]?.usesTranscript).map((j) => j.key.at(-1)!));
	const absent = [...need].filter((id) => speechOutput(set, 20, 1, id) === null);
	if (absent.length) throw new Error(`run the speech step first: no 20-word transcript (run 1) for ${absent.join(', ')}`);
}

let done = 0;
let failed = 0;
async function worker(queue: Job[]) {
	for (let j = queue.shift(); j; j = queue.shift()) {
		await j.run();
		const stored = load(set, j.key);
		if (stored?.error) {
			failed++;
			console.log(`  error ${j.key.join('/')}: ${stored.error}`);
		}
		if (++done % 25 === 0) console.log(`  ${done}/${todo.length}  spent $${spent((r) => r.step.startsWith(`${phase}:`)).toFixed(3)}`);
	}
}
const queue = [...todo];
await Promise.all(Array.from({ length: concurrency }, () => worker(queue)));
console.log(`done: ${done} calls, ${failed} errors; ${phase} spent $${spent((r) => r.step.startsWith(`${phase}:`)).toFixed(3)}`);
