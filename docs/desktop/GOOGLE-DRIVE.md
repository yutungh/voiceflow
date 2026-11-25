# Keep your word lists in Google Drive

VoiceFlow Desktop reads its word lists from a folder you choose. With [Google Drive for desktop](https://www.google.com/drive/download/) installed, that folder can live in your Google Drive, so every computer you sign in on has the same words. A shared Drive folder works for a household or a small team, and an AI assistant can refresh your words every day.

This page is for Windows and Mac. The word-list format itself is in [`pack/README.md`](../../pack/README.md); the rules for an assistant are in [Connect your own AI](CONNECT-YOUR-OWN-AI.md).

> **Tested how?** VoiceFlow's own loader code was run against a local folder laid out exactly like this page describes. A real Google Drive folder has **not** been tested yet. See [Limits](#limits).

## What you get

- **The same words on every PC and Mac** that runs VoiceFlow Desktop and Google Drive for desktop.
- **One folder for a household or team:** shared words everyone gets, plus each person's own words.
- **Daily updates from an AI**, picked up by VoiceFlow within the hour.
- **Offline is fine:** VoiceFlow keeps a last good copy of every list on each computer.
- **Not the phone:** the Android keyboard can't read a Drive folder (see [Limits](#limits)).

## 1. Set up the folder

1. Install **Google Drive for desktop** and sign in.
2. Find your Drive on your computer:
   - **Windows:** File Explorer shows a drive called **Google Drive** (often `G:`). Open **My Drive**.
   - **Mac:** Finder's sidebar shows **Google Drive** under **Locations**. Open **My Drive**. (The files sit under `/Users/<you>/Library/CloudStorage/`, and Google doesn't let you move them.)
3. In **My Drive**, make a folder called `VoiceFlow`. Inside it, make `profiles`, and inside that a folder with your name in lower case, for example `sam`.
4. In `profiles/sam/`, create `profile.json` with a plain text editor:

   ```json
   {
     "schema": "voiceflow-profile",
     "schemaVersion": 1,
     "layer": "personal",
     "user": "sam",
     "terms": [],
     "corrections": []
   }
   ```

   Use your own name in `"user"`. In Notepad, choose **Save as type: All files** so the file isn't saved as `profile.json.txt`. Or copy the example folder [`pack/fixtures/demo/`](../../pack/fixtures/demo/) and rename `sam`.
5. **Keep the files on your computer.** Drive for desktop streams files by default (they download only when opened). Right-click the `VoiceFlow` folder and choose **Offline access > Available offline**. If you set Drive to mirror files instead, you can skip this: everything is already on disk.

When you're done, the folder looks like this. VoiceFlow makes the `ops` and `status` folders itself.

```text
VoiceFlow/
  profiles/household/profile.json          words everyone shares (optional)
  profiles/sam/profile.json                Sam's own words
  profiles/sam/generated/profile.json      written by Sam's AI job (optional)
  profiles/sam/ops/<device>-<id>.json      words added in the app (VoiceFlow writes these)
  status/sam/<device>.json                 each computer's sync status (VoiceFlow writes these)
```

## 2. Point VoiceFlow at it

1. **Copy the folder's full path.**
   - **Windows:** open the `VoiceFlow` folder, click the address bar and copy it, for example `G:\My Drive\VoiceFlow`. Leave out any quote marks.
   - **Mac:** select the `VoiceFlow` folder in Finder, hold **Option**, then right-click and choose **Copy "VoiceFlow" as Pathname**. It looks like `/Users/sam/Library/CloudStorage/GoogleDrive-sam@example.com/My Drive/VoiceFlow`. Paste the whole path: VoiceFlow doesn't understand a `~` shortcut.
2. In VoiceFlow Desktop, open **Settings > Word lists** and turn on **Use word lists**.
3. Paste the path into **Folder**. If the path is right, the line **In this folder:** lists the names it found (`sam`).
4. Enter your name under **Whose words**, then **Save**.
5. Open **Vocabulary**: it shows your words. **Settings > Word lists** should say **Word lists up to date**.

