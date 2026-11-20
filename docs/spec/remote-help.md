# Remote help: a tailnet tunnel to a frame, only when someone asks for it

Status: **draft** (2026-11-19), not built. A prototype ran on a Denver PFF-1024 (§8).

## 1. Summary

Frames sit in living rooms far from the person who set them up. When something is wrong, the only way to look
inside today is a USB cable. Remote help lets the frame's owner switch on a temporary, encrypted tunnel from the
frame to the server operator's tailnet (Tailscale clients against a self-hosted Headscale), so the operator can use
ADB on the frame as if it were plugged in. It is off by default, never runs unasked, ends by itself, and everyone
in the family can see when it is on.

It is not a general VPN for frames and adds no attack surface while it is off: frames keep making only outgoing
HTTPS calls to their server.

## 2. Principles

1. **Asked for, not standing.** A session exists only after a person with rights switched it on, for 1, 8 or 24
   hours. When it ends, the frame leaves the tailnet and forgets its key.
2. **Visible.** The frame shows "Remote help is on until 18:00" in a corner while a session runs; the app shows it
   on the frame card; family members of the frame get a notification when it starts; every session is logged.
3. **Least access.** The tailnet policy lets only the operator's support devices reach frames, only on the ADB
   port. Frames reach nothing on the tailnet, not even each other.
4. **ADB keeps its own lock.** Only the operator's ADB key is accepted, pushed for the session and removed after.
5. **Optional for operators.** A server without Headscale configured shows nothing of this.

## 3. User stories

