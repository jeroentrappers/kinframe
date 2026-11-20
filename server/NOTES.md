# Server implementation notes

Decisions taken where `docs/api/` was silent or ambiguous, and known gaps. Contract changes are listed separately.

## Contract changes

- Frame self-update (README §5.7): `GET /v1/device/update` (`FrameUpdate` or 204, `channel` and `skip` query
  parameters), `GET /v1/device/update/apk/{versionCode}`, Heartbeat `update_state` and `last_update_error`,
  FrameSettings `updates.channel`. Both update endpoints are exempt from `426 app_update_required`.
- Weather: `GET /v1/geocode` (`Place[]`), the `Weather` schema for `Change.weather` (nullable; extends the earlier
  `fetched_at`/`current.temp_c`/`daily[].min_c`… fields without renaming them), Discovery `weather`, problem codes
  `upstream_unavailable` (502) and `feature_disabled` (503), and `required`/range limits on `settings.location`.
- Web parity: Discovery `webpush_public_key` (VAPID), `listPlacements?on_this_day=MM-DD` ("On this day": captured on
  that month-day in an earlier year, UTC capture dates), and the documented 3 s minimum for `trim`
  (`422 validation_failed` on `items.N.trim`). Deleting the album a frame plays resets `slideshow.album_id` to null.
- Feeds (docs/spec/feeds.md, README §5.12): `posters_can_invite` on Feed, FeedCreate and FeedUpdate (posters may
  create `join` invites and list/revoke their own; `subscribe` invites stay owner/admin); the feed invite code
  pattern accepts lower case (the description said case-insensitive, the pattern did not); Heartbeat `features`
  (`feed_backfill`, see "Feeds" below).
- Birthdays (docs/spec/birthdays.md, README §5.13): `BirthdayPerson.created_by_id` (account id of whoever added or
  suggested the entry, null when that account is gone), so clients can tell "added by me" (poster year visibility,
  edit and withdraw). Birthday pushes also carry `date` (the occurrence date) and, for greetings, `frame_id` in `ids`:
  clients need the date to build the `birthday:<birthday_id>:<date>` tag, and the frame's local date can differ from
  the phone's. Greeting pushes also carry that tag as a top-level `tag` in the payload (`{type, key, title, body,
  ids, tag}`), so a newer greeting for the same person and day replaces the older one.
- Photo sources (docs/spec/photo-sources.md, README §5.14): no changes to the contract were needed. The coordinator
  added `Album.only_in_album_mode`; the server implements it. `ImmichAlbum.thumbnail_url` is always null (no proxy
  endpoint exists in the contract for it).
- Telegram (docs/spec/telegram.md, README §5.17): no changes to the contract were needed.
- Display rendition (README §5.3): `Item.display_url` (optional, absent until made) and the `display` variant of
  `GET /v1/items/{id}/media/{variant}`.
- Otherwise none made by the server work. The coordinator added `dev_auth` to Discovery, `POST /dev/token` (tag `dev`) and the
  signed media URL description; the server implements them. `/dev/token` additionally accepts `"operator": true` to
  get the `kinframe:operate` scope (dev only, not in the contract).

## Code generation

- `docs/api/openapi.yaml` is OpenAPI 3.1. oapi-codegen cannot resolve references into schema properties
  (`#/components/schemas/Album/properties/schedule`) and handles 3.1 type arrays poorly, so `tools/specprep` rewrites
  a copy (inline deep refs, `type: [x, 'null']` → `nullable`, `oneOf [ref, null]` → nullable, labelled 3.0.3). The same
  prepared copy is embedded for request validation (kin-openapi). The contract file itself is untouched.
- `tusCreateUpload`, `putAvatar`, `putFeedBirthdayPhoto`, `getItemMedia`, `deviceGetMedia` and `devToken` are hand-written (tus, binary or
  multi-content-type bodies, redirects, dev-only).

## API behaviour

- Validation: malformed requests (bad params, missing headers, unparsable JSON) → `400 validation_failed`; well-formed
  bodies with invalid fields → `422 validation_failed` with `errors[]`.
- Merge-patch endpoints also accept `Content-Type: application/json`.
- Timestamps are UTC; they may carry fractional seconds.
- Nullable fields that are null are omitted from responses (generated structs use `omitempty`); clients treat missing
  as null.
- `PATCH` endpoints distinguish `null` from absent where it matters (`caption: null` clears, `location: null`,
  `schedule: null`) by looking at the raw body.

## Auth

- Logto access tokens normally carry no name/email. `bootstrap-logto` makes Logto ask for a name at sign-up (built-in
  `name` profile field) and installs a custom access-token script (Logto OSS runs it locally) that adds `name` and
  `email` for the kinframe API resource only, so the user row is created with the real name. Without those claims
  (other Logto setups) the display name falls back to the email local part or "New member", and clients should
  `PATCH /v1/me` with the ID-token name after sign-in (the web app does).
- Client-credentials (M2M) tokens for the API resource are accepted like user tokens (`sub` = app ID). Only apps
  created in this Logto by its admin can get them.
- Device tokens: HS256 with a key derived from `KINFRAME_SIGNING_KEY`, `iss` = public URL, `aud` = `device`, 1 h.
  Revocation is checked on every request (device row), so a revoked device fails immediately.
