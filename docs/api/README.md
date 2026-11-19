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
- **Browser frames**: a web browser can enroll as a frame (`kind: "browser"` in the enroll body, the TV page
  `/tv/frame`). It speaks this same protocol. It keeps its credential in `localStorage` (scoped to the API origin),
  never self-updates (`GET /device/update` answers 204) and is exempt from `426 app_update_required`. Because
  browsers cannot set headers on websockets or on `<img>`/`<video>`, `GET /device/ws` and `GET /device/media/{id}`
  also accept the access token as `?access_token=`; no other device call does. Frames show `device_kind` in
  `FrameSummary` so apps can label them ("Browser"); they are removed like any frame.
- The token is refreshed when less than 5 minutes remain. A `401` with `code: device_revoked` makes the frame wipe its
  credential and return to the setup wizard. A `401 token_expired` means: refresh and retry once.

## 4. Core model

```
User ─┬─< Membership >─┬─ Frame ──── Device (current credential)
      │  role, perms   │    │ settings (versioned JSON), health
      │                │    └─< Album >─< AlbumItem
      ├─< Send ─< SendItem ──> Item ─< Rendition (master, frame_<w>x<h>, thumb_<n>)
      │                         └─< Placement (item × frame) ─ status, hidden, trashed, reaction, reply
      ├─< PushEndpoint
      ├─< FeedMembership >─ Feed ─< FeedPost ─< FeedPostItem ──> Item
      │   role, prefs, mute   │        │ circle_id?, prompt_id?
      │   (owner, admin,      │        └── Thread (post_id) ─< Comment (parent_id: one level) ─< CommentMention
      │    poster, member)    ├─< Subscription >── Frame     (status, history, birthdays; delivers placements origin=feed)
      │                       ├─< BirthdayPerson ─< BirthdayGreeting >── Frame   (birthdays change to frames)
      │                       ├─< Circle ─< CircleMember (feed members only), CircleFrame
      │                       ├── Conversation kind=family (members = the feed's members, kept by the database)
      │                       ├── FeedEngagement, FeedStreak ─< StreakWeek, FeedPrompt (weekly), FeedRecap (monthly)
      │                       └── FeedFreshness
      ├─< FramePost (send × frame: a direct send as a timeline post) ── Thread
      ├─< Reaction (target: post | post_item | comment | message; one per person and target)
      ├─< ConversationMember >─ Conversation (direct | group | family | circle) ─< Message ─< MessageItem ──> Item
      │   read position, mute                                                       │ reply_to, shared_post, link preview,
      │                                                                             └ system (put on a frame, recap, …)
      ├─< Block, Report, ThreadRead, ReminderPreferences, Nudge (reminder log), Milestone, WeeklySummary
Frame ─< PhotoRequest ("Ask for new photos"), FrameFreshness

PhotoSource (owner: User) ──▶ Album (of one frame) or Feed      (Immich / iCloud sync: one send per run, Item.via)
GoogleImport (User) ──▶ Album or Feed                           (one-time copy from Google's picker)
EmailAddress (owner: User) ──▶ Frame or Feed ─< EmailSender     (mailed photos: one send per mail, Item.via email)
TelegramChat (owner: User) ──▶ Frame or Feed ─< TelegramContributor   (one send per batch, Item.via telegram)
TelegramLink: User ── one Telegram account
```

- **Item**: one photo or video, owned by its sender. It has caption, capture time, focal point and fit mode. The
  server stores a **master rendition** only, never the original (see `docs/spec/product-spec.md` §8).
- **Placement**: an item on a specific frame. Delivery state (`pending → ready → delivered → seen`), visibility
  (`hidden`), trash and the frame's reaction are per placement. One send to three frames creates one item and three
  placements. A placement **shared** from another frame's library points at the same item and records who shared it
  (`shared_by_user_id`), so sharing never copies media (§5.3). A placement made by a **feed** the frame subscribes to
  has `origin: feed` (§5.12); a frame never holds two live placements of the same item, whatever the path.
- **Posts and threads** (§5.18): a **post** is a feed post or a frame post (a direct send to one frame); both
  share one id space and have a thread of comments, people's reactions, mentions and frame entries. A feed post may
  have a **circle** (a smaller group inside the family) as its audience.
- **Conversations** (§5.19): `direct`, `group`, `family` (one per feed) and `circle` (one per circle) chats;
  message photos, videos and voice recordings are ordinary items. **Engagement** (§5.20): freshness, photo
  requests, reminders, streaks, prompts, summaries, recaps and milestones.
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
  │ tus POST /v1/uploads  (Upload-Metadata: filename, filetype, sha256, prepared?)
  │ tus PATCH … (resumable, 4 MiB chunks)
  │ POST /v1/sends {frame_ids, items:[{upload_id, live_video_upload_id?, caption, focal_point, fit, trim, captured_at}]}
  │ ◀── 202 { send_id, items:[{item_id, status:"processing"}] }
  │                               process: verify or decode → master → frame renditions → thumbs → faces
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
  - `prepared`: optional, `photo-master-v1` or `video-master-v1`. The client already made the master (below).
    Unknown values are ignored.
- Max upload size is in discovery `limits.max_upload_bytes` (default 2 GiB).

**Prepared uploads (client-side preparation)**

Phones and browsers can make the master themselves, which saves upload bytes (a 12 MP JPEG or HEIC becomes a
≈1 MB WebP, a 4K clip a 1080p one) and server CPU. The server **verifies instead of trusting**; anything that does
not conform silently takes the normal pipeline, so preparing is always safe.

| Profile | The client produces | The server checks | Then |
|---|---|---|---|
| `photo-master-v1` | WebP (lossy, q90) or JPEG where WebP encoding isn't available; long edge ≤ 2560 px; EXIF orientation applied; no metadata; sRGB | WebP or JPEG by magic bytes, ≤ 16 MiB, ≤ 2560 px, no orientation other than 1, 8-bit sRGB or grey, one page, decodes completely, matches the declared `width`/`height` | stores it as the master **without re-encoding**, after stripping all metadata again (EXIF, XMP, IPTC, comments, anything after the image; the ICC profile stays). Frame renditions and thumbnails are made from it as usual. |
| `video-master-v1` | MP4, H.264 High or Main (Baseline accepted) at level ≤ 4.1, yuv420p, ≤ 1080p (either orientation, rotation applied), ≤ 30 fps preferred (≤ 60 accepted), AAC-LC stereo/mono or no audio, trim and mute already applied, bitrate ≈ w·h·3.5 bps capped at 8 Mbps | MP4 container, one video stream, the codec/profile/level/pixel format above, no rotation flag, ≤ 1080p, ≤ 60 fps, AAC (≤ 2 channels) or no audio, duration ≤ `max_video_seconds` (and matching `duration_ms` ±0.5 s), total bitrate ≤ 20 Mbps, no `trim` in the send | **remuxes** it (stream copy, no re-encode) without any container or stream metadata and with the index first (faststart), and stores that as the master. |

- **Privacy is never delegated.** Metadata is stripped server-side on every path, also when a client claims to have
  done it. Prepared files carry no capture time, so clients send `captured_at` (and `width`, `height`,
  `duration_ms`) with the send item.
- **Frame video renditions reuse the master** whenever it already fits the frame: H.264 ≤ level 4.1 within the
  device's `max_video_decode`, ≤ 30 fps, bitrate within the rendition budget (w·h·4, max 8 Mbps, +10 %), AAC or no
  audio, faststart. The frame then downloads the master's bytes and the server transcodes nothing. This applies to
  any master, prepared or not. Photos always get their cheap per-frame-size WebP and thumbnails.
- **Clients never upload full originals.** The Android and iOS family apps and the web app prepare every photo and
  video. A file a client can't prepare goes as it is only when it is already within the limits (photo: long edge
  ≤ 2560 px, and for the web app a JPEG or WebP; video: ≤ 1080p and ≤ 30 fps); otherwise the item shows an error and
  isn't sent. A prepared photo that comes out bigger than its original may be replaced by the original, again only
  when the original is within the limits. Mixed sends are fine. The server keeps accepting originals and processing
  them itself (older clients, other tools); nothing about that changed.
- **Web app.** Photos are prepared in a worker with the browser's own decoder (`createImageBitmap` +
  `OffscreenCanvas`, WebP or JPEG). When that fails or is missing, a WebAssembly module (Rust, `web/prep-wasm`) decodes
  JPEG, PNG, WebP, GIF (first frame), BMP and TIFF, scales with Lanczos3, applies the EXIF orientation, converts
  matrix/TRC ICC profiles (Display P3, Adobe RGB…) to sRGB and writes a metadata-free JPEG q90. HEIC/HEIF (and AVIF)
  that the browser can't decode (everything but Safari) is decoded by libheif + libde265 compiled to WebAssembly
  (LGPL-3.0, `libheif-js`); its primary image is used, with the HEIF rotation and mirroring applied. HEVC decoding is
  patent-encumbered in some countries, so that module is loaded only on demand, for such files. Videos need WebCodecs
  (H.264 encoding); a browser without an AAC encoder sends the video muted and says so on the item.
  `localStorage['kinframe.prep'] = 'wasm'` forces the WebAssembly path for testing.
- The operator sees how masters were made in `GET /v1/operator/stats` → `media_pipeline` (`transcoded`,
  `prepared`, `fallback`, `reused_renditions`); each master is also logged with its path and the rejection reason.

**Live Photos (iOS) and Motion Photos (Android)**

A **live item** is a still plus a short motion clip (≤ 10 s; longer clips are cut).

- **Sending**: either two uploads, the still as `upload_id` and the clip as `live_video_upload_id` (iOS Live Photo:
  HEIC/JPEG + MOV, or both prepared), or one Android **Motion Photo** (a JPEG/HEIC with an embedded MP4, XMP
  `GCamera:MotionPhoto`/`MicroVideo` with `Container:Directory` offsets, or a HEIC `mpvd` box) as `upload_id`: the
  server finds and splits the clip itself. Clients that split it themselves send the parts as two uploads. Send
  `live: false` to send a Motion Photo as a still only. The clip is muted unless `include_audio: true` is sent
  explicitly. `trim` is not allowed on live items.
- **Stored**: the still as a photo master, the clip as a video master (H.264 ≤ 1080p, same prepared/fallback rules).
  The clip has no poster and no thumbnails of its own: the still is its poster. If the clip fails to process, the
  item simply stays a photo.
- **API**: `Item.kind` is `live` with `Item.motion` (`status`, size, duration, audio); the clip's master is at
  `GET /v1/items/{id}/media/motion`. Every photo field (size, thumbnails, `captured_at`) describes the still.
  The library filter `kind=photo` includes live items and `kind=live` returns only them.
- **Frames**: `placement.upsert` carries the still's rendition in `media` (so frames that don't know `live` show a
  normal photo) and, once ready, `motion` `{url, sha256, bytes, mime, width, height, duration_ms, has_audio}` with
  the frame-sized clip (`GET /v1/device/media/{placement_id}?variant=motion`). The frame plays it per
  `settings.slideshow.live_photos`: `on` (default: once when the slide appears, again on tap), `tap` or `off`.

**Sends**
- `POST /v1/sends` validates everything before accepting: permissions on every frame, item count, video length after
  trim, supported types, live clip uploads. It is all-or-nothing.
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
  `shared_by`. With `feed_ids` it posts the send to feeds (§5.12).

**Sending to feeds**: `SendCreate.feed_ids` lists feeds next to `frame_ids` (either may be empty, not both). See §5.12.

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
  | `calendar` | The frame's merged calendar (§5.8), or `calendar: null` when it has none |
  | `birthdays` | The frame's birthday people from its feeds (§5.13), or `birthdays: null` when it has none |
  | `comments` | The newest comments of one placement's post (entity = the placement), with *Show comments* on (§5.20), or `comments: null` |
  | `photo_request` | The frame's latest "Ask for new photos" (§5.20), or `photo_request: null` |
  | `prompts` | The current weekly prompts of the frame's families (§5.20), or `prompts: null` |
  | `reset` | The frame must discard local state and re-sync from 0 |

- **Weather** (schema `Weather`): the server fetches it from [Open-Meteo](https://open-meteo.com) (no API key) for
  every frame with `settings.location` set and `widgets.weather` or `widgets.calendar_sidebar` on (the calendar shows the forecast per day). Frames never call third parties.
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
- **Feed placements** (§5.12) are ordinary `placement.upsert`/`placement.remove` changes; there is no feed change
  type. The placement carries `origin`, `origin_feed_id`, `origin_feed_name`, `feed_ids` and `backfill`. Placements of
  a paused subscription render as `placement.remove` and come back as `placement.upsert` on resume. Frames that don't
  know these fields show feed posts as normal photos.
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
- With family social (§5.18) they also appear in the reaction row of the post (`ReactionSummary.frames`) and as
  frame entries in its thread; on photos put on a frame from a chat, under the chat message (`frame_activity`).
  People react to posts, items, comments and messages with `PUT /v1/reactions/{target_kind}/{id}`.

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
- **Google Play build** (`docs/play-frame.md`): the frame app's `play` flavor, for Android tablets, never calls
  `GET /device/update` and never installs APKs. It updates through Google Play's in-app updates on the same cadence
  and idle rules, and right away after a `426`. Its heartbeat lists `play` in `features`.
- **Public download** (no sign-in): `GET /v1/frame-app/latest?sdk=` returns the newest stable `FrameUpdate` (or
  `204`) with `url` → `GET /v1/frame-app/apk/{code}`. The website's convert tool (`/convert`,
  `tools/convert/README.md`) uses it to install kinframe on a stock frame over USB without a cross-origin download
  from GitHub. The tool still checks the sha256 and pins the release signing certificate itself.
