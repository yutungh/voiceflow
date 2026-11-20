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

- JDK 17+
- Android SDK or Android Studio

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

- Run `./gradlew assembleDebug`.
- Do not commit API keys, keystores, APKs, local SDK folders, or generated build output.
- Keep privacy implications explicit in the PR description.
- Include screenshots for keyboard layout changes when possible.

## Security And Privacy

Do not add telemetry, analytics, or server calls without clear documentation and an opt-in path.

Do not hardcode API keys. Users should provide their own keys.

## Maintainer Privacy Guard

Maintainers keep a private term list outside version control and run a local pre-push hook that checks every outgoing commit against it.

- `scripts/privacy-check.mjs` scans the added lines, new file names and messages of the commits being pushed, and fails any author, committer or tagger email that is not a GitHub no-reply address. `--tree` scans every tracked file instead.
- `sh scripts/install-privacy-hook.sh` installs it as `.git/hooks/pre-push`. The hook is local and is not committed.
- The term list defaults to `private-docs/privacy-terms.txt`, which must stay out of git. The script's header describes its format.