- `426 app_update_required` compares the device's `app_version_code` (from enrollment, updated by heartbeats) with
  `KINFRAME_MIN_FRAME_APP_VERSION_CODE`; heartbeat, state and the update endpoints stay allowed. Only
  `GET /v1/device/update` parses `X-Kinframe-App` (the version code in parentheses), falling back to the stored code.
- Family app version policy (README §5.15): the gate sits in front of user authentication (`userAuth` and the user
  websocket), so only user endpoints are gated and an outdated app gets 426 even with an expired token. Discovery,
  `/healthz`, `/.well-known/*`, the Google OAuth callback and every public unauthenticated endpoint (tv, shop,
  stations, signed media, avatars, the SPA) never see it. Only `kinframe-family-{android,ios,macos}/<name> (<build>)`
  counts; a missing, unparsable or frame header is never blocked. Refusals are logged at debug level (platform, build,
  minimum, running count; no user data). The 426 reuses the problem title "The frame app must be updated"; apps act on
  `code`, not the title.

## Frame updates

- `GET /v1/device/update` and the APK download are hand-written (excluded from codegen): 200-or-204 and binary
  `Range` responses. The channel defaults to the frame's `updates.channel`, then the enrollment channel. `min_sdk` is
  checked against `hardware.android_sdk` from enrollment (or the last heartbeat); unknown means no check.
- APK metadata comes from the APK itself (binary manifest parser), not from a metadata asset: what the server
  advertises is exactly what the frame installs, and drop-in APKs need no naming convention.
- User websocket: no `hello` message (none is specified); closes with 4401 at token expiry.

## Codes, joining, members

- Frame codes: the same code is returned until it is within 10 minutes of expiry, then a new one is issued (the frame
  re-requests 5 minutes before expiry, which would otherwise always get the dying code). Expired codes answer
  `410 code_expired` for one day after expiry, then `404 code_invalid` like any unknown code.
- Codes are invalidated by a claim (and by replace-device), not by joins.
- A claimed frame is named "<owner name>'s frame"; settings defaults take language/timezone from enrollment.
- Admins cannot change or remove the owner or other admins (applied to removal as well as role changes).
- `DELETE /v1/me` returns `409 owner_cannot_leave` while the user owns frames; users are soft-deleted (identity
  detached) so items they sent keep their sender.
- Frame removal deletes memberships, removes all placements and revokes the device; items stay in senders' history.
- `remove_items` / `remove_my_items` remove placements (not items).

## Sends and media

- All-or-nothing: uploads are sniffed by magic bytes and videos are probed with ffprobe during `POST /v1/sends`, so
  `unsupported_media`, `corrupt_media` (unreadable video) and `video_too_long` are reported before anything is created.
  A corrupt photo is only detected by the pipeline and then marks the item `failed`.
- Duplicates: `Kinframe-Existing-Item` is matched on the client-supplied sha256 against the caller's own items
  (untrimmed, with audio). Sending that item id creates a new item (own caption/focal point) that shares the stored
  media; such items cannot be trimmed (`422`). The server records the real sha256 of every processed source.
