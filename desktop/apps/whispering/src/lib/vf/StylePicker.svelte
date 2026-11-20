<script lang="ts">
	import * as DropdownMenu from '@epicenter/ui/dropdown-menu';
	import { createQuery } from '@tanstack/svelte-query';
	import { rpc } from '$lib/query';
	import { settings } from '$lib/stores/settings.svelte';
	import { profileStyleKey } from '$lib/profile/ownership';
	import { wordLists } from '$lib/profile/service.svelte';
	import Icon from './Icon.svelte';
	import { styleSummary } from './styles';

	/**
	 * Picks the style new dictations use (the default style). "Raw" means no
	 * tidy-up; saved spellings still apply.
	 */
	let {
		label = 'Change',
		heading = 'Style for new dictations',
		triggerClass = 'btn text',
	}: { label?: string; heading?: string; triggerClass?: string } = $props();

	const stylesQuery = createQuery(() => rpc.db.transformations.getAll.options);
	const selected = $derived(
		settings.value['transformations.selectedTransformationId'],
	);
	/** VoiceFlow B + C2: styles from the word lists, listed first. */
	const profileStyles = $derived(
		// 0.9.13: a hidden word-list style is not offered (it still works as a default).
		(wordLists.snapshot?.styles ?? []).filter((s) => s.hidden !== true),
	);
	const owned = $derived(
		settings.isProfileOwned('transformations.selectedTransformationId'),
	);
</script>

<DropdownMenu.Root>
	<DropdownMenu.Trigger class={triggerClass}>
		<span>{label}</span>
		{#if triggerClass.includes('sel')}<Icon name="caret-down" class="i sm" />{/if}
	</DropdownMenu.Trigger>
	<DropdownMenu.Content align="end" class="vf w-[300px] p-1.5">
		<p class="m-h">{heading}</p>
		{#if owned}
			<p class="m-f"><Icon name="lock-simple" class="i sm" />Set in your word lists</p>
		{/if}
		<DropdownMenu.RadioGroup
			bind:value={
				() => selected ?? 'raw',
				(v) =>
					settings.updateKey(
						'transformations.selectedTransformationId',
						v === 'raw' ? null : v,
					)
			}
		>
			{#each profileStyles as style (style.id)}
				<DropdownMenu.RadioItem value={profileStyleKey(style.id)} class="items-start py-2" disabled={owned}>
					<span class="flex flex-col">
						<b class="font-semibold">{style.label}</b>
						<span class="text-subtle-foreground text-[13px]">From your word lists</span>
					</span>
				</DropdownMenu.RadioItem>
			{/each}
			{#each stylesQuery.data ?? [] as style (style.id)}
				<DropdownMenu.RadioItem value={style.id} class="items-start py-2" disabled={owned}>
					<span class="flex flex-col">
						<b class="font-semibold">{style.title || 'Untitled style'}</b>
						<span class="text-subtle-foreground text-[13px]">{styleSummary(style)}</span>
					</span>
				</DropdownMenu.RadioItem>
			{/each}
			<DropdownMenu.RadioItem value="raw" class="items-start py-2" disabled={owned}>
				<span class="flex flex-col">
					<b class="font-semibold">Raw</b>
					<span class="text-subtle-foreground text-[13px]"
						>No tidy-up; saved spellings still apply</span
					>
				</span>
			</DropdownMenu.RadioItem>
		</DropdownMenu.RadioGroup>
		<p class="m-f"><Icon name="info" class="i sm" />Applies to new dictations only</p>
	</DropdownMenu.Content>
</DropdownMenu.Root>
