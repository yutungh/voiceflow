/**
 * VoiceFlow 0.9.21: the feedback window's "use this style in this app" fix
 * may name a built-in style (or another made on this PC), with or without
 * word lists; apply writes the same `app-style` edit and Undo its tombstone.
 */
import { describe, expect, test } from 'bun:test';
import { activeAppStyles, parseOp, type ProfileOp } from '../profile/ops';
import type { ProfileSnapshot } from '$lib/profile/types';
import { applyFixes, undoFix, type Writers } from './apply';
import type { DictationEvidence } from './evidence';
import type { FeedbackRecord } from './log';
import { availableStyles, buildFixRequest } from './prompt';
import { checkSelection, reviewProposals, type ReviewContext } from './validate';

const SNAPSHOT: ProfileSnapshot = {
	id: 'snap-1',
	user: 'tester',
	terms: [],
	corrections: [],
	styles: [{ id: 'work', label: 'Work', prompt: 'p', layer: 'personal', expression: 2 }],
	defaults: { promptCharsEffective: 1000, promptTermsEffective: 50, fromLayer: {} },
	privacy: {},
	apps: [],
	issues: [],
};

const DEVICE = [
	{ id: 'vf-builtin-friends', name: 'Friends' },
	{ id: 'vf-builtin-ai-prompt', name: 'AI prompt' },
	{ id: 'Ab3xYz', name: 'My memo' },
];

function evidence(over: Partial<DictationEvidence> = {}): DictationEvidence {
	return {
		schema: 'voiceflow-dictation-evidence',
		version: 1,
		recordingId: 'rec1',
		updatedAt: '2026-11-18T00:00:00Z',
		appName: 'Telegram',
		appId: 'telegram.exe',
		rawText: 'see you at six',
		tidyOutput: 'I look forward to seeing you at six.',
		deliveredText: 'I look forward to seeing you at six.',
		styleId: 'profile:work',
		styleName: 'Work',
		expression: 2,
		outcome: 'pasted',
		pastedInto: 'Telegram',
		...over,
	};
}

const ctx = (over: Partial<ReviewContext> = {}): ReviewContext => ({
	evidence: evidence(),
	snapshot: SNAPSHOT,
	active: [],
	history: [],
	deviceStyles: DEVICE,
	...over,
});

const toFriends = { type: 'style_adjust', expressionDelta: null, styleId: 'vf-builtin-friends', reason: 'Chats are casual.' } as const;

function memoryWriters() {
	const ops: ProfileOp[] = [];
	let k = 0;
	const base = () => {
		k += 1;
		return { schema: 'voiceflow-op' as const, version: 1 as const, id: `dev1-op${k}`, device: 'dev1', createdAt: `2026-11-18T00:00:0${k}.000Z` };
	};
	const writers: Writers = {
		async saveCorrection() {
			return { ok: false, message: 'unused' };
		},
		async saveWord() {
			return { ok: false, message: 'unused' };
		},
		async saveAppStyle(s) {
			const op = parseOp({ ...base(), type: 'app-style', ...s });
			if (!op) return { ok: false, message: 'bad' };
			ops.push(op);
			return { ok: true, op };
		},
		async removeOp(target) {
			const op = parseOp({ ...base(), type: 'tombstone', target })!;
			ops.push(op);
			return op;
		},
	};
	return { ops, writers };
}

