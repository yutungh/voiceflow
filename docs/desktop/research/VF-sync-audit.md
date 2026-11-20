# VoiceFlow vocabulary sync audit (2026-11-16)

## Storage
- Vocabulary lives in SharedPreferences `voiceflow_keyboard_settings`, key `replacements_json` (Prefs.java:70).
  - It is a JSON array of `{from, to, context}`, with `from` holding the heard forms separated by newlines (PhraseReplacement.java:23-32).
  - Entries with an empty `from` or `to` are dropped.
  - 7 built-ins come first (Prefs.java:1315-1331).
- There is NO import or export:
  - the manifest has no share or file intents, and `allowBackup` is false;
  - entries can only be typed by hand in FindReplaceActivity.

## How things get onto the phone today
- `scripts/install-private.ps1` is the only PC→phone channel. It uses USB adb, `run-as` (works because builds are debug), and the API keys in `.env.local`.
- It merges vocabulary from `.voiceflow-private.json` with a merge that never removes anything (309-369), force-stops the app, and pushes the prefs file.
- `.voiceflow-devices.json` lists the target phones by USB serial.
- There is no Wi-Fi adb and no config fetch.
- A pattern to reuse: `ModelAvailability.checkIfDue` runs at most once a day, only when online, whenever the keyboard opens, on a background thread (VoiceFlowKeyboardService.java:100,323-327).

## Limits
- **Prompt:** 3,500 chars (PersonalVocabulary.java:14). It is built from the built-ins plus entries in saved order, and stops when it reaches the budget, with no priority order.
- **keywords[]:** NO count cap. It sends every unique `to`, for gpt-transcribe only.
- **Characters:** `<`, `>`, CR and LF are filtered out. It sends `languages[]`.
- **Other engines:** Deepgram, xAI and offline get no vocabulary, and the clean-up step does not get the vocabulary either.
- **Local correction:** whole-word, case-insensitive, and ignores context.
  - It runs at insert time after clean-up, and also on typed text at each space or punctuation mark (backspace undoes it).
  - Risk: it rebuilds the regex on every keystroke, which may slow down with hundreds of entries.

## Effort estimates
- (a) Pull JSON from a URL on keyboard open, reusing the `checkIfDue` pattern, with a NAS URL plus a bearer token seeded by `install-private.ps1`: about 1.5–2 sessions. Store under a separate key `synced_replacements_json`.
- (b) adb Wi-Fi push: about 1 session, but fragile.
- (c) Import button: about 1 session, and manual each time.

## Features
- **Speech-to-text:** OpenAI (gpt-transcribe default, 4o/mini, whisper-1), xAI, Deepgram; offline Vosk or Parakeet with automatic fallback; record-then-upload.
- **Clean-up:** OpenAI gpt-5.4-mini, Anthropic or xAI; styles Friends/Work/Family/Partner/custom/Fun with a 5-step expression level and per-style model.
- **Other:** translation and retone.
- **Vocabulary:** several heard forms per entry, exact output, context, guaranteed local correction.
- **Keyboard:** full IME with pinyin, suggestions, emoji, numpad, paste chip.

## Recommendation from the audit
- Build (a).
- The nightly file uses `{from, to, context}` with `from = to` for bare terms, sorted by priority.
- Cap the keyword count in the app.
- Use (b) with a replace mode as a stopgap.
