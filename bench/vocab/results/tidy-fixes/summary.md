# Results: tidy-up fixes

**Human recordings**: one speaker (set real), the same frozen 20-word transcript, 80-word list and scoring as results/real. n = 40 sentences × 3 runs, plus 10 made-up mishearings × 3 runs. Counts only, no transcripts. Each cell: median of the runs [lowest–highest].

## What it shows

**Recommended default:** keep gpt-5.4-mini and ship both fixes: the reworded instruction, and dropping heard-as forms that are everyday English words. Offer Claude Opus 5.5 at effort low as the accuracy option.

The reasoning is below. Codex reviewed it independently and reached the same call.

### The options, weighed

- **Today's default** (gpt-5.4-mini, current prompt) turned 3 [2–4] of 10 correctly heard words into their listed sound-alike: Beeper and Docker. It takes 0.60 s median.
- **Neither fix alone is enough on the cheap model:**
  - The reworded instruction (wording A, picked on the synthetic pilot) left it at 3 [1–3]; it swapped Docker for Miro.
  - Dropping everyday-word hints brought it to 2 [1–3].
  - Both together brought it to **1 [1–1]**, at the same speed and cost. The one left is Miro → Mirva: "Miro" is a proper name, so the dictionary rule keeps that hint.
- **The fixes cost repairs.** Made-up mishearings repaired fell from 8 [8–9] to 6 [4–7]. That loss is built in: the repair cases are exactly the everyday words whose hints the rule removes. On this speaker it costs little in practice, because with 20 listed words the speech step got every listed name right, so there were no real mishearings to repair. A wrong change corrupts text that was right, silently; a missed repair only leaves the transcriber's own error.
- **Opus 5.5 at effort low**, with the current prompt and hints, made no wrong changes and repaired 10 of 10.
  - Cost: about $0.016 a call, against $0.0005 for mini, which is roughly $95 a month at 200 dictations a day.
  - Speed: +0.18 s at the median (0.78 s) and +0.9 s at p90 (1.67 s).
  - That suits an opt-in "accuracy" choice, not the default.
- **Sonnet 5.5 at effort low** is slower than Opus here (1.29 s median, 1.79 s p90) and still made 1 [1–2] wrong change.
  - With both fixes it made none, but repaired only 2 [2–3]: too cautious.
- **GPT-5.4 (full)** was worse than mini: 6 of 10, every run (Beeper, Docker and Miro), at 0.77 s.

### Limits

- One speaker, five sound-alike pairs said twice, three runs of the same recordings.
- The hints were seeded to name the everyday word, the hardest case.
- Latency was measured from this PC, with 4 calls in flight, on 2026-11-25.
- The Claude models ran at effort low with max_tokens 1024 (as VoiceFlow sends them). Every Claude call ended normally; the longest answer used 293 output tokens.

## On his voice

| Arm | Sound-alike → listed name | Sound-alikes broken (any way) | Targets broken | Made-up mishearings repaired | Never-said listed words | Latency median / p90 (s) | Cost per call |
|---|---|---|---|---|---|---|---|
| gpt-5.4-mini, VoiceFlow's prompt | 3 [2–4] of 10 | 3 [2–4] | 0 [0–0] of 20 | 8 [8–9] of 10 | 3 [2–4] | 0.60 / 0.75 | $0.0004 |
| gpt-5.4-mini, reworded instruction A | 3 [1–3] of 10 | 3 [1–3] | 0 [0–0] of 20 | 8 [8–9] of 10 | 3 [1–3] | 0.59 / 0.73 | $0.0004 |
| gpt-5.4-mini, no everyday-word hints | 2 [1–3] of 10 | 2 [1–3] | 0 [0–0] of 20 | 7 [5–9] of 10 | 2 [1–3] | 0.59 / 0.76 | $0.0015 |
| gpt-5.4-mini, reworded instruction A + no everyday-word hints | 1 [1–1] of 10 | 1 [1–1] | 0 [0–0] of 20 | 6 [4–7] of 10 | 1 [1–1] | 0.61 / 0.74 | $0.0005 |
| GPT-5.4, VoiceFlow's prompt | 6 [6–6] of 10 | 6 [6–6] | 0 [0–0] of 20 | 9 [9–10] of 10 | 6 [6–6] | 0.77 / 0.89 | $0.0024 |
| Claude Sonnet 5.5 (effort low), VoiceFlow's prompt | 1 [1–2] of 10 | 1 [1–2] | 0 [0–0] of 20 | 10 [9–10] of 10 | 2 [1–4] | 1.29 / 1.79 | $0.0077 |
| Claude Sonnet 5.5 (effort low), reworded instruction A + no everyday-word hints | 0 [0–0] of 10 | 0 [0–0] | 0 [0–0] of 20 | 2 [2–3] of 10 | 0 [0–0] | 1.36 / 2.05 | $0.0074 |
| Claude Opus 5.5 (effort low), VoiceFlow's prompt | 0 [0–0] of 10 | 0 [0–0] | 0 [0–0] of 20 | 10 [10–10] of 10 | 0 [0–0] | 0.78 / 1.67 | $0.0157 |

