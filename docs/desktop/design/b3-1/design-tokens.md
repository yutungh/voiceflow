# VoiceFlow Desktop B2: design tokens

For M1. Direction B2 "Night Console, refined" (mockup `b2-night-console.html`). One set of token names, two themes (light, dark) and two densities (comfortable, compact), so the app switches theme and density by swapping variables only. Values are the ones the mockup renders and the screenshots show.

## How it maps onto the existing stack

- **Where:** `packages/ui/src/app.css` (shadcn-svelte variables on `:root` and `.dark`, mapped to Tailwind 4 colours by `@theme inline`). Replace the stock slate values with the tables below. Hex is fine; convert to OKLCH only if the rest of the file stays OKLCH.
- **Light is `:root`, dark is `.dark`.** The mockup uses `html[data-theme]`; the app keeps shadcn's `.dark` class.
- **Theme switch: System / Light / Dark** in Settings > Appearance and in the tray menu. `mode-watcher` today runs with `defaultMode="dark"` and `track={false}` (ignores Windows). Change it to `defaultMode="system"` with tracking on, and let the two switches call `setMode('system' | 'light' | 'dark')`. The tray reads the same stored mode.
- **Density:** a `.compact` class on the app root that overrides only the density tokens. Stored with the other device settings.
- **Font:** bundle Geist and Geist Mono (OFL) locally; don't load from Google at runtime. This also fixes today's bug where light and dark render in different fonts (inventory §1.5). Monospace is only for shortcut keys, raw transcripts, timings and version numbers.

## Colour

shadcn name first where one exists; VoiceFlow additions are marked *new*. Contrast is against the surface each pair is actually used on; all 56 checked pairs are 4.5:1 or better in both modes (lowest: light `--primary` on `--muted` 4.85, dark `--subtle-foreground` on `--accent` 5.63).

