<script lang="ts">
	import { createQuery } from '@tanstack/svelte-query';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { rpc } from '$lib/query';
	import { secretStoreLabel } from '$lib/services/desktop/secrets';
	import { asDeviceIdentifier } from '$lib/services/types';
	import { settings } from '$lib/stores/settings.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import Illo from '$lib/vf/Illo.svelte';
	import Kc from '$lib/vf/Kc.svelte';
	import Tile from '$lib/vf/Tile.svelte';
	import { createdAt, finalTextOf, wordCount } from '$lib/vf/dictations';
	import { markFirstRunDone } from '$lib/vf/first-run';
	import MicMeter from '$lib/vf/settings/MicMeter.svelte';

	/**
	 * B3.1 first run: three steps. 1: a microphone test. 2: one real test
	 * dictation with the start-or-stop keys, read from the box. 3: you're
	 * ready, with the hold-to-talk tip. Shown once, on a fresh install.
	 */
	const SENTENCE = 'Hello VoiceFlow, this is my first dictation.';

	const initial = Number(page.url.searchParams.get('step') ?? '1');
	let step = $state(initial >= 1 && initial <= 3 ? initial : 1);
	let heard = $state(false);
	let micError = $state<string | null>(null);
	let startedAt = $state(Date.now());

	const toggle = $derived(settings.value['shortcuts.global.toggleManualRecording']);
	const hold = $derived(settings.value['shortcuts.global.pushToTalk']);
	const devicesQuery = createQuery(() => rpc.recorder.enumerateDevices.options);
	const method = $derived(settings.value['recording.method']);
	const deviceKey = $derived(`recording.${method}.deviceId` as const);

	const recorderQuery = createQuery(() => rpc.recorder.getRecorderState.options);
	const recordingsQuery = createQuery(() => rpc.db.recordings.getAll.options);
	/** The test dictation: the first one made since step 2 started. */
	const test = $derived(
		(recordingsQuery.data ?? [])
			.filter((r) => createdAt(r).getTime() >= startedAt - 1000)
			.sort((a, b) => createdAt(b).getTime() - createdAt(a).getTime())[0],
	);
	const testText = $derived(test ? finalTextOf(test) : '');
	const listening = $derived(recorderQuery.data === 'RECORDING');

	const hasKey = $derived(!!settings.value['apiKeys.openai']);
	let key = $state('');
	function saveKey(e: SubmitEvent) {
		e.preventDefault();
		if (key.trim()) settings.updateKey('apiKeys.openai', key.trim());
		key = '';
	}

	function go(next: number) {
		step = next;
		if (next === 2) startedAt = Date.now();
		goto(`/welcome?step=${next}`, { replaceState: true, keepFocus: true, noScroll: true });
	}

	function finish() {
		markFirstRunDone();
		goto('/', { replaceState: true });
	}

	$effect(() => {
		if (step === 2 && testText) go(3);
	});

	function onKey(e: KeyboardEvent) {
		const el = e.target as HTMLElement | null;
		if (el && ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) return;
		if (e.key !== 'Enter') return;
		if (step === 1) go(2);
		else if (step === 3) finish();
	}
</script>

<svelte:head>
	<title>Set up VoiceFlow</title>
</svelte:head>

<svelte:window onkeydown={onKey} />

