import { redirect } from '@sveltejs/kit';

// VoiceFlow: new styles are made on the Styles page.
export function load() {
	redirect(307, '/styles');
}
