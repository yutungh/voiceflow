import { invoke } from '@tauri-apps/api/core';
import { exists, stat } from '@tauri-apps/plugin-fs';
import { type } from 'arktype';
import { extractErrorMessage } from 'wellcrafted/error';
import { Ok, type Result, tryAsync } from 'wellcrafted/result';
import { WhisperingErr, type WhisperingError } from '$lib/result';
import type { ParakeetModelConfig } from './types';

/**
 * VoiceFlow: Parakeet files come from the ONNX export's own Hugging Face
 * repository (istupakov/parakeet-tdt-0.6b-v3-onnx, CC-BY-4.0, of NVIDIA's
 * nvidia/parakeet-tdt-0.6b-v3), pinned to one revision, instead of
 * Whispering's GitHub re-upload. Same five files, byte sizes identical.
 */
const PARAKEET_V3_BASE =
	'https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce';

/**
 * Pre-built Parakeet models available for download.
 * These are NVIDIA NeMo models consisting of multiple ONNX files.
 */
export const PARAKEET_MODELS = [
	{
		id: 'parakeet-tdt-0.6b-v3-int8',
		name: 'Parakeet TDT 0.6B v3 (INT8)',
		description: 'Fast and accurate NVIDIA NeMo model',
		size: '~670 MB',
		sizeBytes: 670_619_803, // Total size of all individual files
		engine: 'parakeet',
		directoryName: 'parakeet-tdt-0.6b-v3-int8',
		files: [
			{
				url: `${PARAKEET_V3_BASE}/config.json`,
				filename: 'config.json',
				sizeBytes: 97,
			},
			{
				url: `${PARAKEET_V3_BASE}/decoder_joint-model.int8.onnx`,
				filename: 'decoder_joint-model.int8.onnx',
				sizeBytes: 18_202_004,
			},
			{
				url: `${PARAKEET_V3_BASE}/encoder-model.int8.onnx`,
				filename: 'encoder-model.int8.onnx',
				sizeBytes: 652_183_999,
			},
			{
				url: `${PARAKEET_V3_BASE}/nemo128.onnx`,
				filename: 'nemo128.onnx',
				sizeBytes: 139_764,
			},
			{
				url: `${PARAKEET_V3_BASE}/vocab.txt`,
				filename: 'vocab.txt',
				sizeBytes: 93_939,
			},
		],
	},
] as const satisfies readonly ParakeetModelConfig[];

const ParakeetErrorType = type({
	name: "'AudioReadError' | 'FfmpegNotFoundError' | 'ModelLoadError' | 'TranscriptionError'",
	message: 'string',
});

export function createParakeetTranscriptionService() {
	return {
		async transcribe(
			audioBlob: Blob,
			options: { modelPath: string },
		): Promise<Result<string, WhisperingError>> {
			// Pre-validation
			if (!options.modelPath) {
				return WhisperingErr({
					title: 'Model Directory Required',
					description: 'Please select a Parakeet model directory in settings.',
					action: {
						type: 'link',
						label: 'Configure model',
						href: '/settings#transcription',
					},
				});
			}

			// Check if model directory exists
			const { data: isExists } = await tryAsync({
				try: () => exists(options.modelPath),
				catch: () => Ok(false),
			});

			if (!isExists) {
				return WhisperingErr({
					title: 'Model Directory Not Found',
					description: `The model directory "${options.modelPath}" does not exist.`,
					action: {
						type: 'link',
						label: 'Select model',
						href: '/settings#transcription',
					},
				});
			}

			// Check if it's actually a directory
			const { data: stats } = await tryAsync({
				try: () => stat(options.modelPath),
				catch: () => Ok(null),
			});

			if (!stats || !stats.isDirectory) {
				return WhisperingErr({
					title: 'Invalid Model Path',
					description:
						'Parakeet models must be directories containing model files.',
					action: {
						type: 'link',
						label: 'Select model directory',
						href: '/settings#transcription',
					},
				});
			}

			// Convert audio blob to byte array
			const arrayBuffer = await audioBlob.arrayBuffer();
			const audioData = Array.from(new Uint8Array(arrayBuffer));

			// Call Tauri command to transcribe with Parakeet
			// Note: Parakeet doesn't support language selection, temperature, or prompt
			const result = await tryAsync({
				try: () =>
					invoke<string>('transcribe_audio_parakeet', {
						audioData: audioData,
						modelPath: options.modelPath,
					}),
				catch: (unknownError) => {
					const result = ParakeetErrorType(unknownError);
					if (result instanceof type.errors) {
						return WhisperingErr({
							title: 'Unexpected Parakeet Error',
							description: extractErrorMessage(unknownError),
							action: { type: 'more-details', error: unknownError },
						});
					}
					const error = result;

					switch (error.name) {
						case 'ModelLoadError':
							return WhisperingErr({
								title: 'Model Loading Error',
								description: error.message,
								action: {
									type: 'more-details',
									error: new Error(error.message),
								},
							});

						case 'FfmpegNotFoundError':
							return WhisperingErr({
								title: 'FFmpeg Not Installed',
								description:
									'Parakeet requires FFmpeg to convert audio formats. Please install FFmpeg or switch to CPAL recording at 16kHz.',
								action: {
									type: 'link',
									label: 'Install FFmpeg',
									href: '/settings#microphone',
								},
							});

						case 'AudioReadError':
							return WhisperingErr({
								title: 'Audio Read Error',
								description: error.message,
								action: {
									type: 'more-details',
									error: new Error(error.message),
								},
							});

						case 'TranscriptionError':
							return WhisperingErr({
								title: 'Transcription Error',
								description: error.message,
								action: {
									type: 'more-details',
									error: new Error(error.message),
								},
							});

						default:
							return WhisperingErr({
								title: 'Parakeet Error',
								description: 'An unexpected error occurred.',
								action: {
									type: 'more-details',
									error: new Error(String(error)),
								},
							});
					}
				},
			});

			return result;
		},
	};
}

export type ParakeetTranscriptionService = ReturnType<
	typeof createParakeetTranscriptionService
>;

export const ParakeetTranscriptionServiceLive =
	createParakeetTranscriptionService();
