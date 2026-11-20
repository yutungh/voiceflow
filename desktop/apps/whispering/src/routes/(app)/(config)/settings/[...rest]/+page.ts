import { redirect } from '@sveltejs/kit';

/**
 * VoiceFlow: Settings is one page now. Links to the old section pages
 * (from notices, or saved elsewhere) land on the matching section.
 */
const SECTION_FOR_OLD_PAGE: Record<string, string> = {
	recording: 'microphone',
	transcription: 'transcription',
	'api-keys': 'transcription',
	privacy: 'privacy',
	shortcuts: 'keys',
	sound: 'sounds',
	about: 'about',
	speed: 'speed',
};

export function load({ params }: { params: { rest: string } }) {
	const first = params.rest.split('/')[0] ?? '';
	const section = SECTION_FOR_OLD_PAGE[first];
	redirect(307, section ? `/settings#${section}` : '/settings');
}
