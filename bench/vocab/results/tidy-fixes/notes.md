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
