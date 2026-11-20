/**
 * VoiceFlow A2: keeps the dictation-only global keys in step with whether a
 * dictation is being recorded. Calls are serialized and only the latest
 * wish counts, so a Stop that comes while the keys are still being
 * registered always ends with none registered (they never linger and take
 * the keys from other apps). Pure, so it is unit tested.
 */
export function createKeySync<K>(deps: {
	/** The keys to register while dictating (read at the time). */
	wanted: () => K[];
	/** Registers one key; false when it isn't available. */
	register: (key: K) => Promise<boolean>;
	unregister: (key: K) => Promise<void>;
}) {
	let registered: K[] = [];
	let desired = false;
	let chain: Promise<void> = Promise.resolve();

	async function apply() {
		const want = desired;
		for (const key of registered) await deps.unregister(key);
		registered = [];
		if (!want) return;
		for (const key of deps.wanted()) {
			// A Stop arrived meanwhile: the next pass removes what is here.
			if (!desired) break;
			if (await deps.register(key)) registered.push(key);
		}
	}

	return {
		/** True while dictating. Resolves once the keys match the latest wish. */
		sync(active: boolean): Promise<void> {
			desired = active;
			// Each wish queues its own pass after the ones before it, so the
			// last pass always runs with the latest wish.
			chain = chain
				.then(apply)
				.catch((error) => console.warn('[bar] dictation keys', error));
			return chain;
		},
		get registered(): readonly K[] {
			return registered;
		},
	};
}
