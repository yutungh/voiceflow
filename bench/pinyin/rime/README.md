# Rime on the pinyin benchmark (phase 3)

Scores Rime (librime) on the same phrases and the same replay as the Java
engine, on a PC, to decide whether Rime is worth building into Android.
Measurement only: nothing here ships in the app.

## What is here

| File | What it is |
|---|---|
| `build.sh` | Builds librime 1.17.0 with the octagram and predict plugins (every commit pinned), the driver, and two variants: `dict` (Wanxiang dictionary only) and `gram` (plus Wanxiang's grammar model). Linux or WSL. |
| `vf_rime_driver.cc` | Speaks the benchmark's line protocol (`../README.md`), plus `time` (per-key latency inside Rime), `mem`, and `--cold` (start-up timing). |
| `run-driver.sh` | Starts the driver on one variant with a fresh, empty user directory. |
| `schemas/` | Our own slim schemas, no Lua: `vf_t9` (nine-key), `vf_qwerty` (full keyboard), `vf_pinyin.dict.yaml` (imports the Wanxiang tables), `gram.custom.yaml` (turns the grammar model on). |

Everything here is MIT, written for VoiceFlow. Wanxiang's `wanxiang_t9.schema.yaml`
was read as a reference for how a nine-key speller is set up; nothing is copied
from Wanxiang's Lua or tools, which carry no licence.

## Data (downloaded by `build.sh`, never committed)

- **Dictionary:** Wanxiang 万象 by amzxyz, https://github.com/amzxyz/rime-wanxiang
  at `907edf67`, `zi` + `jichu` + `duoyin`, CC BY 4.0, unchanged, SHA-256 checked.
  The same three files at the same commit feed the Java engine's 0.8.9 asset,
  but Rime gets all of them, with no 300k cut and no chat-word boost.
- **Grammar model:** `wanxiang-lts-zh-hans.gram` (390,786,092 bytes) from the
  LTS release of https://github.com/amzxyz/rime-wanxiang-data, CC BY 4.0,
  SHA-256 `e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5`.
  Its penalties are the ones Wanxiang's own nine-key schema uses, untuned.

## How a buffer reaches Rime

VoiceFlow's buffer goes to Rime unchanged: digits for keys, letters for a
pinned syllable, `'` between syllables. The nine-key speller keeps every
syllable's letters and adds a copy projected to key digits, so `xie'943` and
`943943` both work. The driver sets the whole buffer, reads the first 30
candidates of the segment that starts the input, and reports each one's
`end()` as `consumed`. Rime's C API has no candidate spans, so the driver uses
librime's C++ classes for that; an Android JNI would do the same.

## Running

```sh
bench/pinyin/rime/build.sh "$HOME/vfrime"          # about 5 minutes; --no-gram skips the 391 MB model
# then, from the repository (Windows: prefix the command with  wsl.exe -d Ubuntu --):
java -cp <classes> com.voiceflowkeyboard.ime.PinyinBenchmark external rime-dict \
  bench/pinyin/phrases.tsv <out dir> bash "$HOME/vfrime/run-driver.sh" dict
```

Latency: send `time<TAB>t9<TAB><keys>` lines to `run-driver.sh dict`; each
reply is the milliseconds per key. Start-up: `run-driver.sh dict --cold t9 943943`.
