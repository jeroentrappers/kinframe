# Remote help: a temporary tailnet tunnel to a frame for the server operator

Status: **building** (2026-11-20). A prototype ran on a Denver PFF-1024 (§8).

## 1. Summary

Frames sit in living rooms far from the person who set them up. When something is wrong, the only way to look
inside today is a USB cable. Remote help lets the server operator switch on a temporary, encrypted tunnel from the
frame to the server operator's tailnet (Tailscale clients against a self-hosted Headscale), so the operator can use
ADB on the frame as if it were plugged in. It is off by default, ends by itself, everyone in the family can see
when it is on, and the frame's owner or anyone at the frame can stop it.

It is not a general VPN for frames and adds no attack surface while it is off: frames keep making only outgoing
HTTPS calls to their server.

## 2. Principles

1. **Started by the operator only.** Only the server operator (`kinframe:operate`) can start a session, for 1, 8 or
   24 hours, with a reason (usually: the family asked for help by mail or phone). Nobody in the family can start
   one, so a family account that is taken over can't open a tunnel either. When it ends, the frame leaves the
   tailnet and forgets its key.
2. **Visible and stoppable.** The frame shows "Remote help is on until 18:00" in a corner while a session runs; the
   frame's page in the apps shows it with the operator's reason; every member of the frame gets a notification when
   it starts and ends; every session is logged. The frame's owner and admins (app) and anyone at the frame (badge,
   frame PIN if set) can stop it at once.
3. **Least access.** The tailnet policy lets only the operator's support devices reach frames, only on the ADB
   port. Frames reach nothing on the tailnet, not even each other.
4. **ADB keeps its own lock.** Only the operator's ADB key is added, for the session, and removed after.
5. **Optional for operators.** A server without Headscale configured shows nothing of this.

## 3. User stories

1. *Asking for help.* Oma's frame shows no new photos. Ben (owner) mails the operator. The operator opens Settings →
   Remote help on the web, finds Oma's frame (by name, id or a member's e-mail), picks 8 hours, types the reason
   "Ben asked by mail: no new photos" and taps Start.
2. *The family.* Ben and the other members of the frame get "Remote help started on Oma's frame by the kinframe
   operator, until 02:15". The frame's page shows the same with the reason and, for owner/admins, Stop.
3. *The frame.* Within a minute the frame joins the tailnet and shows a small badge "Remote help is on until 02:15".
   Oma can tap it: "The kinframe operator can look at this frame to help. Stop?" (with the frame PIN if one is set).
4. *The operator.* The operator's remote-help page shows the session as *on* with the frame's tailnet name and IP
   and a copyable `adb connect kinframe-<serial>:5555`.
5. *Ending.* The session ends at the chosen time, or when the operator, an owner/admin or someone at the frame taps
   Stop, or when the frame is unpaired. The operator's ADB connection drops, the badge disappears, the log says who
   ended it, members get "Remote help ended".

## 4. Who may do what

| Action | Operator | Frame owner/admin | Other frame members | At the frame (PIN if set) |
|---|---|---|---|---|
| Start a session | ✓ | – | – | – |
| See that a session runs | ✓ | ✓ (notification + frame page) | ✓ (notification + frame page) | ✓ (badge) |
| Stop a session | ✓ | ✓ | – | ✓ |
| Connect | ✓ (support devices) | – | – | – |

A frame can have one session at a time. Only frames whose latest heartbeat lists the `remote_help` feature (the
GitHub build on a rooted frame) can get one: the operator's search shows the others as "not available (Play build or
no root)" and starting returns `409 conflict` with `remote_help_unsupported`.

## 5. How it works

### 5.1 Server configuration

