# VoiceFlow Desktop speed benchmarks

Scripts that measure where dictation time goes outside the app: upload and
transcription by audio format and model, cold vs warm connections, tidy-up
settings, realtime transcription, and local costs (IPC body encoding, FFmpeg).
The app's own per-dictation timings are in Settings > Speed
(`src/lib/timing/`).

Node 22 or later, no dependencies. FFmpeg is needed for `clips` and `local`.

```
node desktop/tools/bench/bench.mjs clips  --config <config.json> --out <dir>
node desktop/tools/bench/bench.mjs stt    --config <config.json> --out <dir> --n 5
node desktop/tools/bench/bench.mjs conn   --config <config.json> --out <dir> --n 8
node desktop/tools/bench/bench.mjs tidy   --config <config.json> --out <dir> --n 2
node desktop/tools/bench/bench.mjs live   --config <config.json> --out <dir> --n 3
node desktop/tools/bench/bench.mjs local  --config <config.json> --out <dir>
node desktop/tools/bench/bench.mjs report --out <dir>
```

Options: `--clips <dir>` (reuse clips from another run), `--models`,
`--lengths`, `--formats`, `--variants`, `--delays` (comma lists), `--cold`
(stt on fresh connections), `--cred <target>`.

## Rules

- **Keep `--out`, the config and your audio outside the repository.** Results
  hold timings, sizes, token counts, word counts and per-term match counts;
  the scripts never store or print transcript text or the key.
- **The key** comes from `OPENAI_API_KEY`, or on Windows from a generic
  credential in Credential Manager (`--cred <target>` or `credTarget` in the
  config). It stays in memory.
- API calls cost money. A full run of every command with the defaults is
  roughly 250 calls and under $2 at current prices.

## Config

```json
{
  "audioDir": "C:/path/to/your/recordings",
  "clips": { "short": ["a.wav"], "medium": ["b.wav"], "long": ["c.wav", "d.wav"] },
  "tidyInputs": ["a.ogg", "b.ogg"],
  "terms": ["Name1", "Name2"],
  "sttPrompt": "Dictation about … Names and terms: Name1, Name2.",
  "tidyPromptJava": { "file": "app/src/main/java/com/voiceflowkeyboard/ime/Prefs.java", "method": "casualPrompt" },
  "glossaryNotes": "",
  "language": "en",
  "ffmpeg": "ffmpeg",
  "credTarget": "api-key-openai.<app identifier>"
}
```

`tidyPromptFile` (a text file) can replace `tidyPromptJava`. `terms` drive
both the transcription prompt's name list and the name-hit counts: a "hit" is
an exact, case-sensitive, whole-word match of a term in the returned text, so
more hits on the same clip means more names spelled as in the list.

## What each command measures

| Command | Measures |
|---|---|
| `stt` | Total request time per cell on a pre-connected (warm) connection, so the numbers are upload + server time. `--cold` uses a fresh connection per request. Files over 25 MB are skipped and recorded as such. |
| `conn` | DNS / TCP / TLS on fresh connections; a short transcription and a short tidy-up, cold vs warm, interleaved. |
| `tidy` | Chat Completions per variant (model, `reasoning_effort`, full vs trimmed prompt, `max_completion_tokens` bound). Unsupported variants are probed once and reported. Inputs are transcribed once in memory. |
| `live` | `gpt-live-transcribe` over `wss://api.openai.com/v1/realtime?intent=transcription`: streams 24 kHz PCM16 at real time in 100 ms chunks, commits, and times last audio sent → `…input_audio_transcription.completed`. |
| `local` | `tauri-plugin-http` 2.5.x request-body encoding (`Array.from(new Uint8Array(body))` + JSON) per file size, and FFmpeg compression with the app's default options. |
