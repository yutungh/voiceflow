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
 * text-audio15 and audio15-fix differ only in the recording, so that pair
 * shows what hearing the audio adds with the model held fixed. One forced
 * difference: gpt-audio-1.5 refuses a request with no audio in or out, so the
 * no-recording control asks for a spoken answer and keeps its text
 * (calls.ts, audioChat).
 */
import { audioChat, tidyAnthropic, tidyOpenAI, type CallResult } from './calls';
import { AUDIO_ADDENDUM, AUDIO_MODEL, DIRECT_AUDIO_USER, OPUS_MODEL, TIDY_MODEL, tidySystem, tidyUser } from './prompts';

export type ArmId = 'text-mini' | 'text-opus' | 'text-audio15' | 'audio15-fix' | 'audio15-direct';

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

export const ARMS: Record<ArmId, Arm> = {
	'text-mini': {
		id: 'text-mini',
		model: TIDY_MODEL,
		label: 'Text tidy-up, gpt-5.4-mini (VoiceFlow default)',
		usesTranscript: true,
		usesAudio: false,
		estimate: 0.003,
		call: (t) => tidyOpenAI(tidySystem(), tidyUser(t)),
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
};
