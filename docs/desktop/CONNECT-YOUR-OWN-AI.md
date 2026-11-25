# Connect your own AI to VoiceFlow's word lists

VoiceFlow's word lists are plain JSON files in a folder you choose. Any assistant that can read and write files (a chat assistant with file access, a coding agent, a script) can manage them for you: add the names you use, fix spellings VoiceFlow keeps getting wrong, adjust a tidy-up style. Nothing here depends on a particular AI, service or server.

The format is in [`pack/README.md`](../../pack/README.md); the JSON Schemas are in [`pack/schema/`](../../pack/schema/).

> **Paths in this page.** Schema and fixture paths such as `pack/schema/...` and `pack/fixtures/demo/` are relative to the root of the VoiceFlow repository (`git clone https://github.com/yutungh/voiceflow`), not to your word-list folder. Give your assistant the schema file's contents if it cannot see the repository. This page is the short version for an assistant, with the rules that keep your lists safe.

## 1. Set up the folder

1. Make a folder, for example `Documents/VoiceFlow` (or a shared folder on a home server or NAS so all your devices see it). To keep it in Google Drive, shared with your household or team and refreshed by an AI every day, see [Keep your word lists in Google Drive](GOOGLE-DRIVE.md).
2. Create `profiles/<you>/profile.json`, where `<you>` is a short lower-case name:

   ```json
   {
     "schema": "voiceflow-profile",
     "schemaVersion": 1,
     "layer": "personal",
     "user": "sam",
     "terms": [],
     "corrections": []
   }
   ```

3. In VoiceFlow Desktop: **Settings > Word lists**, turn on **Use word lists**, enter the folder and your name, Save. **Vocabulary** now shows your words.
4. Optional: `profiles/household/profile.json` (`"layer": "household"`, `"user": "household"`) for words everyone at home shares.

[`pack/fixtures/demo/`](../../pack/fixtures/demo/) is a complete example folder you can copy.

## 2. Tell your assistant the rules

Paste this to your assistant, with the folder path:

> You manage my VoiceFlow word lists in `<folder>`. The format is `voiceflow-profile` v1 (schema: `pack/schema/voiceflow-profile.v1.schema.json` in the VoiceFlow repository). Rules:
>
> 1. **Edit only the file you own.** My file is `profiles/<me>/profile.json` (or, if you are my notes generator, only `profiles/<me>/generated/profile.json`). Never edit the household file, another person's folder, the `ops/` folder or the `status/` folder.
> 2. **Never rewrite another layer to win.** If a household word is wrong for me, add an entry with the same `id` to my file: the higher layer replaces it.
> 3. **Keep ids stable.** A new entry gets a new lower-case id (`a-z`, `0-9`, `.`, `_`, `-`). Never reuse an id for something different. To change an entry, keep its id.
> 4. **A word is a hint; a correction rewrites.** Add a term (`terms[]`) so VoiceFlow hears a name. Add a correction (`corrections[]`) only for a form that is always wrong, and say which apps (`scope.apps`, default every app) if the wrong form can be a real word elsewhere.
> 5. **No chains, no conflicts.** A correction's `to` must not be another correction's `from`, and one heard form gets one spelling per app.
> 6. **Words are one line,** at most 60 characters, without `<` or `>`. A `gloss` is at most 80 characters.
> 7. **Write the whole file at once,** then check it: valid JSON, matches the schema, `layer` and `user` unchanged. A file that fails is ignored by VoiceFlow (it keeps the last good copy), so a mistake never empties my lists, but nothing you changed takes effect until it passes.
> 8. **Tell me what you changed,** in one line per entry.
> 9. **Stay on version 1** unless every device that reads the file reports it reads version 2: each `status/<user>/<device>.json` must list 2 under `reads.profile`. Version 2 (`pack/schema/voiceflow-profile.v2.schema.json`) adds style fields (`icon`, `kind`, `guidance`, `expressionOverride`, `models`, `hidden`) and `defaults.pinnedStyles`; a file using any of them says `"schemaVersion": 2`, and an app that can't read it ignores the whole file.

## 3. Everyday requests

| You say | The assistant does |
|---|---|
| "Add Zorblax, it's my project." | Adds `{ "id": "zorblax", "text": "Zorblax", "kind": "project" }` to `terms`. |
| "VoiceFlow keeps writing Zork Blacks." | Adds `{ "id": "zorblax-zork-blacks", "from": ["Zork Blacks"], "to": "Zorblax" }` to `corrections`. |
| "Only in Team Chat, Kwibbit means Quibbit." | Adds a correction with `"scope": { "apps": ["teamchat.exe"] }`. |
| "Make my casual style shorter." | Edits the `prompt` of the style with id `casual` (same id), and shows you the before and after. |
| "Use a different model for tidy-up." | Sets `defaults.cleanupModel`. |
| "Never type into my password vault app." | Adds its app id to `privacy.blockApps`. |
| "Why did that word not apply?" | Reads `status/<you>/<device>.json`: it lists each layer's state, errors, and issue codes such as `correction-conflict`. |

## 4. What the app writes (read-only for assistants)

- **`profiles/<you>/ops/`**: every "Add a word", "Fix a spelling" and Undo made in VoiceFlow, one file each. They sit above your file in precedence. An assistant may **read** them (for example to fold a spelling you added in the app into your file), but must not change or delete them. If you fold one in, keep it identical (same `from`, `to` and scope) so nothing conflicts; VoiceFlow treats the duplicate as one.
- **`status/<you>/<device>.json`**: each device's last sync, errors and counts. No dictated text.

## 5. Checking a file before saving

Any JSON Schema validator works, for example:

```sh
npx ajv-cli validate --spec=draft2020 -s pack/schema/voiceflow-profile.v1.schema.json -d profiles/sam/profile.json
```

The schema can't see across entries, so also check by hand: unique ids per kind, no heard form listed twice with different spellings, no correction whose result is another's heard form. VoiceFlow checks all of this too and names the problem in **Vocabulary** and in the status file.

## 6. A generated layer (optional)

If you keep notes somewhere (a notes app, a CRM, a contacts list), a script or assistant can publish the names in them to `profiles/<you>/generated/profile.json` (`"layer": "generated"`) on a schedule. Your own file still wins over it, and edits made in the app win over both. Write it to a temporary file in the same folder and rename it over the old one, so VoiceFlow never reads half a file.
