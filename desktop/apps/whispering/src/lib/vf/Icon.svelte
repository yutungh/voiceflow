<script lang="ts">
	import { PHOSPHOR, type IconName, type IconWeight } from './phosphor';

	/** A Phosphor icon (MIT). Decorative unless `label` is given. */
	let {
		name,
		weight = 'regular',
		class: className = 'i',
		label,
	}: {
		name: IconName;
		weight?: IconWeight;
		class?: string;
		label?: string;
	} = $props();
</script>

<svg
	class={className}
	viewBox="0 0 256 256"
	fill="currentColor"
	role={label ? 'img' : undefined}
	aria-label={label}
	aria-hidden={label ? undefined : 'true'}
	>{@html PHOSPHOR[name][weight]}</svg
>
