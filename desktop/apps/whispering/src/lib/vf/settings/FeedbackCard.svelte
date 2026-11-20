<script lang="ts">
	import Icon from '$lib/vf/Icon.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import { settings } from '$lib/stores/settings.svelte';
	import { feedbackService } from '$lib/feedback/service.svelte';
	import { KEEP_FEEDBACK_LABELS, KEEP_FEEDBACK_OPTIONS, type KeepFeedback } from '$lib/feedback/log';
	import { FEEDBACK_MODELS, type FeedbackProvider } from '$lib/feedback/providers';

	/**
	 * VoiceFlow phase F: Settings > Feedback. Which model turns feedback into
	 * a fix (the person's own key; Anthropic only when a key is set), how long
	 * feedback is kept on this PC, and "Delete all feedback".
	 */
	const hasAnthropic = $derived(!!settings.value['apiKeys.anthropic']);
	const options = $derived(FEEDBACK_MODELS.filter((m) => m.provider === 'OpenAI' || hasAnthropic));
	const current = $derived(`${settings.value['feedback.provider']}|${settings.value['feedback.model']}`);
	const known = $derived(options.some((o) => `${o.provider}|${o.model}` === current));

	let confirming = $state(false);
	let done = $state<string | null>(null);

	function choose(value: string) {
		const [provider, model] = value.split('|') as [FeedbackProvider, string];
		settings.update({ 'feedback.provider': provider, 'feedback.model': model });
	}

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
		<div><b>Feedback model</b><span>Turns your feedback into a fix, with your own API key</span></div>
		<select class="sel" aria-label="Feedback model" value={current} onchange={(e) => choose(e.currentTarget.value)}>
			{#if !known}<option value={current}>{settings.value['feedback.provider']} {settings.value['feedback.model']}</option>{/if}
			{#each options as o (`${o.provider}|${o.model}`)}
				<option value={`${o.provider}|${o.model}`}>{o.label}</option>
			{/each}
		</select>
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
		Sending feedback shares that one dictation's text (as heard, tidied and pasted), the spellings
		that changed it, its style,
		expression and outcome, the app's name, your style names, related words from your lists and your
		feedback with the provider above. Window titles never leave this PC.
	</p>
</section>

<style>
	.tight {
		margin-top: 0;
	}
</style>
