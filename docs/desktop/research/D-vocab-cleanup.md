## Aspect: Vocabulary learning and clean-up (subagent return, 2026-11-16)

### Sources
1. https://docs.wisprflow.ai/articles/4052411709-teach-flow-your-words-with-the-dictionary — Oct 2026 (snippet 2026-10-18) — Wispr Flow dictionary, auto-learn, import [primary]
2. https://wisprflow.ai/canto-1-results — "09.11.2026" (ambiguous) — own model post-trained for personal context [primary, vendor]
3. https://wisprflow.ai/research/technical-challenges — Sep 2025 — context-conditioned ASR, zero-edit goal [primary, vendor]
4. https://tryvoiceink.com/docs/vocabulary + /docs/auto-learn-dictionary — undated — VoiceInk Vocabulary vs Replacements; Auto Learn with screen context [primary]
5. https://superwhisper.com/docs/get-started/interface-vocabulary + interface-replacements — undated — vocabulary vs replacements, CLI [primary, snippets]
6. https://docs.openwhispr.com/guides/custom-dictionary.md — undated — dictionary = model hint; snippets = exact substitution [primary]
7. https://developers.openai.com/api/docs/guides/speech-to-text — undated (current) — gpt-transcribe `prompt` + `keywords`, LLM post-pass with term list, 4o-transcribe shutdown 2027-02-26 [primary]
8. https://www.assemblyai.com/blog/best-llms-dictation-cleanup — 2026-10-28 — cleanup LLM benchmark, latency, failure modes, prompt rules [secondary, vendor]
9. https://tap2talk.pages.dev/blog/custom-cleanup-prompt-dictation + /groq-fastest-dictation-app — undated — over-applied rules; Groq cleanup 200–400 ms [secondary, vendor]
10. https://arxiv.org/abs/2505.17410 — May 2025 (Interspeech 2025) — LLM GER for rare words; phonetic context reduces over-correction [primary, ~18 mo]
11. https://arxiv.org/abs/2505.19179 — May 2025 — BR-ASR: long prompts cause hallucination; retrieve bias words [primary, ~18 mo]
12. https://arxiv.org/pdf/2607.17079 (Jul 2026, SALMONN-2) + https://arxiv.org/pdf/2604.12398 (Apr 2026) — distractor-heavy bias lists hurt (9.3→19.8 WER at 100 entries) [primary, snippets]
13. https://arxiv.org/pdf/2411.06437 — Nov 2024 — WER/B-WER rise mildly 100→2,000 terms [primary, possibly stale]
14. https://feedback.clickup.com/clickup-brain-desktop/p/over-aggressive-custom-dictionary-matching-in-voice-to-text — 2026-06-09 — over-aggressive dictionary forcing [practitioner]
15. https://community.obsidian.md/plugins/voxtral-transcribe — v1.21.1, ~Nov 2026 — Obsidian plugin mining vault vocabulary with caps [primary]

### Key findings
- Common pattern: transcription-time biasing (prompt/keywords) = soft; post-transcription replacement = deterministic; learning from corrections only proposes entries.
- Wispr Flow: manual entries (≤60 chars), "Correct a misspelling" = whole-word case-insensitive find-replace; Auto-learn from post-dictation corrections (Mac+Windows), deleted words never re-added; import up to 1,000 items; own Canto 1 model trained for personal context (-13% desktop edit rate). [1][2][3]
- VoiceInk: Vocabulary = hints to model + AI enhancement (never forces), local phonetic fix; Word Replacements = exact; Auto Learn suggests Replacements; screen context. [4] Superwhisper same split + CLI (`superwhisper vocab`/`snippets`). [5] OpenWhispr dictionary = hint, snippets = exact; auto-learn-from-edits unconfirmed. [6]
- OpenAI documents: names/acronyms in `prompt`; `gpt-transcribe` adds `keywords` ("hints, not required output"); then a text-model post-pass with correct spellings ("can handle longer terminology lists"); caveat "Evaluate corrections against the original audio". gpt-4o-transcribe and gpt-4o-mini-transcribe shut down 2027-02-26. [7]
- Forum reports of 1,024-char prompt cap on realtime gpt-4o-transcribe (undocumented).
- Over-correction main risk; phonetic context + N-best fixes rare words and lowers WER [10]; forced dictionary terms [14]; bias-list size hurts B-WER [13]; distractor lists 9.3→19.8 WER [12]; long prompts → hallucination, retrieve per utterance [11]. Voxtral plugin caps 50 vault terms, 100 in correction [15]. (Inference: few dozen terms at transcription; long list in correction.)
- Cleanup prompts (AssemblyAI 2026-10-28): explicit command map, command words never in output, keep every other word verbatim, output only final text; "reads better than what was said" = failure. Vague "remove fillers" over-applies [9]. Models: Gemini 2.5 Flash Lite 97 score ~0.7 s on 30 s clips (82 on 2-min); Qwen3.5 4B ~0.28 s 81; Groq 200–400 ms (vendor). [8][9]
- Vault sync: Voxtral Transcribe Obsidian plugin mines headings/links/titles/aliases/tags (not contents), frontmatter `voxtral-vocabulary` + global list, dedupe + caps. [15] No tool mines past transcripts into misheard→correct table; Wispr/VoiceInk do it from user edits.

### Recommended design (subagent)
- Generated `vocab.json` from the user's own notes (person names and aliases, topic titles and aliases, a hand-kept term list with "said as" hints and known mishearings, link targets); regenerate on vault change/nightly.
- Stage A: ≤30–50 priority terms as gpt-transcribe `keywords` + short `prompt`.
- Stage B: deterministic whole-word replacements from known wrong forms (Mylo→Milo, Phoebe→Febe).
- Stage C: LLM cleanup with full glossary as closed list ("replace only if it sounds like a glossary term and fits"), explicit disfluency rules, never answer the text, fast model with timeout fallback to Stage B.
- Learning loop: diff post-paste edits → proposed rows for approval; never auto-commit.
- Measure: keep raw/StageB/final; regression set of real recordings with the names.
- Style mode = separate transformation with 3–5 samples; per-app presets.

### Couldn't verify
Typeless/Aqua/Willow/TypeWhisper/Handy vocab internals; OpenWhispr auto-learn; Wispr Canto date; documented gpt-transcribe prompt/keywords limit; style-from-samples evidence; independent latency numbers.
