<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from './Icon.svelte';
	import Tile from './Tile.svelte';
	import { EVERY_APP } from '$lib/profile/ops';
	import { saveCorrection } from '$lib/profile/op-store';
	import { wordLists } from '$lib/profile/service.svelte';

	/**
	 * "Fix a spelling" (B3.1): the person types what VoiceFlow heard and how
	 * it should be written. It is saved as a profile operation on this PC
	 * (and, with word lists on, uploaded to the person's own list) and used
	 * from the next dictation; this one stays as it was.
	 */
	let {
		heard = '',
		appName,
		appId,
		onSaved,
		onCancel,
	}: {
		heard?: string;
		appName?: string | null;
		appId?: string | null;
		onSaved: (saved: { id: string; from: string; to: string }) => void;
		onCancel: () => void;
	} = $props();

	let from = $state(untrack(() => heard));
	let to = $state('');
	let where = $state<'every' | 'app'>('every');
	let error = $state<string | null>(null);
	let saving = $state(false);
	let toInput = $state<HTMLInputElement | null>(null);

	$effect(() => {
		toInput?.focus();
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = null;
		const result = await saveCorrection({
			from: [from],
			to,
			scope: { apps: where === 'app' && appId ? [appId] : [EVERY_APP] },
		});
		saving = false;
		if (!result.ok) {
			error = result.message;
			return;
		}
		onSaved({ id: result.op.id, from: from.trim(), to: to.trim() });
	}
</script>

<form class="pop" aria-label="Fix a spelling" onsubmit={save}>
	<div class="pop-h"><Tile icon="pencil-simple" /><b>Teach VoiceFlow a spelling</b></div>
	<label for="sp-heard">It heard</label>
	<input id="sp-heard" class="inp" bind:value={from} autocomplete="off" required />
	<label for="sp-to">Always write it as</label>
	<input id="sp-to" class="inp" bind:value={to} bind:this={toInput} autocomplete="off" required />
	{#if appId}
		<label for="sp-where">Where</label>
		<select id="sp-where" class="inp" bind:value={where}>
			<option value="every">Every app</option>
			<option value="app">Only in {appName ?? 'this app'}</option>
		</select>
	{/if}
	{#if error}
		<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
	{/if}
	<p class="note">
		<Icon name="info" class="i sm" />{wordLists.active
			? 'Saved to your word list, on every device you use.'
			: 'Saved on this PC.'} Used from your next dictation; this one stays as it was.
	</p>
	<div class="acts">
		<button class="btn pri" type="submit" disabled={saving}>Save spelling</button>
		<button class="btn text" type="button" onclick={onCancel}>Cancel</button>
	</div>
</form>
