import { Ok } from 'wellcrafted/result';
import type { PlaySoundService } from '.';
import { playWithAudioElement } from './assets';

export function createPlaySoundServiceWeb(): PlaySoundService {
	return {
		playSound: async ({ set, cue, volume }) => {
			if (!document.hidden) await playWithAudioElement(set, cue, volume);
			return Ok(undefined);
		},
	};
}