| Variable | Meaning |
|---|---|
| `KINFRAME_REMOTE_HELP` | `on` to enable (default off) |
| `KINFRAME_HEADSCALE_URL` | Headscale control URL, e.g. `https://headscale.appmire.be` (also the frame's `--login-server`) |
| `KINFRAME_HEADSCALE_API_KEY` | Headscale API key (`headscale apikeys create`), sent as `Authorization: Bearer` |
| `KINFRAME_HEADSCALE_USER` | Headscale user name frames register under, e.g. `kinframe-frames` (looked up to its id via `GET /api/v1/user`) |
| `KINFRAME_REMOTE_HELP_ADB_KEY` | The operator's ADB public key (one line of `~/.android/adbkey.pub`) |
| `KINFRAME_REMOTE_HELP_POLICY_CHECKED` | `1`: the operator confirms the Headscale ACL of §5.3 is in place |
| `KINFRAME_REMOTE_HELP_TAILSCALE_VERSION` | Optional; default `1.104.7`, whose built-in SHA-256s are below |

All of them set (and the policy checked) → discovery and `/v1/me` for operators gain `remote_help: true`; otherwise
the endpoints return `503 feature_disabled`. Built-in hashes for 1.104.7 (from `pkgs.tailscale.com/stable/…tgz.sha256`):
`arm` `1c753fef45d497491081db37552b7a2a42c7932292743bf6936cb1a42b6bceb0`, `arm64`
`d6bed2720310b328b861f943d7b4016c5358a9bad60575eb51e8143b79068484`. Another version needs
`KINFRAME_REMOTE_HELP_TAILSCALE_SHA256_ARM` and `…_ARM64`. Secrets (`API_KEY`, auth keys) are sealed at rest
(`KINFRAME_SECRETS_KEY`).

### 5.2 API

**Operator** (`kinframe:operate`, tag `operator`):

- `POST /v1/operator/frames/{frameId}/remote-help` `{hours: 1|8|24, reason: string (1–500)}` → `201 RemoteHelpSession`.
  Asks Headscale for a pre-auth key (`POST /api/v1/preauthkey` with `user` (id), `reusable: false`,
  `ephemeral: true`, `expiration` = session end, `aclTags: ["tag:kinframe-frame"]`), stores the session and puts a
  `remote_help` change on the frame's feed. `409 conflict` when a session is already active or the frame lacks the
  feature; `502 upstream_error` when Headscale fails (no session is stored).
- `DELETE /v1/operator/frames/{frameId}/remote-help` → `204`. Ends the active session.
- `GET /v1/operator/remote-help?cursor&limit` → sessions, active first, then newest (kept 2 years).
- The operator's frame search (`operatorOwnershipSearch`) gains `remote_help_supported` per frame.

**Frame members** (any member; Stop needs owner/admin):

- `GET /v1/frames/{frameId}/remote-help` → `200 RemoteHelpSession` (active one) or `204` when none.
- `DELETE /v1/frames/{frameId}/remote-help` → `204` (owner/admin; `403` for others).
- `FrameSummary`/frame detail gain `remote_help: {ends_at, reason} | null` so frame lists can show a badge.

**Device:**

- `DELETE /v1/device/remote-help` → `204`. Stop tapped at the frame.
- Heartbeat gains `remote_help: {session_id, state: starting|on|failed|off, ip: string|null, error: string|null}`
  and the frame lists feature `remote_help` in `features` when it can do it. The first heartbeat with
  `state: on` (or `failed`) for the session clears the stored pre-auth key and the change's `auth_key`
  (a new `remote_help` change without it); `on` stores the IP for the operator and sends the push "Remote help is on".
  `failed` ends the session with `ended_by: frame` and the error in the log.

**`RemoteHelpSession`:** `id, frame_id, frame_name, state (pending|on|ended), reason, started_by_name,
started_at, ends_at, ended_at|null, ended_by (operator|owner|frame|expired|unpaired|failed)|null, ended_by_name|null,
hostname, tailnet_ip|null, adb_command ("adb connect <hostname>:5555"), error|null`. Members see the same minus
`error` and `tailnet_ip`.

**Change `remote_help`** (entity `id` = the frame), payload `remote_help`, or `null` = no session (clean up):

```json
{
  "session_id": "…", "state": "active", "ends_at": "2026-11-20T18:00:00Z",
  "login_server": "https://headscale.appmire.be", "auth_key": "…" , "hostname": "kinframe-36339301",
  "adb_key": "QAAAA… user@host",
  "tailscale": { "version": "1.104.7",
    "arm":   { "url": "https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm.tgz",   "sha256": "1c75…" },
    "arm64": { "url": "https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm64.tgz", "sha256": "d6be…" } }
}
```

`auth_key` is present only until the frame reported `on`/`failed`. Ending a session sends `remote_help: null`.
`hostname` is `kinframe-<device serial or short device id>`, lowercase, `[a-z0-9-]`, ≤ 63.

**Events:** push keys `remote_help_started` (to frame members, at start), `remote_help_on` (to the operator who
started it), `remote_help_ended` (members and operator; not to whoever ended it). Websocket `remote_help.updated`
with the session to the frame's members and operators. Owner/admin push is in their language with the frame name
and end time.

**Background:** a worker ends sessions at `ends_at` (`expired`), ends `pending` sessions whose frame never reported
within 15 minutes as `failed`, and ends sessions of frames that are unpaired or deleted (`unpaired`). Ending always
deletes the frame's node in Headscale (`GET /api/v1/node?user=…`, match on `given_name`/`name` = hostname, then
`DELETE /api/v1/node/{id}`), and expires the pre-auth key (`POST /api/v1/preauthkey/expire`) if it is still unused.

