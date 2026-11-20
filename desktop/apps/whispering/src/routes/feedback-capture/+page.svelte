<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import type { FeedbackPayload } from '$lib/bar/contract';
	import type { FeedbackClient } from '$lib/feedback/client';
	import Icon from '$lib/vf/Icon.svelte';
	import FeedbackFix from '$lib/vf/FeedbackFix.svelte';

	/**
	 * VoiceFlow A2 + phase F: opens after a voice feedback recording (or the
	 * bar's Feedback button). The person checks what VoiceFlow heard, sees the
	 * dictation it is about, and either saves it as a draft (kept on this PC)
	 * or sends it for a fix. The work happens in the main window (it owns the
	 * keys, the word lists and the paste path); this window only asks it,
	 * through `$lib/feedback/rpc`.
	 */

	const desktop = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

	let payload = $state<FeedbackPayload | null>(null);
	let loaded = $state(false);
	let showRaw = $state(false);
	let round = $state(0);
	let client = $state<FeedbackClient | null>(null);
	let unlisten: (() => void) | null = null;

	const DEMO: FeedbackPayload = {
		feedbackRecordingId: 'demo',
		transcript: "You wrote Zebbo, it's Zibbo.",
		transcriptProblem: null,
		subject: {
			recordingId: 'prev',
			appName: 'Telegram',
			at: new Date(Date.now() - 90_000).toISOString(),
			snippet: '',
		},
		subjectRawText: 'ask zebbo to add milk to the list',
		subjectFinalText: 'Ask Zebbo to add milk to the list.',
		subjectStyleName: 'Casual',
		subjectAppId: 'telegram.exe',
	};

	async function take() {
		if (!desktop) {
			payload = DEMO;
		} else {
			const { invoke } = await import('@tauri-apps/api/core');
			const next = await invoke<FeedbackPayload | null>('feedback_payload_take').catch(() => null);
			if (next) payload = next;
		}
		round += 1;
		loaded = true;
	}

	async function close() {
		if (!desktop) return;
		const { getCurrentWindow } = await import('@tauri-apps/api/window');
		await getCurrentWindow().close();
	}

	const when = $derived.by(() => {
		const at = payload?.subject?.at;
		if (!at) return '';
		const d = new Date(at);
		return Number.isNaN(d.getTime())
			? ''
			: d.toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' });
	});

	onMount(() => {
		void (async () => {
			client = desktop
				? (await import('$lib/feedback/rpc')).windowFeedbackClient
				: (await import('$lib/feedback/service.svelte')).feedbackService;
			await take();
		})();
		if (!desktop) return;
		void import('@tauri-apps/api/event').then(async ({ listen }) => {
			unlisten = await listen('feedback://reload', () => void take());
		});
	});
	onDestroy(() => unlisten?.());
</script>

<svelte:head>
	<title>Feedback on a dictation</title>
</svelte:head>

<main class="vf fb">
	<div class="wrap">
		{#if !loaded || !client}
			<p class="note">Loading…</p>
		{:else}
			<section class="subj" aria-label="The dictation this is about">
				<p class="lab">About</p>
				{#if payload?.subject}
					<p class="who">
						<b>{payload.subject.appName ?? 'A dictation'}</b>
						{#if when}<span class="sub">{when}</span>{/if}
						{#if payload.subjectStyleName}<span class="sub">· {payload.subjectStyleName} style</span>{/if}
					</p>
					<p class="rawq small">{payload.subjectFinalText || payload.subjectRawText || payload.subject.snippet || '—'}</p>
					{#if payload.subjectRawText && payload.subjectFinalText}
						<button class="btn text sm" type="button" aria-expanded={showRaw} onclick={() => (showRaw = !showRaw)}>
							<Icon name={showRaw ? 'caret-down' : 'caret-right'} class="i sm" />What you said
						</button>
						{#if showRaw}<p class="rawq small">{payload.subjectRawText}</p>{/if}
					{/if}
				{:else}
					<p class="sub">No earlier dictation was found, so there is nothing to attach this to yet.</p>
				{/if}
			</section>

			{#if payload?.transcriptProblem}
				<p class="note warnline"><Icon name="warning" class="i sm" />{payload.transcriptProblem}</p>
			{:else if payload?.transcript}
				<p class="note"><Icon name="microphone" class="i sm" />Transcribed from what you said. Fix anything it misheard.</p>
			{/if}

			{#if payload?.subject}
				{#key `${round}:${payload.subject.recordingId}`}
					<FeedbackFix
						{client}
						recordingId={payload.subject.recordingId}
						initialFeedback={payload.transcript}
						onClose={() => void close()}
					/>
				{/key}
			{:else}
				<div class="acts"><button class="btn text" type="button" onclick={() => void close()}>Close</button></div>
			{/if}
		{/if}
	</div>
</main>

<style>
	.fb {
		min-height: 100svh;
		background: var(--background);
		padding: 20px 22px;
		overflow-y: auto;
	}
	.wrap {
		display: flex;
		flex-direction: column;
		gap: 10px;
		max-width: 640px;
		margin: 0 auto;
	}
	.subj {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px 14px;
		border-radius: 12px;
		background: var(--card);
		box-shadow: var(--shadow-card);
	}
	.subj p {
		margin: 0;
	}
	.who {
		display: flex;
		gap: 6px;
		align-items: baseline;
		flex-wrap: wrap;
	}
	.warnline {
		color: var(--warning);
	}
	.subj :global(.btn.text) {
		align-self: flex-start;
		padding: 0 6px;
	}
</style>
