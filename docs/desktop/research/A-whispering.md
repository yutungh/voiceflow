## Aspect: Whispering today (subagent return, 2026-11-16)

### Sources
1. https://github.com/EpicenterHQ/epicenter/releases (gh API) — 2026-11-16 — releases, v7.8–7.11 notes [primary]
2. https://github.com/EpicenterHQ/epicenter/blob/main/apps/whispering/README.md — main 2026-11-16 (README last changed Oct/Nov 2026) — what Whispering-on-main is now [primary]
3. Source at tag v7.11.0 (2025-12-27): apps/whispering/src/lib/settings/settings.ts, routes/(app)/(config)/settings/transcription/+page.svelte, services/isomorphic/transcription/{cloud/*,self-hosted/speaches.ts,registry.ts}, constants/database/transformation-types.ts, services/isomorphic/db/models/transformation-steps.ts, stores/settings.svelte.ts, src-tauri/src/lib.rs [primary]
4. Commits on main + apps/whispering (gh API) — 2026-10-16→2026-11-16 [github-repo]
5. Issue #1703 "Make a new release of Whispering to enable scribe v2" — open, 2026-04-24, comments to 2026-06-21 [github-repo]
6. Issue #2494 closed 2026-10-08 by braden-w ("preparing a substantial update for Whispering v8.0.0") + PR #2507 merged 2026-10-11 [github-repo]
7. Issue #2464 — maintainer Leftium 2026-08-01 / 2026-08-31 [github-repo]
8. Issues #904 (open, 2025-10-20 personal dictionary), #1320 (closed not planned 2026-08-15, ElevenLabs keyterms UI), #1208 (closed not planned 2026-07-07, multiple transformations) [github-repo]
9. Windows issues #2457 (2026-07-19), #2031 (2026-06-16), #2432/#2421 (2026-07), #1770 (2026-05-17) [github-repo]
10. LICENSE on main + v7.8.0 "update license to AGPL-3.0" (PR #1007) [primary]
11. WebSearch "Whispering v8 2026" — no v8 found; third-party "MIT" claims outdated [secondary]

### Findings
1. Latest release still 7.11.0 (2025-12-27); ~11 months no release; previously ~weekly (7.5→7.11 Sep–Dec 2025). Users ask for a build (#1703, scribe_v2 merged but unshipped). Main very active but on Epicenter platform (data folders, CLI v0.4.1 2026-11-16, accounts/sync). v8 "not shipped yet" (#2494). Maintainer 2026-08-31: "Whispering may not even compile successfully right now" (#2464). 207 open issues; many closed not planned Jul–Aug 2026.
   README on main: "What is not supported": "System-wide dictation beyond the desktop's one start-and-stop shortcut: push-to-talk, voice-activated recording, and delivering text to the cursor. Copy buttons are the only way text leaves the app." "Automatic transcription or transformation. Each is a button you press." "Transformations beyond one prompt and one request." "Windows and Linux are untested." No migration from earlier builds.
   License AGPL-3.0-or-later since 7.8.0 (MIT before).
2. Providers 7.11.0: OpenAI whisper-1 / gpt-4o-transcribe / gpt-4o-mini-transcribe (fixed Select; `apiEndpoints.openai` base-URL override); Groq whisper-large-v3/turbo; Deepgram nova-3/2/…; ElevenLabs scribe_v1/v1_experimental; Mistral voxtral-mini/small; "Speaches"/OpenAI-compatible (free Base URL+Model ID, 7.9) but sends NO API key; local Parakeet only on Windows (whisper.cpp + Moonshine removed from Windows in 7.11). v8 main: free "connections".
3. Vocabulary: Settings → Transcription → "System Prompt" (key `transcription.prompt`), help: "Helps transcription service (e.g., Whisper) better recognize specific terms, names, or context during initial transcription." OpenAI + Groq: sent as API `prompt`. Speaches/whisper.cpp: prompt/initialPrompt. Deepgram: whole text as ONE keyterm/keywords value. ElevenLabs + Mistral: UI enabled but silently dropped. Parakeet/Moonshine: disabled. No personal dictionary/keyterms list/word replacement (#904 open; #1320 not planned). Prompt stored in WebView localStorage (`whispering-settings`) — not cleanly externally updatable.
4. Transformations 7.11: steps `prompt_transform` + `find_replace` (regex option). Providers OpenAI/Anthropic/Google/Groq from fixed lists (Groq list stale — all fail, #2494); OpenRouter + Custom (OpenAI-compatible, 7.8) take any model. One active transformation, auto-runs after each transcription; multi refused (#1208). v8: one prompt, manual.
5. Windows: 7.10 Vulkan crashes → 7.11 Parakeet-only; Parakeet "ORT error" (#2457 open); close button doesn't quit (#2031); start minimized/tray (#1770); build-from-source broke Jul 2026 (#2432 260-char path, #2421). No recent Windows paste/long-recording reports found.
6. Fork: Bun 1.2.19+ monorepo, SvelteKit/Svelte 5, Tauri 2 (Rust), Rust/CMake (transcribe-rs, ONNX). 7.11 tag 1,645 files (481 in apps/whispering); main 3,594; ~144 MB. No plugin API / HTTP API / CLI. Vocab file reread = small change in a 7.11 fork (tauri_plugin_fs). But permanent solo fork of abandoned 7.x, AGPL if distributed.

### Take
- Quick win: paste jargon into System Prompt with gpt-4o-transcribe today; test.
- Don't wait for upstream: v8 removes paste-to-cursor, auto-run, multi-step; Windows untested.
- Fork = own frozen 7.11 forever.
- Keyterm providers broken (Deepgram/ElevenLabs).
- Vault sync needs fork or app that reads a file.

### Couldn't verify
System Prompt length caps; forcing new model via base-URL override (type accepts string, UI doesn't); v8 date; >25 MB handling (compressionEnabled FFmpeg exists, not traced); actual Windows behaviour.
