## Aspect: Transcription models and vocabulary features (subagent return, 2026-11-16)

### Sources
1. https://developers.openai.com/api/docs/guides/speech-to-text — undated, read 2026-11-16 — gpt-transcribe new model, prompt/keywords, deprecations, 25 MB [primary]
2. https://developers.openai.com/api/docs/models/gpt-transcribe — undated — pricing/endpoints [primary]
3. https://developers.openai.com/api/reference/typescript/resources/audio/subresources/transcriptions/index.md — undated — `keywords` "Supported by gpt-transcribe"; gpt-4o-mini-transcribe-2025-12-15 snapshot [primary]
4. https://developers.deepgram.com/docs/keyterm — undated — keyterm limits [primary]
5. https://deepgram.com/pricing — undated — Nova-3 rates [primary]
6. https://assemblyai.com/md/models — current (references 2026-10-27 launch) — models, keyterms limits, Dictation API, pitfalls [primary]
7. https://elevenlabs.io/docs/overview/capabilities/speech-to-text + https://elevenlabs.io/pricing/api — undated — Scribe v3 keyterm limits, Scribe v2 price [primary]
8. https://soniox.com/docs/stt/concepts/context — undated — Soniox context object, limit [primary]
9. https://artificialanalysis.ai/speech-to-text — live, read 2026-11-16 — AA-WER v2 + prices [secondary, independent]
10. https://arxiv.org/html/2604.07354 (Contextual Earnings-22) — Apr 2026 — independent keyterm benchmark [secondary, independent]
11. https://preview.aclanthology.org/ingest-lrec/2026.lrec-main.32/ — LREC 2026 — jargon dataset (snippet only) [secondary]
12. https://community.openai.com/t/gpt-4o-transcribe-outputs-content-from-prompt-instruction-for-small-silent-audio-samples/1367326 — undated — prompt leakage [secondary]

### Table
| Provider/model | Vocab mechanism | Limit | Evidence | Price/min | Max file |
|---|---|---|---|---|---|
| OpenAI gpt-transcribe (new, recommended) | prompt + keywords array + languages | keyword count not found; prompt over limit rejected | keywords "hints, not required output… evaluate whether they… cause unspoken terms to appear" | $0.0045 | 25 MB |
| OpenAI gpt-4o-transcribe / mini (deprecated, shutdown 2027-02-26) | prompt only | not found | AA-WER 4.0% / 4.5%; AA chunks ~9 min | not verified | 25 MB |
| whisper-1 (deprecated) | prompt | 224 tokens | context made WER worse in [10] | – | 25 MB |
| ElevenLabs Scribe v3 batch | keyterms | 1,000 terms ×50 chars (realtime 50×20; v3 Live library 10,000) | vendor: context-aware | v3 price not rendered; v2 $0.20/hr; keyterms extra | >8 min chunked internally |
| AssemblyAI Universal-3.5 Pro async | keyterms_prompt OR prompt (exclusive) | 1,000 terms ≤6 words; prompt 1,500 words | AA-WER 3.0% | ~ $0.21/hr + $0.05/hr keyterms (secondary) | not found |
| AssemblyAI Dictation API (Universal-3.6 Pro) | not confirmed | – | returns verbatim text + cleaned llm_response | – | short utterances |
| Deepgram Nova-3 | keyterm | 100 terms, 500 tokens; advise 20–50 | AA-WER 5.2% | streaming $0.46/hr | – |
| Soniox v6 async | context general/text/terms | 8,000 tokens | AA-WER 3.0% | – | – |
| Voxtral Small Transcribe 3 | context biasing (unverified) | – | AA-WER 2.1% | $0.004 | – |
| Gemini 3.5 Transcribe | unverified | – | AA-WER 2.6% | $0.005 | – |
| Groq whisper-large-v3 | prompt | 224 tokens | AA-WER 4.1% | – | – |

### Findings
- OpenAI: gpt-transcribe now recommended; whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe, -diarize deprecated, shutdown 2027-02-26. gpt-transcribe adds `keywords`; $0.0045/min; AA-WER 3.3% vs 4.0% for gpt-4o-transcribe.
- AA-WER v2 leaders (2026-11-16): Alibaba Fun-Realtime-ASR-preview 1.7%, ElevenLabs Scribe v3 1.9%, MAI-Transcribe-2 2.0%, Voxtral Small Transcribe 3 2.1%, Scribe v2 2.2%, Gemini 3.5 Transcribe 2.6%, Soniox v6 / AssemblyAI U-3.5 Pro 3.0%, GPT Transcribe 3.3%, Nova-3 5.2%. No vocab lists in AA-WER.
- Contextual Earnings-22 (Apr 2026): context improved keyword F-score for all; long lists with unspoken terms cost precision (false insertions); prompt-based context caused artifacts (unspoken terms, partial/empty output, language switching); OpenAI WER worsened with context. LREC 2026: oracle jargon prompt cut jargon WER 0.50–0.70 absolute for Whisper.
- Short relevant lists beat long ones; Deepgram 20–50 advice; OpenAI warns about unspoken terms.
- Failure modes: prompt leakage on short/silent clips; AA chunks gpt-4o at ~9 min.
- AssemblyAI pitfalls: word_boost routes to Universal-2; prompt and keyterms exclusive; "speaker" in prompt adds labels.
- No 2026 benchmark for glossary LLM post-correction.

### Take
- Move off gpt-4o-transcribe before 2027-02-26: gpt-transcribe + keywords (same key, $0.0045/min, better WER).
- Strongest on paper: ElevenLabs Scribe v3 (1.9%, 1,000 keyterms); AssemblyAI U-3.5 Pro and Soniox second; Deepgram trails.
- Keep vocab short/audio-likely (top 30–100 per request).
- Light glossary-constrained LLM backstop limited to observed confusion pairs.
- A/B 10–20 of real recordings across gpt-transcribe, Scribe v3, U-3.5 Pro; score keyword precision/recall.

### Couldn't verify
gpt-transcribe keyword/prompt/duration limits; current 4o prices; Scribe v3 price + keyterm surcharge; Deepgram prerecorded/keyterm price; AssemblyAI primary price; Groq/Voxtral/Gemini vocab mechanisms; acronym/made-up-word tests; LLM post-correction benchmark.
