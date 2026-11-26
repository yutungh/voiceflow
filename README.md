# VoiceFlow

Voice dictation with your own AI key. Speak, and VoiceFlow turns it into clean text in whatever app you are using, with your own names and terms spelled right. You choose the provider (OpenAI, Grok / xAI, Deepgram, Claude for cleanup, or fully offline models) and pay it directly; there is no VoiceFlow account and no VoiceFlow server in the way.

VoiceFlow has three parts that work alone or together: a **desktop app** (press a hotkey, speak, text appears at the cursor), an **Android keyboard** (a native input method with a microphone button), and an optional **sync server** that shares your word lists between devices.

> **No prebuilt downloads yet.** There are no installers or APKs to download. You build each app from source, which this page and each part's own docs walk through. Prebuilt builds may be published on the [Releases page](https://github.com/yutungh/voiceflow/releases) later.

> Not affiliated with Typeless, Voiceflow, Apple, Google, Anthropic, or OpenAI. "Typeless-style" is used only to describe the product category: voice-first typing with AI cleanup.

## The parts

| Part | What it is | Licence | Status | Docs |
|---|---|---|---|---|
| **Android keyboard** (repository root, `app/`) | Native Android keyboard with voice dictation, AI cleanup, offline models and an emoji picker | MIT | Working prototype, built from source | [This page](#android-keyboard), [`PRIVACY.md`](PRIVACY.md) |
| **Desktop app** (`desktop/`) | Hotkey dictation for Windows and macOS. A fork of [Whispering](https://github.com/EpicenterHQ/epicenter) | AGPL-3.0 | In development, built from source | [`desktop/README.md`](desktop/README.md), [`desktop/CHANGELOG.md`](desktop/CHANGELOG.md) |
| **Word-list pack** (`pack/`) | The data format both apps read: words, spelling fixes, styles, privacy lists. Data only | MIT | Stable format, versioned | [`pack/README.md`](pack/README.md), [Connect your own AI](docs/desktop/CONNECT-YOUR-OWN-AI.md), [Google Drive](docs/desktop/GOOGLE-DRIVE.md), [GitHub](docs/GITHUB-SYNC.md) |
| **Sync server** (`sync/`) | Small self-hosted HTTP service that gives each phone its word lists, with a token per device. Without a server, a [private GitHub repository](docs/GITHUB-SYNC.md) can keep the word lists in sync instead | MIT | Optional, self-hosted | [`sync/README.md`](sync/README.md) |

Each folder's licence covers only that folder. Do not copy code from the AGPL desktop app into the MIT parts.

## Get the source

```sh
git clone -c core.longpaths=true https://github.com/yutungh/voiceflow
```

On Windows, keep `-c core.longpaths=true`. Some paths in the repository are long, and without it the clone or the desktop build can fail on Windows' 260-character path limit.

Then follow the build steps for the part you want: the [Android keyboard](#build) below or the [desktop app](desktop/README.md).

# Android keyboard

Open-source Android voice keyboard for people who want a Typeless-style mobile dictation workflow, AI dictation cleanup, and bring-your-own-key voice-to-text on any Android phone.

Record a voice note from the keyboard, transcribe it, optionally clean it with an LLM prompt, and insert the final text into any app. It is a native Android IME, so it works anywhere a normal keyboard works: chat apps, email, notes, search, docs, forms, and coding tools.
## Screenshots

![VoiceFlow Keyboard letters layout](docs/keyboard-letters.png)

![VoiceFlow Keyboard symbols layout](docs/keyboard-symbols.png)

![VoiceFlow Keyboard settings](docs/settings-v046.png)

![VoiceFlow Keyboard voice model picker](docs/voice-picker-v046.png)

![VoiceFlow Keyboard Offline Parakeet model details](docs/parakeet-v046.png)

## Try It

There is no published APK yet. To try it now, build the debug APK from source (see [Build](#build)) and install it on your Android phone (see [Install On A Phone](#install-on-a-phone)).

Prebuilt APKs may be published on the [GitHub Releases page](https://github.com/yutungh/voiceflow/releases) later. If they are, they will be debug-signed prototype builds for sideloading and testing, built by GitHub Actions from this public repository, with no `.env.local` and no bundled API keys. For production use, build and sign your own release APK.

## Why This Exists

Most mobile voice typing tools either insert raw dictation immediately or live inside one app. This project is a base for an Android keyboard that can:

- capture a full recording instead of live-inserting partial text,
- transcribe the recording,
- rewrite or lightly clean the transcript with a configurable prompt,
- insert the final result into the active text field,
- let users bring their own API key and model choices.

## Features

- Native Android input method service.
- Apple-inspired key layout and spacing.
- Permanent microphone button above the keys.
- Whole-clip recording: record first, transcribe after stop, then insert.
- OpenAI audio transcription via `/v1/audio/transcriptions`.
- Grok/xAI speech-to-text and transform support.
- Claude/Anthropic transform support.
- Deepgram speech-to-text support.
- Optional local offline transcription with compact Vosk or high-accuracy Parakeet via sherpa-onnx.
- Automatic offline transcription fallback when a cloud voice provider is selected but the phone has no validated internet connection.
- Settings control to download the compact offline fallback model before you need it.
- Optional transcript cleanup via configurable cloud transform providers.
- Editable API keys, providers, transcription models, transform models, and voice styles.
- Combined provider/model pickers for voice input and text transform setup.
- Voice styles: Friends, Work, and Family by default, an optional Partner template, editable custom styles, optional emoji icons, and a collapsed Fun group with Haiku, Pirate, Shakespearean, Noir Detective, and Wizard personas.
- A mutually exclusive style picker replaces the disabled keys while dictation or translation is recording.
- A five-step expression control (`Reserved` through `Expressive`) adjusts conversational energy, punctuation, and permitted emoji use independently for each voice style.
- Voice styles apply to both transcript cleanup and natural target-language localization; voice instruction mode remains independent.
- Friends and Work automatically use bullets or numbering when the transcript is clearly a list, steps, tasks, instructions, options, or grouped items.
- Translation history remembers the target language and generated style variants for comparison or reuse.
- Retone can regenerate the last inserted dictation or translation from its original transcript, safely replace it when the field is unchanged, and keep alternate style/expression versions together in history.
- Low-latency GPT-5 transform settings with safe fallback retry.
- Non-destructive English spelling and completion suggestions; spelling candidates are never applied unless the user taps one.
- Personal vocabulary supports multiple likely mishearings, an exact output, and optional context for names, nicknames, jargon, and commands.
- GPT Transcribe receives personal exact spellings as keyword hints, while a provider-independent local correction guarantees configured output such as `N P M run pre flight` -> `npm run preflight`.
- Built-in phrase replacement example: `Cloud Code` -> `Claude Code`.
- Smart spacing for voice inserts.
- Every new input target and keyboard window opens on the layout its field type calls for (letters for text, the number pad for number and phone fields, symbols for dates and times), even when Android reuses the same keyboard view across app switches. A layer chosen by hand survives the same field restarting, as some code fields do after every digit, but never carries into a different field. A default-on safety setting cancels and discards active recordings when the keyboard closes or the input app changes.
- Phone-style number pad with the extra keys each field needs (`+ * #` for phone numbers, `.` and `-` where a number field allows them, none for a PIN), an ABC key to leave it, and a `1234` key on the symbols layer to open it by hand for code or card fields an app did not mark as numeric.
- Paste chip: text copied in the last two minutes is offered as a one-tap Paste, typed as-is, which also works in apps that block their own paste menu. Password fields always offer copied text for password managers; nothing is offered on the lock screen. The history panel always has a Paste clipboard button.
- Holding delete removes characters, then whole words after 3 seconds, then everything before the cursor after 6 seconds.
- Backspace straight after a phrase replacement restores what you typed.
- Active cellular and VoIP calls are detected before recording so Android's microphone restriction is explained instead of silently producing an empty transcript.
- Compact recording and retone statuses use adaptive sizing and marquee overflow so the full state remains readable.
- Short voice outputs under 5 words do not get a forced trailing period.
- Haptics for keyboard taps.
- Symbols and expanded symbols views.
- Emoji key immediately left of the space bar, available in letters, Chinese, and symbols layouts.
- Offline emoji picker with nine categories, recent picks, English keyword search, and long-press skin-tone choices. Search has its own typing keys and never inserts the query into the active app.

## Emoji Keyboard

Tap **☺** beside the space bar to browse emojis. Tap a category to filter the grid, or tap **Search emojis** and type a name or keyword such as `happy`, `birthday`, or `dog`. Matching emojis appear above the search keys; **Done** expands the results. Hold an emoji to choose an available skin tone. **ABC** returns to typing, and Back first closes search, then the picker.

The bundled Unicode 16.0 catalog works offline and uses the phone's emoji font, so the appearance and support for newer emojis depend on Android/the installed font. Recent picks stay on the device; password fields and editors that disable personalized learning do not add to recents. Backspace removes complete catalog emoji sequences, including families, flags, and skin tones, once the picker has loaded.

GIF browsing is not included in this version. A future integration can use [GIPHY's search and trending API](https://developers.giphy.com/docs/api/) with a developer key and the required attribution. Android's [image keyboard API](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard) can insert GIF content into compatible apps; unsupported editors would need an explicit link fallback. [Tenor discontinued its third-party API on June 30, 2026](https://support.google.com/tenor/answer/10455265?hl=en).

To regenerate the emoji asset from the pinned sources:

```text
python scripts/build-emoji-catalog.py
```

## Current Status

This is a working prototype and a base for other developers, not a finished consumer keyboard.

Known tradeoffs:

- API keys are stored locally in app preferences. For production, migrate to encrypted storage.
- Audio is sent to the configured transcription provider when using OpenAI transcription.
- Realtime streaming transcription is not implemented yet. Cloud transcription records the full clip and uploads after stop.
- Offline Vosk transcription downloads a compact local model on first use and runs the transcription on-device after recording stops.
- Offline Parakeet downloads a much larger local model, about 600 MB, on first use. It is intended as the higher-accuracy offline English option.
- Offline fallback uses an installed Parakeet model first, then an installed Vosk model. Use Settings > Voice input > Offline fallback to prepare the compact Vosk fallback before you need it.
- English spelling suggestions are context-free and much simpler than Gboard or Apple Keyboard, so they are never applied automatically.
- The UI is tuned for a modern Samsung/Android phone but is not exhaustively tested across devices.

## Build

Requirements:

- JDK 17 (CI uses Temurin 17; newer JDKs may work but are untested).
- Android SDK with **platform 35** (`compileSdk 35`, `targetSdk 35`, `minSdk 26`) and **build-tools 35.0.0**. Android Studio installs both; or use `sdkmanager "platforms;android-35" "build-tools;35.0.0"`.
- Tell Gradle where the SDK is, either with a `local.properties` file in the repository root:

  ```text
  sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
  ```

  (on macOS/Linux: `sdk.dir=/home/you/Android/Sdk`) or with the `ANDROID_HOME` environment variable. Android Studio writes `local.properties` for you. It is git-ignored.
- Gradle itself comes from the wrapper (`gradlew`, Gradle 8.10.2); the first build downloads it.

Run the unit tests and build the debug APK:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

On macOS/Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

**Release builds.** `assembleRelease` signs with `~/.android/debug.keystore` (the standard Android debug key, created by Android Studio or the first debug build) unless you point it at your own keystore with `-PvfKeystore=<path>` or the `VF_KEYSTORE` environment variable. Because that fallback is the public debug key, treat such a build as a test build and use your own keystore for anything you distribute. The release build keeps only the arm64 native libraries.
## Install On A Phone

Enable Developer Options and USB Debugging on your Android phone. On Samsung devices, you may also need to disable Auto Blocker for sideloading.

Install the debug APK you built from source:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then:

1. Open **VoiceFlow Keyboard**.
2. Grant microphone permission.
3. Add your provider API keys if using cloud transcription or cleanup.
4. Choose your voice input model and transform model.
5. Open Android keyboard settings and enable **VoiceFlow Keyboard**.
6. Choose it from the keyboard picker.

## Local Install With Your Own Keys

For a build on your own phone, you can keep API keys in an ignored `.env.local` file and seed them into the installed app preferences after install:

```powershell
.\gradlew.bat assembleDebug
.\scripts\install-private.ps1 -Serial YOUR_DEVICE_SERIAL
```

Supported local key names:

```text
OpenAIAPIKey=...
AnthropicAPIKey=...
XAIAPIKey=...
DeepgramAPIKey=...
```

This script does not compile keys into the APK. It installs the local debug APK, then writes the keys directly into the connected device's private app preferences with `adb run-as`.

## Recommended Model Setup

The default OpenAI flow is:

- transcription: `gpt-transcribe`
- transform: a GPT-5 model

The transform request uses low-latency options for GPT-5-style cleanup tasks:

- `reasoning.effort: none`
- `text.verbosity: low`
- prompt caching key/retention
- fallback retry without optional latency fields if a selected model rejects them

Provider support:

- OpenAI: transcription and transform.
- Grok / xAI: transcription and transform.
- Claude / Anthropic: transform only.
- Deepgram: transcription only.
- Offline Parakeet: transcription only, high-accuracy English transcription through sherpa-onnx, with a large local model downloaded on first use.
- Offline Vosk: transcription only, compact local fallback model downloaded on first use.
- Offline fallback: when OpenAI, Grok / xAI, or Deepgram is selected for voice input and the phone has no validated internet connection, VoiceFlow records with installed Offline Parakeet first, then installed Offline Vosk.

## Voice Styles

The default public styles are **Friends**, **Work**, and **Family**.

**Friends** lightly cleans raw speech-to-text while preserving wording, tone, intent, hedging, slang, and order. **Work** rewrites the transcript into concise, send-ready professional text while preserving meaning, certainty, boundaries, and factual content. **Family** uses familiar, supportive everyday language that is warmer than Friends but less intimate than Partner, without inventing affection or softening serious content.

The optional **Partner** template adds recipient-aware intimacy without inventing nicknames, facts, or emotions. Partner can permit a fitting affectionate emoji when the message itself supports it.

The keyboard places five novelty personas in a collapsed **Fun** group at the far right of the horizontally scrollable style row. **Haiku** returns only a three-line poem; **Pirate**, **Shakespearean**, **Noir Detective**, and **Wizard** restate the message in an unmistakable persona while preserving facts and avoiding invented story details.

While normal dictation or translation is recording, the disabled keyboard is covered by a single-select voice-style panel. The selected style can be changed before stopping and is applied in the same transform request, so it does not add another processing step. Voice instruction mode is excluded.

Built-in styles can format content as bullets or numbered steps when that structure naturally fits. For example, grocery lists, task lists, instructions, recipes, options, and step-by-step workflows do not need a separate Bullets mode.

You can add editable custom styles. Each style has:

- editable display name,
- editable dictation behavior,
- editable translation tone guidance,
- the same one-tap recording flow as the built-in profiles.

This makes the project useful as a base for:

- personal dictation cleanup,
- professional message drafting,
- task and instruction formatting,
- domain-specific terminology cleanup.

## Privacy Notes

This keyboard can read what you type and can record microphone audio while active, because that is how Android keyboards and dictation tools work.

Read [PRIVACY.md](PRIVACY.md) before using or modifying this app.

Current privacy model:

- The app does not include a bundled API key.
- Users bring their own provider API keys.
- API keys are saved locally on the device in app preferences.
- When a cloud transcription provider is enabled, recorded audio is sent to that provider after recording stops.
- With compatible OpenAI transcription models, configured personal vocabulary spellings and context are sent with the recording as transcription hints.
- A phone enrolled with a vf-sync gateway ([`sync/`](sync/README.md)) also downloads its user's word lists (household, generated and personal layers, plus edits made in the apps), keeps an offline copy in app-private storage, and merges them with the phone's own list by the rules in `pack/`. They are used for dictation only: the vocabulary line and keywords sent with the recording, and the corrections applied once to the dictated text. Typed-text corrections keep using the phone's own list. Nothing is uploaded in this release.
- A phone whose word lists source is a private GitHub repository (Word lists > Shared lists; [docs/GITHUB-SYNC.md](docs/GITHUB-SYNC.md)) downloads the same files from `api.github.com` with its own read-only access token, kept encrypted with an Android Keystore key, and the same offline-copy rules apply. It writes nothing to the repository. A gateway it is enrolled with keeps delivering app updates.
- When transcript cleanup is enabled, transcript text is sent to the configured transform provider.
- Offline Vosk and Offline Parakeet transcription keep audio local after the selected local model has been downloaded.

For a production release, you should:

- use encrypted preferences or Android Keystore for API keys,
- disable voice/network features in password, OTP, payment, and other sensitive fields,
- publish a clear privacy policy,
- avoid collecting logs that contain dictated text,
- consider a provider abstraction so users can choose local or cloud transcription.

## Roadmap Ideas

- Optional voice-style-specific vocabulary scopes.
- Encrypted API key storage.
- Undo last voice insert.
- Per-field safety rules.
- Better tablet/foldable layouts.
- More local transcription model options.
- Prebuilt, signed release builds.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md), and [AGENTS.md](AGENTS.md) for the privacy rules that apply to every change.

## License

The Android keyboard, `pack/` and `sync/` are MIT ([LICENSE](LICENSE), [pack/LICENSE](pack/LICENSE)). The desktop app in `desktop/` is AGPL-3.0 ([desktop/LICENSE](desktop/LICENSE)). Each folder's licence covers only that folder.
