import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { desktopServices } from '$lib/services';
import { THEME_OPTIONS, theme, type ThemeChoice } from '$lib/theme.svelte';

/**
 * Keeps the desktop pieces outside the web view in step with the theme:
 * the native title bar (Windows draws it light or dark from the window
 * theme; null means follow the OS) and the tick in the tray's Theme menu.
 * A Theme picked in the tray (built in Rust) arrives as `tray://theme`.
 * Call during component initialisation, desktop app only.
 */
export function syncThemeWithDesktop() {
	$effect(() => {
		const choice = theme.choice;
		void getCurrentWindow()
			.setTheme(choice === 'system' ? null : choice)
			.catch(() => {});
		void desktopServices.tray.setThemeChecks(choice);
	});

	$effect(() => {
		const unlisten = listen<string>('tray://theme', ({ payload }) => {
			const option = THEME_OPTIONS.find((o) => o.value === payload);
			if (option) theme.set(option.value satisfies ThemeChoice);
		});
		return () => {
			void unlisten.then((stop) => stop());
		};
	});
}
