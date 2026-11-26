import { createPersistedState } from '@epicenter/svelte-utils';
import { nanoid } from 'nanoid/non-secure';
import { extractErrorMessage } from 'wellcrafted/error';
import { Ok, partitionResults } from 'wellcrafted/result';
import { commands } from '$lib/commands';
import type { RecordingMode } from '$lib/constants/audio';
import { rpc } from '$lib/query';
import {
	API_KEY_SETTING_KEYS,
	type ApiKeySettingKey,
	SecretsServiceLive,
	type SecretStoreKind,
	secretStoreLabel,
} from '$lib/services/desktop/secrets';
import {
	clearMovedKeys,
	migrateApiKeys,
} from '$lib/services/desktop/api-key-migration';
import {
	getDefaultSettings,
	parseStoredSettings,
	Settings,
} from '$lib/settings/settings';
import { migrateStoredSoundSettings } from '$lib/settings/sound-schema';
import { vadRecorder } from '$lib/stores/vad-recorder.svelte';
import { blockedUpdates, deviceOnly } from '$lib/profile/ownership';
import { wordLists } from '$lib/profile/service.svelte';
import {
	syncGlobalShortcutsWithSettings,
	syncLocalShortcutsWithSettings,
} from '$routes/(app)/_layout-utils/register-commands';

/**
 * Encapsulated settings object with controlled access.
 * Provides read-only access to settings values and methods for controlled mutations.
 */
