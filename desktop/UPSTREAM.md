# Upstream source

`desktop/` is a fork of **Whispering**, imported from the Epicenter monorepo.

| | |
|---|---|
| Source repository | https://github.com/EpicenterHQ/epicenter |
| Tag | `v7.11.0` |
| Commit | `a6b15c28db9076742d7ae7014f8ec9f97aa615d1` |
| Commit date | 2025-12-27 05:59:54 +0900 |
| Imported | 2026-11-16 |
| Licence | GNU Affero General Public License v3 (`LICENSE`; upstream `package.json` declares `AGPL-3.0`), Copyright (C) 2023-2025 Braden Wong |

## How it was imported

- Every tracked file at the tag (`git archive v7.11.0`, 1,361 files), unchanged and at the same paths, under `desktop/`.
- One exception: upstream's root `README.md` is kept as `README.upstream.md`, because `desktop/README.md` is VoiceLnq's own.
- No upstream git history is included.

## Seeing what VoiceLnq changed

Compare a file with the tag in a clone of the upstream repository:

```sh
git -C <upstream-clone> show v7.11.0:apps/whispering/src-tauri/tauri.conf.json > upstream.json
diff upstream.json desktop/apps/whispering/src-tauri/tauri.conf.json
```

## What was pruned after the import

The commit "Prune desktop/ to the Whispering app and its packages" keeps only what the Whispering desktop app builds from: `apps/whispering`, the workspace packages it uses (`packages/config`, `constants`, `epicenter` (`@epicenter/hq`), `shared`, `svelte-utils`, `ui`) and the root build files. It dropped the other apps (`demo-mcp`, `landing`, `posthog-reverse-proxy`, `tab-manager`), `packages/vault-core`, `examples/`, `docs/`, `specs/`, `skills/`, `logos/`, upstream's `.github/` and `.claude/`, the root `AGENTS.md`, `CLAUDE.md`, `conductor.json`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SECURITY.md`, and upstream's root README (`README.upstream.md`). The root `package.json` workspaces lost `examples/*`. `git revert` of that commit restores all of it.

The commit that imported the tree into this repository is "Import Whispering 7.11.0 into desktop/"; `git diff <that commit> -- desktop/` shows every change made since.
