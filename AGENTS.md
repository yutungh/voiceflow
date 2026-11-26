# VoiceLnq: notes for agents and contributors

These rules apply to anyone working in this repository, human or AI agent. Working in `desktop/`? Read [`desktop/AGENTS.md`](desktop/AGENTS.md) too. Build, test and pull request steps are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## This repository is public

Never commit personal data. That means:

- real people's names, addresses, phone numbers or email addresses;
- home network or server hostnames and IP addresses;
- device IDs or serial numbers;
- local user paths (`C:\Users\<name>\...`, `/home/<name>/...`, `/Users/<name>/...`);
- real dictations, transcripts, voice recordings or personal word lists;
- work or employer terms;
- API keys, tokens or other secrets.

This covers everything that reaches GitHub: code, tests, fixtures, screenshots and images, docs, commit messages and pull request text.

## Use invented examples

- Names, sentences and words in tests, fixtures, docs and screenshots are obviously made up ("Alex Example", "Buy oat milk and call the plumber").
- Before committing a screenshot or image, look for visible sample text: dictations, word lists, names, notifications, file paths.

## The maintainer's privacy check

The maintainer runs a local pre-push check against a private term list ([`CONTRIBUTING.md`, Maintainer Privacy Guard](CONTRIBUTING.md#maintainer-privacy-guard-optional-maintainers-only)). Contributors don't need it.

- Where it is installed, it must pass. Never push with `--no-verify`.
- A finding means the content changes. Never print or copy the term list.

## Release binaries come from CI only

Public release files (APKs, installers, native libraries) are built by the GitHub Actions workflows in `.github/workflows/`, never on a developer's machine. A local build embeds local paths, user names included.

If a local build ever has to be published:

- **Rust:** build with `--remap-path-prefix` for the cargo home, the rustup home, the source directory and the target directory.
- **Native C/C++ libraries** (for example the Android Rime library built by `scripts/rime-android/build.sh`): build with `-ffile-prefix-map` and `-fdebug-prefix-map`.
- **Check before publishing:** search every built file's strings for user and home paths (`C:\Users\`, `/home/`, `/Users/`). Publish only when nothing is found.

## History is not rewritten

Old content in git history stays. Fix problems forward with a new commit on `main`; don't rewrite history or force-push to remove them.
