/**
 * VoiceFlow 0.9.17: which model finds fixes from feedback (Settings >
 * Feedback, "Model for finding fixes"). Pure, so it can be unit-tested.
 *
 * - The choice is kept per device in the desktop settings
 *   (`feedback.fixModel`), never in the word lists: the phones read those and
 *   refuse fields they don't know.
 * - Offered: every provider the app takes a key for whose answer can be held
 *   to the fix's JSON schema (OpenAI strict JSON schema, Anthropic structured
 *   outputs). Groq, Google, OpenRouter and custom endpoints are left out: the
 *   app's calls to them can't hold the answer to that schema reliably.
 * - Nothing chosen yet (Automatic): Claude Opus 5.5 when there is an
 *   Anthropic key, otherwise GPT-5.4 when there is an OpenAI key. Finding
 *   fixes is rare and quality-sensitive, so each provider's strongest model.
 * - A model the person chose stays chosen: if its key is missing, finding
 *   fixes waits for the key instead of going to another provider.
 */

/**
 * The setting, spread into the desktop settings schema (`settings.ts`):
 * `Provider|model`, or null until the person picks one.
 */
export const FIX_MODEL_SETTING = {
	'feedback.fixModel': 'string | null = null',
} as const;

/** In Automatic's order, and the picker's. */
export const FEEDBACK_PROVIDERS = ['Anthropic', 'OpenAI'] as const;
export type FeedbackProvider = (typeof FEEDBACK_PROVIDERS)[number];

export type FixModelOption = {
	provider: FeedbackProvider;
	model: string;
	/** Plain name, e.g. "Claude Sonnet 5.5". */
	name: string;
	/** A few words after the name in the picker. */
	note?: string;
};

/** The models offered, grouped by provider in this order. */
export const FIX_MODELS: readonly FixModelOption[] = [
	{ provider: 'Anthropic', model: 'claude-opus-5-5', name: 'Claude Opus 5.5', note: 'default' },
	{ provider: 'Anthropic', model: 'claude-sonnet-5-5', name: 'Claude Sonnet 5.5', note: 'faster' },
	{ provider: 'OpenAI', model: 'gpt-5.4', name: 'GPT-5.4', note: 'default' },
	{ provider: 'OpenAI', model: 'gpt-5.4-mini', name: 'GPT-5.4 mini', note: 'faster' },
];

/** Each provider's model when nothing is chosen. */
export const DEFAULT_FIX_MODEL: Record<FeedbackProvider, string> = {
	Anthropic: 'claude-opus-5-5',
	OpenAI: 'gpt-5.4',
};

/** The setting holding each provider's key. */
export const KEY_SETTING = {
	OpenAI: 'apiKeys.openai',
	Anthropic: 'apiKeys.anthropic',
} as const satisfies Record<FeedbackProvider, string>;

export type SettingsSection = 'transcription' | 'feedback';

/** Where each provider's key is added, by its name in the app. */
export const KEY_PLACE: Record<FeedbackProvider, { section: SettingsSection; label: string }> = {
	OpenAI: { section: 'transcription', label: 'Settings > Transcription' },
	Anthropic: { section: 'feedback', label: 'Settings > Feedback' },
};

/** Stored when the person picks "Automatic": no model chosen (an earlier 0.9.16 pick is ignored). */
export const AUTOMATIC = 'auto';

export type FixChoice = { provider: FeedbackProvider; model: string };
export type HasKey = Record<FeedbackProvider, boolean>;

/** The models 0.9.16 stored by default (not a choice the person made). */
const LEGACY_DEFAULT: FixChoice = { provider: 'OpenAI', model: 'gpt-5.4-mini' };

function isProvider(value: unknown): value is FeedbackProvider {
	return typeof value === 'string' && (FEEDBACK_PROVIDERS as readonly string[]).includes(value);
}

/** `Provider|model` as stored. */
export function choiceKey(choice: FixChoice): string {
	return `${choice.provider}|${choice.model}`;
}

export function parseChoiceKey(value: unknown): FixChoice | null {
	if (typeof value !== 'string') return null;
	const bar = value.indexOf('|');
	if (bar < 0) return null;
	const provider = value.slice(0, bar);
	const model = value.slice(bar + 1).trim();
	return isProvider(provider) && model ? { provider, model } : null;
}

/**
 * The model the person chose, or null when they haven't. `fixModel` is the
 * 0.9.17 setting; before it, 0.9.16 stored `feedback.provider` and
 * `feedback.model`, with OpenAI gpt-5.4-mini as the default (not a choice).
 */
