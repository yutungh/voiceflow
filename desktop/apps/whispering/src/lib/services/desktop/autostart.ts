import { invoke } from '@tauri-apps/api/core';
import { createTaggedError, extractErrorMessage } from 'wellcrafted/error';
import { tryAsync } from 'wellcrafted/result';

export const { AutostartServiceError, AutostartServiceErr } = createTaggedError(
	'AutostartServiceError',
);
export type AutostartServiceError = ReturnType<typeof AutostartServiceError>;

/**
 * Auto-start service for desktop platforms.
 * Enables/disables launching Whispering on system login.
 *
 * Platform-specific behavior:
 * - macOS: Creates Launch Agent in ~/Library/LaunchAgents/
 * - Windows: Adds registry entry to HKEY_CURRENT_USER\...\Run
 * - Linux: Creates .desktop file in ~/.config/autostart/
 *
 * VoiceFlow: goes through Rust (`src-tauri/src/tray.rs`) so the tray's
 * "Start with Windows" / "Open at login" tick stays in step. The login item
 * starts VoiceFlow with `--hidden`, in the tray only.
 */
export function createAutostartServiceDesktop() {
	return {
		/** Check if autostart is currently enabled for Whispering. */
		isEnabled: () =>
			tryAsync({
				try: () => invoke<boolean>('autostart_is_enabled'),
				catch: (error) =>
					AutostartServiceErr({
						message: `Failed to check autostart status: ${extractErrorMessage(error)}`,
					}),
			}),

		/** Enable autostart so Whispering launches on system login. */
		enable: () =>
			tryAsync({
				try: async () => {
					await invoke<boolean>('autostart_set', { enabled: true });
				},
				catch: (error) =>
					AutostartServiceErr({
						message: `Failed to enable autostart: ${extractErrorMessage(error)}`,
					}),
			}),

		/** Disable autostart so Whispering does not launch on system login. */
		disable: () =>
			tryAsync({
				try: async () => {
					await invoke<boolean>('autostart_set', { enabled: false });
				},
				catch: (error) =>
					AutostartServiceErr({
						message: `Failed to disable autostart: ${extractErrorMessage(error)}`,
					}),
			}),
	};
}

export type AutostartService = ReturnType<typeof createAutostartServiceDesktop>;

export const AutostartServiceLive = createAutostartServiceDesktop();
