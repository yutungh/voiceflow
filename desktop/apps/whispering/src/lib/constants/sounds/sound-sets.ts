import type { WhisperingSoundNames } from './sound-names';

/**
 * VoiceFlow's own sounds (desktop/tools/sounds/generate.mjs): three sets of
 * five cues, synthesised in code, CC0. Every event the app makes a sound for
 * maps to one cue; the user picks the set (or Off) and the volume.
 */
export const SOUND_SETS = ['glass', 'soft', 'minimal'] as const;
export type SoundSet = (typeof SOUND_SETS)[number];

/** The setting: a set, or no sounds at all. */
export const SOUND_SET_CHOICES = [...SOUND_SETS, 'off'] as const;
export type SoundSetChoice = (typeof SOUND_SET_CHOICES)[number];

export const SOUND_CUES = ['start', 'stop', 'done', 'error', 'cancel'] as const;
export type SoundCue = (typeof SOUND_CUES)[number];

/**
 * Glass by default: clear sine tones between 660 and 1320 Hz with no energy
 * above 3 kHz, 2 ms attacks and a short tail. Brighter and more modern than
 * Soft, less piercing than Minimal's 1.7-2.3 kHz ticks (the ear is most
 * sensitive, and tires fastest, at 2-5 kHz).
 */
export const DEFAULT_SOUND_SET: SoundSet = 'glass';
export const DEFAULT_SOUND_VOLUME = 0.8;

export const SOUND_SET_LABELS: Record<SoundSetChoice, string> = {
	glass: 'Glass',
	soft: 'Soft',
	minimal: 'Minimal',
	off: 'Off',
};

/** Which cue each app event plays. */
export const CUE_OF: Record<WhisperingSoundNames, SoundCue> = {
	'manual-start': 'start',
	'vad-start': 'start',
	'manual-stop': 'stop',
	'vad-stop': 'stop',
	'vad-capture': 'stop',
	'manual-cancel': 'cancel',
	transcriptionComplete: 'done',
	transformationComplete: 'done',
	error: 'error',
};

/** The event toggles (`sound.playOn.*`) behind each cue's switch in Settings. */
export const EVENTS_OF_CUE: Record<SoundCue, readonly WhisperingSoundNames[]> = {
	start: ['manual-start', 'vad-start'],
	stop: ['manual-stop', 'vad-stop', 'vad-capture'],
	done: ['transcriptionComplete', 'transformationComplete'],
	error: ['error'],
	cancel: ['manual-cancel'],
};

export type SoundSettings = { 'sound.set': SoundSetChoice; 'sound.volume': number } & {
	[K in `sound.playOn.${WhisperingSoundNames}`]: boolean;
};

export type SoundToPlay = { set: SoundSet; cue: SoundCue; volume: number };

/**
 * A cue is on when any of its events' switches is on: Settings shows one
 * switch per cue (and sets all of its events together), so an older mix,
 * such as transcription on and tidy-up off, still means "Done is on".
 */
export function cueOn(cue: SoundCue, s: SoundSettings): boolean {
	return EVENTS_OF_CUE[cue].some((name) => s[`sound.playOn.${name}`]);
}

/** What an event plays under these settings; null when it plays nothing. */
export function soundFor(name: WhisperingSoundNames, s: SoundSettings): SoundToPlay | null {
	const set = s['sound.set'];
	const cue = CUE_OF[name];
	if (set === 'off' || !cueOn(cue, s)) return null;
	const volume = Math.min(1, Math.max(0, s['sound.volume']));
	if (volume === 0) return null;
	return { set, cue, volume };
}

/** What Rust needs to play the dictation cues itself (`sound_configure`). */
export type NativeSoundConfig = {
	set: SoundSetChoice;
	volume: number;
	start: boolean;
	stop: boolean;
	cancel: boolean;
};

export function nativeSoundConfig(s: SoundSettings): NativeSoundConfig {
	return {
		set: s['sound.set'],
		volume: Math.min(1, Math.max(0, s['sound.volume'])),
		start: cueOn('start', s),
		stop: cueOn('stop', s),
		cancel: cueOn('cancel', s),
	};
}
