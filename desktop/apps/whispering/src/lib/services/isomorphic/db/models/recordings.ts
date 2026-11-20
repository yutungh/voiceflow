/**
 * Recording intermediate representation.
 *
 * This type represents the unified interface for recordings across the application.
 * It is NOT the storage format - different storage implementations use different formats:
 *
 * - Desktop: Stores metadata in markdown files (.md) and audio in separate files (.webm, .mp3)
 * - Web: Stores in IndexedDB with serialized audio (see RecordingStoredInIndexedDB)
 *
 * Both implementations read their storage format and convert it to this intermediate
 * representation for use in the UI layer.
 *
 * Audio access: Audio data is NOT stored in this intermediate representation. Instead, use:
 * - `db.recordings.getAudioBlob(id)` to fetch audio as a Blob
 * - `db.recordings.ensureAudioPlaybackUrl(id)` to get a playback URL
 * - `db.recordings.revokeAudioUrl(id)` to clean up cached URLs
 */
export type Recording = {
	id: string;
	title: string;
	subtitle: string;
	timestamp: string;
	createdAt: string;
	updatedAt: string;
	transcribedText: string;
	/**
	 * Recording lifecycle status:
	 * 1. Begins in 'UNPROCESSED' state
	 * 2. Moves to 'TRANSCRIBING' while audio is being transcribed
	 * 3. Marked as 'DONE' when transcription completes
	 * 4. Marked as 'FAILED' if transcription fails
	 */
	transcriptionStatus: 'UNPROCESSED' | 'TRANSCRIBING' | 'DONE' | 'FAILED';
	/**
	 * VoiceFlow (spec §2.1, §4.3): the app the recording was dictated into,
	 * captured at the hotkey, and how the recording was authorized. All
	 * optional: recordings made before M1c, uploads and the web build have
	 * none of them.
	 */
	appName?: string;
	/** Lower-case executable file name (Windows) or executable (macOS). */
	appExe?: string;
	/** Full path of the executable. Local only: used to show the app's icon. */
	appExePath?: string;
	appBundleId?: string;
	/** The window title. Local only: kept in history here, never sent. */
	windowTitle?: string;
	/** The window handle, to bring the window back for a later paste. */
	appWindowId?: string;
	appPid?: number;
	/** allowed | allowed-no-context | held (not uploaded) | sent-anyway */
	privacy?: 'allowed' | 'allowed-no-context' | 'held' | 'sent-anyway';
	/** Outcome of the password-field check at the hotkey. */
	passwordCheck?: string;
	/**
	 * VoiceFlow C1 (design B3.1 Home and History): what happened to the
	 * dictation, kept so problems stay visible until fixed or dismissed.
	 * All optional; older recordings have none of them.
	 */
	/** Length of the audio, in milliseconds. */
	durationMs?: number;
	/** pasted | copied (to the clipboard only, by choice) | not-pasted | no-text */
	result?: RecordingResult;
	/** Which step went wrong, so the right fix can be offered. */
	problemKind?: RecordingProblemKind;
	/** The cause, in one plain sentence. */
	problem?: string;
	/** The person dismissed the problem on Home. */
	problemDismissed?: boolean;
	/** The text VoiceFlow delivered: tidied, with saved spellings applied. */
	finalText?: string;
	/** The style that tidied it, by name, as it was then. */
	styleName?: string;
	/**
	 * 0.9.13: the language guard kept his own words, because tidy-up changed
	 * the language of part of what he said. The tidied text it held back
	 * ("Use cleaned"), the reason code, and the style that made it. Cleared
	 * once he uses it.
	 */
	heldBackText?: string;
	heldBackReason?: string;
	heldBackStyle?: string;
	/** Set when deleted: kept under Recently deleted for 30 days. */
	deletedAt?: string;
	/** Set when the audio was removed under Keep recordings; the text stays. */
	audioRemovedAt?: string;
};

export const RECORDING_RESULTS = [
	'pasted',
	'copied',
	'not-pasted',
	'no-text',
] as const;
export type RecordingResult = (typeof RECORDING_RESULTS)[number];

export const RECORDING_PROBLEM_KINDS = [
	'transcription',
	'tidy-up',
	'paste',
] as const;
export type RecordingProblemKind = (typeof RECORDING_PROBLEM_KINDS)[number];

/**
 * Serialized audio format for IndexedDB storage.
 *
 * This format is used to work around iOS Safari's limitations with storing Blob objects
 * in IndexedDB. Instead of storing the Blob directly (which can fail or become corrupted
 * on iOS), we deconstruct it into:
 * - arrayBuffer: The raw binary data
 * - blobType: The original MIME type (e.g., 'audio/webm', 'audio/wav')
 *
 * This can be reliably stored in IndexedDB on all platforms, including iOS Safari.
 * To reconstruct: new Blob([arrayBuffer], { type: blobType })
 *
 * @see /Users/braden/Code/whispering/.conductor/la-paz/docs/patterns/serialized-audio-pattern.md
 */
export type SerializedAudio = {
	arrayBuffer: ArrayBuffer;
	blobType: string;
};

/**
 * How a recording is actually stored in IndexedDB (storage format).
 *
 * This is NOT the intermediate representation used by the UI (see Recording type).
 *
 * Extends Recording with:
 * - `serializedAudio` field for storing audio data (see SerializedAudio type)
 * - Audio is stored in serialized format to work around iOS Safari Blob storage issues
 */
export type RecordingStoredInIndexedDB = Recording & {
	serializedAudio: SerializedAudio | undefined;
};

export type RecordingsDbSchemaV5 = {
	recordings: RecordingStoredInIndexedDB;
};

export type RecordingsDbSchemaV4 = {
	recordings: RecordingsDbSchemaV3['recordings'] & {
		// V4 added 'createdAt' and 'updatedAt' fields
		createdAt: string;
		updatedAt: string;
	};
};

export type RecordingsDbSchemaV3 = {
	recordings: RecordingsDbSchemaV1['recordings'];
};

export type RecordingsDbSchemaV2 = {
	recordingMetadata: Omit<RecordingsDbSchemaV1['recordings'], 'blob'>;
	recordingBlobs: { id: string; blob: Blob | undefined };
};

export type RecordingsDbSchemaV1 = {
	recordings: {
		id: string;
		title: string;
		subtitle: string;
		timestamp: string;
		transcribedText: string;
		blob: Blob | undefined;
		/**
		 * A recording
		 * 1. Begins in an 'UNPROCESSED' state
		 * 2. Moves to 'TRANSCRIBING' while the audio is being transcribed
		 * 3. Finally is marked as 'DONE' when the transcription is complete.
		 * 4. If the transcription fails, it is marked as 'FAILED'
		 */
		transcriptionStatus: 'UNPROCESSED' | 'TRANSCRIBING' | 'DONE' | 'FAILED';
	};
};
