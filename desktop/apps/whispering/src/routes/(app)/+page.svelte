<script lang="ts">
	import { createQuery } from '@tanstack/svelte-query';
	import { goto } from '$app/navigation';
	import { rpc } from '$lib/query';
	import { recovery } from '$lib/query/isomorphic/actions';
	import { settings } from '$lib/stores/settings.svelte';
	import { dictation } from '$lib/stores/dictation.svelte';
	import AppIcon from '$lib/vf/AppIcon.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import Illo from '$lib/vf/Illo.svelte';
	import Kc from '$lib/vf/Kc.svelte';
	import ProblemCard from '$lib/vf/ProblemCard.svelte';
	import StatCard from '$lib/vf/StatCard.svelte';
	import StylePicker from '$lib/vf/StylePicker.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import Wave from '$lib/vf/Wave.svelte';
	import { createCopyState } from '$lib/vf/copy.svelte';
	import {
		createdAt,
		dayLabel,
		finalTextOf,
		formatLength,
		formatMinutes,
		formatTime,
		homeStats,
		isDeleted,
		isNothingHeard,
		markChanges,
		problemsOf,
		resultOf,
		wordChanges,
		wordCount,
	} from '$lib/vf/dictations';
	import { player } from '$lib/vf/player.svelte';
	import {
		getSelectedTranscriptionService,
		isTranscriptionServiceConfigured,
	} from '$lib/settings/transcription-validation';

	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);
	const stylesQuery = createQuery(() => rpc.db.transformations.getAll.options);

	const recordings = $derived(
		(recordingsQuery.data ?? [])
			.filter((r) => !isDeleted(r))
			.sort((a, b) => createdAt(b).getTime() - createdAt(a).getTime()),
	);
	const stats = $derived(homeStats(recordings));
	// 0.9.12 (F4c): cards older than 24 h drop off, checked each minute too.
	let now = $state(Date.now());
	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(timer);
	});
	const problems = $derived(problemsOf(recordings, now));
	const last = $derived(recordings.find((r) => r.privacy !== 'held'));
	const recent = $derived(recordings.slice(0, 6));

	const toggleShortcut = $derived(
		settings.value['shortcuts.global.toggleManualRecording'],
	);
	const holdShortcut = $derived(settings.value['shortcuts.global.pushToTalk']);
	const styleName = $derived.by(() => {
		const id = settings.value['transformations.selectedTransformationId'];
		if (!id) return 'Raw';
		return (stylesQuery.data ?? []).find((s) => s.id === id)?.title || 'Raw';
	});

	// Older dictations have no final text saved: use their latest tidy-up.
	const lastRunQuery = createQuery(() => ({
		...rpc.db.runs.getLatestByRecordingId(() => last?.id ?? '').options,
		enabled: !!last && !last.finalText,
	}));
	const lastText = $derived.by(() => {
		if (!last) return '';
		if (last.finalText) return last.finalText.trim();
		const run = lastRunQuery.data;
		if (run && run.status === 'completed' && run.output.trim())
			return run.output.trim();
		return last.transcribedText.trim();
	});
	const lastParts = $derived(
		last ? markChanges(last.transcribedText, lastText) : [],
	);
	const lastFixes = $derived(
		last ? wordChanges(last.transcribedText, lastText).filter((c) => c.heard) : [],
	);
	let showDetails = $state(false);
	const copy = createCopyState();

	const fmt = (n: number) => n.toLocaleString();
	const wordsBars = $derived(
		stats.days.map((d) => ({ ...d, value: d.words, text: `${fmt(d.words)} words` })),
	);
	const speedBars = $derived(
		stats.days.map((d) => ({ ...d, value: d.wpm, text: d.wpm ? `${d.wpm} wpm` : 'no dictations' })),
	);
	const savedBars = $derived(
		stats.days.map((d) => ({ ...d, value: d.savedMin, text: formatMinutes(d.savedMin) })),
	);

	const transcriptionReady = $derived.by(() => {
		const service = getSelectedTranscriptionService();
		return !!service && isTranscriptionServiceConfigured(service);
	});

	function recentLine(r: (typeof recordings)[number]) {
		const result = resultOf(r);
		if (result === 'held') return { tone: 'warn', lead: 'Held.', rest: r.appName ? 'Not sent' : 'App unknown' };
		// 0.9.12 (F6): no speech is not a problem; said quietly.
		if (result === 'no-text' && isNothingHeard(r)) return { tone: '', lead: '', rest: 'Nothing heard' };
		if (result === 'no-text') return { tone: 'bad', lead: 'No text.', rest: 'Audio saved' };
		if (result === 'not-pasted')
			return {
				tone: 'bad',
				lead: 'Not pasted.',
				rest: r.problemKind === 'tidy-up' ? 'Tidy-up didn’t finish' : 'Kept in History',
			};
		if (result === 'working') return { tone: '', lead: '', rest: 'Working on it…' };
		return { tone: '', lead: '', rest: finalTextOf(r) };
	}

	function onKey(e: KeyboardEvent) {
		if (e.ctrlKey || e.metaKey || e.altKey) return;
		const el = e.target as HTMLElement | null;
		if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)))
			return;
		const key = e.key.toLowerCase();
		if (key === 'c' && last && lastText) {
			e.preventDefault();
			copy.copy(lastText);
		} else if (key === 'r') {
			const tidy = problems.find((p) => p.kind === 'tidy-up');
			if (tidy) {
				e.preventDefault();
				recovery.retryTidyUp(tidy.recording.id);
			}
		}
	}
