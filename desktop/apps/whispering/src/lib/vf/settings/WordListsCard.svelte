<script lang="ts">
	import { Switch } from '@epicenter/ui/switch';
	import { untrack } from 'svelte';
	import { deviceId } from '$lib/profile/op-store';
	import { wordLists } from '$lib/profile/service.svelte';
	import type { GithubTestReport } from '$lib/profile/types';
	import Icon from '$lib/vf/Icon.svelte';
	import { syncSummary } from '$lib/vf/word-lists';

	/**
	 * Settings > Word lists (plan v2, B + C2): where the word lists live (a
	 * folder on this PC or a network share, or since 0.9.19 a private GitHub
	 * repository), whose list this device uses, and the assistant who
	 * manages the "from notes" list. Device settings, kept by the profile
	 * service in the app's data folder; the GitHub token is kept in the OS
	 * credential store and never comes back to this page.
	 */

	const isDesktop = !!window.__TAURI_INTERNALS__;
	const current = $derived(wordLists.state?.config);
	const tokenStored = $derived(!!wordLists.state?.githubToken);
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
		// Turning it off needs nothing else; turning it on waits for the form.
		if (!on && current?.enabled) void save();
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
				<label for="wl-repo">Repository</label>
				<input id="wl-repo" class="inp" bind:value={repo} placeholder="your-name/voiceflow-words" autocomplete="off" spellcheck="false" />
				<label for="wl-branch">Branch</label>
				<input id="wl-branch" class="inp" bind:value={branch} placeholder="main" autocomplete="off" spellcheck="false" />
				<label for="wl-token">Access token</label>
				<input
					id="wl-token"
					class="inp"
					type="password"
					bind:value={token}
					placeholder={tokenStored ? 'Stored on this computer (type a new one to replace it)' : 'github_pat_…'}
					autocomplete="off"
					spellcheck="false"
				/>
				<span class="sub small">
					A fine-grained token for this one repository, with Contents read and write. It is kept in this computer’s
					credential store, never in a file. <a href="https://github.com/yutungh/voiceflow/blob/main/docs/GITHUB-SYNC.md#2-make-an-access-token-for-each-device" target="_blank" rel="noreferrer">How to make one</a>
				</span>
				<div class="srow tight">
					<div><b>Upload this computer’s changes</b><span>Words and spellings you fix here, and this computer’s status</span></div>
					<Switch checked={upload} onCheckedChange={(on) => (upload = on)} aria-label="Upload this computer’s changes" />
				</div>
				<div class="acts">
					<button class="btn text" type="button" onclick={testConnection} disabled={testing || !repo.trim() || (!token.trim() && !tokenStored)}>
						<Icon name="shield-check" /><span>{testing ? 'Testing…' : 'Test connection'}</span>
					</button>
					{#if tokenStored}
						<button class="btn text" type="button" onclick={removeToken}>Remove token</button>
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
</style>
