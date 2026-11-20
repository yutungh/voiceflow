# Design inventory: Whispering 7.11.0 → VoiceFlow Desktop

2026-11-16. Input for the full UI redesign and rebrand (SPEC §2.3, M1). Source: untouched upstream `C:\vf\src` (EpicenterHQ/epicenter tag v7.11.0, `apps/whispering`), served as the web build (`vite preview` on 127.0.0.1) and captured headless with Playwright. No Tauri, no window on the desktop, his installed Whispering and its data were not touched.

**What the captures are and are not**

- The web build runs the same Svelte UI as the desktop app, minus Tauri-only parts (global shortcuts, tray, always-on-top, file-system storage, paste at cursor). Tauri calls throw harmlessly in the browser.
- Screens 21–25, 31 (edit recording, transcript, runs) and 32 show demo transcripts and are kept out of the public repo. History and transformations are **demo data seeded into the browser's own IndexedDB**: six invented sentences, two demo transformations, and runs that reproduce his real failure (`404 The model gpt-4.1-nano has been deprecated`). No transcript of his is in any image.
- Settings were seeded to mirror his setup (OpenAI, whisper-1, auto-run "Casual Format Text"); the API key is a fake placeholder.

Contents: [1. Current UI](#1-current-ui-inventory) · [2. Screens](#2-screens) · [3. Android brand](#3-android-brand-tokens) · [4. Usability notes](#4-usability-notes-for-his-workflow)

---

## 1. Current UI inventory

### 1.1 Routes (17 screens)

All under `apps/whispering/src/routes/`. `(app)` wraps everything in the sidebar shell; `(config)` adds the top header with the five selector icons.

| Route | Screen | Purpose | Keep in VoiceFlow? |
|---|---|---|---|
| `/` | Home | Big emoji mic, mode switch (Manual / Voice Activated / Upload File), 4 selector icons, latest transcript + audio | Replace with a status home |
| `/recordings` | Recordings | TanStack table: timestamp, transcript, latest transformation output, audio, 8 row actions | Becomes **History** |
| `/transformations` | Transformations | Table of saved multi-step pipelines; the active one is ticked | Becomes **Clean-up styles** (pack-owned) |
| `/transformations/new` | New transformation | Editor: title, description, steps (prompt / find-replace), test panel | Drop (styles come from the pack) |
| `/settings` | Settings › General | Output toggles (copy / paste transcript, copy / paste transformed), auto-delete, navigation layout (sidebar / header); desktop adds paste options and always-on-top | Keep, reduced |
| `/settings/recording` | Settings › Recording | Mode, device, bitrate (web); desktop adds method (cpal / navigator / ffmpeg), sample rate, output folder, FFmpeg command builder; content changes per mode | Keep, reduced |
| `/settings/transcription` | Settings › Transcription | Service (10+ providers), model, key, base URL, compression, language, temperature, **System Prompt** (vocabulary) last | Pack-owned: read-only display |
| `/settings/api-keys` | Settings › API Keys | One field per provider (long page, 2,164 px) | One OpenAI key → keychain |
| `/settings/sound` | Settings › Sound | Per-event sound toggles | Keep |
| `/settings/shortcuts/local` | Settings › Shortcuts › Local | 10 in-app commands | Merge |
| `/settings/shortcuts/global` | Settings › Shortcuts › Global | Same commands as global hotkeys (desktop only) | Merge, show his 5 first |
| `/settings/analytics` | Settings › Privacy & Analytics | Aptabase telemetry toggle | Replace with **Privacy** (strict / best-effort, blocked apps) |
| `/desktop-app` | "Whispering Desktop" | Marketing page for web users | Drop |
| `/global-shortcut` | Change global shortcut | Onboarding help page | Drop or fold into onboarding |
| `/install-ffmpeg` | Install FFmpeg | macOS / Windows / Linux install guide | Drop unless FFmpeg stays |
| `/macos-enable-accessibility` | macOS accessibility | Permission walkthrough (Mac, M5) | Keep for Mac users |
| `/transform-clipboard` | Transform Clipboard | Separate 700×600 always-on-top window (Ctrl+Shift+R) with a transformation combobox | Rework or drop |

Also `+error.svelte` (error page) and the sidebar `VerticalNav` (Home, Recordings, Transformations, Settings; footer: theme toggle, GitHub, Notifications).

### 1.2 Dialogs, popovers and overlays

| Component | Where | Captured |
|---|---|---|
| Device / method selector (popover) | header icon 1 | `30-popover-1-device-dark` |
| Compression selector | header icon 2 | `30-popover-2-compression-dark` |
| Transcription service + model selector | header icon 3 | `30-popover-3-transcription-dark` |
| Transformation selector ("post-processing") | header icon 4 | `30-popover-4-transformation-dark` |
| Recording mode dropdown | header caret | `30-popover-5-recording-mode-dark` |
| Transformation picker (per recording; also Ctrl+Shift+X) | row action | `31-dialog-transformation-picker-dark` |
| Edit recording | row action | `31-dialog-edit-recording-dark` |
| Transformation runs | row action | `31-dialog-transformation-runs-dark` |
| Transcript viewer (`TranscriptDialog`) | transcript cell | `31-dialog-transcript-dark` |
| Columns menu | Recordings | `31-popover-columns-dark` |
| Notification history (`NotificationLog`) | sidebar footer | `32-dialog-notification-log-dark` |
| Transformation settings (`EditTransformationModal`, full-screen) | Transformations › pencil | `34-dialog-edit-transformation-dark` |
| Toasts (svelte-sonner, bottom-right, 5 s, rich colours) | everywhere | `20-first-run-toast-dark`, `36-error-toast-no-microphone-dark` |
| `ConfirmationDialog`, `MoreDetailsDialog`, `UpdateDialog`, `MigrationDialog` | global | not reachable without Tauri or an error |

### 1.3 Tray, recording indicator, sounds

- **Tray** (`lib/services/desktop/tray.ts`): id `whispering-tray`; menu = Show Window, Hide Window, Settings, Quit; left click does nothing (the toggle call is commented out).
- **Recording indicator:** there is **no floating overlay**. The signals are:
  - the tray icon swapped between two emoji PNGs (`src-tauri/recorder-state-icons/studio_microphone.png` idle, `red_large_square.png` recording);
  - the main window optionally pinned on top while recording (`system.alwaysOnTop`);
  - start / stop / done sounds (7 Zapsplat MP3s in `lib/services/isomorphic/sound/assets/`);
  - in the app, the emoji mic swaps 🎙️ → ⏹️, with 🚫 to cancel; VAD uses 🎤 💬 👂.
- **Tiny-window mode:** below 196 px wide the whole window becomes one giant emoji record button (`26-tiny-window-dark`).

### 1.4 Stack

| Layer | What |
|---|---|
| Framework | SvelteKit 2.49 + Svelte 5.45 (runes), `adapter-static` SPA (fallback `index.html`), Vite 7 |
| Desktop shell | Tauri 2 (`src-tauri`, Rust) |
| Components | **shadcn-svelte** (registry, base colour `slate`) on **bits-ui 2.14.4**, vendored in the workspace package `packages/ui` (`@epicenter/ui`, ~50 components: button, dialog, sidebar, command, table, toggle-group, kbd, file-drop-zone, sonner…) |
| Styling | **Tailwind CSS 4.1** (`@tailwindcss/vite`), `tw-animate-css`, `tailwind-variants`, `tailwind-merge`, `@tailwindcss/typography` |
| Data | TanStack Svelte Query 6, TanStack Table 9 alpha, Dexie (IndexedDB) on web, Markdown + WAV files on desktop |
| Toasts / theme | `svelte-sonner`, `mode-watcher` |
| Icons | **Lucide** (`@lucide/svelte` 0.555) for chrome; provider logos and GitHub / Chrome Web Store as Svelte SVG components in `lib/components/icons`; **emoji** for the mic, modes and tray |

### 1.5 Theming

- **One CSS file:** `packages/ui/src/app.css`. shadcn CSS variables in OKLCH on `:root` (light) and `.dark`, mapped to Tailwind colours by `@theme inline`; `--radius: 0.625rem`.
- **Dark mode:** class-based (`@custom-variant dark (&:is(.dark *))`), driven by `mode-watcher` with `defaultMode="dark"` and `track={false}`, so it **ignores the OS setting** and starts dark; the sidebar footer toggles it (stored in localStorage `mode-watcher-mode`).
- **Palette:** stock shadcn slate (navy-black `oklch(0.129 0.042 264.7)` background in dark, white in light); sidebar uses neutral greys; `warning` and `success` added. The logo tile uses `sidebar-primary`, which in dark is a bright blue `oklch(0.488 0.243 264.4)`.
- **Custom breakpoints:** `xxs` 196 px (tiny-window mode), `xs` 320 px.
- **Font bug:** `--font-sans` names **Manrope Variable**, but its `@import` is commented out, so it never loads. Light falls back to `system-ui` (Segoe UI); `.dark` overrides the stack to `"Manrope Variable", sans-serif`, so dark mode renders in the browser's generic sans (Arial). The two modes use different fonts (compare `01-home-light` and `01-home-dark`).

### 1.6 Where branding lives (to change for the rebrand)

| Item | Location |
|---|---|
| Product name, window title, identifier, publisher, copyright, descriptions, updater endpoint, macOS signing identity | `apps/whispering/src-tauri/tauri.conf.json` |
| App icons (ico, icns, PNGs, Windows Square*Logo, android/ios sets) | `apps/whispering/src-tauri/icons/` |
| Tray state icons (emoji PNGs) | `apps/whispering/src-tauri/recorder-state-icons/` |
| Favicons, web manifest | `apps/whispering/static/` |
| `<title>Whispering</title>` | `src/routes/+layout.svelte`, `src/routes/(app)/+page.svelte` |
| Hero "Whispering" + tagline "Press shortcut → speak → get text. Free and open source ❤️" | `src/routes/(app)/+page.svelte` |
| Header wordmark `whispering` (lowercase) | `src/routes/(app)/(config)/+layout.svelte` |
| Sidebar logo tile 🎙️ + "Whispering / Speech to text" + GitHub link | `src/routes/(app)/_components/VerticalNav.svelte` |
| "Customize your experience for Whispering v7.11.0" and per-page descriptions | `src/routes/(app)/(config)/settings/**` |
| Welcome toasts ("Welcome to Whispering!") | `src/routes/(app)/_layout-utils/register-onboarding.ts` |
| Tray id `whispering-tray` | `src/lib/services/desktop/tray.ts` |
| Colours, radius, font | `packages/ui/src/app.css` |
| Mic emoji per state | `src/lib/constants/audio/recording-states.ts` |

Counts: "Whispering" appears 47 times across 20 `.svelte` files; 23 links point at `epicenter.so` or the upstream GitHub. **There is no About screen**: the AGPL notice the spec needs (§7.4, §5d) has no home yet.

---

## 2. Screens

75 captures in `screens/` (4.7 MB). Size is 1280×800 unless noted.

### Empty first-run state, light and dark

| # | Screen | Light | Dark |
|---|---|---|---|
| 01 | Home | [light](screens/01-home-light.png) | [dark](screens/01-home-dark.png) |
| 01n | Home, narrow 420×720 | [light](screens/01-home-narrow-light.png) | [dark](screens/01-home-narrow-dark.png) |
| 02 | Recordings, empty | [light](screens/02-recordings-light.png) | [dark](screens/02-recordings-dark.png) |
| 02n | Recordings, narrow 420×720 | [light](screens/02-recordings-narrow-light.png) | [dark](screens/02-recordings-narrow-dark.png) |
| 03 | Transformations, empty | [light](screens/03-transformations-light.png) | [dark](screens/03-transformations-dark.png) |
| 04 | New transformation | [light](screens/04-transformation-new-light.png) | [dark](screens/04-transformation-new-dark.png) |
| 05 | Settings › General | [light](screens/05-settings-general-light.png) | [dark](screens/05-settings-general-dark.png) |
| 06 | Settings › Recording (manual mode) | [light](screens/06-settings-recording-light.png) | [dark](screens/06-settings-recording-dark.png) |
| 07 | Settings › Transcription | [light](screens/07-settings-transcription-light.png) | [dark](screens/07-settings-transcription-dark.png) |
| 08 | Settings › API Keys | [light](screens/08-settings-api-keys-light.png) | [dark](screens/08-settings-api-keys-dark.png) |
| 09 | Settings › Sound | [light](screens/09-settings-sound-light.png) | [dark](screens/09-settings-sound-dark.png) |
| 10 | Settings › Shortcuts › Local | [light](screens/10-settings-shortcuts-local-light.png) | [dark](screens/10-settings-shortcuts-local-dark.png) |
| 11 | Settings › Shortcuts › Global | [light](screens/11-settings-shortcuts-global-light.png) | [dark](screens/11-settings-shortcuts-global-dark.png) |
| 12 | Settings › Privacy & Analytics | [light](screens/12-settings-privacy-analytics-light.png) | [dark](screens/12-settings-privacy-analytics-dark.png) |
| 13 | Desktop app (marketing) | [light](screens/13-desktop-app-light.png) | [dark](screens/13-desktop-app-dark.png) |
| 14 | Global shortcut help | [light](screens/14-global-shortcut-light.png) | [dark](screens/14-global-shortcut-dark.png) |
| 15 | Install FFmpeg | [light](screens/15-install-ffmpeg-light.png) | [dark](screens/15-install-ffmpeg-dark.png) |
| 16 | macOS accessibility | [light](screens/16-macos-accessibility-light.png) | [dark](screens/16-macos-accessibility-dark.png) |
| 17 | Transform Clipboard window (needs Tauri to read the clipboard) | [light](screens/17-transform-clipboard-light.png) | [dark](screens/17-transform-clipboard-dark.png) |

### With demo data

| # | Screen | Light | Dark |
|---|---|---|---|
| 20 | First-run welcome toast | | [dark](screens/20-first-run-toast-dark.png) |
| 21 | Home with latest transcript + audio | light (not published) | dark (not published) |
| 22 | Recordings, 6 rows (2 clean-up failures, 1 failed transcription) | light (not published) | dark (not published) |
| 23 | Transformations, 2 rows | light (not published) | dark (not published) |
| 24 | Home, narrow 420×720 | light (not published) | dark (not published) |
| 25 | Recordings, narrow 420×720 | light (not published) | dark (not published) |
| 26 | Tiny window, 180×180 | | [dark](screens/26-tiny-window-dark.png) |
| 30 | Header popovers: [device](screens/30-popover-1-device-dark.png), [compression](screens/30-popover-2-compression-dark.png), [transcription](screens/30-popover-3-transcription-dark.png), [transformation](screens/30-popover-4-transformation-dark.png), [recording mode](screens/30-popover-5-recording-mode-dark.png) | | dark |
| 31 | Row dialogs: [transformation picker](screens/31-dialog-transformation-picker-dark.png), edit recording (not published), transformation runs (not published), transcript (not published), [columns](screens/31-popover-columns-dark.png) | | dark |
| 32 | Notification history | | dark (not published) |
| 33 | Sidebar expanded | | [dark](screens/33-sidebar-expanded-dark.png) |
| 34 | Transformation settings (edit) | | [dark](screens/34-dialog-edit-transformation-dark.png) |
| 35 | Home in [Voice Activated](screens/35-home-voice-activated-dark.png) and [Upload File](screens/35-home-upload-dark.png) modes | | dark |
| 36 | Error toast: no microphone | | [dark](screens/36-error-toast-no-microphone-dark.png) |
| 40 | Settings, full height: [general](screens/40-settings-general-full-dark.png) (1070 px), [recording, upload mode](screens/40-settings-recording-upload-mode-dark.png), [transcription](screens/40-settings-transcription-full-dark.png) (1246 px), [API keys](screens/40-settings-api-keys-full-dark.png) (2164 px), [sound](screens/40-settings-sound-full-dark.png), [shortcuts local](screens/40-settings-shortcuts-local-full-dark.png), [shortcuts global](screens/40-settings-shortcuts-global-full-dark.png), [analytics](screens/40-settings-analytics-full-dark.png) | | dark |
| 41 | New transformation, full height | | [dark](screens/41-transformation-new-full-dark.png) |

Not capturable without Tauri: the tray menu, the tray icon states, always-on-top, the global-shortcut recorder, paste at cursor, the update and migration dialogs. Section 1.3 describes them from source.

---

## 3. Android brand tokens

Full detail, sources and file list: [`android/brand-tokens.md`](android/brand-tokens.md). Icon: [`voiceflow-icon-512.png`](android/voiceflow-icon-512.png) (from the SVG master), [`ic_launcher-192.png`](android/ic_launcher-192.png).

| | |
|---|---|
| **Primary** | Teal `#0F766E` (soft `#D8F3EE`) |
| **Secondary** | Mint `#2DD4BF`; indigo `#5B5CE2` / violet `#8B5CF6` as a sparing third accent (icon only today) |
| **Background / surface** | `#F4F6F8` / `#FFFFFF` (alt `#EEF2F6`) |
| **Text / muted / divider** | `#1F2328` / `#667085` / `#E5E7EB` |
| **Danger** | `#B42318` (soft `#FFE4E0`) |
| **Dark surfaces (keyboard)** | `#202124`, `#2D2E32`, `#3A3B3F`, text `#F1F3F4` |
| **Type** | System sans (Roboto), regular + bold only; no brand font |
| **Shape** | 18 dp cards, 20 dp pill buttons (40 dp tall), 7 dp keys; icon is a rounded square (~24% radius) |
| **Icon motif** | White sine wave with a mint core over a white keyboard bar, on a navy → teal → mint → indigo diagonal gradient |
| **Tone** | Short plain status lines (`Ready`, `Copied`, `Nothing to paste`); human style names (Casual, Work, Family, Partner); line icons, no emoji |

Screenshots: [style picker](android/screen-keyboard-style-picker.png), [suggestions](android/screen-keyboard-suggestions.png), [symbols](android/screen-keyboard-symbols.png).

---

## 4. Usability notes for his workflow

His use [L]: global hotkey dictation into other apps (toggle Ctrl+Space, push-to-talk Alt+Shift+D), ~1,600 clips since June, one auto-run clean-up ("Casual Format Text"), the transformation picker (Ctrl+Shift+X), keep-forever history. The window is rarely in front when he dictates.

1. **The home screen serves a use he doesn't have.** A hero title, a marketing tagline, a 3-way mode switch and a 130 px emoji mic fill the first screen (`21`). He starts dictation from other apps by hotkey, so home should answer "is VoiceFlow ready, what did it last paste, is anything wrong", with his two hotkeys shown.
2. **What was pasted is not what is shown.** Home and History lead with the raw transcript; the cleaned text sits in a separate "Latest Transformation Run Output" column (`22`), and with his flags the raw text is what reached the cursor. VoiceFlow needs one "final text" per dictation (what was pasted), with raw behind a disclosure.
3. **Clean-up failure is silent and looks like content.** 166 straight 404s since 2026-10-24 appear only as text in the output cell, styled exactly like a transcript (`22`). Needs an error state on the row, a banner, a tray colour (SPEC §6.3), and "raw pasted because clean-up failed" wording.
4. **The header is five unlabeled icons, repeated on every page.** Device, compression, transcription, transformation and record/mode (`30-*`) carry meaning only in tooltips, with unexplained dot badges. For him these are set-once settings; they don't belong in persistent chrome.
5. **History doesn't scale to 1,600 clips.** At 1280 px the Actions column (8 icons: retranscribe, transform, edit, copy, copy output, runs, download, delete) is clipped (`22`); cells truncate mid-line at three lines; an audio player per row takes a third of the width; paging is Previous/Next only, with no date groups, no "failed" filter and no search beyond a text filter.
6. **Narrow windows break.** At 420 px the sidebar vanishes with no way to open it on Home (`01-home-narrow`, `24`); the Recordings table scrolls sideways and loses its empty-state text (`02-recordings-narrow`, `25`). A narrow, docked window is the natural shape for a dictation companion.
7. **"Transformations" is a pipeline builder; he needs one style.** The editor offers providers × models per step, prompt and find-replace steps and a test panel (`34`, `41`); the picker shows internal nanoid chips and Ctrl+1/Ctrl+2 hints (`31-dialog-transformation-picker`). VoiceFlow's model is a few pack-owned styles (Casual default, Work, Family) picked by name, as on Android (`Casual ▾`), plus approved corrections as their own list.
8. **Vocabulary is buried and was empty for months.** The only lever is "System Prompt", the last field on Transcription settings, below Temperature (`40-settings-transcription-full`). VoiceFlow needs a first-class Vocabulary view: pack version, term count, last refresh, "No vocabulary yet", Refresh, and the approved corrections with their scope.
9. **The new features have no home.** Nothing in today's nav (Home, Recordings, Transformations, Settings) can hold per-app rules, privacy mode (strict / best-effort), held recordings with "Send anyway", learning suggestions to approve, or a status and errors panel. These need a place in the information architecture before any visual work.
10. **Settings sprawl, mostly not his to change.** 7 tabs, 10+ providers, base URL, temperature, compression, a 2,164 px API-keys page, plus 4 setup pages (desktop-app marketing, FFmpeg, global-shortcut help, macOS accessibility). Under the spec, model, style and privacy lists are pack-owned: show them read-only with "managed by your profile", and keep only device settings editable (key, mic, hotkeys, paste method, retention).
11. **Shortcuts are two 10-row tables.** Local and global tabs list every command (`10`, `11`); he uses five (toggle, push-to-talk, cancel, picker, clipboard). Show the global ones he uses first, in one list.
12. **Feedback lands where he isn't looking.** Errors are 5-second toasts bottom-right, hidden in small windows (`xs:block`), while he is in another app; the Notification history dialog sat empty at the bottom of the sidebar (`32`). The tray, the only always-visible surface, has two emoji states and a four-item menu with no status, no "Refresh vocabulary" and no last-error line. The visuals add to it: emoji as the main icons, stock slate shadcn, and different fonts in light and dark (§1.5).
