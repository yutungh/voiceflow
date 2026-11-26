import { describe, expect, test } from 'bun:test';
import {
	activeCorrections,
	applyCorrections,
	parseOp,
	validateNewCorrection,
	type CorrectionOp,
	type ProfileOp,
} from './ops';

let n = 0;
function correction(
	from: string[],
	to: string,
	apps: string[] = ['*'],
	createdAt = `2026-11-17T10:00:0${n % 10}.000Z`,
): CorrectionOp {
	n += 1;
	return {
		schema: 'voiceflow-op',
		version: 1,
		id: `dev1-${n}`,
		device: 'dev1',
		createdAt,
		type: 'correction',
		from,
		to,
		scope: { apps },
	};
}

function tombstone(target: string): ProfileOp {
	n += 1;
	return {
		schema: 'voiceflow-op',
		version: 1,
		id: `dev1-${n}`,
		device: 'dev1',
		createdAt: '2026-11-17T11:00:00.000Z',
		type: 'tombstone',
		target,
	};
}

describe('parseOp', () => {
	test('accepts a correction and lower-cases app ids', () => {
		const op = parseOp({
			schema: 'voiceflow-op',
			version: 1,
			id: 'pc-1',
			device: 'pc',
			createdAt: '2026-11-17T10:00:00Z',
			type: 'correction',
			from: [' Zefira ', ''],
			to: 'Zephyra',
			scope: { apps: ['Telegram.exe'] },
			extra: 'dropped',
		});
		expect(op).toEqual({
			schema: 'voiceflow-op',
			version: 1,
			id: 'pc-1',
			device: 'pc',
			createdAt: '2026-11-17T10:00:00Z',
			type: 'correction',
			from: ['Zefira'],
			to: 'Zephyra',
			scope: { apps: ['telegram.exe'] },
		});
	});

	test('accepts a tombstone', () => {
		const op = parseOp({
			schema: 'voiceflow-op',
			version: 1,
			id: 'pc-2',
			device: 'pc',
			createdAt: '2026-11-17T10:00:00Z',
			type: 'tombstone',
			target: 'pc-1',
		});
		expect(op?.type).toBe('tombstone');
	});

	test('rejects malformed operations', () => {
		const good = {
			schema: 'voiceflow-op',
			version: 1,
			id: 'pc-1',
			device: 'pc',
			createdAt: '2026-11-17T10:00:00Z',
			type: 'correction',
			from: ['a'],
			to: 'b',
			scope: { apps: ['*'] },
		};
		expect(parseOp(null)).toBeNull();
		expect(parseOp({ ...good, schema: 'other' })).toBeNull();
		expect(parseOp({ ...good, version: 2 })).toBeNull();
		expect(parseOp({ ...good, createdAt: 'yesterday' })).toBeNull();
		expect(parseOp({ ...good, from: [] })).toBeNull();
		expect(parseOp({ ...good, from: ['  '] })).toBeNull();
		expect(parseOp({ ...good, to: ' ' })).toBeNull();
		expect(parseOp({ ...good, scope: { apps: [] } })).toBeNull();
		expect(parseOp({ ...good, type: 'rename' })).toBeNull();
		expect(parseOp({ ...good, type: 'tombstone' })).toBeNull();
	});
});

describe('activeCorrections', () => {
	test('drops corrections a tombstone removes', () => {
		const a = correction(['zefira'], 'Zephyra');
		const b = correction(['versal'], 'Vercel');
		expect(activeCorrections([a, b, tombstone(a.id)])).toEqual([b]);
	});

	test('orders oldest first', () => {
		const late = correction(['x'], 'X', ['*'], '2026-11-17T12:00:00Z');
		const early = correction(['y'], 'Y', ['*'], '2026-11-17T09:00:00Z');
		expect(activeCorrections([late, early]).map((c) => c.to)).toEqual(['Y', 'X']);
	});
});

