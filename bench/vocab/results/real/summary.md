# Results: real

**Human recordings**: one speaker reading the script through VoiceFlow (set real). Results hold counts only, no transcripts.

## What it shows

One speaker (the author), 40 sentences, 3 runs, recorded through VoiceFlow 0.9.18 on 2026-11-25. Ten targets and five sound-alike pairs: enough to show a direction and concrete failures, not rates to quote to two digits. The three runs repeat the API calls on the same recordings.

### Speech step: 20 words is right; the break on this voice is between 50 and 80

- Without a list, gpt-transcribe got 9 of the 20 target mentions, and 3 of the 14 invented names.
- With 20, 40 or 50 listed words it got all 20 in every run.
- With 80 it got 18 [17–18]. Zoravyn and Kandrel were each lost in about half their mentions across the runs.
- No listed word that was never said appeared at any list size (0 in every run). The anecdote's "invented words" didn't happen here. The pilot's synthetic voices saw it rarely: Bepra written for Beeper, at 20 and 40 words.
- Beeper itself was misheard 2 times in 6 at 40 words and once at 50, not into the listed name.

### Tidy-up: the text step does turn right words into listed sound-alikes, and the heard-as hint causes it

- VoiceFlow's default tidy-up (gpt-5.4-mini) turned 3 of the 10 correctly heard sound-alikes into their listed name in every run: Docker → Dockra, Beeper → Bepra, Miro → Mirva.
- The same model and list without the "speech-to-text wrote …" notes did it 0 [0–1] times. The hint naming the everyday word is what drives the error.
- Claude Opus 5.5, given the same prompt and hints, did it 0 times, and still repaired all 10 constructed mishearings. gpt-5.4-mini repaired 8 [8–10] with hints and 7 without.
- There were no natural misses to fix. With 20 listed words, the transcript had every target right, so "fixes" has nothing to count on this voice.

### Hearing the recording (gpt-audio-1.5) did not help

- Without the recording it turned 5 [5–6] of 10 sound-alikes into the listed name. With it, 4 [4–6]. Both are worse than the text-only gpt-5.4-mini (3) and Opus (0).
- Given only the recording and the list, it broke 7 [6–9] of 10 sound-alikes. Asked to answer by voice, it often kept talking past the sentence: it invented whole clauses and dropped in other listed words (15 [11–15] never-said listed words, word error rate 68% [49–75]).
- It repaired 10 of 10 constructed cases with or without the recording. It leans on the list, not on what it hears.

### What the real voice changed from the synthetic pilot

- With no list, invented names fared worse on the real voice: 3 of 14, against 9 of 28.
- 40 and 50 words lost nothing on the real voice. The pilot lost one name mention there.
- The default tidy-up's error was steady on the real voice: 3 of 10 in every run, against 1 [0–3] of 9 in the pilot.
- The audio model's behaviour was the same in both.

### For the blog post

- **Supported:** a word list matters (names go from 3 of 14 to 14 of 14). 80 words is worse than 20. A text-only tidy-up can change a correctly heard word into a listed one that sounds like it, and here the cause is the heard-as hint that names the everyday word.
- **Contradicted:** that the break comes between 40 and 50 (here, 50 still scored perfectly). That a model which hears the recording fixes the problem: gpt-audio-1.5 did not, and without a transcript it made things up.
- **Left open:**
  - other audio-capable models;
  - other speakers;
  - lists whose hints don't name a common word;
  - whether a stronger text model (Opus 5.5 scored 0 wrong changes and 10 of 10 repairs here) is the better fix than audio, which this test suggests but can't settle with five pairs.

## Speech step: gpt-transcribe with 0 to 80 listed words

n = 40 sentences × 3 runs (real). Each cell: median of the runs [lowest–highest run].

| Words listed | Targets right | Names right | Products right | Sound-alikes kept right | Never-said listed words | Control sentences with one | Word error rate |
|---:|---|---|---|---|---|---|---|
| 0 | 9 [9–9] of 20 | 3 [3–3] of 14 | 6 [6–6] of 6 | 8 [8–9] of 10 | 0 [0–0] | 0 [0–0] of 10 | 5.6% [5.3–5.6] |
| 20 | 20 [20–20] of 20 | 14 [14–14] of 14 | 6 [6–6] of 6 | 10 [10–10] of 10 | 0 [0–0] | 0 [0–0] of 10 | 1.5% [1.5–1.5] |
| 40 | 20 [20–20] of 20 | 14 [14–14] of 14 | 6 [6–6] of 6 | 9 [9–10] of 10 | 0 [0–0] | 0 [0–0] of 10 | 1.5% [1.2–1.8] |
| 50 | 20 [20–20] of 20 | 14 [14–14] of 14 | 6 [6–6] of 6 | 10 [9–10] of 10 | 0 [0–0] | 0 [0–0] of 10 | 1.2% [1.2–1.5] |
| 80 | 18 [17–18] of 20 | 12 [11–12] of 14 | 6 [6–6] of 6 | 10 [9–10] of 10 | 0 [0–0] | 0 [0–0] of 10 | 2.4% [2.1–2.4] |

