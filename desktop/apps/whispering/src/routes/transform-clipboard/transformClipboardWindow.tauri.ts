import { invoke } from '@tauri-apps/api/core';
import { emit } from '@tauri-apps/api/event';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import { Err, Ok, tryAsync } from 'wellcrafted/result';

const WINDOW_LABEL = 'transform-clipboard';

let windowInstance: WebviewWindow | null = null;

/**
 * Toggles the transform clipboard window (show if hidden, hide if shown).
 * Creates window on first call, then toggles visibility for instant toggling.
 * Window reads clipboard directly when shown.
 */
export async function toggle(): Promise<void> {
	// Check if window already exists
	const existingWindow = await WebviewWindow.getByLabel(WINDOW_LABEL);

	if (existingWindow) {
		// Window exists, check if it's visible
		const isVisible = await existingWindow.isVisible();

		if (isVisible) {
			// Hide it
			await existingWindow.hide();
		} else {
			// Show the window
			await existingWindow.show();

			// Emit event to tell the page to open the combobox
			await emit('transform-clipboard-open-combobox');
		}
		windowInstance = existingWindow;
	} else {
		// Create new window (only happens once). VoiceFlow: built in Rust so
		// it gets the same WebView2 switches as every window
		// (src-tauri/src/webview_args.rs); a window created here would not.
		const { error } = await tryAsync({
			try: () => invoke('transform_window_create'),
			catch: (error) => Err(error),
		});
		if (error) console.error('Failed to create transform clipboard window:', error);
		windowInstance = await WebviewWindow.getByLabel(WINDOW_LABEL);
	}
}

/**
 * Hides the transform clipboard window (doesn't destroy it for fast re-opening)
 */
export async function hide(): Promise<void> {
	const existingWindow = await WebviewWindow.getByLabel(WINDOW_LABEL);
	if (existingWindow) {
		await tryAsync({
			try: () => existingWindow.hide(),
			catch: (error) => {
				console.error('Error hiding transform clipboard window:', error);
				return Ok(undefined);
			},
		});
	}
}
