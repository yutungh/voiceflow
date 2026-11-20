# VoiceFlow word lists (the pack)

The data contract VoiceFlow reads: the words it listens for, the spellings it always fixes, tidy-up styles, model defaults and privacy lists. VoiceFlow Desktop (`../desktop/`) reads it, and so does the Android keyboard (repository root) from 0.8.2, through the sync gateway (`../sync/`). It is data only, so sharing it never mixes the apps' licences.

Everything here is MIT (see [`LICENSE`](LICENSE)). Every word in the fixtures is invented.

| Path | What |
|---|---|
| [`schema/voiceflow-profile.v1.schema.json`](schema/voiceflow-profile.v1.schema.json) | One layer of a profile (JSON Schema), version 1 |
| [`schema/voiceflow-profile.v2.schema.json`](schema/voiceflow-profile.v2.schema.json) | Version 2: version 1 plus the shared style fields and pinned styles (see [Versions](#versions-readers-before-writers)) |
| [`schema/voiceflow-op.v1.schema.json`](schema/voiceflow-op.v1.schema.json) | One edit made in the app |
| [`schema/voiceflow-status.v1.schema.json`](schema/voiceflow-status.v1.schema.json) | The status file each device writes |
| [`schema/voiceflow-feedback.v1.schema.json`](schema/voiceflow-feedback.v1.schema.json) | What a model answers to feedback on a dictation ([`feedback/`](feedback/README.md)) |
| [`builtin/styles.json`](builtin/styles.json) | The built-in tidy-up styles both apps ship, one stable id each. Today: **Clean** (`clean`), which turns speech into the message the speaker meant to send, in their own voice |
| [`feedback/README.md`](feedback/README.md) | The feedback contract: what goes to the model, what must come back, how it is checked, and the edit each fix becomes |
| [`fixtures/demo/`](fixtures/demo/) | A complete example folder: household + one user (`sam`) with all three layers and some edits |
| [`vectors/merge.json`](vectors/merge.json) | Merge test vectors: inputs and the result every client must produce |
| [`vectors/wordlists.json`](vectors/wordlists.json) | More merge vectors (edit order, time offsets and ties, case and spacing, duplicates, undo chains, a fix that loses one heard form) and the vocabulary prompt and keywords built from merged words |
| [`vectors/styles.json`](vectors/styles.json) | Style vectors: version 2 fields, style and default edits, pins and per-app levels, compared in full |
| [`vectors/drift.json`](vectors/drift.json) | The language guard after tidy-up: what counts as drift, and the retry and fallback |
| [`vectors/feedback.json`](vectors/feedback.json) | Feedback answers and the fixes they become |
| [`../docs/desktop/CONNECT-YOUR-OWN-AI.md`](../docs/desktop/CONNECT-YOUR-OWN-AI.md) | How an assistant edits these files safely |

## The folder

Any folder works: a plain folder on your computer, a synced folder, or a network share (`\\NAS\voiceflow`, `/Volumes/voiceflow`). In VoiceFlow Desktop, choose it under **Settings > Word lists**.

```text
<folder>/
  profiles/household/profile.json             layer "household": shared house words
  profiles/<user>/profile.json                layer "personal": the user's own file
  profiles/<user>/generated/profile.json      layer "generated": written by a tool (e.g. from notes); optional
  profiles/<user>/ops/<device>-<uuid>.json    edits made in the app, one per file, never changed
  status/<user>/<device>.json                 written by each device (no dictated text)
```

`<user>` is a short lower-case name (`a-z`, `0-9`, `-`, `_`). Each device belongs to one user. A user's devices share that user's words and edits; users never see each other's personal files.

## Layers and precedence

```text
built-in defaults  <  household  <  generated  <  personal  <  app edits
```

A later layer wins:

- **Words, corrections and styles have stable ids.** An entry in a higher layer with the same id replaces the lower one entirely. Never reuse an id for something else.
- **Defaults** are taken field by field from the highest layer that sets them. A default that no layer sets is left to the device (its own settings keep working).
- **Privacy lists** are added together, and only ever add blocks to the app's built-in lists.
- **App rules** (`apps`): each entry needs an `id`, the app it is for (a lower-case Windows exe name such as `telegram.exe`, or a macOS bundle id); word and style references are checked. VoiceFlow Desktop uses `style` (a style id or `raw`) as the style a dictation into that app starts with on the recording bar; `terms` is reserved.

## Rules every client follows

- **A file is accepted whole or not at all.** It must parse, name the right `schema`, a `schemaVersion` the client knows (a higher one is refused), the `layer` that belongs where it sits, and the right `user` (`household` for the household file). Unknown fields, bad ids, duplicate ids, words with `<`, `>`, line breaks or other control and format characters, lists longer than 50 (heard forms, apps, title patterns), notes over 500 characters, and conflicting or chained corrections inside one file reject it. A layer file over 1 MB or an edit file over 64 KB is not read. A rejected or unreadable file leaves the **last good copy** of that layer in use; the problem is shown in the app and in the status file.
- **Never empty on a glitch.** If a layer file that was read before goes missing, the last good copy stays until a valid file comes back.
- **Hints are not corrections.** A word only helps recognition and tidy-up. Only a correction rewrites text: whole word, ignoring case and runs of spaces, applied **once to the final text**, never chained.
- **Checked again after merging.** Across layers:
  - one heard form written two ways in overlapping apps: the higher layer wins (for two app edits, the newer one), reported as `correction-conflict`;
  - a correction whose result another correction would change again (a chain or a loop): the lower one is set aside, `correction-chain`;
  - a word that a correction would rewrite (a real "Phoebus" next to Phoebus → Pip): both kept, `word-corrected`;
  - a default style or app-rule reference that doesn't exist: ignored, `missing-style` / `missing-word`;
  - the same word twice within one layer: one kept, `duplicate-word`;
  - a pinned style that doesn't exist: left out, `missing-style`;
  - a style made by edits without a label or a prompt yet: not offered, `style-incomplete`.
- **Snapshots.** The merged result has an id that changes when its content changes. A dictation pins the snapshot it started with; a refresh never changes a dictation in progress. VoiceFlow Desktop refreshes at start, every hour and on demand, and waits for a running dictation to finish.
- **Offline is normal.** The app keeps a copy of each layer and of every edit it has seen. When the folder can't be reached it uses those, and dictation works as usual.

## Edits made in the app (`voiceflow-op` v1)

"Add a word", "Fix a spelling" and Undo each write one small file. They are written on the device first, then copied to `profiles/<user>/ops/` under the same name. Copying is idempotent: the same file already there counts as done, and a different file under that name is never overwritten. Nothing is ever edited or deleted: Undo writes a **tombstone** that targets the edit, and undoing an Undo writes a tombstone for the tombstone. There is no compaction.

```json
{ "schema": "voiceflow-op", "version": 1,
  "id": "pc1a2b3c4d-0b6f9a2e-1111-4c1a-9d55-000000000001", "device": "pc1a2b3c4d",
  "createdAt": "2026-11-17T09:00:00.000Z",
  "type": "correction", "from": ["Flu Mocks"], "to": "Flumox", "scope": { "apps": ["*"] } }
```

| `type` | Fields | Meaning |
|---|---|---|
| `correction` | `from[]`, `to`, `scope.apps[]`, `note?` | Always write these heard forms this way |
| `term` | `term: {text, heardAs?, kind?, gloss?, priority?, apps?}` | A word to listen for (hint only) |
| `app-style` | `app`, `style?`, `expression?` (0–4) | For this app, use this style and/or expression level (an approved feedback fix). Sets the app rule's fields over the layers'; at least one of the two. App ids are native to each platform (`telegram.exe`, a macOS bundle id, an Android package name) |
| `style-set` | `style`, `set: {…}` | Some fields of one style: `label`, `prompt`, `model`, `models`, `expression`, `expressionOverride`, `icon`, `kind`, `guidance`, `hidden`, `note` (as in a v2 layer file). Field by field the newest edit wins, so an expression change on a phone never overwrites a prompt edit on the PC. `null` clears an optional field; `label` and `prompt` can't be cleared. A style no layer has is made by its edits once they give it a label and a prompt (until then: `style-incomplete`). Written only once every device reads it ([Versions](#versions-readers-before-writers)) |
| `default-set` | `set: {cleanupStyle?, pinnedStyles?}` | The default style (`raw` for none) and the pinned styles, over the layers; the newest edit wins each. `null` takes the edits' value away, so the layers' applies again. Same rule for writing as `style-set` |
| `tombstone` | `target` | Remove the edit with this id |

Edits are ordered by their time (`createdAt`, real time, offsets allowed), then by id; the same file arriving twice counts once.

A client that doesn't know a `type` skips that file. Edits are the user's own (the personal side); they never change the household file or anyone else's.

## A layer file (`voiceflow-profile` v1)

```json
{
  "schema": "voiceflow-profile", "schemaVersion": 1,
  "layer": "personal", "user": "sam",
  "updatedAt": "2026-11-17T08:30:00Z", "updatedBy": "sam",
  "terms": [
    { "id": "zorblax", "text": "Zorblax", "heardAs": ["Zork Blacks"], "kind": "project", "priority": 80 }
  ],
  "corrections": [
    { "id": "zorblax-zork", "from": ["Zork Blacks"], "to": "Zorblax" },
    { "id": "quibbit", "from": ["Kwibbit"], "to": "Quibbit", "scope": { "apps": ["teamchat.exe"] } }
  ],
  "styles": [
    { "id": "casual", "label": "Casual", "prompt": "Tidy up this dictation…", "model": "gpt-5.4-mini" }
  ],
  "defaults": { "language": "en", "promptChars": 600, "promptTerms": 40 },
  "privacy": { "blockApps": ["vault-demo.exe"], "titleDenyRe": ["demo bank"] }
}
```

| Field | Content |
|---|---|
| `terms[]` | `{id, text, heardAs[]?, kind?, gloss?, priority? (0–100, default 50), apps? (default ["*"]), note?}`. `kind`: person, bot, project, org, product, acronym, place, term. `gloss` ≤ 80 characters. |
| `corrections[]` | `{id, from[], to, scope? ({apps[]}, default every app), note?}` |
| `styles[]` | `{id, label (≤ 40), prompt, model?, expression? (0–4: the style's default level), note?}`. Version 2 adds `icon?` (≤ 16 characters, one line), `kind?` (`casual`, `business`, `family`, `partner`, `fun_*`, `custom`: which shared expression guidance it follows), `guidance?` (≤ 2,000: its voice in a sentence or two, shown as its description), `expressionOverride?` (≤ 2,000: added after the expression guidance, taking precedence), `models?` (`{openai, anthropic, google, groq, openrouter, xai, custom}`: a model per provider, before `model`) and `hidden?` (not offered in pickers; still works where a default or app rule names it) |
| `defaults` | `transcriptionModel`, `language`, `cleanupModel` (for styles that name none), `cleanupStyle` (a style id or `raw`), `cleanupTimeoutS`, `promptChars` (default 600), `promptTerms` (default 40). Version 2 adds `pinnedStyles` (≤ 12 style ids: the bar's pinned styles, in order, one list taken whole from the highest layer that sets it; ids that name no style are left out and reported) |
| `privacy` | `blockApps[]` (app ids never recorded in or pasted into), `titleDenyRe[]` (window-title patterns for sensitive pages: case-insensitive, Rust `regex` syntax, matched on the device only) |
| `apps[]` | `{id, style?, expression? (0–4), terms[]?}`: per-app rules. `id` is the app id; `style` is the starting style for dictations into that app, `expression` its starting level; `terms` is reserved |

## Versions: readers before writers

Every app refuses a whole file, and skips a whole edit, it can't read. So a new field or edit type is rolled out **readers first**: nothing writes it until every device that reads the folder can read it.

| What | Reads it | Writes it |
|---|---|---|
| `voiceflow-profile` v2 (the style fields marked v2 above, `defaults.pinnedStyles`) | VoiceFlow Desktop 0.9.13 | Nobody yet. A person or assistant may write a v2 file only once every device of every user who reads that file reports `reads.profile` containing 2 |
| `style-set`, `default-set` edits | VoiceFlow Desktop 0.9.13 | Nobody yet. The apps start writing them only once every device of the user reports both in `reads.op`; until then VoiceFlow Desktop keeps any it finds on the device to itself (`edit-held`) and never uploads it |
| `app-style` edits, `apps[].expression` | VoiceFlow Desktop 0.9.7 and later; not Android 0.8.2 (it refuses what it does not know) | VoiceFlow Desktop makes them (approved feedback fixes). From 0.9.13 such an edit works on the PC that made it and is uploaded only once every device of the user reports `app-style` in `reads.op`; edits already on the share from 0.9.7 to 0.9.12 stay there |

- A file that uses a v2 field must say `"schemaVersion": 2`; a v1 file with one is refused (an older app would refuse it too, so the mistake shows up on the newest device first). A file that needs none stays v1.
- Each device's status file says what it reads (`reads`, below). A status without `reads` (an app from before 0.9.13, or a phone) counts as reading profile v1 and the edit types `correction`, `term` and `tombstone` only. No status at all proves nothing, so the answer is "not yet".
- The Android app (0.8.x) doesn't read the word lists yet; when it does (plan phase 4) it reads v1 and v2 and every edit type here from the start.

App ids are lower-case: a Windows exe name (`telegram.exe`) or a macOS bundle id.

## The vocabulary prompt

Built per dictation from the pinned snapshot: words for the app being dictated into first, then words for every app by priority, within `promptChars` characters and `promptTerms` words (a word that doesn't fit is skipped and the next tried); most relevant last; one line, `Vocabulary: A, B, C`. No sentences, which a model can echo back on silence.

**The transcriber gets 20 of them** (VoiceFlow Desktop 0.9.15, Android 0.8.5). The transcription request (the line and its `keywords[]`) carries only the first 20 words chosen, in the same order (the app's own words first, then priority), with pronunciation hints fitted to that shorter line within `promptChars`. The tidy-up still gets every word chosen within `promptChars` and `promptTerms` (below). On real recordings, 50 or 80 words made gpt-transcribe drop words, swap an acronym for an everyday word and write listed words nobody said; the top 20 came out right every time, and the break came between 40 and 50. OpenAI calls keywords "hints, not required output" and advises keeping only terms that help.

- **20 is a code constant** (`TRANSCRIBE_TERMS` in `prompt.ts` and `WordPrompt.java`), with a hard cap of 40 (`TRANSCRIBE_TERMS_MAX`) whatever asks for more. It is not a profile field: Android 0.8.x refuses a profile file with a field it doesn't know, so adding one now would break the phones ([Versions](#versions-readers-before-writers)).
- **Plan:** a later profile schema version (v2 or after) may add `defaults.transcribeTerms` to override it, clamped to 1..40, written only once every device reads that version. Until then `promptTerms` sizes the tidy-up's list, and the request's size changes only with an app release.
- Shared cases: `split` in [`vectors/wordlists.json`](vectors/wordlists.json) (the 20-word request and the longer tidy-up list from the same words).

## The tidy-up's word list

Since VoiceFlow Desktop 0.9.14 and Android 0.8.4, every tidy-up style except the fun ones also gets the words, after its own instructions: "Words this speaker uses", one line per word with a note, then the rules for fixing a misheard word. The speech model can't map an unusual pronunciation onto a word (ZDRL, said "zee-drul", written "zee drill"); the tidy-up can, from the context, and leaves a real word that fits alone.

- **Words:** every word the vocabulary prompt chose for that dictation (within `promptChars` and `promptTerms`), most important first. Since 0.9.15 / 0.8.5 that is more than the 20 the transcriber gets.
- **Notes:** the gloss from any layer (with the kind when the gloss doesn't say it), then `speech-to-text wrote "X" or "Y"` from `heardAs`; at most 120 characters, whole forms only. The list keeps within 3,000 characters: words first, then notes on how a word sounds or was misheard, then the rest.
- **Language guard:** a listed word written in place of one of its heard-as forms is a spelling fix, not drift. Its credit is capped by what changed; a listed word the speaker never said still counts.
- **Saved spellings** still run after tidy-up, as before.

The exact header and rules, and the shared cases, are in [`vectors/tidywords.json`](vectors/tidywords.json); the guard's credits are in [`vectors/drift.json`](vectors/drift.json).

## Status file

After each refresh that reached the folder, a device writes `status/<user>/<device>.json`: app version, OS, what it reads (`reads: {profile: [1, 2], op: [...]}`, since 0.9.13), last sync, whether it used its copy, each layer's state, edits waiting to upload, issue codes and errors. It never holds dictated text or words from the lists. See [`fixtures/demo/status/sam/demo1.json`](fixtures/demo/status/sam/demo1.json).

## Permissions on a shared folder

Give each person read access to `profiles/household/`, read/write to their own `profiles/<user>/` and `status/<user>/`, and nothing on other users' folders. Whoever maintains the household list gets write access there. A generator that writes `generated/profile.json` needs write access only to that folder.
