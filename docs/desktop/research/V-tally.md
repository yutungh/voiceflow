# Verification tally (deep mode)

C1 OpenAI deprecation/gpt-transcribe/keywords/$0.0045 — 3 survive / 0 refute — CONFIRMED (high)
- Deprecations table: Feb 26, 2027 for whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe (+2025-12-15 snapshot), gpt-4o-transcribe-diarize, /v1/audio/translations; notice Aug 26, 2026; replacement "gpt-live-transcribe or gpt-transcribe".
- Guide: "Start with gpt-transcribe. This is the recommended model for transcribing recorded speech in its original language." "Keywords are hints, not required output." languages replaces singular language — don't send both; keywords containing < > CR LF reject request. Python SDK passes keywords via extra_body (no typed param yet). Word timestamps remain whisper-1 only.
- Model page: $0.0045/min; "supports unstructured context, keyword hints, and multiple language hints".

C2 Whispering frozen; README excludes paste/auto — 3 / 0 — CONFIRMED (high)
- Latest Whispering release v7.11.0 2025-12-27T02:38:16Z; only later releases epicenter-cli v0.4.0/v0.4.1 (2026-11-16, mac/linux only). No maintenance branch. main package.json 7.11.1 unreleased.
- README main (b5df2ff7, 2026-11-14) quotes exact; describes Whispering as "an app for voice notes".
- ADR-0596 (2026-10-17) moved native dictation (write_text, auto paste, global-shortcut, clipboard) behind off-by-default `dictation` Cargo feature; superseded by ADR-0611 "Epicenter is only the data layer its apps reach over Git".
- Dictation requests closed NOT_PLANNED: #1426 paste-to-cursor (2026-11-15), #1153 Fn key (2026-11-12), #1720 paste hotkeys (2026-11-02), #1433 Caps Lock PTT Win11 (2026-10-10); PR #2072 closed unmerged 2026-11-11.
- Nuance: repo very active; it is moving away from dictation, not idle. "Frozen" understates.

Inline check (Claude, not a panel): TypeWhisper v1.2.2 tag OpenAiTranscriptionClient.cs line 36 sends keywords[]; OpenAiPlugin.cs calls DictionaryKeywords(prompt); 600-char budget. Closes Codex's round-2 caveat.

## C3: TypeWhisper 1.2.2 has a free local dictionary API and per-workflow LLM. Result 3 to 0, CONFIRMED (high), with corrections to the wording
- v1.2.2 is stable: published 2026-11-03T12:35:22Z, prerelease=false. Licence GPL-3.0.
- docs/WINUI-HTTP-API.md at v1.2.2 lists GET/PUT/DELETE /v1/dictionary/terms. PUT takes a terms or term_entries array (up to 10,000), with an optional replace.
  - LocalApiDataHandler.cs line 134 shows that `replace` removes every existing Term entry, then validates the batch and writes it in one atomic step. Entries starting with `pack:` are kept.
- **Correction:** there is no bulk replace for /v1/dictionary/corrections. PUT upserts one {original, replacement, caseSensitive, isRegex}; DELETE removes one entry. Syncing means a GET, a diff, then one call per entry, which is not atomic.
- PremiumAccess.cs gates only three features: CalendarMeetings, CorrectionLearning (needs Commercial) and CloudSync. The API, dictionary, workflows and BYO-key providers are free.
  - Pricing page: Free GPLv3 "Core dictation, local engines, workflows, add-ons, history, snippets, and APIs".
  - LICENSE-COMMERCIAL.md: "You do not need a commercial license merely to install and run an unmodified GPL copy … including for internal professional or commercial work". Licensing is honor-based, with "no nagging popups".
- The HTTP API is **off by default**: turn it on under Settings > Advanced > HTTP API (port 8978).
  - It accepts loopback connections only, rejects requests with a browser Origin header, and checks the caller is the same Windows user.
  - The bearer token is optional and off by default.
  - TypeWhisper must be running.