- **Rollback**: the frame keeps the previous APK. If the crash-loop guard trips within 10 minutes after an update, it
  reinstalls the previous APK (`pm install -r -d`, root only), skips that version code from then on and reports
  `update_state: rolled_back` in the heartbeat.

### 5.8 Calendars

Calendars are synced **by the server**, never by the frame, and there is no limit on the number of calendars per
frame. Management needs `change_settings`; sending an event needs `send`.

**Sources** (`/v1/frames/{id}/calendars`)
- `ics`: any ICS/iCal link, including `webcal://` (fetched as `https://`). This covers the "secret address" links of
  Google Calendar, iCloud (public calendar), Outlook/Microsoft 365 ("publish calendar") and Yahoo.
- `caldav`: a CalDAV server with username and app password (Nextcloud, Fastmail, iCloud with an app-specific
  password, Radicale, …). The URL may be the server, the account or a calendar. `POST …/calendars:test` lists the
  account's calendars; the client creates one source per chosen calendar (`calendar_url`).
- `local`: the frame's **received events** calendar holding accepted invitations. It is created with the first
  accepted invitation. Its `name` is empty until renamed; clients show "Received events" with a heart.
- Every source has a display `name`, a `color` from the palette below and `enabled`. The URL, username and
  password are encrypted at rest (AES-256-GCM). The key is `KINFRAME_SECRETS_KEY` (32+ bytes, hex or base64) or,
  when unset, derived from `KINFRAME_SIGNING_KEY` with HKDF-SHA256. Rotating that key makes stored credentials
  unreadable: affected sources go to `auth_failed` until the URL or password is entered again. Responses only carry
  a redacted `url_hint`.

**Sync**
- Every 15 minutes (`KINFRAME_CALENDAR_SYNC_MINUTES`) plus up to 3 minutes of jitter, right after a source is added
  or changed, on "Sync now" (`POST …/calendars/{cid}/sync`) and when the frame asks (`POST /v1/device/calendar/sync`,
  e.g. from its menu or a `sync_now` command).
- ICS requests send `If-None-Match`/`If-Modified-Since`; a `304` keeps the stored copy. Responses are capped at 10 MiB.
- Failures back off from 1 minute, doubling up to 2 hours. Status per source: `syncing`, `ok`, `failed` (with
  `last_error`), `auth_failed` (HTTP 401/403 or unreadable stored credentials) or `disabled`.
- The window is `calendar.past_days` before and `calendar.future_days` after today in the frame's time zone
  (default ±30 days). Recurrence rules are expanded on the server (RRULE, RDATE, EXDATE, and overrides with
  RECURRENCE-ID; cancelled occurrences are dropped). Time zones come from IANA names, common Windows names and
  embedded VTIMEZONE definitions; floating times use the frame's time zone.

**The `calendar` change** (schema `Calendar`)
- `generated_at`, `timezone`, `window {from, to}`, `sources` (id, kind, name, color, status, last_synced_at),
  `events` and `countdowns`.
- `events` merges every enabled source into one list sorted by start. An event that appears in several sources
  (same UID and start) is kept once, from the first source in display order. Each event keeps its `source_id`;
  clients colour it from that source. Timed events carry `start_at`/`end_at` in UTC; all-day events carry
  `start_date`/`end_date` (inclusive). `recurring` marks occurrences of a series.
- Event ids are stable per occurrence, so countdowns and the frame's UI state survive re-syncs.
- `countdowns` are the marked events that have not passed yet (nearest first, also beyond the window). Mark them
  with `PUT /v1/frames/{id}/countdowns/{eventId}` or, on the frame, `PUT /v1/device/countdowns/{eventId}`. Frames
  show the nearest three as slideshow widgets.
- A new change is emitted only when the content changed (ignoring `generated_at`), and once a day as the window
  moves. `calendar: null` means "drop your calendar" (the last source was removed); snapshots omit it.
- `GET /v1/frames/{id}/calendar` returns the same payload plus the frame's `weather`, for a read-only preview.

**Sending an event** (`POST /v1/frames/{id}/events`)
- Title, all-day or start/end, location, notes and an optional photo (a tus upload, stored as WebP of at most
  1280 px) shown on `top` of the card or as its `background`.
- The frame gets a websocket `event_invitation` message (and re-reads `GET /v1/device/invitations` on connect). It
  shows an invitation card with Accept and Decline: `POST /v1/device/invitations/{iid} {decision}`. The decision
  is final. Accepted events go into the received-events calendar (a `calendar` change follows).
- The sender gets `event_invitation.decided` over the websocket, or a push, and sees the status in
  `GET /v1/frames/{id}/events`. A pending invitation can be withdrawn with `DELETE`.

**Calendar settings** (`FrameSettings.calendar`, `widgets.calendar_sidebar`, `widgets.countdowns`)
- `view` (`four_day`, `week`, `month`), `start_on_today`, `fade_past`, `week_numbers`, `past_days`,
  `future_days` and `timed_views`: schedules (same shape as sleep schedules, with `from`/`to`) during which the
  frame shows the full-screen calendar instead of photos. New schedules default to 07:00–09:00.

**Palette** (`CalendarColor`). Light is for light backgrounds (event fills on the paper style), dark for dark ones.

| Key | Light | Dark |
|---|---|---|
| `coral` | `#E5726A` | `#F0958E` |
| `tangerine` | `#EE8F4F` | `#F4AE7E` |
| `amber` | `#DDA731` | `#E9C26A` |
| `olive` | `#9AA63E` | `#BBC575` |
| `mint` | `#4FB28A` | `#82CDAE` |
| `sage` | `#7F9C77` | `#A8C0A1` |
| `teal` | `#349E9A` | `#6EC1BD` |
| `sky` | `#4F9FDF` | `#87BFEB` |
| `ocean` | `#4767C8` | `#8197DD` |
| `lavender` | `#8E7BD0` | `#B3A6E2` |
| `plum` | `#AC5F9F` | `#CA92C1` |
| `rose` | `#DA6C92` | `#E79AB5` |

New sources get the first colour not yet used on the frame, in this order.

### 5.9 Showing a frame on a TV

Product spec 4.11 (Google Cast / DLNA). The library is shown on a TV through a **TV link**: a short-lived,
revocable share token.

- `POST /v1/frames/{id}/tv-links {album_id?, placement_ids?, kind?, interval_seconds, expires_in_hours, purpose}`
  returns `{token, url, expires_at, …}`. `url` is the web app's full-screen TV page, `/tv/<frameId>?t=<token>`.
  - Needs `see_library`, unless every `placement_ids` entry is the caller's own (e.g. "Show on TV" from History).
  - Default expiry is 12 h (max 7 days). `GET` lists active links (owner/admin see all) and `DELETE …/{linkId}`
    revokes one.
- `GET /v1/tv/{token}` needs no sign-in. It returns `TvSlideshow`: the frame name, interval and up to 500 slides.
  - Slides are the visible placements, newest first, or the selection in its own order.
  - Each slide carries an **absolute signed URL of the master rendition** (WebP photo or H.264/AAC MP4). A TV
    browser or Chromecast can fetch it without headers, with Range and CORS `*`.
  - URLs live 1–2 h. Clients refetch after `refresh_after` (45 min).
  - The creator's membership and `see_library` are re-checked on every fetch, so leaving the frame or losing the
    permission narrows or kills the link.
  - Revoked or unknown tokens return `404 not_found`; expired ones return `410 link_expired`.
- **Mirror links** (`purpose: "mirror"`) follow the physical frame live instead (§6.4). They need `see_library`
  (a mirror shows whatever the frame shows) and take no `album_id`, `placement_ids` or `kind`. Their `url` adds
  `&mode=mirror`.
- **Web senders**:
  - Chromium browsers use the Google Cast Web Sender SDK with the **Default Media Receiver**, so no receiver app
    registration is needed. The sender drives the slideshow: it loads one slide URL per interval and offers
    play/pause, next/previous, interval and stop.
  - Browsers with the Presentation API present the TV page itself.
  - The fallback is the TV page link (with QR) for a smart-TV browser.
  - Single videos can also use the Remote Playback API.

### 5.10 Import and backup from SD/USB on the frame

- `POST /v1/device/imports/check {sha256: […]}` returns the hashes the frame already has: a live placement whose
  source, master or frame rendition hash matches. The frame skips those before uploading.
- `POST /v1/device/imports` (body = the file, `X-Filename`, optional `X-Captured-At` and `?caption=`) hashes the body.
  - A duplicate returns `200` with the existing placement.
  - A new file returns `202` and joins the library with the frame as sender.
- Restoring a kinframe export from a stick (written by the frame: original-quality files named
  `YYYY-MM-DD_sender_caption.ext` plus a `kinframe-export.json` manifest) uses the same endpoints, with captions
  from the manifest. Files that came from this frame's own renditions are recognised and skipped.
- A converted Frameo frame imports the photos its old app received (`docs/spec/frameo-import.md`) through the same
  endpoint with `?source=frameo&source_key=<Frameo file name>` plus `sender_name`, `received_at`, `fit`,
  `focal_x`/`focal_y`, `hidden` and `rotation` from the old app's database.
  - One item per frame and source key: a known key returns `200` with that placement, even after it was removed, so
    a re-run never duplicates or brings back deleted photos. `imports/check {source, source_keys}` answers
    `known_source_keys`, so a resumed import only uploads what is missing.
  - The frame stays the sender, the frame's owner owns the media, and the item carries `imported_from: frameo` and
    `imported_sender_name`; clients show "Anna · via Frameo" where they would show the sender.

### 5.11 Pre-order shop (optional)

A server operator who sells frames can switch on a small reservation shop with a config file
(`KINFRAME_SHOP_FILE`, see `docs/deploy.md` → *Pre-order shop*). Without it there is no shop at all: discovery has
no `shop` object and every endpoint below answers `404 not_found`. Reservations are pre-orders **without payment**:
the operator emails customers when stock is in, with the final price and a payment link outside kinframe.

- **Discovery** `shop`: `brand` (the consumer brand for shop texts; defaults to the server name), `currency`,
  `vat_percent`, `products[]` (`id`, `name`, `size`, `price_cents` incl. VAT, `max_qty`, `image`, `specs` per
  language), `shipping` (`countries`, `flat_cents`, `free_from_cents`), `estimated_delivery` per language, and
  `seller` (`name`, `email`, `legal`, `privacy_url`). Language maps always have `en`, the fallback. The operator's
  notification address is not published. Optional `domains` (storefront host names) and `default_locale`.
- **Notice, DIY and retailer links** (all optional): `notice` per language is shown prominently at the top of the
  storefront and the shop page, above the products (plain text, a blank line starts a new paragraph; no HTML).
  `elsewhere[]` (`countries`, `retailer`, `product`, `url`, `affiliate`, `secondary`) links to retailers selling a
  frame the convert tool supports. The web app guesses the visitor's country from the browser languages (the first
  region the shop ships to, else the first region). A visitor in a shipping country sees the entries that list
  that country in a "Buy it yourself and convert it" section right after the notice, with the convert tool link;
  its title and text come from `diy` (`title`, `body` per language), else the web app's defaults. Other visitors
  see the entries for countries the shop doesn't ship to (`*` = every other country) under "Outside {countries}?".
  `secondary: true` shows a link as a small text link below the buttons ("Also at bol.com"); the server refuses a
  secondary link when none of its countries has a main link. Affiliate links carry `rel="sponsored"` and a
  disclosure. Language maps need `en` and only use the codes of docs/i18n.md. The plain HTML storefront (below)
  shows the notice and the links for the shipping countries too.
- **Storefront domains**: the operator may serve the shop on its own host names (`domains`), proxied to the same
  server. Discovery is identical on every host (`web` stays the app's origin). On a storefront the web app shows
  only the brand's landing page, `/shop` and `/shop/cancel`, sends every other path to the same path on `web`, and
  calls the API on its own origin. The server uses the request's `Host` (or `X-Forwarded-Host` behind a trusted
  proxy) only for two things: the customer's cancel link points to the storefront the reservation was made on, and
  `index.html` gets the brand's title, description and Open Graph tags in the visitor's language
  (`Accept-Language` among en/nl/fr/de, else `default_locale`), plus a `<noscript>` plain HTML version of the shop
  (notice, DIY links, products, shipping, seller). Unknown hosts are served like the app's own.
- **Reserve** `POST /v1/shop/reservations` (no auth): `name`, `email`, `country` (one of `shipping.countries`),
  optional `postal_code` and `note`, `items[]` (`product_id`, `quantity` 1…`max_qty`; lines for the same product
  are merged), `locale` and `consent: true`. The server prices the order from its own config (the client never
  sends prices): items + `flat_cents` shipping, free when the item subtotal is at least `free_from_cents`. All
  field problems come back at once as one `422` with `errors[]` (`email`/`format`, `country`/`enum`,
  `items`/`minItems`, `items.<i>.product_id`/`enum`, `items.<i>.quantity`/`maximum`, `consent`/`required`).
  `201` returns the receipt with a short reference like `KF-7Q3M` (prefix configurable; no look-alike characters).
  - Spam: 10 requests per hour per IP (`429 rate_limited`, `Retry-After`), and a honeypot field `website` that
    people never see. A filled honeypot gets a normal-looking `201` and nothing is stored or sent.
  - Mail: a confirmation to the customer in their language (English, Dutch, French or German; others get English)
    and a notification to the operator's `notify_email`, both plain text + simple HTML, no images or tracking.
    They go out in the background over `KINFRAME_SMTP_*`; without SMTP the reservation is stored and a warning
    is logged.
