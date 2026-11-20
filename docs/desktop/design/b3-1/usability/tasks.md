# VoiceFlow B3.1: usability test kit

Static first-click and task test on the B3.1 mockup, light mode, 1280 × 800. From the protocol in `research-uiux.md` §3, adapted to B3's real screens. Demo content only.

## How to run it

- **People:** five testers find most problems. As a cheap pre-check, give each task to its own fresh subagent with only the screenshot(s) and the prompt, never the rest of this file. Treat that as a direction, not proof.
- **Read each prompt as written.** The prompts avoid the interface's own words on purpose (for example "spell it right", not "Vocabulary" or "Fix a spelling").
- **Show only the "start" screenshot first.** Ask: "Where would you click first?" Then show the next screenshot, if the task has one, and ask what they would do there.
- **Do not hint.** A hint makes the task "partial".

## Score each task

| Measure | How | Pass bar (5 people) |
|---|---|---|
| First click | Lands in the correct zone below? yes / no | 4 of 5 |
| Task success | Success / partial (needed a hint) / fail | 4 of 5 success |
| Ease (SEQ) | "Overall, how easy was that?" 1 to 7 | Median 6 or more |
| Confidence | "How sure are you that it worked?" 1 to 5 | 4 or more |
| Notes | Hesitation over 5 s, wrong turns, misread labels | Each repeat becomes a fix |

Report counts ("4/5"), not percentages. A static click test can mispredict live behaviour, so re-check the worst tasks on the clickable build.

## Tasks

### Task 1: Home

**Say to the tester:** “How many words have you dictated this week, and is that more than last week?”

- **Show:** `screens/t01-1-start.png` (from `home`). Give the next screen only after their first click or answer.
- **Correct first click:** The "Words this week" card (top left).
- **Success:** Says 3,412 and yes, about 18% more than last week.
- **What it tests:** Stat strip and sparkline.

### Task 2: Home

**Say to the tester:** “Yesterday afternoon you dictated a message into Telegram. Find it and copy it.”

- **Show:** `screens/t02-1-start.png` (from `home`), then `screens/t02-2-after-first-click.png` (from `history-ok`). Give the next screen only after their first click or answer.
- **Correct first click:** History in the sidebar, or "All history" on the Recent card.
- **Success:** On the second screen: picks the Telegram row at 16:20 yesterday (already selected) and clicks Copy text (or presses C).
- **What it tests:** History, app icons, finding by app and time.

### Task 3: History

**Say to the tester:** “VoiceFlow keeps writing the name “Zephyra” wrong in your messages. Make it spell it right from now on.”

- **Show:** `screens/t03-1-start.png` (from `history-ok`), then `screens/t03-2-after-first-click.png` (from `history-fix`). Give the next screen only after their first click or answer.
- **Correct first click:** "Fix a spelling" in the right-hand panel.
- **Success:** On the second screen: checks "Always write it as: Zephyra" and clicks Save spelling; ideally notices it applies to new dictations. Partial: goes to Vocabulary and reads "ask your assistant".
- **What it tests:** Add-a-spelling from a row.

### Task 4: History

**Say to the tester:** “Delete the Telegram message from yesterday afternoon. Then: oh wait, you needed it. Get it back.”

- **Show:** `screens/t04-1-start.png` (from `history-ok`), then `screens/t04-2-after-delete.png` (from `history-undo`). Give the next screen only after their first click or answer.
- **Correct first click:** "Delete" in the right-hand panel.
- **Success:** On the second screen: clicks Undo in the message at the bottom (or Ctrl+Z) within 8 seconds; or names Recently deleted (kept 30 days).
- **What it tests:** Undo instead of a confirm dialog.

### Task 5: Suggestions

**Say to the tester:** “VoiceFlow has an idea for you. Decide whether you want it, and say what it will change.”

- **Show:** `screens/t05-1-start.png` (from `suggest`). Give the next screen only after their first click or answer.
- **Correct first click:** One of the three answers ("Always write “Vercel”", "Add “Vercel” as a word, no auto-fix", "No, “Versal” is right").
- **Success:** Explains that "Versal" would be written "Vercel" in new dictations only (past ones stay), citing the crossed-out and highlighted words.
- **What it tests:** Before-and-after legibility.

### Task 6: Styles

**Say to the tester:** “Your emails sound too stiff. Make the emails you dictate more casual, but leave your chat messages as they are.”

- **Show:** `screens/t06-1-start.png` (from `styles-closed`), then `screens/t06-2-after-first-click.png` (from `styles`). Give the next screen only after their first click or answer.
- **Correct first click:** The style menu on the Email card (it says "Work").
- **Success:** On the second screen: picks Casual for Email, leaves Chat alone; ideally notices "Applies to new dictations only".
- **What it tests:** Per-app styles; new dictations only.

### Task 7: Pill, no sound

**Say to the tester:** “You are talking, but nothing is happening. What is wrong, and what would you click?”

- **Show:** `screens/t07-1-start.png` (from `pill-noaudio`). Give the next screen only after their first click or answer.
- **Correct first click:** "Choose microphone" on the bar at the bottom of the screen.
- **Success:** Says the microphone is not picking up sound and clicks Choose microphone.
- **What it tests:** Problem-state clarity on the pill.

### Task 8: Home with a problem

**Say to the tester:** “Something needs your attention. What is it, and how do you fix it?”

- **Show:** `screens/t08-1-start.png` (from `home`). Give the next screen only after their first click or answer.
- **Correct first click:** "Retry tidy-up" on the card under "Needs you".
- **Success:** Says a Telegram message was not pasted because tidy-up took too long, and that Retry tidy-up fixes it (Paste raw text is an acceptable alternative).
- **What it tests:** The edge, icon, plain text and one fix.

### Task 9: Settings

**Say to the tester:** “Change the key you hold down while you talk.”

- **Show:** `screens/t09-1-start.png` (from `settings`). Give the next screen only after their first click or answer.
- **Correct first click:** "Change" on the "Hold to talk" row.
- **Success:** Clicks that Change button (not Start or stop).
- **What it tests:** Keycaps and labels in Settings.

### Task 10: First run

**Say to the tester:** “You just installed VoiceFlow. Get it working and do your first dictation.”

- **Show:** `screens/t10-1-start.png` (from `firstrun-1`), then `screens/t10-2-step-2.png` (from `firstrun-2`), then `screens/t10-3-step-3.png` (from `firstrun-3`). Give the next screen only after their first click or answer.
- **Correct first click:** "Start the test" on the first screen.
- **Success:** Screen 2: says they would press Ctrl+Space, read the sentence in the box, and press Ctrl+Space again. Screen 3: says they are done and clicks "Start using VoiceFlow"; the vocabulary step is optional.
- **What it tests:** Task-based onboarding.

## After all tasks

1. "Describe this app to a friend in one sentence."
2. "Which screen felt calm, and which felt dull?"
3. "Overall, does it feel too playful, about right, or too dry?"

## Files

- `screens/`: the 16 screenshots, named `t<task>-<order>-<when>.png` (not committed; `node ../src/usability-kit31.mjs` writes them after `node ../src/b31-run.mjs`).
- Full mockup with every screen in light and dark: `../voiceflow-b3-1.html`.
