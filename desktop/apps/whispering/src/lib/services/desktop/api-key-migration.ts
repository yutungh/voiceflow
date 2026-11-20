/**
 * VoiceFlow: moving API keys from localStorage (an older install) into the
 * OS credential store (spec §2.3). Pure, so the rules are unit-tested
 * (`api-key-migration.test.ts`); the settings store supplies the reads and
 * writes.
 *
 * A key is cleared from localStorage only after its own write to the store
 * succeeded. A key whose write failed stays in localStorage, so it is still
 * there on the next launch and the move is tried again.
 */

type StoreResult<T> = { data: T; error: null } | { data: null; error: unknown };

export async function migrateApiKeys<K extends string>({
	keys,
	stored,
	write,
	read,
	onWriteError,
	onReadError,
}: {
	keys: readonly K[];
	/** The settings as loaded from localStorage. */
	stored: Readonly<Record<K, string>>;
	write: (key: K, value: string) => Promise<StoreResult<unknown>>;
	read: (key: K) => Promise<StoreResult<string | null>>;
	onWriteError?: (error: unknown) => void;
	onReadError?: (error: unknown) => void;
}): Promise<{
	/** Every key's value for this session. */
	secrets: Record<K, string>;
	/** Keys now in the store, with the localStorage value that was moved. */
	moved: Map<K, string>;
}> {
	const secrets = Object.fromEntries(keys.map((k) => [k, ''])) as Record<
		K,
		string
	>;
	const moved = new Map<K, string>();
	for (const key of keys) {
		const legacy = stored[key];
		if (legacy) {
			// The key works this session either way.
			secrets[key] = legacy;
			const { error } = await write(key, legacy);
			if (error) {
				onWriteError?.(error);
				continue;
			}
			moved.set(key, legacy);
			continue;
		}
		const { data, error } = await read(key);
		if (error) {
			onReadError?.(error);
			continue;
		}
		secrets[key] = data ?? '';
	}
	return { secrets, moved };
}

/**
 * The settings with the moved keys blanked. A key is left alone if it
 * changed in localStorage since it was moved.
 */
export function clearMovedKeys<S extends Record<string, unknown>, K extends keyof S & string>(
	settings: S,
	moved: ReadonlyMap<K, string>,
): S {
	const cleared = { ...settings };
	for (const [key, value] of moved) {
		if (cleared[key] === value) (cleared as Record<string, unknown>)[key] = '';
	}
	return cleared;
}