VoiceFlow reads the folder when it starts, every hour, and when you press **Refresh now**. It waits for a dictation in progress to finish first.

## 3. Share it with your household or team

Everyone points VoiceFlow at the same folder, each with their own name. Each file has **one writer**:

| File | Who writes it | What it holds |
|---|---|---|
| `profiles/household/profile.json` | One person, the list keeper | Words everyone uses: family names, the pets, the team's product names |
| `profiles/<name>/profile.json` | That person, or their assistant | Their own words; they win over the household list |
| `profiles/<name>/generated/profile.json` | That person's AI job only | Words it pulls from their notes or projects |
| `profiles/<name>/ops/`, `status/<name>/` | VoiceFlow only | Never edit these by hand |

A household file says `"layer": "household"` and `"user": "household"`; a generated file says `"layer": "generated"` and the person's name. The order is **household < generated < personal < words added in the app**: a later one wins when two use the same `id`.

**The person who owns the folder:**

1. In Google Drive, share the `VoiceFlow` folder with each person as **Editor**. Editor is needed: each person's VoiceFlow saves the words they add in the app and a status file in the folder. With **Viewer**, their words still load, but words they add stay on their own computer and **Settings > Word lists** shows an upload error.
2. Create `profiles/household/profile.json` if you want shared words.

**Everyone else:**

