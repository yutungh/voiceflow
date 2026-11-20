/**
 * VoiceFlow: what a recording keeps about the app it was dictated into
 * (spec §2.1 "the snapshot travels with the recording"). The window title is
 * local only: it stays in history on this computer and is never sent.
 */
import type { Recording } from '$lib/services/isomorphic/db';
import type { RecordingPrivacy, TargetSnapshot } from './policy';

export type RecordingTargetFields = Pick<
	Recording,
	| 'appName'
	| 'appExe'
	| 'appExePath'
	| 'appBundleId'
	| 'windowTitle'
	| 'appWindowId'
	| 'appPid'
	| 'privacy'
	| 'passwordCheck'
>;

/** Only the fields that have a value (front matter cannot hold undefined). */
export function recordingTargetFields(
	target: TargetSnapshot,
	privacy: RecordingPrivacy,
): RecordingTargetFields {
	const fields: RecordingTargetFields = { privacy };
	if (target.platform === 'web') return fields;
	if (target.appName) fields.appName = target.appName;
	if (target.exeName) fields.appExe = target.exeName;
	if (target.exePath) fields.appExePath = target.exePath;
	if (target.bundleId) fields.appBundleId = target.bundleId;
	if (target.title) fields.windowTitle = target.title;
	if (target.windowId) fields.appWindowId = target.windowId;
	if (typeof target.pid === 'number') fields.appPid = target.pid;
	fields.passwordCheck = target.passwordCheck;
	return fields;
}

/**
 * VoiceFlow C1: the target a recording was made for, rebuilt from what it
 * keeps, so Retry and Paste raw text from History or Home can bring that
 * window back. Null when it was never identified.
 */
export function targetFromRecording(recording: Recording): TargetSnapshot | null {
	if (!recording.appWindowId && !recording.appName) return null;
	return {
		platform: recording.appBundleId ? 'macos' : 'windows',
		resolved: !!recording.appWindowId,
		isSelf: false,
		appName: recording.appName ?? null,
		exeName: recording.appExe ?? null,
		exePath: recording.appExePath ?? null,
		bundleId: recording.appBundleId ?? null,
		title: recording.windowTitle ?? null,
		windowId: recording.appWindowId ?? null,
		pid: recording.appPid ?? null,
		samples: 0,
		identityMs: 0,
		passwordCheck: 'not-checked',
		passwordCheckMs: 0,
		error: null,
	};
}

/** The id a spelling's app scope uses: exe name, else bundle id. */
export function appIdOf(
	recording: Pick<Recording, 'appExe' | 'appBundleId'>,
): string | null {
	return recording.appExe?.toLowerCase() ?? recording.appBundleId ?? null;
}