| Token | Role | Light | Dark |
|---|---|---|---|
| `--background` | Window background, behind panels | `#F3F6F7` | `#071016` |
| `--card` / `--popover` (mockup `--surface`) | Panels, rail, menus, title bar | `#FFFFFF` | `#0B1820` |
| `--muted` (mockup `--surface-raised`) | Table heads, hover, chips, quiet fills | `#EDF2F3` | `#10222B` |
| `--border` | Hairlines between rows and panels | `#DCE4E7` | `#1A2F38` |
| `--input` (mockup `--border-strong`) | Outlined controls, menus, secondary buttons | `#C2CFD4` | `#294955` |
| `--foreground` | Body text, headings | `#0B1F2A` | `#E4EDEF` |
| `--muted-foreground` | Secondary text, labels | `#40555E` | `#A9BBC1` |
| `--subtle-foreground` *new* | Timestamps, hints, captions | `#566A72` | `#8CA2AA` |
| `--primary` | The one primary button, links, focus, "ok" | `#0F766E` (brand teal) | `#2DD4BF` (brand mint) |
| `--primary-foreground` | Text on a primary fill | `#FFFFFF` | `#042420` |
| `--accent` (mockup `--primary-soft`) | Selected nav item, selected row, "managed by your profile" band | `#DDF2EE` | `#0D2B2B` |
| `--accent-foreground` (mockup `--primary-soft-foreground`) | Text on `--accent`, corrected words in light | `#0B5F58` | `#5EEAD4` |
| `--destructive` (mockup `--danger`) | Error titles, "Not pasted", Delete history | `#B42318` | `#FF8A7A` |
| `--danger-soft` *new* | Error rows and the tray problem block | `#FDECEA` | `#2B1517` |
| `--warning` *new* | "1 problem", held dictations, password-box warning | `#8A4A06` | `#F5B544` |
| `--warning-soft` *new* | Warning chip fill | `#FDF1D8` | `#2A2110` |
| `--info` *new* | Suggestions (the icon's indigo, used sparingly) | `#4F46C8` | `#B0B1FB` |
| `--info-soft` *new* | Suggestion rows and badges | `#EEEDFC` | `#1A1C3B` |
| `--record` *new* | The listening dot only | `#E5372B` | `#FF5A4E` |
| `--kbd-bg` / `--kbd-border` / `--kbd-foreground` *new* | Shortcut keys shown in text and Settings | `#EEF3F4` / `#C2CFD4` / `#33474F` | `#132A33` / `#2A4955` / `#B7C8CD` |
| `--statusbar` *new* | Status bar fill: navy in both themes | `#0B1F2A` | `#0A1A22` |
| `--statusbar-foreground` *new* | Status bar text | `#D3E1E5` | `#C9D8DC` |
| `--statusbar-ok` / `--statusbar-warn` *new* | "Ready to dictate" / "1 problem needs you" | `#5EEAD4` / `#F5B544` | `#2DD4BF` / `#F5B544` |
| `--ring` | Keyboard focus outline (2 px, 2 px offset) | `#0F766E` | `#5EEAD4` |
| `--overlay` *new* | Scrim behind the palette and drawer | `rgba(11,31,42,.38)` | `rgba(2,8,12,.62)` |

Rules that come with the colours:

- **One primary fill per screen.** Secondary buttons are outlined (`--card` + `--input` border); tertiary are text-only in `--muted-foreground`.
- **Status is never colour alone.** Every dot or tint sits beside words ("Not pasted", "Held for you", "Ready to dictate").
- **The status bar stays navy in light mode.** It is B's console spine and the one dark element in the light theme.
- **Indigo means "a question for you" only** (Suggestions). Teal means OK or the main action; red means something didn't happen; amber means held or needs a look.
- **Brand tie-in:** light primary is the Android `ACCENT` `#0F766E` and its soft `#D8F3EE` family; dark primary is the icon's mint `#2DD4BF`; the dark background is the icon's navy corner.

## Type

Geist for everything; Geist Mono only for keys, raw words, timings and versions. Sentence case everywhere; no all-caps labels.

| Token | Use | Comfortable | Compact | Weight |
|---|---|---|---|---|
| `--fs-xs` | Chips, table heads, badges, status bar (12 px fixed) | 12 px | 11.5 px | 600 for chips and heads |
| `--fs-sm` | Captions, secondary lines, hints | 13 px | 12.5 px | 400 |
| `--fs-body` | Body, buttons, nav, panel headings | 14 px | 13 px | 400 body, 500 controls, 600 headings |
| (fixed) | Error and "Needs you" titles | 15 px | 14 px | 600 |
| `--fs-lead` | The last dictation's cleaned text | 20 px | 17 px | 450 |
| `--fs-title` | Page title (h1) | 22 px | 18 px | 600, tracking -0.015em |
| (fixed) | First-run titles, Suggestions question | 28 px | 28 px | 600, tracking -0.02em |
| (fixed) | `kbd` | 11 px mono | 11 px mono | 500 |

Line height 1.5 for body, 1.55 for the dictation text, 1.2 for titles.

## Spacing and density

4 px base. Spacing steps: 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40.

| Token | Use | Comfortable | Compact |
|---|---|---|---|
| `--ctl-h` | Buttons, selects, inputs, search, toggles | 36 px | 32 px |
| `--nav-h` | Rail and drawer items (drawer at 420 px: 44 px) | 38 px | 32 px |
| `--row-y` | Table row vertical padding | 10 px | 5 px |
| `--pad` | Panel padding | 18 px | 12 px |
| `--gap` | Between panels and grid columns | 16 px | 10 px |
| `--rail-w` | Navigation rail width | 272 px | 272 px |
| (fixed) | Main area padding | 22 × 26 px | 14 × 20 px |
| (fixed) | Title bar / status bar height | 32 / 28 px | 32 / 28 px |

Click targets are 32 px or more in both densities. Compact also shows every navigation shortcut (Ctrl+1 to Ctrl+6) and opens "raw words and timing" by default; comfortable hides those until asked.

Narrow window (420 px wide): the rail becomes a top bar with a labelled **Menu** button that opens a 316 px drawer (same search field and labelled items, plus a Close button). Buttons in "Needs you" stack full width.

## Radius and elevation

| Token | Value | Use |
|---|---|---|
| `--radius-sm` | 4 px | `kbd` |
| `--radius-md` | 8 px | Buttons, inputs, selects, search, table wrappers |
| `--radius-lg` | 10 px | Panels, menus, tray menu, drawer |
| `--radius-xl` | 12 to 14 px | Command palette, first-run cards, error cards on the pill |
| `--radius-full` | 999 px | The recording pill, chips, badges |
| `--shadow-pop` | light `0 18px 48px rgba(11,31,42,.22)`, dark `0 18px 48px rgba(0,0,0,.55)` | Palette, menus, drawer, tray |
| `--shadow-pill` | light `0 10px 28px rgba(11,31,42,.18)`, dark `0 12px 32px rgba(0,0,0,.5)` | The recording pill |

Panels have no shadow; a 1 px `--border` separates them.

## Paste-in block for `app.css`

```css
:root {
  --background: #F3F6F7; --foreground: #0B1F2A;
  --card: #FFFFFF; --card-foreground: #0B1F2A; --popover: #FFFFFF; --popover-foreground: #0B1F2A;
  --muted: #EDF2F3; --muted-foreground: #40555E; --subtle-foreground: #566A72;
  --border: #DCE4E7; --input: #C2CFD4; --ring: #0F766E;
  --primary: #0F766E; --primary-foreground: #FFFFFF;
  --accent: #DDF2EE; --accent-foreground: #0B5F58;
  --secondary: #FFFFFF; --secondary-foreground: #0B1F2A;
  --destructive: #B42318; --danger-soft: #FDECEA;
  --warning: #8A4A06; --warning-soft: #FDF1D8;
  --info: #4F46C8; --info-soft: #EEEDFC; --record: #E5372B;
  --kbd-bg: #EEF3F4; --kbd-border: #C2CFD4; --kbd-foreground: #33474F;
  --statusbar: #0B1F2A; --statusbar-foreground: #D3E1E5; --statusbar-ok: #5EEAD4; --statusbar-warn: #F5B544;
  --overlay: rgba(11,31,42,.38);
  --radius: 0.625rem; /* 10 px panels; sm/md derive from it as today */
  --fs-xs: 12px; --fs-sm: 13px; --fs-body: 14px; --fs-lead: 20px; --fs-title: 22px;
  --ctl-h: 36px; --nav-h: 38px; --row-y: 10px; --pad: 18px; --gap: 16px; --rail-w: 272px;
  --font-sans: "Geist", system-ui, sans-serif; --font-mono: "Geist Mono", ui-monospace, monospace;
}
.dark {
  --background: #071016; --foreground: #E4EDEF;
  --card: #0B1820; --card-foreground: #E4EDEF; --popover: #0B1820; --popover-foreground: #E4EDEF;
  --muted: #10222B; --muted-foreground: #A9BBC1; --subtle-foreground: #8CA2AA;
  --border: #1A2F38; --input: #294955; --ring: #5EEAD4;
  --primary: #2DD4BF; --primary-foreground: #042420;
  --accent: #0D2B2B; --accent-foreground: #5EEAD4;
  --secondary: #0B1820; --secondary-foreground: #E4EDEF;
  --destructive: #FF8A7A; --danger-soft: #2B1517;
  --warning: #F5B544; --warning-soft: #2A2110;
  --info: #B0B1FB; --info-soft: #1A1C3B; --record: #FF5A4E;
  --kbd-bg: #132A33; --kbd-border: #2A4955; --kbd-foreground: #B7C8CD;
  --statusbar: #0A1A22; --statusbar-foreground: #C9D8DC; --statusbar-ok: #2DD4BF; --statusbar-warn: #F5B544;
  --overlay: rgba(2,8,12,.62);
}
.compact {
  --fs-xs: 11.5px; --fs-sm: 12.5px; --fs-body: 13px; --fs-lead: 17px; --fs-title: 18px;
  --ctl-h: 32px; --nav-h: 32px; --row-y: 5px; --pad: 12px; --gap: 10px;
}
@theme inline {
  --color-subtle-foreground: var(--subtle-foreground);
  --color-danger-soft: var(--danger-soft);
  --color-warning: var(--warning); --color-warning-soft: var(--warning-soft);
  --color-info: var(--info); --color-info-soft: var(--info-soft);
  --color-record: var(--record);
  --color-statusbar: var(--statusbar); --color-statusbar-foreground: var(--statusbar-foreground);
  --color-kbd: var(--kbd-bg);
  /* the stock shadcn mappings (background, card, primary, accent, destructive...) stay as they are */
}
```

## Checks behind this file

- Contrast: `src/contrast2.mjs` reads the token blocks out of the built mockup and checks 28 text/background pairs per mode: 0 below 4.5:1.
- Screens: 19 frames × 2 themes in `shots/light/` and `shots/dark/`, captured headless at their real sizes (1280 × 800; narrow 420 × 720).

## Calm light palettes (candidates, 2026-11-16)

The B2 light theme was judged too playful. These three replace only the **light** values; dark stays as above. Same token names, so the choice is a value swap. In the mockup: Light, then the "Light palette" switch at the top (or `?theme=light&palette=graphite|ink|stone`).

- **Graphite:** Cool zinc greys, a deep desaturated teal for the one main action.
- **Ink:** Near-monochrome slate; main buttons in dark ink, teal only in the app icon.
- **Stone:** Warm neutral greys, a muted grey-teal accent, a light status bar.

| Token | Graphite | Ink | Stone |
|---|---|---|---|
| `--background` | `#F4F4F5` | `#F6F7F9` | `#F0EDE6` |
| `--surface` | `#FFFFFF` | `#FFFFFF` | `#FFFDF9` |
| `--surface-raised` | `#F4F4F5` | `#F1F3F6` | `#EEEAE3` |
| `--border` | `#E4E4E7` | `#E2E6EB` | `#E3DDD3` |
| `--border-strong` | `#D4D4D8` | `#CBD3DC` | `#CFC7BB` |
| `--foreground` | `#18181B` | `#0F172A` | `#1C1A17` |
| `--muted-foreground` | `#3F3F46` | `#334155` | `#45403A` |
| `--subtle-foreground` | `#5B5B63` | `#556274` | `#615A51` |
| `--primary` | `#115E59` | `#1E293B` | `#3F6B67` |
| `--primary-foreground` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `--primary-soft` | `#F4F4F5` | `#F1F3F6` | `#EEEAE3` |
| `--primary-soft-foreground` | `#115E59` | `#1E293B` | `#335854` |
| `--danger` | `#A3301F` | `#A3301F` | `#9A3426` |
| `--danger-soft` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `--warning` | `#87590B` | `#7E5A10` | `#7A581C` |
| `--warning-soft` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `--info` | `#4A4F7E` | `#3E4C7A` | `#544E7A` |
| `--info-soft` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `--record` | `#C2412F` | `#C2412F` | `#B9442F` |
| `--kbd-bg` | `#F4F4F5` | `#F1F3F6` | `#EEEAE3` |
| `--kbd-border` | `#D4D4D8` | `#CBD3DC` | `#CFC7BB` |
| `--kbd-foreground` | `#3F3F46` | `#334155` | `#45403A` |
| `--statusbar` | `#27272A` | `#1E293B` | `#E8E3DA` |
| `--statusbar-foreground` | `#D4D4D8` | `#CBD5E1` | `#3D3832` |
| `--statusbar-ok` | `#9CCFC8` | `#A9D3BC` | `#335854` |
| `--statusbar-warn` | `#E3C07E` | `#E3C07E` | `#7A581C` |
| `--ring` | `#115E59` | `#1E293B` | `#3F6B67` |
| `--fix-underline` | `#A1A1AA` | `#94A3B8` | `#A8A095` |
| `--success` | `#115E59` | `#3D7458` | `#3F6B67` |

New tokens, also set in dark and B2 light to their old look: `--success` (OK dots, ticks, "Working"; equals `--primary` except in Ink, where the accent is ink) and `--fix-underline` (the dotted line under words the vocabulary fixed).

Rules that come with the calm palettes (in the mockup, CSS scoped to `html[data-theme=light][data-palette]`):

- **No tinted blocks.** `--primary-soft` is a neutral grey; `--danger-soft`, `--warning-soft` and `--info-soft` are white. State shows as an icon, a 3 px left border (`--danger` for an error, `--info` for a suggestion) or text colour.
- **Accent only for:** the one primary button per screen, focus rings, the selected-nav bar (3 px, no fill; the label goes semibold), links (underlined) and the selected radio.
- **Chips are outlined** (1 px `--border-strong`, transparent fill), with the status colour on the dot and the text only.
- **Fixed words:** text in `--foreground` with a dotted `--fix-underline`; the underline turns `--primary` on hover.
- **Level meters** use `--subtle-foreground`, not the accent.

Contrast, 21 text pairs per palette: none below 4.5:1 (lowest: Graphite 6.06, Ink 5.58, Stone 4.99).

## B3 (2026-11-16): warm, tonal, illustrated

B3 replaces the light candidates above. Base: Graphite structure with Soft buttons, warmed; dark keeps B2's navy and mint, with the same tonal treatment. Mockup: `b3/b3-voiceflow.html` (Light / Dark switch at the top).

### What changed in the system

- **Neutrals are warm and low-chroma** (hue about 70 in OKLCH, chroma under 0.012), with a second neutral layer for the sidebar (`--rail`).
- **The primary is tonal, never solid in light:** `--accent-tint` fill, `--accent-ink` text and icon, a 1 px `--accent-tint-border` edge, `--accent-tint-hover` on hover. One per view. Secondary buttons are white with the card shadow; tertiary are text only.
- **Colour lives in 32 px icon tiles** (`--tile-*`), status edges and the waveform, never in card fills.
- **Depth is shadow, not border:** cards `--shadow-card` (a 6% ring plus a soft lift), menus and toasts `--shadow-pop`, the pill `--shadow-pill`. Dark uses a white 7 to 10% ring instead.

### Colour

| Token | Role | Light | Dark |
|---|---|---|---|
| `--background` | Content area behind cards | `#F7F6F3` | `#071016` |
| `--rail` | Sidebar, title bar, narrow top bar (second neutral layer) | `#EFEDE8` | `#0A151C` |
| `--surface` | Cards, menus, pill, inputs | `#FFFFFF` | `#0D1A22` |
| `--surface-raised` | Table group rows, quiet fills, raw-words box | `#F1EFEB` | `#122530` |
| `--border` | Dividers inside cards | `#E6E3DD` | `#1A2F38` |
| `--border-strong` | Input outlines, chip outlines, keycap edge | `#D3CFC7` | `#294955` |
| `--foreground` | Text | `#1D1C1A` | `#E4EDEF` |
| `--muted-foreground` | Secondary text, nav labels | `#4A4640` | `#A9BBC1` |
| `--subtle-foreground` | Captions, timestamps, hints | `#66615A` | `#8CA2AA` |
| `--accent` | Focus ring colour, selected-nav icon, radio, sparkline today, waveform | `#115E59` | `#2DD4BF` |
| `--accent-ink` | Text and icon on the tonal primary; links | `#0E4F4B` | `#5EEAD4` |
| `--accent-tint` | Tonal primary fill, selected nav pill, selected row | `#E2EEEC` | `#0F2F2E` |
| `--accent-tint-hover` | Tonal primary on hover | `#D2E6E2` | `#134140` |
| `--accent-tint-border` | Tonal primary 1 px edge (3:1 on card and page) | `#5F918B` | `#2E7A73` |
| `--tile-acc` | Icon tile fill (teal) | `#E2EEEC` | `#10302F` |
| `--tile-acc-fg` | Icon in a teal tile | `#115E59` | `#5EEAD4` |
| `--tile-bad` | Problem tile fill | `#F7E6E1` | `#2B1517` |
| `--tile-bad-fg` | Problem icon | `#A3341F` | `#FF8A7A` |
| `--tile-warn` | Warning tile fill | `#F5ECD9` | `#2A2110` |
| `--tile-warn-fg` | Warning icon | `#87590B` | `#F5B544` |
| `--tile-ind` | Suggestion tile fill | `#E9E9F4` | `#1A1C3B` |
| `--tile-ind-fg` | Suggestion icon | `#4A4E86` | `#B0B1FB` |
| `--tile-ok` | Success tile fill | `#E2F0E8` | `#0F2A22` |
| `--tile-ok-fg` | Success icon | `#2D7350` | `#6EE7B7` |
| `--danger` | Error edge and small error text | `#A3341F` | `#FF8A7A` |
| `--warning` | Warning edge and small text | `#87590B` | `#F5B544` |
| `--success` | OK ticks and small text | `#2D7350` | `#6EE7B7` |
| `--info` | Suggestion edge | `#4A4E86` | `#B0B1FB` |
| `--record` | The listening dot only | `#D23F2B` | `#FF5A4E` |
| `--wave` | Live waveform | `#115E59` | `#2DD4BF` |
| `--wave-quiet` | History thumbnails, still waveform | `#B9B3A9` | `#3A5560` |
| `--spark` | Sparkline bars | `#9CC3BE` | `#1F5C57` |
| `--spark-today` | Sparkline, today | `#115E59` | `#2DD4BF` |
| `--ill-s` | Illustration line | `#115E59` | `#5EEAD4` |
| `--ill-f` | Illustration fill | `#DCEBE8` | `#10302F` |
| `--ill-m` | Illustration waveform accent | `#3FA394` | `#2DD4BF` |
| `--kbd-bg` | Keycap | `#FFFFFF` | `#132A33` |
| `--kbd-border` | Keycap edge | `#D3CFC7` | `#2A4955` |
| `--kbd-fg` | Keycap text | `#3E3A35` | `#B7C8CD` |
| `--statusbar` | Status bar | `#2B2925` | `#0A1A22` |
| `--statusbar-fg` | Status bar text | `#D9D5CE` | `#C9D8DC` |
| `--statusbar-ok` | Ready to dictate | `#9ED0C8` | `#2DD4BF` |
| `--statusbar-warn` | 1 problem needs you | `#E6C47F` | `#F5B544` |
| `--ring` | Focus outline, 2 px | `#115E59` | `#5EEAD4` |

Contrast, computed from these values: 30 text pairs per theme, none below 4.5:1 (lowest light tile-ok-fg on tile-ok 4.85, lowest dark subtle-foreground on surface-raised 5.9). Tonal-button edge: 3.55:1 on a card and 3.29:1 on the page in light; 3.49:1 and 3.79:1 in dark (3:1 needed).

### Type

Inter variable (OFL; Google Fonts in the mockup, bundled in the app) with its optical-size axis: text at `opsz` 14 to 20, titles of 20 px and up at `opsz` 28 to 32 (the Inter Display cut). Tabular numbers on every time, duration, count and stat. `text-wrap: balance` on headings, `pretty` on short text.

| Use | Size / weight |
|---|---|
| Page title | 26 px, 650, tracking -0.022em, opsz 32 |
| First-run title | 28 px, 650, opsz 32 |
| Stat figure | 24 px, 650, tabular |
| Suggestion question | 22 px, 650 |
| Card title | 15 px, 600 |
| Body, buttons, nav | 14 px, 400 / 500 / 600 |
| Secondary lines | 13 px |
| Captions, chips, keycaps | 12 to 12.5 px, 500 to 600 |
| Raw words, "You say" | ui-monospace (Cascadia Mono on Windows), 12.5 to 13 px |

### Shape, spacing, size

| Token | Value |
|---|---|
| Radius | keycap 5, button and input 9, list rows 10, cards 14, first-run and empty panels 16, pill and chips full |
| Concentric rule | outer = inner + padding (a 14 px card holds 9 px buttons with about 5 px of visual inset) |
| Spacing | 4 px grid: 6, 8, 10, 12, 14, 16, 18, 22, 28 |
| Controls | 36 px tall (34 in the pill, 32 in Settings rows); hit areas at least 40 px |
| Rail | 272 px; drawer 320 px at a 420 px window |
| Icon tiles | 32 px (26 small, 44 large), radius 9 |

### Icons, app icons, illustrations

- **Phosphor** (MIT, github.com/phosphor-icons/core): regular at 20 px for navigation and rows, **fill** for the active nav item and status ticks, **duotone** inside tiles and the pill. One SVG per icon in `currentColor`; states change colour only. The 57 icons used are in `src/phosphor.json`, cleaned to plain shapes.
- **Target apps** (Kite, Telegram, Chrome, VS Code, Outlook, Claude Code, unknown): simple stand-in shapes in recognisable colours, 16 to 32 px, always beside their name, with a 1 px outline at 10% black (light) or white (dark). In the app these come from the real executable's icon.
- **Spot illustrations**, 96 to 120 px, duotone line art in `--ill-*`: microphone, keycaps, done, history, search, lightbulb, book, microphone blocked. Used on every empty state and each first-run step.
- **The waveform** is the signature: live in the pill, a 10-bar thumbnail per History row, the stat sparklines as rounded bars, the rail's hotkey card, and the illustrations.

### Motion

Transitions 150 to 200 ms, ease-out, transform and opacity only; press scales buttons to 0.96 for 100 ms; icon swaps cross-fade at scale 0.25 to 1 with blur 4 to 0 px, no bounce. The pill: appears by rising 8 px (180 ms); bars follow the voice; after 3 s with no input it switches to No sound; cleaning eases the bars to a resting line with a 1.2 s shimmer; pasted shows a tick for 600 ms then slides down 8 px and fades (150 ms); a problem never fades. Reduced motion: no slide, no shimmer, instant swaps with the same icons and words.

### Behaviour that the tokens support

- **Problems:** title (what happened, where), one line of cause, one tonal fix; a 3 px `--danger` or `--warning` edge and a tinted tile; text stays `--foreground`.
- **Undo, not confirm:** deleting a dictation, saving a spelling or answering a suggestion shows a toast with Undo (and Ctrl+Z) for 8 s, with a draining bar. Only "Delete all history" (under Advanced) asks first.
- **First run:** three screens, one real test dictation, skippable, vocabulary optional at the end.

## B3.1 (2026-11-16): fixes from the usability test

No colour values changed; every B3 contrast figure still holds. Mockup: `voiceflow-b3-1.html` (this folder).

| Change | What it needs in the build |
|---|---|
| Your notes, your assistant | User-facing text says "your notes" and "your assistant". The assistant name is a per-user setting (`assistantName`, default "Milo"; Settings > Accounts > Your assistant’s name). "Atlas" appears only in the "your notes" tooltip and in About. |
| Tidy-up | The feature formerly called clean-up is "Tidy-up" in every label, error and menu. The word carries a tooltip: "Fixes punctuation and filler words in your chosen style". |
| Tooltip | Inverse surface: `--foreground` fill, `--surface` text (17.0:1 light, 14.9:1 dark), 12.5 px, radius 8, max 260 px, opens below its word on hover or focus. |
| Counts | Home "Needs you" lists problems only (1); suggestions are "Ideas" (Home row "Ideas for you", nav "Ideas", count 2). Status bar, tray and narrow chip all say "1 problem". |
| Idea answers | "Always write X" (tonal primary), "Add X as a word, no auto-fix" (secondary), "No, Y is right" (text), stacked, then "Changes new dictations only. Past ones stay as they were." |
| Success states | Copy turns into a "Copied" button for 2 s (`--tile-ok` fill, `--success` text, 4.85:1); saving a spelling shows an inline note with a 3 px `--success` edge. |
| Undo window | Toast: "Dictation deleted. Undo within 8 seconds, or find it in Recently deleted for 30 days.", an 8 s countdown and a draining bar; History gets a "Recently deleted (30 days)" filter. |
| Labelled stat bars | "Last 7 days" label, one day initial under each bar (10 px, `--subtle-foreground`, 6.1:1), a value tooltip on hover. |
| First run | "Step n of 3" text over 44 x 6 px step bars; one method taught (Ctrl+Space); the sentence to read sits inside the test box; hold-to-talk is a tip on the last step. |
| No-sound pill | "Is your mic muted or unplugged?" with Choose microphone (tonal) and Sound settings (text). |
