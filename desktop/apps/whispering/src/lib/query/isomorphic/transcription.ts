import { Err, Ok, partitionResults, type Result } from 'wellcrafted/result';
import { defineMutation, queryClient } from '$lib/query/client';
import { WhisperingErr, type WhisperingError } from '$lib/result';
import { desktopServices, services } from '$lib/services';
import type { Recording } from '$lib/services/isomorphic/db';
import { settings } from '$lib/stores/settings.svelte';
import { dictationTiming } from '$lib/timing';
import { rpc } from '..';
import { notify } from './notify';
import { patchRecording } from './outcome';
import { appIdOf } from '$lib/privacy/history';
import { buildPrompt } from '$lib/profile/prompt';
import { profilePins } from '$lib/profile/service.svelte';

/**
 * VoiceFlow B + C2: the vocabulary prompt for one dictation. With word lists
 * on, it is built from the snapshot pinned when the dictation started, with
 * the words for the app it was dictated into; otherwise the device's prompt.
 */
function promptFor(recording: Recording): VocabularyPrompt {
	const snapshot = profilePins.snapshotFor(recording.id);
	if (snapshot) {
		const built = buildPrompt(snapshot, appIdOf(recording));
		return { prompt: built.prompt, keywords: built.sent };
	}
	return { prompt: settings.value['transcription.prompt'], keywords: [] };
}

/**
 * VoiceFlow 0.9.11: the prompt and its words, which also go as `keywords[]`
 * to models that take them (the words without their hints).
 */
export type VocabularyPrompt = { prompt: string; keywords: readonly string[] };

/** VoiceFlow: why a held recording was not sent. */
function heldRecordingErr() {
	return WhisperingErr({
		title: 'This dictation is held on this PC',
		description:
			"VoiceFlow couldn't confirm the app it was dictated into, so it was not sent. Use Send anyway on the recording to send it.",
	});
}

/**
 * VoiceFlow (speed): a recording together with the audio already in memory,
 * so a fresh dictation is not read back from disk before it is sent.
 */
export type RecordingWithAudio = { recording: Recording; audio: Blob };

function hasAudio(
	input: Recording | RecordingWithAudio,
): input is RecordingWithAudio {
	return 'audio' in input && input.audio instanceof Blob && 'recording' in input;
}

const transcriptionKeys = {
	isTranscribing: ['transcription', 'isTranscribing'] as const,
} as const;

export const transcription = {
	isCurrentlyTranscribing() {
		return (
			queryClient.isMutating({
				mutationKey: transcriptionKeys.isTranscribing,
			}) > 0
		);
	},
	transcribeRecording: defineMutation({
		mutationKey: transcriptionKeys.isTranscribing,
		mutationFn: async (
			input: Recording | RecordingWithAudio,
		): Promise<Result<string, WhisperingError>> => {
			const recording = hasAudio(input) ? input.recording : input;
			// VoiceFlow (spec §4.3): a held recording is never uploaded until
			// the user says Send anyway.
			if (recording.privacy === 'held') return heldRecordingErr();

			// Fetch audio blob by ID, unless the caller already holds it.
			const { data: audioBlob, error: getAudioBlobError } = hasAudio(input)
				? { data: input.audio, error: null }
				: await services.db.recordings.getAudioBlob(recording.id);

			if (getAudioBlobError) {
				return WhisperingErr({
					title: 'Failed to fetch audio',
					description: `Unable to load audio for recording: ${getAudioBlobError.message}`,
				});
			}

			// VoiceFlow: field by field on the stored dictation, so a delete or
			// result saved meanwhile is not overwritten by this older copy.
			const { error: setRecordingTranscribingError } = await patchRecording(
				recording.id,
				{ transcriptionStatus: 'TRANSCRIBING' },
			);
			if (setRecordingTranscribingError) {
				notify.warning.execute({
					title:
						'Unable to set recording transcription status to transcribing',
					description: 'Continuing with the transcription process...',
					action: {
						type: 'more-details',
						error: setRecordingTranscribingError,
					},
				});
			}
			const { data: transcribedText, error: transcribeError } =
				await transcribeBlob(audioBlob, promptFor(recording));
			dictationTiming.markRecording(recording.id, 'transcribed');
			if (transcribeError) {
				const { error: setRecordingTranscribingError } = await patchRecording(
					recording.id,
					{ transcriptionStatus: 'FAILED' },
				);
				if (setRecordingTranscribingError) {
					notify.warning.execute({
						title: "Couldn't save the text with the dictation",
						description:
							"Transcription failed but unable to update recording's transcription status in database",
						action: {
							type: 'more-details',
							error: setRecordingTranscribingError,
						},
					});
				}
				return Err(transcribeError);
			}

			const { error: setRecordingTranscribedTextError } = await patchRecording(
				recording.id,
				{ transcribedText, transcriptionStatus: 'DONE' },
			);
			if (setRecordingTranscribedTextError) {
				notify.warning.execute({
					title: "Couldn't save the text with the dictation",
					description:
						"Transcription completed but unable to update recording's transcribed text and status in database",
					action: {
						type: 'more-details',
						error: setRecordingTranscribedTextError,
					},
				});
			}
			return Ok(transcribedText);
		},
	}),

	transcribeRecordings: defineMutation({
		mutationKey: transcriptionKeys.isTranscribing,
		mutationFn: async (recordings: Recording[]) => {
			const results = await Promise.all(
				recordings.map(async (recording) => {
					if (recording.privacy === 'held') return heldRecordingErr();

					// Fetch audio blob by ID
					const { data: audioBlob, error: getAudioBlobError } =
						await services.db.recordings.getAudioBlob(recording.id);

					if (getAudioBlobError) {
						return WhisperingErr({
							title: 'Failed to fetch audio',
							description: `Unable to load audio for recording: ${getAudioBlobError.message}`,
						});
					}

					return await transcribeBlob(audioBlob, promptFor(recording));
				}),
			);
			const partitionedResults = partitionResults(results);
			return Ok(partitionedResults);
		},
	}),
};

