import { rpc } from '$lib/query';

/**
 * VoiceFlow C1: a Copy button's state. After a copy the button shows
 * "Copied" with a check for two seconds (B3.1 feedback).
 */
export function createCopyState() {
	let copiedAt = $state(0);
	let timer: ReturnType<typeof setTimeout> | undefined;
	return {
		get copied() {
			return copiedAt > 0;
		},
		async copy(text: string): Promise<boolean> {
			const { error } = await rpc.text.copyToClipboard.execute({ text });
			if (error) return false;
			copiedAt = Date.now();
			clearTimeout(timer);
			timer = setTimeout(() => (copiedAt = 0), 2000);
			return true;
		},
	};
}

/** Words that explain themselves on hover (B3.1 "term"). */
export const TIDY_UP_TIP =
	'Tidy-up fixes punctuation and filler words in the style you chose.';
