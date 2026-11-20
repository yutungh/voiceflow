<script lang="ts">
	import { onMount } from 'svelte';
	import {
		readTimingRecords,
		summarize,
		timingFileLocation,
		type SegmentStats,
		type TimingRecord,
	} from '$lib/timing';

	/**
	 * Settings > Speed (desktop/speed): where the time goes in recent hotkey
	 * dictations. Only timings are kept on this PC: no words, no app names.
	 */
	const LAST_N = 50;

	let records = $state<TimingRecord[]>([]);
	let location = $state<string | null>(null);
	let loading = $state(true);
	let loadError = $state<string | null>(null);

	const stats = $derived<SegmentStats[]>(summarize(records, LAST_N));
	const total = $derived(stats.find((s) => s.id === 'release-to-paste'));
	const shown = $derived(Math.min(records.length, LAST_N));

	async function load() {
		loading = true;
		loadError = null;
		try {
			records = await readTimingRecords();
			location = await timingFileLocation();
		} catch (error) {
			loadError = error instanceof Error ? error.message : String(error);
		} finally {
			loading = false;
		}
	}

	onMount(load);

	function ms(value: number | null | undefined) {
		if (value === null || value === undefined) return '–';
		return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${Math.round(value)} ms`;
	}
</script>

{#if loading}
	<p class="sub">Loading…</p>
{:else if loadError}
	<p class="err">Couldn't read the timings: {loadError}</p>
{:else if records.length === 0}
	<div class="srow">
		<div><b>Release to pasted</b><span>No dictations timed yet. Dictate with your keys, then come back.</span></div>
		<button class="btn text" type="button" onclick={load}>Refresh</button>
	</div>
{:else}
	<div class="srow">
		<div>
			<b>Release to pasted</b>
			<span>Typical over your last {shown} dictation{shown === 1 ? '' : 's'}; slow is 1 in 10</span>
		</div>
		<span class="num"><b>{ms(total?.p50)}</b> <span class="sub">· slow {ms(total?.p90)}</span></span>
		<button class="btn text" type="button" onclick={load}>Refresh</button>
	</div>
	<details class="ldet">
		<summary>By step</summary>
		<table class="speed">
			<thead>
				<tr><th>Step</th><th>Typical</th><th>Slow</th><th>Count</th></tr>
			</thead>
			<tbody>
				{#each stats.filter((s) => s.id !== 'release-to-paste') as s (s.id)}
					<tr class:dim={s.n === 0}>
						<td>{s.label}</td>
						<td class="num">{ms(s.p50)}</td>
						<td class="num">{ms(s.p90)}</td>
						<td class="num">{s.n}</td>
					</tr>
				{/each}
			</tbody>
		</table>
		<p class="sub small">Steps a dictation skipped (no tidy-up, no corrections yet) show a dash. Only timings are kept on this PC: no words, no app names.</p>
		{#if location}<p class="sub small loc">{location}</p>{/if}
	</details>
{/if}

<style>
	.speed {
		width: 100%;
		font-size: 13px;
		border-collapse: collapse;
		margin-top: 6px;
	}
	.speed th {
		font-weight: 400;
		color: var(--subtle-foreground);
		text-align: right;
		padding: 2px 0;
	}
	.speed th:first-child,
	.speed td:first-child {
		text-align: left;
		white-space: pre-line;
	}
	.speed td {
		text-align: right;
		padding: 3px 0;
		border-top: 1px solid var(--border);
	}
	.speed tr.dim td {
		color: var(--subtle-foreground);
	}
	.loc {
		font-family: var(--font-mono, monospace);
		overflow-wrap: anywhere;
	}
</style>
