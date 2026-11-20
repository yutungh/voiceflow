/**
 * VoiceFlow B + C2: one plain-language line about the word lists' sync, for
 * Vocabulary and Settings > Word lists.
 */
import type { ProfileState } from '$lib/profile/types';

export type SyncSummary = {
	tone: 'ok' | 'warn' | 'off';
	title: string;
	detail: string;
};

function time(iso: string | undefined, now: Date): string {
	if (!iso) return '';
	const at = new Date(iso);
	if (Number.isNaN(at.getTime())) return '';
	const sameDay = at.toDateString() === now.toDateString();
	const hm = at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	return sameDay
		? `today ${hm}`
		: `${at.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${hm}`;
}

const plural = (n: number, one: string, many: string) =>
	`${n} ${n === 1 ? one : many}`;

export function syncSummary(
	state: ProfileState | null | undefined,
	now = new Date(),
): SyncSummary {
	if (!state?.config.enabled)
		return {
			tone: 'off',
			title: 'Word lists aren’t set up on this PC',
			detail:
				'Spellings you fix are kept on this PC. Connect a folder in Settings to share words with your other devices.',
		};
	const { snapshot, status } = state;
	const report = status.report;
	const counts = snapshot
		? `${plural(snapshot.terms.length, 'word', 'words')}, ${plural(snapshot.corrections.length, 'spelling', 'spellings')}`
		: 'No words yet';
	const pending = report?.pendingEdits
		? ` · ${plural(report.pendingEdits, 'change', 'changes')} waiting to upload`
		: '';
	const deferred = status.deferred ? ' · Refreshing after this dictation' : '';
	if (!report || (status.refreshing && !report.syncedAt && !status.lastSyncAt))
		return {
			tone: 'ok',
			title: 'Checking your word lists…',
			detail: `${counts}${pending}`,
		};
	if (!report.sourceReachable) {
		const last = time(status.lastSyncAt, now);
		return {
			tone: 'warn',
			title: 'Can’t reach your word lists',
			detail: `${last ? `Using the copy from ${last}` : 'Using the copy on this PC'}. Dictation works as usual.${pending}${deferred}`,
		};
	}
	if (report.errors.length > 0)
		return {
			tone: 'warn',
			title: 'Your word lists need a look',
			detail: `${report.errors[0]}${report.errors.length > 1 ? ` (and ${report.errors.length - 1} more)` : ''}`,
		};
	return {
		tone: 'ok',
		title: 'Word lists up to date',
		detail: `${counts} · checked ${time(report.syncedAt ?? status.lastSyncAt, now)}${pending}${deferred}`,
	};
}
