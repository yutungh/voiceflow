# VoiceFlow Desktop: design B3.1

The chosen desktop UI design. Milestone M1 builds the app to match it.

## What it is

- **Direction:** B, "Night Console", reworked to be easy to understand on first use (the three starting directions are in `../directions/`).
- **Light theme:** the Graphite palette with soft, tonal primary buttons.
- **Dark theme:** deep navy with a mint accent.
- **B3:** a warmer round: tonal buttons, Phosphor icons, app icons and small illustrations.
- **B3.1:** fixes from a usability test with 3 first-time testers on 10 tasks. All 30 first clicks were correct; the fixes address what they still found unclear (wording, counts, undo, first run).
  - The B3.1 changes are listed in `design-tokens.md`, section "B3.1". No colour values changed from B3.

## How to open it

Open `voiceflow-b3-1.html` in a browser. It is one self-contained file; its only network request is the Inter font from Google Fonts. Use the Light / Dark switch at the top, or add `?theme=dark` to the address. Each screen is a frame with its own `#id` link.

## What is here

| Path | What |
|---|---|
| `voiceflow-b3-1.html` | The mockup: every screen and state, light and dark |
| `shots/light/`, `shots/dark/` | One screenshot per frame, 1360 px wide. Not committed: `node src/b31-run.mjs` writes them |
| `design-tokens.md` | Token names and values for the build (B2 base, then the B3 and B3.1 sections, which win) |
| `contrast.json` | Measured contrast of every text and edge pair, both themes (all text 4.5:1 or better) |
| `usability/` | The test script (`tasks.md`); `node src/usability-kit31.mjs` writes the 16 screens the testers saw |
| `research-uiux.md` | The UI/UX research behind the choices |
| `src/` | The generator |

## Regenerate

From `src/`, with Node 20 or later:

- `node b31-run.mjs` rebuilds `../voiceflow-b3-1.html` and the screenshots. It needs Playwright and Microsoft Edge: `npm i playwright`, or set `PLAYWRIGHT_MODULE` to an installed `playwright/index.mjs`. Pass frame ids to re-shoot only those.
- `node contrast-b3.mjs b31.css ../contrast.json` re-measures contrast.
- `node usability-kit31.mjs` rebuilds `../usability/` from the light screenshots.
- `node phosphor-build.mjs` rebuilds `phosphor.json` from the Phosphor icon assets (MIT, github.com/phosphor-icons/core); set `PHOSPHOR_DIR` to the folder holding `regular/`, `fill/` and `duotone/`.

The sample content in the mockup (names, messages, words) is demo data.
