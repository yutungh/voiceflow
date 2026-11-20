# VoiceFlow Desktop: product and architecture spec

> **Public copy.** This is the product part of the desktop spec: the dictation pipeline, the word-list pack and how it syncs, the privacy model and the features. The desktop app lives in this monorepo under `desktop/` (code) and `docs/desktop/` (docs). `PLAN-v2.md` revises the profile model and the phase order; where they differ, the plan wins.

Draft 2, 2026-11-16.

**Sources** (cited by tag):

| Tag | What | Where |
|---|---|---|
| A | Whispering 7.11 internals, AGPL, upstream direction | `research/A-whispering.md` |
| GT | gpt-transcribe live test on real clips | `research/GT-live-test.md` |
| VF | VoiceFlow Android vocabulary storage and sync | `research/VF-sync-audit.md` |
| CP / CC | Context capture by platform / competitor patterns | `research/CTX-platform.md`, `CTX-products.md` |
| V | Verified claims (deep mode) | `research/V-tally.md` |

---

## 1. Goal, users, non-goals

**Goal.** A desktop dictation app that spells your names right, knows which app you are talking into, and keeps its word lists current without you touching settings. Press a hotkey, speak, get clean text at the cursor.

| | |
|---|---|
| **Users** | One person per install, on Windows 11 or macOS. A household runs one install per person, each with its own profile. No profile switcher. |
| **Sibling** | VoiceFlow Android keyboard (MIT, at the root of this repository). Both read the same per-user **pack** (§3). |
| **Why a fork** | Fork Whispering 7.11.0 and own it. Upstream is retiring dictation (no paste-to-cursor, no auto-run, Windows untested on main) [A, V-C2]. |
| **What must survive from Whispering** | Toggle hotkey Ctrl+Space, push-to-talk Alt+Shift+D, cancel, paste at cursor + clipboard, local history, provider choice, auto-run clean-up. |
| **What it fixes** | An empty vocabulary prompt, so coined names are misspelled; clean-up that silently stops when a model is retired (gpt-4.1-nano); cleaned text that never reaches the cursor; whisper-1 retiring 2027-02-26 [V-C1]. |

**Non-goals (v1):** a profile switcher; Linux; streaming transcription; local STT as default (Parakeet stays an option); a plugin API; one person editing another person's profile; the app holding cloud-account credentials; any silent rewrite the user did not approve.

---

## 2. Architecture

### 2.1 Pipeline (one dictation)

```
hotkey ─► Tier A app identity (capture + 2 re-samples, ≤100 ms) ─► Tier B/C checks (§4.3)
       ─► record ─► stop
       ─► upload authorized? no ─► audio kept locally + notice ("Send anyway")
       ─► transcribe: gpt-transcribe, prompt = app terms + base terms (§3.4)
       ─► clean-up: style prompt + glossary (handles names) + (M6) context slot, timeout 10 s
       ─► approved corrections, ONCE, on the final output (§3.5)
       ─► paste-time authorization (target still allowed?) ─► paste + clipboard
       ─► history: raw, final, pack version + selected rules pinned to the recording
```

Clean-up fails or times out → **nothing is pasted** (design B3.1): the raw transcript stays in history, a persistent error says "Tidy-up didn't finish, so nothing was pasted." and offers **Retry tidy-up** and **Paste raw text**; raw text is pasted only when the user asks. Transcription fails → audio kept, error shown, Retry offered. Nothing fails silently.

### 2.2 Fork layout

1. **Import:** commit the v7.11.0 tree (1,645 files) unchanged, with the upstream tag sha in the message.
2. **M0:** build that untouched tree: Windows locally, macOS through CI.
3. **M1:** product changes on top, at upstream paths (`apps/whispering`, `packages/*`), so diffs against the tag stay readable.
4. **Workspace extraction:** a **separate, reversible commit** made only after green builds. It drops the workspace folders `apps/whispering` does not use: `@epicenter/config`, `ui`, `constants`, `hq` (= `packages/epicenter`), `shared`, `svelte-utils` stay, plus any transitive ones `bun install` names. `git revert` restores the dropped folders.

