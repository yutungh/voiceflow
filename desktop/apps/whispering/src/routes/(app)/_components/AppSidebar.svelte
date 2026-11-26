<script lang="ts" module>
	import BookOpenTextIcon from '@lucide/svelte/icons/book-open-text';
	import HistoryIcon from '@lucide/svelte/icons/history';
	import HouseIcon from '@lucide/svelte/icons/house';
	import SettingsIcon from '@lucide/svelte/icons/settings';
	import WandSparklesIcon from '@lucide/svelte/icons/wand-sparkles';

	/**
	 * The app's pages, in B3.1 order. Ideas comes with the feature behind it,
	 * not before.
	 */
	export const NAV_ITEMS = [
		{ label: 'Home', href: '/', icon: HouseIcon, match: (p: string) => p === '/' },
		{
			label: 'History',
			href: '/history',
			icon: HistoryIcon,
			match: (p: string) => p.startsWith('/history'),
		},
		{
			label: 'Vocabulary',
			href: '/vocabulary',
			icon: BookOpenTextIcon,
			match: (p: string) => p.startsWith('/vocabulary'),
		},
		{
			label: 'Styles',
			href: '/styles',
			icon: WandSparklesIcon,
			match: (p: string) => p.startsWith('/styles'),
		},
		{
			label: 'Settings',
			href: '/settings',
			icon: SettingsIcon,
			match: (p: string) => p.startsWith('/settings'),
		},
	] as const;

	export function pageTitle(pathname: string): string {
		return NAV_ITEMS.find((item) => item.match(pathname))?.label ?? 'VoiceLnq';
	}
</script>

<script lang="ts">
	import { page } from '$app/state';
	import { cn } from '@epicenter/ui/utils';
	import Keys from '$lib/components/Keys.svelte';
	import { settings } from '$lib/stores/settings.svelte';
	import type { Snippet } from 'svelte';

	let {
		size = 'rail',
		onNavigate,
		header,
	}: {
		/** "rail": the 272 px sidebar; "drawer": the narrow-window menu (taller rows). */
		size?: 'rail' | 'drawer';
		onNavigate?: () => void;
		/** Replaces the wordmark row (the drawer adds its Close button there). */
		header?: Snippet;
	} = $props();

	const toggleShortcut = $derived(
		settings.value['shortcuts.global.toggleManualRecording'],
	);
	const holdShortcut = $derived(settings.value['shortcuts.global.pushToTalk']);

	// The rail card's still waveform: ten bars, the B3 signature.
	const BARS = [6, 10, 14, 8, 12, 6, 9, 13, 7, 10];
</script>

{#snippet wordmark()}
	<a
		href="/"
		class="focus-visible:outline-ring flex items-center gap-2.5 rounded-md px-2 pb-3.5 pt-0.5 focus-visible:outline-2"
		onclick={() => onNavigate?.()}
	>
		<img src="/apple-touch-icon.png" alt="" class="size-7 rounded-[7px]" />
		<span class="font-display text-[15px] font-semibold tracking-[-0.01em]"
			>VoiceLnq</span
		>
	</a>
{/snippet}

<div class="flex h-full min-h-0 flex-col">
	{#if header}
		{@render header()}
	{:else}
		{@render wordmark()}
	{/if}

	<nav aria-label="Pages" class="flex flex-col gap-0.5">
		{#each NAV_ITEMS as item (item.href)}
			{@const active = item.match(page.url.pathname)}
			{@const Icon = item.icon}
			<a
				href={item.href}
				aria-current={active ? 'page' : undefined}
				onclick={() => onNavigate?.()}
				class={cn(
					'focus-visible:outline-ring flex items-center gap-3 rounded-md px-2.5 font-medium transition-colors duration-150 focus-visible:outline-2 focus-visible:-outline-offset-2',
					size === 'drawer' ? 'h-11 text-[15px]' : 'h-[38px] text-sm',
					active
						? 'bg-primary-tint text-foreground font-semibold'
						: 'text-muted-foreground hover:bg-muted hover:text-foreground',
				)}
			>
				<Icon
					class={cn('size-5 flex-none', active && 'text-primary')}
					strokeWidth={active ? 2.25 : 1.75}
					aria-hidden="true"
				/>
				<span class="flex-1">{item.label}</span>
			</a>
		{/each}
	</nav>

	{#if toggleShortcut || holdShortcut}
		<section
			aria-label="Dictate in any app"
			class="bg-card text-muted-foreground shadow-card mt-auto flex flex-col gap-2 rounded-xl p-3 text-[13px]"
		>
			<span class="flex h-4 items-center gap-[2px]" aria-hidden="true">
				{#each BARS as h}
					<i class="bg-wave block w-[2.5px] rounded-full opacity-50" style="height:{h}px"
					></i>
				{/each}
			</span>
			<p class="text-foreground font-semibold">Dictate in any app</p>
			{#if toggleShortcut}
				<div class="flex items-center gap-2.5">
					<Keys shortcut={toggleShortcut} class="min-w-[104px]" />
					<span>Start or stop</span>
				</div>
			{/if}
			{#if holdShortcut}
				<div class="flex items-center gap-2.5">
					<Keys shortcut={holdShortcut} class="min-w-[104px]" />
					<span>Hold to talk</span>
				</div>
			{/if}
		</section>
	{/if}
</div>
