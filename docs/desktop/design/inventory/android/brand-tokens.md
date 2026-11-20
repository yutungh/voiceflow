# VoiceFlow Android: brand tokens

Read from the Android repo (`VoiceFlow - Voice Transcription`, read-only) on 2026-11-16: `assets/voiceflow-icon.svg`, `app/src/main/res/values/*.xml`, `Ui.java` (app screens) and `VoiceFlowKeyboardService.java` `Palette` (keyboard). Nothing was changed there.

## Summary

| Token | Value | Where it comes from |
|---|---|---|
| **Primary (brand accent)** | `#0F766E` teal-700 | `Ui.ACCENT`, used for primary buttons, selected chips |
| Accent soft (selected background) | `#D8F3EE` | `Ui.ACCENT_SOFT` |
| Icon gradient | `#07111F` → `#0F766E` → `#2DD4BF` → `#5B5CE2` (diagonal, top-left to bottom-right) | `voiceflow-icon.svg` |
| Icon glows | white 26% top-left; violet `#8B5CF6` 46% bottom-right | `voiceflow-icon.svg` |
| Mint highlight | `#BFF7EF` (core of the wave stroke) | `voiceflow-icon.svg` |
| **Background** | `#F4F6F8` | `Ui.BACKGROUND` (also status and nav bars) |
| Surface / surface-alt | `#FFFFFF` / `#EEF2F6` | `Ui.SURFACE`, `Ui.SURFACE_ALT` (secondary buttons) |
| **Text** | `#1F2328` | `Ui.TEXT`, `colors.xml keyboard_text` |
| Muted text | `#667085` | `Ui.MUTED` |
| Divider | `#E5E7EB` | `Ui.DIVIDER` |
| Danger / danger soft | `#B42318` / `#FFE4E0` | `Ui.DANGER`, `Ui.DANGER_SOFT` |
| Deep ink (shadows, key marks) | `#0B1727`, `#020617` | `voiceflow-icon.svg` |

**Secondary colour:** there is no named secondary. Treat **mint `#2DD4BF`** (teal-400, the icon's bright stop) as the secondary and **indigo `#5B5CE2` / violet `#8B5CF6`** as a sparing third accent (the icon's far corner only).

## Keyboard surface (the IME itself)

The keyboard does not use the teal accent; it follows the system theme and the device's `colorAccent`.

| Role | Light | Dark |
|---|---|---|
| Keyboard background | `#EEF0F3` | `#202124` |
| Toolbar / alt key | `#F7F8FA` | `#2D2E32` |
| Key | `#FFFFFF` | `#3A3B3F` |
| Key pressed / utility | `#E0E4E9` | `#4A4B50` |
| Key text | `#1F2328` | `#F1F3F4` |
| Key border | `#DAE0E6` | `#525358` |
| Accent (fallback when the system gives none) | `#1967D2` | `#64B5F6` |
| Danger (recording stop, errors) | `#BF360C` | `#C62828` |

`colors.xml` also holds an older set (`keyboard_bg #F1F3F4`, `keyboard_key_dark #D7DCE1`, `accent #1565C0`) that no Java or XML references: legacy, not a token to carry over.

## Shape

- **Icon:** rounded square, corner radius 228 on a 944 box (about 24%), not a circle or a true squircle.
- **Cards and panels:** 18 dp radius is the house default (most common in `Ui.rounded`); 8, 12, 14, 16 dp for smaller elements.
- **Buttons:** pill-ish top actions, 20 dp radius on a 40 dp minimum height; primary = teal fill + white bold 15 sp; secondary = `#EEF2F6` fill + `#1F2328` text.
- **Keys:** 7 dp radius with a 1 dp border.

## Typography

- **Family:** system sans (`android:fontFamily="sans"`, Roboto on his phone). No custom font is bundled.
- **Weights:** regular and bold only; bold marks headings, primary actions and the selected tab.
- **Sizes in use (sp):** 22 page title; 18, 16, 15 bold section and row headings; 13, 12 body and captions; keyboard 22 for letter keys, 11 to 12 bold for utility keys and chips.
- **Desktop mapping (suggested):** a system UI stack (`"Segoe UI Variable", "Segoe UI", system-ui, -apple-system, Roboto, sans-serif`) keeps parity with Android's "system font, no brand font" choice.

## Motif

- **Sine wave over a keyboard bar:** voice turning into typed text. The wave is a thick white stroke with a mint core; the bar is white with five dark key pills and a spacebar at 22 to 26% opacity.
- Dark navy top-left fading through teal to indigo bottom-right: night-to-day, calm and technical rather than playful.
- For the desktop: keep the wave, swap the keyboard bar for a text caret or cursor line if a desktop-specific mark is wanted; keep the same gradient so both icons read as one family.

## Tone

- **Labels are short, plain and lower-key:** `Ready`, `Copied`, `Cleared`, `Nothing to paste`, `Finish or cancel the recording first`, `No connection for transform`.
- **Status line, not dialogs:** the keyboard reports state in one line at the top-left of its toolbar.
- **Style names are human, not technical:** `Casual`, `Work`, `Family`, `Partner`, `Friends` (pack ids `casual`, `business`, `family`, `partner`). The style picker is a single chip with a caret (`Casual ▾`) beside the mic.
- No emoji in chrome; Material-style line icons (mic, history, wand, spellcheck, translate, keyboard) in `res/drawable`.

## Files here

| File | What |
|---|---|
| `voiceflow-icon.svg` | Master icon (1024, vector) |
| `voiceflow-icon-512.png` | The SVG rendered at 512 px, transparent corners |
| `ic_launcher-192.png` | The shipped launcher icon (xxxhdpi) |
| `screen-keyboard-style-picker.png` | Keyboard, dark: status `Ready`, round mic button, `Casual ▾` style chip |
| `screen-keyboard-suggestions.png` | Keyboard, dark: suggestion strip, `set` and mic buttons |
| `screen-keyboard-symbols.png` | Keyboard symbols layer |

The screenshots date from 2026-06-29 (early builds, test screen "Keyboard Test"); the app's settings and history screens have no screenshots in the repo, so their look is known only from `Ui.java`.
