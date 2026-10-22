# kinframe API

Version **v1** (draft, 2026-10-22). `openapi.yaml` in this folder is the machine-readable contract and the source for
generated code:

| Side | Generator |
|---|---|
| Go server | `oapi-codegen`: types plus a strict server interface |
| Android (frame and family app, shared `:core` module) | `openapi-generator` (kotlin, `jvm-okhttp4` + kotlinx.serialization) |
| iOS family app | Apple `swift-openapi-generator` |
| SvelteKit web | `openapi-typescript` plus `openapi-fetch` |

This README explains the concepts, flows and the parts OpenAPI cannot express: websocket messages, tus uploads and
the change feed.

Default production server: `https://kinframe.appmire.be` (see `docs/deploy.md`).

## 1. Conventions

- **Base path** `/v1`. JSON only, UTF-8, `snake_case` fields.
- **IDs** are UUIDv7 strings. **Timestamps** are RFC 3339 in UTC (`2026-10-22T13:05:00Z`).
- **Pagination** is cursor-based: `?cursor=…&limit=…`. Responses have `next_cursor` (null at the end). Default limit
  50, max 200.
- **Errors** use RFC 9457 `application/problem+json` with a stable machine `code`:

  ```json
  { "type": "https://kinframe.app/errors/code-expired", "title": "This code has expired",
    "status": 410, "code": "code_expired", "detail": "Ask for a new code on the frame." }
  ```

  Clients branch on `code` and may show `title` and `detail`, which are localized via `Accept-Language`. All codes are
  listed in `openapi.yaml` under `ProblemCode`.
- **Concurrency**: mutable documents (settings, albums) carry a `version` integer. Writes send `If-Match: "<version>"`
  and get `412 version_conflict` if it is stale.
- **Idempotency**: `POST` endpoints that create things accept an `Idempotency-Key` header. A retry with the same key
  returns the first result. Mobile clients must use it for sends.
- **Rate limits**: `429` with `Retry-After`. Code redemption is limited per user and per IP.

## 2. Discovery

`GET /.well-known/kinframe` needs no auth and is not versioned. Apps and frames call it when a server URL is
entered. See `docs/architecture.md` → *Server discovery*.

## 3. Authentication

### 3.1 People: Logto

- Clients run OIDC Authorization Code + PKCE against `auth.issuer` from discovery, requesting the resource
  `auth.api_resource` and scopes `openid profile email offline_access`.
- They call the API with `Authorization: Bearer <access token>`. The server validates the JWT against the issuer's
  JWKS and checks `iss`, `aud == api_resource`, `exp`, and `nbf` with 60 s leeway.
- The first request from an unknown `sub` creates the user row. The display name is initialized from the ID token
  name or the email local part.
- **Operator** rights come from the Logto role `kinframe-operator`, exposed as scope `kinframe:operate` on the API
  resource.

### 3.2 Frames: device credentials

Device credentials are issued at enrollment.

```
frame                                   server
  │ POST /v1/devices/enroll  {hardware, screen, app} ──▶ creates device (unclaimed)
  │ ◀── { device_id, device_secret }                       secret stored as argon2id hash
  │ POST /v1/devices/token {device_id, device_secret} ──▶
  │ ◀── { access_token (JWT, 1 h, aud=device), expires_in }
  │ Authorization: Bearer <access_token> on /v1/device/* and the device websocket
```

- The secret lives in Android Keystore-backed encrypted storage on the frame. It is never shown and never leaves the
  device except to `/devices/token`.
- **Revocation**: the owner removes the frame, or uses "Move to new frame". All tokens for that device are rejected
  from then on, and the websocket is closed with code `4401`.
- The token is refreshed when less than 5 minutes remain. A `401` with `code: device_revoked` makes the frame wipe its
  credential and return to the setup wizard. A `401 token_expired` means: refresh and retry once.

## 4. Core model

```
User ─┬─< Membership >─┬─ Frame ──── Device (current credential)
      │  role, perms   │    │ settings (versioned JSON), health
      │                │    └─< Album >─< AlbumItem
      ├─< Send ─< SendItem ──> Item ─< Rendition (master, frame_<w>x<h>, thumb_<n>)
      │                         └─< Placement (item × frame) ─ status, hidden, trashed, reaction, reply
      └─< PushEndpoint
```

- **Item**: one photo or video, owned by its sender. It has caption, capture time, focal point and fit mode. The
  server stores a **master rendition** only, never the original (see `docs/spec/product-spec.md` §8).
