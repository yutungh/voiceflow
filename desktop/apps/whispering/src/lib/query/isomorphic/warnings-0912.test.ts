/**
 * VoiceFlow 0.9.12, the rest of the warnings audit: the Notifications list
 * keeps problems only, fixed problems clear themselves, Home shows only
 * what still needs him, "Nothing heard" can be retried, no developer
 * statistics. Pure parts are called; modules that import `$lib` paths
 * `bun test` cannot resolve are checked in their source.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Recording } from '$lib/services/isomorphic/db';
import type { UnifiedNotificationOptions } from '$lib/services/isomorphic/notifications/types';
import {
	LOG_CAP,
	isLogged,
	problemNoticeIds,
	upsertLog,
	withoutLog,
	type LogEntry,
} from '../../components/notification-log';
import {
	EMPTY_RETRY_MIN_MS,
	emptyInputLeftovers,
	hasProblem,
	isNothingHeard,
	problemOf,
	problemsOf,
	worthRetrying,
} from '../../vf/dictations';

const src = (...path: string[]) =>
	readFileSync(join(import.meta.dir, '..', '..', '..', ...path), 'utf8').replace(/\r\n/g, '\n');
const actions = src('lib', 'query', 'isomorphic', 'actions.ts');
const delivery = src('lib', 'query', 'isomorphic', 'delivery.ts');
const notify = src('lib', 'query', 'isomorphic', 'notify.ts');
const logList = src('lib', 'components', 'NotificationLog.svelte');
const problemCard = src('lib', 'vf', 'ProblemCard.svelte');
const home = src('routes', '(app)', '+page.svelte');
const statusBar = src('routes', '(app)', '_components', 'StatusBar.svelte');
const contract = src('lib', 'bar', 'contract.ts');
const notice = src('lib', 'bar', 'notice.ts');

const note = (variant: UnifiedNotificationOptions['variant'], over: Partial<UnifiedNotificationOptions> = {}) =>
	({ variant, title: 'T', description: 'D', ...over }) as UnifiedNotificationOptions;

const NOW = Date.parse('2026-11-19T12:00:00Z');
function rec(extra: Partial<Recording> = {}, minutesAgo = 5): Recording {
	const at = new Date(NOW - minutesAgo * 60_000).toISOString();
	return {
		id: `r-${Math.random()}`,
		title: '',
		subtitle: '',
		timestamp: at,
		createdAt: at,
		updatedAt: at,
		transcribedText: 'hello there',
		transcriptionStatus: 'DONE',
		...extra,
	};
}

describe('F1: the Notifications list', () => {
	test('a: warnings and errors only, one entry per item updated in place, 50 at most', () => {
		let logs: LogEntry[] = [];
		for (const v of ['success', 'info', 'loading'] as const) {
			expect(isLogged(v)).toBe(false);
			expect(upsertLog(logs, note(v))).toBe(logs);
		}
		logs = upsertLog(logs, note('warning', { id: 'paste-a', title: 'Not pasted' }), 1);
		logs = upsertLog(logs, note('error', { id: 'tidy-up-b' }), 2);
		logs = upsertLog(logs, note('error', { id: 'paste-a', title: 'Still not pasted' }), 3);
		expect(logs.map((l) => [l.key, l.title, l.at])).toEqual([
			['id:paste-a', 'Still not pasted', 3],
			['id:tidy-up-b', 'T', 2],
		]);
		// Without an id: the same words are the same item.
		logs = upsertLog(logs, note('error'), 4);
		logs = upsertLog(logs, note('error'), 5);
		expect(logs).toHaveLength(3);
		for (let i = 0; i < 80; i++) logs = upsertLog(logs, note('warning', { id: `n${i}` }), 10 + i);
		expect(logs).toHaveLength(LOG_CAP);
		expect(logs[0]!.key).toBe('id:n30');
		expect(logs.at(-1)!.key).toBe('id:n79');
	});

	test('b: removeLog by notice id or entry key, Clear all, and a × on each entry', () => {
		let logs: LogEntry[] = [];
		logs = upsertLog(logs, note('warning', { id: 'paste-a' }));
		logs = upsertLog(logs, note('error'));
		expect(withoutLog(logs, 'paste-a')).toHaveLength(1);
		expect(withoutLog(logs, logs[1]!.key)).toHaveLength(1);
		expect(withoutLog(logs, 'nothing')).toHaveLength(2);
		expect(logList).toContain('removeLog: (id: string) => {');
		expect(logList).toContain('onclick={() => notificationLog.clearLogs()}');
		expect(logList).toContain('onclick={() => notificationLog.removeLog(log.key)}');
		expect(logList).toContain('aria-label="Remove this notification"');
		// A dismissed notice leaves the list too.
		expect(notify).toMatch(/dismiss: \(id\?: string \| number\) => \{\s*services\.toast\.dismiss\(id\);\s*if \(id !== undefined\) notificationLog\.removeLog\(String\(id\)\);/);
	});
});

describe('F3d, F4: fixed problems clear themselves; Home shows what still needs him', () => {
	test('F3d: a paste or copy removes the dictation\'s earlier problem notices', () => {
		expect(problemNoticeIds('x')).toEqual(['tidy-up-x', 'transcribe-x', 'paste-x']);
		expect(delivery).toMatch(/for \(const id of problemNoticeIds\(recordingId\)\) \{\s*if \(id !== toastId\) rpc\.notify\.dismiss\(id\);\s*rpc\.notify\.unlog\(id\);/);
	});

	test('F4b: words copied after a failed tidy-up are off Home, still in History', () => {
		expect(actions).toContain("result: copied ? 'copied' : 'not-pasted',");
		const copied = rec({ result: 'copied', problemKind: 'tidy-up', problem: 'Tidy-up timed out.' });
		expect(problemOf(copied)).toBeUndefined();
		expect(problemsOf([copied], NOW)).toEqual([]);
		expect(problemOf(copied, { includeDismissed: true })?.kind).toBe('tidy-up');
	});

	test('F4c: Home cards drop off after 24 hours', () => {
		const fresh = rec({ result: 'not-pasted', problemKind: 'paste' }, 23 * 60);
		const stale = rec({ result: 'not-pasted', problemKind: 'paste' }, 25 * 60);
		expect(problemsOf([fresh, stale], NOW).map((p) => p.recording)).toEqual([fresh]);
		// History still lists the old one.
		expect(hasProblem(stale)).toBe(true);
		expect(home).toContain('const problems = $derived(problemsOf(recordings, now));');
	});

	test('F4d: Copy text on a card clears the problem', () => {
		expect(problemCard).toContain('if (await copy.copy(finalTextOf(r))) await recovery.markCopied(r.id);');
		const mark = actions.slice(actions.indexOf('async markCopied(recordingId: string)'));
		expect(mark.slice(0, 300)).toContain("for (const prefix of ['tidy-up-', 'transcribe-', 'paste-'])");
		expect(mark.slice(0, 300)).toContain("await updateRecordingFields(recordingId, { result: 'copied' });");
	});
});

describe('F5, F6, F7: Nothing heard', () => {
	test('F5: Retry for recordings of 3 s or more; its audio waits a minute, a short tap goes at once', () => {
		expect(EMPTY_RETRY_MIN_MS).toBe(3000);
		expect(worthRetrying(2999)).toBe(false);
		expect(worthRetrying(3000)).toBe(true);
		expect(worthRetrying(undefined)).toBe(true);
		const empty = actions.slice(actions.indexOf('if (!transcribedText.trim())'), actions.indexOf('const transformationId = styleIdFor(overrides);'));
		expect(empty).toContain('const retry = worthRetrying(recording.durationMs);');
		expect(empty).toContain('{ retry },');
		expect(empty).toContain('if (retry) setTimeout(() => void removeAudioIfOff(recording.id), EMPTY_RETRY_AUDIO_MS);');
		expect(empty).toContain('else await removeAudioIfOff(recording.id);');
		expect(empty).not.toContain('notify.info');
		// The bar's Retry for it retries speech-to-text.
		expect(actions).toContain("kind === 'transcription' || kind === 'empty'");
		expect(notice).toMatch(/title: 'Nothing heard',[\s\S]*?buttons: pick\(view, \[\{ action: 'retry', label: 'Retry' \}\]\),/);
	});

	test('F6: the problems badge counts only problems he can act on', () => {
		const nothing = rec({ result: 'no-text' });
		expect(isNothingHeard(nothing)).toBe(true);
		expect(hasProblem(nothing)).toBe(false);
		expect(hasProblem(rec({ result: 'not-pasted', problemKind: 'paste', problemDismissed: true }))).toBe(false);
		// A failed transcription whose audio is gone can't be retried.
		const failed = { result: 'no-text', problemKind: 'transcription', transcriptionStatus: 'FAILED' } as const;
		expect(hasProblem(rec(failed))).toBe(true);
		expect(hasProblem(rec({ ...failed, audioRemovedAt: '2026-11-19T00:00:00Z' }))).toBe(false);
		expect(home).toContain("if (result === 'no-text' && isNothingHeard(r)) return { tone: '', lead: '', rest: 'Nothing heard' };");
	});

	test('F7: 0.9.10\'s "Empty input" tidy-ups become Nothing heard, once', () => {
		const leftover = rec({ result: 'not-pasted', problemKind: 'tidy-up', transcribedText: '  ', problem: 'Tidy-up failed: Empty input.' });
		const real = rec({ result: 'not-pasted', problemKind: 'tidy-up', transcribedText: 'Milo at Pinewood' });
		const deleted = rec({ ...leftover, deletedAt: '2026-11-18T00:00:00Z' });
		expect(emptyInputLeftovers([leftover, real, deleted])).toEqual([leftover]);
		const run = actions.slice(actions.indexOf('async function cleanUpEmptyInputRecords()'));
		expect(run.slice(0, 1200)).toContain("result: 'no-text',");
		expect(run.slice(0, 1200)).toContain("localStorage.setItem(EMPTY_INPUT_CLEANUP_KEY, 'done');");
		expect(actions).toContain('void cleanUpEmptyInputRecords()');
	});
});

describe('F8, F10, F11', () => {
	test('F8: no held code paths left, but Send anyway still works for old held dictations', () => {
		expect(actions).not.toContain('function reportHeld(');
		expect(actions).not.toContain("if (privacy === 'held') {");
		expect(actions).not.toContain('sendHeldRecording: defineMutation(');
		expect(contract).not.toContain("'held'");
		expect(contract).not.toContain("'send-anyway'");
		expect(actions).toContain('sendAnyway: (recordingId: string) => sendHeldRecording(recordingId),');
		expect(actions).toContain("if (stored.privacy !== 'held') return;");
		expect(problemOf(rec({ privacy: 'held' }))?.kind).toBe('held');
	});

	test('F10: warnings and errors reach the log file, title and description only', async () => {
		expect(notify).toContain("if (variant === 'error' || variant === 'warning') writeToLogFile(fullOptions);");
		expect(notify).toContain("void import('@tauri-apps/plugin-log')");
		const { logLine } = await import('./notify-line');
		expect(logLine({ title: 'Not pasted', description: 'Kite closed.\nTry again.' })).toBe(
			'[notice] Not pasted: Kite closed. Try again.',
		);
		expect(logLine({ title: 'Nothing heard', description: '' })).toBe('[notice] Nothing heard');
		expect(logLine({ title: 'x', description: 'y'.repeat(900) }).length).toBeLessThan(330);
	});

	test('F11: no developer statistic in the status bar', () => {
		expect(statusBar).not.toContain('checksSummary');
		expect(statusBar).not.toContain('Password-field checks');
	});
});
