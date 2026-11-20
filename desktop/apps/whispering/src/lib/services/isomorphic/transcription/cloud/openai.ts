import OpenAI from 'openai';
import { Err, Ok, type Result, tryAsync, trySync } from 'wellcrafted/result';
import { WhisperingErr, type WhisperingError } from '$lib/result';
import { getAudioExtension } from '$lib/services/isomorphic/transcription/utils';
import type { Settings } from '$lib/settings';
import { dictationTiming } from '$lib/timing';
// VoiceFlow (speed): the pooled, pre-warmed connection for api.openai.com.
import { openAIFetchFor } from '../../openai-pool';
// VoiceFlow 0.9.11: the vocabulary words, also sent as keywords.
// VoiceFlow 0.9.15: the language as gpt-transcribe documents it (languages[]).
import { keywordsParam, languageParam } from './keywords';

/**
 * OpenAI transcription models offered in the picker, newest first.
 *
 * VoiceFlow: `gpt-transcribe` is the default for new installs (spec §2.1).
 * The setting also accepts any other model id typed by hand, so a model
 * OpenAI adds later needs no code change. `name` is a plain string for that
 * reason (the SDK's AudioModel union lags behind the API).
 */
export const OPENAI_TRANSCRIPTION_MODELS = [
	{
		name: 'gpt-transcribe',
		description:
			"OpenAI's current transcription model. Best with a short vocabulary prompt for names and terms.",
		cost: 'See OpenAI pricing',
	},
	{
		name: 'gpt-4o-transcribe',
		description:
			'GPT-4o powered transcription with enhanced understanding and context.',
		cost: '$0.36/hour',
	},
	{
		name: 'gpt-4o-mini-transcribe',
		description:
			'Cost-effective GPT-4o mini transcription. Good balance of quality and cost.',
		cost: '$0.18/hour',
	},
	{
		name: 'whisper-1',
		description:
			'The original Whisper API model. Retires Feb 26, 2027: choose another model before then.',
		cost: 'Retires Feb 26, 2027',
	},
] as const satisfies {
	name: string;
	description: string;
	cost: string;
}[];

/** The default OpenAI transcription model for new installs. */
export const DEFAULT_OPENAI_TRANSCRIPTION_MODEL = 'gpt-transcribe' as const;

export type OpenAIModel = (typeof OPENAI_TRANSCRIPTION_MODELS)[number];

const MAX_FILE_SIZE_MB = 25 as const;


