/**
 * VoiceFlow 0.9.13: a word-list style's version 2 fields when it runs: its
 * model for this PC's provider, and hidden styles left out of the lists.
 * (`profile/styles.ts` uses these; its guidance-as-description is checked
 * in the source.)
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { providerKey, styleModelFor, visibleProfileStyles } from './style-fields';
import type { ProfileSnapshot, ProfileStyle } from './types';

const base: ProfileStyle = { id: 'clean', label: 'Clean', prompt: 'Clean it.', layer: 'personal' };

describe('a word-list style as a tidy-up', () => {
	test('the model: per provider first, then its own, then the lists default, then the app', () => {
		expect(providerKey('OpenAI')).toBe('openai');
		expect(styleModelFor({ models: { openai: 'gpt-5.4', anthropic: 'claude-haiku-4-5' }, model: 'gpt-4o' }, 'OpenAI', 'x', 'app')).toBe('gpt-5.4');
		expect(styleModelFor({ models: { anthropic: 'claude-haiku-4-5' }, model: 'gpt-4o' }, 'OpenAI', 'x', 'app')).toBe('gpt-4o');
		expect(styleModelFor({ models: { anthropic: 'claude-haiku-4-5' } }, 'Anthropic', 'x', 'app')).toBe('claude-haiku-4-5');
		expect(styleModelFor({}, 'OpenAI', 'gpt-5.4-nano', 'app')).toBe('gpt-5.4-nano');
		expect(styleModelFor({}, 'OpenAI', undefined, 'app')).toBe('app');
	});

	test('hidden: left out of the lists', () => {
		const snap = { styles: [{ ...base, hidden: true }, { ...base, id: 'work', label: 'Work' }, { ...base, id: 'x', hidden: false }] } as unknown as ProfileSnapshot;
		expect(visibleProfileStyles(snap).map((s) => s.id)).toEqual(['work', 'x']);
		expect(visibleProfileStyles(null)).toEqual([]);
	});

	test('profile styles run with these fields (guidance as the description)', () => {
		const src = readFileSync(join(import.meta.dir, 'styles.ts'), 'utf8');
		expect(src).toContain('styleModelFor(style, provider, cleanupModel, DEFAULT_OPENAI_INFERENCE_MODEL)');
		expect(src).toContain("description: style.guidance?.trim() ?? ''");
	});
});
