# VoiceFlow sounds

`generate.mjs` makes every sound the app plays: three sets (Glass, Soft, Minimal) of five cues (start, stop, done, error, cancel). Pure synthesis, no samples; deterministic, so the same script always writes the same bytes.

```sh
node desktop/tools/sounds/generate.mjs            # rewrites the app's WAVs
node desktop/tools/sounds/generate.mjs <outDir>   # writes <outDir>/<set>/<cue>.wav
```

| Rule | Value |
|---|---|
| Format | 48 kHz, mono, 16-bit PCM WAV |
| Length | 80-300 ms, including the reverb tail; faded in and out |
| Loudness | -20 LUFS momentary (K-weighted energy over one 400 ms block); sample peaks at or below -3 dBFS |
| Licence | The generated sounds: CC0 1.0. This script: AGPL-3.0, like the app |

The app's copies live in `apps/whispering/src/lib/services/isomorphic/sound/assets/<set>/<cue>.wav`; Rust embeds the same files (`src-tauri/src/sound.rs`). A unit test (`src/lib/constants/sounds/sound-sets.test.ts`) fails if the committed files differ from what this script makes, so after changing it, run it and commit the WAVs.
