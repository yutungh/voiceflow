import { redirect } from '@sveltejs/kit';

// VoiceFlow: Transformations are now Styles; old links land there.
export function load() {
	redirect(307, '/styles');
}
