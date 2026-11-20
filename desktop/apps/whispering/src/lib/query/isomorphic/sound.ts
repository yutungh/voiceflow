import { Ok, type Result } from 'wellcrafted/result';
import {
	type SoundCue,
	type SoundSet,
	soundFor,
	type WhisperingSoundNames,
} from '$lib/constants/sounds';
import { defineMutation } from '$lib/query/client';
import { services } from '$lib/services';
import type { PlaySoundServiceError } from '$lib/services/isomorphic/sound';
import { settings } from '$lib/stores/settings.svelte';

const soundKeys = {
	all: ['sound'] as const,
	playSoundIfEnabled: ['sound', 'playSoundIfEnabled'] as const,
	preview: ['sound', 'preview'] as const,
} as const;

export const sound = {
	playSoundIfEnabled: defineMutation({
		mutationKey: soundKeys.playSoundIfEnabled,
		mutationFn: async (
			soundName: WhisperingSoundNames,
		): Promise<Result<void, PlaySoundServiceError>> => {
			const play = soundFor(soundName, settings.value);
			if (!play) return Ok(undefined);
			return await services.sound.playSound(play);
		},
	}),
	/** Settings' play buttons: one cue of a set, at the current volume. */
	preview: defineMutation({
		mutationKey: soundKeys.preview,
		mutationFn: async ({
			set,
			cue,
		}: {
			set: SoundSet;
			cue: SoundCue;
		}): Promise<Result<void, PlaySoundServiceError>> =>
			await services.sound.playSound({
				set,
				cue,
				volume: settings.value['sound.volume'],
			}),
	}),
};
