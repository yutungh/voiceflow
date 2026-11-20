# gpt-transcribe live test (2026-11-16, 8 real clips, about $0.21)

## Request shape
Whispering's exact shape (A: file, model, language=en, temperature=0, prompt) returned HTTP 200 on all 8 clips.
- A2 (no language, no temperature) also works.
- Accepted by the API: temperature, and singular `language` (validated).
- Rejected: sending both `language` and `languages`.
- `response_format`: json and text only. `verbose_json` and `srt` are rejected.
- Keywords: `<` `>` CR LF give a 400. No documented limits on keyword count, prompt length or duration.

## Results: target terms correct out of 22 (6 clips)

| Run | Correct | Median latency | Note |
|---|---|---|---|
| Baseline: whisper-1, no prompt | 9 | – | |
| A0: gpt-transcribe, no prompt | 13 | 1.30 s | |
| A: gpt-transcribe + prompt | 20 | 1.38 s | |
| A2 | 20 | 1.64 s | |
| B: + keywords + languages | 20 | 1.32 s | Febe right in 1 of 3 runs |
| C: gpt-4o-transcribe + prompt | 20 | 1.92 s | |

- No invented terms in the 2 ordinary clips.
- **gpt-transcribe misses:** Pinewood came out as "Pinewud" in 9 of 9 tries, even as a keyword. On the "Milo or Phoebe" clip, Febe was right in only 1 of 9 tries.
- **gpt-4o-transcribe misses:** "Mylo" twice in one clip. It got Pinewood right every time, but dropped the last sentence of one clip.
- **Other differences:**
  - gpt-transcribe added "Two five." at the start of one clip in every run (unclear whether it was actually said).
  - Other wrong words from gpt-transcribe: Stable AI / Stably, "want to know" / "note", "that's managed" / "mandatory".
- **Conclusions:**
  - Setting the stored model `transcription.openai.model=gpt-transcribe` plus filling the prompt is enough.
  - Keep find/replace for Pinewood.
  - Keywords add nothing measurable over the prompt.
