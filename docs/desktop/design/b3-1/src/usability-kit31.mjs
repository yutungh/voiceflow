// Builds ../usability/: tasks.md plus, per task, the light-mode screenshots a fresh tester looks at, copied and renamed.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..'); const out = path.join(root, 'usability'); const shots = path.join(root, 'shots', 'light');
fs.mkdirSync(path.join(out, 'screens'), { recursive: true });
const T = [
  { n: 1, start: 'Home', prompt: 'How many words have you dictated this week, and is that more than last week?', screens: [['home', 'start']], click: 'The "Words this week" card (top left).', pass: 'Says 3,412 and yes, about 18% more than last week.', tests: 'Stat strip and sparkline.' },
  { n: 2, start: 'Home', prompt: 'Yesterday afternoon you dictated a message into Telegram. Find it and copy it.', screens: [['home', 'start'], ['history-ok', 'after-first-click']], click: 'History in the sidebar, or "All history" on the Recent card.', pass: 'On the second screen: picks the Telegram row at 16:20 yesterday (already selected) and clicks Copy text (or presses C).', tests: 'History, app icons, finding by app and time.' },
  { n: 3, start: 'History', prompt: 'VoiceFlow keeps writing the name “Zephyra” wrong in your messages. Make it spell it right from now on.', screens: [['history-ok', 'start'], ['history-fix', 'after-first-click']], click: '"Fix a spelling" in the right-hand panel.', pass: 'On the second screen: checks "Always write it as: Zephyra" and clicks Save spelling; ideally notices it applies to new dictations. Partial: goes to Vocabulary and reads "ask your assistant".', tests: 'Add-a-spelling from a row.' },
  { n: 4, start: 'History', prompt: 'Delete the Telegram message from yesterday afternoon. Then: oh wait, you needed it. Get it back.', screens: [['history-ok', 'start'], ['history-undo', 'after-delete']], click: '"Delete" in the right-hand panel.', pass: 'On the second screen: clicks Undo in the message at the bottom (or Ctrl+Z) within 8 seconds; or names Recently deleted (kept 30 days).', tests: 'Undo instead of a confirm dialog.' },
  { n: 5, start: 'Suggestions', prompt: 'VoiceFlow has an idea for you. Decide whether you want it, and say what it will change.', screens: [['suggest', 'start']], click: 'One of the three answers ("Always write “Vercel”", "Add “Vercel” as a word, no auto-fix", "No, “Versal” is right").', pass: 'Explains that "Versal" would be written "Vercel" in new dictations only (past ones stay), citing the crossed-out and highlighted words.', tests: 'Before-and-after legibility.' },
  { n: 6, start: 'Styles', prompt: 'Your emails sound too stiff. Make the emails you dictate more casual, but leave your chat messages as they are.', screens: [['styles-closed', 'start'], ['styles', 'after-first-click']], click: 'The style menu on the Email card (it says "Work").', pass: 'On the second screen: picks Casual for Email, leaves Chat alone; ideally notices "Applies to new dictations only".', tests: 'Per-app styles; new dictations only.' },
  { n: 7, start: 'Pill, no sound', prompt: 'You are talking, but nothing is happening. What is wrong, and what would you click?', screens: [['pill-noaudio', 'start']], click: '"Choose microphone" on the bar at the bottom of the screen.', pass: 'Says the microphone is not picking up sound and clicks Choose microphone.', tests: 'Problem-state clarity on the pill.' },
  { n: 8, start: 'Home with a problem', prompt: 'Something needs your attention. What is it, and how do you fix it?', screens: [['home', 'start']], click: '"Retry tidy-up" on the card under "Needs you".', pass: 'Says a Telegram message was not pasted because tidy-up took too long, and that Retry tidy-up fixes it (Paste raw text is an acceptable alternative).', tests: 'The edge, icon, plain text and one fix.' },
  { n: 9, start: 'Settings', prompt: 'Change the key you hold down while you talk.', screens: [['settings', 'start']], click: '"Change" on the "Hold to talk" row.', pass: 'Clicks that Change button (not Start or stop).', tests: 'Keycaps and labels in Settings.' },
  { n: 10, start: 'First run', prompt: 'You just installed VoiceFlow. Get it working and do your first dictation.', screens: [['firstrun-1', 'start'], ['firstrun-2', 'step-2'], ['firstrun-3', 'step-3']], click: '"Start the test" on the first screen.', pass: 'Screen 2: says they would press Ctrl+Space, read the sentence in the box, and press Ctrl+Space again. Screen 3: says they are done and clicks "Start using VoiceFlow"; the vocabulary step is optional.', tests: 'Task-based onboarding.' },
];
const lines = [];
for (const t of T) {
  const files = t.screens.map(([id, tag], i) => { const name = `t${String(t.n).padStart(2, '0')}-${i + 1}-${tag}.png`; fs.copyFileSync(path.join(shots, `${id}.png`), path.join(out, 'screens', name)); return `\`screens/${name}\` (from \`${id}\`)`; });
  lines.push(`### Task ${t.n}: ${t.start}\n\n**Say to the tester:** “${t.prompt}”\n\n- **Show:** ${files.join(', then ')}. Give the next screen only after their first click or answer.\n- **Correct first click:** ${t.click}\n- **Success:** ${t.pass}\n- **What it tests:** ${t.tests}\n`);
}
const md = `# VoiceFlow B3.1: usability test kit

Static first-click and task test on the B3.1 mockup, light mode, 1280 × 800. From the protocol in \`research-uiux.md\` §3, adapted to B3's real screens. Demo content only.

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

${lines.join('\n')}
## After all tasks

1. "Describe this app to a friend in one sentence."
2. "Which screen felt calm, and which felt dull?"
3. "Overall, does it feel too playful, about right, or too dry?"

## Files

- \`screens/\`: the ${fs.readdirSync(path.join(out, 'screens')).length} screenshots, named \`t<task>-<order>-<when>.png\`.
- Full mockup with every screen in light and dark: \`../voiceflow-b3-1.html\`.
`;
fs.writeFileSync(path.join(out, 'tasks.md'), md);
console.log(`usability kit: ${T.length} tasks, ${fs.readdirSync(path.join(out, 'screens')).length} screenshots in ${out}`);
