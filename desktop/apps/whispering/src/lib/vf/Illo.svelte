<script lang="ts" module>
	/**
	 * B3.1 duotone line illustrations for empty states and first run, from
	 * the design source (docs/desktop/design/b3-1/src/b31.mjs). Inline SVG,
	 * coloured by the --ill-* tokens so they follow the theme.
	 */
	export const ILLUSTRATIONS = {
		mic: '<rect x="40" y="18" width="32" height="50" rx="16" class="f"/><rect x="40" y="18" width="32" height="50" rx="16" class="s"/><path d="M30 56a26 26 0 0 0 52 0M56 82v12M44 94h24" class="s"/><path d="M14 46v10M20 40v22M92 40v22M98 46v10" class="s m"/>',
		keys: '<rect x="10" y="50" width="44" height="34" rx="9" class="f"/><rect x="10" y="50" width="44" height="34" rx="9" class="s"/><rect x="62" y="50" width="40" height="34" rx="9" class="f"/><rect x="62" y="50" width="40" height="34" rx="9" class="s"/><path d="M10 76h44M62 76h40" class="s t"/><text x="32" y="69" class="k">Ctrl</text><text x="82" y="69" class="k">Space</text><path d="M30 34v8M38 26v24M46 32v12M54 22v32M62 28v20M70 34v8M78 30v16" class="s m"/>',
		done: '<circle cx="56" cy="56" r="30" class="f"/><circle cx="56" cy="56" r="30" class="s"/><path d="m44 57 8 8 16-17" class="s b"/><path d="M8 52v8M14 46v20M98 46v20M104 52v8" class="s m"/>',
		history: '<rect x="22" y="22" width="68" height="20" rx="7" class="f"/><rect x="22" y="22" width="68" height="20" rx="7" class="s"/><rect x="22" y="48" width="68" height="20" rx="7" class="s"/><rect x="22" y="74" width="68" height="20" rx="7" class="s t"/><path d="M32 32h2M38 28v8M44 30v4M50 27v10M56 30v4M62 29v6" class="s m"/><path d="M32 58h20" class="s t"/><path d="M32 84h14" class="s t"/>',
		search: '<circle cx="48" cy="48" r="26" class="f"/><circle cx="48" cy="48" r="26" class="s"/><path d="m67 67 22 22" class="s b"/><path d="M34 48v2M40 42v14M46 38v20M52 44v8M58 46v4" class="s m"/>',
		bulb: '<path d="M56 16a28 28 0 0 0-16 51c3 2 4 5 4 8v5h24v-5c0-3 1-6 4-8a28 28 0 0 0-16-51z" class="f"/><path d="M56 16a28 28 0 0 0-16 51c3 2 4 5 4 8v5h24v-5c0-3 1-6 4-8a28 28 0 0 0-16-51z" class="s"/><path d="M46 92h20" class="s"/><path d="m46 46 7 7 13-13" class="s b"/>',
		book: '<path d="M16 26c14-6 28-6 40 2 12-8 26-8 40-2v58c-14-6-28-6-40 2-12-8-26-8-40-2z" class="f"/><path d="M16 26c14-6 28-6 40 2 12-8 26-8 40-2v58c-14-6-28-6-40 2-12-8-26-8-40-2zM56 28v58" class="s"/><path d="M28 44h16M28 54h12M68 44h16M68 54h10" class="s t"/>',
		micoff: '<rect x="40" y="18" width="32" height="50" rx="16" class="f"/><rect x="40" y="18" width="32" height="50" rx="16" class="s"/><path d="M30 56a26 26 0 0 0 52 0M56 82v12M44 94h24" class="s"/><path d="m24 20 64 72" class="s b w"/>',
		wave: '<path d="M14 56h4M24 44v24M34 34v44M44 26v60M54 38v36M64 22v68M74 34v44M84 44v24M94 52v8" class="s b"/><circle cx="56" cy="56" r="44" class="f o"/>',
	} as const;

	export type IllustrationName = keyof typeof ILLUSTRATIONS;
</script>

<script lang="ts">
	let { name, size = 112 }: { name: IllustrationName; size?: number } = $props();
</script>

<svg class="illo" width={size} height={size} viewBox="0 0 112 112" aria-hidden="true"
	>{@html ILLUSTRATIONS[name]}</svg
>