export function storedChoice(values: {
	fixModel?: string | null;
	legacyProvider?: string | null;
	legacyModel?: string | null;
}): FixChoice | null {
	if (values.fixModel !== null && values.fixModel !== undefined) return parseChoiceKey(values.fixModel);
	const provider = values.legacyProvider;
	const model = values.legacyModel?.trim() || LEGACY_DEFAULT.model;
	if (!isProvider(provider)) return null;
	if (provider === LEGACY_DEFAULT.provider && model === LEGACY_DEFAULT.model) return null;
	return { provider, model };
}

/** Nothing chosen: the first provider with a key (Anthropic, then OpenAI), with its default model. */
export function defaultChoice(hasKey: HasKey): FixChoice | null {
	for (const provider of FEEDBACK_PROVIDERS)
		if (hasKey[provider]) return { provider, model: DEFAULT_FIX_MODEL[provider] };
	return null;
}

export type ResolvedFixModel =
	| { ok: true; choice: FixChoice; chosen: boolean }
	/** The chosen model's provider has no key. */
	| { ok: false; reason: 'chosen-needs-key'; choice: FixChoice }
	/** No provider has a key. */
	| { ok: false; reason: 'no-key' };

export function resolveFixModel(chosen: FixChoice | null, hasKey: HasKey): ResolvedFixModel {
	if (chosen) {
		return hasKey[chosen.provider]
			? { ok: true, choice: chosen, chosen: true }
			: { ok: false, reason: 'chosen-needs-key', choice: chosen };
	}
	const fallback = defaultChoice(hasKey);
	return fallback ? { ok: true, choice: fallback, chosen: false } : { ok: false, reason: 'no-key' };
}

/** The settings this reads (desktop settings, this device only). */
export type FixModelSettings = {
	'feedback.fixModel'?: string | null;
	'feedback.provider'?: string | null;
	'feedback.model'?: string | null;
	'apiKeys.openai'?: string;
	'apiKeys.anthropic'?: string;
};

export function hasKeys(values: FixModelSettings): HasKey {
	return {
		OpenAI: !!values[KEY_SETTING.OpenAI]?.trim(),
		Anthropic: !!values[KEY_SETTING.Anthropic]?.trim(),
	};
}

/** The model finding fixes would use now, from the settings as they are. */
export function fixModelFromSettings(values: FixModelSettings): ResolvedFixModel {
	return resolveFixModel(
		storedChoice({
			fixModel: values['feedback.fixModel'],
			legacyProvider: values['feedback.provider'],
			legacyModel: values['feedback.model'],
		}),
		hasKeys(values),
	);
}

/** "Claude Sonnet 5.5"; a model not in the list keeps its id. */
export function modelName(choice: FixChoice): string {
	return FIX_MODELS.find((m) => m.provider === choice.provider && m.model === choice.model)?.name ?? choice.model;
}

/** "Anthropic (Claude Sonnet 5.5)". */
export function providerAndModel(choice: FixChoice): string {
	return `${choice.provider} (${modelName(choice)})`;
}

/** The one line under Find fixes. */
export function findFixesLine(choice: FixChoice): string {
	return `Sends this dictation's text and your note to ${providerAndModel(choice)} with your own API key, to suggest fixes to your spellings and styles. Nothing is sent to VoiceLnq.`;
}

/**
 * What the Feedback window shows about the model: the line under Find fixes,
 * everything sent (built by `details`), or why it can't run and where the
 * key goes. Provider and model are '' when nothing can be used.
 */
export function fixModelView(
	resolved: ResolvedFixModel,
	details: (provider: FeedbackProvider, modelName: string) => string,
): {
	provider: string;
	model: string;
	modelName: string;
	disclosure: string;
	details: string;
	ready: boolean;
	keyNeeded?: { message: string; section: SettingsSection };
} {
	if (!resolved.ok)
		return { provider: '', model: '', modelName: '', disclosure: '', details: '', ready: false, keyNeeded: keyNeeded(resolved) };
	const { choice } = resolved;
	return {
		provider: choice.provider,
		model: choice.model,
		modelName: modelName(choice),
		disclosure: findFixesLine(choice),
		details: details(choice.provider, modelName(choice)),
		ready: true,
	};
}

/** Why Find fixes can't run yet, and where the key goes. */
export function keyNeeded(resolved: Extract<ResolvedFixModel, { ok: false }>): {
	message: string;
	section: SettingsSection;
} {
	if (resolved.reason === 'chosen-needs-key') {
		const { provider } = resolved.choice;
		return {
			message: `Finding fixes uses ${providerAndModel(resolved.choice)}, which needs your ${provider} API key. Add it in ${KEY_PLACE[provider].label}, or choose another model in Settings > Feedback.`,
			section: KEY_PLACE[provider].section,
		};
	}
	return {
		message: `Finding fixes needs your own OpenAI or Anthropic API key. Add an OpenAI key in ${KEY_PLACE.OpenAI.label} or an Anthropic key in ${KEY_PLACE.Anthropic.label}.`,
		section: 'feedback',
	};
}
