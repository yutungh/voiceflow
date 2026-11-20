/**
 * VoiceFlow C1: the dictation in progress, for the "Dictating into" chip on
 * Home. Set when recording starts (after the app in front is captured) and
 * cleared when it stops or is cancelled. The floating bar (phase A2) can
 * read the same state.
 */
type CurrentDictation = {
	appName: string | null;
	appExe: string | null;
	appExePath: string | null;
	appBundleId: string | null;
	startedAt: number;
};

let current = $state<CurrentDictation | null>(null);

export const dictation = {
	get current() {
		return current;
	},
	start(target: {
		appName: string | null;
		exeName: string | null;
		exePath: string | null;
		bundleId: string | null;
	}) {
		current = {
			appName: target.appName,
			appExe: target.exeName,
			appExePath: target.exePath,
			appBundleId: target.bundleId,
			startedAt: Date.now(),
		};
	},
	clear() {
		current = null;
	},
};
