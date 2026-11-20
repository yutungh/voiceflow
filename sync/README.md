# vf-sync: the sync gateway

A small HTTP service that serves a VoiceFlow profile folder (the layout in [`../pack/README.md`](../pack/README.md)) to phones and other devices, with one token per device. It runs behind a reverse proxy that terminates HTTPS. MIT, like `pack/`.

- **No merging and no database.** Devices merge for themselves. Every request reads the files as they are on disk at that moment, so files written by other tools (a PC over SMB, a script) show up at once.
- **No dependencies.** Node 22 and its standard library only.
- **The user and device always come from the token.** No route takes a user name, and no path is ever built from anything but fixed names and strict patterns.

## Routes

Every `/v1/` route needs `Authorization: Bearer <token>`. Without a valid one the answer is `401`, whatever the route.

| Route | Does |
|---|---|
| `GET /v1/manifest` | The SHA-256 and size of the household, generated and personal layers, and the names of every edit file in the user's `ops/`. `ETag` / `If-None-Match` → `304`. Status files are left out, so heartbeats don't change it. |
| `GET /v1/layer/{household,generated,personal}` | One layer file (≤ 1 MB). `ETag` / `304`. |
| `GET /v1/ops/<name>.json` | One edit file (≤ 64 KB) of the token's user. |
| `PUT /v1/ops/<name>.json` | Creates an edit, never replaces one. The name must start with the token's device id, `id` must equal the name, `device` must be the token's device, and the body must pass `pack/schema/voiceflow-op.v1.schema.json`. New: `201`. The same bytes again (a retry): `200`. Anything else under that name: `409`. Refused with `507` once the user has `maxOps` edits. |
| `PUT /v1/status` | Replaces `status/<user>/<device>.json` and nothing else. Must pass the status schema and name the token's user and device. |
| `PUT /v1/backup/<name>.json` | A one-time migration backup into `archive/<user>/` (≤ 1 MB, JSON, create-only like edits, named `<device>-…`, at most 5 per device). |
| `GET /v1/release/android` | `{"offer": null}` or `{"offer": {versionCode, versionName, sha256, size, url}}`: the release this device may install (the rollout rule below). |
| `GET /v1/release/android/apk/<versionCode>` | The APK, only while it is this device's offer. |
| `GET /healthz` | `{"ok":true}`, no token. |

Files are read only when they are regular files with one link: symlinks, hard links, FIFOs and folders in place of a file are treated as missing. Only the gateway and trusted writers (the owner’s PC and scripts, NAS admins) should be able to write to the folder.

Who can see what: a token reads the household layer plus its own user's layers and edits, and writes only its own edits, its own status file and its own backups. Another user's files are never listed, hashed, read or named, including in error text.

Errors are `{"error": "..."}`. Schema failures add `errors`: the paths that failed, never the values.

## Limits

| | |
|---|---|
| Layer / edit / status / backup | 1 MB / 64 KB / 64 KB / 1 MB (`413` for a body over the limit, `422` for a file on disk over it) |
| Failed sign-ins | 10 per client address, then one a minute (`429` with `Retry-After`) |
| Requests per device | bursts of 120, then 2 a second; writes: bursts of 30, then one every 2 seconds |
| Bytes written per device | 8 MB at once, refilled at 8 MB a day (`429`) |
| Stored | 20,000 edits per user, 5 backups per device (`507`, checked one write at a time per user); more than 1,000 entries in `releases/android` holds every release |
| In flight | 32 requests at once (`503`), 128 connections |
| APK downloads | one per device and 4 in all at once (`429`); each cut off after 30 minutes |
| Address length | 300 characters (`414`) |
| Timeouts | headers 10 s, receiving a request 30 s, keep-alive 5 s, no progress on a socket 2 min |

All of these can be changed under `limits` in the config (see `DEFAULT_LIMITS` in `src/gateway.mjs`). With `trustProxy`, the client address is the proxy's `X-Real-IP`; run the container on loopback only so nothing else can set it.

## Tokens

The tokens file holds hashes only, one device per line: `<user> <device> <sha256> [created] [label]`. A line starting with `#` is ignored. The file is checked on every request, so issuing or revoking takes effect at once with no restart.

```sh
node bin/vf-sync-token.mjs issue  --file tokens.txt --user sam --device ph1a2b3c4d --out ph1a2b3c4d.token --label phone
node bin/vf-sync-token.mjs revoke --file tokens.txt --device ph1a2b3c4d
node bin/vf-sync-token.mjs list   --file tokens.txt
```

`issue` writes the token (`vfs1.` + 43 characters) to `--out` and never prints it. Hand it to the device, then remove that file. `revoke` comments the line out.

## Releases and the rollout rule

```text
releases/android/<versionCode>/release.json   {"schema":"voiceflow-release","version":1,"versionCode":N,"versionName":"…","apk":"<file>.apk","sha256":"…","size":N}
releases/android/<versionCode>/<file>.apk
releases/android/flags.json                   optional: {"hold":[N…], "force":[N…], "note":"…"}
```

Write `release.json` last. A release is offered only when its APK is there with the stated size; the device checks the SHA-256 and the signing certificate itself.

- Devices of `rollout.canaryUser` get the newest release that isn't held.
- Every other device gets a version only when it is forced, or when every device in `rollout.canaryDevices`:
  - first reported that `versionCode` at least `minAgeHours` (24) ago, by the gateway's clock;
  - sent a status within `freshHours` (2) that still shows it;
  - completed at least one dictation on it;
  - had no errors on it: none in its status `update.errors`, and no write of its refused by the gateway (any `4xx` but `429`). The status file’s `profile.errors` (sync problems, such as the NAS being offline) do not count.
- Missing evidence holds the release, and so does an empty `canaryDevices`. A hold beats a force. A malformed flags file holds everything.
- The evidence lives in `<stateDir>/rollout.json`, outside the shared folder. Other devices only ever learn their own offer.

A status file carries the evidence in an `update` block, which `voiceflow-status` v1 allows as an extra field:

```json
"update": { "versionCode": 43, "dictations": 5, "errors": { "auth": 0, "validation": 0, "install": 0 } }
```

`dictations` and `errors` count since that `versionCode` was installed.

## Configuration

`VF_SYNC_CONFIG` names a JSON file (see [`config.example.json`](config.example.json)):

| Key | |
|---|---|
| `dataRoot` | The profile folder (`profiles/`, `status/`, `archive/`, `releases/`) |
| `tokensFile` | The tokens file; keep it outside the profile folder |
| `stateDir` | Where rollout evidence is kept; outside the profile folder |
| `trustProxy` | Use `X-Real-IP` as the client address |
| `rollout` | `canaryUser`, `canaryDevices`, `minAgeHours`, `freshHours` |
| `listen` | `host`, `port` (default `0.0.0.0:8790` inside the container) |
| `schemaDir` | Default: the bundled `pack/schema` |

## Running it

```sh
npm test                                            # node --test, no install step
docker build -f sync/Dockerfile -t vf-sync:0.1.0 .  # from the repository root
```

[`docker-compose.example.yml`](docker-compose.example.yml) shows the intended deployment: loopback port only, read-only root filesystem, no capabilities, the profile folder as the only data mount, and capped logs. Each request is logged as one JSON line (time, client address, method, route, status, time taken, user and device, and an edit's file name). Tokens, headers and file contents are never logged.
