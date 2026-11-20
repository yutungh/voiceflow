# VoiceFlow Desktop v2 plan

Agreed 2026-11-17 after a code audit and two rounds of Claude–Codex co-reasoning (converged). Supersedes the M1R/M2 ordering in `SPEC.md` §8 where they differ; the spec's §3 profile model is revised as below.

## Goals
- Feels like VoiceFlow, not a restyled Whispering, before it is open-sourced.
- Typeless-style floating bar at the bottom-centre while recording.
- Closing the window keeps VoiceFlow running in the tray (Open / Quit; start hidden with the OS).
- Word lists and shared config in a shared folder (for example on a home NAS): household + personal profiles, one per person, plus an optional layer generated from the user's own notes. "Add a word / Fix a spelling" saves to the user's profile.
- Open-source users bring their own AI to manage their profile files (documented format + guide); no dependency on any particular storage or notes system.
- Household use, Windows + macOS.

## Phases

| Phase | Scope | Gate / done when |
|---|---|---|
| **A0** | Uploads as 16 kHz mono (pure-Rust FLAC or WAV); split dictations that would exceed ~24 MB | Long dictations transcribe; CI green |
| **A-gate** | Prove the engine works with the main window hidden: installed release build, hidden > 1 h, sleep/resume, full pipeline, cancel, timeout recovery, exactly one delivery; repeat after hidden autostart; Mac early | Passes before any bar work. On failure: fix the smallest failing mechanism and rerun; move the engine to Rust only if that can't deliver |
| **A1** | Close → hide (Rust), native tray menu Open / Quit, single-instance show, start hidden at login | Dictation works hidden, after login, after sleep; Quit exits |
| **A2** | Floating bar: pre-created hidden, transparent, undecorated, non-activating webview, tightly sized, bottom-centre of the target monitor's work area (DPI-aware); renders `dictation://state` + level events, sends commands to the single engine; Mac NSPanel spike | Never takes focus during dictation; deliberate clicks restore the displaced external target; visible fallback if restore fails |
| **C1** | VoiceFlow identity: B3.1 Home, History, device Settings, recovery states; user-facing terminology and routes; own version number. Bundle identifier, data locations, internal package names and storage keys unchanged (migrations in D) | Daily workflows look and read as VoiceFlow; no upstream wording or endpoints in the UI |
| **B + C2** | Rust profile service: profile I/O, validated snapshots, last-good cache, queued edits; schema + fixtures + "connect your own AI" guide; Vocabulary and Styles screens; Styles replace the transformation builder (migrate an existing clean-up prompt first); Add a word | Household/personal isolation; a spelling added on one device reaches the others; malformed files rejected; offline dictation unaffected |
| **D** | Selective deletion after parity: unused workspace packages, web build, browser recorder/VAD, FFmpeg + shell command handlers, transformation run machinery, IndexedDB (after inspecting and backing up this install's data); internal renames and storage-key migrations; narrow CSP/capabilities; resolve sound-file licensing; CI typecheck + `cargo test` | Packaged Windows/Mac builds pass recording, privacy and recovery checks |
| **E** | Manual installers on the NAS first; then the Tauri updater with VoiceFlow's own signing key, authenticated NAS manifest, source bundles, installs only while idle; Mac self-signed identity (one backed-up certificate, same bundle ID and path) tested across two builds for Microphone/Accessibility retention | One real update succeeds on each platform; permission retention verified (else consider the Apple Developer Program) |

## Decisions
- **Engine stays in TypeScript** in the main webview. Revisit only if the A-gate fails, IPC cost is measured to matter, or a headless engine becomes necessary.
- **Profiles.** Precedence: defaults < household < generated (from notes) < personal < app edits. Stable IDs; collisions, references and cycles validated after merging. A snapshot is pinned per dictation; refresh at start, hourly and on demand, never during a dictation.
- **Ownership.** Profile-owned settings (models, styles, prompt terms, corrections, app rules, privacy lists) come only from the profile service; they are no longer read from or written back to localStorage (separate effective reads from device-only writes; reset/update paths respect ownership). Device prefs (hotkeys, mic, theme) stay in localStorage until AI editing or independent Rust ownership needs a file.
- **Add a word.** Immutable `<device>-<uuid>` operation files, written locally then uploaded idempotently; tombstones for removals; conflicting values shown for resolution. No compaction in v2 (≈10 ops/day); a consumer that compacts must record consumed op IDs explicitly. This replaces the older "no local edits" rule.
- **Transport.** Folder/SMB first; WebDAV once unattended mounts or remote access require it, after ACL and publication tests.
- **Paste target.** If the foreground app at paste time differs from the record-start target and the new target is identified and allowed, paste there and show "Pasted into <app>" using the paste-time authorization's app name. Unknown, blocked, password-field or VoiceFlow-itself targets hold with recovery actions. An inconclusive password check still holds under Strict, and held or "Send anyway" recordings never auto-paste. Ordinary Stop keeps intentional switches; after a bar click, restore the external target the click displaced; explicit Retry / Paste raw keep the saved-target recovery.
- **Tray.** A native menu, not a third webview.
- **Licence.** Everything under `desktop/` stays AGPL-3.0 (version 3 only) however much is rewritten.

## Addendum 2026-11-17: style switching on the bar, and feedback (reviewed with Codex, single pass)
- **A2 bar adds per-dictation overrides.** Style chips (a compact favourites row, with the per-app default preselected) and the Android expression control (stored as **0–4**, with the style-specific guidance from Android's `Prefs.java`; disabled for Raw; labelled detents). The profile is pinned at record start and the overrides are frozen at Stop. Every bar command carries the recording id, so a late click can't affect another recording. Hotkey equivalents exist for everything.
- **Feedback capture** has two entry points that share one engine transition:
  - a dedicated feedback hotkey;
  - the bar's **"Use this recording as feedback"** (an action, not a sticky toggle).

  The previous dictation's id is pinned when the new recording starts, and its app, time and snippet are shown; "last" is never resolved again at Stop. Feedback audio bypasses tidy-up, replacements and delivery, then opens an editable transcript for submission.
- **Delivery notice.** After delivery the bar shows **Pasted into <app>**, **Copied** or **Held**, plus a Feedback button for a few seconds. A new recording supersedes it. History and the tray keep "Give feedback".
- **C1 (now):** History's "Give feedback" saves a local draft. "Fix a spelling" writes local op files (the B queue format), applied once to the final output, non-cascading. There is no second persistence path through style find/replace.
- **Phase F: feedback and fixes, immediately after B+C2 (before D/E).**
  - **This result:** first release is an editable corrected output (default action Copy; Paste restores and re-authorizes the target).
  - **Lasting fixes:** optional "Remember this spelling" (a personal, app-scoped correction by default) and a direct style/expression adjustment.
  - **Open-ended AI style editing comes later, under these rules:**
    - the fix engine (Claude or OpenAI, BYO key) gets stage evidence: raw transcript, pre-correction tidy-up, delivered text, effective style and expression, rule ids, delivery outcome;
    - it may answer "uncertain / no durable fix";
    - its proposals are validated deterministically (whole-word, once, non-cascading, collision examples shown);
    - lasting fixes start unchecked, with exact scope and consequences;
    - it edits one identified style preference rather than appending rules, with immutable meaning/language constraints kept outside its reach;
    - a diff with before/after examples is shown, and conflict and length limits apply;
    - fixes apply against the reviewed profile revision (stale proposals are re-reviewed);
    - Undo compensates the specific op;
    - paste and privacy complaints become diagnostics only.
  - **Disclosure:** Submit names the provider and what is sent (relevant vocabulary only; window titles stay local). Feedback logs have retention and deletion controls.
- **Biggest risk:** an approved, plausible fix turning one contextual complaint into durable damage across dictations and devices. Scope, bounded edits and reversible ops matter more than how convincing the diagnosis sounds.

## Addendum 2026-11-17: speed (measured) and streaming (reviewed with Codex, single pass)
- **Measured today:** about 3.1 s from release to paste for a 30 s dictation (FFmpeg on). Upload and transcription ~1.1 s, tidy-up ~1.1 s, FFmpeg 0.29 s, a fresh connection +76 ms p50 / +590 ms p90, duplicate file writes ~0.15 s, a fixed 50 ms paste wait. Tidy-up model settings don't help (default reasoning is already none). Details: `desktop/tools/bench/` and the Settings > Speed panel (branch `desktop/speed`).
- **Batch quick wins, next build:** A0 FLAC; one reused HTTP connection opened at the hotkey (Rust client or the webview's own fetch; `tauri-plugin-http` 2.5.4 serialises request bodies as JSON number arrays and opens a new connection per request); remove the duplicate WAV write/read and the fixed paste wait. Target ≈2.3 s.
- **A3: opt-in streaming** (`gpt-live-transcribe`, $0.017/min vs $0.0045/min for gpt-transcribe; about $2–4/month extra at household volume). It comes after A1/A2 and repeats the A-gate (hidden window, sleep/resume, cancel, exactly-one delivery). Target ≈1.75 s.
  - **Transport vs policy:** Rust owns capture, the separate 24 kHz PCM16 resample for streaming, and WebSocket I/O with bounded queues, never blocking cpal. TypeScript keeps authorization, transcript selection, tidy-up and delivery.
  - **Identities:** recording id, attempt generation, session id, server item_id, pinned profile and purpose. Finals are matched by id, never by arrival order.
  - **Delivery:** one transcript-selection and delivery controller. Choosing batch disqualifies the stream, and late finals can't overwrite or paste. Cancel invalidates the attempt before aborting. Paste and clipboard are serialised across dictations.
  - **Release:** a drain barrier (stop, flush, send every queued frame, commit once). Dropped frames disqualify streaming. The fallback deadline comes from measured tail latency, with one bounded batch fallback.
  - **Sessions:** a fresh session per dictation, opened only after the hotkey-time decision allows upload; context is sent and its configuration accepted before audio. Held/refused recordings never stream, feedback capture doesn't stream at first, and offline stays local.
  - **Wording:** opt-in wording says audio leaves the device while you speak, and Cancel can't recall audio already sent.
  - **Deferred:** speculative tidy-up on near-final text. Skipping tidy-up for short text, if added, is an explicit profile preference judged on the final text; corrections still apply.
