/**
 * VoiceFlow C1: icons of the apps people dictate into. Filled in by the
 * desktop app (see `loadAppIcon`); everything else gets a lettered tile.
 */
const cache = $state<Record<string, string | null>>({});
const pending = new Set<string>();

function keyOf(exePath: string | null | undefined, exeName: string | null | undefined) {
	return (exePath || exeName || '').toLowerCase();
}

/** The icon's URL for an app, or null (then the lettered tile shows). */
export function appIconUrl(
	exePath: string | null | undefined,
	exeName: string | null | undefined,
	bundleId?: string | null,
): string | null {
	const key = keyOf(exePath, bundleId || exeName);
	if (!key) return null;
	if (!(key in cache) && !pending.has(key)) {
		pending.add(key);
		void loadAppIcon(exePath ?? null, bundleId ?? null).then((url) => {
			cache[key] = url;
			pending.delete(key);
		});
	}
	return cache[key] ?? null;
}

/**
 * Asks the desktop app for the icon (read from the .exe on Windows or the
 * bundle on macOS, cached as PNG in app data). Needs the full path or the
 * bundle id; an exe name alone gets the lettered tile.
 */
async function loadAppIcon(
	exePath: string | null,
	bundleId: string | null,
): Promise<string | null> {
	if (!window.__TAURI_INTERNALS__) return null;
	const looksLikeBundle = !!bundleId && bundleId.includes('.') && !bundleId.endsWith('.exe');
	if (!exePath && !looksLikeBundle) return null;
	try {
		const { invoke } = await import('@tauri-apps/api/core');
		return await invoke<string | null>('app_icon', {
			exePath,
			bundleId: looksLikeBundle ? bundleId : null,
		});
	} catch (error) {
		console.warn('[app-icons] could not read an icon', error);
		return null;
	}
}
