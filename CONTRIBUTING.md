# Contributing

Thanks for helping improve VoiceFlow Keyboard.

This project is intended to be a practical open-source base for Android voice dictation keyboards. Keep changes understandable, privacy-conscious, and useful for people who want to build or test the app themselves.

## Good First Contributions

- Device compatibility reports.
- Setup documentation fixes.
- Safer API-key storage.
- Better custom dictionary and replacement UI.
- Realtime transcription experiments.
- Keyboard layout fixes for different screen sizes.
- Accessibility improvements.

## Development Setup

Requirements:

- JDK 17
- Android SDK with platform 35 and build-tools 35.0.0 (Android Studio installs both), found through `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME` environment variable. See the Build section of the [README](README.md#build).
- Windows: clone with `git clone -c core.longpaths=true https://github.com/yutungh/voiceflow`.
- Desktop app: see [desktop/README.md](desktop/README.md). Sync server: see [sync/README.md](sync/README.md).

Build:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

Install on a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pull Requests

Before opening a pull request:

- Run `./gradlew testDebugUnitTest assembleDebug` (and `bun test` in `desktop/apps/whispering` or `npm test` in `sync` if you touched those parts).
- Do not commit API keys, keystores, APKs, local SDK folders, or generated build output.
- Keep privacy implications explicit in the PR description.
- Include screenshots for keyboard layout changes when possible.

## Security And Privacy

Do not add telemetry, analytics, or server calls without clear documentation and an opt-in path.

Do not hardcode API keys. Users should provide their own keys.

## Maintainer Privacy Guard (optional, maintainers only)

**Contributors can skip this section. Do not install the hook.** It needs a private term list that only the maintainer holds, and it blocks every push without one. Pull requests are checked by the public CI workflows instead.

The maintainer keeps a private term list outside version control and runs a local pre-push hook that checks every outgoing commit against it.

- `scripts/privacy-check.mjs` scans the added lines, new file names and messages of the commits being pushed, and fails any author, committer or tagger email that is not a GitHub no-reply address. `--tree` scans every tracked file instead.
- `sh scripts/install-privacy-hook.sh` installs it as `.git/hooks/pre-push`. The hook is local and is not committed.
- The term list defaults to `private-docs/privacy-terms.txt`, which must stay out of git. It is looked for in the working tree being pushed, then in the main checkout, so a linked worktree (`git worktree add`) needs no copy. With no list found, the push is refused; there is no bypass. The script's header describes its format.
- `node --test scripts/privacy-check.test.mjs` tests the guard itself with a made-up term list in a throwaway repository.
