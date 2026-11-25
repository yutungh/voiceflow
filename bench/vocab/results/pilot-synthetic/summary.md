# Results: pilot-synthetic

**Synthetic voices** (OpenAI text-to-speech: coral, ash), not human speech. A check of the harness and a first look only.

## Speech step: gpt-transcribe with 0 to 80 listed words

n = 80 sentences × 3 runs (tts-coral, tts-ash). Each cell: median of the runs [lowest–highest run].

| Words listed | Targets right | Names right | Products right | Sound-alikes kept right | Never-said listed words | Control sentences with one | Word error rate |
|---:|---|---|---|---|---|---|---|
| 0 | 21 [21–21] of 40 | 9 [9–9] of 28 | 12 [12–12] of 12 | 19 [19–19] of 20 | 0 [0–0] | 0 [0–0] of 20 | 2.9% [2.9–2.9] |
| 20 | 40 [40–40] of 40 | 28 [28–28] of 28 | 12 [12–12] of 12 | 19 [19–19] of 20 | 0 [0–1] | 0 [0–0] of 20 | 0.1% [0.1–0.1] |
| 40 | 39 [39–39] of 40 | 27 [27–27] of 28 | 12 [12–12] of 12 | 19 [19–19] of 20 | 1 [1–1] | 0 [0–0] of 20 | 0.3% [0.3–0.3] |
| 50 | 39 [39–39] of 40 | 27 [27–27] of 28 | 12 [12–12] of 12 | 20 [20–20] of 20 | 0 [0–0] | 0 [0–0] of 20 | 0.1% [0.1–0.1] |
| 80 | 36 [36–37] of 40 | 24 [24–25] of 28 | 12 [12–12] of 12 | 20 [19–20] of 20 | 0 [0–0] | 0 [0–0] of 20 | 0.6% [0.4–0.7] |

API failures (not scored): 0.

Never-said listed words seen (word (list size): times, all runs): Bepra (20): 1; Bepra (40): 3.

## Correction step: the same transcript, tidied with the 80-word list

Input: the 20-word speech answer (run 1) for each sentence, frozen before any result was seen. n = 40 sentences × 3 runs.
"Wrong corrections" counts words that were right in the transcript and wrong after the tidy-up; "into the sound-alike" is how many of those became the listed name that sounds like it (the Beeper case). "Fixes" counts words that were wrong in the transcript and right after. For the direct-audio arm, which never sees the transcript, the same columns compare its answer with the transcript. An answer wrapped in JSON is scored on the text inside it; an empty answer (or a bare control token) scores as wrong; failed API calls are not scored. All three are counted in the last column.

| Arm | Targets right | Sound-alikes kept right | Wrong corrections | …into the sound-alike | Fixes | Never-said listed words | Word error rate | Wrapped / empty / failed answers (all runs) |
|---|---|---|---|---|---|---|---|---|
| No tidy-up (the transcript) | 20 [20–20] of 20 | 9 [9–9] of 10 | – | – | – | 0 [0–0] | 0.3% [0.3–0.3] | 0 / 0 / 0 |
| Text tidy-up, gpt-5.4-mini (VoiceFlow default) | 20 [20–20] of 20 | 8 [6–9] of 10 | 1 [0–3] of 29 | 1 [0–3] | 0 [0–0] of 1 | 2 [1–4] | 0.6% [0.3–1.2] | 0 / 0 / 0 |
| Text tidy-up, gpt-audio-1.5, no recording | 20 [20–20] of 20 | 5 [5–6] of 10 | 4 [3–4] of 29 | 4 [3–4] | 0 [0–0] of 1 | 5 [4–5] | 1.5% [1.2–1.5] | 0 / 0 / 0 |
| Tidy-up with the recording, gpt-audio-1.5 | 20 [20–20] of 20 | 5 [3–6] of 10 | 4 [3–6] of 29 | 4 [2–5] | 0 [0–0] of 1 | 5 [3–6] | 4.1% [1.5–4.4] | 17 / 2 / 0 |
| Recording + list, no transcript, gpt-audio-1.5 | 18 [17–19] of 20 | 3 [2–3] of 10 | 9 [7–9] of 29 | 5 [4–6] | 0 [0–0] of 1 | 6 [5–7] | 12.1% [10.6–16.2] | 65 / 10 / 0 |

Examples of wrong corrections (run 1):

- text-mini: tts-coral/s25: "I rebuilt the image and restarted Docker." -> "I rebuilt the image and restarted Dockra."
- text-audio15: tts-coral/s03: "This morning, Docker would not start the container." -> "This morning, Dockra would not start the container."
- text-audio15: tts-coral/s22: "All my chats are in Beeper, so I only check one app." -> "All my chats are in Bepra, so I only check one app."
- text-audio15: tts-coral/s25: "I rebuilt the image and restarted Docker." -> "I rebuilt the image and restarted Dockra."
- text-audio15: tts-coral/s33: "Please share the Miro link with the whole team." -> "Please share the Mirva link with the whole team."
- audio15-fix: tts-coral/s03: "This morning, Docker would not start the container." -> "This morning, Dockra would not start the container."
- audio15-fix: tts-coral/s14: "We sketched the plan on a Miro board." -> "We sketched the plan on a Mirva board."
- audio15-fix: tts-coral/s22: "All my chats are in Beeper, so I only check one app." -> "All my chats are in Bepra, so I only check one app."
- audio15-fix: tts-coral/s25: "I rebuilt the image and restarted Docker." -> "I rebuilt the image and restarted Dockra."
- audio15-direct: tts-coral/s03: "This morning, Docker would not start the container." -> "This morning Dockra would not start the container."
- audio15-direct: tts-coral/s07: "Please light the candle on the dining table." -> ""
- audio15-direct: tts-coral/s11: "Tell Harbra the parcel arrived this afternoon." -> ""
- audio15-direct: tts-coral/s14: "We sketched the plan on a Miro board." -> "We sketched the plan on a Mirva board."

## Repair cases: the listed name, written as its sound-alike

Each recording says a listed name (e.g. Bepra), but the transcript given to the tidy-up says the everyday sound-alike instead ("Can you ask Beeper to send the slides…"). These errors are constructed, so they are reported apart from the real transcripts above.

| Arm | Repaired to the listed name | Left as the sound-alike |
|---|---|---|
| Text tidy-up, gpt-5.4-mini (VoiceFlow default) | 8 [8–9] of 10 | 2 [1–2] |
| Text tidy-up, gpt-audio-1.5, no recording | 10 [10–10] of 10 | 0 [0–0] |
| Tidy-up with the recording, gpt-audio-1.5 | 10 [10–10] of 10 | 0 [0–0] |
| Recording + list, no transcript, gpt-audio-1.5 (its own answers; it never sees the transcript) | 9 [9–10] of 10 | 0 [0–0] |

## Opus smoke test (unscored)

10 calls; stop reasons: end_turn.

