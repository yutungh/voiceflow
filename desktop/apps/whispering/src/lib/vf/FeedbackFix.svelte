<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from './Icon.svelte';
	import Tile from './Tile.svelte';
	import type { FeedbackClient, FeedbackSetup } from '$lib/feedback/client';
	import { issueOf, issuesOf, LAYER_LABELS, noFixNote } from '$lib/feedback/engine';
	import type { FeedbackRecord } from '$lib/feedback/log';
	import type { Observation } from '$lib/feedback/stage-diff';
	import type { ReviewedFix, ScopeCheck, SpellingFix, WordFix } from '$lib/feedback/validate';

	/**
	 * VoiceFlow phase F (B3.1): feedback on one dictation, then its fix.
	 *
	 * - Compose: the feedback (editable), optionally what it should have
	 *   said, and, next to Send, exactly who it goes to and what is sent.
	 *   "Save as draft" keeps it on this PC only.
	 * - Result: the corrected text (editable) with Copy (the default) and
	 *   Paste into <app>; what changed at each step; then each problem with
	 *   the layer that failed and its own lasting fixes, unchecked, with
	 *   their exact scope and what else they would change. Apply selected
	 *   writes them; each has its own Undo.
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
	let wanted = $state(untrack(() => initialRecord?.wanted ?? ''));
	let showWanted = $state(untrack(() => !!initialRecord?.wanted));
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
	const issues = $derived(result ? issuesOf(result) : []);
	const appName = $derived(setup?.subject?.appName ?? null);
	const liveApplied = $derived(new Map((record?.applied ?? []).filter((a) => !a.undoneAt).map((a) => [a.key, a])));
	const undone = $derived(new Set((record?.applied ?? []).filter((a) => a.undoneAt).map((a) => a.key)));
	const chosen = $derived(
		(result?.fixes ?? []).filter((f) => checked[f.key] && !liveApplied.has(f.key) && usable(f)),
	);
	const observations = $derived(result?.observations ?? []);

	function scopeOf(fix: SpellingFix | WordFix): 'app' | 'everywhere' {
		return scopes[fix.key] ?? fix.defaultScope;
	}
	function checkOf(fix: SpellingFix): ScopeCheck | null {
		return scopeOf(fix) === 'app' ? fix.checks.app : fix.checks.everywhere;
	}
	function usable(fix: ReviewedFix): boolean {
		if (fix.kind === 'spelling') return !!checkOf(fix)?.ok;
		if (fix.kind === 'word') return !!fix.choices[scopeOf(fix)];
		return true;
	}

	function take(reply: { ok: boolean; message?: string; record?: FeedbackRecord; stale?: boolean }) {
		if (reply.record) record = reply.record;
		if (!reply.ok) error = reply.message ?? 'Something went wrong.';
	}

	function openWanted() {
		showWanted = true;
		if (!wanted.trim()) wanted = setup?.subject?.deliveredText ?? '';
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
			...(showWanted && wanted.trim() ? { wanted } : {}),
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
		const reply = await client.saveDraft({
			recordingId,
			feedback,
			...(showWanted && wanted.trim() ? { wanted } : {}),
			recordId: record?.id,
		});
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
			selections: chosen.map((f) =>
				f.kind === 'spelling' || f.kind === 'word' ? { key: f.key, scope: scopeOf(f) } : { key: f.key },
			),
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
	function wordLabel(fix: WordFix): string {
		const choice = fix.choices[scopeOf(fix)] ?? Object.values(fix.choices)[0];
		const added = choice?.added ?? [];
		if (fix.step === 'heard_as') return `Note that ${added.map(q).join(' or ')} is how ${q(fix.word)} comes out`;
		if (fix.step === 'add_word')
			return `Add ${q(fix.word)} to your words${added.length ? ` (heard as ${added.map(q).join(' or ')})` : ''}`;
		return `Give ${q(fix.word)} to the transcriber${fix.appName ? ` in ${fix.appName}` : ''}`;
	}
	function wordEffects(fix: WordFix): string[] {
		const choice = fix.choices[scopeOf(fix)];
		if (!choice) return [];
		const lines: string[] = [];
		const { before, after, dropsOut } = choice.effect;
		const where = fix.appName ? ` in ${fix.appName}` : '';
		if (choice.priority) lines.push(`Priority ${choice.priority.from} → ${choice.priority.to}.`);
		if (choice.widened) lines.push(`It starts applying${where} too.`);
		if (after.transcriber && !before.transcriber)
			lines.push(`It joins the 20 words the transcriber is given${where}.`);
		else if (after.tidy && !before.tidy) lines.push(`It joins the tidy-up’s word list${where}.`);
		else if (!after.tidy) lines.push(`Your word list is full${where}, so the tidy-up won’t see it yet.`);
		if (dropsOut.length)
			lines.push(
				`${dropsOut.map(q).join(', ')} ${dropsOut.length === 1 ? 'drops' : 'drop'} out of the transcriber’s 20 (still in the tidy-up’s list).`,
			);
		if (choice.replaces && choice.replaces.layer !== 'edit')
			lines.push('Your listed entry stays underneath; Undo brings it back.');
		return lines;
	}
	const STAGE_NAMES: Record<Observation['stage'], string> = {
		'tidy-up': 'Tidy-up',
		spelling: 'Spelling',
		'tidy-up-or-spelling': 'Tidy-up or spelling',
		wanted: 'You wanted',
	};
	const ORIGIN_NAMES: Record<NonNullable<Observation['origin']>, string> = {
		'speech-to-text': 'as speech-to-text wrote it',
		'tidy-up': 'from tidy-up',
		spelling: 'from a saved spelling',
		ambiguous: 'source unclear',
	};
	function observationText(o: Observation): string {
		const change =
			o.change === 'removed' ? `removed ${q(o.before)}` : o.change === 'added' ? `added ${q(o.after)}` : `${q(o.before)} → ${q(o.after)}`;
		return o.origin ? `${change} (${ORIGIN_NAMES[o.origin]})` : change;
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
				placeholder="For example: you wrote Zebbo, it's Zibbo. And it was too formal."
			></textarea>
			{#if showWanted}
				<label for="fbx-wanted">What it should have said (optional)</label>
				<textarea
					id="fbx-wanted"
					class="inp area"
					rows="3"
					bind:value={wanted}
					disabled={step === 'sending'}
				></textarea>
				<span class="sub small">Edit it into what you wanted. VoiceFlow takes this as the right text.</span>
			{:else if setup?.subject?.deliveredText}
				<button class="btn text sm start" type="button" onclick={openWanted}>
					<Icon name="pencil-simple" class="i sm" />Add what it should have said
				</button>
			{/if}
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
		{#if result.explanation}<p class="diag">{result.explanation}</p>{/if}

		<label for="fbx-corrected">Corrected text</label>
		<textarea id="fbx-corrected" class="inp area" rows="4" bind:value={corrected}></textarea>
		{#if result.suggestedText !== undefined}
			<span class="sub small">This is what you said it should have said.</span>
		{/if}
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

		{#if observations.length > 0}
			<details class="steps">
				<summary>What changed at each step ({observations.length})</summary>
				<ul>
					{#each observations as o, i (i)}
						<li><span class="stage">{STAGE_NAMES[o.stage]}</span> {observationText(o)}</li>
					{/each}
				</ul>
				{#if result.observationsTruncated}<p class="sub small">Some changes aren’t listed (a long dictation).</p>{/if}
			</details>
		{/if}

		{#each issues as issue, n (n)}
			{@const own = result.fixes.filter((f) => issueOf(f) === n)}
			{@const notes = result.dropped.filter((d) => d.issue !== undefined && d.issue === n)}
			<div class="issue">
				<p class="ih">
					<span class="chip {issue.layer === 'uncertain' ? 'warn' : 'ok'}">{LAYER_LABELS[issue.layer]}</span>
					{#if issues.length > 1 || issue.summary !== result.explanation}<span>{issue.summary}</span>{/if}
				</p>
				{#if own.length > 0}
					<ul class="fixes">
						{#each own as fix (fix.key)}
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
										{#if fix.requires?.length && !applied}
											<span class="sub">It replaces a spelling that is on now: tick “Turn off” for that one too.</span>
										{/if}
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
									{:else if fix.kind === 'word'}
										<label for="fix-{fix.key}" class="fl">{wordLabel(fix)}</label>
										<span class="scope">
											{#if !applied && fix.choices.app && fix.choices.everywhere}
												<select
													class="inp sm"
													aria-label="Where this word applies"
													value={scopeOf(fix)}
													onchange={(e) => (scopes[fix.key] = e.currentTarget.value as 'app' | 'everywhere')}
												>
													<option value="app">in {fix.appName ?? 'this app'} only</option>
													<option value="everywhere">in every app</option>
												</select>
											{:else}
												{(applied?.scope ?? scopeOf(fix)) === 'app' ? `in ${fix.appName ?? 'this app'}` : 'in every app'}
											{/if}
										</span>
										{#if fix.adjusted}<span class="sub">{fix.adjusted}</span>{/if}
										{#if !applied}
											{#each wordEffects(fix) as line (line)}<span class="sub">{line}</span>{/each}
										{/if}
									{:else if fix.kind === 'unspell'}
										<label for="fix-{fix.key}" class="fl">{fix.label}</label>
										<span class="sub">Saved spellings you made can be turned off; Undo turns it back on.</span>
									{:else}
										<label for="fix-{fix.key}" class="fl">{fix.label}</label>
										<span class="sub">From your next dictation in {fix.appName ?? fix.app}.{fix.replaces ? ` ${fix.replaces}` : ''}</span>
									{/if}
									<span class="sub why">{fix.reason}</span>
									{#if applied}
										<span class="row">
											<span class="sub okline">{fix.kind === 'unspell' ? 'Turned off' : 'Saved to your own word list'}</span>
											<button class="btn text sm" type="button" disabled={busy} onclick={() => undo(applied.opId)}>Undo</button>
										</span>
									{:else if undone.has(fix.key)}
										<span class="sub">Undone</span>
									{/if}
								</div>
							</li>
						{/each}
					</ul>
				{:else}
					<p class="note"><Icon name="info" class="i sm" />{noFixNote(issue)}</p>
				{/if}
				{#each notes as d, i (i)}
					<p class="note small"><Icon name="info" class="i sm" />{d.reason}</p>
				{/each}
			</div>
		{/each}

		{#if result.fixes.length > 0}
			<div class="acts">
				<button class="btn sec" type="button" disabled={busy || chosen.length === 0} onclick={apply}>
					Apply selected{chosen.length ? ` (${chosen.length})` : ''}
				</button>
			</div>
		{/if}
		{#each result.dropped.filter((d) => d.issue === undefined) as d, i (i)}
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
	.issue {
		display: flex;
		flex-direction: column;
		gap: 6px;
		margin-top: 12px;
	}
	.ih {
		display: flex;
		gap: 8px;
		align-items: baseline;
		flex-wrap: wrap;
		margin: 0;
	}
	.steps {
		margin-top: 10px;
		font-size: 13px;
	}
	.steps summary {
		cursor: pointer;
		color: var(--subtle-foreground);
		font-weight: 600;
		font-size: 12px;
	}
	.steps ul {
		margin: 6px 0 0;
		padding-left: 18px;
		display: flex;
		flex-direction: column;
		gap: 2px;
		overflow-wrap: anywhere;
	}
	.stage {
		font-weight: 600;
	}
	.start {
		align-self: flex-start;
	}
</style>
