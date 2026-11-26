/**
 * The correction-step arms. All of them get the same frozen transcript (the
 * 20-word speech condition, run 1) and the same 80-word tidy block, except the
 * direct-audio arm, which gets the recording and no transcript.
 *
 * | arm            | model           | gets                                   |
 * |----------------|-----------------|----------------------------------------|
 * | none           | -               | the transcript as it is (baseline)     |
 * | text-mini      | gpt-5.4-mini    | transcript + list (VoiceFlow as built) |
 * | text-opus      | claude-opus-5-5 | transcript + list (VoiceFlow's prompt) |
 * | text-audio15   | gpt-audio-1.5   | transcript + list, no recording        |
 * | audio15-fix    | gpt-audio-1.5   | transcript + list + recording          |
 * | audio15-direct | gpt-audio-1.5   | recording + list, no transcript        |
 *
 * Variants: text-mini-nohint is text-mini with no heard-as notes in the list;
 * the -spoken arms are the two recording arms in the same output mode as the
 * no-recording control (a spoken answer, its text kept).
 *
 * text-audio15 and audio15-fix differ only in the recording, so that pair
 * shows what hearing the audio adds with the model held fixed. One forced
 * difference: gpt-audio-1.5 refuses a request with no audio in or out, so the
 * no-recording control asks for a spoken answer and keeps its text
 * (calls.ts, audioChat).
 */
import { audioChat, tidyAnthropic, tidyOpenAI, type CallResult } from './calls';
import { AUDIO_ADDENDUM, AUDIO_MODEL, DIRECT_AUDIO_USER, OPUS_MODEL, TIDY_MODEL, tidySystem, tidyUser } from './prompts';
import { variantSystem, WORDINGS, type Variant } from './tidyfix';

export type ArmId =
	| 'text-mini'
	| 'text-mini-nohint'
	| 'text-opus'
	| 'text-audio15'
	| 'audio15-fix'
	| 'audio15-direct'
	| 'audio15-fix-spoken'
	| 'audio15-direct-spoken'
	| `fx-${string}`;

export type Arm = {
	id: ArmId;
	model: string;
	label: string;
	/** Needs the frozen transcript (all but direct audio). */
	usesTranscript: boolean;
	/** Sends the recording. */
	usesAudio: boolean;
	/** Default cost per call (USD) until the ledger has real ones. */
	estimate: number;
	call: (transcript: string, wav: () => string) => Promise<CallResult>;
};

export const ARMS: Record<string, Arm> = {
	'text-mini': {
		id: 'text-mini',
		model: TIDY_MODEL,
		label: 'Text tidy-up, gpt-5.4-mini (VoiceFlow default)',
		usesTranscript: true,
		usesAudio: false,
		estimate: 0.003,
		call: (t) => tidyOpenAI(tidySystem(), tidyUser(t)),
	},
	'text-mini-nohint': {
		id: 'text-mini-nohint',
		model: TIDY_MODEL,
		label: 'Text tidy-up, gpt-5.4-mini, list without heard-as notes',
		usesTranscript: true,
		usesAudio: false,
		estimate: 0.001,
		call: (t) => tidyOpenAI(tidySystem(false), tidyUser(t)),
	},
	'text-opus': {
		id: 'text-opus',
		model: OPUS_MODEL,
		label: 'Text tidy-up, Claude Opus 5.5',
		usesTranscript: true,
		usesAudio: false,
		estimate: 0.02,
		call: (t) => tidyAnthropic(tidySystem(), tidyUser(t)),
	},
	'text-audio15': {
		id: 'text-audio15',
		model: AUDIO_MODEL,
		label: 'Text tidy-up, gpt-audio-1.5, no recording',
		usesTranscript: true,
		usesAudio: false,
		estimate: 0.01,
		call: (t) => audioChat(AUDIO_MODEL, `${tidySystem()}\n\n${AUDIO_ADDENDUM}`, tidyUser(t), null),
	},
	'audio15-fix': {
		id: 'audio15-fix',
		model: AUDIO_MODEL,
		label: 'Tidy-up with the recording, gpt-audio-1.5',
		usesTranscript: true,
		usesAudio: true,
		estimate: 0.0095,
		call: (t, wav) => audioChat(AUDIO_MODEL, `${tidySystem()}\n\n${AUDIO_ADDENDUM}`, tidyUser(t), wav()),
	},
	'audio15-direct': {
		id: 'audio15-direct',
		model: AUDIO_MODEL,
		label: 'Recording + list, no transcript, gpt-audio-1.5',
		usesTranscript: false,
		usesAudio: true,
		estimate: 0.009,
		call: (_t, wav) => audioChat(AUDIO_MODEL, tidySystem(), DIRECT_AUDIO_USER, wav()),
	},
	'audio15-fix-spoken': {
		id: 'audio15-fix-spoken',
		model: AUDIO_MODEL,
		label: 'Tidy-up with the recording, gpt-audio-1.5, spoken answer',
		usesTranscript: true,
		usesAudio: true,
		estimate: 0.012,
		call: (t, wav) => audioChat(AUDIO_MODEL, `${tidySystem()}\n\n${AUDIO_ADDENDUM}`, tidyUser(t), wav(), true),
	},
	'audio15-direct-spoken': {
		id: 'audio15-direct-spoken',
		model: AUDIO_MODEL,
		label: 'Recording + list, no transcript, gpt-audio-1.5, spoken answer',
		usesTranscript: false,
		usesAudio: true,
		estimate: 0.012,
		call: (_t, wav) => audioChat(AUDIO_MODEL, tidySystem(), DIRECT_AUDIO_USER, wav(), true),
	},
};

