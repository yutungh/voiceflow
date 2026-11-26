# VoiceLnq Desktop changelog

History and status for VoiceLnq Desktop, moved out of `README.md` so the README can stay a setup guide. The desktop app is a fork of Whispering 7.11.0 (see `UPSTREAM.md`).

## Status (milestone notes)

Milestone M1 was in progress when this was moved out of the README. Recorded as it stood:

> Milestone M1 in progress: the fork builds as VoiceFlow, with telemetry removed, the upstream updater disabled, API keys in the OS keychain, visible failures, the B3.1 look, and app identity with tiered privacy gates and paste-time checks (M1c). Next: the workspace extraction and the maintainer's acceptance run.
## Changes from Whispering 7.11.0

| Change | Where |
|---|---|
| Upstream tree imported unchanged, then pruned to the app and its packages | `UPSTREAM.md` |
| Dependencies pinned (`bun.lock`, exact catalog versions, Rust in `rust-toolchain.toml`) | `package.json`, `rust-toolchain.toml` |
| Renamed VoiceFlow: product name, window title, UI text, identifier `com.voiceflowkeyboard.desktop` (dev builds: `com.voiceflowkeyboard.desktop.dev`), new icons | `apps/whispering/` |
| Aptabase analytics removed | `apps/whispering/` |
| Upstream auto-updater disabled (a signed updater of VoiceFlow's own is planned) | `apps/whispering/src-tauri/` |
| Cloudflare deploy scripts removed | `apps/whispering/` |
| Transcription: `gpt-transcribe` by default; any OpenAI model id can be typed | `src/lib/services/isomorphic/transcription/cloud/openai.ts` |
| Tidy-up: `gpt-5.4-mini` by default (7.11's OpenAI chat models are retired or retiring); any OpenAI model id can be typed | `src/lib/constants/inference/openai-models.ts` |
| API keys in Windows Credential Manager / macOS Keychain, moved out of localStorage on first run | `src-tauri/src/secrets.rs`, `src/lib/stores/settings.svelte.ts` |
| Tidy-up failure or a 10-second timeout pastes nothing and shows Retry tidy-up / Paste raw text; a transcription failure keeps the audio and shows Retry | `src/lib/query/isomorphic/actions.ts` |
| About screen with the licence, the source link and the NOTICE | `src/routes/(app)/(config)/settings/about/` |
| Links, videos and model downloads no longer point at upstream | see "Network calls" in `README.md` |
| B3.1 look: colours, type, sidebar, status bar; theme follows the system | `packages/ui/`, `src/routes/(app)/_components/` |
| App identity at the hotkey (exe / bundle id, window title kept locally), tiered privacy gates (block list, password-field check, Strict / Best-effort), held recordings with Send anyway, paste-time re-check | `src-tauri/src/context.rs`, `src/lib/privacy/`, Settings > Privacy |
| English is the default transcription language for new installs (Auto stays selectable) | `src/lib/settings/settings.ts` |
| Closing the window keeps VoiceFlow running in the tray (menu bar on macOS), with a one-time notice; a native tray menu (status, Open VoiceFlow, Theme, Start with Windows / Open at login, Quit VoiceFlow) and VoiceFlow tray icons; a second launch shows the running window; the login item starts it hidden | `src-tauri/src/lifecycle.rs`, `src-tauri/src/tray.rs` |
| Recordings are kept as 16 kHz mono 16-bit WAV (was the device format, often 48 kHz stereo float: 12x larger); uploads to cloud services are lossless FLAC encoded in Rust (no FFmpeg), and a recording whose FLAC would pass 24 MB is split into parts of at most 10 minutes at quiet moments, transcribed in order with the same prompt | `src-tauri/src/speech_audio.rs`, `src/lib/query/isomorphic/transcription.ts` |

Code paths keep upstream's names (`apps/whispering`, `@epicenter/*` packages) so diffs against the upstream tag stay readable.

