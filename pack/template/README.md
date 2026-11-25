# Word-list template

A starting set of word lists: copy it into a new private GitHub repository ([how](../../docs/GITHUB-SYNC.md#1-make-the-repository)) or a folder ([how](../../docs/desktop/CONNECT-YOUR-OWN-AI.md#1-set-up-the-folder)). Every word in it is invented: replace them with yours.

| File | What it is |
|---|---|
| `profiles/household/profile.json` | Words everyone shares (layer `household`). Optional: delete it if you dictate alone. |
| `profiles/sam/profile.json` | One person's own words (layer `personal`). Rename the folder **and** the `"user"` field to your own short lower-case name. |

VoiceFlow adds `profiles/<name>/ops/` (words added in the app) and `status/<name>/` (each device's status) itself. The format is in [`../README.md`](../README.md); keep the files at `"schemaVersion": 1` so every device can read them.