1. Go to [drive.google.com](https://drive.google.com), open **Shared with me**, right-click `VoiceFlow`, choose **Organize > Add shortcut**, and pick **My Drive**. A folder shared with you isn't on your computer until you do this; the shortcut then shows up under **My Drive** on your computer.
2. Make your own folder `profiles/<your name>/` with a `profile.json` (step 4 of [Set up the folder](#1-set-up-the-folder)), and mark `VoiceFlow` **Available offline**.
3. Point VoiceFlow at the folder with your own name ([section 2](#2-point-voiceflow-at-it)).

**On Google Workspace,** a shared drive works too: the path is like `G:\Shared drives\<drive name>\VoiceFlow`. Shared drives are always streamed, so mark the folder **Available offline**.

Good to know:

- **Everyone in the folder can open every file in it,** including each other's personal words. Drive shares the whole folder. Keep that in mind when you choose what goes in (see [Privacy](#privacy)).
- **Two computers of the same person are safe on one folder.** Each computer writes only its own edit files and its own status file, named after that computer, and never overwrites anything.
- **Two people saving the same file at once** can leave Drive with two versions. VoiceFlow reads only the file named exactly `profile.json`, so keep one writer per file.

## 4. Let an AI update it every day

Give the AI its own file, `profiles/<you>/generated/profile.json`. It's safe to automate:

- Your own file and the words you add in the app win over it, so a bad run can't undo what you set yourself.
- A file that is broken or doesn't match the format is ignored, and VoiceFlow keeps the last good copy.
- VoiceFlow picks the new file up within the hour, or at once with **Refresh now**.

### Which assistants can do it (checked November 2026)

| Option | Status |
|---|---|
| **An AI agent on your computer, on a schedule** (Claude Code, Codex CLI, or a script), writing into the Drive folder | **Works.** It's an ordinary file write; Drive for desktop uploads it like any other file. The recommended way. |
| **Claude** (claude.ai) with the Google Drive connector and a scheduled task | **Not documented end to end.** The connector can create and upload files to Drive ([Anthropic](https://support.claude.com/en/articles/10166901-use-google-workspace-connectors)), and Cowork scheduled tasks can use connectors ([Anthropic](https://support.claude.com/en/articles/13854387-schedule-recurring-tasks-in-claude-cowork)). Replacing an existing file's contents isn't documented. Not tested with VoiceFlow. |
| **ChatGPT** with the Google Drive app and a scheduled task | **Not documented.** The Drive app can be allowed to create and update files, if your account or admin permits it ([OpenAI](https://help.openai.com/en/articles/10929079-google-drive-app-and-setup-in-chatgpt)). The scheduled-tasks page doesn't list Google Drive among the apps a task can use ([OpenAI](https://help.openai.com/en/articles/10291617-scheduled-tasks-in-chatgpt)). Not tested. |
| **Gemini** with Google Workspace | **Not a fit today.** Scheduled actions can use connected Workspace apps ([Google](https://support.google.com/gemini/answer/16316416)), but writing a file to Drive isn't documented, and Gemini creates Google Docs, Sheets and Slides, not plain `.json` files. |

**A cloud assistant can leave two files.** Google Drive allows two files with the same name in one folder. If an assistant uploads a new `profile.json` next to the old one instead of replacing it, Drive keeps both and VoiceFlow may read the old one. After its first run, check that the folder has exactly one `profile.json`.

### Set up a daily job on your computer

1. Save the prompt below in a text file outside the `VoiceFlow` folder, with your folder, name and source filled in.
2. Schedule it once a day. The computer has to be on at that time.
   - **Claude Desktop:** a local scheduled task, with the `VoiceFlow` folder as its folder. It runs while the app is open and the computer is awake ([Anthropic](https://code.claude.com/docs/en/desktop-scheduled-tasks)).
   - **Claude Code:** run `claude -p "<prompt>" --permission-mode acceptEdits` with the `VoiceFlow` folder as the working folder. `acceptEdits` lets it write and rename files without asking ([Anthropic](https://code.claude.com/docs/en/headless)).
   - **Codex CLI:** `codex exec -C "<folder>" --skip-git-repo-check --sandbox workspace-write "<prompt>"`. By default `codex exec` can't write files; `workspace-write` lets it write inside the folder ([OpenAI](https://learn.chatgpt.com/docs/non-interactive-mode)).
   - Start either command from **Task Scheduler** on Windows, or `cron` or `launchd` on a Mac.
3. Let it run once, then press **Refresh now** and check **Vocabulary**.

### The prompt

It is the generator version of the rules in [Connect your own AI](CONNECT-YOUR-OWN-AI.md#2-tell-your-assistant-the-rules). Fill in the parts in angle brackets.

> You keep my VoiceFlow "generated" word list. The folder is `<folder>`, my VoiceFlow name is `<me>`. Once a day:
>
> 1. Read `<my source: for example my project notes or my contacts' names>` and pick the names VoiceFlow should know: people, projects, products, places, acronyms.
> 2. **Write only `profiles/<me>/generated/profile.json`.** Never touch the household file, my own `profile.json`, anyone else's folder, the `ops/` folder or the `status/` folder.
> 3. The file is `voiceflow-profile` version 1 (schema: `pack/schema/voiceflow-profile.v1.schema.json` in the VoiceFlow repository) with `"layer": "generated"` and `"user": "<me>"`. Stay on version 1.
> 4. **Keep ids stable:** the same name keeps the same `id` every day; a new one gets a new lower-case id (`a-z`, `0-9`, `.`, `_`, `-`). Drop names that are gone from the source.
> 5. **Mostly words, few corrections.** A word (`terms[]`) only helps VoiceFlow hear it; add a correction only for a form that is always wrong, with no chains and no conflicts. Words are one line, at most 60 characters, without `<` or `>`; a `gloss` is at most 80 characters. Give the names I use most a higher `priority`, because each dictation sends only the top 20 words to the speech model.
> 6. **Write the whole file at once:** write it to a temporary file in the same folder whose name starts with a dot, check it is valid JSON that matches the schema, then rename it over `profile.json`.
> 7. **Nothing private:** no phone numbers, addresses, account numbers, health or money details.
> 8. Tell me what changed, one line per entry.

## Privacy

- **Your words are stored in your Google account,** and everyone you share the folder with can read every list in it.
- **Words are sent with each dictation.** The words VoiceFlow picks are sent to your speech-to-text provider and to your tidy-up model with every dictation.
- **An AI job reads its source** (your notes, for example) every time it runs.
- **So keep the lists to names and terms:** no secrets, addresses, numbers, or health details. The status files VoiceFlow writes hold counts and errors, never your words or what you said.

## Limits

- **Phones:** the Android keyboard can't read a Google Drive folder. It gets word lists only through the [sync gateway](../../sync/README.md), a small server you run on a computer that stays on, which serves a folder from that computer. Using a Drive-synced folder as the gateway's folder hasn't been tested. Without a gateway, the phone keeps its own word list.
- **Not tested with a real Google Drive folder.** VoiceFlow's own loader code was run against a local folder with this layout, which checked:
  - a path with a space in it (`My Drive`);
  - a household list plus two people;
  - two computers of the same person adding words to one folder;
  - a half-written file and a missing file (the last good copy stayed in use);
  - a stray `profile (1).json` and a `(1)` copy of an edit file;
  - the whole folder disappearing, as when Drive for desktop isn't running.

  Not tested: Drive for desktop itself (streaming, **Available offline**, shortcut folders, shared drives), saving through a temporary file and a rename on the Drive drive, and the cloud assistants above.
- **Slow first download:** VoiceFlow waits up to 45 seconds for the folder. If a streamed file is still downloading, it keeps using the last good copy and tries again in 5 minutes. **Available offline** avoids this.
- **Up to an hour:** a file the AI writes reaches VoiceFlow at the next hourly check, or when you press **Refresh now**.

## Troubleshooting

**Settings > Word lists** shows the state in one line; **Vocabulary** lists anything that needs a look.

| What you see | Why | What to do |
|---|---|---|
| **Can't reach your word lists** | Drive for desktop isn't running or is signed out, the path is wrong, the folder has no `profiles` folder inside, or a streamed file took longer than 45 seconds to download | Start Drive for desktop and mark `VoiceFlow` **Available offline**. Check the path in **Settings > Word lists** against File Explorer's address bar or Finder. VoiceFlow tries again every 5 minutes; **Refresh now** tries at once. Dictation keeps working with the last good copy. |
| **Your word lists need a look**: `... is missing; using the last good copy` | The file isn't there, or isn't on this computer yet | Mark `VoiceFlow` **Available offline**. Check the name is exactly `profile.json`, not `profile.json.txt` or `profile (1).json`. |
| **Your word lists need a look**: `... was not used: not valid JSON` | The file is half-synced, or an edit broke it | Wait for Drive to finish syncing, then **Refresh now**. If it stays, check the file with the validator in [Connect your own AI](CONNECT-YOUR-OWN-AI.md#5-checking-a-file-before-saving). Your words keep working from the last good copy meanwhile. |
| **Your word lists need a look**: `Couldn't upload an edit` or `Couldn't write this device's status` | You have **Viewer** access to a shared folder | Ask the owner for **Editor** access. Your words still load meanwhile. |
| The AI ran, but the words don't show | VoiceFlow hasn't checked yet, the file is in the wrong place, or its `layer` or `user` is wrong | Press **Refresh now**. **Settings > Word lists** names the file and the problem. The file must be `profiles/<you>/generated/profile.json` with `"layer": "generated"` and your name in `"user"`. |
| **Vocabulary**: `An edit file was skipped (... (1).json)` | Drive made a copy of one of VoiceFlow's edit files | Move the copy with `(1)` out of the `ops` folder. Never change the original. |
| The file turned into a Google Doc | It was opened or converted with Google Docs | VoiceFlow reads only plain `.json` files. Put a plain `profile.json` back (from a copy, or rebuild it), and open it with a text editor, never with Google Docs. |
