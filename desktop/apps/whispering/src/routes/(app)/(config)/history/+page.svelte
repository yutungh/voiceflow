<script lang="ts">
	import * as DropdownMenu from '@epicenter/ui/dropdown-menu';
	import { createQuery } from '@tanstack/svelte-query';
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { appIdOf } from '$lib/privacy/history';
	import { removeOp } from '$lib/profile/op-store';
	import { rpc } from '$lib/query';
	import { recovery } from '$lib/query/isomorphic/actions';
	import { updateRecordingFields } from '$lib/query/isomorphic/outcome';
	import type { Recording } from '$lib/services/isomorphic/db';
	import AppIcon from '$lib/vf/AppIcon.svelte';
	// VoiceFlow phase F: feedback on a dictation, and its fix.
	import FeedbackFix from '$lib/vf/FeedbackFix.svelte';
	import { feedbackService } from '$lib/feedback/service.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import Illo from '$lib/vf/Illo.svelte';
	import Kc from '$lib/vf/Kc.svelte';
	import ProblemCard from '$lib/vf/ProblemCard.svelte';
	import SpellingForm from '$lib/vf/SpellingForm.svelte';
	import Wave from '$lib/vf/Wave.svelte';
	import { createCopyState } from '$lib/vf/copy.svelte';
	import { driftReasonText } from '$lib/vf/drift-check';
	import {
		createdAt,
		dayLabel,
		DELETED_KEEP_DAYS,
		finalTextOf,
		formatLength,
		formatTime,
		hasProblem,
		isNothingHeard,
		isDeleted,
		problemOf,
		resultOf,
		whenLabel,
		type DictationResult,
	} from '$lib/vf/dictations';
	import { player } from '$lib/vf/player.svelte';

	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);
	const all = $derived(
		[...(recordingsQuery.data ?? [])].sort(
			(a, b) => createdAt(b).getTime() - createdAt(a).getTime(),
		),
	);

	// ---- filters ------------------------------------------------------------
	type TimeFilter = 'any' | 'today' | 'week' | 'month';
	const TIME_LABELS: Record<TimeFilter, string> = {
		any: 'Any time',
		today: 'Today',
		week: 'Last 7 days',
		month: 'Last 30 days',
	};
	let search = $state('');
	let appFilter = $state<string | null>(null);
	let timeFilter = $state<TimeFilter>('any');
	let problemsOnly = $state(page.url.searchParams.get('filter') === 'problems');
	let deletedView = $state(page.url.searchParams.get('filter') === 'deleted');
	let selectedId = $state<string | null>(page.url.searchParams.get('id'));
	let searchInput = $state<HTMLInputElement | null>(null);

	const live = $derived(all.filter((r) => !isDeleted(r)));
	const deleted = $derived(all.filter(isDeleted));
	const problemCount = $derived(live.filter(hasProblem).length);

	const apps = $derived.by(() => {
		const byName = new Map<string, Recording>();
		for (const r of live) {
			const key = r.appName ?? 'Unknown app';
			if (!byName.has(key)) byName.set(key, r);
		}
		return [...byName.entries()].map(([name, sample]) => ({ name, sample }));
	});

	const filtered = $derived.by(() => {
		const q = search.trim().toLocaleLowerCase();
		const now = Date.now();
		const minTime =
			timeFilter === 'today'
				? new Date(new Date().toDateString()).getTime()
				: timeFilter === 'week'
					? now - 7 * 864e5
					: timeFilter === 'month'
						? now - 30 * 864e5
						: 0;
		return (deletedView ? deleted : live).filter((r) => {
			if (appFilter && (r.appName ?? 'Unknown app') !== appFilter) return false;
			if (problemsOnly && !hasProblem(r)) return false;
			if (minTime && createdAt(r).getTime() < minTime) return false;
			if (q) {
				const hay = `${finalTextOf(r)} ${r.transcribedText} ${r.appName ?? ''}`.toLocaleLowerCase();
				if (!hay.includes(q)) return false;
			}
			return true;
		});
	});

	const selected = $derived(
		filtered.find((r) => r.id === selectedId) ??
			(selectedId ? all.find((r) => r.id === selectedId) : undefined),
	);

	// ---- virtual list -------------------------------------------------------
	const ROW = 44;
	const GROUP = 30;
	type Item =
		| { kind: 'group'; label: string; top: number }
		| { kind: 'row'; r: Recording; top: number; index: number };
	const items = $derived.by(() => {
		const out: Item[] = [];
		let top = 0;
		let last = '';
		filtered.forEach((r, index) => {
			const label = dayLabel(createdAt(r));
			if (label !== last) {
				out.push({ kind: 'group', label, top });
				top += GROUP;
				last = label;
			}
			out.push({ kind: 'row', r, top, index });
			top += ROW;
		});
		return { list: out, height: top };
	});
	let scroller = $state<HTMLDivElement | null>(null);
	let scrollTop = $state(0);
	let viewHeight = $state(600);
	const visible = $derived.by(() => {
		const from = scrollTop - 300;
		const to = scrollTop + viewHeight + 300;
		return items.list.filter((it) => it.top + ROW >= from && it.top <= to);
	});

	async function select(id: string, reveal = false) {
		selectedId = id;
		closePops();
		if (!reveal || !scroller) return;
		await tick();
		const item = items.list.find((it) => it.kind === 'row' && it.r.id === id);
		if (!item || !scroller) return;
		if (item.top < scroller.scrollTop) scroller.scrollTop = item.top - GROUP;
		else if (item.top + ROW > scroller.scrollTop + scroller.clientHeight)
			scroller.scrollTop = item.top + ROW - scroller.clientHeight;
	}

	function move(delta: number) {
		if (filtered.length === 0) return;
		const at = filtered.findIndex((r) => r.id === selectedId);
		const next = Math.min(filtered.length - 1, Math.max(0, at < 0 ? 0 : at + delta));
		const r = filtered[next];
		if (r) select(r.id, true);
	}

	onMount(() => {
		// Dictations deleted more than 30 days ago are removed for good.
		const cutoff = Date.now() - DELETED_KEEP_DAYS * 864e5;
		const expired = (recordingsQuery.data ?? []).filter(
			(r) => r.deletedAt && Date.parse(r.deletedAt) < cutoff,
		);
		if (expired.length) rpc.db.recordings.delete.execute(expired);
		if (selectedId) select(selectedId, true);
	});

	// ---- detail panel -------------------------------------------------------
	const runQuery = createQuery(() => ({
		...rpc.db.runs.getLatestByRecordingId(() => selected?.id ?? '').options,
		enabled: !!selected && !selected.finalText,
	}));
	const selectedText = $derived.by(() => {
		if (!selected) return '';
		if (selected.finalText) return selected.finalText.trim();
		const run = runQuery.data;
		if (run && run.status === 'completed' && run.output.trim()) return run.output.trim();
		return selected.transcribedText.trim();
	});
	const selectedProblem = $derived(
		// 0.9.9: a dismissed problem keeps its fixes here (the bar notice hides by itself).
		selected ? problemOf(selected, { includeDismissed: true }) : undefined,
	);
	const copy = createCopyState();
	// Feedback status per dictation (drafted / sent / fixed).
	onMount(() => void feedbackService.load());
	const FEEDBACK_STATUS = { drafted: 'Feedback drafted', sent: 'Feedback sent', fixed: 'Fixed from feedback' } as const;
	const selectedFeedback = $derived(selected ? feedbackService.statusFor(selected.id) : null);
	let pop = $state<'spelling' | 'feedback' | null>(null);
	let heardGuess = $state('');
	let notice = $state<{ text: string; undo?: () => void } | null>(null);
	let rerunning = $state(false);

	function closePops() {
		pop = null;
		notice = null;
	}

	function openSpelling() {
		const sel = window.getSelection()?.toString().trim() ?? '';
		heardGuess = sel.split(/\s+/).length <= 4 ? sel : '';
		pop = 'spelling';
	}

	async function rerun() {
		if (!selected) return;
		rerunning = true;
		const result = await recovery.rerunTidyUp(selected.id);
		rerunning = false;
		notice = { text: result.ok ? result.message : result.message };
	}

	// 0.9.13: Use cleaned (the tidy-up the language guard held back).
	let usingCleaned = $state(false);
	async function useCleaned() {
		if (!selected) return;
		usingCleaned = true;
		const result = await recovery.useCleaned(selected.id);
		usingCleaned = false;
		notice = { text: result.message };
	}

	// ---- delete with undo ---------------------------------------------------
	const UNDO_MS = 8000;
	let undo = $state<{ id: string; app: string; until: number } | null>(null);
	let undoLeft = $state(0);
	let undoTimer: ReturnType<typeof setInterval> | undefined;

	async function remove(r: Recording) {
		closePops();
		const at = filtered.findIndex((x) => x.id === r.id);
		await updateRecordingFields(r.id, { deletedAt: new Date().toISOString() });
		if (player.playingId === r.id) player.stop();
		const next = filtered[at + 1] ?? filtered[at - 1];
		selectedId = next && next.id !== r.id ? next.id : null;
		undo = { id: r.id, app: r.appName ?? 'Unknown app', until: Date.now() + UNDO_MS };
		undoLeft = UNDO_MS;
		clearInterval(undoTimer);
		undoTimer = setInterval(() => {
			if (!undo) return clearInterval(undoTimer);
			undoLeft = Math.max(0, undo.until - Date.now());
			if (undoLeft === 0) {
				undo = null;
				clearInterval(undoTimer);
			}
		}, 100);
	}

	async function undoDelete() {
		if (!undo) return;
		const id = undo.id;
		undo = null;
		clearInterval(undoTimer);
		await updateRecordingFields(id, { deletedAt: undefined });
		selectedId = id;
	}

	async function restore(r: Recording) {
		await updateRecordingFields(r.id, { deletedAt: undefined });
		notice = { text: 'Restored to History' };
	}

	// ---- keys ---------------------------------------------------------------
	function onKey(e: KeyboardEvent) {
		const el = e.target as HTMLElement | null;
		const typing =
			!!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName));
		if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && undo && !typing) {
			e.preventDefault();
			undoDelete();
			return;
		}
		if (e.key === 'Escape' && pop) {
			pop = null;
			return;
		}
		if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
		const key = e.key;
		if (key === '/') {
			e.preventDefault();
			searchInput?.focus();
		} else if (key === 'j' || key === 'ArrowDown') {
			e.preventDefault();
			move(1);
		} else if (key === 'k' || key === 'ArrowUp') {
			e.preventDefault();
			move(-1);
		} else if (key === ' ' && selected && !selected.audioRemovedAt) {
			e.preventDefault();
			player.toggle(selected.id);
		} else if (key === 'c' && selectedText) {
			e.preventDefault();
			copy.copy(selectedText);
		} else if (key === 'Delete' && selected && !isDeleted(selected)) {
			e.preventDefault();
			remove(selected);
		}
	}

	const RESULT: Record<DictationResult, { text: string; tone: string; icon: 'check-circle' | 'warning-circle' | 'pause' | 'x-circle' | 'copy' | 'clock-counter-clockwise' }> = {
		pasted: { text: 'Pasted', tone: 'ok', icon: 'check-circle' },
		copied: { text: 'Copied', tone: 'ok', icon: 'copy' },
		saved: { text: 'Saved', tone: 'ok', icon: 'check-circle' },
		'not-pasted': { text: 'Not pasted', tone: 'bad', icon: 'warning-circle' },
		held: { text: 'Held', tone: 'warn', icon: 'pause' },
		'no-text': { text: 'No text', tone: 'bad', icon: 'x-circle' },
		working: { text: 'Working', tone: 'ok', icon: 'clock-counter-clockwise' },
	};

	function rowText(r: Recording): { text: string; dim: boolean } {
		const result = resultOf(r);
		if (result === 'held') return { text: 'Audio kept on this PC, not sent', dim: true };
		// 0.9.12: no speech is said as such (its audio may be gone already).
		if (result === 'no-text' && isNothingHeard(r)) return { text: 'Nothing heard', dim: true };
		if (result === 'no-text') return { text: 'Audio saved on this PC', dim: true };
		const text = finalTextOf(r);
		return { text: text || '—', dim: result === 'not-pasted' };
	}
