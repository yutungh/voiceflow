<script lang="ts">
	import { recovery } from '$lib/query/isomorphic/actions';
	import Icon from './Icon.svelte';
	import Kc from './Kc.svelte';
	import Term from './Term.svelte';
	import Tile from './Tile.svelte';
	import { createCopyState, TIDY_UP_TIP } from './copy.svelte';
	import { createdAt, finalTextOf, whenLabel, type Problem } from './dictations';

	/**
	 * B3.1 "Problems explain themselves": what happened, why, and one fix.
	 * A thin edge and an icon show how serious it is. The words are never
	 * lost: they stay in History whatever is chosen here.
	 */
	let { problem, compact = false }: { problem: Problem; compact?: boolean } =
		$props();

	const r = $derived(problem.recording);
	const app = $derived(r.appName?.trim() || 'the app');
	const when = $derived(whenLabel(createdAt(r)));
	let busy = $state(false);
	const copy = createCopyState();

	const title = $derived(
		problem.kind === 'held'
			? 'Your words were kept on this PC'
			: problem.kind === 'transcription'
				? "VoiceLnq couldn't turn your words into text"
				: `Nothing was pasted into ${app}`,
	);

	const cause = $derived(
		problem.kind === 'held'
			? r.appName
				? `VoiceLnq couldn't check the box you were typing in, in ${r.appName}, so nothing was sent.`
				: "VoiceLnq couldn't tell which app you were in, so nothing was sent."
			: (r.problem ?? 'Something went wrong.'),
	);

	const startsWithTidyUp = $derived(cause.startsWith('Tidy-up'));

	/** 0.9.12 (F4d): the words are on the clipboard, so the problem is cleared. */
	async function copyText() {
		if (await copy.copy(finalTextOf(r))) await recovery.markCopied(r.id);
	}

	async function run(fn: () => Promise<unknown>) {
		busy = true;
		try {
			await fn();
		} finally {
			busy = false;
		}
	}
</script>

<div class="prob {problem.kind === 'held' ? 'warn' : 'bad'}" role="group" aria-label={title}>
	<Tile
		icon={problem.kind === 'held' ? 'pause' : 'warning'}
		tone={problem.kind === 'held' ? 'warn' : 'bad'}
	/>
	<div class="pb">
		<b>{title}</b>
		<p>
			{#if startsWithTidyUp}<Term tip={TIDY_UP_TIP}>Tidy-up</Term>{cause.slice(
					'Tidy-up'.length,
				)}{:else}{cause}{/if}
			<span class="num">{when}.</span>
		</p>
		<div class="acts">
			{#if problem.kind === 'tidy-up'}
				<button
					class="btn pri"
					type="button"
					disabled={busy}
					onclick={() => run(() => recovery.retryTidyUp(r.id))}
				>
					<Icon name="arrow-clockwise" /><span>Retry tidy-up</span>{#if !compact}<Kc
							keys={['R']}
						/>{/if}
				</button>
				<button
					class="btn text"
					type="button"
					disabled={busy}
					onclick={() => run(() => recovery.pasteRaw(r.id))}>Paste raw text</button
				>
			{:else if problem.kind === 'transcription'}
				<button
					class="btn pri"
					type="button"
					disabled={busy}
					onclick={() => run(() => recovery.retryTranscription(r.id))}
				>
					<Icon name="arrow-clockwise" /><span>Try again</span>
				</button>
			{:else if problem.kind === 'held'}
				<button
					class="btn pri"
					type="button"
					disabled={busy}
					onclick={() => run(() => recovery.sendAnyway(r.id))}
				>
					<Icon name="arrow-right" /><span>Send anyway</span>
				</button>
			{:else}
				<button
					class="btn pri {copy.copied ? 'done' : ''}"
					type="button"
					onclick={() => copyText()}
				>
					<Icon name={copy.copied ? 'check' : 'copy'} /><span
						>{copy.copied ? 'Copied' : 'Copy text'}</span
					>
				</button>
			{/if}
			{#if !r.problemDismissed}
				<button
					class="btn text"
					type="button"
					disabled={busy}
					onclick={() => run(() => recovery.dismiss(r.id))}>Dismiss</button
				>
			{/if}
		</div>
		{#if problem.kind === 'held'}
			<p class="hint">Send anyway puts the text on the clipboard; it is never pasted on its own.</p>
		{/if}
	</div>
</div>
