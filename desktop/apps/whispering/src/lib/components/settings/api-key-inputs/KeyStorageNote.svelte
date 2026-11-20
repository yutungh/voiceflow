<script lang="ts">
	import { secretStoreLabel } from '$lib/services/desktop/secrets';
	import { settings } from '$lib/stores/settings.svelte';

	// VoiceFlow: say where API keys are kept (spec §2.3).
	const kind = $derived(settings.apiKeyStoreKind);
</script>

{#if kind !== 'loading'}
	<span class="block text-xs text-muted-foreground">
		Stored in {secretStoreLabel(kind)}.
	</span>
{/if}
