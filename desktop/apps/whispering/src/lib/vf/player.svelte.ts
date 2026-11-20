import { services } from '$lib/services';

/**
 * VoiceFlow C1: plays a dictation's audio from Home or History. One
 * dictation plays at a time.
 */
let playingId = $state<string | null>(null);
let audio: HTMLAudioElement | null = null;

export const player = {
	get playingId() {
		return playingId;
	},
	async toggle(recordingId: string): Promise<string | null> {
		if (playingId === recordingId) {
			audio?.pause();
			playingId = null;
			return null;
		}
		audio?.pause();
		const { data: url, error } =
			await services.db.recordings.ensureAudioPlaybackUrl(recordingId);
		if (error || !url) return "This dictation's audio isn't on this PC.";
		audio = new Audio(url);
		audio.onended = () => {
			if (playingId === recordingId) playingId = null;
		};
		playingId = recordingId;
		try {
			await audio.play();
		} catch {
			playingId = null;
			return "The audio couldn't be played.";
		}
		return null;
	},
	stop() {
		audio?.pause();
		playingId = null;
	},
};
