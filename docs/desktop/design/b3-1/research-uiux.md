# VoiceFlow B3: UI/UX research (2026-11-16)

Feeds the redesign from "B2 Graphite/Soft" (calm but "way too dry") to B3. Three parts: the skills report, the B3 design rules, and a usability test protocol. Sources and dates are listed at the end; [S#] tags point to them.

Nothing was installed and no third-party skill code was run. Repos were read through `gh api` only.

---

## 1. Skills report: UI/UX skills for coding agents

### What exists (GitHub numbers fetched 2026-11-16)

| Skill (repo) | Stars | Last push | Licence | What's inside | Runs code / network? | Fit for VoiceFlow (desktop utility app) |
|---|---|---|---|---|---|---|
| **frontend-design** (`anthropics/skills`) | 196k (whole repo) | 2026-11-16 | per-skill LICENSE.txt | Short prose rulebook: commit to a distinctive aesthetic, ground it in the subject, avoid templated defaults | No | **Already installed.** Good at "pick a point of view", weak on app-UI detail (no icon, state or UX checklist) [S1][S2] |
| **ui-ux-pro-max** (`nextlevelbuilder/ui-ux-pro-max-skill`) | 150k | 2026-11-16 | MIT | CSV database plus a Python keyword search: 80 styles, 192 palettes, 74 font pairings, 120 UX guidelines, 105 icons, 25 chart types, 22 stacks (Avalonia, JavaFX and SwiftUI among them). `references/pro-rules.md` holds a pre-delivery checklist | Yes: `python scripts/search.py` (local; I grepped `search.py`, `core.py` and `design_system.py` and found no network calls). `--persist` writes `design-system/MASTER.md` into the project. The repo also bundles brand, design and slide sub-skills with `.cjs` and `.py` scripts, one called `fetch-background.py` | Breadth tool. Reviewers' verdict is that it "changes the paint, not the design" and that keyword retrieval can return off-target matches [S1][S2] |
| **impeccable** (`pbakaus/impeccable`) v4.9.2 | 111k | 2026-11-16 | Apache-2.0 | Design-director skill with "modes" (Persuade, **Operate** for app UI and tools, Read, Experience) and commands: `critique`, `audit`, `polish`, `quieter`, **`bolder`**, **`colorize`**, **`delight`**, **`onboard`**, `clarify`, `harden`, `typeset`, `layout`, `animate`. 59 deterministic detector rules, PRODUCT.md and DESIGN.md files | **Yes, heavily.** The launcher (`scripts/impeccable`) runs a native binary and **downloads it from GitHub releases on first run** (checked against `engine-checksums.txt`). It installs a **PostToolUse hook that runs on every UI file edit** and a Stop hook, writes `.impeccable/` into the project and a machine-local settings file, and ships `live-browser*.js` (566 KB) for browser iteration | **Best conceptual fit.** Its Operate mode and its `bolder`, `colorize` and `delight` playbooks are exactly the "calm but dry" problem. Also the only one with a CI story (`detect` outputs JSON and exit codes) [S2] |
| **taste-skill** (`Leonxlnx/taste-skill`) v2.5.1 | 117k | 2026-11-08 | MIT | Anti-slop **website** skill: landing pages "at the level of figma.com and x.ai", one section per screen, photos, shader scenes, a browser "finish gate" | Yes. A **Stop hook runs `node gate.mjs` at the end of every session** it is loaded in, and it needs Node 22+ and Chrome or Edge | **Poor fit:** built for marketing sites, not an app's settings and history screens. Note: `minimalist-ui`, `redesign-existing-projects`, `image-to-code`, `imagegen-frontend-web` and `full-output-enforcement` are already installed in `~/.claude/skills/` and look like earlier taste-skill variants. *Inference:* `minimalist-ui` prescribes "warm monochrome… muted pastels… no gradients, no heavy shadows", which may have pushed B2 toward "dry" |
| **emil-design-eng / apple-design / animate…** (`emilkowalski/skills`) | 57k | 2026-11-15 | MIT | Motion and interaction rules (easing and duration tables, springs, 44 px hit areas) and an `apple-design` skill distilled from WWDC "Designing Fluid Interfaces" | Prose only (plus a cheat sheet) | Good add-on for the pill and micro-interactions [S2] |
| **make-interfaces-feel-better** (`jakubkrehel/make-interfaces-feel-better`) | 4k | 2026-11-16 | MIT | 14 concrete polish principles plus `icons.md`, `surfaces.md`, `typography.md`, `animations.md`: concentric radii, shadows rather than borders for depth, outline icons by default and filled when active, icon stroke matched to text weight, tabular numbers, image outlines | **Prose only, no scripts** | **High fit, zero risk.** The exact detail layer B2 lacks |
| **ui-skills / baseline-ui** (`ibelick/ui-skills`) | 11k | 2026-11-14 | MIT | Five "fixer" skills (baseline-ui, accessibility, motion…) as MUST/NEVER lists | The repo is also a website plus a CLI (`bin/ui-skills`); the SKILL.md files themselves are prose | QA and cleanup of existing UI; Tailwind-specific [S1] |
| **superdesign** (`superdesigndev/superdesign`) | 7.6k | 2026-06-29 | NOASSERTION | A design **agent and product** (IDE extension), not a static skill | Yes; it is an app | Its own blog review favours it, but the author works there [S1] |

Reviewers' shared conclusion [S1][S2]: all of these are static rulebooks that design blind (none sees its own rendered output except impeccable's live mode and huashu-design). The differences show up when you **match an existing app**, not on solved one-page patterns. Style-preset skills can clash with an existing design.