/**
 * VoiceFlow A2: speech-to-text only, for a feedback recording: nothing is
 * saved to History, and no spellings, tidy-up or delivery follow.
 */
export function transcribeAudioOnly(blob: Blob, prompt: string) {
	return transcribeBlob(blob, { prompt, keywords: [] });
}

async function transcribeBlob(
	blob: Blob,
	prompt: VocabularyPrompt,
): Promise<Result<string, WhisperingError>> {
	// VoiceFlow: API keys load asynchronously from the OS credential store.
	await settings.apiKeysReady;

	const selectedService =
		settings.value['transcription.selectedTranscriptionService'];

	// Log transcription request
	const startTime = Date.now();
	rpc.analytics.logEvent.execute({
		type: 'transcription_requested',
		provider: selectedService,
	});

	// VoiceFlow (A0): upload 16 kHz mono FLAC, in parts when very long. Every
	// part goes to the same service with the same prompt and the text is
	// joined in order.
	const uploads = await prepareUploads(blob, selectedService);
	let transcriptionResult: Result<string, WhisperingError> | undefined;
	const [onlyUpload] = uploads;
	if (uploads.length === 1 && onlyUpload) {
		transcriptionResult = await transcribeWithService(
			onlyUpload,
			selectedService,
			prompt,
		);
	} else {
		const texts: string[] = [];
		for (const part of uploads) {
			const partResult = await transcribeWithService(
				part,
				selectedService,
				prompt,
			);
			if (partResult.error) {
				transcriptionResult = partResult;
				break;
			}
			texts.push(partResult.data.trim());
		}
		transcriptionResult ??= Ok(texts.filter(Boolean).join(' '));
	}

	// Log transcription result
	const duration = Date.now() - startTime;
	if (transcriptionResult.error) {
		rpc.analytics.logEvent.execute({
			type: 'transcription_failed',
			provider: selectedService,
			error_title: transcriptionResult.error.title,
			error_description: transcriptionResult.error.description,
		});
	} else {
		rpc.analytics.logEvent.execute({
			type: 'transcription_completed',
			provider: selectedService,
			duration,
		});
	}

	return transcriptionResult;
}

type TranscriptionServiceId =
	(typeof settings.value)['transcription.selectedTranscriptionService'];

/** VoiceFlow (A0): on-device engines read the recording's WAV themselves. */
const ON_DEVICE_SERVICES = new Set<string>([
	'whispercpp',
	'parakeet',
	'moonshine',
]);

/**
 * The file(s) to send for a recording. On desktop, a WAV going to a cloud
 * service becomes 16 kHz mono FLAC (split into ten-minute parts when one file
 * would pass the upload limit), with no FFmpeg needed. Anything else (an
 * uploaded MP3, the web build, a failure) takes the original path: FFmpeg
 * compression when it is turned on, otherwise the file as it is.
 */
