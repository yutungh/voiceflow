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