### Recommendation

1. **Install `make-interfaces-feel-better` (MIT, prose only).** It has the best ratio of risk to value for B3's detail layer: icons, depth, radii and motion. Read in full: it has no scripts.
2. **Use impeccable's playbooks, not its engine.** For the "dry" problem its `reference/colorize.md`, `bolder.md`, `delight.md`, `onboard.md`, `critique-rubric.md` and `craft-floor.md` are the most relevant material found. Two options:
   - (a) **Safer:** copy those reference `.md` files into a private skill and drop the launcher, binary, hooks and live-browser scripts. Apache-2.0 allows this with attribution. By the project's rule, a new or changed skill goes through `skill-creator`.
   - (b) **Full install:** pin a tag and review `scripts/` first. Accept that it downloads a native binary from GitHub releases and adds an edit-time hook to every UI edit in that project. Turn on `hook.quiet` or skip `hooks` if the per-edit reminders become noise.
3. **Optional: `emilkowalski/skills` `apple-design` and `emil-design-eng`** for the recording pill's motion (prose, MIT).
4. **Skip `taste-skill`** (website-only; a session-wide Stop hook runs Node) and **`ui-ux-pro-max`** (breadth over judgement; script-heavy; adds little beyond frontend-design for one product with an existing look). Revisit ui-ux-pro-max only if you want a palette and font-pairing lookup table.
5. **Review `minimalist-ui` (already installed).** If it is triggering on VoiceFlow work, its "warm monochrome, no shadows" stance works against B3.