- **Placement**: an item on a specific frame. Delivery state (`pending → ready → delivered → seen`), visibility
  (`hidden`), trash and the frame's reaction are per placement. One send to three frames creates one item and three
  placements. A placement **shared** from another frame's library points at the same item and records who shared it
  (`shared_by_user_id`), so sharing never copies media (§5.3).
- **Permissions** per membership:

  | Permission | Allows |
  |---|---|
  | `send` | Send to the frame |
  | `invite` | Create invite links |
  | `see_library` | Browse every placement on the frame, not just your own |
  | `manage_library` | Hide, delete or show-now any placement; edit any caption; manage albums |
  | `change_settings` | Frame settings and remote commands |

  Owner and admin have all permissions. Senders can always edit, hide or delete their own placements.

## 5. Flows

### 5.1 Claiming or joining a frame with a code

1. The frame (online, enrolled) calls `POST /v1/device/code` and gets
   `{ code: "48213907", expires_at, join_url: "https://kinframe.appmire.be/f/48213907" }`.
   - It shows the code as `4821 3907` plus a QR of `join_url`.
   - It re-requests 5 minutes before expiry while the screen is open. The same code is returned until it expires (12 h).
2. The user scans the QR (opens the app or the web), or types the code. The client calls
   `POST /v1/frames/redeem {code}`.
3. The response depends on the frame:
   - **Unclaimed frame**: `status: "claimed"`, and the caller becomes `owner`. The frame gets a `claimed` websocket
     event and leaves the setup screen.
   - **Owned frame, approval on the frame off** (default): `status: "joined"` as `member` with the frame's default
     permissions.
   - **Owned frame, approval on the frame on**: `status: "pending"`. The frame shows "<name> wants to join" with
     Allow and Deny. `POST /v1/device/join-requests/{id}` with `{decision}` resolves it, and the user gets a push.

Codes are 8 digits from a CSPRNG, unique among active codes, and invalidated when used for a claim. Redemption is
rate-limited to 10 per hour per user and 30 per hour per IP. Wrong codes all return `404 code_invalid`, so they
don't reveal whether a code ever existed.

### 5.2 Invite links

`POST /v1/frames/{id}/invites` (needs `invite`) returns `{ token, url: "https://…/i/<token>" }`.

- The recipient opens it, signs in, previews with `GET /v1/invites/{token}`, then calls
  `POST /v1/invites/{token}/accept`.
- If the frame's `invite_requires_approval` is on (default), the result is `pending` until an owner or admin approves
  with `POST /v1/frames/{id}/join-requests/{rid}/approve`.

### 5.3 Sending media

```
client                         server                                   frame
  │ tus POST /v1/uploads  (Upload-Metadata: filename, filetype, sha256)
  │ tus PATCH … (resumable, 4 MiB chunks)
  │ POST /v1/sends {frame_ids, items:[{upload_id, caption, focal_point, fit, trim}]}
  │ ◀── 202 { send_id, items:[{item_id, status:"processing"}] }
  │                               process: decode → master → frame renditions → thumbs → faces
  │                               placements ready ──── ws "changes" ─────▶ │
  │                                                     GET /v1/device/changes │
  │                                                     GET /v1/device/media/… │
  │ ◀── ws/push "delivered"  ◀── POST /v1/device/acks {delivered:[…]} ──────── │
  │ ◀── ws/push "seen"       ◀── POST /v1/device/acks {seen:[…]}  (first shown) │
```

**Uploads (tus 1.0)**
- tus protocol 1.0 at `/v1/uploads` with the extensions *creation*, *creation-with-upload*, *termination* and
  *expiration*.
- Uploads expire after 24 h if no send references them.
- Metadata keys:
  - `filename`
  - `filetype`: a MIME type
  - `sha256`: hex, optional. If the same user already has an item with that hash, the send reuses it without
    processing.
- Max upload size is in discovery `limits.max_upload_bytes` (default 2 GiB).

**Sends**
- `POST /v1/sends` validates everything before accepting: permissions on every frame, item count, video length after
  trim, supported types. It is all-or-nothing.
- Processing is asynchronous. Failures mark the item `failed` with a `code` (`unsupported_media`, `corrupt_media`,
  `video_too_long`), visible in the sender's history and pushed.

