<script lang="ts">
	import { Switch } from '@epicenter/ui/switch';
	import { untrack } from 'svelte';
	import { deviceId } from '$lib/profile/op-store';
	import { wordLists } from '$lib/profile/service.svelte';
	import type { GithubConnectState, GithubLinks, GithubRepoChoice, GithubTestReport } from '$lib/profile/types';
	import Icon from '$lib/vf/Icon.svelte';
	import { syncSummary } from '$lib/vf/word-lists';

	/**
	 * Settings > Word lists (plan v2, B + C2): where the word lists live (a
	 * folder on this PC or a network share, or since 0.9.19 a private GitHub
	 * repository), whose list this device uses, and the assistant who
	 * manages the "from notes" list. Device settings, kept by the profile
	 * service in the app's data folder; the GitHub token is kept in the OS
	 * credential store and never comes back to this page.
	 *
	 * 0.9.20: Connect GitHub signs the computer in to the VoiceFlow GitHub
	 * App (device flow): this page shows the code and a link, then a list of
	 * the repositories the app is installed on. A pasted token stays as an
	 * Advanced choice; whichever was set last is used.
	 */

	const isDesktop = !!window.__TAURI_INTERNALS__;
	const current = $derived(wordLists.state?.config);
	const tokenStored = $derived(!!wordLists.state?.githubToken);
	// 0.9.20: '' | 'app' (Connect GitHub) | 'pat' (a pasted token) | 'reconnect'.
	const auth = $derived(wordLists.state?.githubAuth ?? (tokenStored ? 'pat' : ''));
	const summary = $derived(syncSummary(wordLists.state));

	let enabled = $state(untrack(() => current?.enabled ?? false));
	let kind = $state<'folder' | 'github'>(untrack(() => (current?.kind === 'github' ? 'github' : 'folder')));
	let source = $state(untrack(() => current?.source ?? ''));
	let repo = $state(untrack(() => current?.repo ?? ''));
	let branch = $state(untrack(() => current?.branch || 'main'));
	let upload = $state(untrack(() => !current?.readOnly));
	let token = $state('');
	let user = $state(untrack(() => current?.user ?? ''));
	let assistant = $state(untrack(() => current?.assistant ?? ''));
	let users = $state<string[]>([]);
	let error = $state<string | null>(null);
	let saved = $state(false);
	let saving = $state(false);
	let testing = $state(false);
	let test = $state<GithubTestReport | null>(null);
	let testError = $state<string | null>(null);
	let loaded = false;

	// Fill the form once the service has answered.
	$effect(() => {
		if (loaded || !current) return;
		loaded = true;
		enabled = current.enabled;
		kind = current.kind === 'github' ? 'github' : 'folder';
		source = current.source;
		repo = current.repo ?? '';
		branch = current.branch || 'main';
		upload = !current.readOnly;
		user = current.user;
		assistant = current.assistant;
	});

	async function findUsers() {
		if (kind === 'folder') users = await wordLists.listUsers(source);
	}
	$effect(() => {
		if (kind === 'folder' && source.trim()) void untrack(findUsers);
	});

	const dirty = $derived(
		!current ||
			enabled !== current.enabled ||
			kind !== (current.kind === 'github' ? 'github' : 'folder') ||
			source.trim() !== current.source ||
			repo.trim() !== (current.repo ?? '') ||
			branch.trim() !== (current.branch || 'main') ||
			upload !== !current.readOnly ||
			token.trim() !== '' ||
			user.trim().toLowerCase() !== current.user ||
			assistant.trim() !== current.assistant,
	);

	async function save(e?: SubmitEvent) {
		e?.preventDefault();
		error = null;
		saving = true;
		try {
			// The token first, so the check that follows the settings has it.
			if (kind === 'github' && token.trim()) {
				await wordLists.setGithubToken(token.trim());
				token = '';
			}
			await wordLists.setConfig({
				enabled,
				kind,
				source: source.trim(),
				repo: repo.trim(),
				branch: branch.trim(),
				readOnly: !upload,
				user: user.trim().toLowerCase(),
				assistant: assistant.trim(),
				device: deviceId(),
			});
			saved = true;
			setTimeout(() => (saved = false), 2500);
		} catch (err) {
			error = err instanceof Error ? err.message : String(err);
		} finally {
			saving = false;
		}
	}

	async function testConnection() {
		testError = null;
		test = null;
		testing = true;
		try {
			test = await wordLists.testGithub({ repo: repo.trim(), branch: branch.trim(), user: user.trim(), token: token.trim() });
			if (test.ok) {
				users = test.users;
				if (!branch.trim()) branch = test.branch;
			}
		} catch (err) {
			testError = err instanceof Error ? err.message : String(err);
		} finally {
			testing = false;
		}
	}

	// ---- 0.9.20 Connect GitHub (the device flow runs in the app; this page
	// only shows the code and reads how the attempt is going).
	let attempt = $state<GithubConnectState | null>(null);
	let starting = $state(false);
	let copied = $state(false);
	let connectError = $state<string | null>(null);
	let repos = $state<GithubRepoChoice[] | null>(null);
	let reposError = $state<string | null>(null);
	let loadingRepos = $state(false);
	let links = $state<GithubLinks | null>(null);
	let timer: ReturnType<typeof setInterval> | undefined;

	$effect(() => {
		if (isDesktop) void wordLists.githubLinks().then((l) => (links = l)).catch(() => {});
		return () => clearInterval(timer);
	});

	async function startConnect() {
		connectError = null;
		starting = true;
		try {
			attempt = await wordLists.connectStart();
			clearInterval(timer);
			timer = setInterval(watchAttempt, 1500);
		} catch (err) {
			connectError = err instanceof Error ? err.message : String(err);
		} finally {
			starting = false;
		}
	}

	async function watchAttempt() {
		if (!attempt) return clearInterval(timer);
		const now = await wordLists.connectStatus(attempt.id).catch(() => null);
		if (!now || now.state === 'waiting') return;
		clearInterval(timer);
		attempt = null;
		if (now.state === 'connected') {
			await loadRepos();
		} else if (now.state !== 'cancelled') {
			connectError = now.message ?? 'Connecting to GitHub didn’t finish. Try again.';
		}
	}

	async function cancelConnect() {
		if (attempt) await wordLists.connectCancel(attempt.id).catch(() => {});
		clearInterval(timer);
		attempt = null;
	}

	async function copyCode() {
		if (!attempt) return;
		try {
			await navigator.clipboard.writeText(attempt.userCode);
			copied = true;
			setTimeout(() => (copied = false), 2000);
		} catch {
			// The code is on screen to type.
		}
	}

	async function loadRepos() {
		reposError = null;
		loadingRepos = true;
		try {
			repos = await wordLists.listRepos();
			// The one repository there is: chosen for you.
			const only = repos.length === 1 ? repos[0] : undefined;
			if (only && !repo.trim()) pick(only);
		} catch (err) {
			repos = null;
			reposError = err instanceof Error ? err.message : String(err);
		} finally {
			loadingRepos = false;
		}
	}

	function pick(r: GithubRepoChoice) {
		repo = r.fullName;
		branch = r.defaultBranch || 'main';
		test = null;
	}

	async function disconnect() {
		error = null;
		try {
			await wordLists.setGithubToken('');
			repos = null;
		} catch (err) {
			error = err instanceof Error ? err.message : String(err);
		}
	}

	async function removeToken() {
		error = null;
		try {
			await wordLists.setGithubToken('');
		} catch (err) {
			error = err instanceof Error ? err.message : String(err);
		}
	}

	function toggle(on: boolean) {
		enabled = on;
		// Turning it off needs nothing else (the saved settings, switched off:
		// nothing half-typed in the form, and no token); turning it on waits for
		// the form.
		if (!on && current?.enabled) void disable();
	}

	async function disable() {
		if (!current) return;
		error = null;
		try {
			await wordLists.setConfig({ ...current, enabled: false });
		} catch (err) {
			enabled = true;
			error = err instanceof Error ? err.message : String(err);
		}
	}

	const layerName = { household: 'Household', generated: 'From notes', personal: 'Your list' } as Record<string, string>;
