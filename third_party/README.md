# Vendored third-party data

Everything the app ships that we did not write lives here, pinned to an exact
upstream revision and hashed, together with the licence text its terms require
us to retain. Nothing under `app/src/main/assets/` is hand-written — each asset
is generated from a source in this directory by a script in `scripts/`.

VoiceFlow Keyboard is MIT-licensed. That constrains what may be vendored: only
permissive sources, never GPL/AGPL (an APK is one conveyed binary — there is no
dynamic-linking escape hatch on Android), and never ShareAlike *data* even where
the surrounding code is permissive.

Verify a pin with:

```sh
sha256sum third_party/<dir>/<file>
```

---

## rime-wanxiang — Chinese pinyin dictionary (0.8.9)

| | |
|---|---|
| Upstream | https://github.com/amzxyz/rime-wanxiang (万象拼音, by amzxyz) |
| Pinned at | `907edf67ce6d1f5c73b9f65ce9a2de55f8d7c82d` (2026-11-21, version.txt 18.1.14) |
| Licence | CC BY 4.0 (`rime-wanxiang/LICENSE`, retained) |
| Files | `dicts/zi.dict.yaml`, `dicts/jichu.dict.yaml`, `dicts/duoyin.dict.yaml` |
| SHA-256 | in `scripts/wanxiang-source.mjs` (`WANXIANG_FILES`) |
| Consumed by | `scripts/build-pinyin-dict.mjs` (through `scripts/wanxiang-source.mjs`) |
| Ships as | `app/src/main/assets/pinyin_dict.txt` (344,410 keys, 9.55 MB uncompressed) |

The dictionaries are **not committed**: `jichu` alone is 51 MB. The build
script fetches them from the pinned commit into `rime-wanxiang/dicts/`
(gitignored) and refuses them unless the SHA-256 matches. The generated asset
is committed, so building the app never needs the fetch.

### Licence, verified 2026-11-21

- The repository's `LICENSE` is CC BY 4.0, the GitHub API reports `CC-BY-4.0`,
  and the README carries the CC BY 4.0 badge. No ShareAlike, no NonCommercial:
  adaptation and commercial redistribution are allowed with attribution.
- The sister data repository `amzxyz/rime-wanxiang-data` states the scope in
  `LICENSE-SCOPE.md`: the project's own dictionaries and pinyin data are CC BY
  4.0, but its **program code is not licensed** ("当前未授予…许可"). So no
  Wanxiang tool or script is used here; the conversion in `scripts/` is our own.
- Not used: Wanxiang's grammar model (`.gram`). `LICENSE-SCOPE.md` does not
  name it, so it is not bundled until the author confirms its licence.

### Attribution and changes (CC BY 4.0 §3(a))

Shown in the app (Settings, "Open-source credits", from
`app/src/main/assets/licenses/CREDITS.txt`) and recorded here:

- Creator: amzxyz; work: 万象拼音 Wanxiang, https://github.com/amzxyz/rime-wanxiang,
  revision above; licence: https://creativecommons.org/licenses/by/4.0/.
- Changes: the three dictionaries are merged (highest weight per word and
  spelling); tone marks removed and ü written v; characters with weight < 2
  dropped; words kept are the top 300,000 by weight plus "chat-shaped" phrases
  (ending in a particle or 了, or opening with a personal pronoun) down to
  weight 50; 90 everyday chat words (first-party list in the build script)
  have their weight multiplied by 20.

---

## librime and its dependencies — the Rime engine (0.9.0)

Built from source into `libvfrime.so` by `scripts/rime-android/build.sh`;
nothing is vendored here and nothing built is committed. Every revision is
pinned (commit or SHA-256) in that script, and each build writes them to
`.rime-dist/rime-build.txt`.

