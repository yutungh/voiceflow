<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from './Icon.svelte';
	import Tile from './Tile.svelte';
	import type { FeedbackClient, FeedbackSetup } from '$lib/feedback/client';
	import { DIAGNOSIS_LABELS } from '$lib/feedback/engine';
	import type { FeedbackRecord } from '$lib/feedback/log';
	import type { ReviewedFix, ScopeCheck, SpellingFix } from '$lib/feedback/validate';

	/**
	 * VoiceFlow phase F (B3.1): feedback on one dictation, then its fix.
	 *
	 * - Compose: the feedback (editable) and, next to Send, exactly who it
	 *   goes to and what is sent. "Save as draft" keeps it on this PC only.
	 * - Result: the corrected text (editable) with Copy (the default) and
	 *   Paste into <app>; the diagnosis in one line; lasting fixes as
	 *   unchecked items with their exact scope and what else they would
	 *   change. Apply selected writes them; each has its own Undo.
	 */
	let {
		client,
		recordingId,
		initialFeedback = '',
		initialRecord = null,
		heading = 'What should have been different?',
		popover = false,
		onClose,
	}: {
		client: FeedbackClient;
		recordingId: string;
		initialFeedback?: string;
		initialRecord?: FeedbackRecord | null;
		heading?: string;
		/** Shown as History's popover (scrolls inside it). */
		popover?: boolean;
		onClose: () => void;
	} = $props();

	let setup = $state<FeedbackSetup | null>(null);
	let feedback = $state(untrack(() => initialRecord?.feedback ?? initialFeedback));
	let record = $state<FeedbackRecord | null>(untrack(() => initialRecord));
	let step = $state<'compose' | 'sending' | 'result'>(untrack(() => (initialRecord?.result ? 'result' : 'compose')));
	let error = $state<string | null>(null);
	let notice = $state<string | null>(null);
	let corrected = $state(untrack(() => initialRecord?.result?.correctedText ?? ''));
	let busy = $state(false);
	let checked = $state<Record<string, boolean>>({});
	let scopes = $state<Record<string, 'app' | 'everywhere'>>({});
	let box = $state<HTMLTextAreaElement | null>(null);

	$effect(() => {
		const id = recordingId;
		void untrack(async () => {
			const reply = await client.setup(id);
			if (reply.ok) setup = reply.setup;
			else error = reply.message;
		});
	});
	$effect(() => {
		if (step === 'compose') box?.focus();
	});

	const result = $derived(record?.result ?? null);
	const appName = $derived(setup?.subject?.appName ?? null);
	const liveApplied = $derived(new Map((record?.applied ?? []).filter((a) => !a.undoneAt).map((a) => [a.key, a])));
	const undone = $derived(new Set((record?.applied ?? []).filter((a) => a.undoneAt).map((a) => a.key)));
	const chosen = $derived(
		(result?.fixes ?? []).filter((f) => checked[f.key] && !liveApplied.has(f.key) && usable(f)),
	);

	function scopeOf(fix: SpellingFix): 'app' | 'everywhere' {
		return scopes[fix.key] ?? fix.defaultScope;
	}
	function checkOf(fix: SpellingFix): ScopeCheck | null {
		return scopeOf(fix) === 'app' ? fix.checks.app : fix.checks.everywhere;
	}
	function usable(fix: ReviewedFix): boolean {
		return fix.kind === 'style' || !!checkOf(fix)?.ok;
	}

	function take(reply: { ok: boolean; message?: string; record?: FeedbackRecord; stale?: boolean }) {
		if (reply.record) record = reply.record;
		if (!reply.ok) error = reply.message ?? 'Something went wrong.';
	}

	async function send(e?: SubmitEvent) {
		e?.preventDefault();
		error = null;
		notice = null;
		if (!feedback.trim()) {
			error = 'Say or type what should have been different.';
			return;
		}
		step = 'sending';
		const reply = await client.submit({
			recordingId,
			feedback,
			recordId: record?.id,
			...(setup ? { expect: { provider: setup.provider, model: setup.model } } : {}),
		});
		take(reply);
		if (!reply.ok) {
			// Settings may have changed: show who it would go to now.
			const fresh = await client.setup(recordingId);
			if (fresh.ok) setup = fresh.setup;
		}
		if (reply.ok && reply.record.result) {
			corrected = reply.record.result.correctedText;
			checked = {};
			scopes = {};
			step = 'result';
		} else step = 'compose';
	}

	async function saveDraft() {
		error = null;
		busy = true;
		const reply = await client.saveDraft({ recordingId, feedback, recordId: record?.id });
		busy = false;
		take(reply);
		if (reply.ok) notice = 'Saved on this PC as a draft. Nothing was sent.';
	}

	async function copy() {
		error = null;
		if (!record) return;
		const reply = await client.copy({ recordId: record.id, text: corrected });
		take(reply);
		if (reply.ok) notice = 'Copied to the clipboard.';
	}

	async function paste() {
		error = null;
		if (!record) return;
		busy = true;
		const reply = await client.paste({ recordId: record.id, text: corrected });
		busy = false;
		take(reply);
		if (reply.ok) notice = reply.message;
	}

	async function apply() {
		error = null;
		notice = null;
		if (!record || chosen.length === 0) return;
		busy = true;
		const reply = await client.apply({
			recordId: record.id,
			selections: chosen.map((f) => (f.kind === 'spelling' ? { key: f.key, scope: scopeOf(f) } : { key: f.key })),
		});
		busy = false;
		take(reply);
		if (reply.ok) {
			checked = {};
			notice = 'Saved. Used from your next dictation; this one stays as it was.';
		} else if ('stale' in reply && reply.stale) {
			checked = {};
		}
	}

	async function undo(opId: string) {
		error = null;
		if (!record) return;
		busy = true;
		const reply = await client.undo({ recordId: record.id, opId });
		busy = false;
		take(reply);
		if (reply.ok) notice = 'Undone.';
	}

	const q = (s: string) => `“${s}”`;
	function spellingLabel(fix: SpellingFix) {
		return `Always write ${q(fix.to)} for ${fix.from.map(q).join(' or ')}`;
	}
