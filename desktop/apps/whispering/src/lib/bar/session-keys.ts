/**
 * VoiceFlow A2: the recording bar's expression keys are global shortcuts
 * only while a dictation is being recorded, so the rest of the time those
 * keys still work in every other app. `createKeySync` serializes the
 * changes, so a quick Stop never leaves a key registered.
 */
import { commands } from '$lib/commands';
import { desktopRpc } from '$lib/query';
import type { Accelerator } from '$lib/services/desktop/global-shortcut-manager';
import { settings } from '$lib/stores/settings.svelte';
import { createKeySync } from './key-sync';

/** The commands registered only while dictating. */
export const dictationCommands = commands.filter(
	(command) => 'whileDictating' in command && command.whileDictating,
);

type Entry = { command: (typeof dictationCommands)[number]; accelerator: Accelerator };

const keys = createKeySync<Entry>({
	wanted: () =>
		dictationCommands.flatMap((command) => {
			const accelerator = settings.value[`shortcuts.global.${command.id}`] as Accelerator | null;
			return accelerator ? [{ command, accelerator }] : [];
		}),
	async register({ command, accelerator }) {
		const { error } = await desktopRpc.globalShortcuts.registerCommand.execute({
			command,
			accelerator,
		});
		if (error) console.warn(`[bar] ${accelerator} is not available`, error);
		return !error;
	},
	async unregister({ accelerator }) {
		await desktopRpc.globalShortcuts.unregisterCommand.execute({ accelerator });
	},
});

export function syncDictationKeys(active: boolean): Promise<void> {
	return keys.sync(active);
}