Latency is the time of the answering attempt, from this PC, with 4 calls in flight; a call that had to be retried counts only its last attempt.

## Picking the reworded instruction (synthetic pilot only)

The three wordings were written before any result and compared on the synthetic voice "coral" only, so the choice was not tuned on his voice. Picked: **A**.

| Arm | Sound-alike → listed name | Sound-alikes broken (any way) | Targets broken | Made-up mishearings repaired | Never-said listed words | Latency median / p90 (s) | Cost per call |
|---|---|---|---|---|---|---|---|
| gpt-5.4-mini, VoiceFlow's prompt | 2 [1–3] of 9 | 2 [1–3] | 0 [0–0] of 20 | 8 [8–9] of 10 | 3 [2–4] | 0.60 / 0.75 | $0.0004 |
| gpt-5.4-mini, reworded instruction A | 1 [1–2] of 9 | 1 [1–2] | 0 [0–0] of 20 | 8 [8–8] of 10 | 2 [2–3] | 0.60 / 0.73 | $0.0004 |
| gpt-5.4-mini, reworded instruction B | 2 [1–3] of 9 | 2 [1–3] | 0 [0–0] of 20 | 9 [8–9] of 10 | 3 [2–3] | 0.60 / 0.75 | $0.0004 |
| gpt-5.4-mini, reworded instruction C | 3 [2–4] of 9 | 3 [2–4] | 0 [0–0] of 20 | 8 [8–8] of 10 | 4 [3–5] | 0.60 / 0.77 | $0.0004 |

<details><summary>Wording A (picked)</summary>

- Replace a word or phrase in the transcript with a listed word only when the transcript's word is not a real word or name, or is a real one that does not fit the sentence, and the listed word sounds like it and fits the sentence and its context.
- A real word or name that fits the sentence is correct as written: keep it, even when it sounds like a listed word, and even when a note says speech-to-text once wrote it for that word. If both readings are plausible, keep the transcript's words.

</details>

<details><summary>Wording B</summary>

- Before replacing anything, ask whether the transcript's word is a real word or a known name (a product, an app, a place, an everyday word) that makes sense in this sentence. If it is, keep it exactly. Only if it is not, and a listed word sounds like it and fits, write the listed word.
- The notes on what speech-to-text wrote before describe past mistakes, not this transcript. A match with a note is never a reason on its own to change a word that fits the sentence.

</details>

<details><summary>Wording C</summary>

- Change a word to a listed word only to repair a clear mishearing: something that is not a real word, a name broken into pieces, or a word that makes no sense in the sentence.
- Never change a real word or name that fits the sentence, even if it sounds like a listed word or was once written for one. When unsure, keep the transcript's words.

</details>

## The everyday-word rule

A heard-as form is dropped when every word in it is in SCOWL's "english" and "american" lists up to size 60, the size SCOWL recommends for spell checking (npm wordlist-english 1.2.1). Proper names are not in those lists. In this test list it drops:

- Bepra: "Beeper"
- Dockra: "Docker"
- Harbra: "harbor"
- Kavren: "Cavern"
- Tailscale: "tail scale"
- Kandrel: "candle"
- Syncthing: "sync thing"
- Ollama: "Oh llama"
- Nginx: "engine x"

It keeps every other form, including Mirva: "Miro" (a proper name).

