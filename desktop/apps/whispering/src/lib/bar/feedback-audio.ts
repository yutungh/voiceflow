/**
 * VoiceFlow A2: a feedback recording's audio is not kept. The recorder
 * writes `<recordings folder>/<id>.<ext>` while listening; the feedback
 * recording never becomes a dictation in History, so its file is removed
 * once it is transcribed.
 */
import { PATHS } from '$lib/constants/paths';
import { settings } from '$lib/stores/settings.svelte';
import { audioFilesById } from '$lib/vf/audio-retention';

export async function removeFeedbackAudio(recordingId: string): Promise<void> {
	if (typeof window === 'undefined' || !window.__TAURI_INTERNALS__) return;
	try {
		const folder =
			settings.value['recording.cpal.outputFolder'] ?? (await PATHS.DB.RECORDINGS());
		const { readDir, remove } = await import('@tauri-apps/plugin-fs');
		const { join } = await import('@tauri-apps/api/path');
		const names = (await readDir(folder)).filter((e) => e.isFile).map((e) => e.name);
		for (const name of audioFilesById(names).get(recordingId) ?? []) {
			await remove(await join(folder, name));
		}
	} catch (error) {
		console.warn('[bar] could not remove the feedback audio', error);
	}
}
