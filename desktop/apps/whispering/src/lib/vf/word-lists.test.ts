import { describe, expect, test } from 'bun:test';
import type { ProfileState } from '$lib/profile/types';
import { syncSummary } from './word-lists';

function state(over: Partial<ProfileState['status']> & { enabled?: boolean } = {}): ProfileState {
	const { enabled = true, ...status } = over;
	return {
		config: { enabled, source: 'X', user: 'sam', assistant: 'Ada', device: 'dev1' },
		snapshot: {
			id: 's',
			user: 'sam',
			terms: [{ id: 'a', text: 'A', heardAs: [], priority: 50, apps: ['*'], layer: 'personal' }],
			corrections: [],
			styles: [],
			defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {} },
			privacy: {},
			apps: [],
			issues: [],
		},
		status: { refreshing: false, deferred: false, ...status },
	};
}

const report = (over: Partial<NonNullable<ProfileState['status']['report']>> = {}) => ({
	sourceReachable: true,
	attemptedAt: '2026-11-17T10:00:00Z',
	syncedAt: '2026-11-17T10:00:00Z',
	usingCache: false,
	layers: [],
	pendingEdits: 0,
	uploadedNow: 0,
	errors: [],
	...over,
});

describe('syncSummary', () => {
	test('off', () => {
		expect(syncSummary(state({ enabled: false })).tone).toBe('off');
		expect(syncSummary(null).tone).toBe('off');
	});
	test('up to date, with counts and pending edits', () => {
		const s = syncSummary(state({ report: report({ pendingEdits: 2 }) }));
		expect(s.tone).toBe('ok');
		expect(s.detail).toContain('1 word, 0 spellings');
		expect(s.detail).toContain('2 changes waiting to upload');
	});
	test('share unreachable says dictation still works', () => {
		const s = syncSummary(
			state({ lastSyncAt: '2026-11-17T09:00:00Z', report: report({ sourceReachable: false, syncedAt: undefined }) }),
		);
		expect(s.tone).toBe('warn');
		expect(s.detail).toContain('Dictation works as usual');
	});
	test('a refused GitHub token reads differently from a network problem', () => {
		const s = syncSummary(
			state({
				report: report({
					sourceReachable: false,
					syncedAt: undefined,
					sourceIssue: 'auth',
					errors: ['GitHub didn’t accept the token (it may have expired or been revoked).'],
				}),
			}),
		);
		expect(s.tone).toBe('warn');
		expect(s.title).toBe('GitHub didn’t accept the token');
		expect(s.detail).toContain('expired');
		expect(s.detail).toContain('Dictation works as usual');
		const offline = syncSummary(
			state({ report: report({ sourceReachable: false, syncedAt: undefined, sourceIssue: 'offline', errors: ['Can’t reach GitHub'] }) }),
		);
		expect(offline.title).toBe('Can’t reach your word lists');
	});
	test('a notice (uploads refused) is calm, not a warning', () => {
		const s = syncSummary(
			state({ report: report({ pendingEdits: 1, notices: ['GitHub refused uploads; your edits stay on this computer.'] }) }),
		);
		expect(s.tone).toBe('ok');
		expect(s.detail).toContain('GitHub refused uploads');
	});
	test('0.9.20: a refused sign-in reads by how this PC reaches GitHub', () => {
		const refused = (githubAuth: ProfileState['githubAuth']) => {
			const s = state({ report: report({ sourceReachable: false, syncedAt: undefined, sourceIssue: 'auth', errors: ['x'] }) });
			return syncSummary({ ...s, config: { ...s.config, kind: 'github' }, githubAuth }).title;
		};
		expect(refused('app')).toBe('Connect GitHub again');
		expect(refused('reconnect')).toBe('Connect GitHub again');
		expect(refused('')).toBe('Connect GitHub to read your word lists');
		expect(refused('pat')).toBe('GitHub didn’t accept the token');
	});
	test('a rejected file is named', () => {
		const s = syncSummary(state({ report: report({ errors: ['profiles/sam/profile.json was not used: x'] }) }));
		expect(s.tone).toBe('warn');
		expect(s.detail).toContain('was not used');
	});
});