1. *Asking for help.* Oma's frame shows no new photos. Ben (owner) opens the frame in the app → Settings → Remote
   help → "Let the kinframe operator look at this frame" → 8 hours → Start. The app explains in one sentence what
   the operator can do (see and change everything on the frame, not the family's photos on the server beyond what
   the frame holds) and who that is (the server's name and operator contact from discovery).
2. *The frame.* Within a minute the frame shows a small badge "Remote help is on until 02:15". Oma can tap it: "Ben
   asked for remote help. Stop?" (with the frame PIN if one is set).
3. *The operator.* The operator gets a push/e-mail "Remote help requested for Oma's frame (Ben), until 02:15" with
   the frame's tailnet name `kinframe-<serial>` and runs `adb connect kinframe-<serial>:5555`.
4. *Ending.* The session ends at the chosen time, or when anyone with rights taps Stop (app or frame), or when the
   frame is removed from its owner. The operator's ADB connection drops, the badge disappears, the log says who
   ended it.

## 4. Who may do what

| Action | Frame owner/admin (`change_settings`) | Other frame members | On the frame (PIN if set) | Operator |
|---|---|---|---|---|
| Start a session | ✓ | – | – | – (only on request) |
| See that a session runs | ✓ | ✓ (notification + badge) | ✓ (badge) | ✓ |
| Stop a session | ✓ | – | ✓ | ✓ |
| Connect | – | – | – | ✓ (support devices) |

## 5. How it works

### 5.1 Server

- Config: `KINFRAME_REMOTE_HELP=off|on` (default off), `KINFRAME_HEADSCALE_URL` (e.g. `https://headscale.appmire.be`),
  `KINFRAME_HEADSCALE_API_KEY` (a Headscale API key with permission to create pre-auth keys), `KINFRAME_HEADSCALE_USER`
  (the Headscale user frames register under, e.g. `kinframe-frames`), `KINFRAME_REMOTE_HELP_ADB_KEY` (the operator's
  ADB public key), optional `KINFRAME_REMOTE_HELP_CONTACT`. Discovery gains `remote_help: true` only when all are set.
- `POST /v1/frames/{id}/remote-help {hours: 1|8|24}` (owner/admin): creates a session row (frame, requester,
  started, ends, state), asks Headscale for a pre-auth key (`POST /api/v1/preauthkey` — user, `ephemeral: true`,
  `reusable: false`, `expiration` = session end, `aclTags: ["tag:kinframe-frame"]`), and puts a `remote_help` change
  on the frame's change feed with the key, the login server, the host name, the end time and the ADB key. The
  pre-auth key is never returned to clients and is deleted from the database once the frame confirms.
- `DELETE /v1/frames/{id}/remote-help` (owner/admin, operator, or the device itself): ends the session; the frame
  gets a `remote_help` change with `state: ended`; the server deletes the node in Headscale
  (`DELETE /api/v1/node/{id}`) so it can't come back.
- A background check ends sessions at their end time (and deletes the node) even if the frame is offline.
- Push keys `remote_help_started` / `remote_help_ended` (frame members), and to the operator (push to operator
  accounts plus the contact e-mail). Websocket `remote_help.updated`. Every session is in an audit log (who started,
  who ended, when, frame), visible to the frame's owner and the operator.

### 5.2 Headscale policy (ACL)

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

### 5.3 Frame (GitHub build, rooted frames only)

On a `remote_help` change with a key:

1. **Binary.** Download Tailscale's static `linux/arm` (or `arm64`) tarball for the version the change names from
   `pkgs.tailscale.com`, check its published SHA-256 (the server also sends the expected hash), unpack `tailscaled`
   and `tailscale` to `/data/local/tmp/kinframe-ts/` (≈70 MB; deleted when the session ends; kept for 7 days as a
   cache so a second session doesn't download again).
2. **DNS.** A static Go binary on Android has no `/etc/resolv.conf` and Tailscale's DNS fallback can't resolve a
   self-hosted control server. With root, the frame writes `/system/etc/resolv.conf` (the current `net.dns1`/`dns2`
   plus `1.1.1.1`), remounting `/system` read-write and back read-only as the convert tool does for `hosts`, and
   rewrites it when the network changes during a session. Removed when the session ends.
3. **Start** (all through a root shell script, because this `su` splits `sh -c` arguments into words): `( setsid
   nohup tailscaled --tun=userspace-networking --statedir=… --socket=… --port=0 & )` from a subshell — user-space networking needs no
   TUN device and no VPN permission prompt; `setsid nohup` keeps it alive after the shell ends.
4. **Join**: `tailscale up --login-server=<url> --authkey=<key> --hostname=kinframe-<serial> --accept-dns=false
   --accept-routes=false`; report the tailnet IP back (heartbeat field `remote_help: {state, ip}`).
5. **ADB.** Add the operator's ADB key to `/data/misc/adb/adb_keys`, then `setprop service.adb.tcp.port 5555` and
   restart adbd **from a detached script** (`setsid nohup sh restart-adbd.sh`): `stop adbd` kills the shell that runs
   it, so a plain script never reaches `start adbd`. Tailscale's user-space networking delivers tailnet connections
   to `127.0.0.1:5555`.
6. **Badge** on screen until the end time, with Stop.
7. **End** (time up, Stop, `state: ended`, or the frame unpaired): `tailscale logout`, kill `tailscaled`, remove its
   state, the operator's ADB key and `resolv.conf`, `setprop service.adb.tcp.port -1`, restart adbd detached, delete
   the binaries after 7 days. On boot, a frame finds a stale session directory and cleans up the same way (the
   ephemeral node is removed by Headscale when it stays offline).

### 5.4 Limits

- Play build and unrooted tablets: not offered (Play policy, no root). The UI says so.
- Memory: `tailscaled` used ≈16 MB RSS at idle on the RK3126; acceptable for temporary sessions.
- ADB listening on TCP also listens on the frame's Wi-Fi during a session; ADB's key check still applies (only the
  operator's key and keys authorised before). The frame could bind adbd to localhost only on newer Android; Android
  6's adbd can't.

## 6. Hardening that comes with it

- **USB debugging off after converting** becomes the convert tool's default (with remote help as the way back in);
  today it is deliberately left on.
- The frame's ADB keys are listed in the app (count and "remove all except the operator's") so old computers that
  were once allowed can be forgotten.

## 7. Decisions to take

- **A.** Headscale user and tags for frames (`kinframe-frames`, `tag:kinframe-frame`) and who holds `tag:kinframe-support`.
- **B.** Whether frame members other than owner/admins may *request* a session (proposal: no, but they're told).
- **C.** Default session length (proposal: 8 hours).

## 8. Prototype (2026-11-19, Denver PFF-1024, Android 6, kernel 3.10, root)

- Tailscale 1.104.7 `linux/arm` static binaries (SHA-256 checked) run as root in user-space mode; RSS ≈16 MB.
- Without `/etc/resolv.conf` the join failed ("no DNS fallback candidates remain for headscale.appmire.be"); with
  `/system/etc/resolv.conf` it joined as `kinframe-36339301` (100.64.0.9) with an ephemeral one-hour pre-auth key, and
  answered `tailscale ping` directly over the LAN in 39 ms.
- `sh -c "…"` through this firmware's `su` runs only the first word; scripts pushed to `/data/local/tmp` and run with
  `su 0 sh script` work. Background processes die with the ADB shell unless started with `setsid nohup`.
- Restarting adbd from the ADB shell (`stop adbd; start adbd`) killed the shell before `start adbd` ran and left the
  frame without ADB until a restart: hence the detached restart in §5.3 step 5.
