<script lang="ts">
	import type { Snippet } from 'svelte';

	/** A word with a plain explanation on hover or focus (B3.1 "term"). */
	let { tip, children }: { tip: string; children: Snippet } = $props();
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<span class="term" tabindex="0"
	>{@render children()}<span class="tipb" role="tooltip">{tip}</span></span
>
