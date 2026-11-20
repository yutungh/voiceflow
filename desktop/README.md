# VoiceFlow Desktop

A dictation app for Windows and macOS: press a hotkey, speak, and get clean text at the cursor, with your own names and terms spelled right.

**Based on [Whispering](https://github.com/EpicenterHQ/epicenter) by Braden Wong** (version 7.11.0, AGPL-3.0 (version 3 only, as upstream declares)). VoiceFlow is not affiliated with or endorsed by the Whispering project.

| | |
|---|---|
| **What it is** | A fork of Whispering 7.11.0, renamed VoiceFlow and reworked for hotkey dictation into other apps |
| **Platforms** | Windows first, then macOS |
| **Status** | Milestone M1 in progress: the fork builds as VoiceFlow, with telemetry removed, the upstream updater disabled, API keys in the OS keychain, visible failures, the B3.1 look, and app identity with tiered privacy gates and paste-time checks (M1c). Next: the workspace extraction and his acceptance run |
| **Sibling** | The VoiceFlow Android keyboard, at the root of this repository |
| **Shared with Android** | The vocabulary **pack** (`../pack/`): terms, approved corrections, per-app rules and styles, read by both apps |

## What changed from Whispering

| Change | Where |
|---|---|
| Upstream tree imported unchanged, then pruned to the app and its packages | `UPSTREAM.md` |
| Dependencies pinned (`bun.lock`, exact catalog versions, Rust in `rust-toolchain.toml`) | `package.json`, `rust-toolchain.toml` |
| Renamed VoiceFlow: product name, window title, UI text, identifier `com.voiceflowkeyboard.desktop` (dev builds: `com.voiceflowkeyboard.desktop.dev`), new icons | `apps/whispering/` |
| Aptabase analytics removed | `apps/whispering/` |
| Upstream auto-updater disabled (the fork's own signed updater comes in M4) | `apps/whispering/src-tauri/` |
| Cloudflare deploy scripts removed | `apps/whispering/` |
| Transcription: `gpt-transcribe` by default; any OpenAI model id can be typed | `src/lib/services/isomorphic/transcription/cloud/openai.ts` |
| Tidy-up: `gpt-5.4-mini` by default (7.11's OpenAI chat models are retired or retiring); any OpenAI model id can be typed | `src/lib/constants/inference/openai-models.ts` |
| API keys in Windows Credential Manager / macOS Keychain, moved out of localStorage on first run | `src-tauri/src/secrets.rs`, `src/lib/stores/settings.svelte.ts` |
| Tidy-up failure or a 10-second timeout pastes nothing and shows Retry tidy-up / Paste raw text; a transcription failure keeps the audio and shows Retry | `src/lib/query/isomorphic/actions.ts` |
| About screen with the licence, the source link and the NOTICE | `src/routes/(app)/(config)/settings/about/` |
| Links, videos and model downloads no longer point at upstream | see "Network calls" below |
| B3.1 look: colours, type, sidebar, status bar; theme follows the system | `packages/ui/`, `src/routes/(app)/_components/` |
| App identity at the hotkey (exe / bundle id, window title kept locally), tiered privacy gates (block list, password-field check, Strict / Best-effort), held recordings with Send anyway, paste-time re-check | `src-tauri/src/context.rs`, `src/lib/privacy/`, Settings > Privacy |
| English is the default transcription language for new installs (Auto stays selectable) | `src/lib/settings/settings.ts` |
| Closing the window keeps VoiceFlow running in the tray (menu bar on macOS), with a one-time notice; a native tray menu (status, Open VoiceFlow, Theme, Start with Windows / Open at login, Quit VoiceFlow) and VoiceFlow tray icons; a second launch shows the running window; the login item starts it hidden | `src-tauri/src/lifecycle.rs`, `src-tauri/src/tray.rs` |
| Recordings are kept as 16 kHz mono 16-bit WAV (was the device format, often 48 kHz stereo float: 12x larger); uploads to cloud services are lossless FLAC encoded in Rust (no FFmpeg), and a recording whose FLAC would pass 24 MB is split into parts of at most 10 minutes at quiet moments, transcribed in order with the same prompt | `src-tauri/src/speech_audio.rs`, `src/lib/query/isomorphic/transcription.ts` |

Code paths keep upstream's names (`apps/whispering`, `@epicenter/*` packages) so diffs against the upstream tag stay readable.

## Build on Windows

You need:

| Tool | Version |
|---|---|
| [Bun](https://bun.sh) | 1.3.3 (the `packageManager` field in `package.json`) |
| Rust via rustup, MSVC toolchain | 1.100.0 (`rust-toolchain.toml`; rustup installs it on first use) |
| Visual Studio 2022 Build Tools | "Desktop development with C++" workload |
| WebView2 | ships with Windows 11 |

Steps, from a "x64 Native Tools" prompt, or a prompt where you ran `vcvars64.bat` first. That puts MSVC's `link.exe` ahead of Git's `link.exe` on `PATH`; without it the Rust build fails to link.

```bat
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set CARGO_TARGET_DIR=C:\vf\target
bun install --cwd desktop --frozen-lockfile
bun run --cwd desktop\apps\whispering tauri build --bundles nsis
```

- `CARGO_TARGET_DIR` keeps Rust's build output on a short path. Windows' 260-character path limit broke upstream's build from deep folders; also set `git config core.longpaths true` and, once as admin, the `LongPathsEnabled` registry value.
- The installer lands in `%CARGO_TARGET_DIR%\release\bundle\nsis\VoiceFlow_<version>_x64-setup.exe`.
- The first build downloads ONNX Runtime (for the local Parakeet model) and the NSIS tools.
- The Tauri CLI may rewrite `src-tauri/Cargo.toml` with different line endings; discard that change (`git checkout -- <file>`).
- Builds are unsigned. Windows SmartScreen warns once when you install.

## Build on macOS

CI builds a universal (Apple Silicon + Intel) app on every push that touches `desktop/`: `.github/workflows/desktop.yml`. Locally:

```sh
rustup target add aarch64-apple-darwin x86_64-apple-darwin
bun install --cwd desktop --frozen-lockfile
bun run --cwd desktop/apps/whispering tauri build --target universal-apple-darwin --bundles app,dmg
```

The build is ad-hoc signed (no Apple Developer ID yet), so macOS asks you to "Open Anyway" in System Settings the first time.

## Licence

| Directory | Licence |
|---|---|
| `desktop/` (this folder) | GNU Affero General Public License, AGPL-3.0 (version 3 only, as upstream declares) (`LICENSE`). Upstream copyright and notices are kept; VoiceFlow's modifications are described in `NOTICE` |
| `pack/` | MIT (`../pack/LICENSE`) |
| Android app (repository root) | MIT (`../LICENSE`) |

- Each directory's licence covers only that directory.
- The app's sounds are VoiceFlow's own, generated by `tools/sounds/generate.mjs` (no samples) and released under CC0 1.0; see `NOTICE`.
- **Do not copy any code from this folder into the Android app or `pack/`.** It would bring them under the AGPL. The apps share only data (the pack), never code.
- Giving a build to anyone else means also offering them the corresponding source (AGPL section 6).

## Network calls

The app calls no VoiceFlow, Whispering or Epicenter server: no telemetry, no update check. What it does reach:

| When | Host | Why |
|---|---|---|
| Each dictation, with a cloud service selected | The chosen provider's API (OpenAI by default; Groq, Deepgram, ElevenLabs, Mistral, Anthropic, Google, OpenRouter, or a custom endpoint if picked) | Transcription and tidy-up |
| Only when the user downloads a local model | `huggingface.co` (Parakeet: `istupakov/parakeet-tdt-0.6b-v3-onnx` at a pinned revision; Whisper: `ggerganov/whisper.cpp`; Moonshine: `UsefulSensors/moonshine`) | Model files |
| Only when the user clicks a link | `github.com/yutungh/voiceflow`, provider sign-up and docs pages | Help |

## Where the planning lives

| What | Where |
|---|---|
| Product and architecture spec | [`../docs/desktop/SPEC.md`](../docs/desktop/SPEC.md) |
| Research notes | [`../docs/desktop/research/`](../docs/desktop/research/) |
| Design inventory of Whispering 7.11 and the Android brand | [`../docs/desktop/design/inventory/`](../docs/desktop/design/inventory/) |
| Three UI directions (mockups and screenshots) | [`../docs/desktop/design/directions/`](../docs/desktop/design/directions/) |
