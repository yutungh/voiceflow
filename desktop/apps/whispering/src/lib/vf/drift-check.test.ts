/**
 * VoiceFlow 0.9.13: the language guard. The shared vectors
 * (`pack/vectors/drift.json`, which the Android app runs too) prove both
 * apps hold back the same tidy-ups; the rest covers the desktop's own
 * parts: the strict retry's prompt, the style's find-and-replace steps, and
 * a retry that fails.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Transformation, TransformationStep } from '$lib/services/isomorphic/db';
import {
	ADDED_MIN,
	REASON_HAN_ADDED,
	STRICT_LANGUAGE_NOTE,
	checkDrift,
	driftReasonText,
	guardedTidyUp,
	strictTransformation,
	withFindReplace,
	type ListedWord,
	type TidyAttempt,
} from './drift-check';

const vectors = JSON.parse(
	readFileSync(join(import.meta.dir, '../../../../../../pack/vectors/drift.json'), 'utf8'),
) as {
	check: Array<{
		name: string;
		source: string;
		output: string;
		/** 0.9.14: the tidy-up's word list; absent means none. */
		words?: ListedWord[];
		drift: boolean;
		reason: string;
		mixed: boolean;
	}>;
	guard: Array<{
		name: string;
		transcript: string;
		fun: boolean;
		words?: ListedWord[];
		answers: Array<string | null>;
		expect: { text: string; heldBack: string; reason: string; calls: boolean[] };
	}>;
};

/** A model that answers in order (null: the call fails) and records each call. */
function script(answers: Array<string | null>) {
	const calls: boolean[] = [];
	const attempt: TidyAttempt = async (strict) => {
		calls.push(strict);
		const answer = answers.shift();
		if (answer === undefined) throw new Error('called once too often');
		return answer === null ? { ok: false, reason: 'Tidy-up took over 10 seconds', timedOut: true } : { ok: true, output: answer };
	};
	return { attempt, calls };
}

describe('the shared vectors (pack/vectors/drift.json)', () => {
	test('there are enough of them', () => {
		expect(vectors.check.length).toBeGreaterThanOrEqual(12);
		expect(vectors.guard.length).toBeGreaterThanOrEqual(5);
		expect(ADDED_MIN).toBe(2);
	});
	for (const v of vectors.check) {
		test(`check: ${v.name}`, () => {
			expect(checkDrift(v.source, v.output, v.words ?? [])).toEqual({ drift: v.drift, reason: v.reason as never, mixed: v.mixed });
		});
	}
	for (const v of vectors.guard) {
		test(`guard: ${v.name}`, async () => {
			const model = script([...v.answers]);
			const out = await guardedTidyUp({
				transcript: v.transcript,
				funStyle: v.fun,
				words: v.words ?? [],
				attempt: model.attempt,
			});
			expect(out.ok).toBe(true);
			if (!out.ok) return;
			expect(out.text).toBe(v.expect.text);
			expect(out.heldBack).toBe(v.expect.heldBack);
			expect(out.fallbackReason).toBe(v.expect.reason as never);
			expect(model.calls).toEqual(v.expect.calls);
		});
	}
});

