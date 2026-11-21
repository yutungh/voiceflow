<script lang="ts">
	import Icon from '$lib/vf/Icon.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import { settings } from '$lib/stores/settings.svelte';
	import { secretStoreLabel } from '$lib/services/desktop/secrets';
	import { feedbackService } from '$lib/feedback/service.svelte';
	import { KEEP_FEEDBACK_LABELS, KEEP_FEEDBACK_OPTIONS, type KeepFeedback } from '$lib/feedback/log';
	import {
		AUTOMATIC,
		FEEDBACK_PROVIDERS,
		FIX_MODELS,
		KEY_PLACE,
		choiceKey,
		storedChoice,
		fixModelFromSettings,
		hasKeys,
		keyNeeded,
		modelName,
		parseChoiceKey,
		providerAndModel,
	} from '$lib/feedback/fix-model';

	/**
	 * VoiceFlow phase F: Settings > Feedback. Which model finds fixes from
	 * feedback (0.9.17: any provider with a key, grouped by provider; kept on
	 * this PC only), the Anthropic key (the OpenAI key is under Transcription),
	 * how long feedback is kept on this PC, and "Delete all feedback".
	 */
	const keys = $derived(hasKeys(settings.value));
	const resolved = $derived(fixModelFromSettings(settings.value));
	/** The model the person picked; null means Automatic. */
	const chosen = $derived(
		storedChoice({
			fixModel: settings.value['feedback.fixModel'],
			legacyProvider: settings.value['feedback.provider'],
			legacyModel: settings.value['feedback.model'],
		}),
	);
	const current = $derived(chosen ? choiceKey(chosen) : AUTOMATIC);
	const known = $derived(FIX_MODELS.some((m) => choiceKey(m) === current));
	const missing = $derived(resolved.ok ? null : keyNeeded(resolved));
	const automaticLabel = $derived(
		resolved.ok && !resolved.chosen
			? `Automatic (now ${resolved.choice.provider} · ${modelName(resolved.choice)})`
			: chosen
				? 'Automatic (the first provider with a key)'
				: 'Automatic',
	);

	function choose(value: string) {
		if (value === AUTOMATIC) return settings.updateKey('feedback.fixModel', AUTOMATIC);
		const choice = parseChoiceKey(value);
		if (choice) settings.updateKey('feedback.fixModel', choiceKey(choice));
	}

	// ---- the Anthropic key (OS credential store, like the OpenAI key) ----------
	const storeLabel = $derived(
		settings.apiKeyStoreKind === 'loading' ? 'the credential store' : secretStoreLabel(settings.apiKeyStoreKind),
	);
	let replacingKey = $state(false);
	let newKey = $state('');
	let keySaved = $state(false);
	function saveKey(e: SubmitEvent) {
		e.preventDefault();
		if (!newKey.trim()) return;
		settings.updateKey('apiKeys.anthropic', newKey.trim());
		newKey = '';
		replacingKey = false;
		keySaved = true;
		setTimeout(() => (keySaved = false), 2500);
	}

	let confirming = $state(false);
	let done = $state<string | null>(null);

	async function deleteAll() {
		const n = await feedbackService.deleteAll();
		confirming = false;
		done = n === 1 ? 'Deleted 1 piece of feedback.' : `Deleted ${n} pieces of feedback.`;
		setTimeout(() => (done = null), 4000);
	}
</script>