| Component | Pinned at | Licence |
|---|---|---|
| librime 1.17.0 | `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | BSD-3-Clause |
| librime-octagram | `57d18b9f58e5284bd891d559f6bdd16cf60341e9` | BSD-3-Clause |
| librime-predict | `920bd41ebf6f9bf6855d14fbe80212e54e749791` | BSD-3-Clause |
| Boost 1.84.0 (headers) | tarball SHA-256 `a5800f40…3045724` | BSL-1.0 |
| LevelDB 1.23 | `99b3c03b3284f5886f9ef9a4ef703d57373e61be` | BSD-3-Clause |
| yaml-cpp 0.8.0 | `f7320141120f720aecc4c32be25586e7da9eb978` | MIT |
| OpenCC 1.1.9 | `556ed22496d650bd0b13b6c163be9814637970ae` | Apache-2.0 |
| marisa-trie 0.2.6, darts-clone 0.32, RapidJSON 1.1.0 (bundled in OpenCC) | as in OpenCC above | BSD-2-Clause (marisa's BSD option), BSD-2-Clause, MIT |
| Android NDK r28c | zip SHA-256 `dfb20d39…5aa552f` | build tool only |

All permissive; no GPL or LGPL code is linked (marisa-trie is dual BSD/LGPL
and is used under BSD-2). The JNI bridge is VoiceFlow's own
(`app/src/main/cpp`, MIT); no code from Trime (GPL-3) or fcitx5-android
(LGPL) is used. The licence texts ship in the app
(`app/src/main/assets/licenses/Rime-NOTICES.txt`, shown under Open-source
credits).

The Rime data is the Wanxiang `zi`, `jichu` and `duoyin` tables above,
unchanged, compiled by `rime_deployer` into Rime's binary format, plus a
first-party table (`scripts/build-rime-chat.mjs`) listing the 90 chat words of
`scripts/chat-words.mjs` with their Wanxiang readings at 20 times their
Wanxiang weight. Attribution and changes are on the credits screen.
Wanxiang's grammar model (`.gram`) is not used.

---
## rime-pinyin-simp — retired in 0.8.9

The Apache-2.0 `rime/rime-pinyin-simp` dictionary (@`0c6861ef`, derived from
AOSP PinyinIME) was the pinyin source up to 0.8.8. 0.8.9 replaced it with
Wanxiang above and no longer ships any of it, so its copy was removed from
this directory; it remains in the git history.

---

## symspell-en-frequency — English word list with frequencies

The unigram prior for English autocorrect. A correction engine without frequency
data cannot rank candidates, and a word list alone cannot supply that ranking.

| | |
|---|---|
| Upstream | https://github.com/wolfgarbe/SymSpell |
| File | `SymSpell/frequency_dictionary_en_82_765.txt` |
| Pinned at | `6b8efd7d5053b68013e0193357a79011aa648ce2` (2021-06-29, the last commit to touch this file) |
| SHA-256 | `c604e1121e398ae7c7fbf777f11e0a0f2fa66eda932cb9fba1321466cf3acd7b` |
| Size | 1,332,881 bytes / 82,834 entries |
| Format | `word<space><count>`, one per line |

### Licence chain — verified against primary sources 2026-08-13

This artifact is a *derived work of two upstream datasets*, so the MIT licence on
the SymSpell repository is **not** by itself sufficient authority to redistribute
it. All three licences below were read directly, and the obligations are the
union of them.

1. **Google Books Ngram** — the frequency counts.
   CC BY 3.0 Unported, per Google's own dataset page: *"This compilation is
   licensed under a Creative Commons Attribution 3.0 Unported License."*
   **No ShareAlike, no NonCommercial.** Commercial redistribution and derivative
   works are permitted with attribution.
   https://storage.googleapis.com/books/ngrams/books/datasetsv3.html

2. **SCOWL / ESDB** — the vocabulary the counts were filtered against.
   Retained verbatim as `LICENSE.SCOWL.txt` (sha256
   `71bffd4b74ad47fff01c8b3c666e77da92737854e568850d8728024e0a53f304`).
   Kevin Atkinson grants permission to *"use, copy, modify, distribute, and sell
   any part of the English Speller Database (ESDB ...), **or word lists created
   from it**"* provided the copyright and permission notice appear in all copies
   and in supporting documentation. That clause is what covers this derived list.
   No component carries a copyleft licence: the sources are 12dicts and ENABLE2K
   (public domain), WordNet (BSD-style), UKACD, and Ispell/VarCon (BSD).
   COCA 3-gram data was used under Atkinson's own NDA, but the grant above
   explicitly extends to word lists created from the database.
   The notice itself states that for an official non-Australian-English speller
   dictionary, the block before its `===` separator is sufficient.

3. **SymSpell** — the intersection work itself.
   MIT, © 2018 Wolf Garbe. Retained as `LICENSE.SymSpell.txt`.
   The README describes the derivation: Google Books Ngram ∩ SCOWL, keeping
   *"only those words which appear in both lists"*, truncated to ~80k entries.
   Note the README gives no explicit licence statement for the bundled data
   files as distinct from the code — which is exactly why items 1 and 2 are
   tracked here independently rather than assumed to be covered by the MIT file.

**Attribution the app must ship** (open-source licences screen): Google Books
Ngram (CC BY 3.0), SCOWL/ESDB © Kevin Atkinson with the notice above, and
SymSpell (MIT).

### Known defects in the upstream data

The 2021 pin appended 67 entries by hand *after* the SCOWL intersection had
already run, so these 67 alone are unvetted. They are identifiable because they
all carry a flat count of `300000` — a value that also breaks the file's
otherwise descending sort. All 67 were reviewed individually:

- 65 are ordinary contractions (`don't`, `we're`, `o'clock`, …) plus `covid` and
  `hi`. Legitimate.
