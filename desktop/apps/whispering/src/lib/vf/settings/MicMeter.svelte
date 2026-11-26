<script lang="ts">
	import { onDestroy } from 'svelte';

	/**
	 * A live microphone level, as bars. It listens only while `active` is
	 * true (a test the person started), never in the background. Calls
	 * `onHeard` once the level stays up for a moment ("Sounds good").
	 */
	let {
		active,
		bars = 14,
		height = 14,
		big = false,
		onHeard,
		onError,
	}: {
		active: boolean;
		bars?: number;
		height?: number;
		big?: boolean;
		onHeard?: () => void;
		onError?: (message: string) => void;
	} = $props();

	let levels = $state<number[]>([]);
	let stream: MediaStream | null = null;
	let context: AudioContext | null = null;
	let frame = 0;
	let loudFrames = 0;
	let heard = false;
	/** Bumped by every stop, so a start still waiting for the mic knows it is stale. */
	let generation = 0;

	function stop() {
		generation += 1;
		cancelAnimationFrame(frame);
		stream?.getTracks().forEach((t) => t.stop());
		stream = null;
		void context?.close();
		context = null;
		levels = [];
	}

	async function start() {
		heard = false;
		loudFrames = 0;
		const mine = generation;
		let acquired: MediaStream;
		try {
			acquired = await navigator.mediaDevices.getUserMedia({ audio: true });
		} catch {
			if (mine === generation)
				onError?.("VoiceLnq can't use the microphone. Check that Windows lets apps use it.");
			return;
		}
		// VoiceFlow: the test ended (or the page closed) while the microphone
		// was being opened. Close it now instead of listening unseen.
		if (mine !== generation) {
			acquired.getTracks().forEach((t) => t.stop());
			return;
		}
		stream = acquired;
		context = new AudioContext();
		const analyser = context.createAnalyser();
		analyser.fftSize = 512;
		context.createMediaStreamSource(acquired).connect(analyser);
		const data = new Uint8Array(analyser.fftSize);
		const history: number[] = Array(bars).fill(0);
		const tick = () => {
			analyser.getByteTimeDomainData(data);
			let sum = 0;
			for (const v of data) sum += ((v - 128) / 128) ** 2;
			const rms = Math.sqrt(sum / data.length);
			history.shift();
			history.push(Math.min(1, rms * 6));
			levels = [...history];
			if (rms > 0.04) loudFrames += 1;
			if (!heard && loudFrames > 20) {
				heard = true;
				onHeard?.();
			}
			frame = requestAnimationFrame(tick);
		};
		tick();
	}

	$effect(() => {
		if (active) void start();
		return () => stop();
	});

	onDestroy(stop);

	const shown = $derived(levels.length ? levels : Array(bars).fill(0));
</script>

<span class="wv meter {big ? 'big' : ''}" aria-hidden="true"
	>{#each shown as level}<i style="height:{Math.max(2, Math.round(height * (0.15 + 0.85 * level)))}px"
		></i>{/each}</span
>
