# Custom-vocabulary test

A small, reproducible test of two questions behind VoiceFlow's word lists:

1. **Speech step.** When `gpt-transcribe` is given 0, 20, 40, 50 or 80 custom
   words, how often does it get the target words right, and how often does it
   write a listed word that was never said?
2. **Tidy-up step.** Given the same transcript and an 80-word list with
   "speech-to-text wrote ..." hints, how often does the tidy-up change a word
   that was heard correctly into a listed word that sounds like it (VoiceFlow
   once turned a correctly heard "Beeper" into a listed name), and how often
   does it fix a real mishearing? Does a model that also **hears the
   recording** do better?

Everything is sent the way VoiceFlow sends it, using VoiceFlow's own code
where it can be imported (`lib/prompts.ts` says which parts and how).

## The script

`script.json`: 40 short sentences, read aloud by one person in about ten
minutes (`RECORDING.md` is the reading sheet). The script text is the ground
truth.

| Kind | Sentences | What it is |
|---|---:|---|
| Target | 20 | A listed word in a context that fits it. 10 targets, each said twice: 7 invented names (Bepra, Dockra, Mirva, Harbra, Kandrel, Zoravyn, Kavren) and 3 public products (Kubernetes, Obsidian, Tailscale). |
| Sound-alike | 10 | A word that is **not** listed but sounds like a listed name, used in its own meaning: Beeper / Bepra, Docker / Dockra, Miro / Mirva, harbor / Harbra, candle / Kandrel. Each said twice. Changing it into the listed name is a wrong correction. |
| Control | 10 | No listed word at all. |

The names are invented and said as written in `words.json` (`say`), close to
their sound-alike but not identical, so the recording can tell them apart. The
real listed word from the Beeper case can't be published, so Bepra stands in
for it.

## The word list

`words.json`: one 80-word list, most important first: the 10 targets plus 70
padding words (35 invented names, 35 public products) that are never said. A
list of N words is the first N entries, so the lists are nested and the 10
targets are in every list of 20 or more. As in VoiceFlow, the most important
word goes last in the request, so a longer list adds its extra words at the
front and the 20 core words keep the same order and position at the end.

"Heard-as" hints are seeded for the test, not observed: each of the five
paired names lists its sound-alike ("Bepra: speech-to-text wrote 'Beeper'"),
the hardest case for a tidy-up. A few padding words carry hints too. Whether
the real word list had such a hint in the Beeper case is not known, so the
`text-mini-nohint` arm runs the same list with no heard-as notes.

## Speech step

`gpt-transcribe`, one request per sentence, 16 kHz mono FLAC, `temperature`
0, `languages: ['en']`, and for N > 0 words both `keywords[]` and the line
`Vocabulary: A, B, C` (plain words, no hints). VoiceFlow itself sends at most
40 words (20 by default) since 0.9.15; the 50- and 80-word requests are built
in the same format, and the self-test checks the 20- and 40-word requests
match VoiceFlow's `buildPrompt` exactly.

## Tidy-up step

Every arm starts from the same transcript: the 20-word speech answer from
run 1, fixed by rule before any result was seen. The prompt is VoiceFlow's
default style, Clean, as the desktop app sends it, with the 80-word block from
VoiceFlow's `tidyWordsFor`.

| Arm | Model | Gets |
|---|---|---|
| No tidy-up | | the transcript as it is (the baseline) |
| `text-mini` | gpt-5.4-mini | transcript + list: VoiceFlow as built |
| `text-opus` | claude-opus-5-5 | transcript + list, same prompt (max_tokens 1024, as VoiceFlow) |
| `text-audio15` | gpt-audio-1.5 | transcript + list, no recording |
| `audio15-fix` | gpt-audio-1.5 | transcript + list + the recording |
| `audio15-direct` | gpt-audio-1.5 | the recording + list, no transcript |
| `text-mini-nohint` | gpt-5.4-mini | as `text-mini`, list without heard-as notes |
| `audio15-fix-spoken`, `audio15-direct-spoken` | gpt-audio-1.5 | as the recording arms, answering in the control's output mode |

- `text-audio15` and `audio15-fix` get the same prompt plus one line saying
  the transcript is a draft and an attached recording is the authority. They
  differ only in the recording, so the pair shows what hearing it adds with
  the model held fixed.
