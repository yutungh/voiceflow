import { invoke } from '@tauri-apps/api/core';
import { createTaggedError, extractErrorMessage } from 'wellcrafted/error';
import { tryAsync } from 'wellcrafted/result';
import type { WhisperingRecordingState } from '$lib/constants/audio';
import type { ThemeChoice } from '$lib/theme.svelte';

/**
 * VoiceFlow: the tray icon and its menu are built in Rust
 * (`src-tauri/src/tray.rs`) so they outlive the main webview. The webview
 * only reports state to it: whether a recording is in progress (icon and
 * status line) and the current theme (the tick in the Theme submenu). A
 * Theme picked in the tray arrives as the `tray://theme` event, handled by
 * `syncThemeWithDesktop`.
 */
const { SetTrayIconServiceErr } = createTaggedError('SetTrayIconServiceError');

export function createTrayIconDesktopService() {
	return {
		setTrayIcon: (recorderState: WhisperingRecordingState) =>
			tryAsync({
				try: () =>
					invoke<void>('tray_set_recording', {
						recording: recorderState === 'RECORDING',
					}),
				catch: (error) =>
					SetTrayIconServiceErr({
						message: `Failed to set tray icon: ${extractErrorMessage(error)}`,
					}),
			}),
		/** Ticks the tray's Theme item that matches the current theme. */
		setThemeChecks: (choice: ThemeChoice) =>
			tryAsync({
				try: () => invoke<void>('tray_set_theme', { choice }),
				catch: (error) =>
					SetTrayIconServiceErr({
						message: `Failed to update the tray theme menu: ${extractErrorMessage(error)}`,
					}),
			}),
	};
}

export type TrayIconService = ReturnType<typeof createTrayIconDesktopService>;

export const TrayIconServiceLive = createTrayIconDesktopService();
