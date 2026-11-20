<script lang="ts">
	import { createQuery } from '@tanstack/svelte-query';
	import { Switch } from '@epicenter/ui/switch';
	import { nanoid } from 'nanoid/non-secure';
	import { bar } from '$lib/bar/controller';
	import { RAW_STYLE_ID } from '$lib/bar/contract';
	import { rpc } from '$lib/query';
	import type { Transformation } from '$lib/services/isomorphic/db';
	import { settings } from '$lib/stores/settings.svelte';
	import { profileStyleKey } from '$lib/profile/ownership';
	import { wordLists } from '$lib/profile/service.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import Illo from '$lib/vf/Illo.svelte';
	import Kc from '$lib/vf/Kc.svelte';
	import StyleEditor from '$lib/vf/StyleEditor.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import { createdAt, isDeleted } from '$lib/vf/dictations';
	import {
		fixedSpellingsOf,
		newStyle,
		styleShape,
		styleSummary,
	} from '$lib/vf/styles';
	import {
		BUILT_IN_STYLES,
		FUN_STYLES,
		PACK_BUILT_INS,
		builtInStyle,
		isEquivalentName,
		styleNames,
		type BuiltInStyle,
	} from '$lib/vf/builtin-styles';
	import { MAX_PINS, barPinsOf, canPinMore, inPageOrder, withBarPin } from '$lib/vf/style-pins';

	const stylesQuery = createQuery(() => rpc.db.transformations.getAll.options);
	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);

	let hiddenId = $state<string | null>(null);
	// 0.9.9: a fixed order (oldest first), the same one the bar uses.
	const styles = $derived(
		inPageOrder((stylesQuery.data ?? []).filter((s) => s.id !== hiddenId)),
	);
	const selectedId = $derived(
		settings.value['transformations.selectedTransformationId'],
	);

	/** The latest dictation each style tidied: its live example. */
	const examples = $derived.by(() => {
		const out = new Map<string, { said: string; wrote: string }>();
		const sorted = [...(recordingsQuery.data ?? [])]
			.filter((r) => !isDeleted(r) && r.styleName && r.finalText && r.transcribedText)
			.sort((a, b) => createdAt(b).getTime() - createdAt(a).getTime());
		for (const r of sorted) {
			const key = r.styleName as string;
			if (!out.has(key)) out.set(key, { said: r.transcribedText, wrote: r.finalText as string });
		}
		return out;
	});

	const SAMPLE = 'running ten minutes late save me a seat the demo went well';
	let tries = $state<Record<string, { busy: boolean; text?: string; error?: string }>>({});

	async function tryStyle(style: Transformation) {
		tries[style.id] = { busy: true };
		const { data, error } = await rpc.transformer.transformInput.execute({
			input: SAMPLE,
			transformation: style,
		});
		tries[style.id] = error
			? { busy: false, error: error.description || error.title }
			: { busy: false, text: data };
	}

	/**
	 * VoiceFlow B + C2: styles from the word lists. What each one does is
	 * set in the lists; which one is the default stays on this PC unless the
	 * lists set that too. Without word lists, only this PC's styles show.
	 */
	const profileStyles = $derived(
		// 0.9.13: a hidden word-list style is not offered (it still works as a default).
		(wordLists.snapshot?.styles ?? []).filter((s) => s.hidden !== true),
	);
	const defaultOwned = $derived(
		settings.isProfileOwned('transformations.selectedTransformationId'),
	);
	const LAYER = { default: 'Built in', household: 'Household', generated: 'From notes', personal: 'Mine', edit: 'Mine' } as const;

	function useStyle(id: string | null) {
		settings.updateKey('transformations.selectedTransformationId', id);
	}

	// ---- "Show on the bar" (0.9.9) -------------------------------------------
	// This PC's styles keep the pin in their own file; word-list styles (read
	// only here) and Raw keep it in a setting on this PC.
	const settingPins = $derived(barPinsOf(settings.value['bar.pinnedStyles']));
	// Counted over every style, a removed one too while its Undo is open, so
	// an Undo can never make six.
	const allStyles = $derived(stylesQuery.data ?? []);
	const pinnedIds = $derived([
		...allStyles.filter((s) => s.showOnBar === true).map((s) => s.id),
		...settingPins,
	]);
	const existingIds = $derived(
		new Set([...allStyles.map((s) => s.id), ...profileStyles.map((p) => profileStyleKey(p.id)), RAW_STYLE_ID]),
	);
	const pinCount = $derived(pinnedIds.filter((id) => existingIds.has(id)).length);
	const canPin = $derived(canPinMore(pinnedIds, existingIds));
	let pinBusy = $state<string | null>(null);

	async function setPin(barId: string, on: boolean, style?: Transformation) {
		// One change at a time, each checked against the count it will change.
		if (pinBusy || (on && !canPin)) return;
		pinBusy = barId;
		try {
			if (style) {
				const { showOnBar: _was, ...rest } = style;
				await rpc.db.transformations.update.execute(on ? { ...rest, showOnBar: true } : rest);
			} else {
				settings.updateKey('bar.pinnedStyles', withBarPin(settings.value['bar.pinnedStyles'], barId, on));
			}
			await bar.reloadStyles();
		} finally {
			pinBusy = null;
		}
	}

	// The bar reads the styles again after any change here (added, edited, removed).
	$effect(() => {
		void stylesQuery.data;
		void bar.reloadStyles();
	});

	// ---- "Add a style": the built-in templates (0.9.9; fun ones 0.9.10) -------
	// The same names the seeding goes by, word-list presets included.
	const titles = $derived(styleNames(styles.map((s) => s.title), profileStyles));
	const hasOneLike = (template: BuiltInStyle) => titles.some((t) => isEquivalentName(template, t));
	const TEMPLATE_GROUPS = [
		// 0.9.13: Clean (from the pack) first.
		{ label: null, templates: [...PACK_BUILT_INS, ...BUILT_IN_STYLES] },
		{ label: 'Just for fun', templates: FUN_STYLES },
	] as const;
	function addTemplate(template: BuiltInStyle) {
		edit(builtInStyle(template, { id: nanoid() }), true);
	}

	// ---- edit ---------------------------------------------------------------
	let editing = $state<{ style: Transformation; isNew: boolean } | null>(null);
	let editorOpen = $state(false);
	function edit(style: Transformation, isNew = false) {
		editing = { style, isNew };
		editorOpen = true;
	}

	// ---- remove with undo -----------------------------------------------------
	let undo = $state<{ style: Transformation; wasSelected: boolean; left: number } | null>(null);
	let undoTimer: ReturnType<typeof setInterval> | undefined;

	function remove(style: Transformation) {
		finishRemove();
		const wasSelected = selectedId === style.id;
		hiddenId = style.id;
		if (wasSelected) useStyle(null);
		undo = { style, wasSelected, left: 8000 };
		const until = Date.now() + 8000;
		undoTimer = setInterval(() => {
			if (!undo) return clearInterval(undoTimer);
			undo.left = Math.max(0, until - Date.now());
			if (undo.left === 0) finishRemove();
		}, 100);
	}

	function finishRemove() {
		clearInterval(undoTimer);
		if (!undo) return;
		const style = undo.style;
		undo = null;
		rpc.db.transformations.delete.execute(style).then(() => {
			if (hiddenId === style.id) hiddenId = null;
		});
	}

	function undoRemove() {
		clearInterval(undoTimer);
		if (!undo) return;
		if (undo.wasSelected) useStyle(undo.style.id);
		hiddenId = null;
		undo = null;
	}

	function onKey(e: KeyboardEvent) {
		if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && undo) {
			e.preventDefault();
			undoRemove();
		}
	}