export const settings = (() => {
	// VoiceFlow 0.9.6: an all-off sound profile saved earlier stays silent
	// (the new error switch would otherwise read as on).
	migrateStoredSoundSettings(window.localStorage, 'whispering-settings');
	// Private settings instance
	const _settings = createPersistedState({
		key: 'whispering-settings',
		schema: Settings,
		onParseError: (error) => {
			// For empty storage, return defaults
			if (error.type === 'storage_empty') {
				return getDefaultSettings();
			}

			// For JSON parse errors, return defaults
			if (error.type === 'json_parse_error') {
				console.error('Failed to parse settings JSON:', error.error);
				return getDefaultSettings();
			}

			// For schema validation failures, use our progressive validation
			if (error.type === 'schema_validation_failed') {
				return parseStoredSettings(error.value);
			}

			// For async validation (shouldn't happen with our schemas)
			if (error.type === 'schema_validation_async_during_sync') {
				console.warn('Unexpected async validation for settings');
				return parseStoredSettings(error.value);
			}

			// Fallback - should never reach here
			return getDefaultSettings();
		},
		// VoiceFlow C1: settings save quietly (design B3.1); no notice.
		onUpdateSuccess: () => {},
		onUpdateError: (err) => {
			rpc.notify.error.execute({
				title: 'Error updating settings',
				description: extractErrorMessage(err),
			});
		},
	});

	// ------------------------------------------------------------------
	// VoiceFlow: API keys live in the OS credential store (spec §2.3).
	//
	// In the desktop app the `apiKeys.*` values are held in memory (`secrets`)
	// and written to Windows Credential Manager / macOS Keychain; localStorage
	// only ever stores them as ''. Reads of `settings.value` see the merged
	// object, so callers are unchanged. A key found in localStorage (an older
	// install) is moved to the keychain on first run, then cleared there. The
	// web build, and a desktop with no supported store, keep upstream's
	// localStorage behaviour.
	// ------------------------------------------------------------------
	const isDesktop = Boolean(window.__TAURI_INTERNALS__);
	let useKeychain = $state(isDesktop);
	let apiKeyStoreKind = $state<SecretStoreKind | 'browser' | 'loading'>(
		isDesktop ? 'loading' : 'browser',
	);
	const emptySecrets = () =>
		Object.fromEntries(API_KEY_SETTING_KEYS.map((k) => [k, ''])) as Record<
			ApiKeySettingKey,
			string
		>;
	let secrets = $state.raw(emptySecrets());
	// 0.9.22: bumped by every immediate write (the provisioning file), so a
	// re-read that started before it never puts an older value back.
	let secretsGeneration = 0;
	let importingKeys = 0;
	const pendingWrites = new Map<
		ApiKeySettingKey,
		ReturnType<typeof setTimeout>
	>();

	function notifyKeychainError(description: string) {
		rpc.notify.error.execute({
			title: "Couldn't save your API key",
			description: `${description} The key works until VoiceLnq closes; enter it again if it is missing next time.`,
			persist: true,
		});
	}

	/** Writes one key to the store, debounced so typing doesn't write per keystroke. */
	function scheduleKeychainWrite(key: ApiKeySettingKey) {
		const existing = pendingWrites.get(key);
		if (existing) clearTimeout(existing);
		pendingWrites.set(
			key,
			setTimeout(async () => {
				pendingWrites.delete(key);
				// 0.9.22: kept while it runs, so an immediate write waits for it.
				const write = SecretsServiceLive.set(key, secrets[key]).then(({ error }) => {
					if (error) notifyKeychainError(error.message);
				});
				inFlightWrites.set(key, write);
				await write;
				if (inFlightWrites.get(key) === write) inFlightWrites.delete(key);
			}, 400),
		);
	}

	/** 0.9.22: a debounced write already sent for this key, if any. */
	const inFlightWrites = new Map<ApiKeySettingKey, Promise<void>>();

	/** Loads keys from the store and migrates any left in localStorage. */
	async function loadApiKeys() {
		if (!isDesktop) return;
		const { data: kind, error: kindError } =
			await SecretsServiceLive.storeKind();
		if (kindError || kind === 'none') {
			// No OS store: keep upstream's localStorage behaviour.
			useKeychain = false;
			apiKeyStoreKind = 'none';
			if (kindError) console.error(kindError);
			return;
		}
		apiKeyStoreKind = kind;

		// Only keys whose own write succeeded are cleared from localStorage;
		// a key whose write failed stays there and is moved on the next launch.
		const { secrets: next, moved } = await migrateApiKeys({
			keys: API_KEY_SETTING_KEYS,
			stored: _settings.value,
			write: SecretsServiceLive.set,
			read: SecretsServiceLive.get,
			onWriteError: (error) =>
				notifyKeychainError(extractErrorMessage(error)),
			onReadError: (error) => console.error(error),
		});
		secrets = next;
		if (moved.size > 0) {
			_settings.value = clearMovedKeys(_settings.value, moved);
			rpc.notify.info.execute({
				title: 'API keys moved',
				description: `Your API keys are now stored in ${secretStoreLabel(kind)}.`,
			});
		}
	}

	const apiKeysReady = loadApiKeys();

	// Another window may change a key: re-read the store when this one gains focus.
	if (isDesktop) {
		window.addEventListener('focus', async () => {
			await apiKeysReady;
			if (!useKeychain || pendingWrites.size > 0 || importingKeys > 0) return;
			const generation = secretsGeneration;
			const next = emptySecrets();
			for (const key of API_KEY_SETTING_KEYS) {
				const { data, error } = await SecretsServiceLive.get(key);
				if (error) return;
				next[key] = data ?? '';
			}
			if (
				pendingWrites.size === 0 &&
				importingKeys === 0 &&
				generation === secretsGeneration &&
				API_KEY_SETTING_KEYS.some((k) => next[k] !== secrets[k])
			) {
				secrets = next;
			}
		});
	}

	// ------------------------------------------------------------------
	// VoiceFlow B + C2: settings the word lists own (`$lib/profile/
	// ownership.ts`). Reads see the profile's values over the device's;
	// saves never copy them into localStorage, so the device's earlier
	// values stay there untouched (inert) while word lists are on.
	// ------------------------------------------------------------------
	let mergedCache: {
		base: Settings;
		secrets: Record<ApiKeySettingKey, string>;
		overlay: typeof wordLists.overlay;
		value: Settings;
	} | null = null;

	function readMerged(): Settings {
		const base = _settings.value;
		const overlay = wordLists.overlay;
		const currentSecrets = useKeychain ? secrets : null;
		if (
			mergedCache &&
			mergedCache.base === base &&
			mergedCache.secrets === currentSecrets &&
			mergedCache.overlay === overlay
		) {
			return mergedCache.value;
		}
		const value = { ...base, ...(currentSecrets ?? {}), ...overlay };
		mergedCache = {
			base,
			secrets: currentSecrets as Record<ApiKeySettingKey, string>,
			overlay,
			value,
		};
		return value;
	}

	/**
	 * Persists settings: API keys go to the keychain on desktop, and
	 * profile-owned keys keep what localStorage already has.
	 */
	function persist(requested: Settings) {
		const next = deviceOnly(requested, _settings.value, wordLists.overlay);
		if (!useKeychain) {
			_settings.value = next;
			return;
		}
		const stripped = { ...next };
		const changedKeys = API_KEY_SETTING_KEYS.filter(
			(key) => next[key] !== secrets[key],
		);
		for (const key of API_KEY_SETTING_KEYS) stripped[key] = '';
		if (changedKeys.length > 0) {
			const updated = { ...secrets };
			for (const key of changedKeys) updated[key] = next[key];
			secrets = updated;
			for (const key of changedKeys) scheduleKeychainWrite(key);
		}
		_settings.value = stripped;
	}

	/** A change to a word-list setting is ignored; say so in the console. */
	function warnOwned(updates: Partial<Settings>) {
		const ignored = blockedUpdates(updates, wordLists.overlay);
		if (ignored.length > 0)
			console.warn(
				`[settings] ${ignored.join(', ')} come from your word lists; the change was not saved on this device.`,
			);
	}

	// Private helper for shared reset logic
	function _resetShortcutDefaults(type: 'local' | 'global') {
		const defaultSettings = getDefaultSettings();

		// Build a partial settings object containing only the shortcuts we want to reset
		const updates = commands.reduce<Partial<Settings>>((acc, command) => {
			const shortcutKey = `shortcuts.${type}.${command.id}` as const;
			// Copy the default value for this specific shortcut from defaultSettings to our updates object
			acc[shortcutKey] = defaultSettings[shortcutKey];
			return acc;
		}, {});

		persist({
			...readMerged(),
			...updates,
		});
	}

	return {
		/**
		 * Read-only access to current settings values
		 */
		get value(): Settings {
			return readMerged();
		},

		/**
		 * Resolves once API keys have been read from the OS credential store
		 * (and migrated from localStorage). Await before using a key.
		 */
		apiKeysReady,

		/**
		 * Where API keys are stored: 'windows' (Credential Manager), 'macos'
		 * (Keychain), 'browser' (web build), 'none' (no OS store found) or
		 * 'loading'.
		 */
		get apiKeyStoreKind() {
			return apiKeyStoreKind;
		},

		/**
		 * Update multiple settings at once
		 * @param updates Partial settings object with keys to update
		 */
		update(updates: Partial<Settings>) {
			warnOwned(updates);
			persist({ ...readMerged(), ...updates });
		},

		/**
		 * Update a single setting key
		 * @param key The setting key to update
		 * @param value The new value for the setting
		 */
		updateKey<K extends keyof Settings>(key: K, value: Settings[K]) {
			warnOwned({ [key]: value } as Partial<Settings>);
			persist({ ...readMerged(), [key]: value });
		},

		/**
		 * 0.9.22, the provisioning file: whether API keys go to an OS
		 * credential store on this computer (once it has loaded).
		 */
		async keyStoreAvailable(): Promise<boolean> {
			await apiKeysReady;
			return useKeychain;
		},

		/**
		 * 0.9.22, the provisioning file: writes one API key to the OS
		 * credential store now (no debounce), replacing any write still
		 * waiting for that key. True when the store took it. Never writes to
		 * local storage.
		 */
		async writeApiKeyNow(key: ApiKeySettingKey, value: string): Promise<boolean> {
			await apiKeysReady;
			if (!useKeychain) return false;
			const waiting = pendingWrites.get(key);
			if (waiting) {
				clearTimeout(waiting);
				pendingWrites.delete(key);
			}
			importingKeys++;
			try {
				// An older write already on its way finishes first, never after.
				await inFlightWrites.get(key);
				const { error } = await SecretsServiceLive.set(key, value);
				if (error) return false;
				secretsGeneration++;
				secrets = { ...secrets, [key]: value };
				return true;
			} finally {
				importingKeys--;
			}
		},

		/** 0.9.22: one API key as the OS credential store has it (undefined: unreadable). */
		async readStoredApiKey(key: ApiKeySettingKey): Promise<string | null | undefined> {
			await inFlightWrites.get(key);
			if (pendingWrites.has(key)) return undefined;
			const { data, error } = await SecretsServiceLive.get(key);
			return error ? undefined : (data ?? null);
		},

		/**
		 * 0.9.22, the provisioning file: writes this device's own values,
		 * under any the word lists provide (their view is unchanged while
		 * they are on). Not for API keys.
		 */
		async writeDeviceValues(values: Partial<Settings>): Promise<void> {
			// After the keys' migration, so it can't race this write; API key
			// fields (never in `values`) keep what is stored.
			await apiKeysReady;
			_settings.value = { ..._settings.value, ...values };
		},

		/** 0.9.22: this device's settings as saved in local storage, or null. */
		readSavedValues(): Record<string, unknown> | null {
			try {
				const raw = window.localStorage.getItem('whispering-settings');
				const parsed: unknown = raw ? JSON.parse(raw) : null;
				return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
			} catch {
				return null;
			}
		},

		/** Whether a setting currently comes from the word lists (read-only here). */
		isProfileOwned(key: keyof Settings): boolean {
			return key in wordLists.overlay;
		},

		/**
		 * Reset all settings to their default values
		 */
		reset() {
			persist(getDefaultSettings());
		},

		/**
		 * Reset local shortcuts to their default values
		 */
		resetLocalShortcuts() {
			_resetShortcutDefaults('local');
			syncLocalShortcutsWithSettings();
		},

		/**
		 * Reset global shortcuts to their default values
		 */
		resetGlobalShortcuts() {
			_resetShortcutDefaults('global');
			syncGlobalShortcutsWithSettings();
		},

		/**
		 * Switches the recording mode and automatically stops any active recordings.
		 * This ensures a clean transition between recording modes.
		 */
		async switchRecordingMode(newMode: RecordingMode) {
			const toastId = nanoid();

			// First, stop all active recordings except the new mode
			const { errs } = await stopAllRecordingModesExcept(newMode);

			if (errs.length > 0) {
				// Even if stopping fails, we should still switch modes
				console.error('Failed to stop active recordings:', errs);
				rpc.notify.warning.execute({
					id: toastId,
					title: 'Recording may still be active',
					description:
						'Previous recording could not be stopped automatically. Please stop it manually.',
				});
			}

			// Update the settings if not already in new mode
			if (_settings.value['recording.mode'] !== newMode) {
				persist({
					...readMerged(),
					'recording.mode': newMode,
				});

				// Show success notification
				rpc.notify.success.execute({
					id: toastId,
					title: 'Recording mode switched',
					description: `Switched to ${newMode} recording mode`,
				});
			}

			return Ok(newMode);
		},
	};
})();

