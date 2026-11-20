<script lang="ts">
	import { Switch } from '@epicenter/ui/switch';
	import { untrack } from 'svelte';
	import { deviceId } from '$lib/profile/op-store';
	import { wordLists } from '$lib/profile/service.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import { syncSummary } from '$lib/vf/word-lists';

	/**
	 * Settings > Word lists (plan v2, B + C2): where the word lists live (a
	 * folder on this PC or a network share), whose list this device uses,
	 * and the assistant who manages the "from notes" list. Device settings,
	 * kept by the profile service in the app's data folder.
	 */

	const isDesktop = !!window.__TAURI_INTERNALS__;
	const current = $derived(wordLists.state?.config);
	const summary = $derived(syncSummary(wordLists.state));

	let enabled = $state(untrack(() => current?.enabled ?? false));
	let source = $state(untrack(() => current?.source ?? ''));
	let user = $state(untrack(() => current?.user ?? ''));
	let assistant = $state(untrack(() => current?.assistant ?? ''));
	let users = $state<string[]>([]);
	let error = $state<string | null>(null);
	let saved = $state(false);
	let saving = $state(false);
	let loaded = false;

	// Fill the form once the service has answered.
	$effect(() => {
		if (loaded || !current) return;
		loaded = true;
		enabled = current.enabled;
		source = current.source;
		user = current.user;
		assistant = current.assistant;
	});

	async function findUsers() {
		users = await wordLists.listUsers(source);
	}
	$effect(() => {
		if (source.trim()) void untrack(findUsers);
	});

	const dirty = $derived(
		!current ||
			enabled !== current.enabled ||
			source.trim() !== current.source ||
			user.trim().toLowerCase() !== current.user ||
			assistant.trim() !== current.assistant,
	);

	async function save(e?: SubmitEvent) {
		e?.preventDefault();
		error = null;
		saving = true;
		try {
			await wordLists.setConfig({
				enabled,
				source: source.trim(),
				user: user.trim().toLowerCase(),
				assistant: assistant.trim(),
				device: deviceId(),
			});
			saved = true;
			setTimeout(() => (saved = false), 2500);
		} catch (err) {
			error = err instanceof Error ? err.message : String(err);
		} finally {
			saving = false;
		}
	}

	function toggle(on: boolean) {
		enabled = on;
		// Turning it off needs nothing else; turning it on waits for the form.
		if (!on && current?.enabled) void save();
	}
</script>

{#if !isDesktop}
	<p class="sub">Word lists are set up in the desktop app.</p>
{:else}
	<div class="srow">
		<div>
			<b>Use word lists</b>
			<span>Words, spellings and styles come from a shared folder, so every device spells your way</span>
		</div>
		<Switch checked={enabled} onCheckedChange={toggle} aria-label="Use word lists" />
	</div>
	{#if enabled}
		<form class="wl" onsubmit={save}>
			<label for="wl-source">Folder</label>
			<input
				id="wl-source"
				class="inp"
				bind:value={source}
				onblur={findUsers}
				placeholder={'\\\\NAS\\voiceflow or C:\\Users\\you\\VoiceFlow'}
				autocomplete="off"
				spellcheck="false"
			/>
			<label for="wl-user">Whose words</label>
			<input id="wl-user" class="inp" bind:value={user} list="wl-users" placeholder="your name, lower case" autocomplete="off" spellcheck="false" />
			<datalist id="wl-users">
				{#each users as u (u)}<option value={u}></option>{/each}
			</datalist>
			{#if users.length > 0}
				<span class="sub small">In this folder: {users.join(', ')}</span>
			{/if}
			<label for="wl-assistant">Your assistant’s name <span class="sub">(optional)</span></label>
			<input id="wl-assistant" class="inp" bind:value={assistant} placeholder="Who keeps your notes" autocomplete="off" />
			{#if error}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
			{/if}
			<div class="acts">
				<button class="btn pri" type="submit" disabled={saving || !dirty}>{saved ? 'Saved' : 'Save'}</button>
			</div>
		</form>
	{/if}
	{#if current?.enabled}
		<div class="srow">
			<div><b>{summary.title}</b><span>{summary.detail}</span></div>
			<button class="btn text" type="button" onclick={() => wordLists.refresh()} disabled={wordLists.state?.status.refreshing}>
				<Icon name="arrows-clockwise" /><span>Refresh now</span>
			</button>
		</div>
		<div class="srow">
			<div><b>Your words and spellings</b><span>See them, add a word or fix a spelling</span></div>
			<a class="btn text" href="/vocabulary">Vocabulary</a>
		</div>
	{/if}
{/if}

<style>
	.wl {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 6px 0 12px;
	}
	.wl label {
		font-size: 12px;
		font-weight: 600;
		color: var(--subtle-foreground);
		margin-top: 6px;
	}
	.wl .acts {
		margin-top: 10px;
	}
</style>
