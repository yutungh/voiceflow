import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * VoiceFlow 0.9.6: a dictation's sounds follow what the bar shows. actions.ts
 * imports `$lib` modules that `bun test` cannot resolve, so its order is
 * checked in the source.
 */
const actions = readFileSync(join(import.meta.dir, 'actions.ts'), 'utf8');
const delivery = readFileSync(join(import.meta.dir, 'delivery.ts'), 'utf8');

function body(source: string, start: string): string {
	const from = source.indexOf(start);
	expect(from).toBeGreaterThan(-1);
	const next = source.indexOf('\nasync function ', from + start.length);
	const nextFn = source.indexOf('\nfunction ', from + start.length);
	const ends = [next, nextFn].filter((i) => i > -1);
	return source.slice(from, ends.length ? Math.min(...ends) : undefined);
}

describe('dictation sounds (VoiceFlow 0.9.6)', () => {
	test('done plays once, after delivery, and becomes error when the paste failed', () => {
		const transcribe = body(actions, 'async function transcribeAndDeliver(');
		expect(transcribe).not.toContain("execute('transcriptionComplete')");
		expect(transcribe.indexOf('deliverTranscriptionResult')).toBeLessThan(
			transcribe.indexOf("playDeliverySound(outcome, 'transcriptionComplete')"),
		);
		const tidy = body(actions, 'async function tidyUpAndDeliver(');
		expect(tidy).not.toContain("execute('transformationComplete')");
		expect(tidy.indexOf('deliverTransformationResult')).toBeLessThan(
			tidy.indexOf("playDeliverySound(delivered, 'transformationComplete')"),
		);
		const play = body(actions, 'function playDeliverySound(');
		expect(play).toContain("if (outcome === 'failed') playErrorSound();");
	});

	test('delivery reports failed exactly where the bar shows a paste failure', () => {
		// 0.9.11: one place shows a paste failure, and it returns failed;
		// a copy (nowhere to paste) is done.
		const failedPastes = delivery.match(/bar\.failed\(\s*[\w.]+,\s*'paste',/g) ?? [];
		expect(failedPastes.length).toBe(1);
		expect(delivery).toContain("if (quietToast) return delivered.result === 'not-pasted' ? 'failed' : 'done';");
		expect(body(delivery, "case 'not-pasted':")).toContain("return 'failed';");
		expect(body(delivery, "case 'copied':")).toContain("return 'done';");
		expect(delivery).toContain('return Ok(await deliver(');
	});

	test('a refused native recording plays its error once, even if its stop then fails', () => {
		const failed = body(actions, 'function onNativeFailed(');
		expect(failed).toContain('refusedNativeRecordings.has(id)');
		expect(failed).toContain('if (!refused) playErrorSound();');
		expect(failed).not.toContain('refusedNativeRecordings.delete(');
	});
});
