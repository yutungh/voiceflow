import { type } from 'arktype';
import {
	DEFAULT_SOUND_SET,
	DEFAULT_SOUND_VOLUME,
	SOUND_SET_CHOICES,
} from '../constants/sounds/sound-sets';

/**
 * The sound part of the settings schema (spread into `Settings`). Kept on its
 * own, with relative imports only, so its tests run under `bun test`.
 *
 * Migration: the settings are migration-free (see settings.ts). The keys new
 * in VoiceFlow 0.9.6 (`sound.set`, `sound.volume`, `sound.playOn.error`) take
 * their defaults the first time older settings are read, and are saved from
 * then on, so the default set is applied once; the per-event switches an
 * existing user had are kept as they were.
 */
export const SOUND_SETTINGS = {
	'sound.playOn.manual-start': 'boolean = true',
	'sound.playOn.manual-stop': 'boolean = true',
	'sound.playOn.manual-cancel': 'boolean = true',
	'sound.playOn.vad-start': 'boolean = true',
	'sound.playOn.vad-capture': 'boolean = true',
	'sound.playOn.vad-stop': 'boolean = true',
	'sound.playOn.transcriptionComplete': 'boolean = true',
	'sound.playOn.transformationComplete': 'boolean = true',
	// VoiceFlow: a dictation failed or was refused.
	'sound.playOn.error': 'boolean = true',
	/** VoiceFlow: which of the app's own sound sets plays, or none. */
	'sound.set': type.enumerated(...SOUND_SET_CHOICES).default(DEFAULT_SOUND_SET),
	/** VoiceFlow: master volume for the app's sounds, 0 to 1. */
	'sound.volume': type('0 <= number <= 1').default(DEFAULT_SOUND_VOLUME),
} as const;

/**
 * Settings saved before 0.9.6 with every sound switched off stay silent: the
 * new error switch would otherwise default to on. Runs on the stored object
 * before it is validated; anything else passes through unchanged.
 */
export function migrateSoundSettings(stored: Record<string, unknown>): Record<string, unknown> {
	if ('sound.set' in stored || 'sound.playOn.error' in stored) return stored;
	const switches = Object.keys(SOUND_SETTINGS).filter(
		(k) => k.startsWith('sound.playOn.') && k !== 'sound.playOn.error',
	);
	const saved = switches.filter((k) => k in stored);
	if (saved.length === 0 || saved.some((k) => stored[k] !== false)) return stored;
	return { ...stored, 'sound.playOn.error': false };
}

/**
 * Runs `migrateSoundSettings` on the saved settings before the settings store
 * reads them. Settings saved before 0.9.6 still pass the schema (the new keys
 * have defaults), so the store's parse-error path, which also migrates, never
 * runs for them. Writes only when something changed; anything unreadable is
 * left to the store's own parsing.
 */
export function migrateStoredSoundSettings(
	storage: { getItem(key: string): string | null; setItem(key: string, value: string): void },
	key: string,
): boolean {
	try {
		const raw = storage.getItem(key);
		if (!raw) return false;
		const stored: unknown = JSON.parse(raw);
		if (typeof stored !== 'object' || stored === null || Array.isArray(stored)) return false;
		const migrated = migrateSoundSettings(stored as Record<string, unknown>);
		if (migrated === stored) return false;
		storage.setItem(key, JSON.stringify(migrated));
		return true;
	} catch {
		return false;
	}
}
