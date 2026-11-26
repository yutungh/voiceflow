<script lang="ts">
	import { Switch } from '@epicenter/ui/switch';
	import {
		cueOn as cueIsOn,
		EVENTS_OF_CUE,
		SOUND_SET_CHOICES,
		SOUND_SET_LABELS,
		type SoundCue,
		type SoundSetChoice,
	} from '$lib/constants/sounds';
	import { rpc } from '$lib/query';
	import { settings } from '$lib/stores/settings.svelte';
	import Icon from '$lib/vf/Icon.svelte';
	import Tile from '$lib/vf/Tile.svelte';

	/**
	 * VoiceFlow: Settings > Sounds. The set (Glass, Soft, Minimal or Off), a
	 * volume, and one switch per moment with a play button to hear it.
	 */

	const ROWS: { cue: SoundCue; label: string; help: string }[] = [
		{ cue: 'start', label: 'Start', help: 'The microphone is on' },
		{ cue: 'stop', label: 'Stop', help: 'The microphone is off' },
		{ cue: 'done', label: 'Done', help: 'Your words are pasted' },
		{ cue: 'error', label: 'Problem', help: 'A dictation failed or was not allowed' },
		{ cue: 'cancel', label: 'Cancel', help: 'You cancelled a dictation' },
	];

	const set = $derived(settings.value['sound.set']);
	const volume = $derived(settings.value['sound.volume']);
	const off = $derived(set === 'off');

	function cueOn(cue: SoundCue) {
		return cueIsOn(cue, settings.value);
	}
	function setCue(cue: SoundCue, on: boolean) {
		settings.update(
			Object.fromEntries(EVENTS_OF_CUE[cue].map((name) => [`sound.playOn.${name}`, on])),
		);
		if (on) preview(cue);
	}
	function preview(cue: SoundCue) {
		if (set === 'off') return;
		void rpc.sound.preview.execute({ set, cue });
	}
	function chooseSet(choice: SoundSetChoice) {
		settings.updateKey('sound.set', choice);
		if (choice !== 'off') void rpc.sound.preview.execute({ set: choice, cue: 'done' });
	}
	let volumeTimer: ReturnType<typeof setTimeout> | undefined;
	function setVolume(value: number) {
		settings.updateKey('sound.volume', Math.min(1, Math.max(0, value)));
		// Let the slider settle, then play one cue at the new level.
		clearTimeout(volumeTimer);
		volumeTimer = setTimeout(() => preview('start'), 250);
	}
</script>

<section class="card sp" id="sounds" aria-labelledby="h-sound">
	<div class="c-h"><Tile icon="speaker-high" /><h2 id="h-sound">Sounds</h2></div>
	<div class="srow">
		<div><b>Sound set</b><span>Made for VoiceLnq</span></div>
		<span class="seg" role="radiogroup" aria-label="Sound set">
			{#each SOUND_SET_CHOICES as choice (choice)}
				<button
					type="button"
					role="radio"
					aria-checked={set === choice}
					class:on={set === choice}
					onclick={() => chooseSet(choice)}>{SOUND_SET_LABELS[choice]}</button
				>
			{/each}
		</span>
	</div>
	{#if !off}
		<div class="srow">
			<div><b>Volume</b><span>For VoiceLnq's sounds only</span></div>
			<span class="vol">
				<input
					type="range"
					min="0"
					max="1"
					step="0.05"
					value={volume}
					aria-label="Sound volume"
					oninput={(e) => setVolume(Number(e.currentTarget.value))}
				/>
				<span class="pct">{Math.round(volume * 100)}%</span>
			</span>
		</div>
		{#each ROWS as row (row.cue)}
			<div class="srow">
				<div><b>{row.label}</b><span>{row.help}</span></div>
				<button
					type="button"
					class="play"
					aria-label="Play the {row.label.toLowerCase()} sound"
					title="Play"
					onclick={() => preview(row.cue)}
				>
					<Icon name="play" weight="fill" class="i sm" />
				</button>
				<Switch
					checked={cueOn(row.cue)}
					onCheckedChange={(v) => setCue(row.cue, v)}
					aria-label="{row.label} sound"
				/>
			</div>
		{/each}
	{:else}
		<p class="sub small">VoiceLnq makes no sounds.</p>
	{/if}
</section>

<style>
	.vol {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		flex: none;
	}
	.vol input[type='range'] {
		width: 160px;
		accent-color: var(--primary);
		cursor: pointer;
	}
	.pct {
		width: 40px;
		text-align: right;
		font-variant-numeric: tabular-nums;
		color: var(--muted-foreground);
		font-size: 13px;
	}
	.play {
		all: unset;
		box-sizing: border-box;
		display: inline-grid;
		place-items: center;
		flex: none;
		width: 32px;
		height: 32px;
		border-radius: 8px;
		color: var(--muted-foreground);
		cursor: pointer;
	}
	.play:hover {
		background: var(--muted);
		color: var(--foreground);
	}
	.play:focus-visible {
		outline: 2px solid var(--ring);
	}
</style>