- **`you'v` is a typo.** The correct `you've` is absent from the file entirely,
  so the defect both adds a non-word and loses a real one. The generator must
  drop `you'v` and add `you've`.

Two further consequences of that flat count, for whoever tunes ranking:

- All 67 share one frequency, so they cannot be ranked against each other. The
  value sits far above the file's tail (~12,800), so they are treated as roughly
  as common as the 12,000th word — plausible for contractions, arbitrary for
  `covid` and `hi`.
- The `i`-contractions are stored lowercase (`i'm`, `i'd`, `i'll`, `i've`).
  Correcting `im` must yield `I'm`, not `i'm`, so capitalisation of this group
  cannot be left to the generic case-matching path.

Confirmed absent from the lexicon, and therefore correctly left to the explicit
typo table rather than dictionary lookup: `im`, `teh`, `dont`.

### First-party supplement — 63 words that are NOT from this source

The shipped asset is not purely upstream. `scripts/build-english-dict.mjs` adds
63 words the pinned corpus does not contain, listed in its `SUPPLEMENT` array.

This is a defect fix, not an enrichment. The source is Google Books intersected
with SCOWL, so it knows `okay` but not `ok`, and nothing about texting or
software. On a phone keyboard that is not merely a gap: before this, the
corrector treated them as misspellings and silently rewrote `omg` to `org` and
`thx` to `the`.

**These carry no frequency claim.** Every one is emitted at the lowest count
present in the pinned corpus — 12,714, derived from the data at build time
rather than written down, so it cannot drift if the source is ever repinned.
That is the weakest prior the format can express: enough to be recognised as a
real word, not enough to outrank a word whose count was actually measured. We do
not know how common `tbh` is and the asset does not pretend to. Note this is
recognition, not inertness: a supplement word can still win as a correction when
it is the only credible candidate one edit away, which is the desired behaviour
for `istagram → instagram`.

Every entry was checked to be genuinely protected at that weight — no
adjacent-key substitution rival clears the corrector's real-word override gap.
Two candidates failed that check and are deliberately excluded: **`np`** (loses
to `no`, gap 11,208) and **`vlog`** (loses to `blog`, gap 9,342). Defending them
would have meant inventing a higher weight, which is the exact claim this floor
convention exists to avoid. Also excluded, for different reasons: single letters
such as `u` and `r`, which the service discards before the corrector sees them,
and `im`/`dont`, which must keep expanding to `I'm` and `don't` through
`COMMON_TYPOS`.

`EnglishCorrectorTest.supplementaryVocabularyIsNeverRewritten` pins the
protection invariant, so a future change to the override thresholds cannot
silently re-break them.
