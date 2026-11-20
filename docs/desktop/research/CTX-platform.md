# Platform context capture (subagent, 2026-11-16)

## Windows
- **Foreground app:** process + window title via GetForegroundWindow and GetWindowText: <1 ms, works for every app; the title often names the chat or file.
- **UIA (FlaUI, System.Windows.Automation, pywinauto):** reads the focused element's Value/TextPattern, the selection, a subtree walk, and the browser URL.
  - Chromium 138 has native UIA on by default (Chrome blog 2025-08-14).
  - Electron's accessibility tree is off by default until assistive tech is detected (WM_GETOBJECT trick, unofficial).
  - Telegram Desktop is Qt and custom-drawn, so little UIA text.
  - xterm.js terminals expose text only in screen-reader mode; Windows Terminal exposes its buffer.
  - A subtree walk takes 100–1000 ms.
- **Fallback:** screenshot plus Windows.Media.Ocr (local, 100–300 ms) or a vision LLM (1–3 s). Only what is visible; nothing off-screen.

## Android IME
- **EditorInfo:** packageName, fieldId, inputType, hint. Reliable.
- **InputConnection:** getTextBeforeCursor / getExtractedText return the current field only, may come back null or partial, and are IPC round trips. getSurroundingText needs API 31+.
- **The IME cannot read the screen.**
- **AccessibilityService:** getRootInActiveWindow gives the visible view tree; RecyclerView exposes only the rows on screen.
  - Sideloaded APKs on Android 13+ hit "Restricted settings" (App info → ⋮ → Allow restricted settings). Android 15 adds ECM, Android 16 blocks enabling during calls, Android 17 Advanced Protection revokes it.
- **Calendar:** CalendarContract.Instances with READ_CALENDAR, ~10 ms, offline.

## VoiceFlow today
- **packageName:** used only in the field identity key (VoiceFlowKeyboardService.java:364-366).
- **Password/incognito:** blocking via inputType at :1346-1366.
- **Surrounding text:** getTextBeforeCursor at :2584, :2753-2762, :3283.
- **Call detection:** AudioManager (:5558-5565). **Paste chip:** clipboard (:1650-1700).
- **Not used:** accessibility, contacts, calendar. Permissions are only INTERNET, ACCESS_NETWORK_STATE and RECORD_AUDIO.
- **Plug points:**
  - snapshot at record start: toggleCloudRecording :4798, offline :4979;
  - pass to the worker at :4867;
  - OpenAiClient.java:68-92 for prompt and keywords;
  - TransformClient.transform at :5163-5171 for the glossary.
- **Effort (sessions):** (i) per-app rules ~1; (ii) field text 0.5–1; (iii) accessibility 2–3; (iv) calendar ~1.

## Privacy
- **Risks:** passwords, 2FA codes, banking, other people's messages, and work content covered by an employer's policy or an NDA.
- **Mitigations:**
  - per-app allowlist;
  - skip password/incognito fields;
  - exclude banking, authenticator, SMS-code and work apps;
  - redact OTPs, emails and cards;
  - send extracted names, not raw text;
  - local-only mode;
  - a "context used" indicator and a log.

## Ranking (value ÷ effort)
- **Android:**
  1. per-app glossary from packageName
  2. field text
  3. calendar names
  4. accessibility, last
- **Windows:**
  1. process and window title
  2. UIA focused field and selection
  3. UIA window walk (inconsistent)
  4. OCR fallback
