/**
 * VoiceFlow A2: one recovery action per dictation at a time. The bar, the
 * main window's notices, Home and History all offer Retry, Paste raw text and
 * Send anyway for the same dictation; a second click (on any of them) while
 * the first is still running is ignored, so the words are never delivered
 * twice. Pure, so it is unit tested.
 */
export function createExclusive() {
	const running = new Set<string>();
	return {
		/** Runs `task` unless one is already running for `key`; then returns `undefined`. */
		async run<T>(key: string, task: () => Promise<T>): Promise<T | undefined> {
			if (running.has(key)) {
				console.info(`[recovery] ${key} is already being handled; ignored`);
				return undefined;
			}
			running.add(key);
			try {
				return await task();
			} finally {
				running.delete(key);
			}
		},
		busy(key: string): boolean {
			return running.has(key);
		},
	};
}