</script>

<svelte:head>
	<title>History - VoiceFlow</title>
</svelte:head>

<svelte:window onkeydown={onKey} />

<div class="vf page hpage">
	<header class="ph">
		<div>
			<h1>History</h1>
			<p>
				{#if live.length === 0}Kept on this PC{:else}{live.length.toLocaleString()}
					{live.length === 1 ? 'dictation' : 'dictations'}, kept on this PC{/if}
			</p>
		</div>
	</header>

	{#if recordingsQuery.isPending}
		<section class="card"><div class="skel">{#each [62, 48, 70, 55] as w}<div class="sk-row"><i class="sk c"></i><i class="sk a"></i><i class="sk b" style="width:{w}%"></i><i class="sk w"></i></div>{/each}<p class="sub">Loading your dictations…</p></div></section>
	{:else if all.length === 0}
		<section class="card es-card">
			<div class="es">
				<Illo name="history" size={104} />
				<b>No dictations yet</b>
				<p>Press <Kc keys={['Ctrl', 'Space']} /> in any app and talk. Each dictation appears here with the app it went to.</p>
				<button class="btn pri" type="button" onclick={() => goto('/welcome?step=2')}>
					<Icon name="microphone" /><span>Try a test dictation</span>
				</button>
			</div>
		</section>
	{:else}
		<div class="toolbar" role="search">
			<label class="search">
				<Icon name="magnifying-glass" />
				<input
					bind:this={searchInput}
					bind:value={search}
					type="search"
					placeholder="Search your dictations"
					aria-label="Search your dictations"
				/>
				<Kc keys={['/']} />
			</label>
			<DropdownMenu.Root>
				<DropdownMenu.Trigger class="btn sec drop">
					{#if appFilter}
						{@const app = apps.find((a) => a.name === appFilter)}
						<AppIcon name={appFilter} exe={app?.sample.appExe} exePath={app?.sample.appExePath} bundleId={app?.sample.appBundleId} size={16} />
						<span>{appFilter}</span>
					{:else}
						<span class="stack">
							{#each apps.slice(0, 3) as a (a.name)}
								<AppIcon name={a.name} exe={a.sample.appExe} exePath={a.sample.appExePath} bundleId={a.sample.appBundleId} size={16} />
							{/each}
						</span>
						<span>All apps</span>
					{/if}
					<Icon name="caret-down" class="i sm" />
				</DropdownMenu.Trigger>
				<DropdownMenu.Content align="start" class="vf w-56">
					<DropdownMenu.RadioGroup
						bind:value={() => appFilter ?? '*', (v) => (appFilter = v === '*' ? null : v)}
					>
						<DropdownMenu.RadioItem value="*">All apps</DropdownMenu.RadioItem>
						{#each apps as a (a.name)}
							<DropdownMenu.RadioItem value={a.name}>
								<AppIcon name={a.name} exe={a.sample.appExe} exePath={a.sample.appExePath} bundleId={a.sample.appBundleId} size={16} label />
							</DropdownMenu.RadioItem>
						{/each}
					</DropdownMenu.RadioGroup>
				</DropdownMenu.Content>
			</DropdownMenu.Root>
			<DropdownMenu.Root>
				<DropdownMenu.Trigger class="btn sec drop">
					<Icon name="clock-counter-clockwise" /><span>{TIME_LABELS[timeFilter]}</span><Icon
						name="caret-down"
						class="i sm"
					/>
				</DropdownMenu.Trigger>
				<DropdownMenu.Content align="start" class="w-44">
					<DropdownMenu.RadioGroup bind:value={timeFilter}>
						{#each Object.entries(TIME_LABELS) as [value, label]}
							<DropdownMenu.RadioItem {value}>{label}</DropdownMenu.RadioItem>
						{/each}
					</DropdownMenu.RadioGroup>
				</DropdownMenu.Content>
			</DropdownMenu.Root>
			<button
				class="tgl"
				class:on={problemsOnly}
				type="button"
				aria-pressed={problemsOnly}
				onclick={() => {
					problemsOnly = !problemsOnly;
					if (problemsOnly) deletedView = false;
				}}
			>
				<Icon name="warning-circle" /><span>Problems only</span>{#if problemCount}<em>{problemCount}</em>{/if}
			</button>
			<button
				class="tgl del"
				class:on={deletedView}
				type="button"
				aria-pressed={deletedView}
				onclick={() => {
					deletedView = !deletedView;
					if (deletedView) problemsOnly = false;
				}}
			>
				<Icon name="trash" /><span>Recently deleted</span><small>{DELETED_KEEP_DAYS} days</small>
			</button>
		</div>

		<div class="hsplit">
			<div class="card flush tblw" role="table" aria-label="Dictations" aria-rowcount={filtered.length}>
				<div class="hrow head" role="row">
					<span role="columnheader">Time</span><span role="columnheader">App</span><span
						role="columnheader">Text</span
					><span role="columnheader" class="c-len">Length</span><span role="columnheader">Result</span>
				</div>
				{#if filtered.length === 0}
					<div class="es pad">
						{#if deletedView && !search && !appFilter}
							<Illo name="history" size={96} />
							<b>Nothing deleted</b>
							<p>Deleted dictations stay here for {DELETED_KEEP_DAYS} days.</p>
						{:else if problemsOnly && !search && !appFilter}
							<Illo name="done" size={96} />
							<b>No problems</b>
							<p>Every dictation in this view was pasted or copied.</p>
						{:else}
							<Illo name="search" size={96} />
							<b>Nothing matches{search ? ` “${search}”` : ''}{appFilter ? ` in ${appFilter}` : ''}</b>
							<p>Try fewer words{appFilter ? ', or search every app' : ''}.</p>
							<button
								class="btn sec"
								type="button"
								onclick={() => {
									appFilter = null;
									timeFilter = 'any';
									if (!appFilter) search = '';
								}}>{appFilter ? 'Search every app' : 'Clear the search'}</button
							>
						{/if}
					</div>
				{:else}
					<div
						class="hscroll"
						bind:this={scroller}
						bind:clientHeight={viewHeight}
						onscroll={(e) => (scrollTop = e.currentTarget.scrollTop)}
					>
						<div style="height:{items.height}px;position:relative">
							{#each visible as it (it.kind === 'row' ? it.r.id : `g-${it.label}`)}
								{#if it.kind === 'group'}
									<div class="hgrp" role="row" style="top:{it.top}px">
										<span role="cell">{it.label}</span>
									</div>
								{:else}
									{@const r = it.r}
									{@const res = RESULT[resultOf(r)]}
									{@const t = rowText(r)}
									<div
										class="hrow"
										class:cur={r.id === selectedId}
										role="row"
										tabindex="-1"
										aria-selected={r.id === selectedId}
										style="top:{it.top}px"
										onclick={() => select(r.id)}
										onkeydown={(e) => e.key === 'Enter' && select(r.id)}
									>
										<span role="cell" class="num sub">{formatTime(createdAt(r))}</span>
										<span role="cell" class="c-app"
											><AppIcon name={r.appName} exe={r.appExe} exePath={r.appExePath} bundleId={r.appBundleId} size={18} label /></span
										>
										<span role="cell" class="tx" class:sub={t.dim}>{t.text}</span>
										<span role="cell" class="wvc c-len"
											><Wave count={10} seed={r.id} height={14} class="wv thumb" /><span class="num sub"
												>{formatLength(r.durationMs)}</span
											></span
										>
										<span role="cell"
											><span class="res {res.tone}"
												><Icon name={res.icon} weight="fill" class="i sm" />{res.text}</span
											></span
										>
									</div>
								{/if}
							{/each}
						</div>
					</div>
				{/if}
				<p class="foot">
					Keys: <Kc keys={['J']} /><Kc keys={['K']} /><span>move</span><Kc keys={['Space']} /><span
						>play</span
					><Kc keys={['C']} /><span>copy</span><Kc keys={['Del']} /><span>delete</span>
				</p>
			</div>

			{#if selected}
				{@const res = RESULT[resultOf(selected)]}
				<aside class="card hd" aria-label="Dictation details">
					<div class="hd-h">
						<AppIcon name={selected.appName} exe={selected.appExe} exePath={selected.appExePath} bundleId={selected.appBundleId} size={28} />
						<div>
							<h2>{selected.appName ?? 'Unknown app'}</h2>
							<span class="sub num">{whenLabel(createdAt(selected))}</span>
						</div>
						<span class="chip {res.tone}"><Icon name={res.icon} weight="fill" class="i xs" />{res.text}</span>
					</div>
					{#if selectedFeedback}
						<p class="fbstat"><span class="chip {selectedFeedback === 'fixed' ? 'ok' : ''}"
							><Icon name="chat-circle" class="i xs" />{FEEDBACK_STATUS[selectedFeedback]}</span
						></p>
					{/if}

					{#if isDeleted(selected)}
						<div class="saved warnish">
							<Icon name="trash" class="i sm" /><span
								>Deleted. It is removed for good {DELETED_KEEP_DAYS} days after it was deleted.</span
							>
						</div>
					{:else if selectedProblem}
						<ProblemCard problem={selectedProblem} compact />
					{/if}

					{#if resultOf(selected) === 'held' || resultOf(selected) === 'no-text'}
						<p class="lab">Your words</p>
						<p class="sub">
							{resultOf(selected) === 'held'
								? 'The audio is kept on this PC and was not sent.'
								: 'The audio is saved on this PC; there is no text yet.'}
						</p>
					{:else}
						<p class="lab">{resultOf(selected) === 'pasted' ? 'Pasted text' : 'Text'}</p>
						<p class="quote">{selectedText || '—'}</p>
						{#if selected.finalText && selected.finalText.trim() !== selected.transcribedText.trim()}
							<details class="ldet">
								<summary>As heard</summary>
								<p class="rawq">{selected.transcribedText}</p>
							</details>
						{/if}
						{#if selected.heldBackText && !isDeleted(selected)}
							<!-- 0.9.13: the language guard kept the user's words; the tidy-up waits here. -->
							<div class="saved warnish">
								<Icon name="warning" class="i sm" /><span
									>Kept your words: {driftReasonText(selected.heldBackReason).toLowerCase()}, so
									{selected.heldBackStyle ?? 'the style'}'s tidy-up was held back.</span
								>
								<button class="btn text sm" type="button" disabled={usingCleaned} onclick={useCleaned}
									>{usingCleaned ? 'Copying…' : 'Use cleaned'}</button
								>
							</div>
							<details class="ldet">
								<summary>Tidied version</summary>
								<p class="rawq">{selected.heldBackText}</p>
							</details>
						{/if}
					{/if}

					<Wave count={40} seed={selected.id} height={22} class="wv big" />

					{#if notice}
						<p class="saved" role="status">
							<Icon name="check-circle" weight="fill" class="i sm" /><span>{notice.text}</span>
							{#if notice.undo}<button class="btn text sm" type="button" onclick={notice.undo}>Undo</button>{/if}
						</p>
					{/if}

					<div class="acts col">
						<button
							class="btn pri {copy.copied ? 'done' : ''}"
							type="button"
							disabled={!selectedText}
							onclick={() => copy.copy(selectedText)}
						>
							<Icon name={copy.copied ? 'check' : 'copy'} /><span>{copy.copied ? 'Copied' : 'Copy text'}</span
							>{#if !copy.copied}<Kc keys={['C']} />{/if}
						</button>
					</div>

					<div class="rowacts">
						{#if selected.audioRemovedAt}
							<span class="btn text static" title="Removed under Settings, Keep recordings"
								><Icon name="microphone-slash" /><span>Audio removed</span></span
							>
						{:else}
							<button class="btn text" type="button" onclick={() => player.toggle(selected.id)}>
								<Icon name={player.playingId === selected.id ? 'pause' : 'play'} /><span
									>{player.playingId === selected.id ? 'Pause' : 'Play'}</span
								>
							</button>
						{/if}
						{#if isDeleted(selected)}
							<button class="btn text" type="button" onclick={() => restore(selected)}>
								<Icon name="arrow-counter-clockwise" /><span>Restore</span>
							</button>
						{:else}
							{#if selected.transcribedText.trim()}
								<button class="btn text" type="button" disabled={rerunning} onclick={rerun}>
									<Icon name="arrow-clockwise" /><span>{rerunning ? 'Tidying up…' : 'Re-run tidy-up'}</span>
								</button>
							{/if}
							<button
								class="btn text"
								class:active={pop === 'spelling'}
								type="button"
								onclick={() => (pop === 'spelling' ? (pop = null) : openSpelling())}
							>
								<Icon name="pencil-simple" /><span>Fix a spelling</span>
							</button>
							<button
								class="btn text"
								class:active={pop === 'feedback'}
								type="button"
								onclick={() => (pop = pop === 'feedback' ? null : 'feedback')}
							>
								<Icon name="chat-circle" /><span>Give feedback</span>
							</button>
							<button class="btn text del" type="button" onclick={() => remove(selected)}>
								<Icon name="trash" /><span>Delete</span>
							</button>
						{/if}
					</div>

					{#if pop === 'spelling'}
						<SpellingForm
							heard={heardGuess}
							appName={selected.appName}
							appId={appIdOf(selected)}
							onCancel={() => (pop = null)}
							onSaved={(saved) => {
								pop = null;
								notice = {
									text: `Spelling saved. “${saved.to}” is used from your next dictation; this one stays as it was.`,
									undo: async () => {
										await removeOp(saved.id);
										notice = { text: 'Spelling removed.' };
									},
								};
							}}
						/>
					{:else if pop === 'feedback'}
						{#key selected.id}
							<div class="pop fbpop">
								<FeedbackFix
									client={feedbackService}
									recordingId={selected.id}
									initialRecord={feedbackService.latestFor(selected.id)}
									popover={true}
									onClose={() => (pop = null)}
								/>
							</div>
						{/key}
					{/if}
				</aside>
			{:else}
				<aside class="card hd ghost-panel">
					<Illo name="history" size={96} />
					<p class="sub">Pick a dictation to see it here.</p>
				</aside>
			{/if}
		</div>
	{/if}

	{#if undo}
		<div class="toast" role="status">
			<Icon name="trash" weight="duotone" />
			<span class="tmsg"
				><b>Dictation deleted.</b> Undo within 8 seconds, or find it in Recently deleted for {DELETED_KEEP_DAYS}
				days.</span
			>
			<button class="btn pri" type="button" onclick={undoDelete}
				><span>Undo</span><Kc keys={['Ctrl', 'Z']} /></button
			>
			<span class="tcount num">{Math.ceil(undoLeft / 1000)} s</span>
			<span class="tbar"><i style="width:{(undoLeft / UNDO_MS) * 100}%"></i></span>
		</div>
	{/if}
</div>

<style>
	.fbstat {
		margin: -4px 0 4px;
	}
</style>