API failures (not scored): 0.

## Correction step: the same transcript, tidied with the 80-word list

Input: the 20-word speech answer (run 1) for each sentence, frozen before any result was seen. n = 40 sentences × 3 runs.
"Sound-alike → listed name" counts unlisted words that were right in the transcript and came out as the listed name they sound like (the Beeper case), out of the sound-alikes the transcript had right; "sound-alikes broken" counts every way they went wrong. "Targets broken" is the same for listed words. "Fixes" counts words that were wrong in the transcript and right after. For the direct-audio arm, which never sees the transcript, the same columns compare its answer with the transcript. An answer wrapped in JSON is scored on the text inside it; an empty answer (or a bare control token) scores as wrong; failed API calls are not scored. All three are counted in the last column.

| Arm | Targets right | Sound-alikes kept right | Sound-alike → listed name | Sound-alikes broken | Targets broken | Fixes | Never-said listed words | Word error rate | Wrapped / empty / failed answers (all runs) |
|---|---|---|---|---|---|---|---|---|---|
| No tidy-up (the transcript) | 20 [20–20] of 20 | 10 [10–10] of 10 | – | – | – | – | 0 [0–0] | 1.5% [1.5–1.5] | 0 / 0 / 0 |
| Text tidy-up, gpt-5.4-mini (VoiceFlow default) | 20 [20–20] of 20 | 7 [7–7] of 10 | 3 [3–3] of 10 | 3 [3–3] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 3 [3–3] | 3.2% [2.6–3.2] | 0 / 0 / 0 |
| Text tidy-up, gpt-5.4-mini, list without heard-as notes | 20 [20–20] of 20 | 10 [9–10] of 10 | 0 [0–1] of 10 | 0 [0–1] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 0 [0–1] | 1.8% [1.8–1.8] | 0 / 0 / 0 |
| Text tidy-up, Claude Opus 5.5 | 20 [20–20] of 20 | 10 [10–10] of 10 | 0 [0–0] of 10 | 0 [0–0] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 0 [0–0] | 1.2% [1.2–1.2] | 0 / 0 / 0 |
| Text tidy-up, gpt-audio-1.5, no recording | 20 [20–20] of 20 | 5 [4–5] of 10 | 5 [5–6] of 10 | 5 [5–6] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 5 [5–6] | 2.9% [2.6–3.2] | 0 / 0 / 0 |
| Tidy-up with the recording, gpt-audio-1.5, spoken answer | 20 [20–20] of 20 | 6 [4–6] of 10 | 4 [4–6] of 10 | 4 [4–6] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 4 [4–6] | 2.6% [2.4–2.9] | 0 / 0 / 0 |
| Recording + list, no transcript, gpt-audio-1.5, spoken answer | 20 [20–20] of 20 | 3 [1–4] of 10 | 5 [5–8] of 10 | 7 [6–9] of 10 | 0 [0–0] of 20 | 0 [0–0] of 0 | 15 [11–15] | 68.2% [48.5–75.3] | 0 / 0 / 0 |

Examples of wrong corrections (run 1):

- text-mini: s03: "Docker" -> the listed name "Dockra"
- text-mini: s10: "Beeper" -> the listed name "Bepra"
- text-mini: s14: "Miro" -> the listed name "Mirva"
- text-mini-nohint: s10: "Beeper" -> the listed name "Bepra"
- text-audio15: s03: "Docker" -> the listed name "Dockra"
- text-audio15: s10: "Beeper" -> the listed name "Bepra"
- text-audio15: s14: "Miro" -> the listed name "Mirva"
- text-audio15: s22: "Beeper" -> the listed name "Bepra"
- audio15-fix-spoken: s03: "Docker" -> the listed name "Dockra"
- audio15-fix-spoken: s10: "Beeper" -> the listed name "Bepra"
- audio15-fix-spoken: s14: "Miro" -> the listed name "Mirva"
- audio15-fix-spoken: s22: "Beeper" -> the listed name "Bepra"
- audio15-direct-spoken: s03: "Docker" -> the listed name "Dockra"
- audio15-direct-spoken: s07: "candle" -> the listed name "Kandrel"
- audio15-direct-spoken: s10: "Beeper" -> the listed name "Bepra"
- audio15-direct-spoken: s14: "Miro" -> the listed name "Mirva"

