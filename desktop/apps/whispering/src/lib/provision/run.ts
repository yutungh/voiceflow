/**
 * VoiceFlow 0.9.22: the provisioning file in the app (`provision.ts` holds
 * the rules and is unit tested). Two ways in:
 *
 * - At launch, in the main window only: `<app config folder>/provision.json`
 *   (on macOS `~/Library/Application Support/<identifier>/`, on Windows
 *   `%APPDATA%\<identifier>\`). Removed once everything is saved and read
 *   back; a receipt without values (`provision.applied.json`) stays.
 * - Settings > Startup > Import settings file: a file the person picks. That
 *   file is left where it is; they are told to delete it.
 */

import type { Settings } from '$lib/settings/settings';
import type { ApiKeySettingKey } from '$lib/services/desktop/secrets';
import {
	importProvision,
	CLAIMED_FILE,
	PROVISION_FILE,
	RECEIPT_FILE,
	type ImportOutcome,
	type ParseOptions,
	type ProvisionDeps,
	type WordListConfig,
} from './provision';

async function parseOptions(): Promise<ParseOptions> {
	const { type } = await import('arktype');
	const { Settings, getDefaultSettings } = await import('$lib/settings/settings');
	const { BUILT_IN_STYLES, PACK_BUILT_INS } = await import('$lib/vf/builtin-styles');
	const defaults = getDefaultSettings();
	return {
		isValidSetting: (key, value) => !(Settings({ ...defaults, [key]: value }) instanceof type.errors),
		knownStyleIds: [...PACK_BUILT_INS, ...BUILT_IN_STYLES].map((s) => s.id),
	};
}

async function appDeps(): Promise<ProvisionDeps> {
	const { settings } = await import('$lib/stores/settings.svelte');
	const { wordLists } = await import('$lib/profile/service.svelte');
	const { deviceId, storedDeviceId, setDeviceId, hasLocalEdits } = await import('$lib/profile/op-store');
	await wordLists.start();
	const keyOf = (provider: string) => `apiKeys.${provider}` as ApiKeySettingKey;
	return {
		keys: {
			available: () => settings.keyStoreAvailable(),
			write: (provider, value) => settings.writeApiKeyNow(keyOf(provider), value),
			read: (provider) => settings.readStoredApiKey(keyOf(provider)),
		},
		settings: {
			write: (values) => settings.writeDeviceValues(values as Partial<Settings>),
			readStored: () => settings.readSavedValues(),
		},
		wordLists: {
			deviceId: () => storedDeviceId(),
			ensureDeviceId: () => deviceId(),
			setDeviceId: (id) => setDeviceId(id),
			hasLocalEdits: () => hasLocalEdits(),
			setGatewayToken: (token) => wordLists.setGatewayToken(token),
			gatewayTokenStored: () => !!wordLists.state?.gatewayToken,
			setConfig: (config) => wordLists.setConfig({ ...config, kind: config.kind as 'folder' | 'gateway' }),
			config: () => (wordLists.state?.config as WordListConfig | undefined) ?? null,
		},
	};
}

/** Plain names for the fields, for notices. */
function label(field: string): string {
	if (field.startsWith('apiKeys.')) return `${field.slice('apiKeys.'.length)} API key`;
	const names: Record<string, string> = {
		'settings.transcription.selectedTranscriptionService': 'transcription service',
		'settings.transcription.outputLanguage': 'language',
		'settings.transcription.openai.model': 'transcription model',
		'settings.transformations.selectedTransformationId': 'default style',
		'settings.shortcuts.global.toggleManualRecording': 'dictation key',
		'settings.shortcuts.global.pushToTalk': 'hold-to-talk key',
		'settings.shortcuts.global.cancelManualRecording': 'cancel key',
		wordLists: 'word lists',
		'wordLists.token': 'sync server token',
		'wordLists.device': 'device id',
	};
	return names[field] ?? field;
}

