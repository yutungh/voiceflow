<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from './Icon.svelte';
	import Tile from './Tile.svelte';
	import type { FeedbackClient, FeedbackSetup } from '$lib/feedback/client';
	import { issueOf, issuesOf, LAYER_LABELS, noFixNote } from '$lib/feedback/engine';
	import type { FeedbackRecord } from '$lib/feedback/log';
	import type { Observation } from '$lib/feedback/stage-diff';
	import type { ReviewedFix, ScopeCheck, SpellingFix, WordFix } from '$lib/feedback/validate';
	import {
		ALL_SAVED,
		COPIED,
		COPY_LABEL,
		EMPTY_NEXT_TIME,
		EMPTY_NEXT_TIME_WHY,
		NOW_LINK,
		RESULT_HEADING,
		SECTION_NEXT,
		SECTION_NOW,
		SELECT_HINT,
		WHY_TITLE,
		fixLabel,
		newlySaved,
		nextTimeLink,
		nextTimeState,
		pasteHelp,
		pasteLabel,
		saveLabel,
		savedLine,
		savedReceipt,
		scopeText,
		undoneMessage,
		type Receipt,
	} from '$lib/feedback/result-view';

	/**
	 * VoiceFlow phase F (B3.1): feedback on one dictation, then its fix.
	 *
	 * - Compose: the feedback (editable), optionally what it should have
	 *   said, and, under Find fixes, the provider and model it goes to (the
	 *   one chosen in Settings > Feedback), with everything sent folded below.
	 *   "Save as draft" keeps it on this PC only.
	 * - Result (0.9.18): two separate, numbered sections, both named in a
	 *   summary at the top that jumps to each (`result-view.ts` holds the words).
	 *   1. Fix this text: the corrected text (editable) with "Copy corrected
	 *      text" and "Paste at cursor in <app>", and what Paste does.
	 *   2. Save changes for next time: the lasting changes, unchecked, with
	 *      their exact scope and what else they would change. "Save selected
	 *      changes" writes them and says what was saved; each has its own Undo.
	 *      With none, it says so plainly.
	 *   Why it happened (the explanation, each problem, what changed at each
	 *   step) is folded below both.
	 */
	let {
		client,
		recordingId,
		initialFeedback = '',
		initialRecord = null,
		heading = 'What should have been different?',
		popover = false,
		onClose,
		onStep,
	}: {
		client: FeedbackClient;
		recordingId: string;
		initialFeedback?: string;
		initialRecord?: FeedbackRecord | null;
		heading?: string;
		/** Shown as History's popover (scrolls inside it). */
		popover?: boolean;
		onClose: () => void;
		/** 0.9.18: tells the Feedback window which step shows (it shrinks its About card for the result). */
		onStep?: (step: 'compose' | 'sending' | 'result') => void;
	} = $props();

	let setup = $state<FeedbackSetup | null>(null);
	let feedback = $state(untrack(() => initialRecord?.feedback ?? initialFeedback));
	let wanted = $state(untrack(() => initialRecord?.wanted ?? ''));
	let showWanted = $state(untrack(() => !!initialRecord?.wanted));
	let record = $state<FeedbackRecord | null>(untrack(() => initialRecord));
	let step = $state<'compose' | 'sending' | 'result'>(untrack(() => (initialRecord?.result ? 'result' : 'compose')));
	/** Compose step, and anything not about one section. */
	let error = $state<string | null>(null);
	let notice = $state<string | null>(null);
	/** Section 1 (this text): Copy and Paste. */
	let nowNotice = $state<string | null>(null);
	let nowWarn = $state<string | null>(null);
	/** Section 2 (next time): Save and Undo. */
	let nextError = $state<string | null>(null);
	let nextNotice = $state<string | null>(null);
	let receipt = $state<Receipt | null>(null);
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
	$effect(() => {
		const now = step;
		untrack(() => onStep?.(now));
	});
	// VoiceFlow 0.9.17: back from Settings (a key added, another model chosen),
	// the line under Find fixes shows the model as it is now.
	$effect(() => {
		const onFocus = () => {
			if (step === 'compose') void refreshSetup();
		};
		window.addEventListener('focus', onFocus);
		return () => window.removeEventListener('focus', onFocus);
	});

	async function refreshSetup() {
		const reply = await client.setup(recordingId);
		if (reply.ok) setup = reply.setup;
	}

	async function openKeySettings() {
		if (!setup?.keyNeeded) return;
		const reply = await client.openSettings(setup.keyNeeded.section);
		if (!reply.ok) error = reply.message;
	}

	const result = $derived(record?.result ?? null);
	const issues = $derived(result ? issuesOf(result) : []);
	const appName = $derived(setup?.subject?.appName ?? null);
	const liveApplied = $derived(new Map((record?.applied ?? []).filter((a) => !a.undoneAt).map((a) => [a.key, a])));
	const undone = $derived(new Set((record?.applied ?? []).filter((a) => a.undoneAt).map((a) => a.key)));
	const chosen = $derived(
		(result?.fixes ?? []).filter((f) => checked[f.key] && !liveApplied.has(f.key) && usable(f)),
	);
	const observations = $derived(result?.observations ?? []);
	const next = $derived(nextTimeState(result?.fixes ?? [], record?.applied));

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
	function labelOf(fix: ReviewedFix): string {
		const applied = liveApplied.get(fix.key);
		return fixLabel(fix, applied?.scope ?? (fix.kind === 'word' ? scopeOf(fix) : undefined));
	}

	function clearMessages() {
		error = null;
		notice = null;
		nowNotice = null;
		nowWarn = null;
		nextError = null;
		nextNotice = null;
	}

	/** Moves to a section from the summary at the top. */
	function jump(id: string) {
		const target = document.getElementById(id);
		if (!target) return;
		target.scrollIntoView({ behavior: 'smooth', block: 'start' });
		target.querySelector<HTMLElement>('h2')?.focus({ preventScroll: true });
	}

	function openWanted() {
		showWanted = true;
		const delivered = setup?.subject?.deliveredText ?? '';
		// Prefilled to edit, unless longer than the box takes (it is never cut).
		if (!wanted.trim() && delivered.length <= 20000) wanted = delivered;
	}

	async function send(e?: SubmitEvent) {
		e?.preventDefault();
		clearMessages();
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
		if (reply.record) record = reply.record;
		if (!reply.ok) {
			error = reply.message;
			// Settings may have changed: show who it would go to now.
			await refreshSetup();
		}
		if (reply.ok && reply.record.result) {
			corrected = reply.record.result.correctedText;
			checked = {};
			scopes = {};
			receipt = null;
			step = 'result';
		} else step = 'compose';
	}

	async function saveDraft() {
		clearMessages();
		busy = true;
		const reply = await client.saveDraft({
			recordingId,
			feedback,
			...(showWanted && wanted.trim() ? { wanted } : {}),
			recordId: record?.id,
		});
		busy = false;
		if (reply.record) record = reply.record;
		if (reply.ok) notice = 'Saved on this PC as a draft. Nothing was sent.';
		else error = reply.message;
	}

	async function copy() {
		nowNotice = null;
		nowWarn = null;
		if (!record) return;
		const reply = await client.copy({ recordId: record.id, text: corrected });
		if (reply.record) record = reply.record;
		if (reply.ok) nowNotice = COPIED;
		else nowWarn = reply.message;
	}

	async function paste() {
		nowNotice = null;
		nowWarn = null;
		if (!record) return;
		busy = true;
		const reply = await client.paste({ recordId: record.id, text: corrected });
		busy = false;
		if (reply.record) record = reply.record;
		// Not pasted: usually copied instead, which the message says.
		if (reply.ok) nowNotice = reply.message;
		else nowWarn = reply.message;
	}

	async function apply() {
		nextError = null;
		nextNotice = null;
		if (!record || chosen.length === 0) return;
		const before = record;
		busy = true;
		const reply = await client.apply({
			recordId: record.id,
			selections: chosen.map((f) =>
				f.kind === 'spelling' || f.kind === 'word' ? { key: f.key, scope: scopeOf(f) } : { key: f.key },
			),
		});
		busy = false;
		if (reply.record) record = reply.record;
		// What this Save actually wrote, also when a later one failed.
		const saved = newlySaved(before, reply.record ?? null);
		receipt = savedReceipt(saved);
		for (const s of saved) checked[s.fix.key] = false;
		if (!reply.ok) {
			nextError = reply.message;
			if (reply.stale) checked = {};
		} else checked = {};
	}

	async function undo(opId: string) {
		nextError = null;
		nextNotice = null;
		if (!record) return;
		const entry = record.applied?.find((a) => a.opId === opId);
		const fix = entry ? record.result?.fixes.find((f) => f.key === entry.key) : undefined;
		busy = true;
		const reply = await client.undo({ recordId: record.id, opId });
		busy = false;
		if (reply.record) record = reply.record;
		if (!reply.ok) {
			nextError = reply.message;
			return;
		}
		receipt = null;
		nextNotice =
			reply.message ?? undoneMessage(fix ? { fix, ...(entry?.scope ? { scope: entry.scope } : {}) } : null);
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
	const q = (s: string) => `“${s}”`;
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
	<div class="pop-h"><Tile icon="chat-circle" /><b>{step === 'result' ? RESULT_HEADING : heading}</b></div>

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
					maxlength={20000}
					disabled={step === 'sending'}
				></textarea>
				<span class="sub small">Edit it into what you wanted. VoiceLnq takes this as the right text.</span>
			{:else if setup?.subject?.deliveredText}
				<button class="btn text sm start" type="button" onclick={openWanted}>
					<Icon name="pencil-simple" class="i sm" />Add what it should have said
				</button>
			{/if}
			{#if error}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
			{/if}
			{#if notice}<p class="note okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{notice}</p>{/if}
			<div class="acts">
				<button
					class="btn pri"
					type="submit"
					aria-describedby="fbx-sends"
					disabled={step === 'sending' || !setup?.ready || !setup?.subject}
				>
					{#if step === 'sending'}Finding fixes…{:else}Find fixes{/if}
				</button>
				<button class="btn sec" type="button" disabled={busy || step === 'sending'} onclick={saveDraft}>Save as draft</button>
				<button class="btn text" type="button" onclick={onClose}>Close</button>
			</div>
			{#if setup?.ready}
				<p class="note sends" id="fbx-sends"><Icon name="info" class="i sm" />{setup.disclosure}</p>
				{#if setup.details}
					<details class="sent-detail">
						<summary>Everything that's sent</summary>
						<p class="sub small">{setup.details}</p>
					</details>
				{/if}
			{:else if setup?.keyNeeded}
				<div class="note warnline keyline" id="fbx-sends" role="status">
					<Icon name="warning" class="i sm" />
					<span>
						{setup.keyNeeded.message} You can still save this as a draft.
						<button class="btn text sm inl" type="button" onclick={openKeySettings}>Open Settings</button>
					</span>
				</div>
			{/if}
		</form>
	{:else if result}
		<!-- 0.9.18: both jobs named up front; each link jumps to its section. -->
		<nav class="jobs" aria-label="The two parts of the result">
			<button class="jl" type="button" onclick={() => jump('fbx-now')}>
				<Icon name="cursor-text" class="i sm" />{NOW_LINK}
			</button>
			<button class="jl {next.kind}" type="button" data-next={next.kind} onclick={() => jump('fbx-next')}>
				<Icon name={next.kind === 'all-saved' ? 'check-circle' : 'arrows-clockwise'} class="i sm" />{nextTimeLink(next)}
			</button>
		</nav>

		<section class="job" id="fbx-now" aria-labelledby="fbx-now-h">
			<h2 id="fbx-now-h" tabindex="-1">{SECTION_NOW.title}</h2>
			<p class="scope-line">{SECTION_NOW.scope}</p>
			<label for="fbx-corrected">Corrected text</label>
			<textarea id="fbx-corrected" class="inp area" rows="4" bind:value={corrected}></textarea>
			{#if result.suggestedText !== undefined}
				<span class="sub small">This is what you said it should have said.</span>
			{/if}
			<div class="acts">
				<button class="btn pri" type="button" disabled={!corrected.trim()} onclick={copy}>
					<Icon name="copy" class="i sm" />{COPY_LABEL}
				</button>
				{#if setup?.subject?.canPaste}
					<button
						class="btn sec"
						type="button"
						aria-describedby="fbx-paste-help"
						disabled={busy || !corrected.trim()}
						onclick={paste}
					>
						{pasteLabel(appName)}
					</button>
				{/if}
			</div>
			{#if setup?.subject?.canPaste}
				<p class="note small" id="fbx-paste-help"><Icon name="info" class="i sm" />{pasteHelp(appName)}</p>
			{/if}
			{#if nowWarn}<p class="note warnline" role="status"><Icon name="warning" class="i sm" />{nowWarn}</p>{/if}
			{#if nowNotice}<p class="note okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{nowNotice}</p>{/if}
		</section>

		<section class="job later" id="fbx-next" aria-labelledby="fbx-next-h">
			<h2 id="fbx-next-h" tabindex="-1">
				{SECTION_NEXT.title}{#if next.kind === 'pending'}<span class="cnt">{next.toSave}</span>{/if}
			</h2>
			<p class="scope-line">{SECTION_NEXT.scope}</p>
			{#if next.kind === 'none'}
				<div class="empty" data-empty="next-time">
					<Icon name="info" class="i sm" />
					<span><b>{EMPTY_NEXT_TIME}</b> <span class="sub">{EMPTY_NEXT_TIME_WHY}</span></span>
				</div>
			{:else}
				{#if next.kind === 'pending'}<p class="sub small hint">{SELECT_HINT}</p>{/if}
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
								<label for="fix-{fix.key}" class="fl">{labelOf(fix)}</label>
								{#if fix.kind === 'spelling'}
									<span class="scope">
										{#if applied}
											{scopeText(fix, applied.scope)}
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
											in every app (VoiceLnq doesn’t know this dictation’s app)
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
											{scopeText(fix, applied?.scope ?? scopeOf(fix))}
										{/if}
									</span>
									{#if fix.adjusted}<span class="sub">{fix.adjusted}</span>{/if}
									{#if !applied}
										{#each wordEffects(fix) as line (line)}<span class="sub">{line}</span>{/each}
									{/if}
								{:else if fix.kind === 'unspell'}
									<span class="sub">Saved spellings you made can be turned off; Undo turns it back on.</span>
								{:else}
									<span class="sub">From your next dictation in {fix.appName ?? fix.app}.{fix.replaces ? ` ${fix.replaces}` : ''}</span>
								{/if}
								<span class="sub why">{fix.reason}</span>
								{#if applied}
									<span class="row">
										<span class="sub okline">{savedLine(fix)}</span>
										<button class="btn text sm" type="button" disabled={busy} onclick={() => undo(applied.opId)}>Undo</button>
									</span>
								{:else if undone.has(fix.key)}
									<span class="sub">Undone</span>
								{/if}
							</div>
						</li>
					{/each}
				</ul>
				{#if next.kind === 'pending'}
					<div class="acts">
						<button class="btn pri" type="button" disabled={busy || chosen.length === 0} onclick={apply}>
							{saveLabel(chosen.length)}
						</button>
					</div>
				{:else if !receipt}
					<p class="note okline"><Icon name="check-circle" weight="fill" class="i sm" />{ALL_SAVED}</p>
				{/if}
			{/if}
			{#each result.dropped as d, i (i)}
				<p class="note small"><Icon name="info" class="i sm" />{d.reason}</p>
			{/each}
			{#if receipt}
				<div class="receipt" role="status" data-receipt>
					<p><Icon name="check-circle" weight="fill" class="i sm" /><b>{receipt.head}</b></p>
					<ul>
						{#each receipt.lines as line (line)}<li>{line}</li>{/each}
					</ul>
					<p class="sub">{receipt.tail}</p>
				</div>
			{/if}
			{#if nextError}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{nextError}</p>
			{/if}
			{#if nextNotice}<p class="note okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />{nextNotice}</p>{/if}
		</section>

		<details class="ldet why-fold">
			<summary>{WHY_TITLE}</summary>
			{#if result.explanation}<p class="diag">{result.explanation}</p>{/if}
			{#each issues as issue, n (n)}
				{@const own = result.fixes.filter((f) => issueOf(f) === n)}
				<div class="issue">
					<p class="ih">
						<span class="chip {issue.layer === 'uncertain' ? 'warn' : 'ok'}">{LAYER_LABELS[issue.layer]}</span>
						{#if issues.length > 1 || issue.summary !== result.explanation}<span>{issue.summary}</span>{/if}
					</p>
					{#if own.length === 0}
						<p class="note small"><Icon name="info" class="i sm" />{noFixNote(issue)}</p>
					{/if}
				</div>
			{/each}
			{#if observations.length > 0}
				<span class="lab">What changed at each step ({observations.length})</span>
				<ul class="steps">
					{#each observations as o, i (i)}
						<li><span class="stage">{STAGE_NAMES[o.stage]}</span> {observationText(o)}</li>
					{/each}
				</ul>
				{#if result.observationsTruncated}<p class="sub small">Some changes aren’t listed (a long dictation).</p>{/if}
			{/if}
		</details>

		{#if error}
			<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
		{/if}
		<div class="acts">
			<button class="btn text" type="button" onclick={() => ((step = 'compose'), clearMessages(), (receipt = null))}>
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
	/* 0.9.18: the summary of both jobs, at the top of the result. */
	.jobs {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin-bottom: 6px;
	}
	.jl {
		all: unset;
		box-sizing: border-box;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 5px 12px;
		border-radius: 999px;
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		color: var(--muted-foreground);
		box-shadow: inset 0 0 0 1px var(--input);
		background: var(--card);
	}
	.jl:hover {
		background: var(--muted);
	}
	.jl:focus-visible {
		outline: 2px solid var(--ring);
		outline-offset: 2px;
	}
	.jl.pending {
		background: var(--primary-tint);
		color: var(--primary-ink);
		box-shadow: inset 0 0 0 1px var(--primary-tint-border);
		font-weight: 600;
	}
	.jl.all-saved {
		color: var(--success);
	}
	/* Each job is its own section, numbered in its title. */
	.job {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 14px 16px;
		border-radius: 14px;
		background: var(--card);
		box-shadow: var(--shadow-card);
		scroll-margin-top: 8px;
	}
	.fbx.scroll .job {
		box-shadow: inset 0 0 0 1px var(--border);
	}
	.job + .job {
		margin-top: 10px;
	}
	.job.later {
		box-shadow:
			inset 3px 0 0 var(--primary-tint-border),
			var(--shadow-card);
	}
	.fbx.scroll .job.later {
		box-shadow:
			inset 3px 0 0 var(--primary-tint-border),
			inset 0 0 0 1px var(--border);
	}
	.job h2 {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
		font-size: 15px;
		font-weight: 600;
		letter-spacing: -0.005em;
		outline: none;
	}
	.job h2 .cnt {
		font-size: 12px;
		font-weight: 600;
		color: var(--primary-ink);
		background: var(--primary-tint);
		border-radius: 999px;
		padding: 1px 8px;
		font-variant-numeric: tabular-nums;
	}
	.scope-line {
		margin: 0;
		font-size: 13px;
		color: var(--muted-foreground);
	}
	.job .acts {
		margin-top: 8px;
	}
	.hint {
		margin: 6px 0 0;
	}
	.empty {
		display: flex;
		gap: 8px;
		align-items: flex-start;
		margin-top: 8px;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--muted);
		font-size: 13px;
	}
	.empty :global(.i) {
		margin-top: 2px;
		color: var(--subtle-foreground);
	}
	.receipt {
		margin-top: 10px;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--tile-ok);
		font-size: 13px;
	}
	.receipt p {
		margin: 0;
		display: flex;
		gap: 6px;
		align-items: center;
	}
	.receipt p:first-child {
		color: var(--success);
	}
	.receipt ul {
		margin: 4px 0;
		padding-left: 24px;
		overflow-wrap: anywhere;
	}
	.receipt .sub {
		display: block;
	}
	.diag {
		margin: 0 0 6px;
		font-size: 13.5px;
		color: var(--foreground);
	}
	.fixes {
		list-style: none;
		margin: 8px 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.fixes li {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		width: 100%;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--muted);
	}
	.fixes li input[type='checkbox'] {
		margin-top: 3px;
		width: 16px;
		height: 16px;
		flex: none;
	}
	.tick {
		color: var(--success);
		flex: none;
	}
	.fx {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 3px;
		min-width: 0;
		flex: 1;
	}
	.fl {
		margin: 0;
		font-size: 14px;
		font-weight: 600;
		color: var(--foreground);
		overflow-wrap: anywhere;
	}
	.scope {
		font-size: 13px;
		color: var(--muted-foreground);
	}
	.scope :global(select) {
		height: 28px;
		width: auto;
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
		gap: 4px;
		margin-top: 8px;
	}
	.ih {
		display: flex;
		gap: 8px;
		align-items: baseline;
		flex-wrap: wrap;
		margin: 0;
	}
	.ldet.why-fold {
		margin-top: 14px;
	}
	.steps {
		margin: 4px 0 0;
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
	.sends {
		margin-top: 2px;
	}
	.sent-detail {
		font-size: 12px;
	}
	.sent-detail summary {
		cursor: pointer;
		color: var(--subtle-foreground);
		font-size: 12px;
	}
	.sent-detail p {
		margin: 4px 0 0;
	}
	.keyline {
		display: flex;
		gap: 6px;
		align-items: flex-start;
	}
	.keyline :global(.i) {
		flex: none;
		margin-top: 2px;
	}
	.inl {
		display: inline;
		padding: 0 4px;
		height: auto;
		text-decoration: underline;
	}
</style>
