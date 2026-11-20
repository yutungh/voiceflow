<script lang="ts">
	import { appIconUrl } from './app-icons.svelte';

	/**
	 * The app a dictation went to: its real icon when VoiceFlow could read
	 * it, else a neutral tile with the app's first letter ("?" if unknown).
	 */
	let {
		name,
		exe,
		exePath,
		bundleId,
		size = 20,
		label = false,
	}: {
		name?: string | null;
		exe?: string | null;
		exePath?: string | null;
		/** macOS: the app's bundle id, for its icon. */
		bundleId?: string | null;
		size?: number;
		/** Show the name next to the icon. */
		label?: boolean;
	} = $props();

	const url = $derived(appIconUrl(exePath, exe, bundleId));
	const display = $derived(name?.trim() || 'Unknown app');
	const letter = $derived((name?.trim()[0] ?? '?').toUpperCase());
</script>

{#snippet icon()}
	{#if url}
		<img
			class="appi"
			src={url}
			width={size}
			height={size}
			alt={label ? '' : display}
		/>
	{:else}
		<span
			class="appi letter"
			style="width:{size}px;height:{size}px;font-size:{Math.round(size * 0.55)}px"
			role={label ? undefined : 'img'}
			aria-label={label ? undefined : display}
			aria-hidden={label ? 'true' : undefined}>{letter}</span
		>
	{/if}
{/snippet}

{#if label}
	<span class="appl">{@render icon()}<span>{display}</span></span>
{:else}
	{@render icon()}
{/if}