<div class="vf welcome"><div class="fr">
	<div class="fr-in">
		<div class="stepi">
			<span class="stepl num">Step {step} of 3</span>
			<span class="dots" aria-hidden="true">
				{#each [1, 2, 3] as k}<i class={k === step ? 'on' : k < step ? 'done' : ''}></i>{/each}
			</span>
		</div>

		{#if step === 1}
			<Illo name="mic" size={120} />
			<h1>Talk in any app. VoiceFlow types it.</h1>
			<p class="lead">One quick test and you’re done. It takes about a minute.</p>
			<div class="frbox">
				<div class="srow">
					<div>
						<b>Microphone</b>
						<span>Say anything to test your microphone. The bars react when it hears you.</span>
					</div>
					<select
						class="sel"
						aria-label="Microphone"
						value={settings.value[deviceKey] ?? ''}
						onchange={(e) =>
							settings.updateKey(
								deviceKey,
								e.currentTarget.value ? asDeviceIdentifier(e.currentTarget.value) : null,
							)}
					>
						<option value="">Windows default</option>
						{#each devicesQuery.data ?? [] as device (device.id)}
							<option value={device.id}>{device.label}</option>
						{/each}
					</select>
				</div>
				<div class="meter">
					<MicMeter active={step === 1} bars={46} height={26} big onHeard={() => (heard = true)} onError={(m) => (micError = m)} />
				</div>
				{#if micError}
					<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{micError}</p>
				{:else if heard}
					<p class="okline" role="status"><Icon name="check-circle" weight="fill" class="i sm" />Sounds good</p>
				{:else}
					<p class="sub small">Waiting to hear you…</p>
				{/if}
			</div>
		{:else if step === 2}
			<Illo name="keys" size={120} />
			<h1>Say one sentence</h1>
			<p class="lead">
				Press <Kc shortcut={toggle} />, read the sentence in the box aloud, then press
				<Kc shortcut={toggle} /> again.
			</p>
			{#if !hasKey}
				<form class="frbox keyask" onsubmit={saveKey}>
					<p><b>First, add your OpenAI key.</b> VoiceFlow uses it to turn speech into text. It is stored in {secretStoreLabel(
							settings.apiKeyStoreKind === 'loading' ? 'windows' : settings.apiKeyStoreKind,
						)}.</p>
					<div class="keyform">
						<input class="inp" type="password" bind:value={key} placeholder="sk-…" aria-label="OpenAI key" autocomplete="off" />
						<button class="btn pri" type="submit">Save key</button>
					</div>
				</form>
			{/if}
			<div class="tbox guide" aria-live="polite">
				<span class="g-l">Read this aloud</span>
				<span class="g-s">“{SENTENCE}”</span>
				<span class="g-c">
					{#if listening}
						<span class="rec-dot" aria-hidden="true"></span><span>Listening… press <Kc shortcut={toggle} /> again when you’re done</span>
					{:else if test && !testText}
						<span class="ph">Turning your words into text…</span>
					{:else}
						<i class="caret"></i><span class="ph">Your words will appear here</span>
					{/if}
				</span>
			</div>
		{:else}
			<Illo name="done" size={120} />
			<h1>That’s it. You’re ready.</h1>
			{#if test && testText}
				<p class="lead">
					Your first dictation: <b class="num"
						>{wordCount(testText)} words{#if test.durationMs}{' '}in {(test.durationMs / 1000).toFixed(1)} seconds{/if}</b
					>.
				</p>
				<div class="tbox done"><span>{testText}</span></div>
			{:else}
				<p class="lead">Your dictations will show up on Home.</p>
			{/if}
			<div class="next">
				<Tile icon="waveform" />
				<div>
					<b>From now on</b>
					<span>Click where you want to type, in any app, and press <Kc shortcut={toggle} />. Problems show on Home.</span>
				</div>
			</div>
			{#if hold}
				<div class="next">
					<Tile icon="hand" />
					<div>
						<b>Tip: hold to talk</b>
						<span>You can also hold <Kc shortcut={hold} /> while you talk, and let go to paste.</span>
					</div>
				</div>
			{/if}
		{/if}
	</div>

	<div class="fr-foot">
		{#if step === 1}
			<button class="btn text" type="button" onclick={finish}>Skip for now</button>
			<button class="btn pri" type="button" onclick={() => go(2)}><span>Start the test</span><Kc keys={['Enter']} /></button>
		{:else if step === 2}
			<button class="btn text" type="button" onclick={() => go(1)}>Back</button>
			<button class="btn text" type="button" onclick={finish}>Skip for now</button>
		{:else}
			<span></span>
			<button class="btn pri" type="button" onclick={finish}><span>Start using VoiceFlow</span><Kc keys={['Enter']} /></button>
		{/if}
	</div>
</div>
</div>
