<script lang="ts">
	import { cn } from '@epicenter/ui/utils';
	import MonitorIcon from '@lucide/svelte/icons/monitor';
	import MoonIcon from '@lucide/svelte/icons/moon';
	import SunIcon from '@lucide/svelte/icons/sun';
	import { THEME_OPTIONS, theme } from '$lib/theme.svelte';

	let { class: className, id }: { class?: string; id?: string } = $props();

	const ICONS = { system: MonitorIcon, light: SunIcon, dark: MoonIcon };

	function move(delta: number) {
		const i = THEME_OPTIONS.findIndex((o) => o.value === theme.choice);
		const next =
			THEME_OPTIONS[(i + delta + THEME_OPTIONS.length) % THEME_OPTIONS.length];
		if (!next) return;
		theme.set(next.value);
		document.getElementById(`theme-option-${next.value}`)?.focus();
	}
</script>

<!-- Segmented System / Light / Dark switch (B3.1 Settings > Appearance). -->
<div
	{id}
	role="radiogroup"
	aria-label="Theme"
	tabindex="-1"
	class={cn(
		'bg-muted inline-flex gap-0.5 rounded-md p-[3px] shadow-[inset_0_0_0_1px_var(--border)]',
		className,
	)}
	onkeydown={(e) => {
		if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
			e.preventDefault();
			move(1);
		} else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
			e.preventDefault();
			move(-1);
		}
	}}
>
	{#each THEME_OPTIONS as option (option.value)}
		{@const Icon = ICONS[option.value]}
		{@const selected = theme.choice === option.value}
		<button
			type="button"
			role="radio"
			id="theme-option-{option.value}"
			aria-checked={selected}
			tabindex={selected ? 0 : -1}
			onclick={() => theme.set(option.value)}
			class={cn(
				'focus-visible:outline-ring inline-flex h-8 items-center gap-1.5 rounded-[7px] px-3 text-sm font-medium transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-1',
				selected
					? 'bg-card text-foreground font-semibold shadow-card'
					: 'text-muted-foreground hover:text-foreground',
			)}
		>
			<Icon class={cn('size-4', selected && 'text-primary')} aria-hidden="true" />
			{option.label}
		</button>
	{/each}
</div>
