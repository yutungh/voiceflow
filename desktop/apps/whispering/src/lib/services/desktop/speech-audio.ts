import { invoke } from '@tauri-apps/api/core';
import { createTaggedError, extractErrorMessage } from 'wellcrafted/error';
import { tryAsync } from 'wellcrafted/result';

/**
 * VoiceFlow (A0): the file(s) to upload for a recording. The Rust command in
 * `src-tauri/src/speech_audio.rs` turns any WAV into 16 kHz mono FLAC, split
 * into parts of at most ten minutes when one file would pass the 24 MB upload
 * limit. FFmpeg is not needed. Anything that isn't a WAV is refused, and the
 * caller uploads it as it is.
 */

export const { SpeechAudioServiceError, SpeechAudioServiceErr } =
	createTaggedError('SpeechAudioServiceError');
export type SpeechAudioServiceError = ReturnType<
	typeof SpeechAudioServiceError
>;

/**
 * Unpack the command's reply: a little-endian u32 count, then each part as a
 * u32 length followed by its bytes.
 */
export function unframeParts(buffer: ArrayBuffer): Uint8Array<ArrayBuffer>[] {
	const view = new DataView(buffer);
	if (buffer.byteLength < 4) throw new Error('Empty reply');
	const count = view.getUint32(0, true);
	const parts: Uint8Array<ArrayBuffer>[] = [];
	let offset = 4;
	for (let i = 0; i < count; i++) {
		if (offset + 4 > buffer.byteLength) throw new Error('Truncated reply');
		const length = view.getUint32(offset, true);
		offset += 4;
		if (offset + length > buffer.byteLength) throw new Error('Truncated reply');
		parts.push(new Uint8Array(buffer, offset, length));
		offset += length;
	}
	if (count === 0) throw new Error('No audio parts');
	return parts;
}

export const SpeechAudioServiceLive = {
	/** FLAC blob(s) to upload, in order. */
	prepareUpload: (blob: Blob) =>
		tryAsync({
			try: async () => {
				const bytes = new Uint8Array(await blob.arrayBuffer());
				const reply = await invoke<ArrayBuffer>('prepare_upload_audio', bytes);
				return unframeParts(reply).map(
					(part) => new Blob([part], { type: 'audio/flac' }),
				);
			},
			catch: (error) =>
				SpeechAudioServiceErr({
					message: `Could not prepare the recording for upload: ${extractErrorMessage(error)}`,
				}),
		}),
};