- Workflow.cs has ProviderOverride, ModelOverride and TranscriptionModelOverride per workflow. The API can list and toggle workflows but cannot create or edit them (#722 is open).
- Open bug #758: "Request-specific transcription models overwrite the saved model". It affects the transcription API.

## C4: Scribe v3 is the first challenger, ahead of Nova-3. Result 3 to 0, CONFIRMED (two high, one medium), with qualifications
- Artificial Analysis, non-streaming, 2026-11-16 (AA-WER v2: AgentTalk 50%, VoxPopuli 25%, Earnings22 25%).

  | Model | AA-WER | Rank / note | Price per 1,000 min | Speed factor |
  |---|---|---|---|---|
  | Fun-Realtime-ASR-preview | 1.7% | 1st | | |
  | Scribe v3 | 1.9% | 2nd of 47 | $3.33 | 74.3 |
  | MAI-Transcribe-2 | 2.0% | | $1.67 | |
  | GPT Transcribe | 3.3% | | $4.50 | 48.4 |
  | GPT-4o Transcribe | 4.0% | | | |
  | Nova-3 (Deepgram) | 5.2% | Telnyx-hosted: 4.8% | $4.30 | 426.8 |

- ElevenLabs:
  - Scribe v3 is the batch model: "Batch supports up to 1000 keyterms (50 characters each), while realtime supports up to 50 keyterms (20 characters each)". Batch files up to 5 GB and 15 h.
  - Price: $0.20/hr, plus $0.05/hr for keyterms (about $0.25/hr), against $0.27/hr for gpt-transcribe.
  - Scribe v3 Live scores 2.9% on the streaming board.
- Independent benchmark (superwhisper.com/benchmarks, updated 2026-09-03):

  | Model | WER | Vocab recall | Vocab F-score |
  |---|---|---|---|
  | Scribe v2 | 7.5% | 91.0 | 91.4 |
  | GPT Transcribe | 7.8% | 90.9 | 93.6 |
  | Nova-3 | 10.1% | 83.3 | 86.3 (worst) |

  On this benchmark Scribe and GPT Transcribe are roughly tied, and GPT Transcribe is slightly better on vocabulary F-score. Scribe v3 has not been tested independently yet. Appen MedTerm-90 ranks Scribe v2 above gpt-transcribe.
- **Qualification:** "clearly ahead of gpt-transcribe" holds only on AA. Nova-3 trails everywhere. MAI-Transcribe-2 is an unexamined alternative.

C5 Short relevant list beats long lists — 2 survive / 1 refute — SURVIVES (medium), numeric range demoted to heuristic
- arXiv 2604.07354 (28 Mar 2026): context raises keyword F-score for all 6 systems; "global context primarily stresses precision… distractor-induced false positives"; "for OpenAI, WER increases under context" (whisper-1 only); artifacts (repetition, truncation, language switch). Does NOT sweep list length; global lists ~16 terms/call.
- AssemblyAI "When keyterm prompting backfires" (2026-09-30): 1,000 = "a ceiling, not a target"; "A scoped twenty-term list will usually beat an exhaustive eight-hundred-term one"; "A list that was safe at thirty terms can collide at sixty"; a short surname was rewritten into a different [person]'s full name from the list, a false insertion in 3/3 runs. Not benchmarked numbers.
- Deepgram: "focus on the most important 20-50 terms" under "Excessive keyterms" (no stated reason).
- OpenAI guide: "Include only relevant terms, and evaluate whether they improve accuracy without causing unspoken terms to appear"; no keyword limit stated.
- OpenAI forum 2026-01-07: gpt-4o-*-transcribe outputs last sentence of prompt during silence/noise (sentence-style prompts).
- Refuting voter: "20–60" not backed by any source; relevance > count. TypeWhisper 600-char cap ≈ 40–60 terms sets the number anyway.
- Resolution: keep direction; state range as vendor heuristic, verify by A/B on real audio scoring whole transcript.

C6 Whispering 7.11 System Prompt → OpenAI prompt — 3 / 0 — CONFIRMED (high)
- settings.ts L176 `'transcription.prompt': "string = ''"`; +page.svelte L846–859 label "System Prompt", enabled when supportsPrompt; registry OpenAI/Groq supportsPrompt true; transcription.ts L184/196 passes prompt; openai.ts L133 `prompt: options.prompt || undefined`; groq.ts L126. ElevenLabs/Mistral receive but never send (UI still enabled). No app length cap.
- whisper-1 uses only the final 224 tokens → long list silently truncated from the front; same dropdown has gpt-4o-transcribe/mini with better prompt handling. Issue #761 maintainer points to Settings > Transcription > System Prompt.
