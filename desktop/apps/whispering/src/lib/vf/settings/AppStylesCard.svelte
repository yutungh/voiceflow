<script lang="ts">
	import { createQuery } from '@tanstack/svelte-query';
	import { IS_MACOS } from '$lib/constants/platform';
	import { bar } from '$lib/bar/controller';
	import { rpc } from '$lib/query';
	import { wordLists } from '$lib/profile/service.svelte';
	import {
		localAppRules,
		onLocalAppRulesChange,
		refreshLocalAppRules,
		removeAppStyle,
		replaceAppStyle,
		undoRemoveAppStyle,
	} from '$lib/profile/op-store';
	import type { TombstoneOp } from '$lib/profile/ops';
	import Icon from '$lib/vf/Icon.svelte';
	import { inPageOrder } from '$lib/vf/style-pins';
	import {
		GROUP_LABELS,
		appNames,
		appRows,
		levelOptions,
		programId,
		recentApps,
		rulesIn,
		styleChoices,
		type AppRow,
		type StyleChoice,
	} from '$lib/vf/app-styles';

	/**
	 * VoiceFlow 0.9.21: Settings > Tidy-up > Style for each app. Each app
	 * listed starts in its own style (a built-in, one from the word lists,
	 * one made on this PC, or Raw), and a level if one is chosen. Saved as
	 * the app's own `app-style` edits, over the word lists' rule for it.
	 */
	let { defaultLabel }: { defaultLabel: string } = $props();

	const stylesQuery = createQuery(() => rpc.db.transformations.getAll.options);
	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);

	// This PC's edits aren't reactive: bumped after each change here.
	let localVersion = $state(0);
	const local = $derived.by(() => {
		void localVersion;
		return new Map(localAppRules());
	});
	// Any change to them (here, or a feedback fix or its Undo) re-renders.
	$effect(() => {
		const stop = onLocalAppRulesChange(() => (localVersion += 1));
		void refreshLocalAppRules();
		return stop;
	});

	const device = $derived(
		inPageOrder(stylesQuery.data ?? []).map((s) => ({ id: s.id, title: s.title })),
	);
	const snapshot = $derived(wordLists.snapshot);
	const choices = $derived(styleChoices(snapshot, device));
	const groups = $derived(
		(['lists', 'builtin', 'pc', 'raw'] as const)
			.map((g) => ({ group: g, items: choices.filter((c) => c.group === g) }))
			.filter((g) => g.items.length > 0),
	);
	const recordings = $derived(recordingsQuery.data ?? []);
	const names = $derived(appNames(recordings));
	const rules = $derived(rulesIn(snapshot, local));
	const rows = $derived(
		appRows({
			rules,
			snapshot,
			device,
			deviceLoaded: !stylesQuery.isPending,
			names,
			defaultLabel,
		}),
	);
	const recent = $derived(recentApps(recordings, new Set(rows.map((r) => r.app))));

	let busy = $state<string | null>(null);
	let problem = $state<{ app: string; message: string } | null>(null);
	let notice = $state<string | null>(null);

	async function changed() {
		localVersion += 1;
		bar.refresh();
	}

	async function save(app: string, style: string, level: number | null) {
		if (busy) return;
		busy = app;
		problem = null;
		try {
			const result = await replaceAppStyle({
				app,
				style,
				...(level !== null && style !== 'raw' ? { expression: level } : {}),
			});
			if (!result.ok) problem = { app, message: result.message };
			else if (result.warning) problem = { app, message: result.warning };
			await changed();
		} finally {
			busy = null;
		}
	}

	const levelValue = (row: AppRow) => (row.level === null ? 'auto' : String(row.level));

	function onStyle(row: AppRow, ref: string) {
		void save(row.app, ref, ref === 'raw' ? null : row.level);
	}

	function onLevel(row: AppRow, value: string) {
		if (!row.ref) return;
		void save(row.app, row.ref, value === 'auto' ? null : Number(value));
	}

	// ---- remove, with Undo while nothing else changed for the app ----------
	let undo = $state<{ app: string; name: string; tombstones: TombstoneOp[]; left: number } | null>(null);
	let undoTimer: ReturnType<typeof setInterval> | undefined;

	async function remove(row: AppRow) {
		if (busy) return;
		busy = row.app;
		problem = null;
		try {
			const result = await removeAppStyle(row.app, row.editIds);
			if (!result.ok) problem = { app: row.app, message: result.message };
			await changed();
			if (result.tombstones.length > 0) startUndo(row, result.tombstones);
		} finally {
			busy = null;
		}
	}

	function startUndo(row: AppRow, tombstones: TombstoneOp[]) {
		clearInterval(undoTimer);
		const until = Date.now() + 8000;
		undo = { app: row.app, name: row.name, tombstones, left: 8000 };
		undoTimer = setInterval(() => {
			if (!undo) return clearInterval(undoTimer);
			undo.left = Math.max(0, until - Date.now());
			if (undo.left === 0) {
				clearInterval(undoTimer);
				undo = null;
			}
		}, 100);
	}

	async function undoRemove() {
		const u = undo;
		if (!u || busy) return;
		clearInterval(undoTimer);
		undo = null;
		busy = u.app;
		try {
			// Only while the app has no newer setting: an older one must not
			// come back under a choice made since (checked again in the store,
			// right before writing).
			const now = rulesIn(wordLists.snapshot, new Map(localAppRules())).find((r) => r.app === u.app);
			const brought =
				!(now && now.editIds.length > 0) && (await undoRemoveAppStyle(u.app, u.tombstones));
			if (!brought) notice = `${u.name} has a newer setting, so the removed one wasn’t brought back.`;
			await changed();
		} finally {
			busy = null;
		}
	}

	// ---- add an app -----------------------------------------------------------
	let adding = $state(false);
	let addApp = $state('');
	let typed = $state('');
	let addStyle = $state('');
	let addLevel = $state('auto');
	let addProblem = $state<string | null>(null);
	const OTHER = '__other';
	const addChoice = $derived(choices.find((c) => c.ref === addStyle));

	function openAdd() {
		adding = true;
		addApp = recent[0]?.id ?? OTHER;
		typed = '';
		addStyle = choices.find((c) => c.group === 'builtin')?.ref ?? choices[0]?.ref ?? 'raw';
		addLevel = 'auto';
		addProblem = null;
	}

	async function add(e: SubmitEvent) {
		e.preventDefault();
		addProblem = null;
		let app = addApp;
		if (app === OTHER) {
			const id = programId(typed, !IS_MACOS);
			if (!id.ok) return (addProblem = id.message);
			app = id.id;
		}
		if (!app) return (addProblem = 'Choose an app.');
		if (rows.some((r) => r.app === app && r.source === 'mine'))
			return (addProblem = 'That app is already listed; change its style above.');
		await save(app, addStyle, addLevel === 'auto' || addStyle === 'raw' ? null : Number(addLevel));
		if (!problem || problem.app !== app) adding = false;
	}

	const optionLabel = (c: StyleChoice) => c.label;
