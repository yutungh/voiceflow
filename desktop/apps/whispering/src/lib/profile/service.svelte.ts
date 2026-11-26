/**
 * VoiceFlow word lists (plan v2, B + C2): the webview side of the Rust
 * profile service.
 *
 * - `wordLists.state` is the merged profile, its sync status and the
 *   device's word-list settings, kept current from `profile://changed`.
 * - `wordLists.overlay` is what the profile provides to `settings.value`
 *   (see `ownership.ts`); empty when word lists are off.
 * - `profilePins` pins the snapshot a dictation started with, so a refresh
 *   in the middle never changes its words, spellings or style, and tells
 *   the service a dictation is running so it waits to refresh.
 *
 * In the web build (no desktop app) word lists are off.
 */
import { SUPPORTED_LANGUAGES } from '$lib/constants/languages';
import { setProfilePrivacy } from '$lib/privacy/profile-blocklist';
import { effectiveSnapshot, profileOverlay } from './ownership';
import { createPins } from './pins';
import type {
	GithubConnectState,
	GithubLinks,
	GithubRepoChoice,
	GithubTestReport,
	ProfileConfig,
	ProfileSnapshot,
	ProfileState,
} from './types';

const isDesktop = () =>
	typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

async function invoke<T>(cmd: string, args?: Record<string, unknown>) {
	const { invoke } = await import('@tauri-apps/api/core');
	return invoke<T>(cmd, args);
}

const isLanguage = (value: string) =>
	(SUPPORTED_LANGUAGES as readonly string[]).includes(value);

let state = $state.raw<ProfileState | null>(null);
let overlay = $state.raw<ReturnType<typeof profileOverlay>>({});
let loadError = $state<string | null>(null);

function apply(next: ProfileState) {
	state = next;
	const active = effectiveSnapshot(next);
	overlay = profileOverlay(active, isLanguage);
	// Extra apps and title patterns from the profile (they only add blocks).
	setProfilePrivacy(active?.privacy ?? null);
}

let started = false;
async function start() {
	if (started || !isDesktop()) return;
	started = true;
	try {
		const { listen } = await import('@tauri-apps/api/event');
		await listen<ProfileState>('profile://changed', (event) =>
			apply(event.payload),
		);
		apply(await invoke<ProfileState>('profile_state'));
	} catch (error) {
		loadError = error instanceof Error ? error.message : String(error);
		console.error('[word lists] could not start', error);
	}
}

export const wordLists = {
	start,
	get state() {
		return state;
	},
	get loadError() {
		return loadError;
	},
	/** Word lists are on and a merged profile is available. */
	get active(): boolean {
		return effectiveSnapshot(state) !== null;
	},
	/** The merged profile in force (null: the device's own settings apply). */
	get snapshot(): ProfileSnapshot | null {
		return effectiveSnapshot(state);
	},
	/** Settings provided by the profile (empty when word lists are off). */
	get overlay() {
		return overlay;
	},
	async setConfig(config: ProfileConfig): Promise<void> {
		apply(await invoke<ProfileState>('profile_set_config', { config }));
	},
	/** Users found in a word-list folder (`profiles/<user>/`). */
	async listUsers(source: string): Promise<string[]> {
		if (!isDesktop() || !source.trim()) return [];
		try {
			return await invoke<string[]>('profile_list_users', { source });
		} catch {
			return [];
		}
	},
	async refresh(): Promise<void> {
		if (!isDesktop()) return;
		apply(await invoke<ProfileState>('profile_refresh'));
	},
	/**
	 * 0.9.19: stores the GitHub token in the OS credential store ('' removes
	 * it). It is never read back into the page.
	 */
	async setGithubToken(token: string): Promise<void> {
		apply(await invoke<ProfileState>('profile_github_set_token', { token }));
	},
	/**
	 * 0.9.22: stores this computer's sync server token in the OS credential
	 * store ('' removes it). It is never read back into the page.
	 */
	async setGatewayToken(token: string): Promise<void> {
		apply(await invoke<ProfileState>('profile_gateway_set_token', { token }));
	},
	/**
	 * Reads a GitHub repository without writing anything. `token` '' uses the
	 * stored one; a typed token is used for this test only.
	 */
	async testGithub(args: {
		repo: string;
		branch: string;
		user: string;
		token: string;
	}): Promise<GithubTestReport> {
		return invoke<GithubTestReport>('profile_github_test', args);
	},
	/**
	 * 0.9.20 Connect GitHub: asks GitHub for a code to show. The app waits
	 * for it to be entered on github.com; read the attempt with
	 * `connectStatus`. The device code and the tokens never reach the page.
	 */
	async connectStart(): Promise<GithubConnectState> {
		return invoke<GithubConnectState>('github_connect_start');
	},
	async connectStatus(id: number): Promise<GithubConnectState | null> {
		return invoke<GithubConnectState | null>('github_connect_status', { id });
	},
	async connectCancel(id: number): Promise<void> {
		await invoke('github_connect_cancel', { id });
	},
	/** The repositories the connected GitHub App reaches. */
	async listRepos(): Promise<GithubRepoChoice[]> {
		return invoke<GithubRepoChoice[]>('github_list_repos');
	},
	async githubLinks(): Promise<GithubLinks> {
		return invoke<GithubLinks>('github_links');
	},
	/** An edit file was saved: merge it now and upload it in the background. */
	async editsChanged(): Promise<void> {
		if (!isDesktop() || !state?.config.enabled) return;
		try {
			apply(await invoke<ProfileState>('profile_edits_changed'));
		} catch (error) {
			console.error('[word lists] could not merge the new edit', error);
		}
	},
};

// Settings read the profile's values from the first dictation on.
if (isDesktop()) void start();

// ---------------------------------------------------------------------------
// Pins: one snapshot per dictation
// ---------------------------------------------------------------------------


export type { ProfilePin } from './pins';

export const profilePins = createPins({
	current: () => wordLists.snapshot,
	enabled: () => !!wordLists.state?.config.enabled,
	setBusy(busy) {
		if (!isDesktop()) return;
		invoke('profile_set_busy', { busy }).catch((error) =>
			console.warn('[word lists] could not mark the dictation', error),
		);
	},
});