- **One forced difference:** gpt-audio-1.5 refuses a request with no audio
  in or out, so `text-audio15` asks for a spoken answer and keeps the text of
  it (its audio is thrown away), while the recording arms answer in text. The
  `-spoken` arms remove that difference. Answering in text, gpt-audio-1.5
  often wraps its answer in JSON or returns nothing; answering by voice it
  did not, in the pilot.
- No Claude model takes audio input (the Models API lists image and PDF input
  for claude-opus-5-5, not audio, and the Messages content blocks are text,
  image and document: https://platform.claude.com/docs/en/api/messages), so the
  audio arms are OpenAI only. OpenAI's audio-in models for Chat Completions:
  https://developers.openai.com/api/docs/guides/audio-chat-completions.
- **Repair cases.** The ten recordings of a paired name are also given a
  transcript with the sound-alike written in ("Can you ask Beeper to send the
  slides…"). These made-up errors measure repair only and are reported apart.

## What is counted

Each run is scored on its own; tables show the **median of 3 runs [lowest–highest]**
with "n = sentences × 3 runs". The runs repeat the API calls on the same
recordings: they show how much the models vary, not how much people vary.

- **Targets right:** the target word appears, spelled exactly as listed
  (case and punctuation ignored). "Zora Vin" is not "Zoravyn".
- **Sound-alikes kept right:** the same for the ten unlisted words.
- **Never-said listed words:** any of the 80 listed words that the answer has
  more times than the script sentence, in every condition, including 0 words.
- **Wrong corrections:** right in the transcript, wrong after the tidy-up;
  "into the sound-alike" counts those that became the paired listed name.
- **Fixes:** wrong in the transcript, right after.
- **Word error rate:** secondary; the tidy-up may legitimately change
  punctuation and small words.
- **Answer format:** an answer wrapped in JSON is scored on the text inside;
  an empty answer scores as wrong; both are counted (`lib/score.ts`).

## Running it

Needs [bun](https://bun.sh), ffmpeg on the PATH, and OpenAI and Anthropic API
keys in the repository root's `.env.local` (`OpenAIAPIKey = ...`,
`AnthropicAPIKey = ...`) or as `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`.

```sh
cd bench/vocab
bun install
bun lib/selftest.ts                                   # no API calls

# Pilot on synthetic voices
bun tts.ts --voices coral,ash
bun run.ts --phase pilot --set tts-coral --step speech
bun run.ts --phase pilot --set tts-ash --step speech
bun run.ts --phase pilot --set tts-coral --step correction --arms text-mini,text-mini-nohint,text-audio15,audio15-fix,audio15-direct
bun run.ts --phase pilot --set tts-coral --step constructed --arms text-mini,text-mini-nohint,text-audio15,audio15-fix
bun run.ts --phase pilot --set tts-coral --step smoke  # 10 unscored Opus calls
bun report.ts --sets tts-coral,tts-ash --label pilot-synthetic

# A person's own recordings (RECORDING.md), then everything
# (--step all runs speech, then the tidy-up steps that read its answers)
bun collect.ts --since "2026-11-26 19:00"
bun run.ts --phase real --set real --step all --estimate
bun run.ts --phase real --set real --step all
bun report.ts --sets real --label real
```

On Windows PowerShell, quote comma lists: `--arms "a,b"`. Add `--estimate` to any `run.ts` command to see the calls and cost first.
`run.ts` stops before a step that would take the phase past its budget
(`--budget`, default $5 pilot / $10 real), counting what the ledger already
spent. A rerun only makes the calls that are missing or failed.

Audio, raw answers and the cost ledger live outside the repository, in
`C:\vf\vocab-test` (`VOCAB_DATA_DIR` to change it). No recording of anyone's
voice is committed; `results/` holds the scored text answers.

## Limits

- One speaker, ten targets, five sound-alike pairs: enough to show a
  direction and concrete failures, not a rate to quote to two digits.
- The pilot uses OpenAI text-to-speech voices, which say every word cleanly;
  it checks the harness and is labelled "synthetic voices" wherever it is
  quoted.
- The heard-as hints are seeded to match the real failure, so the tidy-up
  results show what happens when a sound-alike has been misheard before.
- The tidy-up prompt leaves out the bar's expression guidance (one paragraph
  on tone), which doesn't bear on word choice.
