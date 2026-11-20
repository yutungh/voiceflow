import { redirect } from '@sveltejs/kit';

// VoiceFlow: Recordings is now History; old links land there.
export function load() {
	redirect(307, '/history');
}
