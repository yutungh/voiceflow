<script lang="ts">
	import { onMount } from 'svelte';
	import { activeCorrections, type CorrectionOp } from '$lib/profile/ops';
	import { loadOps, removeOp, saveTerm } from '$lib/profile/op-store';
	import { wordLists } from '$lib/profile/service.svelte';
	import type {
		ProfileCorrection,
		ProfileLayer,
		ProfileTerm,
	} from '$lib/profile/types';
	import Icon from '$lib/vf/Icon.svelte';
	import Illo from '$lib/vf/Illo.svelte';
	import SpellingForm from '$lib/vf/SpellingForm.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import { syncSummary } from '$lib/vf/word-lists';

	/**
	 * VoiceFlow B3.1 Vocabulary (plan v2, B + C2): the words VoiceFlow
	 * listens for and the spellings it always fixes, from every layer of
	 * the word lists, each marked with where it comes from. "Add a word" and
	 * "Fix a spelling" save an edit to the person's own list; Undo removes
	 * an edit made here (a tombstone, so it can be undone again). Words from
	 * the household list or from notes are changed there, or by asking the
	 * assistant.
	 */

	const LAYER_LABEL: Record<ProfileLayer, string> = {
		default: 'Built in',
		household: 'Household',
		generated: 'From notes',
		personal: 'Mine',
		edit: 'Mine',
	};
	const LAYER_HELP: Record<ProfileLayer, string> = {
		default: 'Comes with VoiceLnq',
		household: 'Shared by everyone at home. Change it in the household word list.',
		generated: 'Made from your notes. Ask your assistant to change it.',
		personal: 'In your word-list file. Change it there, or ask your assistant.',
		edit: 'Added in VoiceLnq',
	};

	const snapshot = $derived(wordLists.snapshot);
	const config = $derived(wordLists.state?.config);
	const status = $derived(wordLists.state?.status);
	const assistant = $derived(config?.assistant?.trim() || 'your assistant');
	const summary = $derived(syncSummary(wordLists.state));

	let query = $state('');
	const match = (...texts: (string | undefined)[]) => {
		const q = query.trim().toLocaleLowerCase();
		return !q || texts.some((t) => t?.toLocaleLowerCase().includes(q));
	};

	const terms = $derived(
		(snapshot?.terms ?? [])
			.filter((t) => match(t.text, ...t.heardAs, t.gloss))
			.sort((a, b) => a.text.localeCompare(b.text, undefined, { sensitivity: 'base' })),
	);
	const fromNotes = $derived((snapshot?.terms ?? []).some((t) => t.layer === 'generated'));

	// Without word lists: the spellings saved on this PC (C1).
	let localCorrections = $state<CorrectionOp[]>([]);
	async function loadLocal() {
		localCorrections = activeCorrections(await loadOps());
	}
	onMount(() => {
		void loadLocal();
	});

	const corrections = $derived<ProfileCorrection[]>(
		(snapshot
			? snapshot.corrections
			: localCorrections.map((c) => ({
					id: c.id,
					from: c.from,
					to: c.to,
					scope: c.scope,
					layer: 'edit' as const,
				}))
		).filter((c) => match(c.to, ...c.from)),
	);

	const where = (apps: string[]) =>
		apps.includes('*') ? 'Every app' : apps.map((a) => a.replace(/\.exe$/, '')).join(', ');

	// ---- add a word / fix a spelling ---------------------------------------------
	let adding = $state<'word' | 'spelling' | null>(null);
	let newWord = $state('');
	let newHeard = $state('');
	let addError = $state<string | null>(null);
	let saving = $state(false);

	async function addWord(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		addError = null;
		const result = await saveTerm({
			text: newWord,
			heardAs: newHeard.split(',').map((h) => h.trim()).filter(Boolean),
		});
		saving = false;
		if (!result.ok) {
			addError = result.message;
			return;
		}
		adding = null;
		const id = result.op.id;
		const text = result.op.term.text;
		newWord = '';
		newHeard = '';
		show(`“${text}” added. VoiceLnq listens for it from your next dictation.`, id);
	}

	// ---- undo -------------------------------------------------------------------------
	let notice = $state<{ text: string; undo?: () => Promise<void> } | null>(null);
	let noticeTimer: ReturnType<typeof setTimeout> | undefined;
	function show(text: string, editId?: string) {
		clearTimeout(noticeTimer);
		notice = {
			text,
			undo: editId
				? async () => {
						await removeOp(editId);
						await loadLocal();
						show('Removed.');
					}
				: undefined,
		};
		noticeTimer = setTimeout(() => (notice = null), 8000);
	}

	async function remove(id: string, label: string) {
		const tombstone = await removeOp(id);
		await loadLocal();
		clearTimeout(noticeTimer);
		notice = {
			text: `${label} removed.`,
			// Undoing the removal removes the tombstone, which brings it back.
			undo: async () => {
				await removeOp(tombstone.id);
				await loadLocal();
				show('Brought back.');
			},
		};
		noticeTimer = setTimeout(() => (notice = null), 8000);
	}

	let refreshing = $state(false);
	async function refresh() {
		refreshing = true;
		try {
			await wordLists.refresh();
		} finally {
			setTimeout(() => (refreshing = false), 600);
		}
	}