**Windows path length.** Upstream's build-from-source broke on the 260-char limit (#2432) [A]. Set `git config core.longpaths true`, turn on Windows `LongPathsEnabled` (admin, one-time), and point `CARGO_TARGET_DIR` at a short path.

### 2.3 Renaming (M1)

| Item | Whispering 7.11.0 | VoiceFlow |
|---|---|---|
| `productName`, window title, icons | Whispering | **VoiceFlow**, new icons |
| `identifier` | `com.bradenwong.whispering` | **`com.voiceflowkeyboard.desktop`** (matches Android's `com.voiceflowkeyboard`); dev: `…desktop.dev` |
| App data (Win / mac) | `%APPDATA%\com.bradenwong.whispering\` | `%APPDATA%\com.voiceflowkeyboard.desktop\` / `~/Library/Application Support/com.voiceflowkeyboard.desktop/` |
| Updater | upstream GitHub `latest.json` + Braden's pubkey | **Disabled in M1**; manual releases until the fork's own signed updater in M4 (§7.3). Left as is, it would "update" to upstream. |
| macOS signing identity | `Developer ID … Braden Wong` | own (§7.2) |
| Telemetry | `@aptabase/tauri` + `@aptabase/web` | **Removed** |
| API key | WebView localStorage | **OS keychain** (Credential Manager / Keychain) |
| Copyright/publisher | Braden Wong | upstream copyright kept, plus "Modified by <maintainer>, 2026" (AGPL §5a) |
| Deploy scripts | `wrangler deploy` (Cloudflare) | removed |

### 2.4 Moving over from a Whispering install (M1)

| What | Where in Whispering | How |
|---|---|---|
| Settings (hotkeys, mic, output flags) | WebView2 localStorage `whispering-settings` | **Re-entered by hand** on first run (a handful of values). No LevelDB import. |
| OpenAI key | same localStorage | Typed once into VoiceFlow, stored in the keychain. |
| Known-bad values | whisper-1; gpt-4.1-nano; cleaned output not pasted | New defaults: gpt-transcribe, gpt-5.4-mini, paste cleaned output. |
| A clean-up transformation | `transformations/*.md` | Its prompt becomes the pack style `casual` (§3.3); its find/replace steps become seed corrections. |
| History transcripts | `recordings/*.md` | **Not imported.** The learning loop (§5) can read them in place. |
| Audio | `recordings/*.wav` | **Left in place.** |

---

## 3. The pack (sync contract, desktop + Android)

### 3.1 Principles
- **One source of truth per layer.** Pack-managed data has no local edits that silently override it; `PLAN-v2.md` adds immutable "Add a word" operation files for edits made on a device.
- **Hints ≠ corrections.** A term biases recognition and clean-up. Only an **approved correction** rewrites text. A name or alias never becomes a correction by itself.
- **Never empty, never half-written.** Schema-versioned, validated, atomic; clients keep the last good pack.
- **Minimal export.** Terms and approved rules only; no note bodies or other personal text.

### 3.2 Files on the shared folder (per user)

```
voiceflow/
  packs/<user>/manifest.json        {schema, packVersion, file, sha256, bytes, generatedAt}
  packs/<user>/pack-<packVersion>.json   (last 14 kept; rollback = repoint manifest)
  packs/<user>/today.json           (M6: calendar-day overlay, same envelope)
  status/<user>/<deviceId>.json     (written by each app, §6)
  releases/<target>/latest.json + installers + .sig  (M4, §7.3)
  source/<version>.tar.gz           (AGPL corresponding source, §7.4)
```

Publish = write the versioned file, verify it, then replace `manifest.json` (rename-over). A client never sees a partial pack. The format and the "connect your own AI" guide are in `pack/README.md` and `CONNECT-YOUR-OWN-AI.md`.

### 3.3 Schema `voiceflow-pack` v1 (JSON Schema + fixtures in this repo under `pack/`)

| Field | Content |
|---|---|
| `schema`, `schemaVersion` | `"voiceflow-pack"`, `1`. Client refuses a higher major version and keeps last-good. |
| `user`, `packVersion`, `generatedAt`, `source` | user id, monotonic `2026-11-16.2`, ISO time, source revision |
| `terms[]` | `{id, text, heardAs[], kind, gloss, priority, apps[]}`. `kind`: person, bot, project, org, product, acronym, place. `gloss` ≤ 80 chars, for clean-up ("the user's assistant bot"). `apps`: `["*"]` or app ids. |
| `corrections[]` | `{id, from[], to, scope:{apps[]}, match:"word-ci", approvedAt, approvedBy, source}`. Only approved ones are exported. |
| `apps[]` | `{id, label, match:{win:{process[], titleRe?}, mac:{bundleId[], titleRe?}, android:{package[]}}, terms[] (ids), glossary, style?, context}`. `context`: `full`, `title-only`, `app-only`. |
| `privacy` | `{blockApps[], titleDenyRe[], passwordCheck:"strict"}` (§4.3) |
| `styles[]` | `{id, label, model, prompt, expression?}`. Android's ids: `raw`, `casual` ("Friends"), `business` ("Work"), `family`, `partner`, `fun_*`. `casual` is the default. |
| `defaults` | `{transcriptionModel:"gpt-transcribe", language:"en", promptChars:600, promptTerms:40, sendKeywords:false, cleanupModel:"gpt-5.4-mini", cleanupStyle:"casual", cleanupTimeoutS:10}` |

**Validation is split:**

| Check | Publisher | Client |
|---|---|---|
| Schema, `schemaVersion`, `user` matches this install, `sha256` matches manifest | yes | yes |
| Non-empty `terms`, no empty `to`, no `< > CR LF` in `from` [GT] | yes | yes |
| No duplicate `from`, no chains (a `to` that is another's `from`), no cycles | yes | yes |
| Pack ≥ 50% of previous term count unless the source says "shrink ok" | **yes, only here** | no: clients need no publisher state |

**Who owns which setting:**

| Pack owns | Device owns |
|---|---|
| terms, corrections, app rules, privacy lists and mode, styles, model defaults | API key, sync credential (M4), mic, hotkeys, paste method, history retention |

Model defaults are pack-owned on purpose: when a model is retired, fixing it is a data change that reaches every device the same day.

### 3.4 Prompt builder (per dictation)
1. **Select:** the matched app's terms first, then fill with base terms by priority, within `promptChars` (600) and `promptTerms` (40).
2. **Pre-pack:** a term that does not fit is skipped and the next is tried; skipped terms go to the local log.
3. **Order:** most relevant last (base terms first, app terms at the end). Dedupe.
4. **Format:** one line, `Vocabulary: Milo, Febe, Pinewood, Claude Code, …`. No sentences, which can be echoed on silence [V-C5].
5. **Request:** Whispering's proven shape (`file, model, language=en, temperature=0, prompt`); never `language` and `languages` together [GT]. `keywords[]` off (no measurable gain [GT]) until an A/B says otherwise.
6. **0.9.15, the transcriber's share:** the request (the line and `keywords[]`, sent since 0.9.11) carries only the first 20 words selected in step 1 (`TRANSCRIBE_TERMS`, a code constant, capped at 40), with hints fitted to that line; the tidy-up keeps every selected word (§3.5). On real recordings 50 or 80 words made gpt-transcribe drop and invent words; 20 got them right. gpt-transcribe models get the language as `languages[]` (as OpenAI documents for them), other models keep `language`; never both (`pack/README.md`, "The vocabulary prompt").

Evidence: 20/22 target names with a one-line prompt vs 13 without, 9 for whisper-1 [GT].

### 3.5 Corrections and glossary
- **Names are handled first by the clean-up glossary** (terms with `gloss` plus the app glossary). "Phoebe → Febe" is an app glossary line, never a correction: the user may talk about a real Phoebe.
- **0.9.14:** the clean-up glossary is the tidy-up's word list: the words the vocabulary prompt chose, with their notes and heard-as forms, after each non-fun style's instructions, and a rule to fix a sound-alike mishearing only when the context points to the word (`pack/README.md`, "The tidy-up's word list").
- **Approved corrections** are scoped, literal (whole word, case-insensitive), non-cascading, and **applied once, to the final output** (cleaned text, or the raw transcript when clean-up failed).
- **Typical seed corrections:**

| Correction | Collision the user must accept to make it global |
|---|---|
| Pinewud → Pinewood (a coined project name) | A real mention of the misheard word |
| Cloud Code → Claude Code | Google's "Cloud Code" tool |
| Chachapiti / Chattopaddy → ChatGPT | none known: global after approval |

- **Recommendation:** global only where the user accepts the named collision; otherwise scope the correction to the apps where it matters. Scoping reduces exposure but cannot remove ambiguity inside the chosen apps.
- Clean-up input: style prompt; glossary; transcript in `<transcript>` tags; (M6) context in a labelled `<reference>` slot, marked untrusted text, never instructions.

### 3.6 Publisher
- Any tool may publish a pack, as long as it follows the schema and the validation above. An open-source user can hand-edit the files or let their own AI assistant edit them (`CONNECT-YOUR-OWN-AI.md`).
- **When:** nightly and on demand after an edit.
- **Sources:** hand-curated terms with heard-as forms, approved corrections, app rules, styles, defaults, privacy lists and mode; optionally names generated from the user's own notes. Ranking: known mishearings first, then recently active names.
- **Isolation:** each user's pack is built only from that user's sources plus a household list everyone agreed to share.
- Writes the result and the term-count diff to a publish log.

### 3.7 Transport

| Stage | Transport | Notes |
|---|---|---|
| **M2** | **A shared folder** (local disk, SMB or a synced folder) | Behind a read-bytes seam, so HTTPS slots in later without touching the pack code. |
| **M4** | Authenticated HTTPS (for example WebDAV) with per-user accounts | Adopted **only after** tests pass: folder ACLs, cross-user denial, GET and PUT, and publication. |
| Fallback | Small service with token auth | If the HTTPS option fails those tests (ETag, locking, ACLs) |

M4 accounts are least-privilege: read `packs/<user>/` + `releases/`, write `status/<user>/`, nothing else. An unguessable URL is not protection.

### 3.8 Client fetch
- **Off the dictation path:** pack reads and status writes run on a background worker. A slow or missing share never delays a dictation.
- **When:** on start; daily; "Refresh vocabulary" in the tray; plus an hourly manifest check (~200 bytes), so a fix lands within the hour.
- **How:** read `manifest.json`; if `packVersion` changed, read the file, check `sha256`, validate (§3.3), swap the cache atomically. M4 adds `If-None-Match` where the server gives an ETag.
- **Last-good cache:** `<appdata>/pack/current.json` + `previous.json`. Any failure keeps the current one. No pack yet (first run) → built-in minimal defaults and a visible "No vocabulary yet" status.
- **Credentials (M4):** per user, typed once, kept in the OS keychain. Never in this repo, an installer or an APK.

---

## 4. Context engine

### 4.1 Capture

| Level | Windows | macOS | Budget | Milestone |
|---|---|---|---|---|
| **Tier A: app identity** | `GetForegroundWindow` → PID → exe path | `NSWorkspace.frontmostApplication` bundle id (no permission) | **normal path ≤ 5 ms**; bounded retry: 2 re-samples, **≤ 100 ms total** | M1 Win / M5 mac |
| Window title (browser deny list, app rules) | `GetWindowText` | AX `AXTitle` (Accessibility permission) | ≤ 5 ms / ≤ 20 ms | M1 Win / M5 mac |
| **Tier C: password field** | UIA focused element `IsPassword` | `IsSecureEventInputEnabled()` + AX `AXSecureTextField` | cap 150 ms, then "timed out" | M1 Win / M5 mac |
| Focused field text / selection | UIA Value/TextPattern | AX `AXValue` / `AXSelectedText` | cap 300 ms | M6 |
| Browser URL | UIA (Chromium native UIA) | AX for Safari/Chrome | cap 100 ms | M6 |
| Calendar | not in the app: the publisher writes `today.json` (attendee names matching known terms) | same | 0 | M6 |

Limits [CP]: Telegram Desktop is Qt and custom-drawn (little UIA text); xterm.js terminals expose text only in screen-reader mode; Electron trees are off until assistive tech is detected. M6 items each ship only on held-out evidence that they help.

### 4.2 Mapping (per-app rules, M2)
Tier A identity → first matching `apps[]` rule (exe/bundle id, then `titleRe`) → app terms into the prompt (§3.4), app glossary + style into clean-up. No match → base terms, default style. The pack version and the rules selected are pinned to the recording.

Example rules: a **chat app** (people's names first; "Phoebe" may be a real person), an **AI coding tool** (bot and tool names, a glossary line), **Outlook/Teams** (work acronyms; style `business`), **browser** (base terms only).

### 4.3 Privacy: tiered

| Tier | What | Result |
|---|---|---|
| **A: app identity = the authorization signal** | Captured at the hotkey, re-sampled twice within ~100 ms | Resolved → continue. **Unresolved → audio kept locally, not uploaded.** A notice offers one-click **"Send anyway"**: it authorizes that recording's audio only, with no context. It never overrides a positive block, and never auto-pastes into an unresolved target. |
| **B: positive exclusions** | Exe/bundle on `blockApps` (password managers, banking apps, authenticators) | Recording refused |
| | Browser title matches `titleDenyRe` (bank names, "password", "sign in", "verification code") | Audio allowed (the user chose to speak); context app-only. URL checks come with URL capture in M6. Title matching has limits: a bank page with a generic title is missed. |
| | Password field detected | Recording refused, no paste |
| **C: password-field check inside non-blocked apps** | **Privacy mode, the user's choice** | **Strict (default):** check unknown, unavailable, unsupported or timed out → no upload; same "Send anyway" path. **Best-effort:** only a positive detection blocks. |

- **Context text always fails closed.** A timeout or error can only remove context, never add it.
- **Paste-time authorization.** The snapshot is bound to the recording, but it does not prove the insertion target is still safe. Before pasting, the target is re-checked; if it changed to a blocked, password or unresolved target, nothing is pasted and the text stays in the app with a notice.
- **Counters:** unknown, unavailable, unsupported and timed-out checks are counted apart from a successful "not a password field". They measure what strict mode costs in availability, not detection accuracy. Accuracy is tested on representative password fields per app (§9).
- **No automatic policy switch.** Tightening or loosening is a deliberate setting change.
- **Wording the user sees:** "Listed apps are blocked when identified. Checks inside other apps have these limitations: …" Browser banking pages are explained as a separate, title-based case.
- Work content is sent only as far as the user's settings allow; an app can be set `app-only`.
- **Logs:** metadata by default (app id, rule hit, terms sent, pack version, latencies, errors). An exact-payload view is opt-in, local, deleted after 24 h. A tray indicator shows when context text was attached.

---

## 5. Learning loop (M3; bounded, never auto-live)

| Step | What |
|---|---|
| 1. Mine (nightly, on the user's PC) | Raw transcripts in local history, **read in place**. Each candidate keeps provenance (recording id, raw text span). Signals: letter-by-letter spellings; near-misses of known terms (Mylo↔Milo); repeated unknown capitalised words. |
| 2. Rank + dedup | Count, recency, distinctiveness. Durable dedup store; **rejections remembered, never asked again**. |
| 3. Ask | ≤ 3 a day: "VoiceFlow heard *Versal* 3 times. Is that Vercel?" → correction / hint only / no. |
| 4. Write | A yes goes through the normal edit and publish path. Nothing new to approve or publish. |

- Wrong transcripts alone never establish a spelling.
- **Deferred** if candidates are noisy or the loop needs new infrastructure.
- Off for any user until that user opts in.

---

## 6. Status file (app → shared folder)

`status/<user>/<deviceId>.json`, written off the dictation path on start, after each pack read, hourly, and on any error. No transcript text. An assistant or a script can read it to tell a data problem (a retired model, a missing term: fixed in the pack) from an infrastructure problem (share unreachable) or a code problem (crash, paste broken, hotkey dead: a bug report).

| Field | Example |
|---|---|
| `app`, `version`, `os`, `deviceId` | VoiceFlow 1.0.0, win11 |
| `pack` | `{version, readAt, lastError, usingFallback}` |
| `last24h` | `{dictations, failedTranscribe, failedCleanup, cleanupTimeouts, refusedBlocked, heldUnresolved, sentAnyway}` |
| `passwordCheck` | `{mode, notPassword, detected, unknown, unavailable, unsupported, timedOut}` |
| `lastErrors[]` | `{at, stage, code, message}` (max 10) |
| `permissions`, `update` | mac Accessibility granted?; update available/pending |

**In the app, failures show at once**: the tray icon turns amber or red and a banner names the problem.

---

## 7. Build and release

### 7.1 Windows
Bun 1.2.19+, Rust stable, MSVC build tools, WebView2. NSIS `currentUser` installer (as upstream). Authenticode signing is optional for household use (SmartScreen warns once at install); Azure Trusted Signing later.

### 7.2 macOS
GitHub Actions `macos-latest` builds a universal binary (`aarch64` + `x86_64`, as upstream's workflow). M0 builds the untouched tag there, unsigned.

| Signing option | Install | Accessibility/Mic grants after updates | Cost |
|---|---|---|---|
| **Developer ID + notarization** | opens normally | kept (stable signature); verified through a real signed update in M5 | $99/yr |
| Self-signed certificate in the user's keychain | "Open Anyway" once | probably kept; untested | 0 |
| Ad-hoc signing | "Open Anyway" every update | lost every update | 0 |

### 7.3 Updates
- **M1–M3:** upstream updater disabled; releases installed by hand.
- **M4:** the fork's own signed updater. Own minisign key, private half in CI secrets and a password manager, never in the repo. Endpoint `https://<host>/releases/{{target}}-{{arch}}/latest.json`, with the user's credential as a header. A release script uploads installers + `.sig` + `latest.json` + the source tarball.
- Downloads in the background and installs at next restart with a notice; **never mid-dictation**.

### 7.4 Licence (AGPL-3.0, version 3 only, as upstream declares; the whole desktop fork is AGPL)

| Situation | Obligation |
|---|---|
| Personal use | None beyond keeping notices. |
| Giving builds to others (conveying) | Corresponding Source: a `source/<version>.tar.gz` next to each release satisfies §6. Keep upstream LICENSE and copyright, "modified, with date" notices (§5a), an About screen with the licence notice (§5d). |
| §13 network clause | Not triggered: no one uses it remotely over a network. |
| VoiceFlow Android (MIT) | Unaffected: the pack is data. Do not copy Whispering code into Android (it would pull Android under AGPL). |
| Pack publishers and assistants | Separate programs talking through files: unaffected. |
| Trademark | The rename and new icons remove the Whispering name and logo (M1). |

---

## 8. Milestones

| # | Scope | Done when |
|---|---|---|
| **M0** | Import the untouched v7.11.0 tree; build it on Windows locally and macOS in CI. | Both builds produce installers from the tag as is |
| **M1** | Usable Windows fork: rename + icons; telemetry out; upstream updater disabled; API key in keychain; gpt-transcribe; clean-up fallback, visible errors, Retry; Tier A identity + tiered privacy (§4.3); paste-time authorization. Then the workspace extraction commit (§2.2). | Clean install; packaged Windows build and macOS CI build both green after extraction; 10 real dictations pasted cleaned; no network call to upstream or Aptabase |
| **M2** | Pack loop over a shared folder: schema, validator, fixtures; publisher; prompt builder; approved corrections; **per-app rules**; pack version + rules pinned per recording; status file; acceptance tests | An edit is live within 1 h; bad packs rejected with last-good kept; a share outage never delays a dictation; A/B gate passed (§9) |
| **M3** | Bounded learning-suggestion loop (§5) | ≤ 3 asks a day with provenance; a "no" never returns; a yes goes live through the existing path |
| **M4** | Authenticated HTTPS transport after its tests (§3.7); Android replace-mode sync (`checkIfDue` pattern, `synced_replacements_json`, keyword cap, synced corrections off on typed text); the fork's own signed updater | ACL, cross-user denial, GET/PUT and publication tests pass; a pack change reaches phones and PCs; deleted entries never come back; one update installed through the updater |
| **M5** | Mac: signing, NSWorkspace/AX identity, a second user's isolated pack | A second user dictates into two apps with their own terms; permissions survive an actual signed update; their pack holds nothing from another user's sources |
| **M6** | Calendar overlay (`today.json`), field text, browser URL, clean-up `<reference>` slot | Each item ships only after held-out evidence that it helps; privacy tests pass; latency budgets held |

---

## 9. Acceptance tests and risks

### 9.1 Acceptance gate (each runs from the milestone that adds the feature)

| # | Test | Pass |
|---|---|---|
| 1 | A/B on 20 held-out clips (12 with target names, 8 without), pack prompt vs none | Names right at the 20/22 level [GT]; **zero invented names** in the 8; prompt never echoed on silence |
| 2 | Real mentions of Phoebe and of each correction's collision word | Phoebe untouched (glossary only); the others behave exactly as their approved scope says |
| 3 | Unapproved candidate in the source | Never exported, never applied |
| 4 | Corrections applied | Once, on the final output; no cascade; conflicting/chained/cyclic sets refused by the publisher |
| 5 | Client gets a truncated pack, bad sha, wrong user, schemaVersion 2, empty terms | Last-good kept; status shows the error; dictation works |
| 6 | Publisher asked to publish a 60% shrink without "shrink ok" | Refused; previous pack stays live |
| 7 | Share slow, down or (M4) 401 | Dictation latency unchanged; status amber; error named |
| 8 | App switch mid-dictation; two dictations overlapping | Each recording keeps its own snapshot, rules and pack version; paste-time check applies |
| 9 | Tier A unresolved (forced) | Audio not uploaded; "Send anyway" sends audio only, no context; no auto-paste into the unresolved target |
| 10 | Tier B blocked app; password field detected | Recording refused; no paste; nothing uploaded |
| 11 | Tier C check unknown/unavailable/timed out, strict vs best-effort | Strict: no upload + Send-anyway; best-effort: upload; counters split correctly |
| 12 | Representative password fields per app (browsers, chat apps, terminals, Outlook) | Detection result recorded per app; the user-facing limitations text matches it |
| 13 | Latency | Tier A normal path ≤ 5 ms; bounded retry ≤ 100 ms; Tier C ≤ 150 ms; M6 field text ≤ 300 ms; end-to-end median ≤ no-context + 0.5 s |
| 14 | Clean-up 404, 5xx, timeout; transcription 404 | Nothing pasted; error shown within 10 s with Retry tidy-up and Paste raw text / audio kept; Retry works after the default model is changed in the pack |
| 15 | M4 transport | One user's account can't read another user's pack or write their status; GET/PUT and publication work |
| 16 | A second user's pack | No term from another user's sources outside the agreed household list |

### 9.2 Risks

| Risk | Mitigation |
|---|---|
| Solo fork of abandoned code (Tauri, WebView2, macOS changes) | Small diff from the tag; pin toolchain; fix only what breaks |
| 7.11 may not build cleanly today (#2432 path, #2421) [A] | M0 builds the untouched tag first |
| Approved correction rewrites a real mention | Named collisions accepted up front; scoping; undo; tests 2 and 4 |
| Strict mode holds back too many recordings | Counters show the cost; the user switches mode deliberately |
| Bank page in a browser with a generic title | Stated limitation until M6 URL checks |
| Window titles leak sensitive text | `titleDenyRe`, title-only for unknown apps, redaction, opt-in payload log |
| Work content sent to a cloud provider | Only as far as the user's settings allow; `app-only` per app where an employer's policy requires |
| A sync endpoint reachable from the internet (M4) | Least-privilege accounts, cross-user denial tests, rate limiting |
| A Mac loses permissions on update | Stable signing identity; verified through a real signed update |
| OpenAI model churn (whisper-1 ends 2027-02-26) | Model is pack data, changed without a release |
| Licence obligations missed | Source tarball per release; About screen; checklist in the release script |