## Repair cases: the listed name, written as its sound-alike

Each recording says a listed name (e.g. Bepra), but the transcript given to the tidy-up says the everyday sound-alike instead ("Can you ask Beeper to send the slides…"). These errors are constructed, so they are reported apart from the real transcripts above.

| Arm | Repaired to the listed name | Left as the sound-alike |
|---|---|---|
| Text tidy-up, gpt-5.4-mini (VoiceFlow default) | 8 [8–10] of 10 | 2 [0–2] |
| Text tidy-up, gpt-5.4-mini, list without heard-as notes | 7 [7–7] of 10 | 3 [2–3] |
| Text tidy-up, Claude Opus 5.5 | 10 [10–10] of 10 | 0 [0–0] |
| Text tidy-up, gpt-audio-1.5, no recording | 10 [10–10] of 10 | 0 [0–0] |
| Tidy-up with the recording, gpt-audio-1.5, spoken answer | 10 [10–10] of 10 | 0 [0–0] |
| Recording + list, no transcript, gpt-audio-1.5, spoken answer (its own answers; it never sees the transcript) | 10 [10–10] of 10 | 0 [0–0] |

## Next to the pilot-synthetic results

This run: human recordings. pilot-synthetic: synthetic voices (OpenAI gpt-4o-mini-tts). Same script, lists, prompts and scoring; the pilot had two voices for the speech step and one for the tidy-up.

| Words listed | Targets right (this) | Targets right (pilot) | Never-said listed words (this) | (pilot) | Word error rate (this) | (pilot) |
|---:|---|---|---|---|---|---|
| 0 | 9 [9–9] of 20 | 21 [21–21] of 40 | 0 [0–0] | 0 [0–0] | 5.6% [5.3–5.6] | 2.9% [2.9–2.9] |
| 20 | 20 [20–20] of 20 | 40 [40–40] of 40 | 0 [0–0] | 0 [0–1] | 1.5% [1.5–1.5] | 0.1% [0.1–0.1] |
| 40 | 20 [20–20] of 20 | 39 [39–39] of 40 | 0 [0–0] | 1 [1–1] | 1.5% [1.2–1.8] | 0.3% [0.3–0.3] |
| 50 | 20 [20–20] of 20 | 39 [39–39] of 40 | 0 [0–0] | 0 [0–0] | 1.2% [1.2–1.5] | 0.1% [0.1–0.1] |
| 80 | 18 [17–18] of 20 | 36 [36–37] of 40 | 0 [0–0] | 0 [0–0] | 2.4% [2.1–2.4] | 0.6% [0.4–0.7] |

| Arm | Sound-alike → listed name (this) | (pilot) | Targets right (this) | (pilot) | Fixes (this) | (pilot) |
|---|---|---|---|---|---|---|
| No tidy-up (the transcript) | – | – | 20 [20–20] of 20 | 20 [20–20] of 20 | – | – |
| Text tidy-up, gpt-5.4-mini (VoiceFlow default) | 3 [3–3] of 10 | 1 [0–3] of 9 | 20 [20–20] of 20 | 20 [20–20] of 20 | 0 [0–0] of 0 | 0 [0–0] of 1 |
| Text tidy-up, gpt-5.4-mini, list without heard-as notes | 0 [0–1] of 10 | 0 [0–1] of 9 | 20 [20–20] of 20 | 20 [20–20] of 20 | 0 [0–0] of 0 | 0 [0–0] of 1 |
| Text tidy-up, Claude Opus 5.5 | 0 [0–0] of 10 | – | 20 [20–20] of 20 | – | 0 [0–0] of 0 | – |
| Text tidy-up, gpt-audio-1.5, no recording | 5 [5–6] of 10 | 4 [3–4] of 9 | 20 [20–20] of 20 | 20 [20–20] of 20 | 0 [0–0] of 0 | 0 [0–0] of 1 |
| Tidy-up with the recording, gpt-audio-1.5, spoken answer | 4 [4–6] of 10 | 4 [2–5] of 9 ¹ | 20 [20–20] of 20 | 20 [20–20] of 20 ¹ | 0 [0–0] of 0 | 0 [0–0] of 1 ¹ |
| Recording + list, no transcript, gpt-audio-1.5, spoken answer | 5 [5–8] of 10 | 5 [4–6] of 9 ¹ | 20 [20–20] of 20 | 18 [17–19] of 20 ¹ | 0 [0–0] of 0 | 0 [0–0] of 1 ¹ |

¹ The pilot ran this arm answering in text, not by voice.

