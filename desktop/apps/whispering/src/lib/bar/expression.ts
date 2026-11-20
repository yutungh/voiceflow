/**
 * VoiceFlow A2: the expression control (0–4) and what each level tells the
 * tidy-up model. Mirrors the Android app's `Prefs.java` (expressionLabel,
 * expressionGuidance, promptForPreset) so both apps mean the same thing by
 * "Lively Work". The text is a contract: change it in both apps together.
 *
 * Desktop styles are the tidy-up "transformations" with free names until
 * phase C2 gives them the profile's stable ids, so a style's Android preset is
 * recognised from its name (`presetKindOf`). An unrecognised style gets the
 * general guidance.
 */
import type { Transformation } from '$lib/services/isomorphic/db';

export const EXPRESSION_RESERVED = 0;
export const EXPRESSION_SUBTLE = 1;
export const EXPRESSION_NATURAL = 2;
export const EXPRESSION_LIVELY = 3;
export const EXPRESSION_EXPRESSIVE = 4;
export const DEFAULT_EXPRESSION = EXPRESSION_NATURAL;

export const EXPRESSION_LABELS = [
	'Reserved',
	'Subtle',
	'Natural',
	'Lively',
	'Expressive',
] as const;

/** Android's preset ids, plus `custom` for a style it doesn't have. */
export type PresetKind =
	| 'raw'
	| 'casual'
	| 'business'
	| 'family'
	| 'partner'
	| 'fun_haiku'
	| 'fun_pirate'
	| 'fun_shakespeare'
	| 'fun_noir'
	| 'fun_wizard'
	| 'custom';

/** Clamps to 0–4 (Android `sanitizeExpression`); non-numbers become Natural. */
export function sanitizeExpression(expression: number): number {
	if (!Number.isFinite(expression)) return DEFAULT_EXPRESSION;
	return Math.max(
		EXPRESSION_RESERVED,
		Math.min(EXPRESSION_EXPRESSIVE, Math.round(expression)),
	);
}

export function expressionLabel(expression: number): string {
	return EXPRESSION_LABELS[sanitizeExpression(expression)] ?? 'Natural';
}

/** Names the Android app uses for its presets, and their usual synonyms. */
const NAMES: Array<[RegExp, PresetKind]> = [
	[/^raw$/, 'raw'],
	[/^(friends?|casual)$/, 'casual'],
	[/^(work|business|professional)$/, 'business'],
	[/^family$/, 'family'],
	[/^(partner|spouse)$/, 'partner'],
	[/haiku/, 'fun_haiku'],
	[/pirate/, 'fun_pirate'],
	[/shakespeare/, 'fun_shakespeare'],
	[/noir/, 'fun_noir'],
	[/wizard/, 'fun_wizard'],
];

/** The Android preset a desktop style corresponds to, from its name. */
export function presetKindOf(styleName: string | null | undefined): PresetKind {
	const name = (styleName ?? '').trim().toLowerCase();
	for (const [pattern, kind] of NAMES) if (pattern.test(name)) return kind;
	return 'custom';
}

const KINDS: readonly PresetKind[] = [
	'casual',
	'business',
	'family',
	'partner',
	'fun_haiku',
	'fun_pirate',
	'fun_shakespeare',
	'fun_noir',
	'fun_wizard',
	'custom',
];

/**
 * A word-list style's Android preset: 0.9.13, its `kind` when the word
 * lists give one (a fun_* kind this app doesn't know counts as custom);
 * else from its stable id (`partner`, `work`...), else from its label.
 */
export function presetKindOfProfileStyle(style: {
	id: string;
	label: string;
	kind?: string;
}): PresetKind {
	if (style.kind) return (KINDS as readonly string[]).includes(style.kind) ? (style.kind as PresetKind) : 'custom';
	const fromId = presetKindOf(style.id.replace(/[._-]+/g, ' '));
	return fromId === 'custom' ? presetKindOf(style.label) : fromId;
}

/** A fun style: defined in English, so the language guard never checks it. */
export function isFunKind(kind: PresetKind): boolean {
	return kind.startsWith('fun_');
}

