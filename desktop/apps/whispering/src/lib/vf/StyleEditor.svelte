<script lang="ts">
	import * as Dialog from '@epicenter/ui/dialog';
	import { untrack } from 'svelte';
	import { rpc } from '$lib/query';
	import type { Transformation } from '$lib/services/isomorphic/db';
	import Icon from './Icon.svelte';
	import {
		STYLE_MODEL_SUGGESTIONS,
		modelIsEditable,
		modelOf,
		promptOf,
		styleShape,
		withStyleFields,
	} from './styles';

	/**
	 * The simple style editor: a name, one prompt and a model. Steps, ids
	 * and the old pipeline builder stay out of sight; spellings fixed with
	 * find-and-replace steps before keep working.
	 */
	let {
		style,
		isNew = false,
		open = $bindable(false),
		onSaved,
	}: {
		style: Transformation;
		isNew?: boolean;
		open?: boolean;
		onSaved?: (style: Transformation) => void;
	} = $props();

	const shape = untrack(() => styleShape(style));
	const step = shape.kind === 'simple' ? shape.prompt : null;
	let title = $state(untrack(() => style.title));
	let prompt = $state(step ? promptOf(step) : '');
	let model = $state(step ? modelOf(step) : STYLE_MODEL_SUGGESTIONS[0]);
	const editableModel = step ? modelIsEditable(step) : true;
	const provider = step?.['prompt_transform.inference.provider'] ?? 'OpenAI';
	let error = $state<string | null>(null);
	let saving = $state(false);

	async function save(e: SubmitEvent) {
		e.preventDefault();
		if (!title.trim()) return (error = 'Give the style a name.');
		if (!prompt.trim()) return (error = 'Say how VoiceFlow should tidy your words.');
		if (!model.trim()) return (error = 'Choose a model.');
		saving = true;
		const next = withStyleFields(style, {
			title: title.trim(),
			prompt: prompt.trim(),
			model: model.trim(),
		});
		const { error: saveError } = isNew
			? await rpc.db.transformations.create.execute(next)
			: await rpc.db.transformations.update.execute(next);
		saving = false;
		if (saveError) {
			error = `The style couldn't be saved (${saveError.message}).`;
			return;
		}
		open = false;
		onSaved?.(next);
	}
</script>

<Dialog.Root bind:open>
	<Dialog.Content class="vf sm:max-w-[560px]">
		<Dialog.Header>
			<Dialog.Title>{isNew ? 'New style' : `Edit ${style.title || 'style'}`}</Dialog.Title>
			<Dialog.Description>How VoiceFlow tidies your words with this style.</Dialog.Description>
		</Dialog.Header>
		<form class="sform" onsubmit={save}>
			<label for="st-name">Name</label>
			<input id="st-name" class="inp" bind:value={title} placeholder="For example: Work" />
			<label for="st-prompt">What it does</label>
			<textarea
				id="st-prompt"
				class="inp area"
				rows="6"
				bind:value={prompt}
				placeholder="For example: Rewrite this in full sentences, without slang. Reply with the text only."
			></textarea>
			<p class="hint">Your dictation is sent with these instructions; the reply is what gets pasted.</p>
			<label for="st-model">Model</label>
			{#if editableModel}
				<input id="st-model" class="inp" bind:value={model} list="st-models" autocomplete="off" />
				<datalist id="st-models">
					{#each STYLE_MODEL_SUGGESTIONS as m}<option value={m}></option>{/each}
				</datalist>
			{:else}
				<p class="inp ro" id="st-model">{provider} · {model}</p>
			{/if}
			{#if error}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
			{/if}
			<p class="note"><Icon name="info" class="i sm" />Applies to new dictations only.</p>
			<div class="acts">
				<button class="btn pri" type="submit" disabled={saving}>{isNew ? 'Make style' : 'Save style'}</button>
				<button class="btn text" type="button" onclick={() => (open = false)}>Cancel</button>
			</div>
		</form>
	</Dialog.Content>
</Dialog.Root>