describe('feedback can switch an app to a built-in style', () => {
	test('the model is offered the built-ins and this PC’s styles by reference, never prompts', () => {
		const offered = availableStyles(SNAPSHOT, DEVICE);
		expect(offered).toEqual([
			{ id: 'work', name: 'Work' },
			{ id: 'vf-builtin-friends', name: 'Friends' },
			{ id: 'vf-builtin-ai-prompt', name: 'AI prompt' },
			{ id: 'vf-pc-.ab3x.yz', name: 'My memo' },
			{ id: 'raw', name: 'Raw (no tidy-up)' },
		]);
		const req = buildFixRequest({ evidence: evidence({ styleId: 'vf-builtin-friends', styleName: 'Friends' }), feedback: 'too stiff', snapshot: SNAPSHOT, deviceStyles: DEVICE });
		expect(req.data.style).toEqual({ id: 'vf-builtin-friends', name: 'Friends' });
		expect(req.data.styles_available.map((s) => s.id)).toContain('vf-builtin-ai-prompt');
	});

	test('a hidden word-list style with a built-in’s id hides that built-in from the model', () => {
		const hidden: ProfileSnapshot = {
			...SNAPSHOT,
			styles: [...SNAPSHOT.styles, { id: 'vf-builtin-friends', label: 'Old friends', prompt: 'p', layer: 'personal', hidden: true }],
		};
		const ids = availableStyles(hidden, DEVICE).map((s) => s.id);
		expect(ids).not.toContain('vf-builtin-friends');
		expect(ids).toContain('vf-builtin-ai-prompt');
	});

	test('reviewed with word lists on, and with them off (it used to need word lists)', () => {
		for (const snapshot of [SNAPSHOT, null]) {
			const { fixes, dropped } = reviewProposals([toFriends], ctx({ snapshot }));
			expect(dropped).toEqual([]);
			const fix = fixes[0]!;
			expect(fix.kind === 'style' && fix.style).toEqual({ id: 'vf-builtin-friends', name: 'Friends' });
			expect(fix.kind === 'style' && fix.label).toBe('In Telegram, use the Friends style');
		}
		// A style that isn't offered is still left out.
		const out = reviewProposals([{ ...toFriends, styleId: 'vf-builtin-pirate' }], ctx());
		expect(out.fixes).toEqual([]);
	});

	test('refused at apply when the built-in was deleted from this PC meanwhile', () => {
		const { fixes } = reviewProposals([toFriends], ctx());
		const gone = ctx({ deviceStyles: DEVICE.filter((d) => d.id !== 'vf-builtin-friends') });
		const checked = checkSelection(fixes, [{ key: fixes[0]!.key }], gone);
		expect(checked.ok).toBe(false);
		expect(!checked.ok && checked.message).toBe('The Friends style is no longer on this PC.');
	});

	test('apply writes the app-style edit naming the built-in; Undo is its tombstone', async () => {
		const c = ctx();
		const { fixes } = reviewProposals([toFriends], c);
		const record: FeedbackRecord = {
			schema: 'voiceflow-feedback-draft',
			version: 2,
			id: 'fb-1',
			device: 'dev1',
			createdAt: '2026-11-18T00:00:00Z',
			recordingId: 'rec1',
			rawText: '',
			feedback: 'too stiff for chat',
			result: {
				contract: 2,
				explanation: 'e',
				correctedText: null,
				issues: [{ layer: 'app-context', summary: 's', noFixReason: null }],
				proposals: [],
				fixes,
				dropped: [],
				observations: [],
				reviewedAgainst: 'rev-1',
			},
		} as unknown as FeedbackRecord;
		const { ops, writers } = memoryWriters();
		const review = { snapshot: SNAPSHOT, active: [], history: [], deviceStyles: DEVICE, revision: 'rev-1' };
		const applied = await applyFixes({
			record,
			selections: [{ key: fixes[0]!.key }],
			evidence: evidence(),
			review,
			revisionAfter: async () => 'rev-2',
			writers,
		});
		expect(applied.ok).toBe(true);
		expect(ops[0]).toMatchObject({ type: 'app-style', app: 'telegram.exe', style: 'vf-builtin-friends' });
		expect(activeAppStyles(ops).get('telegram.exe')?.style).toBe('vf-builtin-friends');
		const opId = applied.record.applied![0]!.opId;
		const undone = await undoFix({ record: applied.record, opId, removeOp: writers.removeOp, revisionAfter: async () => 'rev-3', isLive: async () => true });
		expect(undone.ok).toBe(true);
		expect(ops.at(-1)).toMatchObject({ type: 'tombstone', target: opId });
		expect(activeAppStyles(ops).get('telegram.exe')).toBeUndefined();
		expect(undone.ok && undone.message).toBeFalsy();
	});

	test('Undo of a fix Settings already replaced still writes its tombstone, and says nothing changed', async () => {
		const { fixes } = reviewProposals([toFriends], ctx());
		const { ops, writers } = memoryWriters();
		const record = {
			schema: 'voiceflow-feedback-draft',
			version: 2,
			id: 'fb-2',
			device: 'dev1',
			createdAt: '2026-11-18T00:00:00Z',
			recordingId: 'rec1',
			rawText: '',
			feedback: 'f',
			applied: [{ key: fixes[0]!.key, opId: 'dev1-old', appliedAt: '2026-11-18T00:00:00Z' }],
			result: { contract: 2, explanation: 'e', correctedText: null, issues: [], proposals: [], fixes, dropped: [], observations: [], reviewedAgainst: 'x' },
		} as unknown as FeedbackRecord;
		const undone = await undoFix({ record, opId: 'dev1-old', removeOp: writers.removeOp, revisionAfter: async () => 'r', isLive: async () => false });
		expect(undone.ok && undone.message).toBe('This fix was already replaced in Settings; the app’s current style is unchanged.');
		expect(ops.at(-1)).toMatchObject({ type: 'tombstone', target: 'dev1-old' });
	});
});
