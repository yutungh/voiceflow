# Nine-key pinyin benchmark

A fixed test of how well an engine turns typed pinyin into the Chinese she
meant, on the nine-key pad (九宫格) above all, with full-keyboard pinyin as a
control. Built in 0.8.9 to decide engine changes on numbers, and to score Rime
on the same ruler later.

## The phrases

`phrases.tsv`: 373 everyday chat chunks of 2-8 characters, self-written for
this repository (MIT). No private data and no copied text.

- **Grouped by conversation** (greetings, meeting up, food, work, ...). The
  dev/test split is by group: **183 dev** phrases may be used for tuning; **190
  test** phrases are held out and were scored only after every setting was
  frozen.
- **Tags:** `weak` (the three 0.8.8 weak spots: 谢谢, 明天见, 可以吗), `clash`
  (syllables that share keys: xie/zhe 943, jian/jiao 5426, ma/na 62), `name`,
  `colloq`, `poly` (a polyphone in the phrase).
- **Gold readings** are one toneless syllable per character, written by hand
  and checked by `node scripts/pinyin-bench/check-phrases.mjs` against the
  readings the Wanxiang character table lists (it catches typos, not a valid
  but wrong polyphone reading).
- Digits and letters are derived from the readings by the harness, with no
  syllable boundaries given to the engine.
- Still to come: phrases from real users' own typing, only with their consent.

## What is measured

Each phrase is typed in full, as digits (谢谢 = `943943`) and as letters
(`xiexie`), and the engine's first 30 candidates are scored:

| Metric | Meaning |
|---|---|
| top1 | The whole phrase is the first candidate. |
| top5 | The whole phrase is among the first five. |
| charAccuracy | 1 − (edit distance between the first candidate and the phrase) / length. |
| recall30 | The whole phrase is anywhere in the 30. |
| corrections | Extra actions to get the phrase committed, beyond one tap or space (below). |
| failed | Phrases it could not commit at all, even with pins (charged 2 actions a character). |

**Corrections** are the cheapest way she could get the phrase committed.
Committing a candidate that is a correct leading part of what is left costs 1
action, +1 if it is not the first candidate, +1 per page of 6 scrolled; what it
leaves keeps composing, exactly as `PinyinSession` does. On nine-key, pinning
the next syllable's spelling costs 1. The harness searches every such sequence
(a shortest path over committed characters and pinned syllables), so an engine
is never charged more for offering an extra correct candidate. Only the first
30 candidates of any engine count. Corrections = actions − 1, so a
first-candidate hit costs 0. Reported per phrase and per 100 characters.