- **Cancel** `POST /v1/shop/reservations/cancel {token}` (no auth). The email links to the web page
  `/shop/cancel?token=…`, which asks for confirmation first (mail scanners open links) and then calls this. The
  token is the reservation ID with an HMAC from the server's signing key; it doesn't expire, the reservation's
  deletion ends it. Idempotent; the operator is notified once.
- **Operator** (`kinframe:operate`): `GET /v1/operator/reservations[?status=]` (newest first),
  `PATCH /v1/operator/reservations/{id}` with `status` (`new`, `contacted`, `confirmed`, `cancelled`) and/or
  `internal_note` (`null` clears it; never emailed), and `GET /v1/operator/reservations.csv[?status=]` (UTF-8
  with BOM, one column per product, cells starting with `= + - @` prefixed with `'`). The web app shows them under
  Settings → Shop reservations.
- **Retention**: the maintenance loop deletes reservations `retention_days` (default 365) after their last change,
  and cancelled ones 30 days after the cancellation. No IP addresses are stored; the time of consent is.

### 5.12 Feeds and subscriptions

Product spec: `docs/spec/feeds.md`; tables and the placement algorithm: `docs/spec/feeds-data-model.md`. Discovery
says `feeds: true` when the server supports them.

**Feeds and members** (`/v1/feeds`)
- A feed has a name, an optional cover (`cover_item_id`, one of its own items) and members with roles `owner` (one),
  `admin` and `poster`. Feed membership is independent of frame membership. Unknown feeds and feeds the caller is
  not a member of both answer `404 not_found`; there is no listing beyond `GET /v1/feeds` (your own feeds).
- Members: `GET /feeds/{id}/members`, `PATCH …/members/{userId} {role}`, `DELETE …/members/{userId}?remove_posts=`,
  `POST /feeds/{id}/leave {remove_my_posts}`, `POST /feeds/{id}/transfer {user_id}`. Admins cannot change or remove
  the owner or other admins (as for frames).

**Feed invites**
- `POST /v1/feeds/{id}/invites {purpose, max_uses, expires_in_hours, note}` (owner/admin) returns `token`,
  `url` (`https://<web>/fi/<token>`, shown as QR) and `code` (8 Crockford base32 characters shown as `KF7Q-M2XA`;
  never all digits, so one "Enter a code" field tells them from 8-digit frame codes).
- `GET /v1/feed-invites/{token}` previews; `POST /v1/feed-invites:lookup {code}` resolves a code to the same preview
  including the token; `POST /v1/feed-invites/{token}/accept` redeems. Rate limits as for frame codes.
- `purpose: join`: the caller becomes a poster, or `pending` (a `JoinRequest`, decided with
  `POST /feeds/{id}/join-requests/{rid}/{approve|deny}`) when the feed has `invite_requires_approval` (default).
- `purpose: subscribe`: the caller picks frames they manage (`frame_ids`, plus `history`); the subscriptions are
  active at once because an admin made the link. The caller does not join the feed.

**Subscriptions**
- `POST /v1/feeds/{id}/subscriptions {frame_id, history, history_count}`: the caller is a feed member and a frame
  manager (owner, admin or `change_settings`). `active` at once for feed owners/admins or with
  `auto_approve_subscriptions`, else `pending` until `POST /v1/subscriptions/{sid}/approve` or `/reject` by a feed
  owner/admin.
- Frame side (frame managers): `POST …/pause`, `POST …/resume`, `PATCH /v1/subscriptions/{sid} {history,
  history_count}`, `DELETE` (unsubscribe, or withdraw a pending request). Feed owners/admins may `DELETE` any
  subscription. Lists: `GET /v1/feeds/{id}/subscriptions` and `GET /v1/frames/{id}/subscriptions` (`?status=`).
- States: `pending → active ⇄ paused`, `pending → rejected`, anything live `→ removed`. Repeating the transition
  the subscription is already in returns it unchanged; impossible ones answer `409 invalid_transition` (approve or
  reject of a decided request: `409 already_decided`). A second live subscription for the same frame and feed:
  `409 already_subscribed`.
- `history`: `none`, `last_n` (`history_count` 1–1000 items) or `all` (capped at `limits.max_feed_backfill_items`),
  counted back from the first activation (`activated_at`). It is history only, not a rolling limit. Changing it
  backfills or trims in the background (`Subscription.backfill`); posts after `activated_at` are never trimmed.

**Posting and placements**
- `POST /v1/sends` with `feed_ids`: one item per upload as always, one **post** per feed, and one placement per item
  on every `active` subscribed frame (`origin: feed`, `origin_feed_id`). The frame's moderation applies as for direct
  sends; renditions are made per screen size and reused.
- One live placement per item and frame: if the item also reaches the frame directly or through another feed, the
  existing placement is shared and `feed_ids` lists every feed holding it. It leaves the frame only when no path
  holds it any more.
- History placements (activation, history change, resume) have `backfill: true` and `received_at` = post time;
  frames don't show them as new. Frames announce that they handle this with `features: ["feed_backfill"]` in the
  heartbeat; until a frame's latest heartbeat does, its backfilled placements are withheld from its change feed
  (older apps would show the whole history as new) and are emitted as soon as it does.
- Pause: the feed's placements leave the frame (rendered as `placement.remove`) but stay on the server; resume brings
  them back and quietly adds what was posted meanwhile. Unsubscribe or removal removes them. Placements that the
  frame purged or whose moderation was rejected are never placed there again.
- `DELETE /v1/feeds/{id}/posts/{pid}` (poster or feed owner/admin) and `…/items/{itemId}` remove posts everywhere;
  `DELETE /v1/sends/{id}` removes the send's posts too. Feed renames re-emit the feed's placements (new
  `origin_feed_name`). Ending a subscription resets `settings.slideshow.feed_id` when it pointed at that feed.

**Receipts and reactions**
- `GET /v1/feeds/{id}/posts` lists posts (`FeedPost`) newest first. `receipts` (`FeedReceiptSummary`: `frames`,
  `delivered`, `seen`, `awaiting_approval`, `paused`, `removed`) and `reactions` (with frame names) are filled on the
  caller's own posts, on all posts for feed owners/admins, and for everyone when `members_see_reactions` is on;
  otherwise null. `GET …/posts/{pid}/receipts` has the per-frame, per-item detail.
- `Send.feeds[]` gives the poster the same summary in the sent history; `Send.frames[]` stays the direct
  recipients.
- Reactions and quick replies on feed placements are stored per placement and go to the item's sender, as today.

### 5.13 Birthdays

Product spec: `docs/spec/birthdays.md`. Discovery says `birthdays: true` when the server supports them (only with
`feeds: true`).

**The list** (`/v1/feeds/{id}/birthdays`, tag `feeds`)
- A `BirthdayPerson` has `display_name`, `day`, `month`, optional `birth_year`, `show_age` (default false), an
  optional photo and an optional `user_id` (a member of the same feed). People, not accounts.
- `POST` by the owner or an admin (or a poster when the feed has `posters_can_add_birthdays`) creates it `approved`;
  any other poster creates a `pending` suggestion, decided with `POST …/birthdays/{bid}/approve` or `/reject` (owner,
  admin). Repeating the current decision returns the entry; deciding a decided one is `409 already_decided`.
- `PATCH …/birthdays/{bid}` and `DELETE` follow `birthdays.md` §4.1: admins anything, suggesters their pending
  suggestion, posters with the setting what they added, the linked member the privacy fields of their own entry
  (`birth_year`, `show_age`, photo, unlink) and removing it.
- Photo: `PUT …/birthdays/{bid}/photo` with a JPEG/PNG/WebP body, exactly like `PUT /v1/me/avatar` (square crop,
  256×256 WebP at a public unguessable URL); `DELETE` falls back to the linked member's avatar.
- Uniqueness: within a feed, pending and approved entries are unique on normalized name (NFKC, case-folded, without
  diacritics, collapsed spaces) + day + month: `409 duplicate_birthday` with the existing id in `detail`. Limit
  `limits.max_birthdays_per_feed` (default 200, pending and approved count): `409 limit_reached`.
- Dates: 29 February is valid (without a year, or with a leap year); impossible dates answer `422 validation_failed`
  (`day`), years before 1900 or after this year `422` (`birth_year`).
- `birth_year` is returned to owner/admins, the entry's creator and the linked member, and to everyone in the feed
  when `show_age` is on; otherwise it is null. `next_birthday` and `turns` are computed in the caller's
  `User.timezone`.
- Feed setting `posters_can_add_birthdays` (`Feed`, `FeedCreate`, `FeedUpdate`); `Feed.birthday_count`,
  `pending_birthday_count` (owner/admins) and the caller's own `birthday_notifications`, changed with
  `PATCH /v1/feeds/{id}/preferences {birthday_notifications}` (default true).

**Subscriptions**
- `Subscription.birthdays` (default true), set in `requestSubscription`, `acceptFeedInvite` (subscribe invites) and
  `PATCH /v1/subscriptions/{sid} {birthdays}` by the frame side. `history` is no longer required in that patch;
  omitting it changes nothing about history. A frame shows the approved people of its `active` subscriptions with
  `birthdays: true`; pending, paused and removed subscriptions contribute nothing.
- `Subscription.frame.birthdays_supported` is true when the frame's latest heartbeat lists `features: ["birthdays"]`,
  so apps can say "update the frame app to show birthdays". Nothing is withheld from older apps.

**The `birthdays` change** (schema `FrameBirthdays`)
- One compacted entry per frame, like `calendar`: `generated_at` and `people`, each a `FrameBirthday` (`id`, `name`,
  `day`, `month`, `birth_year` only when the age may be shown, `photo_url`, `feed_ids`, `birthday_ids`), sorted by
  month and day. `birthdays: null` means "drop your birthdays"; snapshots omit it then.
- People listed by several of the frame's feeds are merged on normalized name + day + month. The primary (`id`,
  `name`, photo) is the entry with a photo from the earliest-activated subscription (else the earliest-activated one);
  `birth_year` is present only if every merged entry with a year has `show_age` on and the years agree.
- Emitted when the content changes: an approved entry is created, edited, removed or its photo changes, a suggestion
  is approved, a linked member's avatar that serves as photo changes, a subscription becomes active, pauses,
  resumes, ends or flips `birthdays`, or the feed is deleted. Never daily: the frame computes "today", "in 3 days",
  ages and the 29 February rule in its own `locale.timezone`, and hides all of it while its clock is not trusted.
- Photos are public URLs (no device token); the frame caches them by URL and deletes those no entry uses.

**Greetings** (frame → family)
- `PUT /v1/device/birthdays/{bid}/greetings/{date}` (device token): `bid` is `FrameBirthday.id` (or any of its
  `birthday_ids`), `date` the frame's local date. Idempotent, one per person, frame and date (`204` when recorded now
  or before). The server records a `FeedBirthdayGreeting` in every feed that lists the person, with a snapshot of the
  frame's name and location.
- `404 not_found` when the person is no longer on the frame; `422 validation_failed` (`date`) when `date` is not
  their birthday or lies outside the server's UTC date −2 … +1 days; `429` beyond 50 a day per frame. The frame
  queues greetings offline in its outbox and drops them after two days or on 404/422.
- `GET /v1/feeds/{id}/birthday-greetings?birthday_id=&date=&cursor=&limit=` lists them for every feed member, newest
  first (kept 13 months). `BirthdayPerson.greetings_today` counts today's.

