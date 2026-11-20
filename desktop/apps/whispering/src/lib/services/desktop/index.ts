import { AutostartServiceLive } from './autostart';
import { CommandServiceLive } from './command';
import { FfmpegServiceLive } from './ffmpeg';
import { FsServiceLive } from './fs';
import { GlobalShortcutManagerLive } from './global-shortcut-manager';
import { PermissionsServiceLive } from './permissions';
import { CpalRecorderServiceLive } from './recorder/cpal';
import { FfmpegRecorderServiceLive } from './recorder/ffmpeg';
import { SpeechAudioServiceLive } from './speech-audio';
import { TrayIconServiceLive } from './tray';

/**
 * Desktop-only services.
 * These services are only available in the Tauri desktop app.
 */
export const desktopServices = {
	autostart: AutostartServiceLive,
	command: CommandServiceLive,
	ffmpeg: FfmpegServiceLive,
	fs: FsServiceLive,
	tray: TrayIconServiceLive,
	globalShortcutManager: GlobalShortcutManagerLive,
	permissions: PermissionsServiceLive,
	cpalRecorder: CpalRecorderServiceLive,
	ffmpegRecorder: FfmpegRecorderServiceLive,
	// VoiceFlow (A0): 16 kHz mono FLAC uploads, split when very long
	speechAudio: SpeechAudioServiceLive,
} as const;