</script>

<svelte:head>
	<title>Vocabulary - VoiceLnq</title>
</svelte:head>

{#snippet badge(layer: ProfileLayer)}
	<span class="chip lay" class:mine={layer === 'edit' || layer === 'personal'} title={LAYER_HELP[layer]}
		>{LAYER_LABEL[layer]}</span
	>
{/snippet}

<div class="vf page">
	<header class="ph">
		<div>
			<h1>Vocabulary</h1>
			<p>Words VoiceLnq listens for, and how it spells them</p>
		</div>
		<div class="ph-r">
			{#if snapshot}
				<button class="btn sec" type="button" onclick={() => ((adding = 'word'), (addError = null))}>
					<Icon name="plus" /><span>Add a word</span>
				</button>
			{/if}
			<button class="btn sec" type="button" onclick={() => (adding = 'spelling')}>
				<Icon name="pencil-simple" /><span>Fix a spelling</span>
			</button>
		</div>
	</header>

	<div class="managed" aria-live="polite">
		<Tile icon={summary.tone === 'ok' ? 'check-circle' : summary.tone === 'off' ? 'book-open-text' : 'warning-circle'} />
		<div>
			<b>{summary.title}</b>
			<span>{summary.detail}</span>
		</div>
		{#if config?.enabled}
			<button class="btn text" type="button" onclick={refresh} disabled={refreshing || status?.refreshing}>
				<Icon name="arrows-clockwise" /><span>{refreshing || status?.refreshing ? 'Checking…' : 'Refresh now'}</span>
			</button>
		{:else}
			<a class="btn text" href="/settings#word-lists">Set up</a>
		{/if}
	</div>

	{#if fromNotes}
		<div class="managed">
			<Tile icon="lock-simple" />
			<div>
				<b>Some words are managed in your notes</b>
				<span>Words marked <b>From notes</b> come from your notes · ask your assistant, {assistant}, to change them.</span>
			</div>
		</div>
	{/if}

	{#if adding === 'word'}
		<form class="pop add" aria-label="Add a word" onsubmit={addWord}>
			<div class="pop-h"><Tile icon="text-aa" /><b>Add a word</b></div>
			<label for="vw-word">Word</label>
			<!-- svelte-ignore a11y_autofocus -->
			<input id="vw-word" class="inp" bind:value={newWord} autocomplete="off" required autofocus />
			<label for="vw-heard">Often heard as <span class="sub">(optional, separate with commas)</span></label>
			<input id="vw-heard" class="inp" bind:value={newHeard} autocomplete="off" />
			{#if addError}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{addError}</p>
			{/if}
			<p class="note">
				<Icon name="info" class="i sm" />Saved to your own word list. It helps VoiceLnq hear the word; to always
				rewrite a wrong spelling, use Fix a spelling.
			</p>
			<div class="acts">
				<button class="btn pri" type="submit" disabled={saving}>Add word</button>
				<button class="btn text" type="button" onclick={() => (adding = null)}>Cancel</button>
			</div>
		</form>
	{:else if adding === 'spelling'}
		<div class="add">
			<SpellingForm
				onCancel={() => (adding = null)}
				onSaved={async (saved) => {
					adding = null;
					await loadLocal();
					show(`Spelling saved. “${saved.to}” is used from your next dictation.`, saved.id);
				}}
			/>
		</div>
	{/if}

	<div class="tabs">
		<span class="sp"></span>
		<label class="search sm">
			<Icon name="magnifying-glass" class="i sm" />
			<input class="q" type="search" placeholder="Filter words" bind:value={query} aria-label="Filter words" />
		</label>
	</div>

	<div class="vg">
		<div class="card flush">
			<div class="c-h hpad"><Tile icon="text-aa" /><h2>Words</h2><span class="cnt">{snapshot?.terms.length ?? 0}</span></div>
			{#if !snapshot}
				<div class="es pad">
					<Illo name="wave" size={96} />
					<b>Words VoiceLnq should always spell your way</b>
					<p>Connect your word lists in Settings to see and add words here.</p>
					<a class="btn pri" href="/settings#word-lists">Set up word lists</a>
				</div>
			{:else if terms.length === 0}
				<p class="sub empty">{query ? 'No word matches.' : 'No words yet. Add one, or ask your assistant.'}</p>
			{:else}
				<table class="t vt">
					<thead><tr><th>Word</th><th>Often heard as</th><th>Where</th><th>From</th><th><span class="sr-only">Actions</span></th></tr></thead>
					<tbody>
						{#each terms as term (term.id)}
							<tr>
								<td><b>{term.text}</b>{#if term.gloss}<span class="sub gl">{term.gloss}</span>{/if}</td>
								<td class="sub">{term.heardAs.join(', ') || '—'}</td>
								<td class="sub">{where(term.apps)}</td>
								<td>{@render badge(term.layer)}</td>
								<td class="ra">
									{#if term.layer === 'edit'}
										<button class="btn text sm" type="button" onclick={() => remove(term.id, `“${term.text}”`)}>
											<Icon name="arrow-counter-clockwise" /><span>Undo</span>
										</button>
									{/if}
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			{/if}
		</div>

		<div class="col">
			<section class="card">
				<div class="c-h"><Tile icon="arrows-clockwise" /><h2>Corrections</h2><span class="cnt">{corrections.length}</span></div>
				<p class="note">Always rewritten, once, in the apps shown.</p>
				{#if corrections.length === 0}
					<p class="sub">{query ? 'No spelling matches.' : 'No spellings yet. Use Fix a spelling.'}</p>
				{:else}
					<ul class="corr">
						{#each corrections as c (c.id)}
							<li>
								<s>{c.from.join(', ')}</s><Icon name="arrow-right" class="i sm sub" /><b>{c.to}</b>
								<span class="sub sm wh">{where(c.scope.apps)}</span>
								{@render badge(c.layer)}
								{#if c.layer === 'edit'}
									<button class="btn text sm" type="button" onclick={() => remove(c.id, `“${c.to}”`)} aria-label={`Undo ${c.to}`}>
										<Icon name="arrow-counter-clockwise" />
									</button>
								{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>

			{#if snapshot && snapshot.issues.length > 0}
				<section class="card">
					<div class="c-h"><Tile icon="warning-circle" tone="warn" /><h2>Needs a look</h2><span class="cnt">{snapshot.issues.length}</span></div>
					<ul class="issues">
						{#each snapshot.issues as issue, i (i)}
							<li>{issue.message}</li>
						{/each}
					</ul>
					<p class="note sm">
						<Icon name="info" class="i sm" />VoiceLnq keeps working; it uses the higher list where two disagree.
						Ask {assistant} to tidy these up.
					</p>
				</section>
			{/if}
		</div>
	</div>

	{#if notice}
		<div class="toast" role="status">
			<Icon name="check-circle" weight="duotone" />
			<span class="tmsg">{notice.text}</span>
			{#if notice.undo}
				<button class="btn pri" type="button" onclick={() => notice?.undo?.()}><span>Undo</span></button>
			{/if}
		</div>
	{/if}
</div>

<style>
	.vf .add {
		position: relative;
		max-width: 520px;
		margin-bottom: 14px;
	}
	.vf .add :global(.pop),
	.vf form.pop.add {
		position: static;
	}
	.vf .hpad {
		padding: 14px 14px 0;
	}
	.vf .search .q {
		border: 0;
		outline: 0;
		background: transparent;
		color: var(--foreground);
		flex: 1;
		min-width: 0;
		font: inherit;
	}
	.vf .lay {
		font-size: 11px;
		padding: 1px 8px;
	}
	.vf .lay.mine {
		color: var(--primary);
	}
	.vf .gl {
		display: block;
		font-size: 12px;
	}
	.vf .ra {
		text-align: right;
		width: 1%;
		white-space: nowrap;
	}
	.vf .corr .wh {
		margin-left: auto;
	}
	.vf .empty {
		padding: 14px;
	}
	.vf .issues {
		list-style: disc;
		padding-left: 18px;
		font-size: 13px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	@media (max-width: 900px) {
		.vf .vg {
			grid-template-columns: minmax(0, 1fr);
		}
		/* The filter takes the row; the spacer's padding would push it out. */
		.vf .tabs .sp {
			display: none;
		}
		.vf .tabs .search.sm {
			flex: 1 1 auto;
			min-width: 0;
		}
	}
</style>