/** Android `expressionGuidance(preset, expression)`, word for word. */
export function expressionGuidance(kind: PresetKind, expression: number): string {
	const level = sanitizeExpression(expression);
	let base: string;
	switch (level) {
		case EXPRESSION_RESERVED:
			base =
				'Use restrained punctuation and low emotional intensity. Do not add emojis, exclamation marks, or playful emphasis.';
			break;
		case EXPRESSION_SUBTLE:
			base =
				'Keep the delivery low-key and lightly conversational. Do not add emojis or extra enthusiasm.';
			break;
		case EXPRESSION_LIVELY:
			base =
				'Make the delivery animated and conversational with natural expressive punctuation, including an occasional exclamation mark when the message supports enthusiasm. You may add one fitting emoji only when the message already supports that emotion.';
			break;
		case EXPRESSION_EXPRESSIVE:
			base =
				'Use energetic, playful delivery and expressive punctuation, including exclamation marks when the message supports them. You may add up to two fitting emojis, but never manufacture affection, excitement, or sentiment.';
			break;
		default:
			base =
				"Match the speaker's natural conversational energy and punctuation, including a single exclamation mark when the meaning clearly supports enthusiasm. Add at most one fitting emoji only when the selected voice style permits it and the message clearly supports it.";
			break;
	}
	if (kind === 'partner' && level === EXPRESSION_EXPRESSIVE) {
		base =
			"Make an already affectionate or playful message feel unmistakably intimate, enthusiastic, and text-message-like for the speaker's spouse. " +
			'Actively use fitting partner-text conventions such as emphatic punctuation, playful repeated tildes, a text heart such as <3, or one or two affectionate emojis when the source already expresses that warmth. ' +
			"For example, the energy may resemble 'Love you!~~ <3' in English or '爱你哦～～❤️' in natural Simplified Chinese. " +
			'Adapt these conventions naturally to the target language instead of copying the examples. Never add affection, excitement, pet names, hearts, emojis, or playful punctuation to neutral logistics, conflict, bad news, or serious content.';
	} else if (kind === 'partner' && level === EXPRESSION_LIVELY) {
		base +=
			' For an affectionate spouse message, favor warm partner-text energy and one natural heart or affectionate marker when it fits.';
	}
	if (kind === 'business') {
		base +=
			' Because this is the Work style, do not add emojis or repeated or exaggerated punctuation. A single professional exclamation mark is welcome when the message supports enthusiasm, thanks, congratulations, encouragement, or a friendly greeting.';
	}
	if (kind === 'fun_haiku') {
		base =
			'Return exactly three lines in a 5-7-5 English haiku pattern. The expression level may influence word energy only; never add a title, label, explanation, fourth line, or emoji.';
	}
	return `${base} This setting controls presentation only: never change facts, intent, certainty, sentiment, relationship, or the speaker's underlying emotion.`;
}

/**
 * What is appended to a style's prompt (Android `promptForPreset(context,
 * preset, expression)`): nothing for Raw.
 */
export function expressionBlock(kind: PresetKind, expression: number, override?: string | null): string {
	if (kind === 'raw') return '';
	const block = `\n\nSelected expression level: ${expressionLabel(expression)}\n${expressionGuidance(kind, expression)}`;
	// 0.9.13: a word-list style's own override (Android
	// `expressionGuidanceForPreset`), after the guidance it overrides.
	const own = override?.trim();
	return own
		? `${block}\n\nVoice-style-specific expression override (takes precedence over conflicting guidance above):\n${own}`
		: block;
}

/**
 * A copy of the style with the expression guidance added to each tidy-up
 * prompt's instructions (the system prompt). Used only for a dictation whose
 * expression was chosen on the bar; the stored style is not changed.
 */
export function withExpression(
	style: Transformation,
	expression: number,
	kind: PresetKind = presetKindOf(style.title),
	override?: string | null,
): Transformation {
	const block = expressionBlock(kind, expression, override);
	if (!block) return style;
	return {
		...style,
		steps: style.steps.map((step) =>
			step.type === 'prompt_transform'
				? {
						...step,
						'prompt_transform.systemPromptTemplate': `${step['prompt_transform.systemPromptTemplate'].trimEnd()}${block}`,
					}
				: step,
		),
	};
}
