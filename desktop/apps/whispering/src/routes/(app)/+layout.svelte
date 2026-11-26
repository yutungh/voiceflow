<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { listen, type UnlistenFn } from '@tauri-apps/api/event';
	import { Button } from '@epicenter/ui/button';
	import * as Sheet from '@epicenter/ui/sheet';
	import MenuIcon from '@lucide/svelte/icons/menu';
	import XIcon from '@lucide/svelte/icons/x';
	import { rpc } from '$lib/query';
	import { services } from '$lib/services';
	import AppLayout from './_components/AppLayout.svelte';
	import AppSidebar, { pageTitle } from './_components/AppSidebar.svelte';
	import StatusBar from './_components/StatusBar.svelte';
	import { syncThemeWithDesktop } from './_layout-utils/syncThemeWithDesktop.svelte';
	import { shouldShowFirstRun } from '$lib/vf/first-run';

	let { children } = $props();

	// VoiceFlow C1: first-run setup is a full window, without the sidebar.
	const isFirstRun = $derived(page.url.pathname.startsWith('/welcome'));

	onMount(async () => {
		if (isFirstRun) return;
		if (await shouldShowFirstRun()) goto('/welcome', { replaceState: true });
	});

	/** The narrow-window menu (below 768 px the sidebar becomes this drawer). */
	let drawerOpen = $state(false);
	let unlistenNavigate: UnlistenFn | null = null;

	if (window.__TAURI_INTERNALS__) {
		syncThemeWithDesktop();
	}

	$effect(() => {
		const unlisten = services.localShortcutManager.listen();
		return () => unlisten();
	});

	// Log app started event once on mount
	$effect(() => {
		rpc.analytics.logEvent.execute({ type: 'app_started' });
	});

	// Listen for navigation events from other windows
	onMount(async () => {
		unlistenNavigate = await listen<{ path: string }>(
			'navigate-main-window',
			(event) => {
				goto(event.payload.path);
			},
		);
	});

	onDestroy(() => {
		unlistenNavigate?.();
	});
</script>

<!--
	B3.1 app shell: a labelled sidebar on the left, the page, and the status
	bar along the bottom. In a narrow window the sidebar becomes a top bar with
	a labelled Menu button that opens the same items in a drawer. Below 196 px
	(the "Mini window" size) only the record button shows.
-->
{#if isFirstRun}
	<AppLayout>
		{@render children()}
	</AppLayout>
{:else}
<div class="flex h-svh flex-col overflow-hidden">
	<div class="flex min-h-0 flex-1">
		<aside
			class="bg-rail hidden w-[272px] flex-none flex-col px-3 pb-3 pt-3.5 md:flex"
			aria-label="Sidebar"
		>
			<AppSidebar />
		</aside>

		<div class="flex min-w-0 flex-1 flex-col">
			<header
				class="bg-rail xxs:flex hidden h-[54px] flex-none items-center gap-3 px-3 md:hidden"
			>
				<Button
					variant="outline"
					class="h-10 gap-2 px-3.5 text-[15px]"
					onclick={() => (drawerOpen = true)}
					aria-expanded={drawerOpen}
				>
					<MenuIcon class="size-5" aria-hidden="true" />
					Menu
				</Button>
				<span class="font-display truncate text-base font-semibold"
					>{pageTitle(page.url.pathname)}</span
				>
			</header>

			<div class="flex min-h-0 flex-1 flex-col overflow-auto">
				<AppLayout>
					{@render children()}
				</AppLayout>
			</div>
		</div>
	</div>

	<StatusBar />
</div>
{/if}

<Sheet.Root bind:open={drawerOpen}>
	<Sheet.Content
		side="left"
		class="bg-rail w-[320px] max-w-[85vw] gap-0 border-0 p-3 shadow-pop sm:max-w-[320px] [&>button]:hidden"
	>
		<Sheet.Header class="sr-only">
			<Sheet.Title>Menu</Sheet.Title>
			<Sheet.Description>Go to a page of VoiceLnq.</Sheet.Description>
		</Sheet.Header>
		<AppSidebar size="drawer" onNavigate={() => (drawerOpen = false)}>
			{#snippet header()}
				<div class="mb-2.5 flex h-[42px] items-center gap-2.5 pl-1">
					<img src="/apple-touch-icon.png" alt="" class="size-7 rounded-[7px]" />
					<span class="font-display text-[15px] font-semibold">VoiceLnq</span>
					<Button
						variant="ghost"
						class="ml-auto gap-1.5 text-[15px]"
						onclick={() => (drawerOpen = false)}
					>
						<XIcon class="size-5" aria-hidden="true" />
						Close
					</Button>
				</div>
			{/snippet}
		</AppSidebar>
	</Sheet.Content>
</Sheet.Root>