</script>

{#snippet stylePicker(value: string | undefined, label: string, onchange: (ref: string) => void, disabled: boolean)}
	<select class="sel" aria-label={label} {disabled} value={value ?? ''} onchange={(e) => onchange(e.currentTarget.value)}>
		{#if value === undefined}<option value="" disabled>Default style</option>{/if}
		{#if value !== undefined && !choices.some((c) => c.ref === value)}
			<option value={value} disabled>Not on this PC</option>
		{/if}
		{#each groups as g (g.group)}
			<optgroup label={GROUP_LABELS[g.group]}>
				{#each g.items as c (c.ref)}<option value={c.ref}>{optionLabel(c)}</option>{/each}
			</optgroup>
		{/each}
	</select>
{/snippet}

<div class="aps" aria-labelledby="h-apps">
	<div class="aps-h">
		<b id="h-apps">Style for each app</b>
		<span>Dictating into these apps starts in the style shown. You can still pick another on the bar.</span>
	</div>

	{#if rows.length === 0}
		<p class="note sm aps-empty"><Icon name="info" class="i sm" />No app has its own style yet. Every app starts in your default style ({defaultLabel}).</p>
	{/if}

	<ul class="aps-list" aria-label="Apps with their own style">
		{#each rows as row (row.app)}
			<li class="aps-row" aria-label={row.name}>
				<div class="aps-t">
					<b>{row.name}</b>
					<span class="sub sm">{row.app}{row.source === 'lists' ? ' · from your word lists' : ''}</span>
					<span class="aps-sum" class:warn={row.status === 'missing'}>
						{#if row.status === 'missing'}<Icon name="warning-circle" weight="fill" class="i xs" />{/if}{row.summary}
					</span>
					{#if problem?.app === row.app}<span class="err">{problem.message}</span>{/if}
				</div>
				<div class="aps-c">
					{@render stylePicker(row.ref, `Style for ${row.name}`, (ref) => onStyle(row, ref), busy !== null)}
					<select
						class="sel"
						aria-label={`Level for ${row.name}`}
						disabled={busy !== null || !row.ref || row.ref === 'raw' || row.status === 'missing'}
						value={levelValue(row)}
						onchange={(e) => onLevel(row, e.currentTarget.value)}
					>
						{#each levelOptions() as o (o.value)}
							<option value={o.value}>{o.value === 'auto' && row.autoNote ? `Automatic (${row.autoNote})` : o.label}</option>
						{/each}
					</select>
					{#if row.removeLabel}
						<button
							class="btn text del sm"
							type="button"
							disabled={busy !== null}
							title={row.afterRemove}
							onclick={() => remove(row)}>{row.removeLabel}</button
						>
					{/if}
				</div>
			</li>
		{/each}
	</ul>

	{#if adding}
		<form class="aps-add" onsubmit={add} aria-label="Add an app">
			<label class="aps-f">
				<span>App</span>
				<select class="sel" aria-label="App" bind:value={addApp}>
					{#if recent.length > 0}
						<optgroup label="Recently dictated into">
							{#each recent as r (r.id)}<option value={r.id}>{r.name} ({r.id})</option>{/each}
						</optgroup>
					{/if}
					<option value={OTHER}>Other program…</option>
				</select>
			</label>
			{#if addApp === OTHER}
				<label class="aps-f">
					<span>{IS_MACOS ? 'Bundle id' : 'Program name'}</span>
					<input
						class="inp"
						aria-label={IS_MACOS ? 'Bundle id' : 'Program name'}
						placeholder={IS_MACOS ? 'com.example.app' : 'telegram.exe'}
						bind:value={typed}
						autocomplete="off"
						spellcheck="false"
					/>
				</label>
			{/if}
			<label class="aps-f">
				<span>Starts in</span>
				{@render stylePicker(addStyle, 'Style for the new app', (ref) => (addStyle = ref), false)}
			</label>
			<label class="aps-f">
				<span>Level</span>
				<select class="sel" aria-label="Level for the new app" bind:value={addLevel} disabled={addStyle === 'raw'}>
					{#each levelOptions() as o (o.value)}
						<option value={o.value}>{o.value === 'auto' && typeof addChoice?.ownLevel === 'number' ? `Automatic (the style’s own level)` : o.label}</option>
					{/each}
				</select>
			</label>
			<div class="aps-a">
				<button class="btn pri sm" type="submit" disabled={busy !== null}>Add</button>
				<button class="btn text sm" type="button" onclick={() => (adding = false)}>Cancel</button>
			</div>
			{#if addProblem}<span class="err">{addProblem}</span>{/if}
		</form>
	{:else}
		<div class="aps-more">
			<button class="btn sec sm" type="button" onclick={openAdd}><Icon name="plus" /><span>Add an app</span></button>
			{#if rows.length > 0}<span class="sub sm">Other apps start in your default style ({defaultLabel}).</span>{/if}
		</div>
	{/if}

	{#if notice}<p class="note sm"><Icon name="info" class="i sm" />{notice}</p>{/if}
</div>

{#if undo}
	<div class="vf toast" role="status">
		<Icon name="trash" weight="duotone" />
		<span class="tmsg"><b>{undo.name}’s style removed.</b> Undo within 8 seconds.</span>
		<button class="btn pri" type="button" disabled={busy !== null} onclick={undoRemove}><span>Undo</span></button>
		<span class="tcount num">{Math.ceil(undo.left / 1000)} s</span>
		<span class="tbar"><i style="width:{(undo.left / 8000) * 100}%"></i></span>
	</div>
{/if}

<style>
	.aps {
		border-top: 1px solid var(--border);
		padding: 10px 0 4px;
	}
	.aps-h {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.aps-h span {
		color: var(--subtle-foreground);
		font-size: 13px;
	}
	.aps-empty {
		margin: 8px 0 4px;
	}
	.aps-list {
		list-style: none;
		margin: 6px 0 0;
		padding: 0;
	}
	.aps-row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px 12px;
		padding: 10px 0;
		border-top: 1px solid var(--border);
	}
	.aps-row:first-child {
		border-top: 0;
	}
	.aps-t {
		flex: 1 1 180px;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.aps-t .sm {
		font-size: 12.5px;
	}
	.aps-sum {
		font-size: 13px;
		color: var(--muted-foreground);
		display: inline-flex;
		gap: 6px;
		align-items: center;
	}
	.aps-sum.warn {
		color: var(--warning);
	}
	.aps-c {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		align-items: center;
	}
	.aps-c .sel {
		max-width: 200px;
	}
	.aps-add {
		display: flex;
		flex-wrap: wrap;
		gap: 10px 12px;
		align-items: flex-end;
		padding: 10px 0 6px;
		border-top: 1px solid var(--border);
	}
	.aps-f {
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: 12.5px;
		color: var(--subtle-foreground);
	}
	.aps-f .inp {
		width: 180px;
		height: 36px;
	}
	.aps-f .sel,
	.aps-f .inp {
		font-size: 14px;
		color: var(--foreground);
		max-width: 260px;
	}
	.aps-a {
		display: flex;
		gap: 6px;
	}
	.aps-add .err {
		flex-basis: 100%;
		margin-top: 0;
	}
	.aps-more {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 12px;
		padding-top: 8px;
	}
</style>
