# VoiceFlow Desktop: live checks

What only a run on a real desktop can prove. Build: the `desktop/verify` branch, version 0.9.0 (M1 + A0 audio + A1 tray + C1 screens + speed work). Install the NSIS build from that branch's CI run or a local build.

**Core session: 12 checks, about 10 to 15 minutes.** The extended checks below are for when there is more time or something looks off.

Before starting: VoiceFlow is installed and running, Settings shows an OpenAI key in Windows Credential Manager, Privacy is Strict, Transcription is `gpt-transcribe`, and a tidy-up style is the default under Tidy-up. "Your keys" below means the start/stop and hold-to-talk keys shown in Settings > Dictation keys.

## Core

| # | Check | Steps | Expected |
|---|---|---|---|
| 1 | First look | Open VoiceFlow. Visit Home, History, Styles, Settings | No first-run setup (an install with history goes straight to Home). History lists earlier dictations; any imported from Whispering show "Audio removed" and their text. Settings has a Startup card with a Start with Windows switch and a Speed card. No developer badge or toolbar anywhere. |
| 2 | Dictate and paste | Click in Notepad. Start with your keys, say a sentence, stop | Text appears at the cursor. A quiet "Pasted into Notepad" note. Home's latest dictation shows Notepad's icon. The clipboard holds what it held before. |
| 3 | Hold to talk | In a browser text box (Edge or Chrome), hold the hold-to-talk keys, speak, release | Pasted in the text box. |
| 4 | Password field refused | Click a password box on any sign-in page, press your keys | Nothing records. Notice: "Not recording: … looks like a password field." |
| 5 | Switch away before the paste | Dictate into Notepad, and as soon as you stop, click into a password manager (Bitwarden, 1Password) | Not pasted there: "Not pasted: … is on the block list" or "another window came to the front". The text is in History and on the clipboard. |
| 6 | Held, then Send anyway | Put Task Manager (run as administrator) or Windows Terminal in front, dictate | "Nothing was sent" notice with Send anyway. Click it: the text arrives in History and on the clipboard, and is not pasted anywhere. |
| 7 | Tidy-up failure | On the default style, set the model to `gpt-test-invalid` (Styles). Dictate into Notepad, then click into another app | Nothing pasted. A notice that stays, with Retry tidy-up and Paste raw text. Paste raw text: Notepad comes back and gets the words. Set the model back. |
| 8 | Fix a spelling, then Undo | In History, pick a dictation with a misheard name. Fix a spelling: heard form, how to write it, every app. Dictate the name again. Then Undo that spelling and dictate it once more | After saving: the name is written your way, and History shows the change. After Undo: the heard form comes back. A spelling added by the upgrade kit (if used) applies the same way. |
| 9 | Keep recordings | Settings > History and audio > Keep recordings shows 7 days. Set it to Off and dictate once | The new History row shows "Audio removed"; its text stays. Set it back to 7 days. |
| 10 | Settings > Speed | After the dictations above, open Settings > Speed, press Refresh, open "By step" | "Release to pasted" shows a typical and a slow time (note the typical one). Each step has numbers; Paste is a few hundred ms at most. No words or app names are shown. |
| 11 | Tray and single copy | Click the window's X. Right-click the tray icon. Then launch VoiceFlow from the Start menu | X hides the window (one-time notice); your keys still dictate. Tray menu: status, Open VoiceFlow, Theme, Start with Windows, Quit. Launching again shows the existing window, no second copy. |
| 12 | Look and focus | Settings > Appearance: switch Light, Dark, System | Every screen stays readable in both. During the whole session, no VoiceFlow window jumped in front of the app you were dictating into (except when you clicked a notice button). |

## Extended

