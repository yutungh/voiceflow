/**
 * VoiceFlow 0.9.22: the provisioning file. A file prepared for one person
 * (`provision.json`) pre-fills this computer's settings once: API keys, a
 * few device settings and where the word lists come from. Placed in the
 * app's config folder it is imported at launch and then removed; Settings >
 * General > Import settings file imports one by hand.
 *
 * Pure (no app imports), so it is unit tested with bun. The app passes its
 * stores in as `ProvisionDeps` (`run.ts`).
 *
 * Rules:
 * - A strict, versioned format with an allowlist. Anything else is ignored
 *   and named in the result; a value on the allowlist that isn't valid stops
 *   the import before anything is written.
 * - API keys go to the OS credential store only, never to local storage.
 * - Every write is read back. The file is removed only after every read-back
 *   passed and a receipt was written, and only after a short wait and a
 *   second read-back (the webview writes its storage to disk a moment after
 *   a change). On any failure the file stays, so the next launch tries again;
 *   applying the same file twice gives the same result.
 * - Messages and the receipt name fields, never values.
 */

export const PROVISION_SCHEMA = 'voicelnq-provision';
export const PROVISION_VERSION = 1;
export const RECEIPT_SCHEMA = 'voicelnq-provision-receipt';
/** The launch import's file, in the app's config folder. */
export const PROVISION_FILE = 'provision.json';
export const RECEIPT_FILE = 'provision.applied.json';
/** The launch import's file once claimed (renamed before it is read). */
export const CLAIMED_FILE = 'provision.importing.json';
/** How long the launch import waits before its second read-back and removal. */
export const SETTLE_MS = 15_000;

/** API key providers the file may set (the credential store's names). */
export const PROVISION_API_KEYS = [
	'openai',
	'anthropic',
	'groq',
	'google',
	'deepgram',
	'elevenlabs',
	'mistral',
	'openrouter',
] as const;
export type ProvisionApiKey = (typeof PROVISION_API_KEYS)[number];

/** Device settings the file may set. */
export const PROVISION_SETTINGS = [
	'transcription.selectedTranscriptionService',
	'transcription.outputLanguage',
	'transcription.openai.model',
	'transformations.selectedTransformationId',
	'shortcuts.global.toggleManualRecording',
	'shortcuts.global.pushToTalk',
	'shortcuts.global.cancelManualRecording',
] as const;
export type ProvisionSetting = (typeof PROVISION_SETTINGS)[number];

const SHORTCUT_SETTINGS: readonly string[] = PROVISION_SETTINGS.filter((k) => k.startsWith('shortcuts.'));

export type WordListsPlan =
	| { kind: 'gateway'; url: string; user: string; device: string; token: string; assistant: string }
	| { kind: 'folder'; source: string; user: string; assistant: string };

export type ProvisionPlan = {
	apiKeys: Partial<Record<ProvisionApiKey, string>>;
	settings: Partial<Record<ProvisionSetting, string | null>>;
	wordLists: WordListsPlan | null;
};

export type Skipped = { field: string; reason: string };

export type ParseResult =
	| { ok: true; plan: ProvisionPlan; ignored: string[]; skipped: Skipped[] }
	| { ok: false; errors: string[]; ignored: string[] };

export type ParseOptions = {
	/** The app's own check of one setting's value (its settings schema). */
	isValidSetting: (key: ProvisionSetting, value: unknown) => boolean;
	/** Style ids a default style may name (the built-in styles). */
	knownStyleIds: readonly string[];
};

const MODIFIERS = new Set([
	'commandorcontrol',
	'cmdorctrl',
	'commandoralt',
	'cmdoralt',
	'command',
	'cmd',
	'control',
	'ctrl',
	'alt',
	'option',
	'shift',
	'super',
	'meta',
]);
const NAMED_KEYS = new Set([
	'space',
	'enter',
	'return',
	'tab',
	'escape',
	'esc',
	'backspace',
	'delete',
	'insert',
	'home',
	'end',
	'pageup',
	'pagedown',
	'up',
	'down',
	'left',
	'right',
	'plus',
	...Array.from({ length: 24 }, (_, i) => `f${i + 1}`),
]);

