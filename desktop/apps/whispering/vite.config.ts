import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import devtoolsJson from 'vite-plugin-devtools-json';
import { nodePolyfills } from 'vite-plugin-node-polyfills';

const host = process.env.TAURI_DEV_HOST;

// https://vitejs.dev/config/
export default defineConfig(async () => ({
	plugins: [
		sveltekit(),
		tailwindcss(),
		devtoolsJson(),
		nodePolyfills({
			// Enable polyfills for Buffer (needed by gray-matter)
			globals: {
				Buffer: true,
			},
		}),
	],
	// Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
	//
	// 1. prevent vite from obscuring rust errors
	clearScreen: false,
	// 2. tauri expects a fixed port, fail if that port is not available
	server: {
		port: 1420,
		strictPort: true,
		host: host || false,
		hmr: host
			? {
					protocol: 'ws',
					host,
					port: 1421,
				}
			: undefined,
		watch: {
			// 3. tell vite to ignore watching `src-tauri`
			ignored: ['**/src-tauri/**'],
		},
		// The bundled Inter font lives in packages/ui; let the dev server serve it.
		// (Production builds copy it into the bundle.)
		fs: {
			// VoiceFlow 0.9.13: the shared built-in styles (pack/builtin, MIT).
			allow: ['../../packages/ui/src/fonts', '../../../pack/builtin'],
		},
	},
}));