/**
 * Ensures only one recording mode is active at a time by stopping all other modes.
 * This prevents conflicts between different recording methods and ensures clean transitions.
 *
 * @returns Object containing array of errors that occurred while stopping recordings
 */
async function stopAllRecordingModesExcept(modeToKeep: RecordingMode) {
	const { data: recorderState } = await rpc.recorder.getRecorderState.fetch();

	// Each recording mode with its check and stop logic
	const recordingModes = [
		{
			mode: 'manual' as const,
			isActive: () => recorderState === 'RECORDING',
			stop: () => rpc.commands.stopManualRecording.execute(),
		},
		{
			mode: 'vad' as const,
			isActive: () => vadRecorder.state !== 'IDLE',
			stop: () => rpc.commands.stopVadRecording.execute(),
		},
	] satisfies {
		mode: RecordingMode;
		isActive: () => boolean;
		stop: () => Promise<unknown>;
	}[];

	// Filter to modes that need to be stopped
	const modesToStop = recordingModes.filter(
		(recordingMode) =>
			recordingMode.mode !== modeToKeep && recordingMode.isActive(),
	);

	// Create promises that wrap each stop call in try-catch
	const stopPromises = modesToStop.map(
		async (recordingMode) => await recordingMode.stop(),
	);

	// Execute all stops in parallel
	const results = await Promise.all(stopPromises);

	// Partition results into successes and errors
	const { errs } = partitionResults(results);

	return { errs };
}
