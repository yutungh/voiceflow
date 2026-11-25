# Rime for the Android keyboard (0.9.0)

The keyboard's Chinese engine is Rime (librime) with the Wanxiang dictionary,
with the pure-Java engine ("Classic") kept as the fallback. Nothing built here
is committed: the app builds without it (Classic only, as CI does), and a
release build refuses to run until it exists.

| File | What it is |
|---|---|
| `build.sh` | Builds `libvfrime.so` (arm64-v8a) from pinned sources with NDK r28c, compiles the Rime data on the PC, records and checks the replay fixture, and writes `.rime-dist/`, which `app/build.gradle` picks up. Linux or WSL. |
| `replay-buffers.tsv` | The buffers of the replay check (nine-key phrases, prefixes, pinned syllables, apostrophes; some full-keyboard). |
| `device-gates.ps1` | The device gates on a plugged-in phone over adb (cold start, replay, per-key latency, memory, soak, 16 KB pages). |

## What goes into `libvfrime.so`

- librime 1.17.0 with the octagram and predict plugins merged, at the same
  commits as the PC benchmark (`bench/pinyin/rime/build.sh`), built static with
  logging off.
- Its dependencies, static, at the commits in `build.sh`: yaml-cpp 0.8.0,
  leveldb 1.23, OpenCC 1.1.9 with its bundled marisa-trie 0.2.6, Boost 1.84
  headers.
- VoiceFlow's own bridge (`app/src/main/cpp`, MIT; no Trime or fcitx5-android
  code): `vf_rime_core.cc` (shared with the PC replay tool) and
  `vf_rime_jni.cc`.
- The C++ runtime is linked statically (`c++_static`) and a version script
  exports only `JNI_OnLoad`, so nothing clashes with the other native
  libraries in the APK (vosk, sherpa-onnx, onnxruntime), which carry their own
  runtimes. `build.sh` checks the exports, that no shared C++ runtime is
  needed, and that every LOAD segment is aligned for 16 KB pages.

## The data

The schemas in `bench/pinyin/rime/schemas` (nine-key and full keyboard) over
Wanxiang's `zi`, `jichu` and `duoyin` tables (CC BY 4.0) plus the chat table,
compiled by `rime_deployer` on the PC: 54 MB, about 24 MB compressed in the
APK. On first use the app copies it into its own storage, checks every file
against `manifest.txt`, and reuses it afterwards (`RimeData.java`). What Rime
learns from her typing (its user dictionary) is kept apart and cleared by
Settings > Keyboard > Forget my typing.

## Running

```sh
scripts/rime-android/build.sh            # in WSL from the repo; first run about 15 minutes
./gradlew assembleRelease                # picks up .rime-dist/
```