export function createOpenaiTranscriptionService() {
	return {
		async transcribe(
			audioBlob: Blob,
			options: {
				prompt: string;
				/** VoiceFlow 0.9.11: the vocabulary words, sent as keywords[]. */
				keywords?: readonly string[];
				temperature: string;
				outputLanguage: Settings['transcription.outputLanguage'];
				apiKey: string;
				modelName: (string & {}) | OpenAIModel['name'];
				baseURL?: string;
			},
		): Promise<Result<string, WhisperingError>> {
			const isUsingCustomEndpoint = Boolean(options.baseURL);

			// When no custom baseURL is provided, we're using the official OpenAI API.
			// The official API has strict requirements:
			// 1. An API key is always required
			// 2. The key must follow OpenAI's format (starts with "sk-")
			//
			// Custom endpoints (reverse proxies, OpenAI-compatible servers, etc.) may have
			// different authentication schemes or no auth at all, so we skip these checks.
			if (!isUsingCustomEndpoint) {
				// Check 1: Official OpenAI API requires an API key
				if (!options.apiKey) {
					return WhisperingErr({
						title: 'API Key Required',
						description:
							'Please enter your OpenAI API key in settings to use Whisper transcription.',
						action: {
							type: 'link',
							label: 'Add API key',
							href: '/settings#transcription',
						},
					});
				}

				// Check 2: Official OpenAI API keys always start with "sk-"
				if (!options.apiKey.startsWith('sk-')) {
					return WhisperingErr({
						title: 'Invalid API Key Format',
						description:
							'Your OpenAI API key should start with "sk-". Please check and update your API key.',
						action: {
							type: 'link',
							label: 'Update API key',
							href: '/settings#transcription',
						},
					});
				}
			}

			// Validate file size
			const blobSizeInMb = audioBlob.size / (1024 * 1024);
			if (blobSizeInMb > MAX_FILE_SIZE_MB) {
				return WhisperingErr({
					title: `The file size (${blobSizeInMb}MB) is too large`,
					description: `Please upload a file smaller than ${MAX_FILE_SIZE_MB}MB.`,
				});
			}

			// Create File object from blob
			const { data: file, error: fileError } = trySync({
				try: () =>
					new File(
						[audioBlob],
						`recording.${getAudioExtension(audioBlob.type)}`,
						{ type: audioBlob.type },
					),
				catch: (_error) =>
					WhisperingErr({
						title: 'File Creation Failed',
						description:
							'Failed to create audio file for transcription. Please try again.',
					}),
			});

			if (fileError) return Err(fileError);

			// VoiceFlow: speed timings (audio prep ends here).
			dictationTiming.uploadStarting({
				bytes: audioBlob.size,
				model: options.modelName,
			});

			// Call OpenAI API
			const { data: transcription, error: openaiApiError } = await tryAsync({
				try: () =>
					new OpenAI({
						apiKey: options.apiKey,
						dangerouslyAllowBrowser: true,
						fetch: openAIFetchFor(options.baseURL),
						...(options.baseURL && { baseURL: options.baseURL }),
					}).audio.transcriptions.create({
						file,
						model: options.modelName,
						prompt: options.prompt || undefined,
						temperature: options.temperature
							? Number.parseFloat(options.temperature)
							: undefined,
						// VoiceFlow 0.9.11: one keywords[] per vocabulary word (the
						// SDK sends an array as `keywords[]`). Not in its types yet.
						...keywordsParam(options.modelName, options.keywords ?? []),
						// VoiceFlow 0.9.15: `languages[]` for gpt-transcribe, the
						// singular `language` for other models, never both (spec §3.4).
						// Nothing for Auto.
						...languageParam(options.modelName, options.outputLanguage),
					}),
				catch: (error) => {
					// Check if it's NOT an OpenAI API error
					if (!(error instanceof OpenAI.APIError)) {
						// This is an unexpected error type
						throw error;
					}
					// Return the error directly
					return Err(error);
				},
			});

			if (openaiApiError) {
				// Error handling follows https://www.npmjs.com/package/openai#error-handling
				const { status, name, message, error } = openaiApiError;

				// 400 - BadRequestError
				if (status === 400) {
					return WhisperingErr({
						title: 'Bad Request',
						description:
							message ??
							`Invalid request to OpenAI API. ${error?.message ?? ''}`.trim(),
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 401 - AuthenticationError
				if (status === 401) {
					return WhisperingErr({
						title: 'Authentication Required',
						description:
							message ??
							'Your API key appears to be invalid or expired. Please update your API key in settings to continue transcribing.',
						action: {
							type: 'link',
							label: 'Update API key',
							href: '/settings#transcription',
						},
					});
				}

				// 403 - PermissionDeniedError
				if (status === 403) {
					return WhisperingErr({
						title: 'Permission Denied',
						description:
							message ??
							"Your account doesn't have access to this feature. This may be due to plan limitations or account restrictions.",
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 404 - NotFoundError
				if (status === 404) {
					return WhisperingErr({
						title: 'Not Found',
						description:
							message ??
							'The requested resource was not found. This might indicate an issue with the model or API endpoint.',
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 413 - Request Entity Too Large
				if (status === 413) {
					return WhisperingErr({
						title: 'Audio File Too Large',
						description:
							message ??
							'Your audio file exceeds the maximum size limit (25MB). Try splitting it into smaller segments or reducing the audio quality.',
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 415 - Unsupported Media Type
				if (status === 415) {
					return WhisperingErr({
						title: 'Unsupported Format',
						description:
							message ??
							"This audio format isn't supported. Please convert your file to MP3, WAV, M4A, or another common audio format.",
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 422 - UnprocessableEntityError
				if (status === 422) {
					return WhisperingErr({
						title: 'Invalid Input',
						description:
							message ??
							'The request was valid but the server cannot process it. Please check your audio file and parameters.',
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// 429 - RateLimitError
				if (status === 429) {
					return WhisperingErr({
						title: 'Rate Limit Reached',
						description:
							message ?? 'Too many requests. Please try again later.',
						action: {
							type: 'link',
							label: 'Update API key',
							href: '/settings#transcription',
						},
					});
				}

				// >=500 - InternalServerError
				if (status && status >= 500) {
					return WhisperingErr({
						title: 'Service Unavailable',
						description:
							message ??
							`The transcription service is temporarily unavailable (Error ${status}). Please try again in a few minutes.`,
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// Handle APIConnectionError (no status code)
				if (!status && name === 'APIConnectionError') {
					return WhisperingErr({
						title: 'Connection Issue',
						description:
							message ??
							'Unable to connect to the OpenAI service. This could be a network issue or temporary service interruption.',
						action: { type: 'more-details', error: openaiApiError },
					});
				}

				// Return the error directly for other API errors
				return WhisperingErr({
					title: 'Unexpected Error',
					description:
						message ?? 'An unexpected error occurred. Please try again.',
					action: { type: 'more-details', error: openaiApiError },
				});
			}

			// Success - return the transcription text
			return Ok(transcription.text.trim());
		},
	};
}

export type OpenaiTranscriptionService = ReturnType<
	typeof createOpenaiTranscriptionService
>;

export const OpenaiTranscriptionServiceLive =
	createOpenaiTranscriptionService();
