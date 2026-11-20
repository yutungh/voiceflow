<script lang="ts">
	import * as Kbd from '@epicenter/ui/kbd';
	import { cn } from '@epicenter/ui/utils';
	import { getShortcutDisplayLabel } from '$lib/constants/keyboard';

	/** A shortcut as stored in settings ("Control+Shift+;"), shown as keycaps. */
	let { shortcut, class: className }: { shortcut: string | null; class?: string } =
		$props();

	const keys = $derived(
		getShortcutDisplayLabel(shortcut)
			.split(' + ')
			.filter((k) => k.length > 0),
	);
</script>

{#if keys.length > 0}
	<span class={cn('inline-flex flex-none items-center gap-[3px] align-[1px]', className)}>
		{#each keys as key}
			<Kbd.Root>{key}</Kbd.Root>
		{/each}
	</span>
{/if}