Latency is per key, through `PinyinSession` (candidates, the spelling shown and
the strip's options), over the test phrases, after one warm-up round. A second
figure covers buffers with spellings pinned (the slower, constrained path).

Paired comparisons give the change in top1 and in corrections with a 95%
bootstrap interval over phrases (2,000 resamples).

## Engine-agnostic

`PinyinBenchmark.Engine` is all the harness needs: a buffer in (digits,
letters, apostrophes), ranked `(text, consumed)` pairs out. `JavaEngine` wraps
this app's composer. `ExternalEngine` drives any other engine as a process over
a line protocol, which is how Rime will be scored in phase 3:

- request, one line: `<mode>\t<buffer>` with mode `t9` or `qwerty`;
- reply, one line: `<text>\t<consumed>\t<text>\t<consumed>...`, best first, empty when nothing.

`consumed` counts characters of the buffer the candidate reads, apostrophes
included; the rest stays composing.

## Running it

```sh
# Full run, writes <out>/shipped.json and shipped.tsv:
./gradlew testDebugUnitTest --tests '*PinyinBenchmarkTest' -PvfBench=<out dir>

# Faster, without Gradle (compile the Pinyin*.java sources and PinyinBenchmark.java):
java -cp <classes> com.voiceflowkeyboard.ime.PinyinBenchmark bench <name> <asset> bench/pinyin/phrases.tsv <out dir> latency
java -cp <classes> com.voiceflowkeyboard.ime.PinyinBenchmark compare <a.tsv> <b.tsv>
java -cp <classes> com.voiceflowkeyboard.ime.PinyinBenchmark misses <asset> bench/pinyin/phrases.tsv t9 dev
java -cp <classes> com.voiceflowkeyboard.ime.PinyinBenchmark tune <asset> bench/pinyin/phrases.tsv   # dev only
```

## Results (`results/`)

`0.8.8.*` is the 0.8.8 engine and data (AOSP-derived rime-pinyin-simp, 39k keys).
`0.8.9.*` is Wanxiang data with the calibrated scoring. JVM timings are from a
desktop; a phone is several times slower.

| Nine-key | 0.8.8 test | 0.8.9 test | 0.8.8 dev | 0.8.9 dev |
|---|---:|---:|---:|---:|
| top1 | 52.6% | **66.3%** | 54.1% | 76.0% |
| top5 | 75.8% | 79.5% | 87.4% | 93.4% |
| char accuracy | 73.7% | 79.1% | 78.1% | 88.5% |
| corrections / phrase | 1.263 | **0.974** | 0.863 | 0.410 |
| corrections / 100 chars | 34.7 | 26.8 | 23.3 | 11.1 |
| failed | 4 | 3 | 0 | 0 |

Paired, held-out nine-key: top1 **+13.7 points [+7.4, +20.5]**; corrections
per phrase **−0.29 [−0.55, −0.05]**. Full-keyboard pinyin, held out: top1
75.3% → 82.6% (+7.4 [+2.1, +12.6]), corrections 0.679 → 0.474 (−0.21 [−0.38, −0.06]).

Weak spots on nine-key: 明天见 and 可以吗 are now first; 谢谢 is still second
behind 这些 (one tap). The 34 test phrases containing a word on the build
script's chat list were also left out as a check on that list: on the other
156, nine-key top1 is 48.1% → 61.5% and corrections 1.429 → 1.115.

Latency (JVM, per key, p95): nine-key 0.38 ms, full keyboard 0.19 ms, pinned
0.36 ms (0.8.8: 0.73 / 0.43 / 0.19). Asset load 72 ms and nine-key index 407 ms
on the JVM, off the UI thread; retained heap about 33 MB (0.8.8: 7 MB).

## Tuning (0.8.9)

Done on the dev split only, by coordinate descent over the knobs in
`PinyinComposer.Scoring` and the build script, then chosen from a plateau
rather than the single best point:

| Knob | Value | Effect on dev nine-key |
|---|---|---|
| Words kept | top 300,000 + chat-shaped tail ≥ 50 | 100k → 300k: top1 +5; 500k gave +1 more for +5 MB |
| Weight exponent / extra segment cost | 0.8 / +2.0 | +1 top1 (a dearer word boundary) |
| Final-particle bonus (吗吧呢呀啦嘛呗咯) | 2.0 | +2-4 top1; fixes 可以吗 |
| Rare-syllable penalty (vowel-less n, m, ng, ...) | 1.0 | +0.5 top1 |
| Beam / entries per segment | 12 / 6 | corrections 0.53 → 0.46 (scored then with a longest-first replay) |
| Whole-word bonus | 0 | no measurable gain |
| Chat-word boost (90 words) | x20 | +1 top1; x50 was not clearly better |

Word-pair (bigram) data was considered and not added: Wanxiang's `.gram`
grammar model has no explicit licence for that asset yet, and book-register
2-grams (Google Books, CC BY 3.0) cannot help the case that is left (谢谢 with
nothing before it). The final-particle bonus is the context score that
measurably helped.

## Phase 3: Rime on the same ruler

Rime (librime 1.17.0 with octagram, every commit pinned) with our own slim
schemas over the same Wanxiang tables, scored on a PC through `ExternalEngine`
(`PinyinBenchmark external`). How it is built and run: `rime/README.md`.
`rime-dict` is the dictionary only; `rime-gram` adds Wanxiang's 391 MB grammar
model. Rime got no dictionary tuning; three grammar settings were tried on dev
only and Wanxiang's own was kept. Each run starts with an empty user
dictionary. A clean build from `rime/build.sh` reproduces the rows exactly.

| Held-out (190) | 0.8.8 | 0.8.9 | rime-dict | rime-gram |
|---|---:|---:|---:|---:|
| Nine-key top1 | 52.6% | 66.3% | 74.7% | **75.8%** |
| Nine-key top5 | 75.8% | 79.5% | 80.5% | 81.6% |
| Nine-key char accuracy | 73.7% | 79.1% | 86.3% | 87.1% |
| Nine-key corrections / phrase | 1.263 | 0.974 | 0.542 | **0.495** |
| Nine-key failed | 4 | 3 | 0 | 0 |
| Full pinyin top1 | 75.3% | 82.6% | 91.6% | **93.7%** |
| Full pinyin corrections / phrase | 0.679 | 0.474 | 0.168 | **0.121** |

| Dev (183) | 0.8.8 | 0.8.9 | rime-dict | rime-gram |
|---|---:|---:|---:|---:|
| Nine-key top1 | 54.1% | 76.0% | 74.9% | **79.8%** |
| Nine-key corrections / phrase | 0.863 | 0.410 | 0.464 | **0.339** |
| Full pinyin top1 | 80.9% | 92.9% | 92.9% | **95.1%** |
| Full pinyin corrections / phrase | 0.410 | 0.153 | 0.142 | **0.093** |

Paired, held-out nine-key: 0.8.9 to rime-dict top1 **+8.4 points [+3.7, +13.2]**,
corrections **-0.43 [-0.72, -0.16]** (-44%). Without the 34 phrases holding a
word on the Java chat list: +10.3 [+5.1, +16.0], -0.54 [-0.89, -0.22]. On dev,
where 0.8.9 was tuned and Rime was not, they are level (-1.1 [-5.5, +3.8]).
rime-dict to rime-gram: +1.1 [-1.1, +3.7] and -0.05 [-0.11, +0.01] (-9%)
held out; +4.9 and -27% on dev; +2.1 and -28% on held-out full pinyin.

Weak spots (nine-key): 明天见 first everywhere; 谢谢 still second behind 这些 in
all three; 可以吗 is first in 0.8.9 (its final-particle bonus) but second
behind 可以拿 in Rime. Rime offers one sentence candidate, so a whole phrase it
misses is usually not in its list at all (its top5 equals its recall); it
makes up for it with cheap word-by-word commits and never fails.

On the PC (`results/rime-host.json`): nine-key per key p95 0.56 ms (Java
0.37 ms), full keyboard 0.12 ms (0.18 ms); first candidates 25 ms from a cold
start, 30 ms with the gram. Memory is almost all memory-mapped data: 60 MB
resident with the dictionary, 270 MB with the gram, about 1 MB of heap.
Compiled dictionary 54.3 MB (24 MB deflated); librime itself about 3.6 MB.

**Gate verdict (plan, 2026-11-21): Rime wins.** Held-out nine-key is +8.4
points (threshold 5) and -44% corrections (threshold 20%; the bootstrap
interval of the relative cut is 21-61%). The top1 interval, +3.7 to +13.2,
shows a gain but does not by itself prove 5 points. **The gram has not earned
its 391 MB:** +1.1 points and -9% corrections on held-out nine-key, against 3
points or 15%. Full pinyin is the control and does not decide it, and the dev
gain came from the setting chosen on dev. More nine-key data could reopen it.

Limits, from a read-only Codex review: this compares two systems (Rime gets
all of Wanxiang, Java a 300k cut with a chat boost), not two algorithms; the
corrections are the cheapest possible path, not real typing, deletes or
learning; desktop timings leave out Android, JNI and the UI.
