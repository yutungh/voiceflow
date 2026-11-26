<script lang="ts">
	// VoiceFlow: the full licence text, bundled in the app (AGPL section 5(d)).
	import * as Field from '@epicenter/ui/field';
	import { Link } from '@epicenter/ui/link';
	import { licenseText } from '$lib/constants/legal';
</script>

<svelte:head>
	<title>Licence - VoiceLnq</title>
</svelte:head>

<div class="w-full max-w-[960px] px-4 py-4 sm:px-7">
<Field.Set>
	<Field.Legend>Licence</Field.Legend>
	<Field.Description>
		<Link href="/settings#about">Back to Settings</Link>
	</Field.Description>
	<Field.Separator />
	<pre
		class="whitespace-pre-wrap rounded-md border p-3 text-xs">{licenseText}</pre>
</Field.Set>
</div>
