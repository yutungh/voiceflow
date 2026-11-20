import { setMode, userPrefersMode } from 'mode-watcher';

/**
 * The app's colour theme: System (follows the OS through
 * prefers-color-scheme), Light or Dark. mode-watcher stores the choice in
 * localStorage, so it survives restarts; the default is System.
 *
 * Settings > General > Appearance, the status bar and the tray menu all
 * read and write it through here.
 */
export type ThemeChoice = 'system' | 'light' | 'dark';

export const THEME_OPTIONS = [
	{ value: 'system', label: 'System' },
	{ value: 'light', label: 'Light' },
	{ value: 'dark', label: 'Dark' },
] as const satisfies { value: ThemeChoice; label: string }[];

export const theme = {
	get choice(): ThemeChoice {
		return userPrefersMode.current ?? 'system';
	},
	get label(): string {
		return (
			THEME_OPTIONS.find((o) => o.value === theme.choice)?.label ?? 'System'
		);
	},
	set(choice: ThemeChoice) {
		setMode(choice);
	},
};
