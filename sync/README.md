# vf-sync: the sync gateway

A small HTTP service that serves a VoiceLnq profile folder (the layout in [`../pack/README.md`](../pack/README.md)) to phones and other devices, with one token per device. It runs behind a reverse proxy that terminates HTTPS. MIT, like `pack/`.

- **No merging and no database.** Devices merge for themselves. Every request reads the files as they are on disk at that moment, so files written by other tools (a PC over SMB, a script) show up at once.
- **No dependencies.** Node 22 and its standard library only.
- **The user and device always come from the token.** No route takes a user name, and no path is ever built from anything but fixed names and strict patterns.

## Set up your own gateway

This is the whole path, from nothing to a phone syncing. The gateway is optional: the desktop app and the Android keyboard both work without it. You need it only to share one set of word lists between a phone and other devices.

**What you need**

- A computer that stays on: a home server, a NAS that runs Docker, a small VPS or a Raspberry Pi. It needs Docker, or Node.js 22 or newer.
- A host name that points at it, and HTTPS with a certificate Android trusts (for example from Let's Encrypt). The phone refuses plain `http://`, a path after the host name and a login in the address.
- The VoiceLnq keyboard 0.8.2 or newer on the phone, a USB cable and `adb` on the computer you plug it into.

### 1. Get the code and build it

```sh
git clone -c core.longpaths=true https://github.com/yutungh/voicelnq
cd voicelnq
docker build -f sync/Dockerfile -t vf-sync:0.1.0 .      # from the repository root
```

No Docker? Run it directly with Node 22 or newer: `VF_SYNC_CONFIG=/path/to/config.json node sync/bin/vf-sync.mjs`. It has no dependencies to install.

### 2. Make the data folder

The gateway serves one folder, `dataRoot` in the config. It is the same folder the desktop app reads as its word-list folder, in the layout described in [`../pack/README.md`](../pack/README.md):

```text
<data folder>/
  profiles/household/profile.json             words everyone shares (optional)
  profiles/<user>/profile.json                this user's own words
  profiles/<user>/generated/profile.json      written by a tool, optional
  profiles/<user>/ops/<device>-<uuid>.json    edits from devices; the gateway creates this
  status/<user>/<device>.json                 each device's status; the gateway writes it
  archive/<user>/                             one-time backups; the gateway creates this
  releases/android/<versionCode>/...          optional APKs to offer phones (see below)
```

Start from the example folder: copy [`../pack/fixtures/demo/`](../pack/fixtures/demo/) and rename `sam` to your own lower-case user name, or write a minimal `profiles/<user>/profile.json` as shown in [`../docs/desktop/CONNECT-YOUR-OWN-AI.md`](../docs/desktop/CONNECT-YOUR-OWN-AI.md). The user running the container must be able to read the folder and write to `profiles/`, `status/` and `archive/`.

### 3. Configure and start it

```sh
mkdir -p config state
cp sync/config.example.json config/config.json
touch config/tokens.txt
```

Edit `config/config.json`: the paths are the container's (`/data`, `/config/tokens.txt`, `/state`), so you normally change only `rollout.canaryUser` to your user name. `canaryDevices` can stay empty unless you use the [release rollout](#releases-and-the-rollout-rule); set `rollout.staged` to `false` to offer every phone each new release at once instead. Then copy [`docker-compose.example.yml`](docker-compose.example.yml) next to those folders, set the data-folder path and the `user:` line, and start it:

```sh
docker compose -f docker-compose.example.yml up -d
curl http://127.0.0.1:8790/healthz        # {"ok":true}
```

The port is bound to the server's loopback address on purpose: only the reverse proxy in the next step should reach it.

### 4. Put HTTPS in front of it

Any reverse proxy that terminates TLS works. Give the gateway a host name of its own (`https://sync.example.com`, not a sub-path), and pass the client address in `X-Real-IP`, which `trustProxy` reads for the sign-in rate limit. If you have no proxy that sets it, set `"trustProxy": false`.

Caddy (gets its certificate automatically):

```text
sync.example.com {
    reverse_proxy 127.0.0.1:8790 {
        header_up X-Real-IP {remote_host}
    }
}
```

nginx (with a certificate you already have, for example from certbot):

```nginx
server {
    listen 443 ssl;
    server_name sync.example.com;
    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;
    client_max_body_size 2m;

    location / {
        proxy_pass http://127.0.0.1:8790;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Host $host;
    }
}
```

Check from another machine: `curl https://sync.example.com/healthz`.

### 5. Connect a phone

The phone makes its own device id the first time it is asked, so you ask it first, then issue a token for that id, then hand the token over. Plug the phone in over USB with USB debugging on and the screen unlocked. The receiver is protected by Android's `DUMP` permission, which only the adb shell holds, so no other app can enrol or reset the phone.

1. Ask the phone for its device id (`ph` and 8 hex characters):

   ```sh
   adb shell am broadcast -n com.voiceflowkeyboard.ime/.SyncCommandReceiver -a com.voiceflowkeyboard.ime.action.SYNC_INFO
   ```

   The answer is in the broadcast's `data=` line: `device=ph1a2b3c4d ... enrolled=no`.

2. On the server, issue a token for that user and device (this writes the token to a file and never prints it):

   ```sh
   node sync/bin/vf-sync-token.mjs issue --file config/tokens.txt --user sam --device ph1a2b3c4d --out ph1a2b3c4d.token --label phone
   ```

3. Enrol the phone. The three extras are `user`, `gateway` (the bare `https://` address from step 4) and `token` (the contents of the file):

   ```sh
   adb shell am broadcast -n com.voiceflowkeyboard.ime/.SyncCommandReceiver \
     -a com.voiceflowkeyboard.ime.action.ENROL \
     --es user sam --es gateway https://sync.example.com --es token "$(cat ph1a2b3c4d.token)"
   ```

   The reply is `enrolled device=ph1a2b3c4d user=sam gateway=https://sync.example.com`. It never repeats the token. A refusal names the field that was wrong, never its value. On Windows PowerShell, use `--es token (Get-Content ph1a2b3c4d.token)` and a backtick instead of `\` for line breaks.

4. Delete the token file. Check the phone with the `SYNC_INFO` action again (`enrolled=yes`), or ask for an immediate run with `-a com.voiceflowkeyboard.ime.action.SYNC_NOW`. The phone then downloads the word lists, keeps an offline copy and sends its status file back. It sends no word edits: words added on the phone stay in its own list.

Other actions the receiver understands: `RESET_STYLES` and `STYLE_DIGEST`, used for maintenance.

### 6. Connect the desktop app

The desktop app does not use the gateway or a token. It reads the data folder itself. In **Settings > Word lists**, turn on **Use word lists**, enter the folder (a path on the PC, or the server's share of the same folder, for example `\\server\voiceflow` or `/Volumes/voiceflow`) and your user name under **Whose words**. So the desktop needs either a profile folder it can open, or none at all (it works without word lists), and the phone needs the gateway. (Without a gateway, a [private GitHub repository](../docs/GITHUB-SYNC.md) can hold the word lists for the desktop app and, from 0.9.2, the phone.) If the gateway's data folder is on another machine, share it with the PC (SMB or a synced folder) and give the PC write access to `profiles/<user>/` and `status/<user>/` only.

### 7. Revoke a device

Lost a phone, or retiring one? Revoke its token. It takes effect on the next request, with no restart:

```sh
node sync/bin/vf-sync-token.mjs revoke --file config/tokens.txt --device ph1a2b3c4d
node sync/bin/vf-sync-token.mjs list   --file config/tokens.txt
```

The device's word lists and edits stay in the data folder. See [Tokens](#tokens) for the file format.
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

Files are read only when they are regular files with one link: symlinks, hard links, FIFOs and folders in place of a file are treated as missing. Only the gateway and trusted writers (the owner’s PC and scripts, server admins) should be able to write to the folder.

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

`rollout.staged` picks one of two modes:

- **`"staged": false`: everyone at once.** Every device gets the newest release that isn't held, as soon as it is published. `force`, `canaryUser`, `canaryDevices` and the evidence below play no part. Use `hold` to stop a bad version.
- **`"staged": true` (the default when the key is absent): canary first.** The rules below.

In staged mode:

- Devices of `rollout.canaryUser` get the newest release that isn't held.
- Every other device gets a version only when it is forced, or when every device in `rollout.canaryDevices`:
  - first reported that `versionCode` at least `minAgeHours` (24) ago, by the gateway's clock;
  - sent a status within `freshHours` (2) that still shows it;
  - completed at least one dictation on it;
  - had no errors on it: none in its status `update.errors`, and no write of its refused by the gateway (any `4xx` but `429`). The status file’s `profile.errors` (sync problems, such as the server being offline) do not count.
- Missing evidence holds the release, and so does an empty `canaryDevices`. A hold beats a force.
- The evidence lives in `<stateDir>/rollout.json`, outside the shared folder. Other devices only ever learn their own offer.

In both modes a held version is offered to nobody, and a malformed flags file holds everything. The gateway keeps recording canary evidence when `staged` is false, so switching back to staged mode works with what it has. A `staged` value that isn't `true` or `false` stops the gateway at start.

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
| `rollout` | `staged` (default `true`; `false` offers every device the newest non-held release at once), and for staged mode `canaryUser`, `canaryDevices`, `minAgeHours`, `freshHours` |
| `listen` | `host`, `port` (default `0.0.0.0:8790` inside the container) |
| `schemaDir` | Default: the bundled `pack/schema` |

## Running it

```sh
npm test                                            # node --test, no install step
docker build -f sync/Dockerfile -t vf-sync:0.1.0 .  # from the repository root
```

[`docker-compose.example.yml`](docker-compose.example.yml) shows the intended deployment: loopback port only, read-only root filesystem, no capabilities, the profile folder as the only data mount, and capped logs. Each request is logged as one JSON line (time, client address, method, route, status, time taken, user and device, and an edit's file name). Tokens, headers and file contents are never logged.