</script>

<svelte:head>
	<title>VoiceLnq</title>
</svelte:head>

<svelte:window onkeydown={onKey} />

<div class="vf page">
	<header class="ph">
		<div>
			<h1>Home</h1>
			<p class="ready-line">
				<span class="ready"><i class="dot ok"></i>Ready</span>
				{#if recordings.length === 0 && !toggleShortcut}
					Set up and waiting for your first dictation
				{:else}
					{#if toggleShortcut}Press <Kc shortcut={toggleShortcut} /> in any app{/if}{#if holdShortcut}{toggleShortcut
							? ', or hold'
							: 'Hold'}
						<Kc shortcut={holdShortcut} />{/if}
				{/if}
			</p>
		</div>
		<div class="ph-r">
			<div class="into">
				{#if dictation.current}
					<span class="lab">Dictating into</span>
					<AppIcon
						name={dictation.current.appName}
						exe={dictation.current.appExe}
						exePath={dictation.current.appExePath} bundleId={dictation.current.appBundleId}
					/>
					<b>{dictation.current.appName ?? 'Unknown app'}</b>
					<span class="sub">{styleName} style</span>
				{:else}
					<span class="lab">Next dictation</span>
					<b>{styleName}</b>
					<span class="sub">style</span>
				{/if}
				<StylePicker />
			</div>
		</div>
	</header>

	<div class="stats">
		<StatCard
			icon="text-aa"
			label="Words this week"
			value={stats.hasData ? fmt(stats.wordsThisWeek) : '—'}
			empty={!stats.hasData}
			deltaTone={stats.changePct !== null && stats.changePct > 0 ? 'up' : 'flat'}
			bars={wordsBars}
		>
			{#snippet delta()}
				{#if !stats.hasData}
					Starts after your first dictation
				{:else if stats.changePct === null}
					Nothing last week to compare
				{:else if stats.changePct === 0}
					Same as last week
				{:else}
					<Icon name="arrow-right" class="i xs {stats.changePct > 0 ? 'up' : 'down'}" />
					{Math.abs(stats.changePct)}% {stats.changePct > 0 ? 'more' : 'fewer'} than last week
				{/if}
			{/snippet}
		</StatCard>
		<StatCard
			icon="gauge"
			label="Speaking speed"
			value={stats.wpm ? `${stats.wpm} wpm` : '—'}
			empty={!stats.hasData}
			bars={speedBars}
		>
			{#snippet delta()}
				{#if stats.wpm}
					About {Math.max(1, Math.round(stats.wpm / 40))}× typing at 40 wpm
				{:else}
					Starts after your first dictation
				{/if}
			{/snippet}
		</StatCard>
		<StatCard
			icon="timer"
			label="Time saved"
			value={stats.wpm ? formatMinutes(stats.savedMin) : '—'}
			empty={!stats.hasData}
			bars={savedBars}
		>
			{#snippet delta()}
				{#if stats.wpm}Compared with typing at 40 wpm{:else}Starts after your first dictation{/if}
			{/snippet}
		</StatCard>
	</div>

	{#if recordingsQuery.isPending}
		<div class="hg">
			<section class="card"><div class="skel">{#each [62, 48, 70] as w}<div class="sk-row"><i class="sk c"></i><i class="sk a"></i><i class="sk b" style="width:{w}%"></i></div>{/each}</div></section>
		</div>
	{:else if recordings.length === 0}
		<div class="hg">
			<div class="col">
				<section class="card empty-big">
					<Illo name="keys" size={120} />
					<div>
						<h2 class="eh">Your dictations will show up here</h2>
						<p class="sub">
							Each one shows the text VoiceLnq pasted, the app it went to, and which words it fixed.
						</p>
						<ol class="howto">
							<li><span class="n">1</span><span>Click where you want to type, in any app</span></li>
							<li><span class="n">2</span><span>Press <Kc shortcut={toggleShortcut} /> and talk</span></li>
							<li><span class="n">3</span><span>Press <Kc shortcut={toggleShortcut} /> again to paste</span></li>
						</ol>
						<div class="acts">
							<button class="btn pri" type="button" onclick={() => goto('/welcome?step=2')}>
								<Icon name="microphone" /><span>Try a test dictation</span>
							</button>
							<button class="btn text" type="button" onclick={() => goto('/settings#keys')}
								>Change the keys</button
							>
						</div>
					</div>
				</section>
			</div>
			<div class="col">
				<section class="card">
					<div class="c-h"><Tile icon="shield-check" tone="ok" /><h2>Ready to go</h2></div>
					<ul class="checks">
						<li>
							<Icon name="check-circle" weight="fill" class="i ok" />
							<div><b>Microphone</b><span>Choose it in Settings if the bars don’t move</span></div>
						</li>
						<li>
							{#if transcriptionReady}
								<Icon name="check-circle" weight="fill" class="i ok" />
								<div><b>Speech to text</b><span>Your key is saved</span></div>
							{:else}
								<Icon name="warning-circle" weight="fill" class="i bad" />
								<div>
									<b>Speech to text</b><span
										>Add your OpenAI key in <a class="lk" href="/settings#transcription">Settings</a></span
									>
								</div>
							{/if}
						</li>
						<li>
							<Icon name="check-circle" weight="fill" class="i ok" />
							<div><b>Privacy</b><span>Password boxes are never filled</span></div>
						</li>
					</ul>
				</section>
			</div>
		</div>
	{:else}
		<div class="hg">
			<div class="col">
				{#if problems.length > 0}
					<section class="card ny" aria-labelledby="needs-you">
						<div class="c-h">
							<Tile icon="warning-circle" tone="bad" />
							<h2 id="needs-you">Needs you</h2>
							<span class="cnt">{problems.length}</span>
							{#if problems.length > 2}
								<a class="lk" href="/history?filter=problems">See all in History</a>
							{/if}
							<!-- VoiceFlow 0.9.11: clear every problem at once (History keeps them). -->
							<button
								type="button"
								class="lk"
								style="background:none;border:0;padding:0;cursor:pointer;font:inherit;font-size:13px;margin-left:{problems.length > 2 ? '12px' : 'auto'}"
								onclick={() => void recovery.dismissAll(problems.map((p) => p.recording.id))}
							>Clear all</button>
						</div>
						{#each problems.slice(0, 2) as problem (problem.recording.id)}
							<ProblemCard {problem} />
						{/each}
					</section>
				{/if}

				{#if last}
					<section class="card last" aria-labelledby="last-dictation">
						<div class="c-h">
							<Tile icon="waveform" />
							<h2 id="last-dictation">Last dictation</h2>
							<span class="meta">
								<AppIcon name={last.appName} exe={last.appExe} exePath={last.appExePath} bundleId={last.appBundleId} size={18} label />
								{#if last.styleName}<span class="chip"><i class="dot neutral"></i>{last.styleName}</span>{/if}
								<span class="num">{formatTime(createdAt(last))}</span>
							</span>
						</div>
						{#if lastText}
							<p class="lc">
								{#each lastParts as part}{#if part.heard}<span class="fix" title="Heard “{part.heard}”">{part.text}</span>{:else}{part.text}{/if}{/each}
							</p>
						{:else}
							<p class="lc sub">{resultOf(last) === 'working' ? 'Working on it…' : 'No text for this dictation.'}</p>
						{/if}
						<div class="lfoot">
							<Wave count={30} seed={last.id} height={18} class="wv big" />
							<span class="num sub">{formatLength(last.durationMs)}</span>
							{#if lastFixes.length > 0}
								<button class="fixes linkish" type="button" onclick={() => (showDetails = !showDetails)}>
									<Icon name="book-open-text" class="i sm" />{lastFixes.length}
									{lastFixes.length === 1 ? 'word' : 'words'} fixed
								</button>
							{/if}
							<span class="lacts">
								<button
									class="btn sec {copy.copied ? 'done' : ''}"
									type="button"
									disabled={!lastText}
									onclick={() => copy.copy(lastText)}
								>
									<Icon name={copy.copied ? 'check' : 'copy'} /><span>{copy.copied ? 'Copied' : 'Copy'}</span
									>{#if !copy.copied}<Kc keys={['C']} />{/if}
								</button>
								{#if last.audioRemovedAt}
									<span class="btn text static"><Icon name="microphone-slash" /><span>Audio removed</span></span>
								{:else}
									<button class="btn text" type="button" onclick={() => player.toggle(last.id)}>
										<Icon name={player.playingId === last.id ? 'pause' : 'play'} /><span
											>{player.playingId === last.id ? 'Pause' : 'Play'}</span
										>
									</button>
								{/if}
							</span>
						</div>
						<details class="ldet" bind:open={showDetails}>
							<summary>Details</summary>
							{#if lastFixes.length > 0}
								<p class="lab">What changed</p>
								<ul class="chg">
									{#each lastFixes as fix}
										<li><s>{fix.heard}</s><Icon name="arrow-right" class="i sm sub" /><b>{fix.written}</b></li>
									{/each}
								</ul>
							{/if}
							<p class="lab">Your words, as heard</p>
							<p class="rawq">{last.transcribedText || '—'}</p>
							<p class="sub tm">
								{wordCount(lastText)} words{#if last.durationMs} in {formatLength(last.durationMs)}{/if}{#if last.styleName} · tidied with {last.styleName}{/if}
							</p>
						</details>
					</section>
				{/if}
			</div>

			<div class="col">
				<section class="card rec" aria-labelledby="recent">
					<div class="c-h">
						<Tile icon="clock-counter-clockwise" />
						<h2 id="recent">Recent</h2>
						<a class="lk" href="/history">All history</a>
					</div>
					<ul class="rl">
						{#each recent as r, i (r.id)}
							{@const day = dayLabel(createdAt(r))}
							{#if (i === 0 && day !== 'Today') || (i > 0 && day !== dayLabel(createdAt(recent[i - 1] ?? r)))}
								<li class="rdiv">{day}</li>
							{/if}
							{@const line = recentLine(r)}
							<li>
								<AppIcon name={r.appName} exe={r.appExe} exePath={r.appExePath} bundleId={r.appBundleId} />
								<a href="/history?id={r.id}">
									<b>{r.appName ?? 'Unknown app'}<span class="num sub">{formatTime(createdAt(r))}</span></b>
									<span>{#if line.lead}<span class={line.tone}>{line.lead}</span>{' '}{/if}{line.rest}</span>
								</a>
							</li>
						{/each}
					</ul>
				</section>
			</div>
		</div>
	{/if}
</div>
