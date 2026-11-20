<script lang="ts">
	import { getShortcutDisplayLabel } from '$lib/constants/keyboard';

	/**
	 * Keycaps. Pass `keys` (["Ctrl", "Space"]) or a stored `shortcut`
	 * ("Control+Space"), which is shown the way the OS names the keys.
	 */
	let {
		keys,
		shortcut,
	}: { keys?: string[]; shortcut?: string | null } = $props();

	const list = $derived(
		keys ??
			getShortcutDisplayLabel(shortcut ?? null)
				.split(' + ')
				.filter((k) => k.length > 0),
	);
</script>

{#if list.length > 0}
	<span class="keys" aria-label={list.join(' plus ')}
		>{#each list as key}<kbd>{key}</kbd>{/each}</span
	>
{/if}