**Morning notification**
- Once per member and local day, at 08:00 in `User.timezone` (set by the apps with `PATCH /v1/me {timezone}`; without
  one, the most common `locale.timezone` of the feed's subscribed frames, then `Europe/Brussels`), catching up until
  20:00 if the server was down. It merges the member's birthdays of that day from every feed where their
  `birthday_notifications` is on, deduplicated like on frames, without the member's own entry.

### 5.14 Photo sources and Google Photos import

Product spec: `docs/spec/photo-sources.md`. Discovery says `photo_sources: true` (with the allowed
`photo_source_providers`) when albums and feeds can sync from Immich or iCloud, `photo_sources_private_networks` when
sources may use home-network addresses, and `google_photos_import: true` when the operator configured a Google OAuth
client. Clients hide what discovery doesn't offer.

**Sources** (`/v1/photo-sources`, tag `photo-sources`)
- A `PhotoSource` connects one outside album to one target: an **album** (`album_id`; albums belong to one frame, so
  this is that frame's album; album managers: owner, admin, `manage_library`) or a **feed** (`feed_id`; feed
  owner/admins). The caller becomes the `owner`: imported photos are sent by them.
- Providers: `immich_link` (`url` = an Immich shared link `https://<immich>/share/<key>`, optional `password`;
  recommended), `immich_api` (`server_url` + `api_key` with `album.read`, `asset.read`, `asset.view`,
  `asset.download`, + `immich_album_id`), `icloud_link` (`url` = `https://www.icloud.com/sharedalbum/#<token>` with
  Public Website on; `best_effort: true`: show the note).
- Connect flow: `POST /v1/photo-sources:test` (any provider; for `immich_api` without an album it returns `albums`),
  or `POST /v1/photo-sources:immich-albums {server_url, api_key}` to list albums; then `POST /v1/photo-sources`
  (tests again, `422 source_test_failed` with the `PhotoSourceErrorCode` in `detail`). Settings: `history`
  (`none`/`last_n` + `history_count`/`all`; defaults `all` for albums, `last_n` 20 for feeds), `on_upstream_delete`
  (`mirror` default for albums, `keep` for feeds), `include_videos`, `notify_reactions`.
- Secrets (`url`, `api_key`, `password`) are write-only and sealed like calendar credentials; responses carry
  `url_hint`, `server_url`, `has_api_key`, `has_password`. A blocked private address is `422 validation_failed` on
  `url`/`server_url`; the same outside album twice on one target `409 duplicate_source`; limits `409 limit_reached`.
- Lists: `GET /v1/frames/{id}/albums/{aid}/photo-sources` (album managers), `GET /v1/feeds/{id}/photo-sources`
  (feed owner/admins), `GET /v1/photo-sources` (the sources the caller owns). `Album.photo_source_count` tells every
  library viewer that an album syncs.
- Change: `PATCH /v1/photo-sources/{sid}`: `paused` (pause/resume), `name`, connection fields (tested, then synced at
  once), settings, `take_over` (become owner after `owner_lost_access`; `immich_api` needs a new `api_key`).
  `POST …/sync` syncs now (at most once per 2 minutes per source, `429`); with `{confirm_removals: true}` it applies
  removals held back by the mass-removal guard. `DELETE …?remove_items=true|false` stops syncing and keeps or removes
  the imported photos (clients ask). `deleteAlbum?remove_source_items=` asks the same for an album with sources.

**Status** (`PhotoSource.status`, `running`, `last_error_code`, `warnings`, `counts`, `last_run`, `next_sync_at`)
- `active` (green; a retried single failure keeps it active with `last_error_code` set), `error` (keeps retrying with
  backoff up to 6 h), `needs_attention` (a person must act: show the fix for `last_error_code`), `paused`. `running`
  is a spinner, not a state. Warnings (`downloads_disabled`, `metadata_hidden`, `item_limit_reached`) don't stop
  syncing. User-facing texts per code: `photo-sources.md` §13.
- `counts`: `upstream`, `imported`, `duplicates`, `pending` (backlog, "importing 40 of 220"), `skipped`, `failed`,
  `removed_locally`, `gone_upstream`.
- `photo_source.updated` (websocket) carries the whole `PhotoSource` when a run starts, when it finished with changes or an error, and on every state change, to the target's
  managers and the owner; `photo_source.removed` when one is deleted.

**What a sync produces**
- One **send** by the owner per run with new photos (`Send.via`), placed on the album's frame and appended to the
  album, or posted to the feed as one post per at most `limits.max_items_per_send` items (`FeedPost.via`). The first
  run's photos are history (`Placement.backfill: true`). Moderation, renditions, receipts, reactions and unsend are
  as for any send. Source sends get no `delivered` push.
- `Item.via` (`ItemVia {service, label, source_id}`) is the credit: "Anna via Immich", "Anna · Peeters kids via
  Immich". Clients that don't know it show the sender only.
- Removing a synced photo on kinframe (purge, moderation reject, out of the album, out of the feed post) means it is
  never imported again from that source.

**Google Photos import** (`/v1/google-imports`)

A one-time copy of photos the person picks in Google's picker (Google offers no library access to apps any more).
Album managers import into an album; any feed member into a feed.

```
app/web                                   server                                Google
 │ POST /v1/google-imports {album_id|feed_id, return_uri}
 │ ◀── 201 {status: needs_auth, auth_url}        (or status: picking + picker_uri when a fresh token exists)
 │ open auth_url in a browser session ─────────────────────────────────────────▶ consent
 │                                     GET /v1/oauth/google/callback ◀──────────── redirect (code, state)
 │                                     token exchange, picker session ───────────▶
 │ ◀── 302 return_uri?import_id=…&status=picking
 │ GET /v1/google-imports/{id} → picker_uri
 │ open picker_uri ────────────────────────────────────────────────────────────▶ pick, Done
 │                                     polls the session, downloads the items ──▶
 │ ◀── ws google_import.updated {status: importing, progress} … {status: done}
```

- `return_uri` is one of `app.kinframe:/google-import` (Android and iOS), `https://<server>/google-import/return`
  (app/universal link) or `<web>/google-import/return` (web). The redirect adds only `import_id` and `status`.
- **Web**: open `auth_url` in a popup; the return page reports to the opener (`postMessage`) and then navigates the
  same popup to `picker_uri + "/autoclose"`, which closes itself after Done.
- **Android**: consent in a Custom Tab (Google refuses embedded web views); the custom-scheme redirect brings the app
  back. Open `picker_uri` with `ACTION_VIEW`: the Google Photos app handles it when installed, else a Custom Tab. When
  the app resumes, refresh the import (or follow the websocket).
- **iOS**: consent in `ASWebAuthenticationSession` with callback scheme `app.kinframe`; the redirect ends the
  session. Open `picker_uri` with `UIApplication.open` (the Google Photos app when installed) or
  `SFSafariViewController`; refresh on foreground.
- Clients never call Google's API and never see a Google token. If the app was closed while picking,
  `GET /v1/google-imports` (last 7 days) finds the import again.
- `GoogleImport.status`: `needs_auth → picking → importing → done`, or `failed` (`error_code`), `expired` (nothing
  picked before Google's session ended) and `cancelled` (`POST …/{id}/cancel`; photos already added stay, undo with
  `DELETE /v1/sends/{id}` for each of `send_ids`). `progress`: `picked`, `imported`, `duplicates`, `skipped`,
  `failed`, `bytes`. At most `limits.max_google_import_items` per import; posts of at most 200.
- The server keeps no long-lived Google token: an access token lives on the import for at most 2 hours and is reused
  by the same user's next import within that hour.

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
| S→F | `event_invitation` | `invitation` | An event was sent to the frame: show the invitation card. Pending ones also come from `GET /v1/device/invitations`. |
| S→F | `revoked` | — | Wipe credential, back to setup. Followed by close `4401`. |
| F→S | `state` | `screen: on/off/dimmed`, `showing_placement_id`, `brightness`, `volume` | Sent on change, at most every 2 s. |
| F→S | `command_result` | `id`, `ok`, `error_code` | Result of a command. |
| F→S | `now_showing` | schema `NowShowing`: `placement_id`, `placement_ids`, `started_at`, `transition`, `transition_ms`, `direction`, `ken_burns`, `hold_ms`, `video {position_ms, playing, live}`, `next_placement_id`, `screen`, `sent_at` | What the screen shows (TV mirror, §6.4). Sent on every slide change (timer, swipe, remote commands, the "N new" card), when a video starts, pauses or ends, every 15 s while one plays, when the screen sleeps or wakes, and again after a reconnect. Times are the frame's clock; the server only uses their differences. |

Health data (Wi-Fi RSSI, storage, uptime, app version, temperature if known) goes to `POST /v1/device/heartbeat`
every 5 minutes, not over the websocket.

### 6.2 Users: `GET /v1/ws?access_token=…`

Browsers can't set headers on websockets, so the token goes in the query. It is validated on connect only, and the
server closes with `4401` at token expiry so the client reconnects with a fresh token.

| `type` | Payload | Meaning |
|---|---|---|
| `send.updated` | `send` (summary) | Processing, delivered or seen counts changed |
| `reaction` | `placement_id`, `frame_id`, `reaction`, `by_frame_name`, `feed_id`?, `post_id`? | New or changed reaction (feed ids when the placement came through a feed) |
| `reply` | `placement_id`, `frame_id`, `preset`, `feed_id`?, `post_id`? | Quick reply from a frame |
| `share.delivered` | `frame_id`, `frame_name` | Everything the user shared to that frame (`sharePlacements`) arrived |
| `frame.status` | `frame_id`, `online`, `last_seen_at` | Frame connectivity |
| `join_request` | `frame_id`, `request` | Approval needed (owner/admin) |
| `membership.updated` | `frame_id` | Role or permissions changed |
| `event_invitation.decided` | `frame_id`, `invitation` | The frame accepted or declined an event you sent |
| `feed_post.updated` | `feed_id`, `post_id`, `summary` (`FeedReceiptSummary`) | Delivery or seen counts of a feed post changed (the poster and feed owner/admins) |
| `subscription.request` | `feed_id`, `subscription` | A frame asks to subscribe (feed owner/admins) |
| `subscription.updated` | `feed_id`, `frame_id`, `subscription` | Approved, rejected, paused, resumed, history changed or removed (the requester, feed owner/admins and the frame's managers) |
| `feed.join_request` | `feed_id`, `request` | Approval needed (feed owner/admins) |
| `feed.membership_updated` | `feed_id` | Your feed role changed, you were added or removed, or the feed was deleted |
| `birthday.suggested` | `feed_id`, `birthday` (`BirthdayPerson`) | A poster suggested a birthday person (feed owner/admins) |
| `birthday.decided` | `feed_id`, `birthday` | Your suggestion was approved or rejected (the suggester) |
| `birthday.updated` | `feed_id`, `birthday` | A birthday person was added, changed, approved or rejected (feed members who may see it) |
| `birthday.removed` | `feed_id`, `birthday_id` | A birthday person was removed (feed members who could see it) |
| `birthday.today` | `date`, `birthdays` (`[{feed_id, birthday_id}]`) | The morning notification (§5.13), on the websocket when one is open |
| `birthday.greeting` | `feed_id`, `greeting` (`FeedBirthdayGreeting`) | A frame said happy birthday (feed members) |
| `photo_source.updated` | `source` (`PhotoSource`) | A photo source's status, counts or last run changed (the target's managers and the source owner; §5.14) |
| `photo_source.removed` | `source_id`, `album_id`?, `feed_id`? | A photo source was deleted, also with its album or feed (the same people) |
| `google_import.updated` | `import` (`GoogleImport`) | Status or progress of the caller's Google Photos import changed |
| `email_address.updated` | `address` (`EmailAddress`) | An inbox address was created, changed, paused, regenerated or taken over, or its counts changed (the target's managers; §5.16) |
| `email_address.removed` | `address_id`, `frame_id`?, `feed_id`? | An inbox address was deleted, also with its frame or feed (the same people) |
| `email_sender.pending` | `address_id`, `pending` (`EmailPendingSender`) | Someone not yet allowed mailed the address, or mailed again (the target's managers) |
| `email_sender.decided` | `address_id`, `sender_id`, `decision` (`approved`, `rejected`, `blocked`, `added`, `removed`, `unblocked`) | A pending sender was decided or the sender list changed (the target's managers) |
| `telegram_chat.updated` | `chat` (`TelegramChat`) | A Telegram chat was connected, changed, paused, taken over or disconnected, or its status or counts changed (the target's managers and the owner; §5.17) |
| `telegram_chat.removed` | `chat_id`, `frame_id`?, `feed_id`? | A disconnected chat was deleted after 30 days, or its frame or feed was deleted (the same people) |
| `telegram_code.updated` | `code` (`TelegramCode`) | The caller's connect or link code was used, or redeeming it failed |
| `telegram_link.updated` | `link` (`TelegramLink` or null) | The caller's Telegram account was linked, unlinked, or blocked the bot |
| `post.updated` | `post_id`, `feed_id`?, `frame_id`?, `thread` (`ThreadSummary` for the receiver) | A post's thread state, comment count or reaction row changed (everyone who sees the post; §5.18) |
| `post.removed` | `post_id`, `feed_id`?, `frame_id`? | A post was removed or is no longer visible to the receiver |
| `comment.created` | `post_id`, `comment` (`Comment`) | A new comment, reply or frame entry (everyone who sees the post, not people who blocked the author) |
| `comment.updated` | `post_id`, `comment` | Edited, or deleted into a tombstone |
| `comment.deleted` | `post_id`, `comment_id` | Gone from the thread (author delete without replies, or moderator removal) |
| `thread.read` | `post_id`, `read_up_to` | The receiver read the thread on another device |
| `reaction.updated` | `target_kind`, `target_id`, `post_id`?, `conversation_id`?, `summary` (`ReactionSummary` for the receiver) | People's reactions on a post, item, comment or message changed |
| `circle.updated` | `feed_id`, `circle` (`Circle`) | Created, renamed, members or frames changed (circle members, the feed's owner/admins, and people just removed) |
| `circle.removed` | `feed_id`, `circle_id` | A circle was deleted |
| `conversation.updated` | `conversation` (`Conversation` for the receiver) | Created, renamed, photo, members, mute, archive or read-only changed (the conversation's members) |
| `conversation.removed` | `conversation_id` | The receiver left or was removed, or the family or circle is gone for them |
| `message.created` | `conversation_id`, `message` (`Message`) | A new message (members who can see it, not people who blocked the author) |
| `message.updated` | `conversation_id`, `message` | Edited, items ready, link preview fetched, frame activity, or deleted into a tombstone |
| `read.updated` | `conversation_id`, `user_id`, `last_read_message_id` | Someone's read position (read receipts rules, §5.19; always for the receiver's own other devices) |
| `typing` | `conversation_id`, `user_id`, `display_name`, `activity` (`typing`/`recording`), `expires_at` | Someone is typing; never stored |
| `badge.updated` | `badge` (`Badge`) | The app badge changed |
| `report.created` | `report` (`Report`) | A new report to handle (the feed's owner/admins, or operators) |
| `photo_request.created` | `request` (`PhotoRequest`) | A frame asked for new photos (its recipients, §5.20) |
| `photo_request.updated` | `request` | Answered or expired |
| `streak.updated` | `feed_id`, `streak` (`FeedStreakSummary`) | This week's count, the goal or the streak changed (family members) |
| `prompt.updated` | `feed_id`, `prompt` (`FeedPrompt`) | The week's prompt started, or an admin changed it |
| `milestone.achieved` | `milestone` (`Milestone`) | The receiver reached a personal milestone (websocket only) |

### 6.3 Push

- The same events are sent as push notifications when the user has no open websocket, through registered endpoints:
  `POST /v1/me/push-endpoints {kind: "unifiedpush"|"fcm"|"apns"|"webpush", endpoint_or_token, ...}`.
- Payloads carry only IDs and a localized title and body. Clients fetch details over the API.
- **WebPush** (browsers): subscribe with `PushManager.subscribe({userVisibleOnly: true, applicationServerKey})`
  using discovery's `webpush_public_key` (VAPID, RFC 8292), then register `{kind: "webpush", endpoint,
  webpush_keys: {p256dh, auth}, locale}`. Messages are encrypted with RFC 8291 (`aes128gcm`). The decrypted payload is
  `{type, key, title, body, ids}` (`ids` may hold `send_id`, `placement_id`, `frame_id`, `request_id`, `feed_id`,
  `post_id`, `subscription_id`, `birthday_id`, `greeting_id`, `source_id`, `album_id`, `import_id`, `address_id`,
  `sender_id`, `chat_id`, and for family social `comment_id`, `parent_id`, `conversation_id`, `message_id`,
  `target_kind`, `target_id`, `circle_id`, `report_id`, `request_id`, `week_of`, `month`). Payloads may also carry
  `tag` (a newer push with the same tag replaces the older one) and `actions` (notification buttons). Endpoints the
  push service reports as gone (404/410) are deleted by the server.
- Feed keys: `subscription_request` (feed owner/admins), `subscription_decided` (the requester),
  `subscription_removed` (frame owner/admins, when a feed admin removed it or the feed was deleted),
  `feed_join_request` (feed owner/admins), `feed_membership` (the member); reactions and replies on feed placements
  use `reaction`/`reply` with `feed_id` and `post_id`. Feed posts get **no** `delivered` push (it would repeat per
  subscribed frame); `feed_post.updated` on the websocket and the receipts cover it.
- Birthday keys (§5.13): `birthday_suggestion` (feed owner/admins), `birthday_decided` (the suggester; texts
  `birthday_approved`/`birthday_rejected`), `birthday_today` (the morning notification) and `birthday_greeting`
  (members with `birthday_notifications` on for that feed; one per greeting even for members of several feeds that
  list the person). `ids` may hold `feed_id`, `birthday_id`, `greeting_id`, `date` (the birthday's local date,
  `YYYY-MM-DD`) and, for greetings, `frame_id`. Greeting pushes also carry a top-level `tag`
  (`birthday:<birthday_id>:<date>`, the same for every frame's greeting of that person that day); clients use it as
  the notification tag (and APNs `apns-collapse-id` / FCM tag, once those exist) so a newer one replaces the older (its body counts the frames: "Opa's frame and 2
  other frames say happy birthday to Oma"). `birthday.updated`/`birthday.removed` are websocket only.
- Photo source keys (§5.14): `photo_source_attention` (the source's owner only: it needs attention, or has been in
  `error` for 3 days; at most once per source and error code per 7 days; `ids`: `source_id` and `frame_id`/`album_id`
  or `feed_id`) and `google_import_done` (the importer, once, when an import finished while they had no websocket;
  `ids`: `import_id`). New photos from sources are **never** pushed, and source sends get no `delivered` push.
  Reactions and replies on source photos go to the owner as `reaction`/`reply`, unless the source has
  `notify_reactions: false`.
- Email keys (§5.16): `email_pending_sender` (the address's managers: frame owner, admins and `change_settings`
  members, or feed owner and admins; once per pending sender per 7 days, at most 10 per address a day; text
  `email_pending_expiring` once, 3 days before the held mail is deleted; `ids`: `address_id`, `sender_id`,
  `frame_id` or `feed_id`). Delivered mail is never pushed, and email sends get no `delivered` push. Join requests
  from email join links use `join_request`/`feed_join_request`.
- Telegram key (§5.17): `telegram_attention` (the chat's owner only: the chat needs attention, the bot was removed or
  the group is gone, or ownership passed to them; at most once per chat and code per 7 days; `ids`: `chat_id`, and
  `frame_id` or `feed_id`). Photos from Telegram are never pushed; sends of outside contributions get no `delivered`
  push. Join requests from Telegram join links use `join_request`/`feed_join_request`.
- Family social keys (§5.18, §5.19, §5.20), all respecting mutes and blocks as described there:

  | Key | To | `ids` | `tag` |
  |---|---|---|---|
  | `comment` | the poster and earlier commenters | `post_id`, `comment_id`, `feed_id` or `frame_id` | `comment:<post_id>` (10-minute bundle) |
  | `comment_reply` | the top comment's author, the quoted person | `post_id`, `comment_id`, `parent_id`, `feed_id` or `frame_id` | `comment_reply:<parent_id>` |
  | `mention` | the mentioned person | `post_id` + `comment_id`, or `conversation_id` + `message_id` | — |
  | `reaction_person` | the target's author (first 3 people per target) | `target_kind`, `target_id`, `post_id`?, `conversation_id`? | `reaction:<target_kind>:<target_id>` |
  | `reaction_digest` | authors with more reactions that day (19:00 local) | — | `reaction_digest` |
  | `message` | conversation members | `conversation_id`, `message_id` | `conversation:<conversation_id>` |
  | `conversation_added` | someone added to a group | `conversation_id` | — |
  | `report` | the report's handlers | `report_id`, `feed_id`? | — |
  | `frame_request` | §5.20 | `frame_id`, `request_id` | `frame_request:<frame_id>` |
  | `reminder_quiet` | §5.20 | `frame_id` | `reminder_quiet:<frame_id>` |
  | `reminder_streak` | §5.20 | `feed_id`, `week_of` | `reminder_streak:<feed_id>` |
  | `reminder_birthday` | §5.20 | `feed_id`, `birthday_id`, `date` | `reminder_birthday:<birthday_id>` |
  | `weekly_summary` | §5.20 | `week_of` | `weekly_summary` |

  The four reminder keys carry `actions: ["snooze_month", "fewer_reminders"]` (APNs category `kinframe.reminder`).
  System messages, streak milestones, recaps and milestones never push.
- `delivered` is pushed once per send and frame, when the last of its photos reaches that frame.
  Placements shared from another frame count for the sharer instead: they get `delivered` (event `share.delivered`)
  once everything they shared to a frame has arrived.

### 6.4 TV mirror: `GET /v1/tv/{token}/mirror` (no sign-in, a `mirror` TV link)

- The server keeps the latest `now_showing` of every connected frame in memory (nothing is stored) and drops it when
  the frame disconnects.
- A TV page connects with a mirror token and gets a `MirrorState` (`type: "mirror"`) right away and after every change:
  `online`, `screen`, the frame settings it needs to look the same (`MirrorSettings`: interval, transition, Ken Burns,
  fill, background, caption, clock), `showing` (the slides on screen as `TvSlide`s with fresh signed URLs, `started_at`
  and the video position at `at`, both in server time; compare with the message's `server_time`) and `next` (to
  preload). Frame reports are coalesced to at most one render every 250 ms per frame.
- Ken Burns is deterministic per placement id and focal point (`TvSlide.focal_point`), so the TV computes the same
  motion as the frame from `started_at`.
- Limits: at most 8 viewers per frame (`429`), connects count against the TV link rate limit per IP. Every minute the
  link and its creator's `see_library` are re-checked; a revoked or expired link gets `{type: "ended", reason}` and
  close code `4401` (revoking closes it at once). Unknown or non-mirror tokens get `404`, expired ones `410` (a plain
  `GET` of the URL tells a browser why the upgrade failed).
- The remote in the app moves the frame itself (`POST /frames/{id}/commands` `next`/`previous`, `change_settings`), and
  the TV follows.

### 5.15 Family app versions (force update)

The Android, iOS and Mac (Catalyst) family apps identify themselves on every request with
`X-Kinframe-App: kinframe-family-<platform>/<version name> (<build>)`, where `<platform>` is `android`, `ios` or
`macos` and `<build>` is the Android versionCode or the Apple `CFBundleVersion` (an integer). The website sends no such
header and is never blocked: it always serves the current version.

- **Policy**: the operator sets, per platform, `KINFRAME_APP_<PLATFORM>_MIN_BUILD`, `KINFRAME_APP_<PLATFORM>_RECOMMENDED_BUILD`
  and `KINFRAME_APP_<PLATFORM>_STORE_URL` (`ANDROID`, `IOS`, `MACOS`). Discovery returns them as `app_versions`
  (`AppVersionPolicy`); a platform with nothing set is left out.
- **Blocking**: a family-app request whose build is below its platform's `minimum_build` gets
  `426 app_update_required` (problem body as usual), except `GET /v1/discovery` and the public, unauthenticated
  endpoints the app needs to show the update screen. Requests without the header (the website, apps from before this
  feature) are never blocked; so a minimum only reaches apps that send the header.
- **Apps**: on start, on returning to the foreground and after any 426 the app (re)reads discovery. Below
  `minimum_build` it shows a full-screen "Update kinframe" page with a button to `store_url` (or the platform's store
  page for the app) and "Check again"; nothing else is usable. Below `recommended_build` it shows a banner "A new
  version of kinframe is available" with Update and dismiss; a dismissal is remembered per recommended build.
- The frame app has its own mechanism (§5.7: `KINFRAME_MIN_FRAME_APP_VERSION_CODE` and self-update).

### 5.16 Email to frame (inbox addresses)

Product spec: `docs/spec/email-inbox.md`. Discovery says `email_inbox: true` and `email_inbox_domain` when frames and
feeds can have inbox addresses; clients hide "Email photos to this frame" otherwise.

**Addresses** (tag `email-inbox`)
- An `EmailAddress` (`<slug>-<random>@<email_inbox_domain>`, e.g. `oma-k7q2m9@in.kinframe.appmire.be`) fills one
  target: a **frame** (each mail becomes a direct send) or a **feed** (each mail becomes a post, split at
  `max_items_per_send`). Frame addresses are managed by frame managers (owner, admin, `change_settings`), feed
  addresses by the feed's owner and admins. The creator is the `owner`: mail from senders without a matching account
  is sent by them. If the owner loses their rights, the frame's or feed's owner takes over automatically.
- `GET|POST /v1/frames/{id}/email-addresses`, `GET|POST /v1/feeds/{id}/email-addresses` (`EmailAddressCreate`:
  `slug` (default from the target's name), `name` (label), `anyone_may_send` (default off), `send_confirmations`
  (default on), `offer_app_invite` (default on), `notify_reactions`). `409 limit_reached` beyond
  `limits.max_email_addresses_per_target`.
- `PATCH /v1/email-addresses/{id}`: `name`, `slug` (changes the address; the old one stops at once), `paused`
  (mail is refused while paused), settings, `take_over`. `POST …/regenerate`: a new random part, the old address stops
  at once, senders and settings stay. `DELETE` removes it (delivered photos stay). Retired addresses are never
  issued again.
- The address is a capability shown to managers only. Clients offer **Copy** and **Share** with a ready-made message
  in the user's language: "You can now email photos to {name}: {address}. Just attach them; the subject becomes the
  caption. The first time, {owner} approves your address."
- `EmailAddress.replies_available: false` when the server has no outgoing mail: say that senders get no replies.

**Senders**
- Allowed automatically: the target's members (frame members with `send`, any feed member) mailing from their
  **verified account email**. They are never listed (their emails are not shown to managers).
- `GET|POST /v1/email-addresses/{id}/senders` (`EmailSender`: `email`, `display_name`, `state` `allowed`/`blocked`,
  `origin`), `PATCH|DELETE …/senders/{sid}` (rename, block/unblock, remove). Adding an address that is pending approves
  it; `409 duplicate_sender` when it is already listed.
- **Pending senders**: `GET …/pending-senders` (`EmailPendingSender` with `messages`: subject, time, `media_count`,
  up to 4 `thumb_urls`, `expires_at`). `POST …/pending-senders/{sid}/approve {deliver: true}` allows the sender and
  delivers their held mail; `POST …/reject {block}` deletes it silently (and blocks). `409 already_decided` when
  another manager was first.
- **Held mail**: `GET …/held-messages/{mid}` (`EmailHeldMessage`: sender as claimed, subject, caption, `auth`, every
  photo and video with a short-lived `thumb_url`), `DELETE` it, or `POST …/deliver` for one mail. Mail that could not
  be authenticated (`reason: unverified_sender`, `auth.authenticated: false`) is never matched to an account or the
  allow-list; show "We couldn't verify that this really comes from {email}" and offer only **Deliver this one** and
  **Delete**. Held mail is deleted after 14 days.
- **Recent mail**: `GET …/messages` (30 days, cursor): time, sender, `outcome` (`delivered`, `held`, `no_media`,
  `too_large`, `encrypted`, `duplicate`, `blocked`, `rejected_dmarc`, `ignored`, `rate_limited`, `failed`), item
  count, skipped files, the reply the sender got.

**What a mail produces**
- One send per mail (`Send.via {service: "email", label, address_id}`), by the matched account or by the address
  owner. Frame: placements on the frame (new, not quiet). Feed: one post (`FeedPost.via`). Moderation applies; mail
  the owner sends on behalf of a non-account sender is always moderated on a moderated frame. Renditions, receipts,
  reactions and unsend are as for any send; email sends get no `delivered` push.
- Credit: for `ItemVia.service == "email"` show `label` **instead of** the sender's name: "Jan via email",
  "Jan · Peeters kids via email". Clients that don't know `via` show the sender.
- Captions come from the subject (reply/forward markers stripped) or the first body line; files under 300 px,
  duplicates, unsupported, too large or too long files are skipped (`EmailSkipCounts`).

**Join links for senders**
- Replies to allowed, authenticated senders carry a join link (an invite that always needs approval). Its join request
  arrives as usual (`join_request` / `feed.join_request`) with `JoinRequest.via: "email"`, `via_name` (the sender
  as managers see it, "Jan · jan.peeters@telenet.be") and `via_matches` (the requester's verified email is that
  address). `via_name`/`via_matches` are generic for every outside channel (Telegram adds `via: "telegram"`). Show
  "asked to join from the email link" and `via_name` with a check mark when it matches. These invites are not in
  `listInvites` / `listFeedInvites`.

**Webhook for operators** (`POST /v1/inbound/email`, security `inboundWebhook`; not for clients)
- For servers that can't receive mail on port 25: the mail provider (or a small forwarder) posts each message raw
  (`Content-Type: message/rfc822`) with `X-Kinframe-Envelope-To` (required), `X-Kinframe-Envelope-From`,
  `X-Kinframe-Client-Ip` and `X-Kinframe-Helo` when known, `X-Kinframe-Timestamp` and `X-Kinframe-Signature:
  v1=<hex HMAC-SHA256(secret, "v1\n" + ts + "\n" + to + "\n" + from + "\n" + ip + "\n" + helo + "\n" +
  hex(sha256(body)))>`. `202` lists an outcome per recipient (`accepted`, `unknown_recipient`, `paused`,
  `rejected_dmarc`, `too_large`); `401` bad signature, `413` too big. Retry only `429`/`5xx`; duplicates are
  recognised. `POST /v1/inbound/email:check {recipients}` (same signature, JSON body) tells which recipients exist,
  for providers that can reject during their own SMTP dialogue. Examples (Cloudflare Email Worker, Postfix `pipe` to
  `kinframe-server deliver-email`) are in the spec, §6.3 and §14.3.

**Events and notifications**
- Websocket (managers of the target): `email_address.updated` (`address`), `email_address.removed`,
  `email_sender.pending` (`address_id`, `pending`), `email_sender.decided` (`address_id`, `sender_id`, `decision`).
- Push `email_pending_sender` to the managers when someone new is waiting (once per pending sender per 7 days; text
  `email_pending_expiring` 3 days before held mail expires). `ids`: `address_id`, `sender_id`, and `frame_id` or
  `feed_id`. Tapping opens the address's pending list. No push for delivered mail.
- Reactions and replies on mailed photos go to the send's sender (`reaction`/`reply`): the matched account, or the
  address owner unless `notify_reactions` is off.

### 5.17 Telegram (groups and private chats)

Product spec: `docs/spec/telegram.md`. Discovery says `telegram: true` with `telegram_bot_username` when the operator
configured the server's one Telegram bot, `telegram_privacy_mode` when a group must make the bot an admin before it
sees photos, and `telegram_max_file_bytes` (20 MB on Telegram's cloud Bot API). Clients hide every Telegram section
otherwise.

**Connecting a chat** (tag `telegram`)
- A `TelegramChat` binds one Telegram chat to one target: a **frame** (each batch becomes a direct send) or a **feed**
  (each batch becomes a post). Frame chats are managed by frame managers (owner, admin, `change_settings`), feed
  chats by the feed's owner and admins. The creator is the `owner`: contributions from people who aren't linked
  kinframe users are sent by them. If the owner loses their rights, the frame's or feed's owner takes over
  automatically; `PATCH {take_over: true}` lets any manager take over.
- `POST /v1/frames/{id}/telegram-codes` or `POST /v1/feeds/{id}/telegram-codes` (`TelegramConnectCodeCreate`:
  `chat_kind` `group` or `private`, `link_account`) returns a `TelegramCode` with `url` (only in this response):
  `https://t.me/<bot>?startgroup=<code>` for a group, `?start=<code>` for a private chat. Open it (Telegram's app
  handles `t.me` links) and show it as a QR code for another device. Single use, 30 minutes; follow it with
  `telegram_code.updated` or `GET /v1/telegram-codes/{id}` until `status: used` (`chat_id`) or an `error_code`
  (`code_expired`, `already_connected`, `limit_reached`, `wrong_chat_kind`, …). `DELETE` revokes it.
- Three entry points in the apps: **Connect a Telegram group** (`group`), **Send from my Telegram** (`private` with
  `link_account: true`: the caller's own Telegram account is linked and its private chat with the bot sends to the
  target), **Invite someone to send by Telegram** (`private`, share the link with a relative who has no app).
- `GET /v1/frames/{id}/telegram-chats`, `GET /v1/feeds/{id}/telegram-chats` (live first, then disconnected ones for 30
  days), `GET|PATCH|DELETE /v1/telegram-chats/{cid}`. `PATCH`: `paused`, `contributors` (`everyone` default,
  `linked_only`), `frame_replies` (post frames' quick replies into the chat, default off), `notify_reactions` (the
  owner's own switch, default off), `offer_app_invite` (default on), `topic_id` (forum groups: one of `topics`, or
  null for all). `DELETE` disconnects: the bot says goodbye and leaves; photos already sent stay.
- `GET /v1/telegram-chats/{cid}/contributors` (`TelegramContributor`: display name, linked, counts),
  `PATCH …/contributors/{id} {blocked}` ignores one person's media in that chat. Telegram user ids are never exposed.
- **Status**: `active`; `needs_attention` with `error_code` `privacy_mode` (show "Make the bot an admin of the
  group", and `sees_all_messages: false`) or `bot_changed` (connect again); `paused`; `disconnected` with
  `ended_reason` (`disconnected`, `stopped` by `/stop`, `bot_removed`, `chat_gone`, `bot_blocked`, `target_deleted`).
  `warnings`: `cannot_post`, `reactions_off`. Texts per code: `telegram.md` §15.

**Linking a Telegram account**
- `POST /v1/me/telegram/link-code` → `TelegramCode` with `url` `https://t.me/<bot>?start=<code>`; tapping Start in
  the private chat links that Telegram account to the caller. `GET /v1/me/telegram` (`TelegramLink`: display name,
  `username`, `can_message`), `DELETE` unlinks. One Telegram account per kinframe account (`409
  telegram_already_linked`).
- A linked user's media in a connected chat is **their own send** (their name, receipts, reactions, moderation role)
  when they may send to the target (frame member with `send`, any feed member); otherwise it counts as an outside
  contribution.

**What a chat produces**
- A **batch** per chat and contributor (closed after 60 s without new media, after 10 minutes, or at
  `max_items_per_send`; Telegram albums are never split) becomes one send (`Send.via {service: "telegram", label,
  telegram_chat_id}`), by the linked account or by the chat's owner. Frame: placements on the frame (new, not quiet).
  Feed: one post (`FeedPost.via`). Moderation applies; outside contributions are always moderated on a moderated
  frame. Outside-contribution sends get no `delivered` push.
- Credit: for `ItemVia.service == "telegram"` show `label` **instead of** the sender's name, like email: "Jan via
  Telegram", "Jan · Peeters kids via Telegram". Older frames show the sender's name (the owner, for outside
  contributions).
- In the chat the bot confirms with a 👍 reaction, mirrors frame reactions (❤ 😁 🤩 😢) on the message, and replies only
  to errors (too big for the bot, too long, unreadable), rate-limited. Deleting a message in Telegram does **not**
  unsend it (the Bot API reports no deletions); unsend in the app.

**Join links**
- The bot's one-time app message to a new contributor carries "Ask to join", delivered through a private chat with
  the bot so the right Telegram user gets it. It is the email inbox's channel invite (§5.16): single use, 30 days,
  always needs approval, not in `listInvites`/`listFeedInvites`. Its join request arrives as usual with
  `JoinRequest.via: "telegram"`, `via_name` ("Ben · Telegram, Peeters family") and `via_matches: null`; show "asked
  to join from Telegram". Accepting the link also links the requester's Telegram account.

**Telegram's webhook** (`POST /v1/telegram/webhook`, security `telegramWebhook`; not for clients)
- Registered by the server with `setWebhook` when `KINFRAME_PUBLIC_URL` is public HTTPS; otherwise the server
  long-polls and this endpoint answers `404`. The header `X-Telegram-Bot-Api-Secret-Token` must carry the server's
  derived secret (`401` otherwise); the update is stored and acknowledged with `200` at once.

**Events and notifications**
- Websocket: `telegram_chat.updated` (`chat`, to the target's managers and the owner), `telegram_chat.removed`
  (`chat_id`, `frame_id`?, `feed_id`?), `telegram_code.updated` (`code`, to its creator), `telegram_link.updated`
  (`link` or null, to the account).
- Push `telegram_attention` to the chat's owner only: it needs attention, the bot was removed or the group is gone,
  or ownership passed to them; at most once per chat and code per 7 days. `ids`: `chat_id`, and `frame_id` or
  `feed_id`. No push for new photos.
- Reactions and replies on Telegram photos go to the send's sender (`reaction`/`reply`): the linked account, or the
  chat's owner only with `notify_reactions`.

### 5.18 Family social: timeline, threads, reactions, circles

Product spec: `docs/spec/family-social.md` (approved 2026-11-19); the work split and table ownership:
`docs/spec/family-social-work.md`. Discovery says `social: true` (only with `feeds: true`). The apps and the web call
feeds **families**; the API keeps `feed` everywhere (decision A).

**Roles and feed settings**
- `FeedRole` gains `member`: reads, comments, reacts and chats, but does not post photos. `createSend`,
  `forwardSend` and `putMessageOnFrame` with a feed where the caller is `member` answer `403 missing_permission`.
  Members suggest birthdays like posters; `posters_can_invite` does not apply to them. Owner and admins change roles
  with `updateFeedMember` (`admin`, `poster`, `member`; admins only change posters and members).
- `new_members_can_post` (default true): people joining through a join invite or an approved join request become
  `poster`, or `member` when it is off. Existing members keep their role.
- `members_see_reactions` now means: every member sees **frames'** reactions and quick replies (in the reaction
  row, `FeedPost.reactions` and frame entries in threads). Default **on** for new feeds. People's reactions are
  always visible to everyone who sees the post.
- `members_see_receipts` (new, default off): every member sees per-frame receipts (`FeedPost.receipts`,
  `TimelinePost.seen`, `getFeedPostReceipts`). The migration copies each existing feed's `members_see_reactions` into
  it, so what members saw before stays the same.
- `members_can_create_circles` (default on), and the caller's own `mute` (`FeedMute`, via `updateFeedPreferences`).

**Posts**
- Two kinds, one id space (UUIDv7, so ids sort by time), all reachable as `/v1/posts/{postId}`:
  - `feed`: a `FeedPost` (one send in one feed), optionally with a circle as audience (`circle_id`).
  - `frame`: a direct send to one frame, one per send and frame (a forward to another frame adds one). Made for
    sends by people: `sends.via` null, `email` or `telegram` (from `createSend`, `forwardSend`, `putMessageOnFrame`),
    never for photo-source syncs, Google imports, shares (`sharePlacements`) or frame imports. Its items are the
    send's items that are still live on that frame; it is removed when the last one is removed there. Existing
    direct sends were backfilled by the migration.
- Who sees a post (the timeline, `getPost`, its thread, its reactions; everyone else gets `404 not_found`):

  | Post | Visible to |
  |---|---|
  | `feed`, whole family | every member of the feed, any role |
  | `feed`, circle audience | the circle's members, and the poster |
  | `frame` | the sender, and the frame's members with `see_library` (owner and admins always) |

  Removed posts, posts of deleted feeds and posts whose every item was removed are never shown. A frame's moderation
  does not hide a post in the apps (it only keeps it off that frame).
- **Moderators** of a post: for whole-family feed posts the feed's owner and admins; for circle posts the circle's
  admins; for frame posts the frame's owner and admins. Moderators and the poster may close the thread; moderators
  may remove comments and the post (`removeFeedPost` for feed posts; frame posts are removed with the send).

**Timeline** (`getTimeline`)
- Every post the caller may see, newest first by id, no ranking, no collapsing: photo-source and Telegram posts are
  posts like any other and show their `via`. `limit` 20 (max 50); `next_before` is an opaque cursor for `before`.
- Each `TimelinePost` carries the family label (`feed_name`, `circle_name`) or the frame (`frame_name`, shown as
  "To Oma's frame"), the items, `seen` (when receipts are visible), the prompt it answers and `thread`
  (`ThreadSummary`: state, `comment_count`, the two newest comments, unread counts, mute, the reaction row).
- Clients group consecutive posts that answer the same prompt ("6 answers to *Your view right now*"); the server does
  not reorder.

**Threads and comments**
- A thread exists for every visible post; the server creates its row lazily (first comment, reaction, frame entry,
  read mark, mute or close). `listComments` returns the thread **flat, oldest first**, with `after` (a comment id)
  and `has_more`; replies carry `parent_id` and come after their parent, and clients nest them. One level of replies:
  `parent_id` must be a top-level comment of the same post (`422 validation_failed`, field `parent_id`); to answer a
  reply, send the top comment as `parent_id` and the person as `quoted_user_id` (they must have commented in this
  thread).
- Who may comment, reply and react: everyone who may see the post, any feed role (frame posts: the sender and
  `see_library` members). Closed threads (`setThreadState`) answer `409 comments_closed` to new comments; edits,
  deletes and reactions still work.
- Bodies: 1–2000 characters (Unicode code points, trimmed, `limits.max_comment_chars`). Links stay plain text (no
  previews in comments). The author edits at any time (`edited_at`); mentions are re-checked and newly mentioned
  people are notified.
- Delete: the author's own delete removes the comment from the thread, except a top comment that still has live
  replies, which stays as a tombstone (`deleted: true`, no body). A moderator's delete hides the comment **and its
  replies** for everyone without a tombstone; only the author still gets it, with `removed_by_admin: true` ("Removed
  by a family admin").
- `comment_count` counts live person comments and replies (not tombstones, not removed ones, frame entries never).
- **Frame entries** (`kind: frame`, read-only, not editable or deletable by anyone): when a frame reacts to a
  placement that belongs to a post (the frame post of its send on that frame, or a feed post delivered to that frame
  through `feed_deliveries`), the post's thread gets one entry per frame ("Oma's frame loved this"); a changed
  reaction updates it, a removed reaction deletes it. Every quick reply adds an entry ("Oma's frame: Call me!").
  In feed posts they are visible only to people who may see frames' reactions (`members_see_reactions`, the poster,
  owner/admins). They don't count as comments, don't make a thread unread and never push (the poster still gets
  `reaction`/`reply` as today, §5.5).
- **Read state**: `markThreadRead {up_to}` (never moves back). `unread_count` = person comments after `read_up_to`
  that are not the caller's own and not by people they blocked; a thread never read counts all of them.
  `unread_mentions` = those that mention the caller or reply to the caller's comments (as `parent_id` author or
  `quoted_user_id`). The caller's other connections get `thread.read`.
- **Mute**: `setThreadMute` per thread; `FeedMute` per family (`posts`, `chat`, `all`). A muted thread sends no
  `comment`, `comment_reply`, `mention` or `reaction_person` pushes; family mute `posts` keeps `mention`, `all` does
  not.

**Mentions** (comments and messages)
- `Mention {user_id, start, length}`: offsets in **UTF-16 code units** into the body, covering the "@Name" text the
  client inserted; spans inside the body, non-overlapping, at most `limits.max_mentions` (20). The server stores them
  and does not check the text under the span.
- Only people who may see the post (comments) or are members of the conversation (messages) can be mentioned
  (`422 validation_failed`, field `mentions`). Mentioning someone the author blocked or who blocked the author is
  allowed but never notifies.

**Reactions by people** (`setReaction`, `listReactions`)
- `PersonReaction`: `love` ❤️, `laugh` 😂, `wow` 😮, `sad` 😢 (the frame's four), `thumbs_up` 👍, `pray` 🙏. One per
  person and target; the latest wins; `null` removes it. Targets: `post`, `post_item` (`targetId` = the item id,
  with `?post_id=`), `comment`, `message`. Not on frame entries, system messages or tombstones (`422`).
- Same visibility as the target (the post, or conversation membership for messages). Frames' reactions stay on
  placements (§5.5) and join the row in `ReactionSummary.frames` (posts and post items only, when visible).
- Blocked people's reactions are counted but left out of `people` and `listReactions`.

**Circles** (tag `circles`)
- A circle is a named group of a feed's members (names 1–40 characters, unique per feed ignoring case; at most
  `limits.max_circles_per_feed`, 20). Created by owner/admins, or any member with `members_can_create_circles`; the
  creator is its admin. Circle admins and the feed's owner/admins manage it (rename, members, `frame_ids`, delete,
  roles); anyone leaves with `removeCircleMember` on themself. Leaving or being removed from the feed leaves its
  circles (database cascade). `listCircles` shows a member's own circles; the feed's owner/admins see all circles
  (names and members), but a circle's posts only if they are in it.
- **Audience**: `SendCreate.circle_ids` / `forwardSend` / `putMessageOnFrame`: at most one circle per feed, its feed
  must be in `feed_ids`, the caller must be in it (`422 validation_failed`, field `circle_ids`). That feed's post has
  `circle_id` and is visible only to the circle (and the poster).
- **Frames**: a circle post is placed only on the feed's `active` subscribed frames that, when posting, either have a
  manager (owner, admin or `change_settings`) in the circle or are in the circle's `frame_ids` (which must be frames
  with an active subscription to the feed; `422` otherwise). Later changes to the circle's members or `frame_ids`
  don't move existing placements; subscription backfills place circle posts only on frames they reach at that time.
- **Chat**: every circle has a `circle` conversation (`Circle.conversation_id`) whose members are always the circle's
  members, maintained by the database (§5.19). Deleting a circle removes its posts everywhere (like `removeFeedPost`),
  then marks it deleted; its chat is archived.
- Events: `circle.updated {feed_id, circle}` to the circle's members and the feed's owner/admins on create, rename,
  member and frame changes (also to people just removed); `circle.removed {feed_id, circle_id}` on delete. Clients
  refresh their chat list on `circle.updated`, `circle.removed` and `feed.membership_updated`.

**Storage and privacy**
- Comment and message bodies (and report excerpts) are stored sealed with AES-256-GCM (`secrets.Sealer`, HKDF info
  `kinframe message bodies v1`) when the server has `KINFRAME_SECRETS_KEY`; otherwise as plain text. There is no
  end-to-end encryption (decision B): the apps say plainly that the server's operator could read messages.
- A deleted account's comments and messages stay, shown as "Former member" (`PersonRef.former_member`); its photos
  follow the existing rules. The account export includes the user's own comments, messages and chat photos.

**Pushes** (§6.3 for payloads): one push per recipient and comment, the first that applies of `mention` (the
mentioned), `comment_reply` (the top comment's author and the quoted person), `comment` (the poster and everyone
who commented in the thread before). Never to the author, to people who blocked the author, or for muted threads.
- `comment` is bundled per recipient and post: the first comment in a quiet thread is pushed at once; further ones
  within 10 minutes are held and sent as one push at the end of the window ("Ben and 2 others commented: …"), with
  `tag` `comment:<post_id>` so it replaces the earlier one.
- `comment_reply`: `tag` `comment_reply:<parent_id>`, not bundled. `mention`: no tag, never bundled.
- `reaction_person` to the target's author (the poster for `post`, the item's sender for `post_item`, the comment's
  or message's author), never for own reactions: the first three people per target are pushed, each replacing the
  previous (`tag` `reaction:<target_kind>:<target_id>`, "Anna and 2 others reacted ❤️ to your photo"); later ones are
  counted and sent once a day at 19:00 local time (`User.timezone`) as `reaction_digest` ("12 more reactions on your
  photos today"), skipped when there are none. Changing an existing reaction never pushes.

### 5.19 Chats

Discovery says `chats: true` (and `voice_messages`, `link_previews`). Tags `chats`, `safety`.

**Who can talk to whom**
- **Contacts** (`listContacts`, `getPerson`): people who share at least one family (both in `feed_members` of the same
  feed that is not deleted, any role) or one frame (both in `memberships` of the same frame that is not deleted) with
  the caller. There is no search and no other way to reach anyone (decision C).
- `direct` chats and adding to groups need the other person to be a contact who has not blocked the caller:
  otherwise `403 cannot_message`, the same answer for "not a contact" and "blocked you" so nobody learns about
  blocks. A direct chat whose other side is gone or blocked the caller becomes `read_only`.

**Conversations**
- `direct`: exactly two people, once per pair (`createConversation` returns the existing one with `200`). Can't be
  left; archive it instead.
- `group`: 3–100 people (`limits.max_group_members`), a name (1–60) and an optional photo (`putConversationPhoto`).
  The creator is admin. Any member adds contacts (up to the limit); admins rename, change the photo, remove people and
  make others admin; anyone leaves. When the last admin leaves, the longest-standing member becomes admin. New
  members see messages from when they were added (`visible_from`).
- `family`: exactly one per feed, created with the feed by the database; existing feeds got one in the migration
  (eager backfill, archived for deleted feeds). Its members are always the feed's members, any role; leaving the
  family leaves the chat, and the chat can't be left on its own. Name and photo follow the feed (name, cover). Full
  history for everyone. Moderators: the feed's owner and admins.
- `circle`: exactly one per circle, the same way (members = the circle's members; moderators: circle admins and the
  feed's owner/admins).
- Family and circle membership is written **only** by the database triggers in migration 00023
  (`chat_feed_member_sync`, `chat_circle_member_sync`, `chat_feed_conversation`, `chat_circle_conversation`); code
  never inserts or deletes those members. A deleted feed or circle archives its chats (read-only, hidden from the list
  unless `archived=true`).
- `listConversations`: all of the caller's conversations, by `last_activity_at` (last message, else creation),
  newest first, not paged. Archiving is per person and undone by the next message.
- Mute (`updateConversationPreferences {mute}`): `1h`, `8h`, `1w` from now, `always`, or `off`. Family mute `chat`
  or `all` (`FeedMute`) also mutes the family's and its circles' chats.

**Messages**
- `sendMessage`: at least one of `body` (1–4000 characters, `limits.max_message_chars`), `items` (at most 30
  photos and videos, `limits.max_message_items`; the normal tus uploads and prepared uploads), `voice` and
  `shared_post_id`.
  Mentions as for comments. `reply_to_id` must be a message of the same conversation the caller can see.
- Order is the message id (UUIDv7, server time). `client_id` (and `Idempotency-Key`) make retries safe: the same
  author, conversation and `client_id` return the first message.
- Items are ordinary items (`items.conversation_id` set): the message is returned at once with items `processing`,
  and `message.updated` follows when they are ready or failed. Their media (`getItemMedia`) is served to the
  conversation's members (in groups: those who can see the message). Chat items are not in sent history or any
  library until they are put on a frame.
- **Voice messages**: `MessageCreate.voice {upload_id}` and no `items` (a body is allowed); returned as
  `Message.voice` (`VoiceClip`: `item_id`, `status`, `duration_ms`, `waveform`). Clients record AAC-LC in MP4
  (`.m4a`, `audio/mp4`: iOS, Android and Safari) or Opus in WebM or Ogg (`audio/webm`, `audio/ogg`: Chrome,
  Firefox); the server stores one master: **AAC-LC, mono, 48 kHz, 64 kbit/s in MP4** (`audio/mp4`), copying the
  stream when it already is AAC mono at most 96 kbit/s, else transcoding with ffmpeg; plus the waveform
  (64 values 0–100) and `duration_ms`. At most `limits.max_voice_seconds` (300 s; longer: `422 audio_too_long`), at
  least 1 s (`422 validation_failed`), uploads at most 20 MB. `getItemMedia` `master` with `VoiceClip.item_id`
  serves it (Range supported); there are no thumbnails. Internally it is an item whose media has kind `audio`
  (`messages.voice_item_id`); `MediaKind` in the API does not change, and such items are never placed on frames,
  posted to families, returned in `items` or shown in libraries.
- **Edit** (`updateMessage`): the author, within `limits.message_edit_hours` (24) of sending; text and mentions
  only. **Delete**: the author at any time, a tombstone remains (`deleted: true`; body, items, voice, link preview and
  reactions are cleared). Moderators (group admins; for family and circle chats the moderators above) remove any
  message: a tombstone with `removed_by_admin: true`. Photos already put on a frame stay there.
- **Shared posts** ("Share in chat"): `shared_post_id` must be a post the sender may see (`422 validation_failed`,
  field `shared_post_id`). Members who may not see it get `PostCard.available: false`.
- **Put on a frame** (`putMessageOnFrame`): a normal send of the message's photos and videos, without uploading
  again (the `forwardSend` path), to frames the caller may send to and families they may post to. Own items are the
  caller's send; items of other people's messages are placed like `sharePlacements` (credit "Ben · shared by Anna",
  `shared_by_user_id`; reactions go to the items' sender as for shares) and can't go to families (`403 forbidden`).
  The conversation gets a `put_on_frame` system message ("Ben put 12 photos on Oma's frame and in Peeters"), and the
  frames' reactions and quick replies on those placements are listed in the original message's `frame_activity`
  (`message.updated` when they change). The send counts for freshness and answers photo requests (§5.20).
- **System messages** (`kind: system`): `put_on_frame`; `streak_milestone`, `monthly_recap`, `prompt` (posted by the
  engagement scheduler in family chats); `member_joined`, `member_left` (groups only: added people and leavers; never
  in family or circle chats). They can be reacted to, never edited, and never push.
- `listMessages`: pages are always oldest first; no cursor = the newest `limit` (50, max 200), `before=<id>` older,
  `after=<id>` newer (both: `422`); `has_more_before`/`has_more_after`. Clients catch up after a reconnect with
  `after` = the last message they have.

**Read state, receipts, typing**
- `markConversationRead {message_id}` never moves back. `unread_count` counts messages after the caller's position,
  not their own, not system messages, not from people they blocked; `unread_mentions` those mentioning them.
- **Read receipts** ("Seen") are shown in `direct` chats and in `group`s of at most 10 people, never in family or
  circle chats (`Conversation.read_receipts`). A member's `last_read_message_id` is returned only to members who have
  `show_read_receipts` on, and only for members who have it on too (`User.show_read_receipts`, default on; turning it
  off hides your own and others'). `read.updated {conversation_id, user_id, last_read_message_id}` goes to the
  other members under the same rule, and always to the reader's own other connections.
- **Typing** (`sendTyping`): relayed as `typing {conversation_id, user_id, display_name, activity, expires_at}` to
  the other connected members (not to people who blocked the typer); `expires_at` = now + 6 s; never stored or
  pushed; at most one every 3 s per person and conversation.

**Link previews** (operator opt-in)
- Off unless the operator sets `KINFRAME_LINK_PREVIEWS=1` (discovery `link_previews`). Then `sendMessage` attaches a
  preview of the body's first `http(s)` link unless `link_preview: false`: the message is returned with `status:
  pending` and `message.updated` follows. `createLinkPreview` gives the composer the same preview first.
- The server fetches with `internal/netguard` (public addresses only, re-checked on every redirect, at most 5
  redirects), 5 s timeout, at most 1 MiB of HTML read, `User-Agent: kinframe-linkpreview/1`; it reads Open Graph,
  Twitter card, `<title>` and description. The image (at most 5 MiB) is re-encoded to WebP of at most 512 px and
  served by the server, so clients never load remote content. Cached per normalized URL (`link_previews`): 7 days, a
  failure 1 day. Failures are `status: failed` (the message shows the plain link).

**Blocks and reports**
- `blockUser`: the blocked person's comments, messages and reactions are hidden from the blocker (lists, latest
  comments, previews; counts still include them), their direct messages are refused (`403 cannot_message`), they
  can't add the blocker to groups, and they get no typing or read events from the blocker. Shared families, circles
  and groups stay. Nobody is told.
- `createReport`: comments, messages and posts the reporter can see. Family context (a feed post or its comments, a
  family or circle chat) goes to the feed's owner and admins (`listFeedReports`, push `report`); everything else
  (frame posts, direct and group chats) to the operator (`listOperatorReports`). `excerpt` keeps the text as it was
  when reported. `resolveReport {remove|dismiss}`: `remove` acts as a moderator delete; `409 already_decided` if
  someone else was first. The reported person never learns who reported.

**Badge** (`getBadge`, `badge.updated {badge}`): conversations with unread messages that are not muted, plus threads
with `unread_mentions` > 0 that are not muted. Not every comment. Sent whenever it changes for the user.

**Pushes**: `message` to members with no open websocket (as for every push, §6.3), unless the conversation or the
family's chat is muted; `tag` `conversation:<conversation_id>` so the newest replaces older ones; title the
conversation's name, body "Anna: Look at this" / "Anna sent 3 photos" / "Anna sent a voice message" / "Anna shared a
post". Messages that mention the recipient use `mention` instead (also suppressed by mute). `conversation_added`
when someone adds you to a group. `report` to the handlers of a new report. System messages never push.

### 5.20 Encouragement to share

Product spec: `docs/spec/family-social.md` §9. Discovery says `engagement: true`. Nothing here ranks people.

**Freshness** (`FrameSummary.freshness`, `FeedSummary.freshness`)
- A frame's **personal** photos are non-backfill placements of sends by people (`sends.via` null, `email` or
  `telegram`: direct sends, feed posts, chats put on a frame); **source** photos are those of photo sources and Google
  imports. Frame imports, shares, birthdays and calendars never count. For a family: its posts, the same way.
  Kept in `frame_freshness` / `feed_freshness` when placements and posts are made.
- With `d(t)` = days (fractional) since `t` and `L(t) = clamp(1 − d(t)/14, 0, 1)`: `level = max(L(last personal),
  0.5 × L(last source))`. `days_quiet` = whole days since the last personal photo (null if never). `state`:
  `never` (no personal or source photo ever), `fresh` (`days_quiet` 0), `recent` (below the quiet threshold),
  `quiet` (at or above it). The threshold is `quiet_after_days` of the family (`FeedEngagement`, default 7); for a
  frame, the smallest of its families with an active subscription, else 7.
- Tapping a pale ring opens "Send something to Oma's frame" with suggestions computed **on the device** (photos taken
  since the last share, never uploaded until picked), `getOnThisDay` and the current prompt.

**Ask for new photos** (frame requests)
- Frame: `deviceRequestPhotos` from the quick menu (shown while `settings.social.ask_for_photos`, default on; `403`
  when off). One per frame per 24 hours: within 24 h of the last one it returns that one with `200` and notifies
  nobody. The `photo_request` change carries `FramePhotoRequest` (`next_allowed_at`: show "Asked today 💛").
- Recipients of push `frame_request` ("Oma would like new photos 💛"): the frame's members with `send` and the members
  (poster or above) of families with an active subscription to it, who sent anything that reached this frame in the
  last 6 months; if nobody did, all of them. Not to people with `frame_requests: false`. Not counted in the reminder
  budget and not affected by snooze or `level`; quiet hours do apply (held until they end).
- **Answered** by the first send by a person that creates a non-backfill placement on that frame after the request
  (`answered_by`, `answered_at`; `photo_request.updated` to the same people, a new `photo_request` change; the frame
  shows "New photos from Ben" on its next wake). Open requests expire after 7 days. `FrameSummary.photo_request` is
  the open one or one answered in the last 24 hours; `getPhotoRequest`, `listFramePhotoRequests` (30 days).

**Reminders and their budget**

| Kind (`nudges.kind`, push key) | To | When | Limit |
|---|---|---|---|
| `reminder_quiet` | 1–2 people who sent to that frame in the last 6 months (frame members with `send`, family members whose posts reach it), longest since their last share first, rotating | a frame's freshness reaches `quiet` | per frame once per 7 days; per person once per 7 days |
| `reminder_streak` | 1 family member who helped most often in the last 8 weeks, not the one reminded last time | Sunday 17:00 (family time zone) while the week's goal isn't met | once per week per family |
| `reminder_birthday` | up to 3 members who posted to that family in the last 12 months (not the birthday person) | 3 days before a birthday in the family, 10:00 local | once per birthday and person |
| `weekly_summary` | everyone whose sends were seen that week | Sunday 19:00 local | weekly, skipped when empty |
| `frame_request` | above | at once | per frame once per 24 h |

- **Budget**: at most 2 budgeted pushes (every kind above except `frame_request`) per person in any 7 days; 1 with
  `level: fewer` (and no `reminder_streak` at all); none with `off` or while `snoozed_until` is in the future. When
  over budget the order is birthday, streak, quiet, summary. "Local" is `User.timezone` (Europe/Brussels without one);
  nothing is sent during `quiet_hours` (default 21:00–09:00), it waits until they end and is dropped if it is no longer
  true by then.
- Reminder pushes carry `actions: ["snooze_month", "fewer_reminders"]`: the apps call `updateReminderPreferences`
  with `{snooze_days: 30}` or `{level: fewer}` (README §6.3).
- Texts are specific ("Opa's frame has had no new photos for 9 days. You last shared 3 weeks ago."), never generic.
- **Outcome**: a reminder is `shared` when its recipient makes a personal send within 24 h, else `ignored`. After 4
  ignored reminders in a row the person drops to `fewer` (`auto_reduced: true`, shown once by the apps). This is the
  only use of the log; nothing leaves the server.
- The scheduler runs every 15 minutes (a worker job), idempotent through `nudges.dedupe_key`.

**Family streak** (`getFeedStreak`, `FeedSummary.streak`, settings in `getFeedEngagement`/`updateFeedEngagement`)
- Weeks run Monday 00:00 to Sunday 24:00 in the family's time zone (`FeedEngagement.timezone`: set by an admin, else
  the most common `locale.timezone` of its subscribed frames, else the owner's `User.timezone`, else
  Europe/Brussels). A week counts the live items of personal posts made in it (removed posts don't count when the
  week closes); the goal is `streak_goal` (default 3, 1–20; decision E).
- When a week closes: goal met → `current_weeks` + 1 (and `best_weeks`); missed with a freeze left → the freeze is
  used, the streak stays (`frozen`); missed without → `current_weeks` = 0, quietly ("New streak: 1 week" when the next
  week is met). One freeze is granted on the 1st of each month, at most 2 held.
- `helpers`: who posted this week, no counts. Milestones at 4, 12, 26 and 52 weeks, then every 52: a
  `streak_milestone` system message in the family chat. `streak_enabled: false` hides everything (and stops the
  streak reminder) but keeps counting so turning it on again shows the truth.

**Weekly prompts** (`listFeedPrompts`, `setFeedPrompt`, `resetFeedPrompt`, `listPromptLibrary`, `tagPostPrompt`)
- Off by default (`prompts_enabled`). The server's list is localized like everything else (i18n keys
  `prompt.<key>` in `locales/engagement`, all 37 languages); each week without an admin's choice gets the next key in a
  fixed rotation the family hasn't had in the last 52 weeks. The week's `feed_prompts` row is made when the week
  starts (Monday 00:00 family time), so its `id` is stable; a `prompt` system message announces it in the family chat
  at 09:00.
- Admins may set the current or a future week (a library key, an own title of 1–80 characters, or skip) and reset it.
- Posts are tagged by `SendCreate.prompt_id` (the prompt's feed must be in `feed_ids`) or `tagPostPrompt` (the
  poster), with the current or previous week's prompt (`422` otherwise). `FeedPost.prompt_id`, `TimelinePost.prompt`,
  `getTimeline?prompt_id=` lists the answers; `FeedPrompt.answer_count`.
- Frames: `Placement.prompt_id` on the answers' feed placements and the `prompts` change (`FramePrompts`, entity = the
  frame) with the current prompts of its families in the frame's `locale.language`. With `settings.social.prompt_cards`
  (default on) the frame shows the title card ("This week: *Your view right now*") before the first answer each time
  it comes up.

**On this day** (`getOnThisDay`): the caller's visible feed posts from today's month and day in earlier years (in
`User.timezone`; 29 February shows on 28 February in other years), at most 3 groups (newest year first, then by
family), 12 items each. One tap re-shares through `createSend` with the items (`upload_id` = the existing item id of
the caller's own items) or `forwardSend`, or into a chat with `shared_post_id`.

**Weekly summary** (`getWeeklySummary`, push `weekly_summary` with `ids.week_of`): for the week just ended in the
caller's time zone: placements of their items first seen that week (`seen_count`) and on how many frames, frame ❤ on
their items, people's reactions and others' comments on their posts, and the item with most reactions. Only made and
pushed when `seen_count` > 0 and `weekly_summary` is on; kept 12 weeks.

**Monthly recap** (`getFeedRecap`): on the 1st at 10:00 (family time) for the month before, when `monthly_recap` is on
and the family posted that month: photos and videos shared, how many people shared, the post with most reactions
(people and frames), the three frames with most days with new photos, new members, the streak. Posted as a
`monthly_recap` system message in the family chat. About the family, never ranking people.

**Personal milestones** (`listMyMilestones`, `updateMyMilestone`; private unless `shown`, then in `Person.milestones`)
- `first_photo`, `photos_10`, `photos_100`, `photos_1000` (photos and videos in the user's own sends, not via photo
  sources or Google imports; chat items count once put on a frame), `first_video`, `year_of_sharing` (365 days after
  the first send, with a send in the last 30 days), `loves_100` (100 frame `love` reactions on their placements).
- Checked after sends and reactions; a new one sends `milestone.achieved {milestone}` on the websocket only (no push).
  Apps show a small card until `seen`.

**Birthday nudges**: `reminder_birthday` (table above), three days before an approved birthday of a family, once per
birthday and person (`dedupe_key` `reminder_birthday:<birthday_id>:<date>`), only with `birthday_reminders` on and the
feed's `birthday_notifications` on for that member. The server has no face recognition: "people with photos of her"
is approximated by members who posted to that family in the last 12 months.

**Email digest** (`ReminderPreferences.email_digest`: `off` default, `daily` 08:00 local, `weekly` Monday 08:00): only
when discovery has `email_digest` (outgoing mail configured). Unread comments in threads the person follows (their
posts, threads they commented in, mentions) and unread messages in conversations that are not muted, since the last
digest; skipped when empty. Each mail links to the app and the web and has a one-click "stop these emails" link.

**The frame**
- `settings.social.show_comments` (default off; owner, admins, `change_settings`): the server sends a `comments`
  change (entity = the placement, `PlacementComments`) for each live placement of a feed post that has person comments
  visible to the whole post audience: the three newest (not tombstones, not removed), first names only, at most 280
  characters, never chat messages, never comments from families the frame doesn't subscribe to. It is re-emitted when
  they change (digest in `frame_comment_cards`) and `comments: null` drops them (the setting turned off, the comments
  removed). The frame shows the newest as a card for 8 s when the photo appears, at most once per photo per day.
- `settings.social.ask_for_photos` (default on): the quick-menu button and `deviceRequestPhotos`.
- `settings.social.new_photos_from` (default on): after new (non-backfill) placements arrived, the frame shows "New
  photos from Ben" (from the placements' senders) on its next wake.
- `prompts` change and `Placement.prompt_id` as above. Heartbeat `features` may list `comments`, `photo_requests`,
  `prompts` (informational; nothing is withheld from older apps, which ignore the new change types).

## 7. Versioning

- Breaking changes need `/v2`. Additive changes (new fields, new event types, new change types) are not breaking.
- Clients must ignore unknown fields and event types.
- The frame sends `X-Kinframe-App: kinframe-frame/<version name> (<version code>)`. The server can answer
  `426 app_update_required` on any device call except heartbeat, state and the update endpoints. The frame then
  self-updates (§5.7, product spec 4.10).
