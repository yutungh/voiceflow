import { describe, expect, test } from 'bun:test';
import {
	expressionBlock,
	expressionGuidance,
	expressionLabel,
	presetKindOf,
	sanitizeExpression,
	withExpression,
} from './expression';
import type { Transformation } from '$lib/services/isomorphic/db';

describe('expression (mirrors Android Prefs.java)', () => {
	test('levels and labels', () => {
		expect([0, 1, 2, 3, 4].map(expressionLabel)).toEqual([
			'Reserved',
			'Subtle',
			'Natural',
			'Lively',
			'Expressive',
		]);
		expect(sanitizeExpression(-3)).toBe(0);
		expect(sanitizeExpression(7)).toBe(4);
		expect(sanitizeExpression(Number.NaN)).toBe(2);
	});

	test('style names map to Android presets', () => {
		expect(presetKindOf('Work')).toBe('business');
		expect(presetKindOf('friends')).toBe('casual');
		expect(presetKindOf('Family')).toBe('family');
		expect(presetKindOf('Partner')).toBe('partner');
		expect(presetKindOf('Haiku time')).toBe('fun_haiku');
		expect(presetKindOf('Raw')).toBe('raw');
		expect(presetKindOf('My email voice')).toBe('custom');
	});

	test('style-specific guidance', () => {
		const tail =
			" This setting controls presentation only: never change facts, intent, certainty, sentiment, relationship, or the speaker's underlying emotion.";
		expect(expressionGuidance('custom', 0)).toBe(
			`Use restrained punctuation and low emotional intensity. Do not add emojis, exclamation marks, or playful emphasis.${tail}`,
		);
		expect(expressionGuidance('business', 3)).toContain('Because this is the Work style, do not add emojis');
		expect(expressionGuidance('partner', 4)).toContain("for the speaker's spouse");
		expect(expressionGuidance('partner', 3)).toContain('warm partner-text energy');
		expect(expressionGuidance('fun_haiku', 4)).toStartWith('Return exactly three lines');
		for (const level of [0, 1, 2, 3, 4]) {
			expect(expressionGuidance('casual', level)).toEndWith(tail);
		}
	});

	test('the prompt block, and nothing for Raw', () => {
		expect(expressionBlock('raw', 4)).toBe('');
		expect(expressionBlock('casual', 3)).toStartWith('\n\nSelected expression level: Lively\n');
	});

	test('withExpression adds the block to prompt steps only, without changing the stored style', () => {
		const style = {
			id: 't',
			title: 'Work',
			description: '',
			createdAt: '',
			updatedAt: '',
			steps: [
				{ id: 'p', type: 'prompt_transform', 'prompt_transform.systemPromptTemplate': 'Tidy it.  ' },
				{ id: 'f', type: 'find_replace', 'prompt_transform.systemPromptTemplate': '' },
			],
		} as unknown as Transformation;
		const out = withExpression(style, 0);
		const [prompt, find] = out.steps;
		expect(prompt?.['prompt_transform.systemPromptTemplate']).toStartWith(
			'Tidy it.\n\nSelected expression level: Reserved\n',
		);
		expect(prompt?.['prompt_transform.systemPromptTemplate']).toContain('Work style');
		expect(find?.['prompt_transform.systemPromptTemplate']).toBe('');
		expect(style.steps[0]?.['prompt_transform.systemPromptTemplate']).toBe('Tidy it.  ');
		const raw = { ...style, title: 'Raw' } as Transformation;
		expect(withExpression(raw, 4)).toBe(raw);
	});
});
