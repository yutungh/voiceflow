import { createTaggedError } from 'wellcrafted/error';
import type { Result } from 'wellcrafted/result';
import type { SoundToPlay } from '$lib/constants/sounds';

export const { PlaySoundServiceError, PlaySoundServiceErr } = createTaggedError(
	'PlaySoundServiceError',
);
export type PlaySoundServiceError = ReturnType<typeof PlaySoundServiceError>;

export type PlaySoundService = {
	playSound: (
		sound: SoundToPlay,
	) => Promise<Result<void, PlaySoundServiceError>>;
};
