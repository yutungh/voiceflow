import { type Command, commandCallbacks } from '$lib/commands';
import { IS_MACOS } from '$lib/constants/platform';
import { defineMutation } from '$lib/query/client';
import { Ok } from 'wellcrafted/result';
import { nativeHotkeys } from '$lib/hotkeys/native-hotkeys.svelte';
import { desktopServices } from '$lib/services';
import type { Accelerator } from '$lib/services/desktop/global-shortcut-manager';

/**
 * Global shortcuts - desktop-only, require Tauri.
 * These use system-level global shortcuts that work even when the app is not focused.
 */
export const globalShortcuts = {
	registerCommand: defineMutation({
		mutationKey: ['shortcuts', 'registerCommandGlobally'] as const,
		mutationFn: ({
			command,
			// Parameter renamed to indicate it may contain legacy "CommandOrControl" syntax
			// Legacy format: "CommandOrControl+Shift+R" → Modern format: "Command+Shift+R" (macOS) or "Control+Shift+R" (Windows/Linux)
			accelerator: legacyAcceleratorString,
		}: {
			command: Command;
			accelerator: Accelerator;
		}) => {
			// VoiceFlow: the dictation keys are registered in Rust, so they work
			// while this webview sleeps (src/lib/hotkeys/native.ts). Like the
			// JS path below, a key another app holds is logged, not an error.
			if (nativeHotkeys.handles(command.id)) {
				return nativeHotkeys
					.bind(command.id, legacyAcceleratorString)
					.then(() => Ok(undefined));
			}
			// Convert legacy "CommandOrControl" syntax to platform-specific modifiers for backwards compatibility
			// This ensures users with old settings don't need to manually update their shortcuts
			const accelerator = legacyAcceleratorString.replace(
				'CommandOrControl',
				IS_MACOS ? 'Command' : 'Control',
			) as Accelerator;
			return desktopServices.globalShortcutManager.register({
				accelerator,
				callback: commandCallbacks[command.id],
				on: command.on,
			});
		},
	}),

	unregisterCommand: defineMutation({
		mutationKey: ['shortcuts', 'unregisterCommandGlobally'] as const,
		mutationFn: async ({ accelerator }: { accelerator: Accelerator }) => {
			// VoiceFlow: a dictation key is released by Rust.
			if (await nativeHotkeys.unbindAccelerator(accelerator)) return Ok(undefined);
			return await desktopServices.globalShortcutManager.unregister(
				accelerator,
			);
		},
	}),

	unregisterAll: defineMutation({
		mutationKey: ['shortcuts', 'unregisterAllGlobalShortcuts'] as const,
		mutationFn: async () =>
			desktopServices.globalShortcutManager.unregisterAll(),
	}),
};