async function prepareUploads(
	blob: Blob,
	selectedService: TranscriptionServiceId,
): Promise<Blob[]> {
	if (window.__TAURI_INTERNALS__ && !ON_DEVICE_SERVICES.has(selectedService)) {
		const { data: parts, error } =
			await desktopServices.speechAudio.prepareUpload(blob);
		if (!error) return parts;
		console.warn(error.message);
	}

	// Compress audio if enabled, else pass through original blob
	let audioToTranscribe = blob;
	if (settings.value['transcription.compressionEnabled']) {
		const { data: compressedBlob, error: compressionError } =
			await desktopServices.ffmpeg.compressAudioBlob(
				blob,
				settings.value['transcription.compressionOptions'],
			);

		if (compressionError) {
			// Notify user of compression failure but continue with original blob
			notify.warning.execute({
				title: 'Audio compression failed',
				description: `${compressionError.message}. Using original audio for transcription.`,
			});
			rpc.analytics.logEvent.execute({
				type: 'compression_failed',
				provider: selectedService,
				error_message: compressionError.message,
			});
		} else {
			// Use compressed blob and notify user of success
			audioToTranscribe = compressedBlob;
			const compressionRatio = Math.round(
				(1 - compressedBlob.size / blob.size) * 100,
			);
			notify.info.execute({
				title: 'Audio compressed',
				description: `Reduced file size by ${compressionRatio}%`,
			});
			rpc.analytics.logEvent.execute({
				type: 'compression_completed',
				provider: selectedService,
				original_size: blob.size,
				compressed_size: compressedBlob.size,
				compression_ratio: compressionRatio,
			});
		}
	}
	return [audioToTranscribe];
}

async function transcribeWithService(
	audioToTranscribe: Blob,
	selectedService: TranscriptionServiceId,
	vocabulary: VocabularyPrompt,
): Promise<Result<string, WhisperingError>> {
	const { prompt, keywords } = vocabulary;
	switch (selectedService) {
		case 'OpenAI':
			return await services.transcriptions.openai.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					prompt,
					keywords,
					temperature: settings.value['transcription.temperature'],
					apiKey: settings.value['apiKeys.openai'],
					modelName: settings.value['transcription.openai.model'],
					baseURL: settings.value['apiEndpoints.openai'] || undefined,
				},
			);
		case 'Groq':
			return await services.transcriptions.groq.transcribe(audioToTranscribe, {
				outputLanguage: settings.value['transcription.outputLanguage'],
				prompt,
				temperature: settings.value['transcription.temperature'],
				apiKey: settings.value['apiKeys.groq'],
				modelName: settings.value['transcription.groq.model'],
				baseURL: settings.value['apiEndpoints.groq'] || undefined,
			});
		case 'speaches':
			return await services.transcriptions.speaches.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					prompt,
					temperature: settings.value['transcription.temperature'],
					modelId: settings.value['transcription.speaches.modelId'],
					baseUrl: settings.value['transcription.speaches.baseUrl'],
				},
			);
		case 'ElevenLabs':
			return await services.transcriptions.elevenlabs.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					prompt,
					temperature: settings.value['transcription.temperature'],
					apiKey: settings.value['apiKeys.elevenlabs'],
					modelName: settings.value['transcription.elevenlabs.model'],
				},
			);
		case 'Deepgram':
			return await services.transcriptions.deepgram.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					prompt,
					temperature: settings.value['transcription.temperature'],
					apiKey: settings.value['apiKeys.deepgram'],
					modelName: settings.value['transcription.deepgram.model'],
				},
			);
		case 'Mistral':
			return await services.transcriptions.mistral.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					prompt,
					temperature: settings.value['transcription.temperature'],
					apiKey: settings.value['apiKeys.mistral'],
					modelName: settings.value['transcription.mistral.model'],
				},
			);
		case 'whispercpp': {
			// Pure Rust audio conversion now handles most formats without FFmpeg
			// Only compressed formats (MP3, M4A) require FFmpeg, which will be
			// handled automatically as a fallback in the Rust conversion pipeline
			return await services.transcriptions.whispercpp.transcribe(
				audioToTranscribe,
				{
					outputLanguage: settings.value['transcription.outputLanguage'],
					modelPath: settings.value['transcription.whispercpp.modelPath'],
					prompt,
				},
			);
		}
		case 'parakeet': {
			// Pure Rust audio conversion now handles most formats without FFmpeg
			// Only compressed formats (MP3, M4A) require FFmpeg, which will be
			// handled automatically as a fallback in the Rust conversion pipeline
			return await services.transcriptions.parakeet.transcribe(
				audioToTranscribe,
				{ modelPath: settings.value['transcription.parakeet.modelPath'] },
			);
		}
		case 'moonshine': {
			// Moonshine uses ONNX Runtime with encoder-decoder architecture
			// Variant is extracted from modelPath (e.g., "moonshine-tiny-en" → "tiny")
			return await services.transcriptions.moonshine.transcribe(
				audioToTranscribe,
				{
					modelPath: settings.value['transcription.moonshine.modelPath'],
				},
			);
		}
		default:
			return WhisperingErr({
				title: 'No transcription service selected',
				description: 'Please select a transcription service in settings.',
			});
	}
}
