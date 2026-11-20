import { invoke } from '@tauri-apps/api/core';
import { extractErrorMessage } from 'wellcrafted/error';
import { tryAsync } from 'wellcrafted/result';
import type { PlaySoundService } from '.';
import { playWithAudioElement } from './assets';
import { PlaySoundServiceErr } from './types';

/**
 * VoiceFlow: sounds play in Rust (`sound_play`, src-tauri/src/sound.rs), the
 * same player the native hotkeys use, so a busy or sleeping webview does not
 * delay them. If Rust cannot play (no output device), the webview tries.
 */
export function createPlaySoundServiceDesktop(): PlaySoundService {
	return {
		playSound: async ({ set, cue, volume }) =>
			tryAsync({
				try: async () => {
					try {
						await invoke('sound_play', { set, cue, volume });
					} catch (error) {
						console.warn('[sound] native playback failed; using the webview', error);
						await playWithAudioElement(set, cue, volume);
					}
				},
				catch: (error) =>
					PlaySoundServiceErr({
						message: `Failed to play sound: ${extractErrorMessage(error)}`,
					}),
			}),
	};
}
