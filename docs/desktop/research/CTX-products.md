# Context-aware dictation: TypeWhisper code + competitors (subagent, 2026-11-16)

## TypeWhisper v1.2.2 (code verified)
- **What it captures:**
  - The foreground process and the browser URL (from a browser extension, `PUT /v1/browser/context`), used ONLY to pick a workflow (Workflow.cs:162-182).
  - `GetActiveWindowTitle` is declared but never implemented.
  - It reads 256 chars before and after the cursor through UIA (InsertionContextReader.cs, 500 ms limit, skips password fields), but only to format the paste.
  - Selected text is used as input only by "Process selected text" workflows.
  - No clipboard, screenshots or OCR as context.
- **What the models receive:**
  - The speech-to-text request gets only the global dictionary plus the OpenAI plugin's fixed `transcriptionContext`.
  - The clean-up prompt gets template, settings, fine-tuning text and a language hint. It has no {app}, {selection} or {context} variables.
  - There is no per-workflow dictionary or transcription prompt.
- **Extension points:**
  - Post-processor plugins get text, app process and workflow.
  - The Script plugin (priority 400, after the LLM) reads stdin and gets the TYPEWHISPER_APP_NAME env var; its output replaces the text.
  - The Webhook only sends a copy.
  - Custom plugins need a fork, because the catalog is hard-coded.
  - FileMemory adds up to 5 matched memories to the clean-up input.

## Competitors
| App | What it reads | Where the context is used |
|---|---|---|
| Wispr Flow | App, text before/after the cursor and selected text, on-screen text (no screenshots) | Spelling names; casing |
| Superwhisper | Selection, clipboard, app context (input fields, names, window title) | The LLM step only |
| VoiceInk | Selection, clipboard, OCR of one screenshot (`<CURRENT_WINDOW_CONTEXT>` tag) | The enhancement step only |
| Aqua Voice | One screenshot at start ("Deep Context") | Speech recognition itself |
| Willow | On-screen text, opt-in | – |

- **Privacy controls:**
  - Wispr Flow: one global toggle; banking apps skipped automatically.
  - Superwhisper: per-mode toggles (Pro), and its History shows the exact prompt sent.
  - VoiceInk: Power Mode switches settings per app or URL.
  - Aqua Voice: off by default, plus a Privacy Mode.
- **Evidence it helps:** only vendor demos (Aqua: "Jeff" → "Geoff" when the name is on screen).

## Pattern
1. Names and terms seen on screen bias the speech recognition itself.
2. Richer text goes to the clean-up model through labelled prompt slots.
3. Per-mode switches, an inspectable record of what was sent, and skipping sensitive apps.