</script>

<svelte:head>
	<title>Styles - VoiceFlow</title>
</svelte:head>

{#snippet pinRow(barId: string, on: boolean, style?: Transformation)}
	<label class="pin" class:off={!on && !canPin} title={!on && !canPin ? `Up to ${MAX_PINS} styles fit on the bar` : undefined}>
		<Switch
			checked={on}
			disabled={pinBusy !== null || (!on && !canPin)}
			onCheckedChange={(v) => setPin(barId, v, style)}
			aria-label="Show on the bar"
		/>
		<span>Show on the bar</span>
	</label>
{/snippet}

<svelte:window onkeydown={onKey} onbeforeunload={finishRemove} />

<div class="vf page">
	<header class="ph">
		<div>
			<h1>Styles</h1>
			<p>How VoiceFlow tidies your words</p>
		</div>
		<div class="ph-r">
			<button class="btn sec" type="button" onclick={() => edit(newStyle(), true)}>
				<Icon name="plus" /><span>New style</span>
			</button>
		</div>
	</header>

	{#if stylesQuery.isPending}
		<section class="card"><div class="skel">{#each [62, 48] as w}<div class="sk-row"><i class="sk c"></i><i class="sk b" style="width:{w}%"></i></div>{/each}</div></section>
	{:else}
		<div class="stg">
			{#each profileStyles as pstyle (pstyle.id)}
				{@const key = profileStyleKey(pstyle.id)}
				{@const isDefault = selectedId === key}
				<section class="card sty" class:focus={isDefault} aria-label={pstyle.label}>
					<div class="c-h">
						<Tile icon="magic-wand" />
						<div class="sty-t">
							<h2>{pstyle.label}</h2>
							<span class="sub sm">{pstyle.prompt.replace(/\s+/g, ' ').slice(0, 70)}{pstyle.prompt.length > 70 ? '…' : ''}</span>
						</div>
						{#if isDefault}
							<span class="chip ok"><Icon name="check-circle" weight="fill" class="i xs" />In use</span>
						{:else if !defaultOwned}
							<button class="btn sec sm" type="button" onclick={() => useStyle(key)}>Use this style</button>
						{/if}
					</div>
					<p class="note sm">
						<Icon name="lock-simple" class="i sm" />From your word lists ({LAYER[pstyle.layer]}){pstyle.model ? ` · ${pstyle.model}` : ''}.
						Change it there, or ask your assistant.
					</p>
					{@render pinRow(key, settingPins.includes(key))}
				</section>
			{/each}
			{#each styles as style (style.id)}
				{@const shape = styleShape(style)}
				{@const example = examples.get(style.title)}
				{@const trial = tries[style.id]}
				{@const fixes = fixedSpellingsOf(style)}
				{@const isDefault = selectedId === style.id}
				<section class="card sty" class:focus={isDefault} aria-label={style.title || 'Untitled style'}>
					<div class="c-h">
						<Tile icon="magic-wand" />
						<div class="sty-t">
							<h2>{style.title || 'Untitled style'}</h2>
							<span class="sub sm">{styleSummary(style)}</span>
						</div>
						{#if isDefault}
							<span class="chip ok"><Icon name="check-circle" weight="fill" class="i xs" />In use</span>
						{:else}
							<button class="btn sec sm" type="button" onclick={() => useStyle(style.id)}>Use this style</button>
						{/if}
					</div>

					{#if shape.kind === 'complex'}
						<p class="note">
							<Icon name="info" class="i sm" />Made with {shape.prompts} steps in an earlier version. You can
							use it or remove it here; editing it comes in a later update.
						</p>
					{:else if example}
						<p class="said"><span class="lab">You said</span>“{example.said}”</p>
						<p class="out"><span class="lab">{style.title} wrote</span>{example.wrote}</p>
					{:else if trial?.text}
						<p class="said"><span class="lab">You say</span>“{SAMPLE}”</p>
						<p class="out"><span class="lab">{style.title} writes</span>{trial.text}</p>
					{:else if shape.kind === 'simple'}
						<p class="said"><span class="lab">You say</span>“{SAMPLE}”</p>
						<p class="out muted">
							{#if trial?.busy}
								<span class="sub">Trying it…</span>
							{:else if trial?.error}
								<span class="err"><Icon name="warning-circle" weight="fill" class="i sm" />{trial.error}</span>
							{:else}
								<span class="sub">No dictation with this style yet.</span>
								<button class="btn text sm" type="button" onclick={() => tryStyle(style)}>
									<Icon name="play" /><span>Try it on this sentence</span>
								</button>
							{/if}
						</p>
					{/if}

					{#if fixes > 0}
						<p class="note sm"><Icon name="check" class="i sm" />Also fixes {fixes} {fixes === 1 ? 'spelling' : 'spellings'} saved earlier.</p>
					{/if}

					<div class="rowacts">
						{#if shape.kind !== 'complex'}
							<button class="btn text" type="button" onclick={() => edit(style)}>
								<Icon name="pencil-simple" /><span>Edit</span>
							</button>
						{/if}
						<button class="btn text del" type="button" onclick={() => remove(style)}>
							<Icon name="trash" /><span>Remove</span>
						</button>
						{@render pinRow(style.id, style.showOnBar === true, style)}
					</div>
				</section>
			{/each}

			<section class="card sty" class:focus={!selectedId} aria-label="Raw">
				<div class="c-h">
					<Tile icon="cursor-text" />
					<div class="sty-t">
						<h2>Raw</h2>
						<span class="sub sm">No tidy-up; saved spellings still apply</span>
					</div>
					{#if !selectedId}
						<span class="chip ok"><Icon name="check-circle" weight="fill" class="i xs" />In use</span>
					{:else}
						<button class="btn sec sm" type="button" onclick={() => useStyle(null)}>Use this style</button>
					{/if}
				</div>
				<p class="said"><span class="lab">You say</span>“{SAMPLE}”</p>
				<p class="out"><span class="lab">Raw writes</span>{SAMPLE}</p>
				<div class="rowacts">{@render pinRow(RAW_STYLE_ID, settingPins.includes(RAW_STYLE_ID))}</div>
			</section>
		</div>

		<p class="note ctr pinnote">
			<Icon name="sliders-horizontal" class="i sm" />{#if pinCount > 0}{pinCount} of {MAX_PINS} styles are on the recording
				bar, in this order.{:else}Choose up to {MAX_PINS} styles for the recording bar. Until you do, it shows
				the ones you used most in the last 30 days.{/if} The rest are under More.
		</p>

		<section class="card addsty" aria-labelledby="add-style">
			<div class="c-h">
				<Tile icon="plus" />
				<div class="sty-t">
					<h2 id="add-style">Add a style</h2>
					<span class="sub sm">Ready-made styles you can add and change</span>
				</div>
			</div>
			{#each TEMPLATE_GROUPS as group (group.label)}
				{#if group.label}
					<h3 class="tpl-g">{group.label}</h3>
				{/if}
				<ul class="tpl" aria-label={group.label ?? 'Everyday styles'}>
					{#each group.templates as template (template.key)}
						{@const has = hasOneLike(template)}
						<li>
							<div class="tpl-t">
								<b>{template.title}</b>
								<span class="sub sm">{template.description}</span>
							</div>
							{#if has}
								<span class="chip"><Icon name="check" class="i xs" />Added</span>
							{/if}
							<button class="btn sec sm" type="button" onclick={() => addTemplate(template)}>
								<Icon name="plus" /><span>{has ? 'Add a copy' : 'Add'}</span>
							</button>
						</li>
					{/each}
				</ul>
			{/each}
		</section>

		{#if styles.length === 0}
			<section class="card es-card">
				<div class="es">
					<Illo name="wave" size={96} />
					<b>No styles yet</b>
					<p>A style tells VoiceFlow how to tidy your words. Until you make one, your words are pasted as heard.</p>
					<button class="btn pri" type="button" onclick={() => edit(newStyle(), true)}>
						<Icon name="plus" /><span>Make a style</span>
					</button>
				</div>
			</section>
		{/if}

		<p class="note ctr">
			<Icon name="info" class="i sm" />Applies to new dictations only. One style is used in every app for
			now; choosing a style per app comes later.
		</p>
	{/if}

	{#if editing}
		{#key editing.style.id}
			<StyleEditor style={editing.style} isNew={editing.isNew} bind:open={editorOpen} />
		{/key}
	{/if}

	{#if undo}
		<div class="toast" role="status">
			<Icon name="trash" weight="duotone" />
			<span class="tmsg"><b>Style removed.</b> Undo within 8 seconds.</span>
			<button class="btn pri" type="button" onclick={undoRemove}><span>Undo</span><Kc keys={['Ctrl', 'Z']} /></button>
			<span class="tcount num">{Math.ceil(undo.left / 1000)} s</span>
			<span class="tbar"><i style="width:{(undo.left / 8000) * 100}%"></i></span>
		</div>
	{/if}
</div>

<style>
	/* 0.9.9: "Show on the bar" and the "Add a style" list. */
	.pin {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		margin-left: auto;
		padding: 0 4px;
		font-size: 13px;
		color: var(--muted-foreground);
		cursor: pointer;
	}
	.pin.off {
		opacity: 0.6;
		cursor: default;
	}
	.pinnote {
		margin-top: 4px;
	}
	.addsty {
		margin-top: 16px;
	}
	.tpl {
		list-style: none;
		margin: 8px 0 0;
		padding: 0;
		display: grid;
		gap: 4px;
	}
	.tpl li {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 8px 4px;
		border-top: 1px solid var(--border);
	}
	.tpl li:first-child {
		border-top: 0;
	}
	.tpl-g {
		margin: 16px 4px 0;
		font-size: 12px;
		font-weight: 600;
		letter-spacing: 0.02em;
		text-transform: uppercase;
		color: var(--muted-foreground);
	}
	.tpl-t {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
		flex: 1 1 auto;
	}
</style>