- Storage model: `media` holds the master and per-device renditions; `items` hold per-send metadata, thumbnails
  (they depend on the item's focal point and are regenerated when it changes) and the display rendition
  (`items.display_key`, `items/<id>/display-<suffix>.webp`, remade with the thumbnails under the same suffix and deleted
  with them). `backfill-display` (server/README.md) fills it in for older items via the `display_backfill` job.
- Rendition variants: photos `frame_<long>x<short>` (screen), videos `frame_<long>x<short>_v<decode long>x<decode short>`
  because the decode box also matters. Devices that report no `max_video_decode` get 1920×1080.
- A photo master that already fits the frame is reused as the frame rendition (no second lossy encode).
- Video renditions are always H.264 High@4.1 (no Main fallback yet), fps capped at 30, bitrate w·h·4 ≤ 8 Mbps.
  Masters cap fps at 60. HDR (HLG/PQ) sources are not tone-mapped yet.
- EXIF capture times without an offset are stored as UTC wall-clock time.
- `Item.status` is the media status; a placement becomes `ready` once its frame's rendition exists. In `Send.summary`,
  placements whose media is still processing count as `processing`, the rest as `pending` until delivered.
- Signed media URLs: `/v1/media/signed/{key}?exp=…&sig=…`, HMAC-SHA256, valid 1–2 h (expiry rounded up so URLs are
  cacheable). `Item.thumb_url` uses them; `GET /v1/items/{id}/media/{variant}` redirects to one (5 min).
- Avatars are public at unguessable URLs (`/avatars/{user}/{random}.webp`) so frames and `<img>` can cache them.
- Blob storage (`fs` or `s3`): only processed files are stored, never originals. The fs store keeps the old layout
  and ETags, so switching nothing changes nothing. The S3 store records `Content-Type` and the sha256 (user metadata)
  on upload. Its `Open` stats once and then issues one ranged GET per contiguous read with `If-Match` on the stat's
  ETag, so `http.ServeContent` handles Range/multi-range/304 exactly as for files and never mixes two versions.
- Scratch moved from `<data dir>/media/tmp` to `<data dir>/tmp` (same volume, so the fs store still renames into
  place); the fs store's `List` skips the old `tmp/` directory and `*.tmp` leftovers.
- Presigned redirects apply to signed media URLs and avatars only. Frame downloads always stream: frames revalidate
  with the rendition's sha256 ETag and send their device token, neither of which a bucket understands.
- The frame release mirror (`<data dir>/frame-releases`) stays on local disk: it is a cache refilled from GitHub or
  the drop-in directory, not user data. Weather is cached in PostgreSQL.
- Frame imports (`/v1/device/imports`) create media without an owner; sender is the frame (`sender_id` = frame id).

## Change feed

- Stored compacted: one row per (frame, entity) holding the latest seq. Seqs are allocated under the frame row lock,
  so readers never see gaps. `since=0` is therefore a full snapshot (removals are omitted from snapshots).
- Changes are rendered from current state at read time. Placements pending moderation, failed or removed render as
  `placement.remove`. Placements enter the feed when their rendition is ready (or when changed).
- `reset` (with `next_seq: 0`) when `since` is ahead of the frame's head or below the floor raised by pruning removal
  entries older than `KINFRAME_FEED_RETENTION_DAYS`.
- Unclaimed devices get an empty feed. Acks don't produce feed entries (the frame already knows).

## Feeds

- Migration `00017_feeds.sql`: the tables of docs/spec/feeds-data-model.md, plus `feeds.posters_can_invite`,
  `subscriptions.via_invite` (survives the invite row), `subscriptions.backfill_placed/backfill_total` (progress),
  `placements.origin/origin_feed_id/held_directly/feed_suspended/backfill`, and `devices.features` (the latest
  heartbeat's `features`). Existing placements get `origin` from their data (share, import, else send).
- Materialization is in `store/feedsync.go` and follows the data model: `place`/`unplace` under the frame lock,
  then the subscription row. Jobs (run by the worker through `api.Server.RunJob`): `feed_fanout` (a new post on
  every active subscription), `feed_reconcile` (history backfill/trim, batches of 200, re-enqueued until done,
  abandoned when `reconciled_version` moved), `feed_sweep` (unplaces what a removed post or ended subscription
  held, deletes its delivery rows; when a deleted feed has none left its row is deleted).
- A fanout only places posts made at or after the subscription's first activation; older posts are history and
  only the reconcile places them (quietly, per the history option), whatever order the jobs run in.
- Tombstones: every placement removal outside `unplace` (purge, trash expiry, member/account removal with items,
  unsend of the send) turns its `placed` delivery rows into `declined`. A declined row for an item blocks feed
  placements of that item on that frame from any post or feed until the subscription ends.
- Pause recomputes `feed_suspended` (not held directly and no active subscription holds it) and re-emits the
  subscription's placements; suspended placements render as `placement.remove` and are left out of the library
  (`listPlacements`, counts). Ending a subscription does the same at once, then the sweep removes them.
- Old frame apps: backfilled placements render as `placement.remove` (and are left out of snapshots) for frames
  whose latest heartbeat does not list `feed_backfill`; the heartbeat that first lists it re-emits all live
  backfilled placements of the frame. Browser frames are never withheld. Withheld placements show as
  `pending`/`ready` in receipts.
- Moderation: a feed placement starts `pending_moderation` when the frame moderates and the poster is not its
  owner/admin. Rejecting is a purge (tombstone).
- Subscriptions: approve/reject are feed owner/admin, pause/resume/history the frame side (owner, admin,
  `change_settings`). Repeating the current transition returns 200 unchanged; approve or reject of a decided one
  `409 already_decided`; other impossible ones `409 invalid_transition`. `DELETE` of an ended one is `204`.
  `removed_reason`: `withdrawn` (pending, frame side or requester), `unsubscribed` (frame side), `removed_by_feed`.
  `409 already_subscribed` carries the existing subscription id as `detail`.
- Visibility: frame managers and feed owner/admins see every status (and `requested_by_name`); other members of
  either side and posters only `active`/`paused`; the requester always their own. `estimate` counts the wanted
  items and their master bytes (renditions are smaller). Removed-frame subscriptions stay listed for the feed.
- Receipts: per frame (active and paused subscriptions with delivery rows): all items declined → `removed`; paused
  → `paused` (not in `frames`); otherwise counted in `frames`, `awaiting_approval` when an item awaits moderation,
  delivered/seen when every non-failed item is. Hidden/trashed are not revealed. Placements of a feed are not in
  `Send.frames[]`, the send's posts are in `Send.feeds[]` (removed ones with `removed: true`, no summary).
- Feed placements get no per-send `delivered` push; `feed_post.updated` goes to the poster and feed owner/admins
  after fanout, acks, reactions and replies. Reaction/reply events and pushes carry `feed_id`/`post_id` (the
  origin feed's post when several hold it).
- Invites: code = 8 Crockford base32 characters (no I, L, O, U), never all digits, stored upper case; lookup
  accepts lower case, a hyphen and spaces and reads I/L as 1 and O as 0. Lookup is rate-limited like code
  redemption (10/h per user, 30/h per IP), invite creation like frame invites (60/h per user). Link:
  `<public URL>/fi/<token>` (also in the Apple app site association).
- Feed deletion: owner only; posts and subscriptions end in the request (frame owners/admins get
  `subscription_removed`), sweeps clear the frames, then the feed row goes (cascading its subscriptions).
- Account deletion answers `409 owner_cannot_leave` while the user owns feeds; feed memberships are deleted, and
  with `remove_items` their posts are unsent too.
- Limits: `max_feeds_per_user` (20), `max_subscriptions_per_feed` (100, counts pending/active/paused),
  `max_feed_backfill_items` (5000) via `KINFRAME_MAX_FEEDS_PER_USER`, `KINFRAME_MAX_SUBSCRIPTIONS_PER_FEED`,
  `KINFRAME_MAX_FEED_BACKFILL_ITEMS` and `PUT /v1/operator/limits`; unset or zero means the default.
- Settings: setting `slideshow.album_id` clears `slideshow.feed_id` and vice versa; ending a subscription removes
  `slideshow.feed_id` when it pointed at that feed (a settings change on the frame).
- Pushes: keys `subscription_request`, `subscription_decided` (texts `subscription_approved`/`_rejected`),
  `subscription_removed`, `feed_join_request`, `feed_membership`, localized like the others.
- Rejected and removed subscriptions are deleted by the maintenance loop 30 days after they ended.

## Birthdays

- Migration `00018_birthdays.sql`: the tables of birthdays.md §12, plus `frame_birthdays (frame_id, digest,
  emitted_at)` (what each frame was last sent) and indexes on greetings per frame and entries per linked user. The
  date CHECK is an inline `CASE` on the month (no `make_date`), with the leap-year CHECK as specified.
- The `birthdays` change (kind `birthdays`, entity = frame id) is rendered from current state at read time;
  `generated_at` is `frame_birthdays.emitted_at`. `store.SyncFrameBirthdays` recomputes the merged list under the
  frame lock and bumps the entry only when its digest (people, ids, photo keys, the shown year) changed, so edits the
  frame cannot see (a hidden year, a pending suggestion) emit nothing. An empty list renders as `birthdays: null`
  and is left out of snapshots. Sync points: create/edit/remove/approve of approved entries and photo changes (every
  frame with an active `birthdays` subscription to the feed, frames locked in id order after the feed lock),
  activate/pause/resume/end of a subscription and its `birthdays` flag (that frame), feed deletion (the entries are
  deleted in the request, then each subscription ends), a linked member leaving, being removed or deleting the
  account (link cleared), and a linked member's avatar change while it serves as the photo.
- Merge (§6.5) in `store.MergeBirthdays`: key = name key + month + day; entries ordered by their subscription's
  `activated_at`, then entry age; the primary is the first with a photo (own photo or the linked member's avatar),
  else the first. The year is sent only if every entry with a year has `show_age` and all years agree. Sorted by
  month, day, name.
- Name key: NFKC, Unicode case folding, NFD with combining marks removed, NFC, whitespace collapsed.
- Permissions follow §4.1. PATCH compares with the stored values, so fields sent unchanged are not edits (clients may
  send whole objects); the linked member may only change `birth_year`, `show_age` and unlink, other changed fields
  are `403 forbidden`. Suggesters may delete (withdraw) their own pending or rejected entries. Entries the caller may
  not see are `404`, like unknown ones; visible but not editable ones `403`.
- `decided_by_name`/`decided_at` are shown for suggestions only, to owner/admins and the suggester. `next_birthday`,
  `turns` and `greetings_today` use the caller's `User.timezone`, else Europe/Brussels; with a visible year,
  occurrences up to the year of birth are skipped.
- Photos: hand-written `PUT …/photo` (excluded from codegen like `putAvatar`), the avatar pipeline, keys
  `avatars/b/<random>.webp` served by the public avatar route. Replacing, removing the photo, the entry, a rejected
  entry's expiry or the feed deletes the file.
- Limits: `max_birthdays_per_feed` (200, `KINFRAME_MAX_BIRTHDAYS_PER_FEED`, operator limits); create and suggest are
  serialized per feed by the feed row lock and rate-limited at 60/h per user (in memory).
- Greetings: the person must be in the frame's current merged list (any of its `birthday_ids`), the date an
  occurrence (29 February on 28 February in non-leap years) after the year of birth, within the server's UTC date
  −2 … +1. One row per merged entry, `ON CONFLICT DO NOTHING`; `204` when all existed. The 50-a-day cap counts
  greetings (taps) the frame recorded since the UTC day started; the rows of one tap share their `created_at`.
  Pushes: one per member (with `birthday_notifications` on in one of the greeted feeds); `birthday_id` is the member's
  oldest entry of that person in any of their feeds, so greetings from different frames get the same tag; the body
  counts the frames that greeted any of those entries that day (texts `birthday_greeting`, `_two`, `_many`).
- Morning notices: `Server.BirthdayMornings(now)` runs every 5 minutes (and at start). Candidates are approved entries
  on the UTC date ±1 in feeds where the member has notifications on; per member the local time must be 08:00–20:00,
  the person's day today (leap rule, not the year of birth), groups containing the member's own entry are left out,
  and `birthday_notices` (insert-or-skip) makes it once per local date. Zone: `User.timezone`, else the most common
  `locale.timezone` of the active subscribed frames of the feeds those entries are in (ties alphabetical), else
  Europe/Brussels. Texts: `birthday_today`, `birthday_today_age` ("Oma turns 80 today 🎈", no ordinals across 36
  languages) and `birthday_today_many` ("3 birthdays in the family today: Oma, Lotte, …").
- `birthday.updated` is rendered per recipient (year visibility) and not sent to whoever got `birthday.suggested` or
  `birthday.decided` for the same action. Members who leave lose their link (and the event re-renders the entry).
- The server clock for birthdays (`Server.SetClock`) is injectable for tests; the morning job takes `now`.
- Maintenance: rejected entries 30 days after the decision (photos too), greetings after 13 months, notices after 7
  days.

## Photo sources and Google Photos import

- Migration `00019_photo_sources.sql`: the tables of photo-sources.md §15, plus `photo_sources.server_url`
  (`immich_api` display URL), `upstream_count`, `hold_since`/`last_new_at` (feed debounce),
  `photo_source_items.replacement_media_id` (a changed upstream file, swapped in once processed), `upstream_added_at`
  (iCloud `batchDateCreated`), `retry_at` (failed rows), `google_imports.target_kind`, `auth_url_enc`, `done_ids`
  (resume), `feed_posts.quiet` (a source's first-run posts are placed as history), `albums.only_in_album_mode`, and
  indexes on `media.source_sha256` and `photo_source_items.upstream_checksum` for the duplicate checks.
- Code: `internal/netguard` (SSRF guard), `internal/photosource` (Immich, iCloud, Google Picker clients, the
  scheduler; `photosourcetest` has httptest fakes), `api/photosources.go` (endpoints), `api/photosync.go` (one run),
  `api/googleimport.go` (consent, callback, polling, copying). The scheduler ticks every 30 s, runs at most 4 sources
  at once and one per upstream host; a run is claimed in the database (`run_started_at`, stale after an hour,
  cleared at start), so two processes never run one source.
- **Network guard** (§12.3, decision 1): every user-entered URL (photo sources **and calendars**) goes through it.
  Each resolved address is checked when it is dialed and the connection goes to exactly that address (DNS rebinding
  can't swap one in), redirects (at most 5) are checked the same way, no proxies, TLS always verified (extra CAs from
  `KINFRAME_SOURCES_CA_FILE`). Loopback, unspecified, link-local, multicast and broadcast are always blocked; private
  networks (RFC 1918, `fc00::/7`, CGNAT) per `KINFRAME_SOURCES_PRIVATE_NETWORKS` (`auto`: allowed when the public
  URL's host is `localhost` or resolves only to private addresses) or `KINFRAME_SOURCES_ALLOWED_HOSTS`; plain
  `http://` only to allowed private addresses. iCloud requests are pinned to `icloud.com`/`icloud-content.com`,
  Google's to `googleapis.com`/`googleusercontent.com`/`google.com`. The server's own listen address is not treated
  specially beyond loopback.
- **Release note (calendars)**: calendar links on private addresses (a LAN CalDAV server) stop working on a public
  server unless `KINFRAME_SOURCES_PRIVATE_NETWORKS=on` (or the host is in `KINFRAME_SOURCES_ALLOWED_HOSTS`); plain
  `http://` calendar links to public hosts are refused (most providers redirect to https anyway). The test shows
  "this server may not reach private network addresses" / "only https:// links are allowed".
- Secrets: links, link passwords, Immich API keys, the PKCE verifier, the consent URL and Google access tokens are
  sealed with their own HKDF key (`kinframe photo source secrets v1`). A key change makes runs fail with
  `credentials_unreadable`. Logs carry hosts and ids only.
- Duplicate key: `sha256(provider|host|album:<Immich album id>)` for Immich (a new link to the same album is the same
  source), the share key/slug for individual-asset links, the token for iCloud. Concurrent creates are caught by the
  unique index (`409 duplicate_source`).
- Testing a connection, listing Immich albums: blocked addresses and unsupported links answer `200 ok: false` with
  the code (like every other test failure); create/update answer `422 validation_failed` (`private_address_blocked`
  on `url`/`server_url`) or `422 source_test_failed` (code in `detail`).
- Runs: the owner's rights are re-checked first (deleted or soft-deleted owners included). Listing errors never
  remove anything. History (`none`/`last_n`/`all`) is applied once, to the very first listing; the first import is
  quiet (album placements `backfill` with `received_at` = upstream added/capture time; feed posts `quiet`) until no
  pending row of it is left, so a backlog spread over several runs stays quiet. Downloads stop at
  `max_photo_source_run_bytes` or 15 minutes; the next run is 2 minutes later. A file that can't be read or isn't
  available is retried 3 times, then `failed` and retried daily for 7 days. Imported but unplaced items (a crash
  between download and placement) are placed by the next run.
- Album targets: one send per run (`sends.via`, `photo_source_id`), placements appended to the album in capture
  order, moderation unless the owner is owner/admin of the frame. Feed targets (decision 2): new items are downloaded
  and created at once but posted only when the outside album has had no new items for 30 minutes, or 3 hours after
  the first held item; `next_sync_at` is moved to that moment. One post per `max_items_per_send` items.
- Dedupe: (source, upstream id); then the upstream checksum against rows of other sources whose items are live in
  the target (before downloading); then sha256 against `media.source_sha256` and `media.original_sha256` of live
  placements on the album's frame / live post items of the feed (album duplicates join the album); then media
  reuse of the owner's same file. `media.original_sha256` is the `sha256` upload metadata of prepared uploads.
- Removals: mirror removes only the source's own hold (placements also held by a feed delivery stay); duplicates
  only leave the album. The mass-removal guard (> 20 and > 50 % of live items) holds them as `pending_removals` until
  `sync {confirm_removals: true}`, switching to `keep` (which clears it), or a new link. Tombstones
  (`removed_locally`): purge, trash expiry, moderation reject, unsend (album sources of that frame), out of the album
  (that album's sources), out of a feed post (that feed's sources). A frame rejecting a feed placement does not
  tombstone the feed source (other frames still show it). A sync never overwrites a tombstone with a stale row.
- Edits: caption (unless `caption_edited`), capture time (`items.captured_override`, rendered as `captured_at` and
  used for "taken" sorting and On this day), and a changed file (new media processed in the background, swapped into
  the same item by a later run; renditions follow, the placements are re-emitted then).
- Status: transient codes (`unreachable`, `upstream_error`, `rate_limited_upstream`) keep the source `active` with
  `last_error_code` until 3 failures in a row or 6 h, then `error`; back-off 1 min doubling to 6 h. Other codes are
  `needs_attention`; `link_not_found`, `album_not_found`, `auth_failed`, `password_required` (and `format_changed` for
  iCloud) are probed daily, the rest wait for a person (`next_sync_at` null). A PATCH of the connection, take-over or
  resume runs at once. Sync now: 2 minutes per source (`Retry-After`), 30/h per user.
- Notifications: `photo_source.updated` (whole `PhotoSource`, rendered per recipient) at run start, at run end and
  on every change through the API, to the album's library managers / feed owner+admins and the owner;
  `photo_source.removed` on deletion (also with the album or feed). Push `photo_source_attention` to the owner only:
  on `needs_attention`, or after 3 days in `error` (text `photo_source_unreachable`), at most once per source and code
  per 7 days (`attention_pushed`). Source and Google import sends get no `delivered` and no `failed` push.
  `notify_reactions: false` drops the owner's reaction/reply event and push (history and receipts still update).
- Google: consent with PKCE S256, `access_type=online`, `prompt=select_account`, state hashed and single use (cleared
  at the callback whatever happens), 15 minutes. The callback is hand-written (excluded from codegen) and answers
  `302` to the stored `return_uri` with `import_id` and `status`, or a small `400` HTML page for an unknown, reused or
  expired state. Allowed `return_uri` values, exactly: `app.kinframe:/google-import` (Android and iOS: the apps'
  `app.kinframe` scheme) and `<public URL>/google-import/return` (the web SPA route, served by the SPA fallback; also
  usable as an Android app link). The picker step on web appends `/autoclose` itself. Polling uses Google's
  `pollInterval` (at least 1 s, at most 30 s); `PERMISSION_DENIED`/`NOT_FOUND` on the session means expired. Items:
  `=d` (photos; `=w2560-h2560` when the file isn't readable), `=dv` (videos once `READY`, else waited for up to
  10 minutes), the listing is repeated after 50 minutes for fresh base URLs, same-file picks count as duplicates.
  One send into the album, or posts of 200 into a feed; never quiet. Then the session is deleted. Tokens: a `done`
  import keeps its sealed token until it expires (≤ 1 h) so the next import within the hour skips consent (§7.3);
  failed, expired and cancelled imports forget it at once; maintenance clears every token after expiry or 2 hours,
  fails imports waiting for consent after 15 minutes, and deletes finished imports after 7 days. Push
  `google_import_done` (text `google_import_failed` for failures) only without an open websocket. A restart resumes
  `picking`/`importing` imports; items copied before a crash but not yet placed are not placed (known gap).
- Rate limits (in memory): test + Immich albums 30/h, create 30/h, sync now 30/h per user, Google starts 20/h.

## Email to frame

- Migration `00020_email_inbox.sql` exactly as docs/spec/email-inbox.md §16 (the generic channel columns on invites
  and join requests are shared with Telegram). Code: `internal/mailin` (receiver, webhook signatures, SPF/DKIM/DMARC,
  MIME walk, captions, reply texts), `internal/api/email*.go`, `internal/store/email.go`; the spec's
  `internal/inbox`/`api/emailinbox.go` names were not used. Queue processing runs in its own loop
  (`RunEmailInbox`, `FOR UPDATE SKIP LOCKED` with a 15-minute lease) instead of a worker job `email_process`.
- Libraries (all permissive, AGPL-compatible): `github.com/emersion/go-smtp` (MIT), `github.com/emersion/go-message`
  (MIT), `github.com/emersion/go-msgauth` (MIT), `blitiri.com.ar/go/spf` (MIT), `github.com/pires/go-proxyproto`
  (Apache-2.0), `golang.org/x/net` publicsuffix/html/idna (BSD-3).
- Verified emails: tokens carry `email_verified` (the bootstrap's access-token script sets it with `primaryEmail`;
  dev tokens with an email). `users.email` and `email_verified` are refreshed on every request whose token differs; an
  email another live account also has is verified for neither (both are unverified). Re-run `bootstrap-logto` so the
  script adds the claim; until then nobody is matched by account (their mail is treated as from a non-account sender).
- DKIM: go-msgauth refuses `l=` signatures outright (the spec would count them when they cover the whole body).
  Expired `x=` fails. ARC is not evaluated. The SMTP greeting is go-smtp's (`220 <host> ESMTP Service Ready`), and
  `250 2.0.0 Queued as <id>` is returned. The data phase is bounded by the 5-minute command timeout (not a separate
  10 minutes); sessions end after 30 minutes. The TLS handshake has 10 s from the ClientHello.
- Webhook mail is authenticated in the request (so `rejected_dmarc` can be answered); a temporary DNS error leaves it
  to the worker, which retries 3 times 10 minutes apart and then treats the mail as unauthenticated. SMTP rejects with
  `451` instead. Refused DMARC mail is logged in Recent mail for the webhook only.
- Matched accounts: items of a matched account's mail carry no `via.address_id` (only the send does), so
  `notify_reactions` (the owner's switch) applies exactly to on-behalf items. The `delivered` push is skipped for every
  `via` send, email included.
- Held mail: media rows are created (owner = address owner) and processed at once; 256 px webp previews are made by
  the minutely loop from the master/poster (`email-held/…` blob keys), until then previews sign the master or poster.
  Held media are deleted explicitly (rows and blob keys) on reject, delete, expiry and caps. Unverified mail that
  claims a listed sender is held under that sender's row; the pending list includes listed senders with held mail.
- Confirmations are coalesced per sender and address (one per 10 minutes; the minutely loop sends the summary once
  the sender has been quiet for 10 minutes). "Already using the app" is a registered `unifiedpush`/`fcm`/`apns`
  endpoint only (no per-user record of recent app requests exists). Store links come from
  `KINFRAME_APP_{IOS,ANDROID}_STORE_URL`; without them the server's public URL is the download link.
- Channel invites: `invites.via`/`feed_invites.via` set means not listed and always needing approval; redemption
  calls a resolver registered per channel (`channelJoins`, email in `email_invite.go`) for `via_name`, `via_ref` and
  `via_matches`.
- Frames and feeds that are deleted keep their addresses until the janitor retires them (`PruneEmail`, every
  janitor round); local parts of addresses removed by a hard delete before that are not retired (unlikely).
- The shop mailer was extended in place (`In-Reply-To`/`References`, RFC 3834 headers, one attachment) rather than
  moved to `internal/mail`.
- Not built: the Ansible/compose variables and `docs/deploy.md` steps (§14.2, left to the deploy work), inbound-parse
  adapters, HEIC/video fixtures in the e2e tests (sniffing and probing are the upload path's, tested there).

## Telegram bridge

- Migration `00021_telegram.sql`: the tables of telegram.md §16 plus `telegram_bot.webhook_hash` (sha256 of the
  registered URL, secret and updates: `getWebhookInfo` doesn't return the secret), `api_base` (one cloud `logOut`
  before a local Bot API server), `commands_hash`; `telegram_updates.next_attempt_at`; `telegram_strays` (groups the
  bot was added to without a code: explained once after 20 s, left after 10 minutes); per contribution `media`
  (what to download on a retry), `caption`, `message_date`, `thread_id`, `retry_at`, `replied_frames`, `closed`;
  per chat `ready_sent_at`, `spoiler_notice_at`, `farewell_due`; batches `label` and `album_at`;
  `sends.telegram_outside`. Frames and feeds are soft-deleted, so a trigger on `deleted_at` ends their chats with
  `target_deleted` (the `ON DELETE SET NULL` of the spec never fires); the consumer then says goodbye and leaves.
- Code: `internal/telegram` (Bot API client with token redaction, webhook secret, update reduction, batching rule,
  outbound queue, poller, per-chat dispatcher, advisory-lock leadership; `telegramtest` is the fake Bot API),
  `api/telegram.go` (start, consumer, webhook, intake), `telegram_chat.go` (membership, migration, commands, codes),
  `telegram_media.go` (sender decision, downloads, batches, sends), `telegram_out.go` (messages, reactions,
  promotion, join links, pushes), `telegram_api.go` (endpoints). Texts in `internal/i18n/locales/telegram/*.json`,
  a feature catalog merged into each language at load.
- One consumer: the process holding the advisory lock (re-checked every 15 s on its own connection) registers the
  webhook or polls, processes updates and closes batches. Every process paces its own outbound calls (in memory, not
  replayed after a restart: reactions and replies are best effort). The webhook stores and answers 200 in any
  process; a 503 on a database error lets Telegram retry.
- Updates are processed in order per chat; a failing update holds its chat back (retried after 1, 2 minutes, then
  `failed`). Downloads that fail temporarily don't block the chat: the contribution waits (`retry_at`, 2 then 10
  minutes, failed after 3 attempts) and a batch waits for it at most 10 minutes past its closing time.
- Batches: an album message always joins the batch holding its album, so a batch can exceed `max_items_per_send` by
  at most one album (albums are never split). The closer ticks every 5 s.
- Sender decision: an anonymous admin or channel post (`sender_chat`) is one contributor keyed by that chat's id.
  A linked user without send rights is credited with their kinframe name, sent by the owner. Owners are re-checked on
  every media message (not only per batch).
- Outside contributions: `sends.telegram_outside`; they get no `delivered`/`failed` push and their reactions reach
  the owner only with `notify_reactions`. A linked user's Telegram send gets the normal pushes and `last_sent_at`.
  Outside contributions are placed moderated whenever the frame moderates. Rejecting one does not change its
  contribution row (counts are kept incrementally; the item simply isn't shown).
- Join codes are reusable by the intended Telegram user while valid (tapping the button twice works). Accepting a
  Telegram channel invite links the redeemer's Telegram account to the account that accepted (via the email inbox's
  channel join resolver), so after approval their photos are their own sends.
- "Get the app" in the welcome opens the server's web page (https) or the Play listing; private-chat app messages
  carry App Store, Google Play (the operator's store URLs, else the public listings) and Web.
- `/status` shows the last delivery in UTC (no time zone is known for a chat). Command menus are registered for every
  language except zh-TW (Telegram's language codes have no script; zh gets Simplified Chinese).
- Not done: `KINFRAME_TELEGRAM_WEBHOOK_IP_CHECK` uses the request's client address (set `KINFRAME_TRUST_PROXY`
  behind a proxy); edited media, round videos (spec open questions) are ignored.

## Settings

- Stale `If-Match` is accepted when none of the patched fields changed since that version (field-level merge);
  otherwise `412 version_conflict`, after which the client re-applies, so the latest write wins. Missing `If-Match` →
  400. `version` and `kiosk.owner_pin_set` are read-only and ignored in patches. The merged document is validated
  against `FrameSettings`; unknown fields are preserved.
- Owner PIN: only the owner may send `kiosk.owner_pin` (4–6 digits or null; others get 403). It is replaced by
  `kiosk.owner_pin_hash` (PBKDF2-HMAC-SHA256, 20 000 iterations, 16-byte salt) before merging, so the PIN is never
  stored. People never see the hash and can't write it; the frame gets it in the feed and may replace it with
  `PATCH /device/settings` when the PIN is changed at the frame. `owner_pin_set` is derived from the hash.

## Weather

- Payloads are always metric (the existing `*_c` field names); frames convert for `locale.temperature_unit`. So the
  cache key is only the location rounded to 2 decimals (`"51.05,3.72"`), not location + unit: one upstream request per
  town however frames are configured.
- Cache and per-frame state live in PostgreSQL (`weather_cache`, `frame_weather`), so restarts neither refetch
  nor lose what frames were sent. The feed entry (kind `weather`, entity = frame id) renders from the cache at read
  time, with the location (name included) taken from the frame's current settings.
- One loop ticks every minute; a location is fetched when due (refresh + 0–5 min jitter, or back-off after errors:
  1 min doubling up to the refresh interval). Locations no frame wants are not fetched, and pruned after a day.
- "Meaningful change" = a digest over values rounded the way the frame shows them (temperatures to 0.5°, humidity
  to 5 %, wind to 1 km/h, rain probability to 10 %), codes, day/night, the hourly window start, daily dates and the
  place name. Unchanged weather is re-sent every 2 h while fetches succeed so the frame's 6 h staleness check holds.
- A settings patch touching `location*` or `widgets.weather` syncs that frame in the background right after commit;
  a cached entry that is not due is reused instead of refetched.
- Geocoding: 60 requests/min per user, results cached in memory for 24 h per (query, language, count), at most
  2000 entries. Queries shorter than 2 characters after trimming return `[]` without an upstream call.

## Push

- Endpoints are stored for all kinds. Delivery is implemented for UnifiedPush (plain POST of the JSON payload) and
  WebPush (RFC 8291 aes128gcm encryption + RFC 8292 VAPID, via `github.com/SherClockHolmes/webpush-go`, TTL 24 h).
  FCM and APNs are not delivered yet. Push is only used when the user has no open websocket.
- Payload: `{type, key, title, body, ids}`; `type` is the websocket event type, `key` the message (`reaction`,
  `reply`, `delivered`, `failed`, `join_request`, `membership`), title/body localized (en/nl) from the endpoint's
  `locale`. Reaction and reply pushes carry `send_id` too, so clients can open the send.
- `delivered` is pushed once per send and frame, by the ack that delivers its last placement there (seen-after-
  delivered doesn't repeat it). Placements of a send that failed don't hold it back.
- A push service answering 404/410 (or a webpush endpoint without keys) deletes the endpoint.
- VAPID keys: `KINFRAME_VAPID_*` or generated once into `<data dir>/vapid.json`; the public key is in discovery
  (`webpush_public_key`, contract addition) for `PushManager.subscribe`.

## Rate limits (in memory, per process)

Redeem 10/h per user and 30/h per IP (also used for replace-device); enroll 20/h per IP; device token 120/h per IP;
invites 60/h per user; birthday create/suggest 60/h per user; dev tokens 120/h per IP.

## Not done yet (v0.1 follow-ups)

- Face detection for focal points (interface `worker.FocalDetector`, centre for now); perceptual duplicate detection.
- Calendar.
- Garbage collection of media no longer referenced by any live placement.
- FCM/APNs delivery. When it is built: pushes with a tag (`notify.Push.Tag`, birthday greetings
  `birthday:<birthday_id>:<date>`) must set `apns-collapse-id` to the tag on APNs (at most 64 bytes; these are 56)
  and the notification `tag` on FCM, so the newer greeting replaces the older one.
- Media tool versions: the image is **Debian 13 (trixie)**: libvips 8.16, ffmpeg 7.1. It was moved off bookworm
  because libvips 8.14 there did **not** strip EXIF/GPS with `[Q=..,strip]`, which `TestPhotoPipeline` caught inside
  the image. The root cause in 8.14 wasn't investigated further. `make test-docker` (full suite with
  `KINFRAME_REQUIRE_MEDIA_TOOLS=1`) passes on trixie, and so does the suite on the dev laptop with Debian 13's system
  vips/ffmpeg. Production (appmire-hetz1) is Debian 13 as well.