describe('applyCorrections', () => {
	test('replaces whole words, ignoring case', () => {
		const ops = [correction(['zefira'], 'Zephyra')];
		expect(applyCorrections('Ask ZEFIRA, not zefiran.', ops).text).toBe(
			'Ask Zephyra, not zefiran.',
		);
	});

	test('reports what changed', () => {
		const ops = [correction(['zefira', 'zefyra'], 'Zephyra')];
		const id = ops[0]!.id;
		expect(applyCorrections('zefira and Zefyra', ops).changes).toEqual([
			{ heard: 'zefira', written: 'Zephyra', id },
			{ heard: 'Zefyra', written: 'Zephyra', id },
		]);
	});

	test('matches multi-word forms across any spacing, longest first', () => {
		const ops = [
			correction(['cloud'], 'Cloud'),
			correction(['cloud code'], 'Claude Code'),
		];
		expect(applyCorrections('open cloud  code now', ops).text).toBe(
			'open Claude Code now',
		);
	});

	test('runs once: a replacement is not corrected again', () => {
		// Validation refuses chains; this guards the apply step on its own.
		const ops = [correction(['a'], 'b'), correction(['b'], 'c')];
		expect(applyCorrections('a b', ops).text).toBe('b c');
	});

	test('respects word boundaries for accented letters and digits', () => {
		const ops = [correction(['café'], 'Café Noir')];
		expect(applyCorrections('cafés and café2 and café.', ops).text).toBe(
			'cafés and café2 and Café Noir.',
		);
	});

	test('treats regex characters literally', () => {
		const ops = [correction(['c++'], 'C++ (lang)')];
		expect(applyCorrections('I like c++ a lot, not cxx', ops).text).toBe(
			'I like C++ (lang) a lot, not cxx',
		);
		const dotted = [correction(['node.js'], 'Node.js')];
		expect(applyCorrections('use node.js or nodexjs', dotted).text).toBe(
			'use Node.js or nodexjs',
		);
	});

	test('only uses corrections scoped to the app', () => {
		const ops = [correction(['phoebe'], 'Febe', ['kite.exe'])];
		expect(applyCorrections('hi phoebe', ops, 'Kite.exe').text).toBe('hi Febe');
		expect(applyCorrections('hi phoebe', ops, 'telegram.exe').text).toBe(
			'hi phoebe',
		);
		expect(applyCorrections('hi phoebe', ops, null).text).toBe('hi phoebe');
	});

	test('leaves a matching casing alone', () => {
		const ops = [correction(['zephyra'], 'Zephyra')];
		const result = applyCorrections('Zephyra and zephyra', ops);
		expect(result.text).toBe('Zephyra and Zephyra');
		expect(result.changes).toHaveLength(1);
	});

	test('does nothing with no corrections', () => {
		expect(applyCorrections('text', [])).toEqual({ text: 'text', changes: [] });
	});
});

describe('validateNewCorrection', () => {
	const everywhere = { apps: ['*'] };

	test('accepts a new spelling', () => {
		const active = [correction(['versal'], 'Vercel')];
		expect(
			validateNewCorrection(active, {
				from: ['Zefira'],
				to: 'Zephyra',
				scope: everywhere,
			}),
		).toEqual({ ok: true });
	});

	test('accepts a casing-only fix', () => {
		expect(
			validateNewCorrection([], { from: ['zephyra'], to: 'Zephyra', scope: everywhere })
				.ok,
		).toBe(true);
	});

	test('refuses empty or no-op input', () => {
		expect(validateNewCorrection([], { from: [' '], to: 'X', scope: everywhere }).ok).toBe(false);
		expect(validateNewCorrection([], { from: ['x'], to: ' ', scope: everywhere }).ok).toBe(false);
		expect(validateNewCorrection([], { from: ['X'], to: 'X', scope: everywhere }).ok).toBe(false);
		expect(validateNewCorrection([], { from: ['x', 'X'], to: 'Y', scope: everywhere }).ok).toBe(false);
		expect(validateNewCorrection([], { from: ['x'], to: 'Y', scope: { apps: [] } }).ok).toBe(false);
	});

	test('refuses a duplicate with a plain message', () => {
		const active = [correction(['zefira'], 'Zephyra')];
		const result = validateNewCorrection(active, {
			from: ['ZEFIRA'],
			to: 'Zephyra',
			scope: everywhere,
		});
		expect(result).toEqual({
			ok: false,
			message: 'VoiceLnq already writes “zefira” as “Zephyra”.',
		});
	});

	test('refuses a conflicting spelling for the same heard word', () => {
		const active = [correction(['zefira'], 'Zephyra')];
		const result = validateNewCorrection(active, {
			from: ['zefira'],
			to: 'Zefira',
			scope: everywhere,
		});
		expect(result.ok).toBe(false);
	});

	test('refuses chains in both directions', () => {
		const active = [correction(['versal'], 'Vercel')];
		// New "to" is an existing "from": versal would become something else.
		expect(
			validateNewCorrection(active, { from: ['verse'], to: 'Versal', scope: everywhere }).ok,
		).toBe(false);
		// New "from" is an existing "to": Vercel would be rewritten again.
		expect(
			validateNewCorrection(active, { from: ['vercel'], to: 'Vercel Inc', scope: everywhere })
				.ok,
		).toBe(false);
	});

	test('refuses a cycle', () => {
		const active = [correction(['a'], 'b')];
		expect(validateNewCorrection(active, { from: ['b'], to: 'a', scope: everywhere }).ok).toBe(
			false,
		);
	});

	test('allows the same heard word in apps that do not overlap', () => {
		const active = [correction(['phoebe'], 'Febe', ['kite.exe'])];
		expect(
			validateNewCorrection(active, {
				from: ['phoebe'],
				to: 'Phoebe',
				scope: { apps: ['telegram.exe'] },
			}).ok,
		).toBe(true);
		expect(
			validateNewCorrection(active, { from: ['phoebe'], to: 'Phoebe', scope: everywhere }).ok,
		).toBe(false);
	});

	test('ignores removed corrections once filtered', () => {
		const old = correction(['zefira'], 'Zephyra');
		const active = activeCorrections([old, tombstone(old.id)]);
		expect(
			validateNewCorrection(active, { from: ['zefira'], to: 'Zefira', scope: everywhere }).ok,
		).toBe(true);
	});
});