| # | Check | Steps | Expected |
|---|---|---|---|
| E1 | Real apps | Repeat 2 in VS Code, Slack or Teams, Word, Outlook | Pasted in each. Telegram Desktop or a terminal may be held under Strict (their fields can't be checked). |
| E2 | Block list at the hotkey | Open 1Password or Bitwarden, press your keys | Nothing records: "… is on the block list." |
| E3 | Target gone | Repeat 7 but close Notepad before clicking Paste raw text | Not pasted; the notice says the window is closed; the text is on the clipboard. |
| E4 | Re-run tidy-up | In History, pick a dictation, Re-run tidy-up | New text is kept and copied, never pasted. |
| E5 | Give feedback | In History, Give feedback on a dictation | Saved on this PC (nothing is sent yet). |
| E6 | Long dictation | Dictate 70 to 90 seconds into Notepad | Pasted in full. The WAV in the app data `recordings` folder is about 32 KB per second (16 kHz mono). |
| E7 | Very long dictation | Dictate or play audio for 12+ minutes | Transcribed in parts, joined in order; no 25 MB error. |
| E8 | Start at login | Turn on Start with Windows (Settings > Startup or the tray), sign out and back in | VoiceFlow is in the tray with no window; your keys work. Turn it off, sign out and in: it doesn't start. |
| E9 | Sleep and resume | With VoiceFlow in the tray, sleep the PC a few minutes, wake it, dictate | Works without restarting VoiceFlow. Also sleep mid-recording, wake, stop: saved or a clear error, and the next dictation works. |
| E10 | Overlapping dictations | Dictate into Notepad, and as soon as it stops start a second one into WordPad | Each text lands in its own app; History shows two rows with the right app each. |
| E11 | Key storage | Quit and reopen VoiceFlow; Credential Manager > Windows Credentials lists `api-key-openai.com.voiceflowkeyboard.desktop` | The key still works. |
| E12 | First dictation after a pause | Leave VoiceFlow idle 10+ minutes, then dictate | Speed's "Upload + transcription" for that dictation is in line with the others (the connection is opened while you talk). |

macOS (when a Mac build is tried): repeat 2, 3, 4, 6, 11 and E8 with Cmd in place of Ctrl; the menu bar replaces the tray; "Open at login" replaces Start with Windows; Accessibility permission is needed for paste. Known gaps: the password check there only sees Secure Input, and Paste raw text returns to the app, not to the exact window.

## A2: the recording bar (branch `desktop/a2-bar`)

Ships only after the A-gate. What the unit tests and the harness cannot prove: that WebView2 inside the non-activating window never moves keyboard focus when clicked, and that VoiceFlow, which may take the foreground right after its hotkey, still never does. The harness (`cargo test --lib bar::tests::live_bar_calls_keep_the_foreground -- --ignored`) only shows that the show/move/hide calls don't change the foreground from a process that couldn't take it anyway.

The log (`%LOCALAPPDATA%\com.voiceflowkeyboard.desktop\logs\voiceflow.log`) has a line `[bar] focus after a click: restored` or `failed` whenever a bar click left VoiceFlow in front and the bar had to give the front back.

| # | Check | Steps | Expected |
|---|---|---|---|
| B1 | Appears without taking focus | Click in Notepad. Start with your keys. While the bar shows, type a letter on the keyboard, then press Cancel's keys | The bar rises at the bottom centre of Notepad's screen. The letter lands in Notepad. No taskbar button appears. |
| B2 | Click Stop | Dictate into Notepad, click Stop on the bar, then type a letter | The text is pasted into Notepad; the bar shows "Pasted into Notepad · <style> style" for about 6 s. The letter lands in Notepad. The log has no `focus after a click` line (no restore was needed). |
| B3 | Style and expression for one dictation | Start a dictation, click the Work chip, click the first expression dot (Reserved), say a casual sentence, Stop | Pasted text reads like Work, with no exclamation marks or emoji. History shows the Work style. The next dictation starts on the default style again. |
| B4 | Keys while dictating | Start a dictation; press the style-picker keys (Ctrl+Shift+X) twice and Alt+Shift+Up once; Cancel. Then in VS Code press Alt+Shift+Up | While dictating: the selected chip moves on twice and the expression moves up one dot. Outside a dictation, Alt+Shift+Up does what VS Code normally does. |
| B5 | Use as feedback | Dictate once into Notepad. Start another dictation, click "Use as feedback", say "that was too formal", Stop | The bar shows "Recording feedback" and "About Notepad <time>" with the start of the first dictation. Nothing is pasted. A feedback window opens with the words and the first dictation; Save feedback writes a file in `%APPDATA%\com.voiceflowkeyboard.desktop\feedback-drafts\`. History has no new row. |
| B6 | Feedback key | Settings > Dictation keys: set "Give feedback by voice". Dictate once, then press the feedback keys, speak, press them again | As B5. |
| B7 | A failure stays | Check 7's setup (bad model). Dictate into Notepad, click into another app | The bar says "Nothing was pasted into Notepad" and stays. Paste raw text on the bar: Notepad comes back and gets the words. |
| B8 | Second monitor | Put Notepad on another monitor with different scaling. Dictate. Then Settings > Recording bar > Position: Bottom right; dictate again | The bar appears on Notepad's monitor, sharp and the right size, then at its bottom right. |
| B9 | Quiet microphone | Mute the microphone, start a dictation, wait 4 s | The level goes flat and the bar says "No sound from your microphone". |
| B10 | Turned off | Settings > Recording bar: off. Dictate | No bar; dictation works as before. Turn it back on. |

**macOS (needs a real Mac; CI only compiles it):** with TextEdit in front, start a dictation and click a style chip: TextEdit stays the active app (its name stays in the menu bar) and keeps the caret. The bar is transparent around the pill, shows above a full-screen app and on every Space, and appears on the screen under the mouse.
