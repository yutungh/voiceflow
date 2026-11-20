import { redirect } from '@sveltejs/kit';

// VoiceFlow: VoiceFlow records without FFmpeg; old links land on Settings.
export function load() {
	redirect(307, '/settings');
}
