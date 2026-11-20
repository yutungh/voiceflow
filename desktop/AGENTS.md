# VoiceFlow Desktop: agent notes

`desktop/` is VoiceFlow Desktop, a fork of Whispering 7.11.0 (Tauri 2 + Svelte 5). It replaces upstream's `AGENTS.md` and `CLAUDE.md` files.

## Where things are

| What | Where |
|---|---|
| Product and architecture spec | `../docs/desktop/SPEC.md` |
| UI design (B3.1) | `../docs/desktop/design/b3-1/` |
| What changed from upstream, and how to compare | `README.md`, `UPSTREAM.md`, `NOTICE` |
| The app | `apps/whispering/` (upstream path kept so diffs stay readable) |
| Shared packages | `packages/*` (`@epicenter/*` names kept) |

## Build (Windows)

From a prompt where `vcvars64.bat` (VS 2022 Build Tools) has run, with Bun 1.3.3 and the Rust toolchain in `rust-toolchain.toml`:

```bat
set CARGO_TARGET_DIR=C:\vf\target
bun install --cwd desktop --frozen-lockfile
bun run --cwd desktop\apps\whispering tauri build --bundles nsis
```

- Typecheck: `bun run --cwd desktop/apps/whispering check`.
- CI builds Windows and macOS on every push that touches `desktop/` (`.github/workflows/desktop.yml`).

## Rules

- **AGPL code stays here.** Never copy code from `desktop/` into the Android app or `pack/`: it would bring them under the AGPL. The apps share data (the pack), never code.
- **No telemetry.** Nothing reports usage anywhere. Don't add analytics, crash reporting or "phone home" calls.
- **No upstream endpoints.** No update checks, downloads, links or videos that reach Whispering's or Epicenter's servers or GitHub. The fork's own updater comes in M4 (spec §7.3).
- **Secrets** go in the OS credential store (`src-tauri/src/secrets.rs`), never in localStorage or the repo.
- **Failures are visible.** Never paste raw text silently (spec §2.1): when tidy-up fails, nothing is pasted and the user gets Retry tidy-up / Paste raw text.
- Mark VoiceFlow changes to upstream files with a short `VoiceFlow:` comment, and list notable ones in `NOTICE`.

## Code patterns (from upstream)

- Three layers: service (pure functions returning `Result`) → query (`rpc.*`, reactivity, notifications) → UI.
- Use `wellcrafted` `Result` types instead of `try`/`catch`.
- Keep business logic out of Svelte components; pass settings into services as parameters.
- Desktop vs web is detected with `window.__TAURI_INTERNALS__`.