describe('the guard on this PC', () => {
	const SPOKEN = 'So, um, 我觉得 we should 先 finish the QBR deck, because, 那个, 他们 need it by Friday.';
	const DRIFTED = '我觉得 we should 先 finish the QBR deck，因为 他们 need it by Friday.';

	test('a failed first run is the usual tidy-up problem (nothing pasted)', async () => {
		const model = script([null]);
		const out = await guardedTidyUp({ transcript: SPOKEN, funStyle: false, attempt: model.attempt });
		expect(out).toEqual({ ok: false, reason: 'Tidy-up took over 10 seconds', timedOut: true });
		expect(model.calls).toEqual([false]);
	});

	test('a retry that fails keeps his words and holds the first tidy-up back (nothing lost)', async () => {
		const model = script([DRIFTED, null]);
		const out = await guardedTidyUp({ transcript: SPOKEN, funStyle: false, attempt: model.attempt });
		expect(out).toMatchObject({ ok: true, text: SPOKEN, heldBack: DRIFTED, fallbackReason: REASON_HAN_ADDED, retried: true });
		expect(model.calls).toEqual([false, true]);
	});

	test('never a third call, and the fallback keeps every filler as transcribed', async () => {
		const model = script([DRIFTED, DRIFTED]);
		const out = await guardedTidyUp({ transcript: `  ${SPOKEN}\n`, funStyle: false, attempt: model.attempt });
		expect(model.calls).toHaveLength(2);
		expect(out.ok && out.text).toBe(SPOKEN);
		expect(out.ok && out.text.includes('那个')).toBe(true);
	});

	test("a replacement the style makes is not drift (the style's own find-and-replace is applied to the transcript)", async () => {
		const spoken = '我们用 Cloud Code 和 Chachapiti 写 the QBR deck';
		const tidied = '我们用 Claude Code 和 ChatGPT 写 the QBR deck';
		const steps = [
			{ type: 'prompt_transform' },
			{ type: 'find_replace', 'find_replace.findText': '\\bCloud Code\\b', 'find_replace.replaceText': 'Claude Code', 'find_replace.useRegex': true },
			{ type: 'find_replace', 'find_replace.findText': 'Chachapiti', 'find_replace.replaceText': 'ChatGPT', 'find_replace.useRegex': false },
			{ type: 'find_replace', 'find_replace.findText': '(', 'find_replace.replaceText': 'x', 'find_replace.useRegex': true },
		] as unknown as TransformationStep[];
		expect(checkDrift(spoken, tidied).drift).toBe(true);
		const source = withFindReplace(spoken, steps);
		expect(source).toBe(tidied);
		expect(checkDrift(source, tidied).drift).toBe(false);
		const model = script([tidied]);
		const out = await guardedTidyUp({ transcript: spoken, source, funStyle: false, attempt: model.attempt });
		expect(out.ok && out.text).toBe(tidied);
		expect(model.calls).toEqual([false]);
	});

	test('the strict retry says the rule before the style input; the stored style is unchanged', () => {
		const style = {
			id: 's',
			title: 'Casual',
			description: '',
			createdAt: '',
			updatedAt: '',
			steps: [
				{ type: 'prompt_transform', 'prompt_transform.systemPromptTemplate': 'Rules.', 'prompt_transform.userPromptTemplate': '<transcript>\n{{input}}\n</transcript>' },
				{ type: 'find_replace', 'find_replace.findText': 'a', 'find_replace.replaceText': 'b', 'find_replace.useRegex': false },
			],
		} as unknown as Transformation;
		const strict = strictTransformation(style);
		expect(strict.steps[0]!['prompt_transform.userPromptTemplate']).toBe(
			`${STRICT_LANGUAGE_NOTE}\n\n<transcript>\n{{input}}\n</transcript>`,
		);
		expect(strict.steps[0]!['prompt_transform.systemPromptTemplate']).toBe('Rules.');
		expect(strict.steps[1]).toBe(style.steps[1]!);
		expect(style.steps[0]!['prompt_transform.userPromptTemplate']).toBe('<transcript>\n{{input}}\n</transcript>');
		// The same words as the Android app's retry.
		expect(STRICT_LANGUAGE_NOTE).toStartWith('Language check: an earlier attempt translated part of this transcript.');
	});

	test('reasons in plain words', () => {
		expect(driftReasonText('han-added')).toBe('Tidy-up put part of what you said into Chinese');
		expect(driftReasonText('swapped')).toContain('other language');
		expect(driftReasonText(undefined)).toContain('changed the language');
	});
});

describe('the Android source agrees (same thresholds and lists)', () => {
	const androidRoot = join(import.meta.dir, '../../../../../../app/src/main/java/com/voiceflowkeyboard/ime');
	const drift = readFileSync(join(androidRoot, 'DriftCheck.java'), 'utf8');
	const text = readFileSync(join(androidRoot, 'DictationText.java'), 'utf8');
	const mine = readFileSync(join(import.meta.dir, 'drift-check.ts'), 'utf8');
	const list = (src: string, name: string) =>
		[...(src.match(new RegExp(`${name}\\s*=\\s*(?:new Set\\(\\[|\\{|\\[)([^\\]}]*)`))?.[1] ?? '').matchAll(/"([^"]+)"|'([^']+)'/g)].map((m) => m[1] ?? m[2]);

	test('thresholds', () => {
		expect(drift).toContain('static final int ADDED_MIN = 2;');
		expect(text).toContain('KEEP_DIVISOR = 4;');
		expect(text).toContain('MIN_HAN = 4;');
		expect(text).toContain('MIN_LATIN_WORDS = 4;');
	});

	test('filler lists', () => {
		expect(list(mine, 'LATIN_FILLERS')).toEqual(list(drift, 'LATIN_FILLERS'));
		expect(list(mine, 'HAN_FILLERS')).toEqual(list(drift, 'HAN_FILLERS'));
		expect(list(mine, 'LATIN_FILLERS').length).toBeGreaterThan(30);
	});

	test('the strict note', () => {
		const java = [...(text.match(/STRICT_LANGUAGE_NOTE = ([\s\S]*?);/)?.[1] ?? '').matchAll(/"((?:[^"\\]|\\.)*)"/g)]
			.map((m) => m[1])
			.join('');
		expect(java).toBe(STRICT_LANGUAGE_NOTE);
	});
});