### Security notes
- Skills are a prompt-injection and code-execution surface. Install only from the repo itself, read `scripts/` and any `hooks:` frontmatter before the first run, and pin a version or tag [S2].
- **Hooks in SKILL.md frontmatter** (taste-skill's Stop hook) and **hooks installed by a command** (impeccable) run outside the model's control on every matching event. These are the main risk, ahead of the prose.
- impeccable: remote binary download (checksum-pinned, but still an executable fetched at runtime), per-edit hooks, writes `.impeccable/` into the repo and a local settings file.
- ui-ux-pro-max: local Python only (no network found in the three core scripts I grepped). Its other bundled sub-skills include `fetch-background.py` and `.cjs` scripts that I did not audit.
- make-interfaces-feel-better and emilkowalski/skills: Markdown only.

---

## 2. VoiceFlow B3 design rules

The B3 thesis: **keep B2's calm structure; add warmth, real-world imagery (app icons, waveform), tinted icon tiles, coloured primary actions and a few earned moments.** Colour stays rare, so each use carries meaning [S3][S10-colorize].

### Colour and surface
1. **Warm the neutrals.** Move B2's cool zinc greys to a low-chroma warm grey (OKLCH hue about 60–80, chroma ≤ 0.012). Keep teal #115E59 as the one accent. Linear's March 2026 "calmer" refresh did exactly this (cool blue-grey to warmer, less saturated grey) and warned that going too warm looks muddy, so tune lightness in OKLCH [S3][S10].
2. **The primary button is filled with the accent.** One per view; secondary buttons stay soft grey. B2's grey primaries remove the one place colour should live: in an Operate surface colour encodes "action, selection, status, wayfinding" and is strong because it is rare [S10-colorize].
3. **Tinted icon tiles carry the colour.** Each Home card and each Settings section header gets a 32 px rounded tile filled with the accent at 10–12% tint, holding a 20 px duotone icon in the accent. That gives colour without playfulness. Fixed colours used sparingly "clarify the meaning of an icon or draw attention to it" (Apple's Mail VIP example) [S5]. Semantic hues (amber warning, red error, green success) appear only on status, never as decoration.
4. **Dim the chrome, light the content.** Sidebar labels in secondary text colour. The selected item gets an accent-tinted pill and the filled icon. Linear dimmed its sidebar "a few notches": "Don't compete for attention you haven't earned" [S3].

### Icons and imagery
5. **One icon family, three weights.** Use Phosphor (MIT) on both OSes: *regular* at 20 px for navigation and rows, *fill* for the active state, *duotone* at 32–48 px for tiles and spot illustrations. Outline by default, fill when active. Stroke weight follows text weight: about 1.5 px next to 14–16 px regular text. Use the native grid sizes (16, 20, 24) only, `currentColor` for states, and keep labels always visible in navigation [S9][S5]. (Lucide, ISC, 25.8k★, is the alternative, but it has no filled or duotone weights.)
6. **Show the target app's real icon on every dictation** (History rows, Home's recent list, Styles' per-app previews): 16–20 px, with a 1 px outline at 10% opacity (black in light, white in dark) so icons sit consistently on any surface [S9]. Recognition beats recall (Nielsen #6), and per-app context is how competitors frame styles: Wispr's styles are per app category, and Superwhisper's recording window shows the current app context [S12][S11].
7. **The waveform is the signature visual.** In the pill, a live waveform: "a moving waveform means your microphone is working" [S11]. History rows get a small static waveform thumbnail plus duration. The app mark and the empty-state illustrations reuse the same bar motif, so the brand is one shape, not an extra colour.
8. **Empty states use a 96–120 px duotone spot illustration in the accent, one sentence saying what will appear here, and one action** (or the dictation hotkey as a keycap). For example, Vocabulary: "Words VoiceFlow should always spell your way" plus [Add a word]. Empty states should communicate status, teach and give a direct path to the key task [S8].
9. **Never show "nothing here" while loading.** Use a skeleton of the real rows. A false "No records" that is later replaced by content destroys trust [S8].

### Depth and shape
10. **Three elevation levels only.** Cards at rest: hairline border plus a tiny shadow (Fluent shadow2/4). Menus, popovers and the tray menu: shadow16. Dialogs and the recording pill: shadow28–64. Use a crisp key shadow plus a soft ambient one. In dark navy, raise the key-shadow opacity (Fluent goes from 14% to 28%). On Windows, a stroke may replace the key shadow [S4].
11. **Shadows for depth, borders for structure; concentric radii.** Outer radius = inner radius + padding. Use borders only for dividers, selection and focus [S9]. Remove separators that have no reason to exist: "Structure should be felt not seen" [S3].

### Type and numbers
12. **Inter Variable for the UI** (OFL, cross-platform, tall x-height, built for dense app UI), **Inter Display for titles of 20 px and up**, and tabular numbers for stats, durations and timestamps [S9][S-fonts]. For one touch of warmth, page titles only may use Instrument Sans; check its licence on the official page first, because sources disagree [S-fonts]. Use `text-wrap: balance` on headings [S9].
13. **Home gets one stat strip:** words this week, words per minute, time saved, each with a 7-day sparkline in the accent. Wispr's Home leads with exactly these (days, words, WPM) [S12]. It turns a dry dashboard into progress without decoration.

### Motion and feedback
14. **Respond on press.** Apply `scale(0.97)` within 100 ms on `:active`. Interaction feedback stays at 200 ms or less, ease-out. Animate transform and opacity only, and honour reduced motion [S13][S14].
15. **Pill states differ by shape and icon, never colour alone:** idle, listening (live waveform), processing (bars settle into a shimmer), done (check, about 600 ms, then out), no audio (a still waveform plus "No sound from mic" and [Choose mic]; Superwhisper added this warning on 2026-05-29), and error (icon, short text, one fix) [S11][S7]. Icon swaps cross-fade at scale 0.25→1, blur 4→0 px, with no spring bounce [S9].
16. **Delight only at earned moments:** the first successful dictation on first run, and milestones (1k or 10k words). Nothing on ordinary clicks. "Do not manufacture a celebration for an ordinary click" [S10-delight].

### Intuitiveness
17. **Problems explain themselves.** Keep B2's thin coloured edge plus icon, and add a plain-language title, the cause in one line and a single fix button ("Microphone blocked: Windows privacy settings stop VoiceFlow from hearing you. [Open settings]"). Severity sets the container: inline for a warning, a banner for a blocker, a modal only for destructive actions. Never signal by colour alone [S7].
18. **Undo, not confirm.** Deleting a dictation or a vocabulary word, or accepting a suggestion, shows a toast with **Undo** for about 8 s. A confirm dialog appears only for irreversible bulk actions ("Clear all history") [S14] (Nielsen #3 user control and freedom).
19. **Hotkeys appear as keycap chips** wherever an action has one (first run, empty states, tray menu, pill tooltip). Recognition over recall (Nielsen #6).
20. **Settings use progressive disclosure.** The six or so settings people change are visible, each with a one-line helper. "Advanced" is collapsed. Never more than two levels of hierarchy in the sidebar, and nothing critical at its bottom edge [S5].
21. **First run is a task, not a tour:** a welcome line, mic permission requested in context, then "Hold [Ctrl]+[Win] and say 'Hello VoiceFlow'" with the real pill live on screen, success, and done. Skippable. Defaults pre-set, so nothing else is required. Teach by doing and postpone nonessential setup [S6][S10-onboard].
22. **Suggestions show before → after** (strikethrough to new text) with Accept, Dismiss and Undo, plus the app icon of where the suggestion was seen. People judge a change by seeing it, not by reading a description.
23. **Styles preview with a real sentence per app category** (Email, Chat, Docs), each headed by the app icons it applies to. Wispr applies styles per app category and only to new dictations, so say "applies to new dictations" under the control [S12].
24. **The tray menu carries a status dot** (ready, listening, problem) and the current style with a checkmark in a "Style" submenu, mirroring Superwhisper's menu bar [S11].
25. **Contrast floor:** body text at least 4.5:1 on its surface in both themes, secondary text included. Icon-only buttons get an accessible label [S14].

---

## 3. Usability test protocol (static mockups)

**Method.** First-click plus task test on static screens (B3 mockups at desktop size), think-aloud.
- Five people is enough to find most issues. In addition, the project's fresh-reader practice applies: one new Sonnet subagent per task, given only the screenshot and the prompt, as a cheap pre-check, never as proof.
- Read each prompt aloud. Never use the UI's own words in the prompt; for example, say "spell it right next time", not "Vocabulary".

**Score each task:**
| Measure | How | Pass bar |
|---|---|---|
| First click | Did the first click land in the predefined correct zone? (y/n) | ≥ 4 of 5. A correct first click predicts task success (secondhand: 87% vs 46%) [S16] |
| Task success | Success / partial (needed a hint) / fail | ≥ 4 of 5 success |
| Ease (SEQ) | "Overall, how easy was that?" 1–7 | Median ≥ 6 |
| Confidence | "How sure are you that worked?" 1–5 | ≥ 4. Catches silent-success problems, such as undo or styles applying only to new dictations |
| Notes | Hesitation over 5 s, wrong turns, misread labels | Each recurring one becomes a fix |

With five people, report counts ("4/5"), not percentages; a static click test can mispredict live behaviour, so confirm the worst tasks on the clickable build [S16]. Afterwards, rate each screen against Nielsen's 10 heuristics at 0–4 severity.

**Tasks** (start screen in brackets):
1. **[Home]** "How many words have you dictated this week, and is that more than last week?" Tests the stat strip and sparkline.
2. **[Home]** "Yesterday afternoon you dictated something into Slack. Find it and copy it." Tests History, app icons and search.
3. **[History]** "VoiceFlow keeps writing your colleague 'Siobhan' wrong. Make it spell her name right from now on." Tests the add-to-Vocabulary path from a row.
4. **[History]** "Delete this dictation. Oh wait, you needed it; get it back." Tests undo.
5. **[Suggestions]** "VoiceFlow has an idea for you. Decide whether you want it, and say what it will change." Tests before→after legibility.
6. **[Styles]** "Your emails sound too stiff. Make dictated emails more casual, but leave Slack as it is." Tests per-app styles and "new dictations only".
7. **[Pill: no-audio state]** "You're talking but nothing is happening. What's wrong, and what would you click?" Tests problem-state clarity.
8. **[Home with a problem card]** "Something needs your attention. What is it and how do you fix it?" Tests the edge, icon, plain text and fix button.
9. **[Settings]** "Change the key you hold down to dictate." Tests progressive disclosure and keycaps.
10. **[First run]** "You just installed VoiceFlow. Get it working and do your first dictation." Tests the task-based onboarding.

**After all tasks:** "Describe this app to a friend in one sentence." Then "Which screen felt calm, and which felt dull?" That question checks the B3 goal directly. The verdict scale: too playful / about right / too dry.

---

## Sources

- [S1] Jason Zhou, "Design skills reviewed", superdesign.dev blog, 2026-07-02 (the author works on Superdesign; disclosed). https://superdesign.dev/blog/design-skills-reviewed
- [S2] Dragos, "Best AI Skills for Web Design: Agent Skills Compared (2026)", Bitdoze, 2026-08-28. https://www.bitdoze.com/best-ai-skills-for-web-design/
- [S3] Charlie Aufmann and Maxime Heckel, "A calmer interface for a product in motion", Linear, 2026-03-12. https://linear.app/now/behind-the-latest-design-refresh (changelog https://linear.app/changelog/2026-03-12-ui-refresh)
- [S4] Microsoft Fluent 2, "Elevation", accessed 2026-11-16. https://fluent2.microsoft.design/elevation
- [S5] Apple HIG, "Sidebars" (changelog: icon-colour guidance updated 2026-06-08), accessed 2026-11-16. https://developer.apple.com/design/human-interface-guidelines/sidebars
- [S6] Apple HIG, "Onboarding", accessed 2026-11-16. https://developer.apple.com/design/human-interface-guidelines/onboarding
- [S7] Tim Neusesser and Evan Sunwall, "Error-Message Guidelines", NN/g, 2023-05-14. https://www.nngroup.com/articles/error-message-guidelines/
- [S8] NN/g, "Designing Empty States in Complex Applications", accessed 2026-11-16. https://www.nngroup.com/articles/empty-state-interface-design/
- [S9] jakubkrehel/make-interfaces-feel-better, `SKILL.md` and `icons.md`, commit 2026-11-16. https://github.com/jakubkrehel/make-interfaces-feel-better
- [S10] pbakaus/impeccable v4.9.2, `reference/colorize.md`, `delight.md`, `onboard.md`, `SKILL.md` (Modes), pushed 2026-11-16. https://github.com/pbakaus/impeccable
- [S11] Superwhisper docs, "Recording window" and "History", accessed 2026-11-16; changelog (New History 2026-01-02; no-audio warning 2.15.0, 2026-05-29). https://superwhisper.com/docs/get-started/interface-rec-window, https://superwhisper.com/docs/get-started/interface-history, https://superwhisper.com/changelog
- [S12] Wispr Flow docs, "Navigating the Wispr Flow app" and "How to set up Flow Styles"; changelog 2025-10-29 and March 2026. https://docs.wisprflow.ai/articles/5096240724-navigating-the-wispr-flow-app-desktop-ios-and-android, https://docs.wisprflow.ai/articles/2368263928-how-to-setup-flow-styles
- [S13] emilkowalski/skills, `apple-design/SKILL.md` (from WWDC 2018 "Designing Fluid Interfaces"), pushed 2026-11-15. https://github.com/emilkowalski/skills
- [S14] ibelick/ui-skills, `baseline-ui/SKILL.md`, pushed 2026-11-14. https://github.com/ibelick/ui-skills
- [S16] MeasuringU, "How to analyze click test metrics in stand-alone studies", https://measuringu.com/how-to-analyze-click-test-data-standalone/; first-click 87%/46% figure (Bailey and Wolfson) cited second-hand via https://www.askable.com/blog/first-click-testing-what-it-is-why-it-matters-and-how-to-do-it-right (original not verified).
- [S-fonts] Font comparisons (background only, no warmth verdict): https://fontalternatives.com/blog/geist-vs-inter-vs-satoshi-product-ui/, https://fontalternatives.com/compare/inter-vs-instrument-sans/
- Nielsen's 10 usability heuristics (NN/g, 1994, updated 2020): cited from standard knowledge, not re-fetched in this run.

### Not verified / gaps
- Wispr Flow's and Superwhisper's exact visuals (whether history rows show app icons, the pill's colours and sizes): their docs don't say, and nothing was screenshotted. Rule 6 is a recommendation, not a copy of a competitor.
- Raycast, Things 3, Arc, Notion Calendar and Superhuman: no source was read this run (search budget). Rules lean on Linear, Apple, Fluent and the dictation competitors instead.
- Fluent icon default size (20 px) came only from a third-party page. Instrument Sans's licence: sources conflict.
- impeccable's binary and hooks and ui-ux-pro-max's non-core scripts were not audited line by line.