**Supported input**
- Photos: JPEG, PNG, WebP, HEIC/HEIF, AVIF, GIF (first frame).
- Videos: MP4, MOV, 3GP, MKV, WebM, with codecs H.264, HEVC, VP8/VP9, AV1.
- EXIF orientation is applied and capture time is extracted. GPS is kept only as a coarse place name, and only if the
  sender enables "share location names".

**Renditions** (all from the master)
- `frame_<w>x<h>`: short edge equals the frame's long screen edge, never upscaled, WebP q85. For video: H.264
  High@4.1 (or Main for older decoders), at most the device's `max_video_decode` size, 30 fps, bitrate w·h·4 capped
  at 8 Mbps, AAC-LC 96 kbps, faststart.
- `thumb_256` and `thumb_512`: square, centred on the focal point, WebP q75.
- A poster image for each video.

**Sharing between frames**

`POST /v1/frames/{frameId}/placements:share` with `{placement_ids, frame_ids, dry_run?}` (and `Idempotency-Key`)
puts photos from one frame's library on other frames without a new upload.

- Who: the caller needs `see_library` on the source frame (or is the item's sender, or shared it there) and `send` on
  every target. Sources must be live, not trashed and not awaiting moderation. Validation is all-or-nothing.
- Storage: each new placement references the **same item** (same media, master, thumbnails). The target gets a
  `frame_<w>x<h>` rendition only if no frame with that screen size has one yet; otherwise the existing one is reused.
- Duplicates: a target that already holds the item is skipped and reported in `skipped` (`already_on_frame`).
  `dry_run: true` returns only `skipped`, so a frame picker can show which frames already have the selection.
- Moderation and limits are the target's: `pending_moderation` unless the caller manages it, at most
  `limits.max_items_per_send` placements per request.
- Credit: the item keeps its original sender; the placement adds `shared_by_user_id` and `shared_by_name`
  (also in the sender's history, `Send.frames[].placements[].shared_by_name`). Frames show "Anna · shared by Ben".
- Afterwards it behaves like any placement: delivery states, change feed, reactions and replies. The **original
  sender** gets reaction and reply notifications and sees the new frame in their history; the **sharer** gets the
  `delivered` push once everything they shared to a frame has arrived.
- Removal: the original sender's unsend removes shared placements too. Owners and admins of a target hide, trash or
  purge them as usual. The sharer may hide, trash or purge the placements they created (clients undo a share with
  `placements:bulk` `purge`).
- `POST /v1/sends/{id}/forward` (the sender placing a whole send on more frames) uses the same code path, without
  `shared_by`.

### 5.4 Frame synchronization (the change feed)

The frame keeps a local mirror of its state. The source of truth is an **append-only change feed** per frame.

- `GET /v1/device/changes?since=<seq>&limit=500` returns `{ changes: [...], next_seq, has_more }`. `since=0` returns
  a full snapshot.
- Each change has the form `{ seq, type, ... }`. Types:

  | Type | Effect |
  |---|---|
  | `placement.upsert` | New or changed placement (full object, including rendition URL, sha256 and size) |
  | `placement.remove` | Hard removal (trash purge, sender unsend) |
  | `settings` | Full settings document |
  | `album.upsert` / `album.remove` | Album changes |
  | `member.upsert` / `member.remove` | Display info only: name, avatar URL |
  | `frame` | Name, location, owner name |
  | `weather` | Current conditions and forecast, when enabled (below), or `weather: null` |
  | `reset` | The frame must discard local state and re-sync from 0 |

- **Weather** (schema `Weather`): the server fetches it from [Open-Meteo](https://open-meteo.com) (no API key) for
  every frame with `settings.location` set and `widgets.weather` on. Frames never call third parties.
  - Payload: `fetched_at`, `location {name, lat, lon}` (from the frame's settings), `timezone`, `utc_offset_seconds`,
    `units`, `current` (`temp_c`, `apparent_c`, `code` = WMO weather code, `is_day`, `wind_kmh`, `wind_dir_deg`,
    `humidity_pct`, `precip_mm`), `hourly` (the next 24 h from the current hour: `time`, `temp_c`, `code`, `is_day`,
    `precip_prob`) and `daily` (7 days from today: `date`, `min_c`, `max_c`, `code`, `precip_mm`, `precip_prob`,
    `sunrise`, `sunset`). Times are RFC 3339 with the location's offset.
  - Always metric (°C, km/h, mm). The frame converts for `locale.temperature_unit`.
  - The server caches per location rounded to 2 decimals (≈1 km), so frames in the same town share one request, and
    refreshes every 30 min (plus jitter, with back-off on errors). Changing `location` or turning `widgets.weather` on
    fetches right away.
  - A new change is emitted only when something visible changed (rounded values, the hourly window, the days, the
    place name), and at least every 2 h while fetches succeed so `fetched_at` stays recent. Frames hide weather older
    than 6 h.
  - `weather: null` means "drop your weather": the location was removed or the widget turned off. Snapshots
    (`since=0`) omit it.
  - Place search for the settings UI: `GET /v1/geocode?q=&lang=` (user auth, 60/min per user, cached) proxies the
    Open-Meteo geocoding API and returns `Place`s.
  - Attribution (CC BY 4.0): apps that show the data credit "Weather data by Open-Meteo.com".
- Clients apply changes in order and persist `next_seq` in the same SQLite transaction as the changes.
- When the frame is behind more than the server's retention window (default 30 days of changes), it gets a `reset`.
- **Media download**: `GET /v1/device/media/{placement_id}` serves the frame's rendition. It supports `Range` and
  `ETag`. The frame checks `sha256`, writes to a temp file, fsyncs and renames.
- **Acks**: `POST /v1/device/acks` with `{ delivered: [placement_id…], seen: [{placement_id, at}…] }`, batched every
  10 s or 50 items.

### 5.5 Reactions and quick replies

- Frame: `PUT /v1/device/placements/{id}/reaction {reaction: "love"|"laugh"|"wow"|"sad"|null}`. The latest value
  wins; `null` removes it.
- Frame: `POST /v1/device/placements/{id}/replies {preset: "beautiful"|"call_me"|"miss_you"|"thanks"}`.
- The sender sees them in `GET /v1/sends/{id}` and gets websocket and push events.

### 5.6 Remote settings and commands

- `GET` and `PATCH /v1/frames/{id}/settings` (needs `change_settings`). The `If-Match` version is required. The frame
  gets a `settings` change. Local changes on the frame go to `PATCH /v1/device/settings` with the same versioning.
  Conflicts are resolved field by field: the server merges non-overlapping fields, and same-field conflicts go to the
  latest write.
- `POST /v1/frames/{id}/commands {type, args}` (needs `change_settings`, or `manage_library` for `show_placement`).
  - Types: `sleep`, `wake`, `restart`, `next`, `previous`, `show_placement`, `sync_now`, `identify` (flashes the
    frame name on screen).
  - Commands are delivered only over the device websocket and are never queued: `409 frame_offline` if the frame is
    not connected.
  - The frame confirms with a `command_result` message.

### 5.7 Frame app updates

The server mirrors signed frame releases and the frame updates itself from the server only (product spec 4.10).

```
GitHub Releases (or a drop-in directory) ──poll 30 min──▶ server mirror ◀── GET /v1/device/update ── frame
                                                          (last 3 APKs)  ◀── GET /v1/device/update/apk/{code}
```

- **Releases** are GitHub releases of `KINFRAME_FRAME_RELEASES_REPO` with an asset
  `kinframe-frame-<version_name>-<version_code>.apk` and a `kinframe-frame-<version_name>-<version_code>.apk.sha256`
  (`sha256sum` format). Non-prereleases are the `stable` channel, prereleases `beta`. The server verifies the
  sha256, reads package name, version code and `minSdkVersion` from the APK's binary manifest, and refuses APKs whose
  package is not `app.kinframe.frame` or whose version code disagrees with the asset name.
- **Version codes** are derived from the tag: `major·1 000 000 + minor·10 000 + patch·100 + n`, where `n` is 99 for a
  release and `N` for `-beta.N` (1–98). So `v0.2.0-beta.3` → 20003 < `v0.2.0` → 20099 < `v0.2.1-beta.1` → 20101.
- **Check**: `GET /v1/device/update?channel=&skip=` returns `FrameUpdate` or `204`. The channel defaults to the
  frame's `updates.channel` setting. `skip` lists version codes the frame rolled back from.
- **Download**: `url` points at the server; it supports `Range`/`If-Range` with ETag `"<sha256>"`, so frames resume.
- **On the frame**: checks every 24 h plus up to 90 min jitter, on `sync_now`, from "Check for update" in settings,
  and right away after a `426 app_update_required`. Installs only when nobody touched the frame for 15 minutes and
  the screen was not switched off by hand. Before installing it verifies sha256, package name, signing certificate
  (must equal the installed app's) and that the version code is higher. With root it runs `pm install -r`; without
  root it opens the system installer and shows a notice.
- **Rollback**: the frame keeps the previous APK. If the crash-loop guard trips within 10 minutes after an update, it
  reinstalls the previous APK (`pm install -r -d`, root only), skips that version code from then on and reports
  `update_state: rolled_back` in the heartbeat.

## 6. Websockets

Both use JSON text frames `{ "type": "...", ... }` and server ping/pong every 25 s. Clients reconnect with
exponential back-off from 1 s up to 60 s, with ±20 % jitter.

### 6.1 Device: `GET /v1/device/ws` (Bearer device token)

| Direction | `type` | Payload | Meaning |
|---|---|---|---|
| S→F | `hello` | `server_time`, `latest_seq` | Sent on connect. The frame corrects its clock if it is off by more than 2 min (no NTP needed) and pulls changes if behind. |
| S→F | `changes` | `latest_seq` | Hint only. The frame calls `/device/changes`. |
| S→F | `command` | `id`, `command_type`, `args` | Remote command. |
| S→F | `claimed` | `owner_name` | Leave the setup or code screen. |
| S→F | `join_request` | `request` | Show Allow/Deny. Requests still pending (e.g. sent while the frame was offline) come from `GET /v1/device/join-requests`. |
| S→F | `revoked` | — | Wipe credential, back to setup. Followed by close `4401`. |
| F→S | `state` | `screen: on/off/dimmed`, `showing_placement_id`, `brightness`, `volume` | Sent on change, at most every 2 s. |
| F→S | `command_result` | `id`, `ok`, `error_code` | Result of a command. |

Health data (Wi-Fi RSSI, storage, uptime, app version, temperature if known) goes to `POST /v1/device/heartbeat`
every 5 minutes, not over the websocket.

### 6.2 Users: `GET /v1/ws?access_token=…`

Browsers can't set headers on websockets, so the token goes in the query. It is validated on connect only, and the
server closes with `4401` at token expiry so the client reconnects with a fresh token.

| `type` | Payload | Meaning |
|---|---|---|
| `send.updated` | `send` (summary) | Processing, delivered or seen counts changed |
| `reaction` | `placement_id`, `frame_id`, `reaction`, `by_frame_name` | New or changed reaction |
| `reply` | `placement_id`, `frame_id`, `preset` | Quick reply from a frame |
| `share.delivered` | `frame_id`, `frame_name` | Everything the user shared to that frame (`sharePlacements`) arrived |
| `frame.status` | `frame_id`, `online`, `last_seen_at` | Frame connectivity |
| `join_request` | `frame_id`, `request` | Approval needed (owner/admin) |
| `membership.updated` | `frame_id` | Role or permissions changed |

### 6.3 Push

- The same events are sent as push notifications when the user has no open websocket, through registered endpoints:
  `POST /v1/me/push-endpoints {kind: "unifiedpush"|"fcm"|"apns"|"webpush", endpoint_or_token, ...}`.
- Payloads carry only IDs and a localized title and body. Clients fetch details over the API.
- **WebPush** (browsers): subscribe with `PushManager.subscribe({userVisibleOnly: true, applicationServerKey})`
  using discovery's `webpush_public_key` (VAPID, RFC 8292), then register `{kind: "webpush", endpoint,
  webpush_keys: {p256dh, auth}, locale}`. Messages are encrypted with RFC 8291 (`aes128gcm`). The decrypted payload is
  `{type, key, title, body, ids}` (`ids` may hold `send_id`, `placement_id`, `frame_id`, `request_id`). Endpoints the
  push service reports as gone (404/410) are deleted by the server.
- `delivered` is pushed once per send and frame, when the last of its photos reaches that frame.
  Placements shared from another frame count for the sharer instead: they get `delivered` (event `share.delivered`)
  once everything they shared to a frame has arrived.

## 7. Versioning

- Breaking changes need `/v2`. Additive changes (new fields, new event types, new change types) are not breaking.
- Clients must ignore unknown fields and event types.
- The frame sends `X-Kinframe-App: kinframe-frame/<version name> (<version code>)`. The server can answer
  `426 app_update_required` on any device call except heartbeat, state and the update endpoints. The frame then
  self-updates (§5.7, product spec 4.10).
