import type { UnifiedNotificationOptions } from '$lib/services/isomorphic/notifications/types';

/** The longest description written to the log file. */
const LOG_DESCRIPTION_MAX = 300;

/**
 * VoiceFlow 0.9.12 (F10): a warning or error shown in the window, written
 * to the log file too. Its title and description only: never the
 * dictation's text, its buttons or error objects.
 */
export function logLine(options: Pick<UnifiedNotificationOptions, 'title' | 'description' | 'id'>): string {
	const flat = (s: string) => s.replace(/\s+/g, ' ').trim();
	const description = flat(options.description ?? '');
	const cut =
		description.length > LOG_DESCRIPTION_MAX
			? `${description.slice(0, LOG_DESCRIPTION_MAX - 1)}…`
			: description;
	return `[notice] ${flat(options.title)}${cut ? `: ${cut}` : ''}`;
}
