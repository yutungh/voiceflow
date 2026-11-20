<script lang="ts">
	// VoiceFlow B3.1: Settings is one page of sections (see +page.svelte), so
	// the upstream header, reset button and section sidebar are gone.
	let { children } = $props();
</script>

{@render children()}
