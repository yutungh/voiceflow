# Keep your word lists in a private GitHub repository

VoiceFlow can read its word lists from a **private GitHub repository**: your computers and your phone stay in sync through GitHub, with no server to run and no Google developer account. An AI agent can update the words by committing to the repository, and the repository's history shows every change, so you can review or undo what the AI did.

The word-list format is in [`pack/README.md`](../pack/README.md); the rules for an assistant are in [Connect your own AI](desktop/CONNECT-YOUR-OWN-AI.md). Other ways to share the lists: [a folder or Google Drive](desktop/GOOGLE-DRIVE.md) (computers only) and the [sync gateway](../sync/README.md) (a small server you run).

| | |
|---|---|
| **VoiceFlow Desktop** | From **0.9.19** (Windows and Mac) |
| **Android keyboard** | **Not released yet.** The phone side is built and waiting to ship; until then the phone keeps its own word list. [Phone setup](#5-set-up-the-phone-once-released) describes what is coming. |
| **Tested how?** | Against a real private repository on GitHub: reading, a re-check with nothing changed, an edit file added by another device, an upload repeated after a lost answer, a status-file conflict, a refused token and no network. The token used was the GitHub CLI's sign-in token; neither a fine-grained token made on GitHub's website (what this page tells you to make) nor a classic one has been tested yet. See [Limits](#limits). |

## What you get

- **The same words on every computer and phone**, wherever they are; devices don't need to be online at the same time.
- **Offline is fine:** each device keeps a last good copy of every list and dictates with it.
- **A history of every change:** who or what changed a word, when, and a way to undo it.
- **AI updates by commit:** an agent on your computer edits a file and pushes; VoiceFlow picks it up within the hour.
- **A cost of one request an hour** when nothing changed (GitHub doesn't count it against your limit).

## 1. Make the repository

1. Sign in to [github.com](https://github.com) (a free account is enough).
2. Click **+** at the top right, then **New repository**.
3. Name it, for example `voiceflow-words`, and choose **Private**. Tick **Add a README file**, then **Create repository**.
4. Add the two starting files from VoiceFlow's [template](../pack/template/) (every word in them is invented):
   1. In your new repository, click **Add file > Create new file**.
   2. In the name box, type `profiles/household/profile.json`. Typing `/` makes the folders.
   3. Open [the template's household file](../pack/template/profiles/household/profile.json), click **Raw**, copy everything and paste it in.
   4. Click **Commit changes**, then **Commit changes** again.
   5. Do the same for `profiles/<your name>/profile.json` with [the template's personal file](../pack/template/profiles/sam/profile.json). Change `"user": "sam"` to your name. A name is up to 32 lower-case letters, digits, `-` or `_`, starting with a letter or digit (and not `household`).
5. Replace the invented words with yours now, or later from VoiceFlow (**Vocabulary**) or an AI ([section 6](#6-let-an-ai-agent-update-the-words)).

When VoiceFlow has been running for a while, the repository looks like this. VoiceFlow makes the `ops` and `status` folders itself.

```text
profiles/household/profile.json          words everyone shares (optional)
profiles/sam/profile.json                Sam's own words
profiles/sam/generated/profile.json      written by Sam's AI job (optional)
profiles/sam/ops/<device>-<id>.json      words and spellings added in the app (VoiceFlow writes these)
status/sam/<device>.json                 each device's sync status (VoiceFlow writes these)
```

**Already using a folder?** Press **Refresh now** in **Settings > Word lists** first, so nothing waits to upload. Then put a copy of the folder's `profiles` folder, `ops` folders included, into the repository (on GitHub: **Add file > Upload files**, or with git). The edit files move as they are; never change them.

## 2. Make an access token for each device

A token lets one device read (and, for a computer, write) **this one repository** and nothing else of yours. Make **one token per device**: if a phone is lost, you delete its token and nothing else changes.

1. Click your profile picture at the top right of GitHub, then **Settings**.
2. In the left sidebar, click **Developer settings**.
3. Under **Personal access tokens**, click **Fine-grained tokens**, then **Generate new token**.
4. **Token name:** what it's for, for example `VoiceFlow on the office PC`.
5. **Expiration:** GitHub suggests 30 days. A token that runs out stops the sync until you add a new one, so choose a date you'll remember (up to a year) or **No expiration**, and put a reminder in your calendar.
6. **Resource owner:** you.
7. **Repository access:** **Only select repositories**, then pick your word-list repository in **Selected repositories**.
8. **Permissions:** under **Repository permissions**, set **Contents** to:
   - **Read and write** for a computer (it uploads the words you fix and its status);
   - **Read-only** for a phone (the phone writes nothing).

   GitHub adds **Metadata: Read-only** by itself. Grant nothing else.
9. Click **Generate token** and copy it (it starts with `github_pat_`). GitHub shows it only once. Paste it straight into VoiceFlow; don't save it in a file or a chat.

**A shortcut** fills in the name, the description and the Contents permission for you (you still pick the repository and the expiration): [token for a computer](https://github.com/settings/personal-access-tokens/new?name=VoiceFlow+computer&description=VoiceFlow+word+lists&contents=write), [token for a phone](https://github.com/settings/personal-access-tokens/new?name=VoiceFlow+phone&description=VoiceFlow+word+lists&contents=read). Check that **Contents** shows the right level before you click **Generate token**.

GitHub's own instructions: [Managing your personal access tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).

## 3. Set up VoiceFlow Desktop

1. Open **Settings > Word lists** and turn on **Use word lists**.
2. Under **Where they are**, choose **A private GitHub repository**.
3. **Repository:** `your-github-name/voiceflow-words` (a pasted link works too). **Branch:** `main`, unless you use another one.
4. **Access token:** paste the computer's token.
5. Click **Test connection**. It reads the repository and your lists and writes nothing. It says whether the repository is private, which names it has (`sam`), and whether each list reads fine. Writing is checked at the first upload.
6. **Whose words:** your name. **Upload this computer's changes** stays on unless this computer should only read.
7. Click **Save**. **Settings > Word lists** should say **Word lists up to date**. To check the round trip, add a word in **Vocabulary**: within a minute, a new file appears in `profiles/<you>/ops/` on GitHub.

The token is kept in the computer's credential store (Windows Credential Manager, the macOS Keychain), never in a file, and VoiceFlow never shows it again. **Remove token** deletes it.

VoiceFlow checks when it starts, every hour, and when you press **Refresh now** (every 5 minutes while GitHub can't be reached). It waits for a dictation in progress to finish first.

## 4. Share it with your household

Everyone's lists can live in one repository, each person under their own name, with one shared household file. The order is **household < generated < personal < words added in the app**: a later one wins when two use the same `id`.

- **The repository's owner makes every token,** one per device, including the other people's. GitHub doesn't let a fine-grained token reach a repository its maker was only invited to ([GitHub](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens), "Fine-grained personal access tokens limitations"), so the other people can't make their own.
- **Everyone with a token can read every list in the repository,** and a read-and-write token can change any file in it. Share a repository only with people who may see each other's words.
- **One writer per file:** a person (or their AI) writes their own `profile.json`, one person keeps the household file, and only VoiceFlow writes `ops/` and `status/`.

## 5. Set up the phone (once released)

The Android keyboard's GitHub support is built but **not released yet**. When it ships:

1. On GitHub, make a **read-only** token for the phone ([section 2](#2-make-an-access-token-for-each-device)).
2. In the VoiceFlow keyboard app, open **Word lists**, tap the line **Shared lists: …** under the introduction, and choose **Private GitHub repository** on the **Word lists source** screen.
3. Enter the repository, the branch, your name and the phone's token, then tap **Test connection** and **Save**. **Forget GitHub** removes the settings and the token from the phone.

The phone reads the lists when the keyboard opens (at most every 15 minutes) and every hour, keeps a last good copy, and writes nothing to the repository. A phone that uses the sync gateway keeps using it unless you switch it here.

## 6. Let an AI agent update the words

Give the AI its own file, `profiles/<you>/generated/profile.json`. Your own file and the words you add in the app win over it, and a file that is broken or doesn't match the format is ignored (the last good copy stays). A valid but wrong entry can still affect dictation, so read what the AI reports, and use the repository's **History** to see and undo its changes.

### Which assistants can do it (checked November 2026)

| Option | Status |
|---|---|
| **An AI agent on your computer** (Claude Code, Codex CLI, or a script) working in a clone of the repository | **Works.** It edits the file, then commits and pushes with git. The recommended way. |
| **ChatGPT** (chat) with its GitHub app | **No.** OpenAI: "The GitHub app in ChatGPT only lets you read from your repositories"; to push code it points to Codex ([OpenAI](https://help.openai.com/en/articles/11145903-connecting-github-to-chatgpt)). |
| **Claude** (claude.ai chat) with its GitHub integration | **No.** Anthropic: Claude "reads file names and contents only"; to edit code and open pull requests it points to Claude Code ([Anthropic](https://claude.com/docs/connectors/github)). |
| **Codex** (cloud) or **Claude Code** (on the web) | **Possible, not tested with VoiceFlow.** Both vendors name these as the tools that change a repository (same sources). They usually propose a pull request: VoiceFlow reads only the branch you set, so the change arrives once you merge it. |

### Set up a daily job on your computer

You need git, the assistant installed and signed in, and the computer on at the time.

1. Clone the repository once, for example into `Documents/voiceflow-words` (`git clone https://github.com/<you>/voiceflow-words.git`). git asks you to sign in to GitHub the first time.
2. Make a folder **outside** the clone for the job, for example `Documents/voiceflow-job`, with `prompt.txt` ([the prompt below](#the-prompt)) and a copy of [the schema](../pack/schema/voiceflow-profile.v1.schema.json) as `voiceflow-profile.v1.schema.json`.
3. Test the command once by hand, then schedule it once a day, from the clone's folder:
   - **Claude Code** (`acceptEdits` lets it write files; git needs its own permission):

     ```sh
     cat ~/Documents/voiceflow-job/prompt.txt | claude -p "Follow these instructions." --permission-mode acceptEdits --allowedTools "Bash(git *)"
     ```

   - **Codex CLI:**

     ```sh
     cat ~/Documents/voiceflow-job/prompt.txt | codex exec -C ~/Documents/voiceflow-words --sandbox workspace-write -
     ```

     Codex's sandbox may block network access, so `git push` can fail inside it. If it does, let the job only commit, and push from the scheduled script afterwards (`git -C ~/Documents/voiceflow-words push`).
   - On Windows, use `Get-Content "$HOME\Documents\voiceflow-job\prompt.txt" -Raw` in place of `cat ...` and start the job from **Task Scheduler**; on a Mac, `cron` or `launchd`.
4. After the first run, open the repository's **Commits** on GitHub, press **Refresh now** in VoiceFlow and check **Vocabulary**.

### The prompt

Fill in the parts in angle brackets.

> You keep my VoiceFlow "generated" word list in the git repository in the current folder. My VoiceFlow name is `<me>`. Once a day:
>
> 1. Run `git pull --rebase` first. VoiceFlow adds files to this repository from my devices.
> 2. Read `<my source: for example my project notes or my contacts' names>` and pick the names VoiceFlow should know: people, projects, products, places, acronyms.
> 3. **Change only `profiles/<me>/generated/profile.json`.** Never touch the household file, my own `profile.json`, anyone else's folder, any `ops/` folder or the `status/` folder.
> 4. The file is `voiceflow-profile` version 1 (schema: `<job folder>/voiceflow-profile.v1.schema.json`) with `"layer": "generated"` and `"user": "<me>"`. Stay on version 1.
> 5. **Keep ids stable:** the same name keeps the same `id` every day; a new one gets a new lower-case id (`a-z`, `0-9`, `.`, `_`, `-`). Drop names that are gone from the source.
> 6. **Mostly words, few corrections.** A word (`terms[]`) only helps VoiceFlow hear it; add a correction only for a form that is always wrong, with no chains and no conflicts. Words are one line, at most 60 characters, without `<` or `>`; a `gloss` is at most 80 characters. Give the names I use most a higher `priority`: each dictation sends only the top 20 words to the speech model.
> 7. Check the file is valid JSON that matches the schema. If nothing changed, stop.
> 8. **Nothing private:** no phone numbers, addresses, account numbers, health or money details.
> 9. Commit only that file with the message `Generated words: <one line on what changed>`, then `git push`. If the push is refused, run `git pull --rebase` and push again.
> 10. Tell me what changed, one line per entry.

## Privacy

- **The lists are stored on GitHub's servers,** in your account. A private repository is visible to you and the people you add to it; when GitHub itself may access private repositories is set out in its [Terms of Service](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) (section E) and [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). Never make the repository public: **Test connection** warns if it is.
- **Words are sent with each dictation:** up to 20 go to your speech-to-text provider, and a longer list to your tidy-up model when a tidy-up style runs.
- **Keep the lists to names and terms:** no secrets, addresses, numbers or health details. Deleted words stay in the repository's history.
- **What VoiceFlow writes:** edit files (the words and spellings you add in the app) and one status file per computer with counts and errors, never what you said. Commit messages are like `VoiceFlow: add edit pc1a2b3c4d-…` and `VoiceFlow: update status pc1a2b3c4d`, with no words in them.

## If a token leaks, or a device is lost

1. On GitHub, open **Settings > Developer settings > Personal access tokens > Fine-grained tokens** and click **Delete** next to that device's token. It stops working at once.
2. Make a new token for the device if you still have it ([section 2](#2-make-an-access-token-for-each-device)).
3. Check the repository's **Commits** for changes you don't recognise, and revert them.

What a leaked token can do: a computer's token can read and change the files in **this repository only**; a phone's read-only token can only read them. Neither reaches your other private repositories or your account settings. (Every fine-grained token can also read public repositories, like anyone on the internet.) GitHub deletes tokens that haven't been used for a year.

## Limits

- **Not tested with a fine-grained token made on GitHub's website.** The integration tests used the GitHub CLI's sign-in token, which reaches the test repository the same way. If **Test connection** fails with a new fine-grained token, check the repository is selected under **Repository access** and **Contents** is set.
- **Phones:** not released yet ([section 5](#5-set-up-the-phone-once-released)).
- **Up to an hour:** a change on GitHub reaches a device at its next hourly check, or at **Refresh now**.
- **History grows:** each word added in the app is one commit, and each computer writes its status when something changed and otherwise once a day.
- **Size:** each list file is at most 1 MB and each edit file 64 KB. If the repository grows past what GitHub lists at once (100,000 files or 7 MB of listing), VoiceFlow stops reading it and keeps the last good copy: keep only the word lists in this repository.
- **Uploads per check:** a computer sends at most 20 edits per check and gives each check 40 seconds; the rest go at the next one.
- **Rate limits:** if GitHub asks VoiceFlow to wait, it waits the time GitHub gives, then tries again.
- **Branch names** may use letters, digits, `.`, `_`, `-` and `/`.

## Troubleshooting

**Settings > Word lists** shows the state in one line; **Vocabulary** lists anything that needs a look.

| What you see | Why | What to do |
|---|---|---|
| **GitHub didn't accept the token** | The token expired, was deleted, or was pasted incompletely | Make a new token ([section 2](#2-make-an-access-token-for-each-device)) and paste it in **Settings > Word lists**. Dictation keeps working with the last good copy. |
| **GitHub can't find your word-list repository** | A typo in the repository or branch, or the token wasn't given this repository | Check the names, and that the token lists the repository under **Repository access**. |
| **Your word-list repository is empty** | There is no `profiles` folder on that branch | Add the template files ([section 1](#1-make-the-repository)). |
| **Can't reach your word lists** | No internet, or GitHub is down | Nothing to do: VoiceFlow tries again every 5 minutes. |
| **GitHub asked VoiceFlow to wait** | Too many requests from your account (often another tool sharing the same limit) | VoiceFlow waits the time GitHub gives, then continues. |
| **GitHub refused uploads; your edits stay on this computer** | The token can only read, or doesn't reach this repository for writing | Give the computer a token with **Contents: Read and write**, or turn off **Upload this computer's changes**. |
| **Your word lists need a look**: `... was not used: ...` | A list file is broken or doesn't match the format | Fix it on GitHub (the message names the file), or revert the commit that broke it. The last good copy is used meanwhile. |
| **Vocabulary**: `An edit file was skipped` | An edit file in `ops/` isn't one VoiceFlow can read | Don't edit files in `ops/`; if one was changed, restore it from the history. |
