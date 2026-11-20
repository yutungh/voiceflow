/**
 * VoiceFlow 0.9.12 (warnings audit F1): the Notifications list keeps
 * problems only. Pure functions, so they are unit tested; the list itself
 * lives in `NotificationLog.svelte`.
 *
 * - Only warnings and errors are kept: confirmations and progress
 *   (Starting, Pasted...) are never listed.
 * - One entry per item: a notice with the same id (or, without an id, the
 *   same kind, title and description) replaces its entry where it is.
 * - At most `LOG_CAP` entries; the oldest go first.
 */
import type { UnifiedNotificationOptions } from '$lib/services/isomorphic/notifications/types';

export const LOG_CAP = 50;

export type LogEntry = UnifiedNotificationOptions & {
	/** The entry's identity: the notice's id, else its kind and words. */
	key: string;
	/** When it was last added or updated (epoch ms). */
	at: number;
};

/** Warnings and errors only. */
export function isLogged(variant: UnifiedNotificationOptions['variant']): boolean {
	return variant === 'warning' || variant === 'error';
}

export function logKey(log: Pick<UnifiedNotificationOptions, 'id' | 'variant' | 'title' | 'description'>): string {
	return log.id ? `id:${log.id}` : `text:${log.variant}:${log.title}:${log.description}`;
}

/**
 * The list with `log` added: replaced in place when it is already there,
 * else appended; capped at `cap` (oldest dropped). Unchanged (the same
 * array) for anything that is not a warning or an error.
 */
export function upsertLog(
	logs: readonly LogEntry[],
	log: UnifiedNotificationOptions,
	now: number = Date.now(),
	cap: number = LOG_CAP,
): LogEntry[] {
	if (!isLogged(log.variant)) return logs as LogEntry[];
	const entry: LogEntry = { ...log, key: logKey(log), at: now };
	const at = logs.findIndex((l) => l.key === entry.key);
	const next = at >= 0 ? logs.map((l, i) => (i === at ? entry : l)) : [...logs, entry];
	return next.length > cap ? next.slice(next.length - cap) : next;
}

/** The list without the entry for notice `id` (or the entry with that key). */
export function withoutLog(logs: readonly LogEntry[], idOrKey: string): LogEntry[] {
	return logs.filter((l) => l.key !== idOrKey && l.key !== `id:${idOrKey}`);
}

/** The ids a dictation's problem notices use (tidy-up, transcription, paste). */
export function problemNoticeIds(recordingId: string): string[] {
	return [`tidy-up-${recordingId}`, `transcribe-${recordingId}`, `paste-${recordingId}`];
}