// ---- tidy-up fixes (results/tidy-fixes) ----------------------------------------
// fx-<model>-<variant>: model mini | sonnet | gpt54 | opus; variant base (VoiceFlow's
// prompt), reword-<A|B|C>, nohint (no everyday-word hints) or both-<A|B|C>. The
// Claude models run at effort low, to keep a tidy-up fast; the OpenAI models get
// no reasoning setting, as VoiceFlow sends them.
const FX_MODELS = {
	mini: { model: TIDY_MODEL, name: 'gpt-5.4-mini', estimate: 0.0006 },
	gpt54: { model: 'gpt-5.4', name: 'GPT-5.4', estimate: 0.003 },
	sonnet: { model: 'claude-sonnet-5-5', name: 'Claude Sonnet 5.5 (effort low)', estimate: 0.007 },
	opus: { model: OPUS_MODEL, name: 'Claude Opus 5.5 (effort low)', estimate: 0.014 },
} as const;
const FX_VARIANTS: Record<string, { v: Variant; label: string }> = { base: { v: { hints: 'all' }, label: "VoiceFlow's prompt" } };
for (const w of Object.keys(WORDINGS) as (keyof typeof WORDINGS)[]) {
	FX_VARIANTS[`reword-${w}`] = { v: { hints: 'all', wording: w }, label: `reworded instruction ${w}` };
	FX_VARIANTS[`both-${w}`] = { v: { hints: 'no-everyday', wording: w }, label: `reworded instruction ${w} + no everyday-word hints` };
}
FX_VARIANTS.nohint = { v: { hints: 'no-everyday' }, label: 'no everyday-word hints' };
const systems = new Map<string, string>();
const systemFor = (key: string) => {
	if (!systems.has(key)) systems.set(key, variantSystem(FX_VARIANTS[key]!.v));
	return systems.get(key)!;
};
for (const [mk, m] of Object.entries(FX_MODELS))
	for (const [vk, v] of Object.entries(FX_VARIANTS)) {
		const id = `fx-${mk}-${vk}` as ArmId;
		ARMS[id] = {
			id,
			model: m.model,
			label: `${m.name}, ${v.label}`,
			usesTranscript: true,
			usesAudio: false,
			estimate: m.estimate,
			call: (t) =>
				mk === 'mini' || mk === 'gpt54'
					? tidyOpenAI(systemFor(vk), tidyUser(t), m.model)
					: tidyAnthropic(systemFor(vk), tidyUser(t), { model: m.model, effort: 'low' }),
		};
	}