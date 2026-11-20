import { describe, expect, test } from 'bun:test';
import type { Transformation, TransformationStep } from '$lib/services/isomorphic/db';
import { tidyUpStatus } from './style-models';

function prompt(model: string, id = 'p1', provider = 'OpenAI'): TransformationStep {
	return {
		id,
		type: 'prompt_transform',
		'prompt_transform.inference.provider': provider,
		'prompt_transform.inference.provider.OpenAI.model': model,
		'prompt_transform.inference.provider.Groq.model': 'llama-3.3-70b-versatile',
		'prompt_transform.inference.provider.Anthropic.model': 'claude-sonnet-4-0',
	} as unknown as TransformationStep;
}

function style(title: string, steps: TransformationStep[]): Transformation {
	return { id: 's', title, description: '', steps } as unknown as Transformation;
}

describe('tidyUpStatus', () => {
	test('Raw (no style) says tidy-up is off', () => {
		const s = tidyUpStatus(undefined);
		expect(s.label).toBe('off');
		expect(s.detail).toContain('Raw');
	});

	test('a one-prompt style shows its model; the tooltip names the style and provider', () => {
		const s = tidyUpStatus(style('Casual', [prompt('gpt-5.4-mini')]));
		expect(s.label).toBe('gpt-5.4-mini');
		expect(s.detail).toBe('Style in use: Casual. It tidies your words with OpenAI gpt-5.4-mini.');
	});

	test('the model follows the step provider', () => {
		expect(tidyUpStatus(style('Work', [prompt('x', 'p1', 'Anthropic')])).label).toBe(
			'claude-sonnet-4-0',
		);
	});

	test('a style with no model chosen says so', () => {
		expect(tidyUpStatus(style('Casual', [prompt('   ')])).label).toBe('no model set');
	});

	test('a spellings-only style runs no tidy-up model', () => {
		const spelling = { id: 'f1', type: 'find_replace' } as unknown as TransformationStep;
		const s = tidyUpStatus(style('Names', [spelling]));
		expect(s.label).toBe('off');
		expect(s.detail).toContain('Names');
	});

	test('a style with several prompts lists the models it uses', () => {
		const s = tidyUpStatus(style('Notes', [prompt('gpt-5.4-mini'), prompt('gpt-5.4', 'p2')]));
		expect(s.label).toBe('gpt-5.4-mini +1');
		expect(s.detail).toBe(
			'Style in use: Notes. It tidies your words in 2 steps, with OpenAI gpt-5.4-mini, OpenAI gpt-5.4.',
		);
	});

	test('an untitled style is named as such', () => {
		expect(tidyUpStatus(style('  ', [prompt('gpt-5.4-mini')])).detail).toContain('Untitled style');
	});
});
