<script lang="ts">
	// VoiceFlow B3.1: the header row of unlabelled icons that sat above these
	// pages is gone. Navigation is the labelled sidebar, the recorder state,
	// model and tidy-up style are in the status bar, and the microphone,
	// model, style and compression pickers are labelled on Home.
	let { children } = $props();
</script>

<div class="flex-1 overflow-x-auto pt-1 sm:pt-2">
	{@render children()}
</div>