async function tell(outcome: ImportOutcome, from: 'launch' | 'manual') {
	const { rpc } = await import('$lib/query');
	if (outcome.status === 'invalid') {
		rpc.notify.error.execute({
			title: "Couldn't import the settings file",
			description: `Not valid: ${outcome.errors.map(label).join(', ')}. Nothing was changed.${from === 'launch' ? ' The file was kept.' : ''}`,
			persist: true,
		});
		return;
	}
	if (outcome.status === 'failed') {
		rpc.notify.error.execute({
			title: "Couldn't finish importing the settings file",
			description: `Not saved: ${outcome.failed.map((f) => `${label(f.field)} (${f.reason})`).join('; ')}.${from === 'launch' ? ' The file was kept, so VoiceLnq tries again when it next starts.' : ''}`,
			persist: true,
		});
		return;
	}
	const set = [...new Set(outcome.receipt.set.map(label))].filter((l) => l !== 'device id' && l !== 'sync server token');
	const notes: string[] = [];
	for (const s of outcome.receipt.skipped) notes.push(`${label(s.field)} not set up: ${s.reason}.`);
	if (outcome.receipt.ignored.length > 0) notes.push(`Not used: ${outcome.receipt.ignored.join(', ')}.`);
	if (from === 'manual') notes.push('It holds your API key, so delete the file now.');
	rpc.notify.success.execute({
		title: 'Settings imported',
		description: [`Set: ${set.join(', ') || 'nothing'}.`, ...notes].join(' '),
		persist: notes.length > 0,
	});
}

async function configDir(): Promise<string> {
	const { appConfigDir } = await import('@tauri-apps/api/path');
	return appConfigDir();
}

async function writeReceipt(text: string) {
	const { exists, mkdir, writeTextFile } = await import('@tauri-apps/plugin-fs');
	const { join } = await import('@tauri-apps/api/path');
	const dir = await configDir();
	if (!(await exists(dir))) await mkdir(dir, { recursive: true });
	await writeTextFile(await join(dir, RECEIPT_FILE), text);
}

let launchRun: Promise<void> | null = null;

/**
 * The launch import: once per app start, main window only. Resolves once the
 * settings are applied (or there was nothing to do); the file is removed
 * later, after the second read-back.
 */
export function importProvisionFileAtLaunch(): Promise<void> {
	launchRun ??= (async () => {
		if (!window.__TAURI_INTERNALS__) return;
		const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow');
		if (getCurrentWebviewWindow().label !== 'main') return;
		const { exists, readTextFile, remove, rename } = await import('@tauri-apps/plugin-fs');
		const { join } = await import('@tauri-apps/api/path');
		const dir = await configDir();
		const dropped = await join(dir, PROVISION_FILE);
		// The file is claimed (renamed) before it is read, so a new one dropped
		// in meanwhile is never removed unread; a claimed one left by an import
		// that didn't finish is tried again.
		const file = await join(dir, CLAIMED_FILE);
		if (await exists(dropped)) await rename(dropped, file);
		if (!(await exists(file))) return;
		const text = await readTextFile(file);
		const [deps, options] = await Promise.all([appDeps(), parseOptions()]);
		let settled!: () => void;
		const ready = new Promise<void>((resolve) => (settled = resolve));
		// Startup goes on once the settings are applied (the notice says so);
		// the file is removed in the background after the second read-back.
		void importProvision(
			text,
			deps,
			{
				writeReceipt,
				onApplied: (receipt) => {
					void tell({ status: 'applied', receipt, removed: false }, 'launch');
					settled();
				},
				removeSource: () => remove(file),
				wait: (ms) => new Promise((r) => setTimeout(r, ms)),
				now: () => new Date().toISOString(),
			},
			{ ...options, from: 'launch', removeSource: true },
		)
			.then((outcome) => {
				if (outcome.status !== 'applied') void tell(outcome, 'launch');
				else if (outcome.removed) console.info('[provision] settings file applied and removed');
				else console.warn(`[provision] the settings file was kept: ${outcome.kept ?? 'unknown reason'}`);
			})
			.catch((error) => console.error('[provision] the settings file could not be imported', error))
			.finally(() => settled());
		await ready;
	})().catch((error) => {
		console.error('[provision] the settings file could not be imported', error);
	});
	return launchRun;
}

/** Settings > Startup > Import settings file. */
export async function importProvisionFileByHand(): Promise<void> {
	const { open } = await import('@tauri-apps/plugin-dialog');
	const picked = await open({
		multiple: false,
		directory: false,
		filters: [{ name: 'Settings file', extensions: ['json'] }],
	});
	if (!picked || typeof picked !== 'string') return;
	const { readTextFile } = await import('@tauri-apps/plugin-fs');
	const text = await readTextFile(picked);
	const [deps, options] = await Promise.all([appDeps(), parseOptions()]);
	const outcome = await importProvision(
		text,
		deps,
		{
			writeReceipt,
			removeSource: async () => {},
			wait: async () => {},
			now: () => new Date().toISOString(),
		},
		{ ...options, from: 'manual', removeSource: false },
	);
	await tell(outcome, 'manual');
}

