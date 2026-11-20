<script lang="ts">
	import Icon from './Icon.svelte';
	import type { IconName } from './phosphor';

	/** The small tinted square that carries a section's icon (B3.1 "tile"). */
	let {
		icon,
		tone = 'acc',
		size = '',
	}: {
		icon: IconName;
		tone?: 'acc' | 'bad' | 'warn' | 'ind' | 'ok';
		size?: '' | 'sm' | 'lg';
	} = $props();
</script>

<span class="tile {tone} {size}" aria-hidden="true"
	><Icon name={icon} weight="duotone" /></span
>
