<script lang="ts">
	import { onNavigate } from '$app/navigation';
	import { queryClient } from '$lib/query/client';
	import { QueryClientProvider } from '@tanstack/svelte-query';
	import { SvelteQueryDevtools } from '@tanstack/svelte-query-devtools';
	import { ModeWatcher, mode } from 'mode-watcher';
	import { Toaster, type ToasterProps } from 'svelte-sonner';
	import '@epicenter/ui/app.css';
	import '$lib/vf/vf.css';
	import * as Tooltip from '@epicenter/ui/tooltip';

	let { children } = $props();

	const TOASTER_SETTINGS = {
		position: 'bottom-right',
		richColors: true,
		duration: 5000,
		visibleToasts: 5,
		toastOptions: {
			classes: {
				toast: 'flex flex-wrap *:data-content:flex-1',
				icon: 'shrink-0',
				actionButton: 'w-full mt-3 inline-flex justify-center',
				closeButton: 'w-full mt-3 inline-flex justify-center',
			},
		},
		// VoiceFlow 0.9.11: routine toasts hide by themselves; one that stays
		// open gets its own close button (`toast.ts`).
		closeButton: false,
	} satisfies ToasterProps;

	onNavigate((navigation) => {
		if (!document.startViewTransition) return;

		return new Promise((resolve) => {
			document.startViewTransition(async () => {
				resolve();
				await navigation.complete;
			});
		});
	});
</script>

<svelte:head>
	<title>VoiceFlow</title>
</svelte:head>

<QueryClientProvider client={queryClient}>
	<Tooltip.Provider delayDuration={300} skipDelayDuration={150}>
		{@render children()}
	</Tooltip.Provider>
</QueryClientProvider>

<Toaster
	offset={16}
	class="xs:block hidden"
	theme={mode.current}
	{...TOASTER_SETTINGS}
/>
<!-- Theme: System (follows the OS) unless the user picks Light or Dark; see $lib/theme.svelte.ts -->
<ModeWatcher defaultMode="system" />
<SvelteQueryDevtools client={queryClient} buttonPosition="bottom-right" />

<style>
	/* Override inspector button to bottom-center positioning */
	:global(#svelte-inspector-host button) {
		bottom: 16px !important;
		left: 50% !important;
		transform: translateX(-50%) !important;
		right: auto !important;
	}
</style>
