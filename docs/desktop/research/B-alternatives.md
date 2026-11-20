## Aspect: Open-source alternatives (subagent return, 2026-11-16)

### Sources
1. https://github.com/TypeWhisper/typewhisper-win — README, docs/WINUI-HTTP-API.md, docs/WINDOWS-CLI.md, docs/WINUI-FUNCTIONAL-STATUS.md, plugins/, plugins/TypeWhisper.Plugin.OpenAi/README.md + OpenAiTranscriptionClient.cs — read 2026-11-16 [github-repo, primary]
2. https://github.com/OpenWhispr/openwhispr — README, src/utils/dictionaryPromptCap.js, src/helpers/cliBridge.js, src/helpers/transcriptionRoute.ts, src/helpers/dictionaryImport.js — 2026-11-16 [github-repo, primary]
3. https://github.com/cjpais/Handy — README, src-tauri/src/settings.rs — 2026-11-16 [github-repo, primary]
4. https://github.com/voquill/voquill — README, cli/README.md — 2026-11-16 [github-repo, primary]
5. https://github.com/Kieirra/murmure — README — 2026-11-16 [github-repo]
6. https://github.com/kstonekuan/tambourine-voice — README — 2026-11-16 [github-repo]
7. https://usevoicy.com/blog/openwhispr-review/ — updated 2026-11-10 — OpenWhispr free vs Pro, learning dictionary (competitor-written) [secondary]
8. https://www.promptquorum.com/power-local-llm/openwhispr-review — 2026-10-12 — privacy modes; not hands-on [secondary]
9. https://spokenly.app/blog/handy-review (undated) + https://betterstack.com/community/guides/ai/handy-offline/ (undated) — Handy praised free/offline, minimal [secondary]
10. https://newreleases.io/project/github/TypeWhisper/typewhisper-win/release/v1.0.9 — undated — release notes incl. Groq "Thank you" hallucination filter [secondary]
11. gh api repo stats/releases/commits since 2026-08-16; TypeWhisper open issues — 2026-11-16 [primary]
Local copies of READMEs were kept in the research session's scratchpad (not published).

### Table
| App | License | Stars | Latest release | Windows | OpenAI 4o / custom endpoint | Vocab | LLM cleanup | Extensibility |
|---|---|---|---|---|---|---|---|---|
| TypeWhisper (Win) | GPL-3.0 + commercial | 244 (Mac repo 1,927) | stable v1.2.2 2026-11-03; daily v1.3.0 2026-11-16 | native WinUI 3/.NET x64 | Yes (OpenAI plugin) / Yes (OpenAiCompatible); Groq, Deepgram, ElevenLabs, AssemblyAI, Soniox, Speechmatics, Gladia, local whisper.cpp/Parakeet | terms → OpenAI `prompt`; corrections list; local HTTP API `PUT /v1/dictionary/terms` with `replace`; CLI; backup import | Workflows: named, app/site matching, own hotkeys, explicit LLM per workflow (OpenAI, Claude, OpenRouter, Gemini, Groq, compat, local) | plugin SDK, HTTP API, CLI, Script, Webhook, Obsidian, FileMemory plugins |
| OpenWhispr | MIT (open-core) | 11,667 | v1.11.0 2026-10-29 | official .exe, Electron | Yes, 8,000-char prompt budget for 4o family / custom base URL; Groq, Deepgram, AssemblyAI, Gemini, local | dictionary → prompt, per-provider budget; local CLI bridge `POST /v1/dictionary/update` add/remove; bulk import; optional learn-from-corrections | local LLM/BYO/cloud; agent mode Pro; multiple named prompts unverified | local CLI bridge free; public API + MCP Pro |
| Handy | MIT | 38,950 | v0.10.1 2026-11-14 | official, winget | NO cloud STT (local Whisper/Parakeet only) | custom_words in settings | multiple post-process prompts; OpenAI/OpenRouter/Anthropic/Groq/custom | CLI toggles; transcription hook mac/Linux only |
| Voquill | AGPL-3.0 | 1,029 | desktop-v0.0.656 2026-11-16 | Tauri | Yes / BYO; OpenRouter | glossary + replacements; no local external update found (CLI → Firebase cloud) | styles Polished/Verbatim/Chat | CLI cloud-account |
| Murmure | AGPL-3.0 | 1,166 | 2.0.3 2026-10-19 | official signed | local Parakeet only | not found | local LLM | `murmure import` |
| Tambourine | AGPL-3.0 | 394 | v0.1.0-alpha 2026-01-15 | yes | OpenAI/Deepgram/AssemblyAI/Groq… | personal dictionary in prompt | editable prompts, per-app | needs own Python Pipecat server; streaming; 4 commits/3 mo |
| Whispering | NOASSERTION (GitHub) | 4,839 (epicenter) | v7.11.0 2025-12-27 | yes | yes | prompt | transformations | releases are epicenter-cli |
Excluded: WhisperWriter (no push since 2024-08); VoiceInk + FluidVoice mac-only (FluidVoice-Desktop Win 7 stars local only); Buzz/Vibe file transcribers; Amical (MIT 1.6k, v1.13.2 2026-11-12, local-first, cloud BYO unverified); Spokenly closed.

### Findings
- TypeWhisper Win: ~40 plugins; dictionary terms sent as OpenAI `prompt` (`content.Add(new StringContent(prompt), "prompt")`); loopback token-auth HTTP API GET/PUT/DELETE /v1/dictionary/terms (replace) and /v1/dictionary/corrections → cleanest auto-update hook; Workflows. Long recordings: compressed upload, narrow WAV fallback, no chunking documented. Risks: 244 stars, no independent reviews; daily builds; package identities changed 1.0→1.1; open issue #758 "Request-specific transcription models overwrite the saved model". His 0.8.3 predates 1.x WinUI rewrite; API documented for 1.x.
- OpenWhispr: per-provider prompt budgets (4o 8,000 chars, "verified live to 40k chars"); free local CLI bridge POST /v1/dictionary/update add/remove (fix for #1295); BYO upload 25 MB cap, optimized audio + WAV fallback; Electron; 519 open issues; open-core (agent mode, public API, MCP Pro); learn-from-corrections watches field post-paste (toggleable); reviews competitor/not hands-on.
- Handy: best-loved free, real post-processing but NO cloud transcription.
- Voquill: AGPL, BYO, 4o-transcribe, OpenRouter, glossary+replacements, styles; pre-1.0; no local dictionary route.
- Murmure local-only; Tambourine alpha needing server.

### Ranking
1. TypeWhisper 1.2.x (upgrade from 0.8.3) — only one meeting all five must-haves out of box; HTTP API replace → ~20-line Obsidian sync script. Cost: fast-moving, small community.
2. OpenWhispr — bigger community, better prompt budgeting, free local dictionary endpoint; Electron, open-core, backlog, delta sync.
3. Voquill, Whispering, Handy (if local OK), Tambourine.

### Couldn't verify
TypeWhisper 0.8.3 API; ≥10-min reliability for TypeWhisper/OpenWhispr; OpenWhispr multiple named prompts/per-prompt model; Voquill local API; Amical cloud; 2026 Reddit/HN on TypeWhisper/OpenWhispr/Voquill; Whispering license per GitHub.