</script>

{#if !isDesktop}
	<p class="sub">Word lists are set up in the desktop app.</p>
{:else}
	<div class="srow">
		<div>
			<b>Use word lists</b>
			<span>Words, spellings and styles come from a shared folder or a private GitHub repository, so every device spells your way</span>
		</div>
		<Switch checked={enabled} onCheckedChange={toggle} aria-label="Use word lists" />
	</div>
	{#if enabled}
		<form class="wl" onsubmit={save}>
			<span class="lbl" id="wl-kind-label">Where they are</span>
			<div class="seg" role="radiogroup" aria-labelledby="wl-kind-label">
				<label class:on={kind === 'folder'}><input type="radio" bind:group={kind} value="folder" />A folder</label>
				<label class:on={kind === 'github'}><input type="radio" bind:group={kind} value="github" />A private GitHub repository</label>
			</div>
			{#if kind === 'folder'}
				<label for="wl-source">Folder</label>
				<input
					id="wl-source"
					class="inp"
					bind:value={source}
					onblur={findUsers}
					placeholder={'\\\\NAS\\voiceflow or C:\\Users\\you\\VoiceFlow'}
					autocomplete="off"
					spellcheck="false"
				/>
			{:else}
				<span class="lbl">GitHub</span>
				{#if auth === 'app' && !attempt}
					<div class="srow tight">
						<div><b>Connected to GitHub</b><span>Through the VoiceFlow Word Lists app: nothing to copy or renew</span></div>
						<button class="btn text" type="button" onclick={disconnect}>Disconnect</button>
					</div>
				{:else if attempt}
					<div class="connect" role="status">
						<span class="sub small">Enter this code on GitHub, then approve <b>VoiceFlow Word Lists</b>:</span>
						<div class="code" aria-label="Code to enter on GitHub">{attempt.userCode}</div>
						<div class="acts">
							<button class="btn text" type="button" onclick={copyCode}><Icon name="copy" /><span>{copied ? 'Copied' : 'Copy code'}</span></button>
							<a class="btn pri" href={attempt.verificationUri} target="_blank" rel="noreferrer">Open GitHub</a>
							<button class="btn text" type="button" onclick={cancelConnect}>Cancel</button>
						</div>
						<span class="sub small">Waiting for GitHub… This updates by itself. The code works for 15 minutes.</span>
					</div>
				{:else}
					{#if auth === 'reconnect'}
						<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />VoiceLnq’s connection to GitHub has ended. Connect again to keep your word lists up to date.</p>
					{/if}
					<div class="acts">
						<button class="btn pri" type="button" onclick={startConnect} disabled={starting}>
							{starting ? 'Asking GitHub…' : auth === 'reconnect' ? 'Connect GitHub again' : 'Connect GitHub'}
						</button>
					</div>
					<span class="sub small">Signs this computer in to GitHub with the VoiceFlow Word Lists app. No token to make or paste.</span>
				{/if}
				{#if connectError}
					<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{connectError}</p>
				{/if}
				<label for="wl-repo">Repository</label>
				<div class="row">
					<input id="wl-repo" class="inp grow" bind:value={repo} placeholder="your-name/voiceflow-words" autocomplete="off" spellcheck="false" />
					{#if auth === 'app'}
						<button class="btn text" type="button" onclick={loadRepos} disabled={loadingRepos}>{loadingRepos ? 'Loading…' : 'Choose'}</button>
					{/if}
				</div>
				{#if reposError}
					<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{reposError}</p>
				{:else if repos && repos.length === 0}
					<div class="test bad" role="status">
						<p><Icon name="warning-circle" weight="fill" class="i sm" />VoiceFlow Word Lists isn’t installed on any of your repositories yet.</p>
						<p>
							<a href={links?.installUrl} target="_blank" rel="noreferrer">Install it on your word-list repository</a>
							(choose <b>Only select repositories</b>), then <button class="link" type="button" onclick={loadRepos}>check again</button>.
						</p>
						<p class="sub small">Someone else’s repository: its owner installs the app there and adds you to the repository.</p>
					</div>
				{:else if repos}
					<div class="pick" role="listbox" aria-label="Your repositories">
						{#each repos as r (r.fullName)}
							<button type="button" role="option" aria-selected={repo === r.fullName} class:on={repo === r.fullName} onclick={() => pick(r)}>
								<span>{r.fullName}</span>
								<span class="chip" class:pub={!r.private}>{r.private ? 'Private' : 'Public'}</span>
							</button>
						{/each}
						<span class="sub small">Not in the list? <a href={links?.installUrl} target="_blank" rel="noreferrer">Add it to the app on GitHub</a>, then press Choose again.</span>
					</div>
				{/if}
				<label for="wl-branch">Branch</label>
				<input id="wl-branch" class="inp" bind:value={branch} placeholder="main" autocomplete="off" spellcheck="false" />
				<details class="adv" open={auth === 'pat'}>
					<summary>Advanced: use an access token instead</summary>
					<label for="wl-token">Access token</label>
					<input
						id="wl-token"
						class="inp"
						type="password"
						bind:value={token}
						placeholder={auth === 'pat' ? 'Stored on this computer (type a new one to replace it)' : 'github_pat_…'}
						autocomplete="off"
						spellcheck="false"
					/>
					<span class="sub small">
						A fine-grained token for this one repository, with Contents read and write. Saving it replaces a GitHub
						connection. It is kept in this computer’s credential store, never in a file. <a href="https://github.com/yutungh/voicelnq/blob/main/docs/GITHUB-SYNC.md#advanced-use-an-access-token-instead" target="_blank" rel="noreferrer">How to make one</a>
					</span>
					{#if auth === 'pat'}
						<div class="acts"><button class="btn text" type="button" onclick={removeToken}>Remove token</button></div>
					{/if}
				</details>
				<div class="srow tight">
					<div><b>Upload this computer’s changes</b><span>Words and spellings you fix here, and this computer’s status</span></div>
					<Switch checked={upload} onCheckedChange={(on) => (upload = on)} aria-label="Upload this computer’s changes" />
				</div>
				<div class="acts">
					<button class="btn text" type="button" onclick={testConnection} disabled={testing || !repo.trim() || (!token.trim() && !tokenStored)}>
						<Icon name="shield-check" /><span>{testing ? 'Testing…' : 'Test connection'}</span>
					</button>
					{#if auth === 'app'}
						<a class="btn text" href={links?.authorizationsUrl} target="_blank" rel="noreferrer">Manage on GitHub</a>
					{/if}
				</div>
				{#if testError}
					<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{testError}</p>
				{:else if test}
					<div class="test" class:bad={!test.ok} role="status">
						<p><Icon name={test.ok ? 'check-circle' : 'warning-circle'} weight="fill" class="i sm" />{test.message}</p>
						{#each test.warnings as w (w)}<p class="warn">{w}</p>{/each}
						{#if test.ok && test.layers.length > 0}
							<ul>
								{#each test.layers as l (l.layer)}
									<li>{layerName[l.layer] ?? l.layer}: {l.state === 'ok' ? 'reads fine' : l.state === 'missing' ? 'not there (optional)' : (l.error ?? 'not usable')}</li>
								{/each}
							</ul>
						{/if}
					</div>
				{/if}
			{/if}
			<label for="wl-user">Whose words</label>
			<input id="wl-user" class="inp" bind:value={user} list="wl-users" placeholder="your name, lower case" autocomplete="off" spellcheck="false" />
			<datalist id="wl-users">
				{#each users as u (u)}<option value={u}></option>{/each}
			</datalist>
			{#if users.length > 0}
				<span class="sub small">In this {kind === 'github' ? 'repository' : 'folder'}: {users.join(', ')}</span>
			{/if}
			<label for="wl-assistant">Your assistant’s name <span class="sub">(optional)</span></label>
			<input id="wl-assistant" class="inp" bind:value={assistant} placeholder="Who keeps your notes" autocomplete="off" />
			{#if error}
				<p class="err" role="alert"><Icon name="warning-circle" weight="fill" class="i sm" />{error}</p>
			{/if}
			<div class="acts">
				<button class="btn pri" type="submit" disabled={saving || !dirty}>{saved ? 'Saved' : 'Save'}</button>
			</div>
		</form>
	{/if}
	{#if current?.enabled}
		<div class="srow">
			<div><b>{summary.title}</b><span>{summary.detail}</span></div>
			<button class="btn text" type="button" onclick={() => wordLists.refresh()} disabled={wordLists.state?.status.refreshing}>
				<Icon name="arrows-clockwise" /><span>Refresh now</span>
			</button>
		</div>
		<div class="srow">
			<div><b>Your words and spellings</b><span>See them, add a word or fix a spelling</span></div>
			<a class="btn text" href="/vocabulary">Vocabulary</a>
		</div>
	{/if}
{/if}

<style>
	.wl {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 6px 0 12px;
	}
	.wl label,
	.wl .lbl {
		font-size: 12px;
		font-weight: 600;
		color: var(--subtle-foreground);
		margin-top: 6px;
	}
	.wl .acts {
		margin-top: 10px;
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
	}
	.seg {
		display: flex;
		gap: 6px;
		flex-wrap: wrap;
	}
	.seg label {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 6px 10px;
		border: 1px solid var(--border);
		border-radius: 8px;
		cursor: pointer;
		color: var(--foreground);
		font-weight: 500;
	}
	.seg label.on {
		border-color: var(--primary);
	}
	.seg input {
		margin: 0;
	}
	.srow.tight {
		padding-top: 8px;
	}
	.test {
		margin-top: 6px;
		padding: 8px 10px;
		border-radius: 8px;
		border: 1px solid var(--border);
		font-size: 13px;
	}
	.test.bad {
		border-color: var(--destructive, #c0392b);
	}
	.test p {
		margin: 2px 0;
		display: flex;
		gap: 6px;
		align-items: flex-start;
	}
	.test .warn {
		color: var(--warning, #b7791f);
	}
	.test ul {
		margin: 4px 0 0 18px;
		padding: 0;
	}
	.connect {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 10px 12px;
		border: 1px solid var(--border);
		border-radius: 8px;
	}
	.connect .acts {
		margin-top: 2px;
	}
	.code {
		font-family: ui-monospace, 'Cascadia Mono', Consolas, monospace;
		font-size: 26px;
		font-weight: 700;
		letter-spacing: 0.12em;
		user-select: all;
	}
	.row {
		display: flex;
		gap: 8px;
		align-items: center;
	}
	.row .grow {
		flex: 1;
		min-width: 0;
	}
	.pick {
		display: flex;
		flex-direction: column;
		gap: 4px;
		margin-top: 4px;
		max-height: 220px;
		overflow-y: auto;
	}
	.pick button {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 8px;
		padding: 6px 10px;
		border: 1px solid var(--border);
		border-radius: 8px;
		background: transparent;
		color: var(--foreground);
		text-align: left;
		cursor: pointer;
	}
	.pick button.on {
		border-color: var(--primary);
	}
	.chip {
		font-size: 11px;
		padding: 1px 6px;
		border-radius: 999px;
		border: 1px solid var(--border);
		color: var(--subtle-foreground);
	}
	.chip.pub {
		color: var(--warning, #b7791f);
		border-color: var(--warning, #b7791f);
	}
	.adv {
		margin-top: 8px;
	}
	.adv summary {
		cursor: pointer;
		font-size: 13px;
		color: var(--subtle-foreground);
	}
	.link {
		background: none;
		border: 0;
		padding: 0;
		color: var(--primary);
		text-decoration: underline;
		cursor: pointer;
		font: inherit;
	}
</style>
