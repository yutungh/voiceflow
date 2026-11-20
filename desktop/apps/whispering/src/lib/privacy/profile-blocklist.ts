/**
 * VoiceFlow B + C2: apps that the word lists add to the built-in privacy
 * list (`privacy.blockApps` in a profile). They can only add blocks, never
 * remove a built-in one. Set by the profile service whenever the merged
 * profile changes.
 *
 * The profile's window-title patterns (`privacy.titleDenyRe`) are matched
 * in the desktop app's Rust side when the target is captured, in linear
 * time (`TargetSnapshot.profileTitleDenied`): a pattern from a shared file
 * never runs in this webview, where a bad one could stall it.
 */

let blockApps: string[] = [];

export function setProfilePrivacy(privacy: { blockApps?: string[] } | null) {
	blockApps = (privacy?.blockApps ?? []).map((a) => a.toLowerCase());
}

/** The listed app id (exe name or bundle id) when the profile blocks it. */
export function profileBlockedApp(
	exe: string | null,
	bundle: string | null,
): string | null {
	if (exe && blockApps.includes(exe)) return exe;
	if (bundle && blockApps.includes(bundle)) return bundle;
	return null;
}