<section class="card sp" id="feedback" aria-labelledby="h-feedback">
	<div class="c-h"><Tile icon="chat-circle" /><h2 id="h-feedback">Feedback</h2></div>
	<div class="srow">
		<div>
			<b>Model for finding fixes</b>
			<span>
				{#if resolved.ok}
					Find fixes sends your feedback to {providerAndModel(resolved.choice)}, with your own API key
				{:else}
					Needs an API key for the model you pick
				{/if}
			</span>
		</div>
		<select
			class="sel"
			aria-label="Model for finding fixes"
			value={current}
			onchange={(e) => choose(e.currentTarget.value)}
		>
			<option value={AUTOMATIC}>{automaticLabel}</option>
			{#if chosen && !known}<option value={current}>{chosen.provider} · {modelName(chosen)}</option>{/if}
			{#each FEEDBACK_PROVIDERS as provider (provider)}
				<optgroup
					label={keys[provider] ? provider : `${provider} · Add a key in ${KEY_PLACE[provider].label}`}
					disabled={!keys[provider]}
				>
					{#each FIX_MODELS.filter((m) => m.provider === provider) as m (choiceKey(m))}
						<option value={choiceKey(m)}>{m.name}{m.note && m.note !== 'default' ? ` (${m.note})` : ''}</option>
					{/each}
				</optgroup>
			{/each}
		</select>
	</div>
	{#if missing}
		<p class="note warnline small"><Icon name="warning" class="i sm" />{missing.message}</p>
	{/if}
	<div class="srow">
		<div><b>Anthropic key</b><span>For Claude models. Stored in {storeLabel}</span></div>
		{#if keySaved || keys.Anthropic}
			<span class="chip ok"><Icon name="check-circle" weight="fill" class="i xs" />Saved</span>
		{:else}
			<span class="chip"><Icon name="key" class="i xs" />Not added</span>
		{/if}
		<button class="btn text" type="button" onclick={() => (replacingKey = !replacingKey)}>
			{keys.Anthropic ? 'Replace' : 'Add'}
		</button>
	</div>
	{#if replacingKey}
		<form class="keyform" onsubmit={saveKey}>
			<input
				class="inp"
				type="password"
				bind:value={newKey}
				placeholder="sk-ant-…"
				aria-label="Anthropic key"
				autocomplete="off"
			/>
			<button class="btn pri" type="submit">Save key</button>
		</form>
	{/if}
	<div class="srow">
		<div><b>OpenAI key</b><span>Added in {KEY_PLACE.OpenAI.label}</span></div>
		{#if keys.OpenAI}
			<span class="chip ok"><Icon name="check-circle" weight="fill" class="i xs" />Saved</span>
		{:else}
			<span class="chip"><Icon name="key" class="i xs" />Not added</span>
		{/if}
		<a class="btn text" href="#transcription">Transcription</a>
	</div>
	<div class="srow">
		<div><b>Keep feedback</b><span>What you said and the fixes, on this PC only</span></div>
		<select
			class="sel"
			aria-label="Keep feedback"
			value={settings.value['feedback.keep']}
			onchange={(e) => settings.updateKey('feedback.keep', e.currentTarget.value as KeepFeedback)}
		>
			{#each KEEP_FEEDBACK_OPTIONS as o (o)}<option value={o}>{KEEP_FEEDBACK_LABELS[o]}</option>{/each}
		</select>
	</div>
	<div class="srow">
		<div><b>Delete all feedback</b><span>Fixes you already applied stay in your word lists</span></div>
		{#if confirming}
			<span class="acts tight">
				<button class="btn sec del" type="button" onclick={deleteAll}>Delete</button>
				<button class="btn text" type="button" onclick={() => (confirming = false)}>Cancel</button>
			</span>
		{:else}
			<button class="btn sec" type="button" onclick={() => (confirming = true)}>Delete all…</button>
		{/if}
	</div>
	{#if done}<p class="sub small" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{done}</p>{/if}
	<p class="sub small">
		Find fixes shares that one dictation's text (as heard, tidied and pasted), the spellings that
		changed it, its style, expression and outcome, the app's name, your style names, related words
		from your lists and your feedback with the model chosen above, using your own key. Nothing goes
		to VoiceFlow. Window titles never leave this PC. Your choice of model stays on this PC.
	</p>
</section>

<style>
	.tight {
		margin-top: 0;
	}
	.warnline {
		color: var(--warning);
		margin: 0 0 6px;
	}
</style>
