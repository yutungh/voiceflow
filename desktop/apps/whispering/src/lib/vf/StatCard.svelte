<script lang="ts">
	import type { Snippet } from 'svelte';
	import Tile from './Tile.svelte';
	import type { IconName } from './phosphor';

	/**
	 * One Home stat: a label, a big number, a line under it, and labelled
	 * bars for the last 7 days (day letters under the bars, the value on
	 * hover or focus).
	 */
	let {
		icon,
		label,
		value,
		delta,
		deltaTone = 'flat',
		bars,
		empty = false,
	}: {
		icon: IconName;
		label: string;
		value: string;
		delta: Snippet;
		deltaTone?: 'up' | 'flat';
		/** Oldest first; the last is today. */
		bars: { letter: string; name: string; value: number; text: string }[];
		empty?: boolean;
	} = $props();

	const max = $derived(Math.max(1, ...bars.map((b) => b.value)));
	let tipAt = $state(-1);
</script>

<section class="card stat" aria-label={label}>
	<div class="st-h"><Tile {icon} /><span>{label}</span></div>
	<div class="st-b">
		<div>
			<b class="num" class:dim={empty}>{value}</b>
			<span class="delta {deltaTone}">{@render delta()}</span>
		</div>
		<span class="sparkw">
			<span class="spl">Last 7 days</span>
			<span
				class="spark {empty ? 'empty' : ''}"
				role="img"
				aria-label="Last 7 days: {bars.map((b) => `${b.name} ${b.text}`).join(', ')}"
			>
				{#each bars as bar, i}
					<span
						class="spb"
						role="presentation"
						onmouseenter={() => (tipAt = i)}
						onmouseleave={() => (tipAt = -1)}
					>
						<i
							class={i === bars.length - 1 ? 'today' : ''}
							style="height:{empty ? 3 : Math.max(3, Math.round((26 * bar.value) / max))}px"
						></i>
						<small>{bar.letter}</small>
						{#if tipAt === i && !empty}
							<span class="sp-tip">{bar.name}: {bar.text}</span>
						{/if}
					</span>
				{/each}
			</span>
		</span>
	</div>
</section>
