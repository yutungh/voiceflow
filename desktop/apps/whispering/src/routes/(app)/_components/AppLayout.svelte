<script lang="ts">
	import * as Dialog from '@epicenter/ui/dialog';
	// import { extension } from '@epicenter/extension';
	import { createQuery } from '@tanstack/svelte-query';
	import { onDestroy, onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { commandCallbacks } from '$lib/commands';
	import ConfirmationDialog from '$lib/components/ConfirmationDialog.svelte';
	import MoreDetailsDialog from '$lib/components/MoreDetailsDialog.svelte';
	import NotificationLog from '$lib/components/NotificationLog.svelte';
	import { rpc } from '$lib/query';
	import Icon from '$lib/vf/Icon.svelte';
	import { scheduleAudioCleanup } from '$lib/vf/audio-cleanup';
	import { syncWindowAlwaysOnTopWithRecorderState } from '../_layout-utils/alwaysOnTop.svelte';
	import { checkIndexedDBMigration } from '../_layout-utils/check-indexeddb-migration';
	import {
		resetGlobalShortcutsToDefaultIfDuplicates,
		resetLocalShortcutsToDefaultIfDuplicates,
		syncGlobalShortcutsWithSettings,
		syncLocalShortcutsWithSettings,
	} from '../_layout-utils/register-commands';
	import {
		registerAccessibilityPermission,
		registerMicrophonePermission,
	} from '../_layout-utils/register-permissions';
	import { syncIconWithRecorderState } from '../_layout-utils/syncIconWithRecorderState.svelte';

	const getRecorderStateQuery = createQuery(
		() => rpc.recorder.getRecorderState.options,
	);

	let cleanupAccessibilityPermission: (() => void) | undefined;
	let cleanupMicrophonePermission: (() => void) | undefined;

	onMount(() => {
		// Sync operations - run immediately, these are fast
		window.commands = commandCallbacks;
		window.goto = goto;
		syncLocalShortcutsWithSettings();
		resetLocalShortcutsToDefaultIfDuplicates();
		cleanupAccessibilityPermission = registerAccessibilityPermission();
		cleanupMicrophonePermission = registerMicrophonePermission();

		if (window.__TAURI_INTERNALS__) {
			syncGlobalShortcutsWithSettings();
			resetGlobalShortcutsToDefaultIfDuplicates();

			// Async operations - fire and forget, don't block UI rendering
			// These show toasts/notifications on completion, no need to await
			// VoiceFlow: upstream's FFmpeg and compression prompts are gone (VoiceFlow
			// records without FFmpeg); first-run setup replaces the welcome toast.
			Promise.allSettled([checkIndexedDBMigration()]);
		} else {
			// Browser extension context - notify that the Whispering tab is ready
			// extension.notifyWhisperingTabReady(undefined);
		}
	});

	onDestroy(() => {
		cleanupAccessibilityPermission?.();
		cleanupMicrophonePermission?.();
	});

	if (window.__TAURI_INTERNALS__) {
		syncWindowAlwaysOnTopWithRecorderState();
		syncIconWithRecorderState();
	}

	// VoiceFlow C1: one retention control, Keep recordings, which removes
	// only audio (dictations themselves stay in History). Upstream's
	// count-based removal of whole recordings no longer runs.
	$effect(() => scheduleAudioCleanup());

	let { children } = $props();
</script>

<button
	class="xxs:hidden hover:bg-accent hover:text-accent-foreground h-screen w-screen transform duration-300 ease-in-out"
	onclick={() => commandCallbacks.toggleManualRecording()}
>
	<span class="vf">
		{#if getRecorderStateQuery.data === 'RECORDING'}
			<Icon name="pause" weight="fill" class="size-10 text-record" label="Stop dictating" />
		{:else}
			<Icon name="microphone" weight="duotone" class="size-10 text-primary" label="Start dictating" />
		{/if}
	</span>
</button>

<div class="hidden flex-1 flex-col gap-2 xxs:flex min-w-0 w-full">
	{@render children()}
</div>

<ConfirmationDialog />
<MoreDetailsDialog />
<NotificationLog />