</script>

<section class="fbx" class:scroll={popover} aria-label="Feedback on this dictation">
	<div class="pop-h"><Tile icon="chat-circle" /><b>{step === 'result' ? 'Here is the fix' : heading}</b></div>

	{#if step !== 'result'}
		<form onsubmit={send}>
			<label for="fbx-text">Your feedback</label>
			<textarea
				id="fbx-text"
				class="inp area"
				rows="3"
				bind:value={feedback}
				bind:this={box}
				disabled={step === 'sending'}
				placeholder="For example: you wrote Zebbo, it's Zibbo"
			></textarea>
			{#if setup}
				<p class="note"><Icon name="info" class="i sm" />{setup.disclosure}</p>
				{#if !setup.ready}
					<p class="note warnline">
						<Icon name="warning" class="i sm" />Add your {setup.provider} API key in Settings to send feedback. You can still save it as a draft.
					</p>
				{/if}
			{/if}
			{#if error}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
			{/if}
			{#if notice}<p class="note okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{notice}</p>{/if}
			<div class="acts">
				<button class="btn pri" type="submit" disabled={step === 'sending' || !setup?.ready || !setup?.subject}>
					{#if step === 'sending'}Asking {setup?.provider ?? 'the model'}…{:else}Send to {setup?.provider ?? '…'}{/if}
				</button>
				<button class="btn sec" type="button" disabled={busy || step === 'sending'} onclick={saveDraft}>Save as draft</button>
				<button class="btn text" type="button" onclick={onClose}>Close</button>
			</div>
		</form>
	{:else if result}
		<p class="diag">
			<span class="chip {result.diagnosis === 'uncertain' ? 'warn' : 'ok'}">{DIAGNOSIS_LABELS[result.diagnosis]}</span>
			<span>{result.explanation}</span>
		</p>

		<label for="fbx-corrected">Corrected text</label>
		<textarea id="fbx-corrected" class="inp area" rows="4" bind:value={corrected}></textarea>
		<div class="acts">
			<button class="btn pri" type="button" disabled={!corrected.trim()} onclick={copy}>
				<Icon name="copy" class="i sm" />Copy
			</button>
			{#if setup?.subject?.canPaste}
				<button class="btn sec" type="button" disabled={busy || !corrected.trim()} onclick={paste}>
					Paste into {appName ?? 'the app'}
				</button>
			{/if}
		</div>

		{#if result.fixes.length > 0}
			<p class="lab sect">Lasting fixes (optional)</p>
			<ul class="fixes">
				{#each result.fixes as fix (fix.key)}
					{@const applied = liveApplied.get(fix.key)}
					<li class:done={!!applied}>
						{#if applied}
							<span class="tick"><Icon name="check-circle" weight="fill" class="i sm" /></span>
						{:else}
							<input
								type="checkbox"
								id="fix-{fix.key}"
								bind:checked={checked[fix.key]}
								disabled={!usable(fix) || busy}
							/>
						{/if}
						<div class="fx">
							{#if fix.kind === 'spelling'}
								<label for="fix-{fix.key}" class="fl">{spellingLabel(fix)}</label>
								<span class="scope">
									{#if applied}
										{applied.scope === 'app' ? `in ${fix.appName ?? 'this app'} only` : 'in every app'}
									{:else if fix.appId}
										<select
											class="inp sm"
											aria-label="Where this spelling applies"
											value={scopeOf(fix)}
											onchange={(e) => (scopes[fix.key] = e.currentTarget.value as 'app' | 'everywhere')}
										>
											<option value="app">in {fix.appName ?? 'this app'} only</option>
											<option value="everywhere">in every app</option>
										</select>
									{:else}
										in every app (VoiceFlow doesn’t know this dictation’s app)
									{/if}
								</span>
								{@const check = checkOf(fix)}
								{#if check && !check.ok}
									<span class="sub warnline">{check.message}</span>
								{:else if check && check.ok && !applied}
									{#if check.alsoCount > 0}
										<span class="sub">This would also change {check.alsoCount} recent dictation{check.alsoCount === 1 ? '' : 's'}:</span>
										{#each check.alsoChanges as ex (ex.recordingId)}
											<span class="ex"><s>{ex.before}</s> → {ex.after}</span>
										{/each}
									{:else}
										<span class="sub">It doesn’t change any of your recent dictations.</span>
									{/if}
								{/if}
							{:else}
								<label for="fix-{fix.key}" class="fl">{fix.label}</label>
								<span class="sub">From your next dictation in {fix.appName ?? fix.app}.{fix.replaces ? ` ${fix.replaces}` : ''}</span>
							{/if}
							<span class="sub why">{fix.reason}</span>
							{#if applied}
								<span class="row">
									<span class="sub okline">Saved to your own word list</span>
									<button class="btn text sm" type="button" disabled={busy} onclick={() => undo(applied.opId)}>Undo</button>
								</span>
							{:else if undone.has(fix.key)}
								<span class="sub">Undone</span>
							{/if}
						</div>
					</li>
				{/each}
			</ul>
			<div class="acts">
				<button class="btn sec" type="button" disabled={busy || chosen.length === 0} onclick={apply}>
					Apply selected{chosen.length ? ` (${chosen.length})` : ''}
				</button>
			</div>
		{:else}
			<p class="note">
				<Icon name="info" class="i sm" />{result.diagnosis === 'uncertain'
					? 'VoiceFlow isn’t sure what went wrong, so nothing lasting is suggested.'
					: result.diagnosis === 'delivery-privacy'
						? 'Noted. Paste and privacy problems don’t change your word lists or styles.'
						: `No lasting fix suggested${result.noFixReason ? `: ${result.noFixReason}` : '.'}`}
			</p>
		{/if}
		{#each result.dropped as d, i (i)}
			<p class="note small"><Icon name="info" class="i sm" />{d.reason}</p>
		{/each}

		{#if error}
			<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
		{/if}
		{#if notice}<p class="note okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{notice}</p>{/if}
		<div class="acts">
			<button class="btn text" type="button" onclick={() => ((step = 'compose'), (error = null), (notice = null))}>
				Change feedback
			</button>
			<button class="btn text" type="button" onclick={onClose}>Close</button>
		</div>
	{/if}
</section>

<style>
	.fbx {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.fbx.scroll {
		max-height: min(70vh, 640px);
		overflow-y: auto;
	}
	form {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	label {
		font-size: 12px;
		font-weight: 600;
		color: var(--subtle-foreground);
		margin-top: 6px;
	}
	.diag {
		display: flex;
		gap: 8px;
		align-items: baseline;
		flex-wrap: wrap;
		margin: 0;
	}
	.sect {
		margin-top: 14px;
	}
	.fixes {
		list-style: none;
		margin: 4px 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.fixes li {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--muted);
	}
	.fixes li input[type='checkbox'] {
		margin-top: 3px;
		width: 16px;
		height: 16px;
	}
	.fx {
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
	}
	.fl {
		margin: 0;
		font-size: 14px;
		font-weight: 600;
		color: var(--foreground);
	}
	.scope :global(select) {
		height: 28px;
		padding: 0 8px;
		font-size: 13px;
	}
	.ex {
		font-size: 12px;
		color: var(--muted-foreground);
		overflow-wrap: anywhere;
	}
	.why {
		font-style: italic;
	}
	.row {
		display: flex;
		gap: 8px;
		align-items: center;
	}
	.warnline {
		color: var(--warning);
	}
	.okline {
		color: var(--success);
	}
	.small {
		font-size: 12px;
	}
</style>
