# VoiceFlow repository layout

This repository holds the whole VoiceFlow family: the Android keyboard, the desktop dictation app, and the vocabulary pack they share.

| Path | What | Licence |
|---|---|---|
| `/` (Gradle project, `app/`, `scripts/`, `Research/`) | VoiceFlow Android keyboard. It stays at the root for now. | MIT (`LICENSE`) |
| `desktop/` | VoiceFlow Desktop for Windows and macOS, a fork of Whispering 7.11. | AGPL-3.0 (version 3 only, as upstream declares) |
| `pack/` | The shared vocabulary pack: schema and synthetic test vectors | MIT (`pack/LICENSE`) |
| `docs/desktop/` | Desktop spec, research notes and design work | Documentation |

## Licence boundaries

- Each directory's licence covers only that directory.
- No Whispering (AGPL) code may be copied into the Android app or into `pack/`. The apps share data through the pack, never code.

## README

`README.md` still describes only the Android keyboard. It will be updated for this layout once the in-progress Android work is committed.