### 5.3 Headscale policy (ACL)

```json
{
  "tagOwners": { "tag:kinframe-frame": ["kinframe-frames@"], "tag:kinframe-support": ["jeroen@"] },
  "acls": [
    { "action": "accept", "src": ["tag:kinframe-support"], "dst": ["tag:kinframe-frame:5555"] }
  ]
}
```

No rule lets frames initiate anything. Support devices are tagged by the operator. Today's tailnet has no policy
(everyone reaches everyone): the operator must add one before turning remote help on; the server refuses to start
sessions until `KINFRAME_REMOTE_HELP_POLICY_CHECKED=1` is set, as a deliberate acknowledgement.

### 5.4 Frame (GitHub build, rooted frames only)

The frame lists `remote_help` in `features` only in the GitHub flavor and when `su 0 id` reports root. On a
`remote_help` change with `state: active`:

1. **Binary.** Pick `arm64` or `arm` from `Build.SUPPORTED_ABIS`, download the tarball the change names, check its
   SHA-256 against the change (abort with `failed` otherwise), unpack `tailscaled` and `tailscale` to
   `/data/local/tmp/kinframe-ts/` (≈70 MB; kept for 7 days after the session as a cache keyed by version). Report
   `state: starting` meanwhile.
2. **DNS.** A static Go binary on Android has no `/etc/resolv.conf` and Tailscale's DNS fallback can't resolve a
   self-hosted control server. With root, the frame writes `/system/etc/resolv.conf` (the current `net.dns1`/`dns2`
   plus `1.1.1.1`), remounting `/system` read-write and back read-only as the convert tool does for `hosts`. Removed
   when the session ends.
3. **Start.** Everything runs as root shell scripts the app writes to `/data/local/tmp/kinframe-ts/` and runs with
   `su 0 sh <script>`, because this firmware's `su` passes only the first word of `sh -c "…"`. The start script
   runs `( setsid nohup tailscaled --tun=userspace-networking --statedir=… --socket=… --port=0 >log 2>&1 </dev/null & )`
   from a subshell and then waits a few seconds: a plain background job dies with the script. User-space
   networking needs no TUN device and no VPN permission.
4. **Join.** `tailscale --socket=… up --login-server=<url> --authkey=<key> --hostname=<hostname> --accept-dns=false
   --accept-routes=false --timeout=60s`, then `tailscale ip -4`; heartbeat (sent at once, not at the next interval)
   `remote_help: {state: on, ip}`; on error `state: failed` with the last line of output.
