import { invoke } from '@tauri-apps/api/core';
import { createTaggedError, extractErrorMessage } from 'wellcrafted/error';
import { tryAsync } from 'wellcrafted/result';

/**
 * VoiceFlow: provider API keys in the OS credential store (spec §2.3):
 * Windows Credential Manager or the macOS Keychain, through the Rust
 * commands in `src-tauri/src/secrets.rs`. The keys never touch localStorage
 * in the desktop app.
 */

export const { SecretsServiceError, SecretsServiceErr } = createTaggedError(
	'SecretsServiceError',
);
export type SecretsServiceError = ReturnType<typeof SecretsServiceError>;

/** Which store holds the keys. `none`: the platform has no supported store. */
export type SecretStoreKind = 'windows' | 'macos' | 'none';

/** Settings keys that hold API keys, and the provider name each is stored under. */
export const API_KEY_PROVIDERS = {
	'apiKeys.openai': 'openai',
	'apiKeys.anthropic': 'anthropic',
	'apiKeys.groq': 'groq',
	'apiKeys.google': 'google',
	'apiKeys.deepgram': 'deepgram',
	'apiKeys.elevenlabs': 'elevenlabs',
	'apiKeys.mistral': 'mistral',
	'apiKeys.openrouter': 'openrouter',
	'apiKeys.custom': 'custom',
} as const;

export type ApiKeySettingKey = keyof typeof API_KEY_PROVIDERS;

export const API_KEY_SETTING_KEYS = Object.keys(
	API_KEY_PROVIDERS,
) as ApiKeySettingKey[];

/** Plain-language name of the store, for Settings. */
export function secretStoreLabel(kind: SecretStoreKind | 'browser'): string {
	switch (kind) {
		case 'windows':
			return 'Windows Credential Manager';
		case 'macos':
			return 'macOS Keychain';
		case 'browser':
			return "this browser's local storage";
		case 'none':
			return 'local app storage (no OS keychain on this system)';
	}
}

export const SecretsServiceLive = {
	storeKind: () =>
		tryAsync({
			try: () => invoke<SecretStoreKind>('secret_store_kind'),
			catch: (error) =>
				SecretsServiceErr({
					message: `Could not find the OS credential store: ${extractErrorMessage(error)}`,
				}),
		}),

	get: (key: ApiKeySettingKey) =>
		tryAsync({
			try: () =>
				invoke<string | null>('secret_get', {
					provider: API_KEY_PROVIDERS[key],
				}),
			catch: (error) =>
				SecretsServiceErr({
					message: `Could not read the ${API_KEY_PROVIDERS[key]} API key: ${extractErrorMessage(error)}`,
				}),
		}),

	/** Stores a key; an empty value removes it. */
	set: (key: ApiKeySettingKey, value: string) =>
		tryAsync({
			try: () =>
				invoke<void>('secret_set', {
					provider: API_KEY_PROVIDERS[key],
					value,
				}),
			catch: (error) =>
				SecretsServiceErr({
					message: `Could not save the ${API_KEY_PROVIDERS[key]} API key: ${extractErrorMessage(error)}`,
				}),
		}),
};
