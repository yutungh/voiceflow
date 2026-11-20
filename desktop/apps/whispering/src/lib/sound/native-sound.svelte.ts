/**
 * VoiceFlow: tells Rust which sounds to play for the dictations it records
 * itself (src-tauri/src/sound.rs). Rust plays start, stop and cancel the
 * moment the microphone opens or closes, without waiting for this page, so
 * the cue is on time even while the webview is busy or asleep.
 *
 * Main window only, started once with the native hotkeys. Until the first
 * configuration arrives, Rust plays nothing.
 */
import { invoke } from '@tauri-apps/api/core';
import { nativeSoundConfig } from '$lib/constants/sounds';
import { settings } from '$lib/stores/settings.svelte';

let started = false;

export function initNativeSound() {
	if (started || typeof window === 'undefined' || !window.__TAURI_INTERNALS__) return;
	started = true;
	let last = '';
	$effect.root(() => {
		$effect(() => {
			const config = nativeSoundConfig(settings.value);
			const key = JSON.stringify(config);
			if (key === last) return;
			last = key;
			void invoke('sound_configure', { config }).catch((error) =>
				console.warn('[sound] configuring native sounds failed', error),
			);
		});
	});
}
