<script lang="ts">
	import { waveHeights } from './wave';

	/**
	 * The still waveform, B3's signature. Decorative: the bar heights come
	 * from a seed (a dictation's id), not from the audio.
	 */
	let {
		count = 24,
		seed = 1,
		height = 16,
		class: className = 'wv',
	}: { count?: number; seed?: number | string; height?: number; class?: string } =
		$props();

	const bars = $derived(waveHeights(count, seed, height));
</script>

<span class={className} aria-hidden="true"
	>{#each bars as h}<i style="height:{h}px"></i>{/each}</span
>
