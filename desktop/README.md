# VoiceLnq Desktop

A dictation app for Windows and macOS: press a hotkey, speak, and get clean text at the cursor, with your own names and terms spelled right. You bring your own OpenAI key. There are no prebuilt downloads yet, so you build the app from source (below).

**Based on [Whispering](https://github.com/EpicenterHQ/epicenter) by Braden Wong** (version 7.11.0, AGPL-3.0 (version 3 only, as upstream declares)). VoiceLnq is not affiliated with or endorsed by the Whispering project.

| | |
|---|---|
| **Platforms** | Windows (primary) and macOS |
| **Licence** | AGPL-3.0 (this folder only; see [Licence](#licence)) |
| **Sibling** | The VoiceLnq Android keyboard, at the root of this repository |
| **Shared with Android** | The vocabulary **pack** ([`../pack/`](../pack/README.md)): terms, approved corrections, per-app rules and styles, read by both apps |
| **History** | What changed from upstream Whispering, and the milestone notes: [`CHANGELOG.md`](CHANGELOG.md) |

## What you need to build it

### Windows

| Tool | Version | Where it is pinned |
|---|---|---|
| [Bun](https://bun.sh) | 1.3.3 | `packageManager` in `package.json`; CI reads the version from there |
| Rust, via [rustup](https://rustup.rs), MSVC toolchain | 1.100.0 | `rust-toolchain.toml`; rustup installs it on first use |
| Visual Studio 2022 Build Tools | the "Desktop development with C++" workload | needed for MSVC's `link.exe` |
| Node.js | 22 | what CI uses for the build tooling |
| WebView2 | any current version | ships with Windows 11 |

### macOS

Xcode command line tools (`xcode-select --install`), plus the same Bun, Rust and Node versions as above.

### Get the source

On Windows, turn on long paths when you clone, or the build can fail on deep folders:

```sh
git clone -c core.longpaths=true https://github.com/yutungh/voicelnq
```

## Build and run

Run these from the repository root.

### Windows

Use an "x64 Native Tools Command Prompt for VS 2022", or run `vcvars64.bat` first. That puts MSVC's `link.exe` ahead of Git's `link.exe` on `PATH`; without it the Rust build fails to link.

```bat
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set CARGO_TARGET_DIR=C:\build\voiceflow-target
bun install --cwd desktop --frozen-lockfile
bun run --cwd desktop\apps\whispering tauri build --bundles nsis
```

- `CARGO_TARGET_DIR` keeps Rust's build output on a short path of your choice. Windows' 260-character path limit broke upstream's build from deep folders. Also run `git config core.longpaths true` in the clone and, once as administrator, set the `LongPathsEnabled` registry value.
- The installer lands in `%CARGO_TARGET_DIR%\release\bundle\nsis\VoiceFlow_<version>_x64-setup.exe`.
- The first build downloads ONNX Runtime (for the local Parakeet model) and the NSIS tools.
- To run it in development mode instead of building an installer: `bun run --cwd desktop\apps\whispering dev`.
- The Tauri CLI may rewrite `src-tauri/Cargo.toml` with different line endings. Discard that change with `git checkout -- <file>`.
- Builds are unsigned. Windows SmartScreen warns once when you install.

### macOS

CI builds a universal (Apple Silicon + Intel) app on every push that touches `desktop/` (`.github/workflows/desktop.yml`). Locally:

```sh
rustup target add aarch64-apple-darwin x86_64-apple-darwin
bun install --cwd desktop --frozen-lockfile
bun run --cwd desktop/apps/whispering tauri build --target universal-apple-darwin --bundles app,dmg
```

The build is ad-hoc signed (no Apple Developer ID), so macOS asks you to "Open Anyway" in System Settings the first time.

### Tests

```sh
cd desktop/apps/whispering
bun test                              # unit tests
cd src-tauri && cargo test --locked --lib   # Rust unit tests (after a build)
```

## First run

1. Start VoiceLnq. A welcome screen asks for your **OpenAI key** first. You can also add or replace it later in **Settings > Transcription > OpenAI key**.
2. The key goes into Windows Credential Manager or the macOS Keychain, never into a file in the project. Without a key, dictation cannot run.
3. Press the dictation hotkey shown on the welcome screen, speak, and press it again. The text is pasted at your cursor, in whatever app you were in. You can also hold the push-to-talk hotkey while you speak.
4. Closing the window keeps VoiceLnq running in the tray (the menu bar on macOS). Quit it from the tray menu.

**What works without any sync:** everything above. Transcription uses `gpt-transcribe` by default and the tidy-up uses `gpt-5.4-mini` by default; both model ids can be changed. The word lists, the sync server and the Android keyboard are all optional.

**Optional: your own word lists.** In **Settings > Word lists**, turn on **Use word lists**, enter the **Folder** (a local folder, a synced folder or a network share, for example `\\server\voiceflow`) and your user name under **Whose words**. The folder layout is in [`../pack/README.md`](../pack/README.md), and [`../docs/desktop/CONNECT-YOUR-OWN-AI.md`](../docs/desktop/CONNECT-YOUR-OWN-AI.md) shows how to let an assistant maintain the lists. The desktop app reads this folder directly; it does not talk to the sync server. To share the same folder with a phone, put it behind the [sync gateway](../sync/README.md). From 0.9.19 the word lists can also live in a **private GitHub repository** (choose it under **Where they are**; from 0.9.20, **Connect GitHub** signs in without a token): see [`../docs/GITHUB-SYNC.md`](../docs/GITHUB-SYNC.md).

**A style for each app (0.9.21).** In **Settings > Tidy-up > Style for each app**, choose the style dictations into an app start in (Telegram in Friends, ChatGPT in AI prompt): a built-in style, one from your word lists, one made on this PC, or Raw, and a level if you want one. A style picked on the recording bar still wins for that dictation. The setting is saved as an edit to your word lists that stays on this PC for now, and it wins over the word lists' own rule for that app; removing it brings their rule back. The format is in [`../pack/README.md`](../pack/README.md#style-references-in-app-rules).

## What it adds to Whispering

A short list; the full table is in [`CHANGELOG.md`](CHANGELOG.md).

- Renamed VoiceLnq, with new icons and a new look, tuned for hotkey dictation into other apps.
- No telemetry and no upstream auto-updater.
- API keys kept in the OS keychain.
- Tidy-up that fails or times out pastes nothing and offers Retry or Paste raw text; a failed transcription keeps the audio and offers Retry.
- App identity at the hotkey, with tiered privacy gates (block list, password-field check) and a paste-time re-check.
- Word lists shared with the Android keyboard.

Code paths keep upstream's names (`apps/whispering`, `@epicenter/*` packages) so diffs against the upstream tag stay readable (`UPSTREAM.md`).

## Licence

| Directory | Licence |
|---|---|
| `desktop/` (this folder) | GNU Affero General Public License, AGPL-3.0 (version 3 only, as upstream declares) (`LICENSE`). Upstream copyright and notices are kept; VoiceLnq's modifications are described in `NOTICE` |
| `pack/` | MIT (`../pack/LICENSE`) |
| `sync/` | MIT (`../sync/package.json`) |
| Android app (repository root) | MIT (`../LICENSE`) |

- Each directory's licence covers only that directory.
- The app's sounds are VoiceLnq's own, generated by `tools/sounds/generate.mjs` (no samples) and released under CC0 1.0; see `NOTICE`.
- **Do not copy any code from this folder into the Android app, `pack/` or `sync/`.** It would bring them under the AGPL. The apps share only data (the pack), never code.
- Giving a build to anyone else means also offering them the corresponding source (AGPL section 6).

## Network calls

The app calls no VoiceLnq, Whispering or Epicenter server: no telemetry, no update check. What it does reach:

| When | Host | Why |
|---|---|---|
| Each dictation, with a cloud service selected | The chosen provider's API (OpenAI by default; Groq, Deepgram, ElevenLabs, Mistral, Anthropic, Google, OpenRouter, or a custom endpoint if picked) | Transcription and tidy-up |
| Only when the user clicks Find fixes in the Feedback window | The model chosen in Settings > Feedback (OpenAI or Anthropic, with the user's own key) | Suggests fixes to spellings and styles from that one dictation and the feedback |
| Only when the user downloads a local model | `huggingface.co` (Parakeet: `istupakov/parakeet-tdt-0.6b-v3-onnx` at a pinned revision; Whisper: `ggerganov/whisper.cpp`; Moonshine: `UsefulSensors/moonshine`) | Model files |
| With word lists in a GitHub repository: at start, hourly, on Refresh now and on Test connection | `api.github.com`, with the GitHub sign-in or the user's own token (kept in the OS keychain) | Reads the word-list repository; uploads this computer's word edits and its status file unless uploads are off |
| Only when the user clicks Connect GitHub (0.9.20 and later), and when that sign-in is renewed | `github.com` (`/login/device/code`, `/login/oauth/access_token`) and `api.github.com` (the repositories the app is installed on) | Signs this computer in through the VoiceFlow Word Lists GitHub App |
| Only with voice-activated recording | `cdn.jsdelivr.net` (`@ricky0123/vad-web` 0.0.24, `onnxruntime-web` 1.14.0) | Voice-detection model and runtime files |
| Only when the user clicks a link | `github.com/yutungh/voicelnq`, provider sign-up and docs pages | Help |

## More documentation

| What | Where |
|---|---|
| Product and architecture spec | [`../docs/desktop/SPEC.md`](../docs/desktop/SPEC.md) |
| Connect your own AI to the word lists | [`../docs/desktop/CONNECT-YOUR-OWN-AI.md`](../docs/desktop/CONNECT-YOUR-OWN-AI.md) |
| Keep the word lists in a private GitHub repository | [`../docs/GITHUB-SYNC.md`](../docs/GITHUB-SYNC.md) |
| Design: three UI directions (mockups) | [`../docs/desktop/design/directions/`](../docs/desktop/design/directions/) |
| Upstream and fork notes | [`UPSTREAM.md`](UPSTREAM.md), [`NOTICE`](NOTICE) |