/** A global shortcut as the settings keep it: modifiers and one key, joined by `+`. */
export function validAccelerator(value: string): boolean {
	if (value.length > 64 || /\s/.test(value)) return false;
	const parts = value.split('+');
	if (parts.length < 2 || parts.length > 5) return false;
	const key = parts[parts.length - 1] ?? '';
	const modifiers = parts.slice(0, -1).map((m) => m.trim().toLowerCase());
	if (modifiers.some((m) => !MODIFIERS.has(m))) return false;
	if (new Set(modifiers).size !== modifiers.length) return false;
	if (key.length === 1) return /^[A-Za-z0-9`\-=[\]\\;',./]$/.test(key);
	return NAMED_KEYS.has(key.toLowerCase()) || /^digit[0-9]$|^key[a-z]$/i.test(key);
}

/** An API key: one line of visible characters. Its format is the provider's business. */
function validApiKey(value: unknown): value is string {
	return typeof value === 'string' && value.length >= 8 && value.length <= 512 && /^[\x21-\x7e]+$/.test(value);
}

const NAME_RE = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const TOKEN_RE = /^vfs1\.[A-Za-z0-9_-]{43}$/;

/** A sync server address: a bare https:// origin (the app's Rust check is the same). */
export function validGatewayUrl(value: unknown): value is string {
	if (typeof value !== 'string') return false;
	const v = value.trim().replace(/\/+$/, '');
	return /^https:\/\/[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*(:[0-9]{1,5})?$/.test(v);
}

/**
 * An unknown field's name for the result and the receipt. A name that could
 * be a pasted secret (long, odd characters, a key's prefix) is never repeated.
 */
function shownName(section: string, name: string): string {
	const looksSecret = name.length > 40 || !/^[A-Za-z][A-Za-z0-9._-]*$/.test(name) || /^(sk|vfs1|github_pat|ghp|xox)[-_.]/i.test(name);
	const shown = looksSecret ? '(a field)' : name;
	return section ? `${section}.${shown}` : shown;
}

function isObject(value: unknown): value is Record<string, unknown> {
	return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The file's text: a byte order mark and surrounding space are dropped. */
export function parseProvisionText(text: string, options: ParseOptions): ParseResult {
	let raw: unknown;
	try {
		raw = JSON.parse(text.replace(/^﻿/, '').trim());
	} catch {
		return { ok: false, errors: ['the file is not valid JSON'], ignored: [] };
	}
	return parseProvision(raw, options);
}

export function parseProvision(raw: unknown, options: ParseOptions): ParseResult {
	const errors: string[] = [];
	const ignored: string[] = [];
	const skipped: Skipped[] = [];
	if (!isObject(raw)) return { ok: false, errors: ['the file is not a settings file'], ignored };
	if (raw.schema !== PROVISION_SCHEMA) errors.push('schema (not a VoiceLnq settings file)');
	if (raw.version !== PROVISION_VERSION) errors.push(`version (this app reads version ${PROVISION_VERSION})`);
	if (errors.length > 0) return { ok: false, errors, ignored };

	for (const key of Object.keys(raw)) {
		if (!['schema', 'version', 'apiKeys', 'settings', 'wordLists', 'comment'].includes(key)) ignored.push(shownName('', key));
	}

	const plan: ProvisionPlan = { apiKeys: {}, settings: {}, wordLists: null };

	if (raw.apiKeys !== undefined) {
		if (!isObject(raw.apiKeys)) errors.push('apiKeys');
		else
			for (const [provider, value] of Object.entries(raw.apiKeys)) {
				if (!(PROVISION_API_KEYS as readonly string[]).includes(provider)) {
					ignored.push(shownName('apiKeys', provider));
					continue;
				}
				if (!validApiKey(value)) errors.push(`apiKeys.${provider}`);
				else plan.apiKeys[provider as ProvisionApiKey] = value;
			}
	}

	if (raw.settings !== undefined) {
		if (!isObject(raw.settings)) errors.push('settings');
		else
			for (const [key, value] of Object.entries(raw.settings)) {
				if (!(PROVISION_SETTINGS as readonly string[]).includes(key)) {
					ignored.push(shownName('settings', key));
					continue;
				}
				const setting = key as ProvisionSetting;
				if (!settingValid(setting, value, options)) errors.push(`settings.${key}`);
				else plan.settings[setting] = value as string | null;
			}
	}

	if (raw.wordLists !== undefined && raw.wordLists !== null) {
		const parsed = parseWordLists(raw.wordLists, errors, ignored, skipped);
		if (parsed) plan.wordLists = parsed;
	}

	if (errors.length > 0) return { ok: false, errors, ignored };
	return { ok: true, plan, ignored, skipped };
}

function settingValid(key: ProvisionSetting, value: unknown, options: ParseOptions): boolean {
	if (SHORTCUT_SETTINGS.includes(key)) {
		if (value !== null && (typeof value !== 'string' || !validAccelerator(value))) return false;
	} else if (key === 'transformations.selectedTransformationId') {
		// A built-in style: one that exists on every install. (Not Raw: a new
		// install's own default would take its place.)
		if (typeof value !== 'string' || !options.knownStyleIds.includes(value)) return false;
	} else if (key === 'transcription.openai.model') {
		if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/.test(value)) return false;
	} else if (typeof value !== 'string') {
		return false;
	}
	return options.isValidSetting(key, value);
}

function parseWordLists(raw: unknown, errors: string[], ignored: string[], skipped: Skipped[]): WordListsPlan | null {
	if (!isObject(raw)) {
		errors.push('wordLists');
		return null;
	}
	const str = (k: string) => (typeof raw[k] === 'string' ? (raw[k] as string).trim() : undefined);
	const user = str('user')?.toLowerCase();
	const assistant = str('assistant') ?? '';
	if (raw.assistant !== undefined && (typeof raw.assistant !== 'string' || assistant.length > 40))
		errors.push('wordLists.assistant');
	if (!user || !NAME_RE.test(user) || user === 'household') errors.push('wordLists.user');
	if (raw.kind === 'gateway') {
		for (const k of Object.keys(raw))
			if (!['kind', 'url', 'user', 'device', 'token', 'assistant'].includes(k)) ignored.push(shownName('wordLists', k));
		const url = str('url');
		const device = str('device');
		const token = str('token') ?? '';
		if (!validGatewayUrl(url)) errors.push('wordLists.url');
		if (!device || !NAME_RE.test(device)) errors.push('wordLists.device');
		if (errors.some((e) => e.startsWith('wordLists'))) return null;
		// No token yet (a placeholder): everything else is still imported.
		if (!TOKEN_RE.test(token)) {
			skipped.push({ field: 'wordLists', reason: 'no sync server token in the file yet' });
			return null;
		}
		return { kind: 'gateway', url: url!.replace(/\/+$/, '').toLowerCase(), user: user!, device: device!, token, assistant };
	}
	if (raw.kind === 'folder') {
		for (const k of Object.keys(raw))
			if (!['kind', 'source', 'user', 'assistant'].includes(k)) ignored.push(shownName('wordLists', k));
		const source = str('source');
		if (!source || source.length > 1024) errors.push('wordLists.source');
		if (errors.some((e) => e.startsWith('wordLists'))) return null;
		return { kind: 'folder', source: source!, user: user!, assistant };
	}
	errors.push('wordLists.kind (gateway or folder)');
	return null;
}

// ---------------------------------------------------------------- applying

/** The word-list settings as the app keeps them (the fields compared). */
export type WordListConfig = {
	enabled: boolean;
	kind?: string;
	source: string;
	user: string;
	assistant: string;
	device: string;
	gateway?: string;
};

/** What applying needs from the app (fakes in tests). */
export type ProvisionDeps = {
	keys: {
		/** Whether the OS credential store is usable (after it has loaded). */
		available: () => Promise<boolean>;
		/** Writes one key at once; true when the store took it. */
		write: (provider: ProvisionApiKey, value: string) => Promise<boolean>;
		/** Reads one key back; undefined when it couldn't be read. */
		read: (provider: ProvisionApiKey) => Promise<string | null | undefined>;
	};
	settings: {
		/** Writes device settings to this device's stored settings. */
		write: (values: Partial<Record<ProvisionSetting, string | null>>) => void | Promise<void>;
		/** This device's stored settings as saved (not the word lists' view). */
		readStored: () => Record<string, unknown> | null;
	};
	wordLists: {
		/** This install's device id, if it has one yet. */
		deviceId: () => string | null;
		/** This install's device id, made now if it has none. */
		ensureDeviceId: () => string;
		setDeviceId: (id: string) => void;
		/** Edit files made on this computer exist (they carry its device id). */
		hasLocalEdits: () => Promise<boolean>;
		setGatewayToken: (token: string) => Promise<void>;
		gatewayTokenStored: () => boolean;
		setConfig: (config: WordListConfig) => Promise<void>;
		config: () => WordListConfig | null;
	};
};

export type ApplyResult = {
	ok: boolean;
	/** Fields written and read back. */
	set: string[];
	/** Fields that failed, with a reason (never a value). */
	failed: Skipped[];
};

function wordListConfigOf(plan: WordListsPlan, device: string): WordListConfig {
	return plan.kind === 'gateway'
		? { enabled: true, kind: 'gateway', gateway: plan.url, source: '', user: plan.user, assistant: plan.assistant, device }
		: { enabled: true, kind: 'folder', source: plan.source, user: plan.user, assistant: plan.assistant, device };
}

function sameConfig(a: WordListConfig | null, b: WordListConfig): boolean {
	if (!a) return false;
	const kind = (k: string | undefined) => (k === 'github' || k === 'gateway' ? k : 'folder');
	return (
		a.enabled === b.enabled &&
		kind(a.kind) === kind(b.kind) &&
		a.user === b.user &&
		a.device === b.device &&
		(a.assistant ?? '') === b.assistant &&
		(b.kind === 'gateway' ? (a.gateway ?? '') === b.gateway : a.source === b.source)
	);
}

/** Applies a parsed file: keys, then device settings, then word lists. */
export async function applyProvision(plan: ProvisionPlan, deps: ProvisionDeps): Promise<ApplyResult> {
	const set: string[] = [];
	const failed: Skipped[] = [];

	const keys = Object.entries(plan.apiKeys) as [ProvisionApiKey, string][];
	if (keys.length > 0) {
		if (!(await deps.keys.available())) {
			for (const [p] of keys) failed.push({ field: `apiKeys.${p}`, reason: 'no OS credential store on this computer' });
		} else {
			for (const [provider, value] of keys) {
				const field = `apiKeys.${provider}`;
				if (!(await deps.keys.write(provider, value))) {
					failed.push({ field, reason: "the credential store didn't take it" });
					continue;
				}
				const back = await deps.keys.read(provider);
				if (back !== value) failed.push({ field, reason: "it didn't read back from the credential store" });
				else set.push(field);
			}
		}
	}
	// Keys first: without them nothing else is worth keeping half-done.
	if (failed.length > 0) return { ok: false, set, failed };

	const values = plan.settings;
	if (Object.keys(values).length > 0) {
		await deps.settings.write(values);
		const stored = deps.settings.readStored();
		for (const [key, value] of Object.entries(values)) {
			const field = `settings.${key}`;
			if (!stored || stored[key] !== value) failed.push({ field, reason: "it didn't read back from this device's settings" });
			else set.push(field);
		}
	}
	if (failed.length > 0) return { ok: false, set, failed };

	if (plan.wordLists) {
		const w = plan.wordLists;
		const current = deps.wordLists.deviceId();
		let device = current;
		if (w.kind === 'gateway') {
			// The token was issued for one device id; this install takes it,
			// unless edits made here already carry another id.
			if (current !== w.device) {
				if (current && (await deps.wordLists.hasLocalEdits())) {
					failed.push({
						field: 'wordLists.device',
						reason: 'this computer already has word-list edits under its own device id; issue the token for that id instead',
					});
					return { ok: false, set, failed };
				}
				deps.wordLists.setDeviceId(w.device);
			}
			device = w.device;
			if (deps.wordLists.deviceId() !== w.device) {
				failed.push({ field: 'wordLists.device', reason: "it didn't read back" });
				return { ok: false, set, failed };
			}
			await deps.wordLists.setGatewayToken(w.token);
			if (!deps.wordLists.gatewayTokenStored()) {
				failed.push({ field: 'wordLists.token', reason: "the credential store didn't take it" });
				return { ok: false, set, failed };
			}
			set.push('wordLists.device', 'wordLists.token');
		}
		if (!device) device = deps.wordLists.ensureDeviceId();
		const wanted = wordListConfigOf(w, device);
		await deps.wordLists.setConfig(wanted);
		if (!sameConfig(deps.wordLists.config(), wanted)) {
			failed.push({ field: 'wordLists', reason: "the word-list settings didn't read back" });
			return { ok: false, set, failed };
		}
		set.push('wordLists');
	}
	return { ok: failed.length === 0, set, failed };
}

/** Reads everything back again without writing: the fields that no longer match. */
export async function verifyProvision(plan: ProvisionPlan, deps: ProvisionDeps): Promise<string[]> {
	const wrong: string[] = [];
	for (const [provider, value] of Object.entries(plan.apiKeys) as [ProvisionApiKey, string][]) {
		if ((await deps.keys.read(provider)) !== value) wrong.push(`apiKeys.${provider}`);
	}
	const stored = deps.settings.readStored();
	for (const [key, value] of Object.entries(plan.settings)) {
		if (!stored || stored[key] !== value) wrong.push(`settings.${key}`);
	}
	if (plan.wordLists) {
		const w = plan.wordLists;
		const device = w.kind === 'gateway' ? w.device : deps.wordLists.deviceId();
		if (w.kind === 'gateway' && (deps.wordLists.deviceId() !== w.device || !deps.wordLists.gatewayTokenStored()))
			wrong.push('wordLists.token');
		if (!device || !sameConfig(deps.wordLists.config(), wordListConfigOf(w, device))) wrong.push('wordLists');
	}
	return wrong;
}

export type Receipt = {
	schema: typeof RECEIPT_SCHEMA;
	version: 1;
	appliedAt: string;
	from: 'launch' | 'manual';
	set: string[];
	skipped: Skipped[];
	ignored: string[];
};

/** The receipt: what was set and when; names only, never a value. */
export function receiptOf(args: {
	now: string;
	from: 'launch' | 'manual';
	set: string[];
	skipped: Skipped[];
	ignored: string[];
}): Receipt {
	return {
		schema: RECEIPT_SCHEMA,
		version: 1,
		appliedAt: args.now,
		from: args.from,
		set: [...args.set],
		skipped: args.skipped.map((s) => ({ field: s.field, reason: s.reason })),
		ignored: [...args.ignored],
	};
}

export type ImportIo = {
	writeReceipt: (text: string) => Promise<void>;
	removeSource: () => Promise<void>;
	wait: (ms: number) => Promise<void>;
	now: () => string;
	/** Called once everything is saved and read back, before any wait. */
	onApplied?: (receipt: Receipt) => void;
};

export type ImportOutcome =
	| { status: 'invalid'; errors: string[]; ignored: string[] }
	| { status: 'failed'; failed: Skipped[]; set: string[]; ignored: string[] }
	| { status: 'applied'; receipt: Receipt; removed: boolean; kept?: string };

/**
 * One import: parse, apply, receipt, then (launch import) wait, read back
 * again and remove the file. Nothing is removed unless every step passed.
 */
export async function importProvision(
	text: string,
	deps: ProvisionDeps,
	io: ImportIo,
	options: ParseOptions & { from: 'launch' | 'manual'; removeSource: boolean; settleMs?: number },
): Promise<ImportOutcome> {
	const parsed = parseProvisionText(text, options);
	if (!parsed.ok) return { status: 'invalid', errors: parsed.errors, ignored: parsed.ignored };
	const applied = await applyProvision(parsed.plan, deps);
	if (!applied.ok) return { status: 'failed', failed: applied.failed, set: applied.set, ignored: parsed.ignored };
	const receipt = receiptOf({ now: io.now(), from: options.from, set: applied.set, skipped: parsed.skipped, ignored: parsed.ignored });
	try {
		await io.writeReceipt(`${JSON.stringify(receipt, null, 2)}\n`);
	} catch {
		io.onApplied?.(receipt);
		return { status: 'applied', receipt, removed: false, kept: "the receipt couldn't be written" };
	}
	io.onApplied?.(receipt);
	if (!options.removeSource) return { status: 'applied', receipt, removed: false };
	await io.wait(options.settleMs ?? SETTLE_MS);
	const wrong = await verifyProvision(parsed.plan, deps);
	if (wrong.length > 0) return { status: 'applied', receipt, removed: false, kept: `these didn't read back: ${wrong.join(', ')}` };
	try {
		await io.removeSource();
	} catch {
		return { status: 'applied', receipt, removed: false, kept: "the file couldn't be removed" };
	}
	return { status: 'applied', receipt, removed: true };
}