5. **ADB.** Append the operator's key to `/data/misc/adb/adb_keys` (keep the file's owner and mode), then a
   **detached** script `( setsid nohup sh adbtcp.sh … & )` that sleeps 2 s, `setprop service.adb.tcp.port 5555`,
   `stop adbd`, sleeps 1 s, `start adbd`: `stop adbd` kills any shell adbd started, so a plain script never reaches
   `start adbd`. User-space networking delivers tailnet connections to `127.0.0.1:5555`.
6. **Badge** in a corner on top of everything until the end, with Stop (PIN if set) → `DELETE /v1/device/remote-help`
   and the clean-up below. The badge text and Stop dialog come from the frame's string resources in all its languages.
7. **End** (`remote_help: null`, `ends_at` passed by the frame's clock, Stop, unpaired): `tailscale logout`, kill
   `tailscaled`, remove its state, the operator's key line from `adb_keys` and `resolv.conf`, `setprop
   service.adb.tcp.port -1` and restart adbd detached, `state: off`. On boot, a frame that finds
   `/data/local/tmp/kinframe-ts/session` without an active change cleans up the same way; with one whose `ends_at`
   is still ahead it starts again (a new pre-auth key is not needed: the node state in `--statedir` survives while
   Headscale keeps the node).

### 5.5 Limits

- Play build and unrooted tablets: not offered (Play policy, no root). The operator's search says so.
- Memory: `tailscaled` used ≈16 MB RSS at idle on the RK3126; acceptable for temporary sessions.
- ADB listening on TCP also listens on the frame's Wi-Fi during a session; ADB's key check still applies (only the
  operator's key and keys authorised before). Android 6's adbd can't bind to localhost only.

## 6. Hardening that comes with it

- **USB debugging off after converting** becomes the convert tool's default (with remote help as the way back in);
  today it is deliberately left on.
- The frame's ADB keys are listed in the app (count and "remove all except the operator's") so old computers that
  were once allowed can be forgotten.

## 7. Decisions

- **A.** Headscale user `kinframe-frames`, tag `tag:kinframe-frame`; the operator's devices carry `tag:kinframe-support`.
- **B.** Only the operator starts a session (2026-11-20). Families ask by mail or phone; they see and can stop it.
- **C.** Session lengths 1, 8 or 24 hours; the operator page preselects 8.

## 8. Prototype (2026-11-19, Denver PFF-1024, Android 6, kernel 3.10, root)

- Tailscale 1.104.7 `linux/arm` static binaries (SHA-256 checked) run as root in user-space mode; RSS ≈16 MB.
- Without `/etc/resolv.conf` the join failed ("no DNS fallback candidates remain for headscale.appmire.be"); with
  `/system/etc/resolv.conf` it joined as `kinframe-36339301` (100.64.0.9) with an ephemeral one-hour pre-auth key, and
  answered `tailscale ping` directly over the LAN in 39 ms.
- `sh -c "…"` through this firmware's `su` runs only the first word; scripts pushed to `/data/local/tmp` and run with
  `su 0 sh script` work. Background processes die with the ADB shell unless started with `setsid nohup`.
- Restarting adbd from the ADB shell (`stop adbd; start adbd`) killed the shell before `start adbd` ran and left the
  frame without ADB until a restart: hence the detached restart in §5.3 step 5.
- After the restart, a plain `setsid nohup tailscaled … &` at the end of a script died silently with the script.
  Starting it from a subshell (`( setsid nohup … & )`) and letting the script wait a few seconds kept it running
  (parent pid 1). The adbd restart works the same way: a detached script that sleeps, sets
  `service.adb.tcp.port 5555`, then runs `stop adbd` and `start adbd`. USB ADB came back by itself, and
  `adb connect 100.64.0.9:5555` from a laptop on the tailnet reached the frame.
