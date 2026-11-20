<script lang="ts">
	import * as DropdownMenu from '@epicenter/ui/dropdown-menu';
	import { cn } from '@epicenter/ui/utils';
	import { createQuery } from '@tanstack/svelte-query';
	import MigrationDialog, {
		migrationDialog,
	} from '$lib/components/MigrationDialog.svelte';
	import { notificationLog } from '$lib/components/NotificationLog.svelte';
	import { rpc } from '$lib/query';
	import { getSelectedTranscriptionService } from '$lib/settings/transcription-validation';
	import { settings } from '$lib/stores/settings.svelte';
	import { vadRecorder } from '$lib/stores/vad-recorder.svelte';
	import { THEME_OPTIONS, theme, type ThemeChoice } from '$lib/theme.svelte';
	import { problemsOf } from '$lib/vf/dictations';
	import { tidyUpStatus } from '$lib/vf/style-models';

	/**
	 * B3.1 status bar: one dark strip at the bottom in both themes. It shows
	 * only what exists today: whether VoiceFlow is ready, the transcription
	 * model, the tidy-up model and style, and the theme. As the window narrows,
	 * the style goes first (below 1280 px), then Privacy (below 1024 px), then
	 * the tidy-up model (below 768 px), then the transcription model and
	 * Notifications (below 640 px); a model name that still does not fit is
	 * cut short, and its tooltip has it in full.
	 */

	const recorderStateQuery = createQuery(
		() => rpc.recorder.getRecorderState.options,
	);
	const transformationsQuery = createQuery(
		() => rpc.db.transformations.getAll.options,
	);
	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);

	// VoiceFlow C1: dictations that need the person (Home's "Needs you").
	const problemCount = $derived(problemsOf(recordingsQuery.data ?? []).length);
	const state = $derived.by(() => {
		if (settings.value['recording.mode'] === 'vad') {
			if (vadRecorder.state === 'SPEECH_DETECTED')
				return { tone: 'rec', text: 'Hearing you' } as const;
			if (vadRecorder.state === 'LISTENING')
				return { tone: 'rec', text: 'Listening' } as const;
		} else if (recorderStateQuery.data === 'RECORDING') {
			return { tone: 'rec', text: 'Listening' } as const;
		}
		return { tone: 'ok', text: 'Ready to dictate' } as const;
	});

	/** Transcription: the speech-to-text model, its service in the tooltip. */
	const transcription = $derived.by(() => {
		const service = getSelectedTranscriptionService();
		if (!service) {
			return {
				label: 'none chosen',
				detail: 'No speech-to-text service is chosen. Click to pick one in Settings.',
			};
		}
		const name =
			service.location === 'cloud'
				? String(settings.value[service.modelSettingKey] ?? '').trim()
				: '';
		return {
			label: name || service.name,
			detail: `Speech to text: ${service.name}${name ? ` ${name}` : ''}. Click to change it in Settings.`,
		};
	});

	/** The style in use (none means Raw) and its tidy-up model. */
	const activeStyle = $derived.by(() => {
		const id = settings.value['transformations.selectedTransformationId'];
		if (!id) return undefined;
		return (transformationsQuery.data ?? []).find((t) => t.id === id);
	});
	const tidyUp = $derived(tidyUpStatus(activeStyle));
	const styleName = $derived(activeStyle ? activeStyle.title.trim() || 'Untitled style' : 'Raw');

	const isDesktop = !!window.__TAURI_INTERNALS__;
	// VoiceFlow C1: shown only when there is old browser data to move.
	const showMigration = $derived(isDesktop && migrationDialog.hasIndexedDBData);

	const item =
		'focus-visible:outline-statusbar-ok inline-flex h-full items-center gap-1.5 whitespace-nowrap rounded-sm px-1 hover:text-white focus-visible:outline-2 focus-visible:-outline-offset-2';
</script>

<footer
	class="bg-statusbar text-statusbar-foreground xxs:flex hidden h-7 flex-none items-center gap-3 overflow-hidden px-3 text-xs lg:gap-4"
	aria-label="Status"
>
	<a
		href="/"
		class={cn(item, state.tone === 'ok' ? 'text-statusbar-ok' : 'text-statusbar-warn')}
		aria-live="polite"
	>
		<span
			class={cn(
				'size-2 flex-none rounded-full',
				state.tone === 'ok' ? 'bg-statusbar-ok' : 'bg-record',
			)}
			aria-hidden="true"
		></span>
		{state.text}
	</a>

	<a
		href="/settings#transcription"
		class={cn(item, 'hidden min-w-0 shrink sm:inline-flex')}
		title={transcription.detail}
	>
		<span class="truncate">Transcription: {transcription.label}</span>
	</a>
	<a href="/styles" class={cn(item, 'hidden min-w-0 shrink-[2] md:inline-flex')} title={tidyUp.detail}>
		<span class="truncate">Tidy-up: {tidyUp.label}</span>
	</a>
	<a
		href="/styles"
		class={cn(item, 'hidden min-w-0 shrink-[3] xl:inline-flex')}
		title="Style in use: {styleName}. Click to manage styles."
	>
		<span class="truncate">Style: {styleName}</span>
	</a>

	{#if problemCount > 0}
		<a
			href="/"
			class={cn(item, 'text-statusbar-warn underline underline-offset-[3px]')}
			title="See them on Home"
		>
			<span class="bg-statusbar-warn size-2 flex-none rounded-full" aria-hidden="true"></span>
			{problemCount === 1 ? '1 problem needs you' : `${problemCount} problems need you`}
		</a>
	{/if}

	<div class="ml-auto flex h-full flex-none items-center gap-3 lg:gap-4">
		{#if showMigration}
			<MigrationDialog>
				{#snippet trigger({ props })}
					<button {...props} class={cn(item, 'text-statusbar-warn underline underline-offset-[3px]')}>
						<span class="bg-statusbar-warn size-2 rounded-full" aria-hidden="true"></span>
						Data migration
					</button>
				{/snippet}
			</MigrationDialog>
		{/if}
		<button class={cn(item, 'hidden sm:inline-flex')} onclick={() => (notificationLog.isOpen = true)}>
			Notifications
		</button>
		<a href="/settings#privacy" class={cn(item, 'hidden lg:inline-flex')}>
			Privacy
		</a>
		<DropdownMenu.Root>
			<DropdownMenu.Trigger class={item}>Theme: {theme.label}</DropdownMenu.Trigger>
			<DropdownMenu.Content align="end" side="top" class="w-40">
				<DropdownMenu.Label>Theme</DropdownMenu.Label>
				<DropdownMenu.RadioGroup
					bind:value={
						() => theme.choice, (v) => theme.set(v as ThemeChoice)
					}
				>
					{#each THEME_OPTIONS as option (option.value)}
						<DropdownMenu.RadioItem value={option.value}>
							{option.label}
						</DropdownMenu.RadioItem>
					{/each}
				</DropdownMenu.RadioGroup>
			</DropdownMenu.Content>
		</DropdownMenu.Root>
	</div>
</footer>
