# Photo sources

Status: **draft** (2026-11-19). Extends `product-spec.md` (§3 concepts, §4.6 albums, §4.11 "Album sync sources"),
`feeds.md` (a source can post to a feed) and the album model in `docs/api/openapi.yaml`. The API contract is in
`docs/api/openapi.yaml` (tag `photo-sources`: `/v1/photo-sources…`, `/v1/google-imports…`, the OAuth callback; the
`ItemVia` credit on items, sends and posts) and the protocol guide `docs/api/README.md` §5.14. Tables are in §15
below.

## 1. Summary

Families already keep their photos somewhere: an Immich server at home, an iCloud shared album the grandparents'
iPhones add to, a Google Photos library. **Photo sources** bring those photos to the frames without anyone having to
send them again.

- **Sync** (Immich, iCloud shared albums): connect an outside album once. The server checks it about every half hour
  and keeps a kinframe **album** (on one frame) or a **feed** (on every subscribed frame) up to date: new photos
  appear, removed ones go (for albums), edited captions and dates follow.
- **Import** (Google Photos): Google no longer lets apps read a library or album. "Add from Google Photos" opens
  Google's own picker; the photos the person picks are copied once into an album or posted to a feed.
- **Nothing new for frames.** Imported photos are ordinary items and placements. Frames that know about it show
  "Anna via Immich"; older frames just show "Anna".
- **Calm.** A sync never sends a push per photo or per run. People hear about a source only when it needs them.

Providers and how well they are supported:

| Provider | `provider` | How | Support |
|---|---|---|---|
| Immich, shared link | `immich_link` | A public album link (`https://photos.example.org/share/…`), optional password | **Recommended**: least privilege, official API |
| Immich, API key | `immich_api` | Server URL + an API key with read permissions, album picked from a list | Official API |
| iCloud shared album | `icloud_link` | The album's public website link (`https://www.icloud.com/sharedalbum/#…`) | **Best effort**: no official API, may break without notice |
| Google Photos | (import, not a source) | Google Photos Picker API, one-time per pick | Official API |

## 2. Concepts

- **Photo source** (`PhotoSource`): a connection from one outside album to one **target**. Fields:
  - `provider`: `immich_link`, `immich_api` or `icloud_link`.
  - `target`: exactly one of
    - an **album** (`album_id`; albums belong to one frame today, so the source fills that frame's album), or
    - a **feed** (`feed_id`; every active subscribed frame gets the photos, `feeds.md`).
  - `owner`: the account that set the source up. Imported items are **sent by the owner**: they carry the owner's
    name and avatar, the owner hears about reactions, and the owner's rights are checked on every run (§4).
  - The connection: the link (and optional password), or the Immich server URL, API key and album id. Secrets are
    write-only (§12).
  - Settings: `history` (what the first run imports: `none` "from now on", `last_n` with `history_count`, or `all`),
    `on_upstream_delete` (`mirror` or `keep`), `include_videos` (default on), `notify_reactions` (default on).
  - Status (§5.1), counts and the last run.
- **Upstream asset**: one photo or video in the outside album, identified by the provider's own id (Immich asset id,
  iCloud photo guid). The server keeps one **source item** row per (source, upstream id) that remembers what became
  of it (§5.2). This is the first dedupe key.
- **Run**: one sync of one source: list the outside album, compare, download what is new, apply edits and removals,
  and record the outcome.
- **Source send**: the photos one run adds become one **send** by the owner (marked `via`, so history shows "12 photos
  via Immich"). For an album target the send's placements go to the album's frame and into the album; for a feed
  target the send becomes one or more **posts** (§9.2). Everything downstream (renditions, change feed, moderation,
  receipts, reactions, unsend) is the existing machinery.
- **Credit** (`ItemVia`): every imported item records the service it came from (`immich`, `icloud`, `google_photos`)
  and, for syncs, the source. Frames and apps show "Anna via Immich" (album, direct) or "Anna · Peeters kids via
  Immich" (feed).
- **Google import** (`GoogleImport`): one "Add from Google Photos" action: consent, picking, copying. Not a source:
  it has no schedule and is forgotten 7 days after it finished.

## 3. User stories

### Family app and web: album target

1. *Connect an Immich album (shared link).* Anna manages Oma's frame. In Oma's frame → Photos → Albums → "Garden",
   she taps "Sync from…" → Immich. The sheet recommends a shared link: "In Immich, open the album, tap Share → Create
   link, allow downloads, and paste the link here." She pastes it, taps **Test**: "Garden 2026 · 214 photos, 6 videos
   · about 1.1 GB". She keeps "Import everything" and "When a photo is removed in Immich: remove it here too" (the
   album default) and taps **Connect**. The album shows "Syncing from Immich · importing 40 of 220".
2. *Connect with an API key.* Ben runs Immich himself and wants to pick from his albums. He chooses "Immich server",
   enters `https://photos.peeters.be` and an API key (the sheet explains which permissions to tick in Immich), and
   picks "Christmas" from the list of his own and shared albums.
3. *iCloud.* Carla's parents add photos to an iCloud shared album. She turns on *Public Website* for the album on her
   iPhone, copies the link and pastes it under "Sync from… → iCloud shared album". The sheet says "Best effort: Apple
   offers no official way to do this. It may stop working without warning; we'll tell you if it does."
4. *Watch it work.* The album header reads "Synced from Immich · 2 minutes ago · next in 28 minutes". A photo Anna adds
   to the Immich album at 10:05 is on Oma's frame by about 10:35, or right away with **Sync now**.
5. *Something broke.* Anna's Immich link was deleted. The album header turns amber: "The link no longer works. Paste a
   new link, or stop syncing." Anna gets one notification about it, not one per attempt.
6. *Pause, change, stop.* Anna pauses the sync while she reorganises in Immich, changes the deletion behaviour, or
   stops syncing. Stopping asks: "Keep the 220 photos on the frame, or remove them?"

### Family app and web: feed target

1. *A feed fed from Immich.* Anna (owner of the feed "Peeters kids") taps Feed → Sources → Add → Immich and pastes the
   shared link of the family's "Kids" album. For feeds the defaults are "Import the last 20" and "When a photo is
   removed in Immich: keep it in the feed".
2. *Posts, not a flood.* At 18:30 the sync finds 14 new photos: one post "Anna · 14 photos via Immich" appears in the
   feed and on all five subscribed frames. Nobody in the feed gets a notification; the frames show "14 new" as for any
   post.
3. *Receipts.* Anna sees "Seen on 4 of 5 frames" and Oma's ❤ on that post, exactly as for her own posts, and gets the
   reaction notification (she can turn reaction notifications off for this source).
4. *Admins.* Ben, an admin of the feed, sees the source, can pause it or stop it, but sees neither the link nor the
   key.

### Family app and web: Google Photos import

1. Anna taps **Add from Google Photos** in the "Garden" album (or in a feed's Post menu). A browser sheet opens
   Google's sign-in and consent ("kinframe wants to: see the photos you select"). Afterwards Google Photos opens (the
   app on Android if installed, the web picker elsewhere). She picks 23 photos and taps **Done**.
2. Back in kinframe: "Adding 23 photos from Google Photos… 9 of 23". When done: "23 photos added" (or "21 added, 2
   were already there"). In a feed it is one post (several when she picked more than 200).
3. The next time, Google asks only which account to use (no long-lived Google access is kept, §7.4).

### Frame owner (frame manager)

1. Frame settings → Photos shows which albums sync from where, and their status.
2. Moderation on the frame applies to synced photos exactly like to sends: with "approve before showing" on, a feed
   post from a source waits in the frame's queue.

### Frame viewer

1. Synced photos look like any other. The caption credit reads "Anna via Immich" (or "Anna · Peeters kids via
   Immich"); reactions go to Anna.
2. The first import of a large album joins the slideshow quietly (no "220 new", no screen wake); later additions are
   new photos like any send.

### Operator

1. Photo sources are on by default (`KINFRAME_PHOTO_SOURCES`), iCloud can be turned off on its own
   (`KINFRAME_ICLOUD_SOURCES=off`).
2. Whether sources may reach addresses on the server's own network (a LAN Immich) is a server setting (§12.3).
3. Google Photos import needs a Google Cloud OAuth client (§7.5); without one the button is hidden everywhere.
4. Limits (sources per album/feed/user, items per source, bytes per run, items per Google import) are in
   `PUT /v1/operator/limits` like the other limits.

## 4. Permissions

### 4.1 Album sources

"Album manager" = owner, admin, or a member with *manage library* on the album's frame (the people who may edit that
album today).

| Action | album manager | other frame member with *see library* | other frame member |
|---|---|---|---|
| Create a source on the album (becomes its owner) | ✓ | — | — |
| List the album's sources (provider, name, status, counts, owner) | ✓ | — (sees `Album.photo_source_count` only) | — |
| See `url_hint`, `server_url`, errors | ✓ | — | — |
| Pause, resume, sync now, change settings, replace link/password/key | ✓ | — | — |
| Take over (become owner, `take_over`) | ✓ | — | — |
| Delete the source (keep or remove its items) | ✓ | — | — |
| Hide, trash, purge, remove from album one imported photo | as any placement (*manage library*; the owner as sender) | as any placement | — |

### 4.2 Feed sources

| Action | feed owner | feed admin | poster |
|---|---|---|---|
| Create a source on the feed (becomes its owner) | ✓ | ✓ | — |
| List the feed's sources and their status | ✓ | ✓ | — |
| Pause, resume, sync now, change settings, replace credentials, take over, delete | ✓ | ✓ | — |
| See the posts a source made, their credit "via Immich" | ✓ | ✓ | ✓ (like any post) |
| Receipts and reactions of source posts | ✓ | ✓ | only with *members see reactions* |
| Remove a source post or item | ✓ | ✓ | — |

The source owner sees receipts and reactions of the source's posts because they are the poster.

### 4.3 The owner's rights are re-checked

Every run starts by checking that the owner still has the right to create the source (album manager of the album's
frame, or owner/admin of the feed). If not (they left the frame or the feed, lost *manage library*, were demoted to
poster, deleted their account), the run stops and the source goes `needs_attention` with `owner_lost_access`. Any
other album manager (album) or feed owner/admin (feed) may **take over** (`PATCH {take_over: true}`): they become the
owner and the credit for future photos; photos already imported keep their original sender. For `immich_api` sources
taking over requires entering an API key again (the old one belongs to someone else's Immich account); links carry
over, a link password too.

### 4.4 Google imports

| Action | Who |
|---|---|
| Import into an album | album managers of its frame |
| Import into a feed (one or more posts) | any feed member (posters too: an import is a send) |
| See, poll, cancel an import | the person who started it |

### 4.5 Visibility

- Unknown sources and sources the caller may not manage answer `404 not_found` (like feeds). Imports of other people
  too.
- Secrets are never returned (§12.1). `url_hint` and `server_url` are shown to managers only.
- Frame members without management rights and feed posters see only the credit on the photos.

## 5. States

### 5.1 Photo source

```
            create (test passes)
                   │
                   ▼
   ┌────────▶ active ◀────────── resume ─────────┐
   │         │  ▲  │                             │
   │ transient  │  └──── pause ────▶ paused ─────┘
   │ failures  recovered                ▲
   │         ▼  │                       │ pause
   │        error ───────────────────────┤
   │         │                           │
   │  needs a person (auth, link gone, owner lost access, mass removal, …)
   │         ▼                           │
   └── fixed ── needs_attention ─────────┘
                    │
                 delete (any state) ──▶ (deleted; items kept or removed)
```

| Status | Meaning | Syncing | UI |
|---|---|---|---|
| `active` | Syncing on schedule. A single failed run that is being retried keeps the source `active` with `last_error_code` set. | Every 30 min (iCloud: 60) + jitter | Green "Synced 2 min ago" |
| `error` | Failures that are expected to go away (server unreachable, upstream 5xx, rate limited) for **3 runs in a row or 6 hours**. | Backoff: 1 min doubling, at most 6 h | Red "Can't reach photos.peeters.be since Tuesday 10:00. We keep trying." |
| `needs_attention` | Something only a person can fix (§13: `auth_failed`, `link_not_found`, `owner_lost_access`, `mass_removal`, …). | Stopped, except one cheap probe a day for errors that may fix themselves upstream (`link_not_found`, `album_not_found`, `auth_failed`, `password_required`, `downloads_disabled`); a probe that works returns the source to `active` | Amber, with the fix as a button |
| `paused` | Paused by a manager. Imported photos stay and keep showing. | No | Grey "Paused" |

- `running: true` while a run is in progress (any status but `paused`); clients show a spinner, not a state.
- Changing the link, password or key of a source in `error` or `needs_attention` starts a run at once; success
  returns it to `active`.
- Repeating a transition (pause a paused source) returns it unchanged (`200`).
- Errors never count as removals: a failing source never removes anything (§8.3).

### 5.2 Source item (one per upstream asset)

| `state` | Meaning |
|---|---|
| `pending` | Seen upstream, not imported yet (backlog: the run's byte budget ran out, or downloads are retried). |
| `imported` | Became an item of this source (`item_id`). |
| `duplicate` | The target already had the same photo (§8.1); `item_id` points at the existing item, nothing new was made. |
| `skipped` | Deliberately not imported: `skip_reason` = `video_off` (*include videos* is off), `too_long` (video longer than `limits.max_video_seconds`), `too_large` (bigger than `limits.max_upload_bytes`), `unsupported` (format kinframe can't read and no rendered version exists), `over_limit` (`max_photo_source_items` reached), `history` (before the initial import's cut). |
| `failed` | Download or processing failed 3 times (`failure_code`); retried once a day, at most 7 days. |
| `removed_locally` | Someone on kinframe removed it (purged on the frame, trash expired, moderation rejected, removed from the album, removed from the feed post). **Never imported again** from this source. |
| `gone_upstream` | No longer in the outside album. With `mirror` its kinframe item was removed; with `keep` it stays. If it comes back upstream it is imported again (unless `removed_locally`). |

### 5.3 Google import

```
start ──▶ needs_auth ──consent──▶ picking ──items picked──▶ importing ──▶ done
              │                      │                         │
              │ denied / 15 min      │ picker expired          │ all failed / target gone
              ▼                      ▼                         ▼
           failed                 expired                   failed
   (cancel from needs_auth, picking or importing ──▶ cancelled; items already added stay)
```

| Status | Meaning | Client shows |
|---|---|---|
| `needs_auth` | Waiting for Google consent; `auth_url` is set. Skipped when the user's previous import left a token with more than 10 minutes to live (§7.4). | Opens `auth_url` in a browser session |
| `picking` | A picker session exists; `picker_uri` is set; the server polls Google until the person taps Done. | Opens `picker_uri`, "Waiting for Google Photos…" |
| `importing` | Downloading and adding; `progress` counts up. | Progress bar |
| `done` | Finished. `progress.failed` may be > 0 ("21 added, 2 couldn't be copied"). | Summary |
| `failed` | Nothing could be done (`error_code`). | Error with retry |
| `expired` | The picker session expired before anything was picked (Google's `expireTime`, about 30 minutes). | "Try again" |
| `cancelled` | The user cancelled. Photos already added stay (undo is the normal unsend). | — |

Imports are deleted 7 days after they finished; their items are ordinary items from then on.

## 6. Sync

### 6.1 Schedule

- Every **30 minutes** (`KINFRAME_PHOTO_SOURCE_SYNC_MINUTES`, at least 10) plus up to 5 minutes of jitter; iCloud
  sources never more often than every **60 minutes** (unofficial endpoints, be polite).
- Right after creation, after the connection changes, after resume, and on **Sync now** (`POST …/sync`): at most
  once per 2 minutes per source (`429` with `Retry-After`) and 30 per hour per user.
- A run that stopped because of its byte budget schedules the next one **2 minutes** later until the backlog is
  empty ("catching up").
- Failures back off from 1 minute, doubling up to 6 hours. At most 4 runs at once per server, at most 1 per upstream
  host (a family's Immich is a small box).
- Paused sources and sources in `needs_attention` don't run (except the daily probe, §5.1).

### 6.2 One run (all providers)

1. **Lock** the source (`running`); a second trigger while running is a no-op.
2. **Check** the target still exists and the owner still has rights (§4.3).
3. **List** the outside album (metadata only, §6.3–6.5) into upstream assets: `id`, `kind` (photo, video, live),
   `taken_at` (+ time zone when known), `caption`, a content fingerprint (`checksum`), size, and for live photos the
   id of the motion clip. A cheap change hint (Immich album `updatedAt` + `assetCount`, iCloud `streamCtag`) lets a
   run stop here when nothing changed; a full comparison still happens at least every 6 hours.
4. **Compare** with the source items:
   - *new*: upstream ids without a row (or `gone_upstream` rows that came back);
   - *changed*: caption, capture time or checksum differs from what was recorded;
   - *gone*: `imported`/`duplicate`/`pending` rows whose id is no longer listed.
5. **Removals** (§8.3): with `mirror`, remove the gone items, unless the mass-removal guard trips; with `keep`, mark
   them `gone_upstream`.
6. **First run only**: apply `history` to the new assets (newest first by `taken_at`): `none` marks them all
   `skipped (history)`; `last_n` keeps the newest N; `all` keeps up to `max_photo_source_items`.
7. **Download** new assets newest first, until the run's byte budget (`max_photo_source_run_bytes`, default 2 GiB) or
   time budget (15 minutes) is used. The rest stays `pending` for the next run. Per asset: stream to the scratch
   volume with a size cap, sniff by magic bytes (never trust the upstream MIME type), hash (sha256), dedupe (§8.1),
   then create the media and its processing job as for an upload. The download is deleted once the master exists
   (product spec §8 decision 2).
8. **Place**: one source send by the owner for everything this run added; album target: placements on the album's
   frame, appended to the album in capture order; feed target: post(s) (§9.2). First-run items are quiet (§9.3).
9. **Edits** (§8.4) for changed assets.
10. **Record**: counts, `last_run`, `last_synced_at`, `next_sync_at`, status; emit `photo_source.updated`.

All of a run's database writes for one frame happen under that frame's lock, as for sends; a crash mid-run leaves
`pending` rows that the next run picks up. Item creation is idempotent per (source, upstream id).

### 6.3 Immich, shared link (`immich_link`)

What the server sees from the outside (Immich's public REST API, the same calls Immich's own shared-album page
makes):

- **Protocol (checked against Immich's OpenAPI spec, 2026-11)**: `GET /api/shared-links/me?key=|slug=` returns
  `type` (`ALBUM`/`INDIVIDUAL`), `allowDownload`, `showMetadata` (not `showExif`), `expiresAt`, `album`, `assets`; a
  password-protected link is unlocked with `POST /api/shared-links/login?key=` `{password}` (201; newer servers, the
  session is kept in a cookie), older servers take `password` on `/shared-links/me` and return a `token` for the
  following calls; the server supports both. Error statuses for wrong, missing or expired passwords and deleted links
  are not documented: 401/403/404/400 with Immich's message are mapped by their text (`password`, `expired`).
  `GET /api/albums/{id}` (`key`/`slug`, `withoutAssets`), `GET /api/albums` (own) and `?shared=true` (shared with
  the account), `POST /api/search/metadata` with `albumIds` (newer: `filter.albumIds`, `cursor`/`nextCursor`; older:
  `page`/`nextPage`, both handled), `GET /api/assets/{id}/original`, `/thumbnail?size=preview|fullsize`,
  `/video/playback`, `GET /api/server/version` (`major`, `minor`, `patch`; `/api/server-info/version` on old
  servers). Asset `duration` is milliseconds on current servers (`H:MM:SS.mmm` on older ones), `checksum` is the
  base64 SHA-1, the file size is `exifInfo.fileSizeInByte`. API keys go in `x-api-key`; permission names include
  `album.read`, `asset.read`, `asset.view`, `asset.download`.
- **The link**: `https://<host>[/<prefix>]/share/<key>` (or `/s/<slug>` on versions with custom link slugs). The
  server derives the API base `https://<host>[/<prefix>]/api` and the key (or slug). Anything else is
  `422 validation_failed` (`url`).
- **Access**: every call carries the key as a query parameter (`?key=…`, or `?slug=…`). A password-protected link is
  unlocked first with a login call for the link (password in the body); Immich answers with a short-lived token for
  that link, sent on the following calls. The password is stored sealed (§12.1) because each run logs in again.
- **The link itself**: "my shared link" returns its type (album or individual assets), whether downloads are allowed,
  whether metadata (EXIF) is shown, its expiry, and the album (id, name). An expired or deleted link answers
  401/404 → `link_expired` / `link_not_found`. An individual-assets link works too: its assets are the "album".
- **The album**: the album endpoint with the key returns the album's name, `updatedAt`, `assetCount` and its assets.
  Each asset has `id`, `type` (image/video), `originalMimeType`, `originalFileName`, `fileCreatedAt`,
  `localDateTime`, `checksum` (SHA-1 of the original, base64), `livePhotoVideoId`, `isTrashed`, `isOffline`,
  `updatedAt`, `duration`, and when metadata is shown `exifInfo` (`dateTimeOriginal`, `timeZone`, `description`,
  `fileSizeInByte`, size).
- **Downloads**: the asset's *original* (only when the link allows downloads), else Immich's large *preview* image
  (`thumbnail?size=preview`), or `size=fullsize` on versions that generate it; videos: the original, else Immich's
  *playback* encoding. Live photo motion clips are the original of the `livePhotoVideoId` asset.
- **Limits of a link**: without *allow download* only previews are available (warning `downloads_disabled`: "Photos
  come in reduced quality because the link doesn't allow downloads"); without *show metadata* there are no capture
  dates or descriptions (warning `metadata_hidden`; `fileCreatedAt` is used as the date).
- Trashed (`isTrashed`) assets count as gone; offline assets (`isOffline`, files Immich can't read) are skipped this
  run and retried; archived assets that are still in the album stay.

Why recommended: the link gives access to exactly one album, read-only, revocable in Immich with one tap, and never
to the person's account.

### 6.4 Immich, API key (`immich_api`)

- **Connection**: `server_url` (`https://photos.example.org`, an `/api` suffix is tolerated) and an API key sent as
  the `x-api-key` header. The sheet asks for a key with only `album.read`, `asset.read`, `asset.view` and
  `asset.download` (Immich's scoped API keys); a key without them fails the test with `permission_missing` naming the
  missing one where Immich says so.
- **Picking**: `listImmichAlbums` (before creating) lists the account's own and shared-with-me albums (id, name,
  asset count, updated, owner) and the server version; the client lets the person pick one (`immich_album_id`). One
  source per album.
- **Listing and downloads**: as §6.3 with the key instead of the link (originals are always downloadable, metadata is
  always visible). Very large albums: if the album response exceeds the response cap (64 MiB), the server pages
  through Immich's metadata search filtered by album instead (Immich versions that support it); otherwise
  `too_large`.
- **Versions**: tested against Immich v1.120 and later (scoped API keys); the test result reports `server_version`.
  Older servers may work for links; an API that answers in an unexpected shape fails with `format_changed`.

### 6.5 iCloud shared album (`icloud_link`), best effort

Apple offers **no official API** for shared albums. The album's public website (*Public Website* switched on in the
shared album's settings on an iPhone, iPad or Mac) is a web page that fetches its contents from publicly reachable
JSON endpoints; kinframe calls those endpoints the way that page does. Described from observable behaviour:

- **The link**: `https://www.icloud.com/sharedalbum/#<token>` (also without `www`, and with a language segment such as
  `/sharedalbum/nl-nl/#<token>`). The token is the part after `#`.
- **The host**: the page talks to `https://p<NN>-sharedstreams.icloud.com/<token>/sharedstreams/…`, where `<NN>` is
  a partition number encoded in the token's first characters. When the server picks the wrong partition, iCloud
  answers with a non-standard redirect status (330) whose JSON body names the right host; the server retries there
  once and remembers the host for this source.
- **Metadata**: a POST to `…/webstream` (JSON body with the last stream change tag, or null) returns the album name,
  the owner's first and last name, a change tag (`streamCtag`) that changes whenever the album does, and the items:
  per item a stable `photoGuid`, its `batchGuid` (one upload batch), `dateCreated`, `batchDateCreated`, `caption`,
  `contributorFullName`, `width`, `height`, `mediaAssetType` (set for videos) and `derivatives`: the available
  renditions by size key, each with a `checksum`, `fileSize`, `width` and `height`.
- **Download URLs**: a POST to `…/webasseturls` with a list of photo guids (the server sends 25 at a time) returns,
  per derivative checksum, a path and a host location with an expiry of a few hours. The server asks for them right
  before downloading and downloads only from hosts under `icloud.com` / `icloud-content.com` (§12.3).
- **Which file**: photos: the largest still derivative (shared albums keep photos at most **2048 px** on the long edge,
  as JPEG); videos: the largest video derivative (shared albums keep videos at about **720p**); poster frames are
  ignored. Live Photos: imported as stills unless a motion derivative is present and recognisable (not relied on).
- **Captions**: the item caption, else nothing. **Dates**: `dateCreated` (a UTC instant).
- **Change detection**: `streamCtag`; the item list is fetched in full when it changed or every 6 hours.
- **Failure signs**: Public Website off, the album deleted, or the token revoked answer 404 or an error body →
  `link_not_found` (with the daily probe: switching Public Website back on with the same link recovers). Answers
  that don't parse, a missing `photos` list, or derivatives without usable sizes → `format_changed` (never treated as
  "the album is empty"). 429/503 → `rate_limited_upstream`.
- **Other iCloud links**: iCloud Links for photos (`https://www.icloud.com/photos/#…`, "Copy iCloud Link", expiring
  after 30 days) are a different, snapshot-style service and are rejected with `unsupported_link` and a hint. Apple
  has been changing how shared albums are shared (invitations by link besides Public Website); whether such links
  use the same endpoints is **not known** and must be checked before release (Open question 5).
- **Limits**: Apple caps a shared album at 5 000 photos and videos, so `max_photo_source_items` is rarely reached.
- The UI and the docs mark iCloud as **best effort** (`PhotoSource.best_effort: true`): "Apple offers no official way
  to do this. It may stop working without warning; we'll tell you if it does." The operator can turn it off.

### 6.6 Which file is downloaded

| Provider | Photos | Videos | Live photos |
|---|---|---|---|
| Immich (API key, or link with downloads) | **The original**, when kinframe can read it (JPEG, PNG, WebP, HEIC/HEIF, AVIF, GIF) and it is within `max_upload_bytes`. Otherwise (RAW/DNG, TIFF, PSD, too big) Immich's `fullsize` rendering if the server has one, else its `preview`. | The original when within `max_upload_bytes`, else Immich's playback encoding. Longer than `max_video_seconds`: skipped (`too_long`), never trimmed silently. | Live item: the still's original + the motion clip (≤ 10 s, cut as for sends), muted. |
| Immich link without downloads | `preview` (warning `downloads_disabled`) | playback encoding | still only |
| iCloud | the largest derivative (≤ 2048 px JPEG) | the largest video derivative (~720p) | still only (§6.5) |
| Google Photos (§7) | `baseUrl=d` (the original with metadata, without location) when readable; for RAW and other unreadable types `baseUrl=w2560-h2560` (a rendered JPEG) | `baseUrl=dv` | still only |

Why the original (Immich): the server makes its 2560 px master and frame renditions from it anyway, keeps the real
EXIF capture time and orientation, and strips metadata itself (product spec §8 decision 2), so nothing of the
original is kept. Immich's preview is 1440 px by default: smaller than the master and too small for 1440p frames.
RAW is not decodable by the pipeline; Immich's rendering is the photographer's intended look anyway.

## 7. Google Photos import

### 7.1 Why an import, not a sync

Since 31 March 2025 the Google Photos Library API only lets an app read what **that app uploaded**; reading a user's
library or albums (including shared albums) is no longer possible. The only official way to get existing photos is
the **Google Photos Picker API**: the person picks photos in Google's own UI, and the app may download exactly those,
for a short time. So Google is a one-time **import** ("Add from Google Photos"), never a sync, and Google shared-album
links are out of scope.

### 7.2 Flow

```
client                      kinframe server                         Google
  │ POST /v1/google-imports {album_id|feed_id, return_uri}
  │ ◀── 201 {status: needs_auth, auth_url}
  │ open auth_url in a browser session ───────────────────────────▶ accounts.google.com consent
  │                                                                   (scope photospicker.mediaitems.readonly,
  │                                                                    PKCE, state, access_type=online)
  │                          GET /v1/oauth/google/callback?code&state ◀─ redirect
  │                          exchange code → access token (≈1 h)  ──▶ oauth2.googleapis.com/token
  │                          create picker session  ─────────────────▶ POST photospicker…/v1/sessions
  │                          ◀── {id, pickerUri, pollingConfig, expireTime}
  │ ◀── 302 return_uri?import_id=…&status=picking   (browser session ends, app/web regains focus)
  │ GET /v1/google-imports/{id} → {status: picking, picker_uri}
  │ open picker_uri (Google Photos app or web) ──────────────────────▶ person picks, taps Done
  │                          poll GET sessions/{id} every pollInterval ▶ … mediaItemsSet: true
  │ ◀── ws google_import.updated {status: importing}
  │                          list GET mediaItems?sessionId=… (pages) ─▶
  │                          download baseUrl=d / =dv with Bearer ────▶ (within 60 min of listing)
  │                          dedupe, create media, one send (+ posts)
  │ ◀── ws google_import.updated {progress …}
  │                          DELETE sessions/{id}; forget the token
  │ ◀── ws google_import.updated {status: done}  (push google_import_done if no websocket)
```

- **Start** (`startGoogleImport`): validates the target and permission (§4.4), `return_uri` (§7.3) and the feature
  (`503 feature_disabled` without a configured client). Rate limit: 20 starts per hour per user.
- **Consent**: the server builds the authorization URL: its client id, redirect
  `<public URL>/v1/oauth/google/callback`, scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`,
  `access_type=online` (no refresh token), PKCE (S256), and `state` = a random single-use value bound to the import
  (stored hashed, valid 15 minutes). `include_granted_scopes` is not used: kinframe asks for nothing else.
- **Callback** (`googleOAuthCallback`, no kinframe sign-in: the browser session carries only `state`): checks
  `state`, exchanges the code (with the PKCE verifier), seals the access token on the import row, creates the picker
  session with `pickingConfig.maxItemCount` = `limits.max_google_import_items`, stores `pickerUri`, the polling
  interval and `expireTime`, and redirects to `return_uri` with `import_id` and `status`. On `error=access_denied`
  the import becomes `failed (consent_denied)`; any other failure `failed (auth_failed)`; the redirect carries the
  status either way. Unknown or reused `state`: a small HTML page "This link has expired. Go back to kinframe and try
  again." (never a redirect to an unvalidated URI).
- **Picking**: the client opens `picker_uri` (§7.3). The server polls the session at Google's `pollInterval` until
  `mediaItemsSet`, or until `timeoutIn`/`expireTime` → `expired`. Clients poll `getGoogleImport` or listen to the
  websocket; they never talk to Google's API themselves.
- **Protocol (checked against Google's Picker API reference, 2026-11)**: `POST https://photospicker.googleapis.com/v1/sessions`
  with `pickingConfig.maxItemCount` as an int64 **string** (Google caps it at 2000); `pollingConfig` (`pollInterval`,
  `timeoutIn`, Duration strings like `"3.5s"`) is only present while `mediaItemsSet` is false; `GET` of an expired
  session answers `PERMISSION_DENIED`, an unknown one `NOT_FOUND`; `mediaItems.list` before Done answers
  `FAILED_PRECONDITION`, `pageSize` is at most 100; downloads need the bearer token; `=dv` can't be combined with
  `w`/`h`; deleting the session once the bytes are fetched is Google's recommended practice; the picker URI stops
  working after Done (a new pick needs a new session). Google documents no fixed base URL host (examples use
  `lh3.googleusercontent.com`), so downloads are pinned to `googleusercontent.com` and Google's API domains.
- **Copying**: list the session's media items (pages of 100), then download each `mediaFile.baseUrl` with `=d`
  (photos) or `=dv` (videos) and the bearer token. Base URLs are valid for 60 minutes; when the import takes longer the
  server lists the session again for fresh URLs. Videos whose `videoMetadata.processingStatus` is not `READY` are
  retried for 10 minutes, then `failed`. Dedupe (§8.1), then one send by the importer: album → placements in the album;
  feed → posts of at most `max_items_per_send` items (§9.2). Items carry `via: google_photos`. Photos keep Google's
  `createTime` as capture time when the file has none. The Picker API exposes no descriptions, so items have no
  caption; the person can add one afterwards.
- **Finish**: delete the picker session at Google, delete the sealed token, record counts. One push
  `google_import_done` to the importer if they have no open websocket.
- **Cancel** (`cancelGoogleImport`): in `needs_auth`/`picking` the session is deleted; in `importing` the server stops
  after the current file. Items already added stay (the client offers Undo = unsend of the import's send).

### 7.3 Clients: opening Google and coming back

Google refuses sign-in inside embedded web views, so consent always runs in a real browser session; the picker URI
works in a browser or in the Google Photos app.

| Client | Consent (`auth_url`) | Back from consent (`return_uri`) | Picker (`picker_uri`) | Back from the picker |
|---|---|---|---|---|
| Web | A popup window (fallback: same tab) | `<web>/google-import/return` (an SPA page that tells the opener via `postMessage`, then closes or navigates back) | The same popup navigates to `picker_uri + "/autoclose"` (Google closes the window after Done) | The popup closes; the page follows `google_import.updated` |
| Android | Custom Tab | `app.kinframe:/google-import` (the app's existing custom scheme, like the OIDC redirect `app.kinframe:/callback`), or the app link `https://<server>/google-import/return` | `ACTION_VIEW` on `picker_uri`: the Google Photos app handles it when installed, otherwise a Custom Tab | The person taps Done and returns (back button, or Photos returns to the caller); the app resumes and follows the import |
| iOS | `ASWebAuthenticationSession` with callback scheme `app.kinframe` | `app.kinframe:/google-import` (ends the session) | `UIApplication.open(picker_uri)` (the Google Photos app handles it when installed via universal link) or `SFSafariViewController` | The app resumes (scene foreground) and refreshes the import |

- `return_uri` must be one of: `app.kinframe:/google-import`, `https://<public URL>/google-import/return` or
  `<web>/google-import/return`; anything else is `422 validation_failed` (`return_uri`).
- The query added to `return_uri` carries only `import_id` and `status`, never a token.
- When the user's previous import left a token with more than 10 minutes to live, `startGoogleImport` skips consent
  and answers `status: picking` with `picker_uri` directly.
- If the app was killed while the picker was open, `GET /v1/google-imports` (the user's recent imports) lets it pick
  up where it was.

### 7.4 Tokens: none kept

kinframe does **not** store long-lived Google tokens. It asks for `access_type=online` (no refresh token); the access
token (about 1 hour) is sealed on the import row and deleted when the import fails, expires or is cancelled; a
finished (`done`) import keeps it only until it expires, so the next import within the hour can skip consent (§7.3);
maintenance deletes every token at the latest after 2 hours.

Why: the only thing a token is good for is creating more picker sessions, and each import starts with the person in
front of the screen anyway. A refresh token would be a long-lived Google credential on a self-hosted server, worth
stealing and in scope for Google's data-handling review, for a saving of one tap: Google skips the consent screen for
a scope the person already granted and shows only the account chooser. Reusing a fresh token for a second import in
the same hour removes even that tap for "add more". (Open question 7 reconsiders this if people find it tedious.)

### 7.5 Operator setup

Google Photos import is off until the operator provides an OAuth client:

1. A Google Cloud project with the **Google Photos Picker API** enabled.
2. An **OAuth consent screen** (external): app name, support e-mail, the server's home page and privacy policy, the
   authorized domain of `KINFRAME_PUBLIC_URL`, and the scope `…/auth/photospicker.mediaitems.readonly`.
3. An **OAuth client** of type *Web application* with the redirect URI `<KINFRAME_PUBLIC_URL>/v1/oauth/google/callback`.
4. `KINFRAME_GOOGLE_CLIENT_ID` and `KINFRAME_GOOGLE_CLIENT_SECRET` (or `…_FILE`). Discovery then says
   `google_photos_import: true`.

While the consent screen is in *Testing*, only up to 100 listed test users can sign in. A server open to the public
needs the app **published and verified** by Google for this scope (Google classifies Photos scopes as sensitive at the
time of writing; verification asks for the privacy policy, a description of the use and possibly a demo video). A
family server can stay in Testing and list its members. `docs/deploy.md` should get these steps when this is built.

## 8. Dedupe, deletions and edits

### 8.1 Dedupe

In this order, per upstream asset:

1. **(source, upstream id)**: a source never imports the same upstream asset twice. Re-listing, retries, crashes,
   and a link replaced by a new link to the same album (same ids) all converge.
2. **Content hash within the target**: after download, the sha256 of the file is compared with the items already in
   the target: for an album, live placements in that album **and** on its frame (direct sends, shares, other
   sources); for a feed, live post items of that feed. A match marks the asset `duplicate` with that item: nothing is
   created. Album target: if the existing placement is on the frame but not in the album, it is added to the album
   (and only removed from the album again by a mirrored deletion, never from the frame).
3. **Media reuse**: a file whose sha256 matches media the owner already has (the existing `media_dedupe_idx`) reuses
   that media (no second master, no reprocessing), but becomes a new item.

Limits of exact hashes, accepted for now:

- iCloud and Google often hand out re-encoded files, Immich the original: the "same" photo from two services or from
  a phone send usually differs byte for byte. Perceptual duplicate detection (product spec §4.3, not built yet) would
  catch these; it plugs in at step 2.
- Phones send prepared masters (README §5.3), so `media.source_sha256` of a direct send is the master's hash, not the
  original's. Proposal: store the client-declared original sha256 of prepared uploads (`media.original_sha256`) and
  compare with it too; the family apps already compute it for `Kinframe-Existing-Item`.
- Two Immich sources on the same Immich server share the upstream SHA-1 checksum; it is compared too (cheap, before
  download).

### 8.2 One upstream asset, several targets

Each source is independent: the same Immich album synced into Oma's album and into the family feed imports twice
(as two items, sharing media through step 3). A frame that gets the photo both ways holds one placement (feeds.md
§6.2).

### 8.3 Upstream deletions

`on_upstream_delete`:

| Value | Album target (default **`mirror`**) | Feed target (default **`keep`**) |
|---|---|---|
| `mirror` | The item's placement is removed from the frame (like an unsend of that item); a `duplicate` row only takes the photo out of the album. | The item is removed from its post (like `removeFeedPostItem`) and thus from every subscribed frame; the post disappears with its last item. |
| `keep` | The photo stays on the frame and in the album (`gone_upstream`). | The photo stays in the feed. |

Why these defaults: a synced album **is** the outside album: people curate in Immich or iCloud and expect the frame
to follow, including "that photo of me was awful, delete it". A feed is a channel with history: a post Oma reacted to
last month should not vanish because someone tidied an album; and feed posts were seen as "new" when they arrived, so
taking them back is surprising. Both are a per-source switch.

Safety rules:

- **Errors are never deletions.** Only a successful, complete listing can remove anything. A listing that fails,
  parses partially, or comes back empty while the change hint says the album has items counts as `format_changed`.
- **Mass-removal guard**: when one run would mirror-remove more than 50 % of the source's live items and more than 20,
  nothing is removed; the source goes `needs_attention (mass_removal)` with `pending_removals = N`: "342 photos
  disappeared from the Immich album. Remove them here too?" — `POST …/sync {confirm_removals: true}` applies them,
  switching to `keep` or replacing a wrong link keeps them.
- Mirror-removal never touches a placement that another path also holds (a direct send, another feed, another
  source): only the source's own hold goes (feeds.md §6.2 rules).
- A photo that comes back upstream after a mirrored removal is imported again (as new); one that someone removed on
  kinframe (`removed_locally`) never is.

### 8.4 Edits

| Upstream change | Effect |
|---|---|
| Caption / description | Becomes the item's caption (first 280 characters), unless a kinframe user edited the caption (`caption_edited`): then kinframe's caption wins and later upstream changes are ignored for that item. Frames don't show "edited" for upstream changes. |
| Capture date / time zone | Updates the item's capture time (`items.captured_override`). |
| The file itself (checksum changed: re-edited, rotated, replaced) | The item's media is replaced in place: new master and renditions, same item, same placements (reactions and seen state stay); frames re-download (content-addressed). |
| Moved between albums | A removal from one source and an addition to another. |
| Favourite, archive, people, location | Ignored. |

### 8.5 Capture time and time zones

kinframe stores instants (UTC). Per provider:

- **Immich**: `exifInfo.dateTimeOriginal` with its `timeZone` when the zone is known (an exact instant); otherwise
  `localDateTime` as UTC wall-clock time, the rule the media pipeline already uses for EXIF without an offset
  (server NOTES), so "taken 14:05" shows 14:05 everywhere. Without metadata (`metadata_hidden`): `fileCreatedAt`.
- **iCloud**: `dateCreated` (an instant).
- **Google**: the file's EXIF (as for any upload), else `createTime`.
- "On this day" and "order: taken" use these values; a photo taken at 23:30 in New York may show on the next day's
  "On this day" in Europe. Accepted (product-wide behaviour today).

## 9. Targets

### 9.1 Album target

- Today an album belongs to **one frame** (`albums.frame_id`, feeds.md open question 1). An album source therefore
  fills one frame's album; the photos are placements on that frame and members of the album. To show an outside
  album on several frames, sync it into a **feed** (or add one source per frame's album). If albums become shared
  across frames later, album sources follow without contract changes (the target is the album).
- By default synced photos, like any album photo, also play in the frame's normal slideshow ("all"); album mode
  (`slideshow.album_id`) plays only the album. Any album (synced or not) has **Only in album mode**
  (`Album.only_in_album_mode`, default off): its photos then play only when the frame plays that album (album mode or
  the album's yearly schedule). A placement leaves the normal slideshow when it is in at least one such album and in
  no album without the flag, so a photo that is also in an everyday album keeps playing. If the normal slideshow would
  be empty (every photo is album-only), the frame plays everything rather than show nothing. The add-source wizard
  suggests turning it on when the first import would add more than 200 photos to an album target. Old frame apps
  ignore the flag and show everything, as before. Album managers change it; it is part of `album.upsert`.
- New photos are appended to the album in capture order; the album's manual order is otherwise left alone.
- Deleting the album: `deleteAlbum` gets `remove_source_items` (default `false`): the client asks "Also remove the 220
  photos synced from Immich?". The album's sources are deleted with it either way.

### 9.2 Feed target: batching

- **Debounce** (decision after review): new upstream photos of a feed source are downloaded as they are found but
  held back until the outside album has had no new photos for **30 minutes**, or **3 hours** after the first held
  photo, whichever comes first; then they become **one send** by the owner, posted to the feed (the next run is
  scheduled for that moment). A family adding photos over an evening gets one post, not one per run. The first
  import (history) is posted at once. Because a post is one send's items (feeds-data-model), more than
  `limits.max_items_per_send` (200) photos make several sends of at most 200 items, each one post, in capture order
  (oldest post first, so the feed reads in order). Album targets are not debounced: each run adds its photos.
- Within a post, items are in capture order.
- The run's byte budget (§6.2) naturally spreads a huge addition over several runs, a few minutes apart.
- A Google import into a feed makes one send (and post) per 200 picked items, likewise.
- Posts carry `via` (`FeedPost.via`); the feed's post list shows "Anna · 14 photos via Immich".
- Unsend: the owner or a feed admin may remove a source post or item as any post (`removeFeedPost`,
  `removeFeedPostItem`); its source items become `removed_locally` and are never re-posted.

### 9.3 Quiet first import

The first run's photos (the `history` import) are **history**: their placements carry `backfill: true` (no "N new"
card, no screen wake) with `received_at` = the upstream "added" time when the provider has one (iCloud
`batchDateCreated`), else the capture time, so "order: received" interleaves them sensibly. The same rule as feed
history: frames whose heartbeat doesn't list `feed_backfill` get them only once they do (feeds.md §11 decision 5).
Photos added upstream later are new, like a send. Google imports are never quiet: the person just picked them.

### 9.4 Moderation

A frame's moderation applies to source photos exactly as to sends and feed posts: with moderation on, a placement
starts `pending_moderation` unless the sender (the source owner) is owner or admin of that frame. Rejecting it marks
the source item `removed_locally`.

### 9.5 Credit on frames and in the apps

| Path | Today's frames and apps | With `via` |
|---|---|---|
| Album / direct | "Anna" | "Anna via Immich" |
| Feed | "Anna · Peeters kids" | "Anna · Peeters kids via Immich" |
| Shared on (frame library) | "Anna · shared by Ben" | "Anna via Immich · shared by Ben" |

- `Item.via` (`ItemVia`: `service` = `immich`, `icloud` or `google_photos`; `label` = "Immich", "iCloud", "Google
  Photos", not translated; `source_id` while the source exists) is optional; frames and clients that don't know it
  ignore it (README §7). The frame composes "%1$s via %2$s" from localized strings.
- The service name is shown, never the outside album's name or host (they may be private).
- `Send.via` and `FeedPost.via` carry the same for history and feed lists.

## 10. Notifications, receipts and reactions

All over the user websocket and, without an open websocket, as push, localized, IDs only (README §6.3).

| Event | Who | Websocket | Push key |
|---|---|---|---|
| A source's status, counts or run changed | Its managers (§4.1/4.2) and the owner | `photo_source.updated` | — |
| A source was deleted (also with its album or feed) | The same | `photo_source.removed` | — |
| A source needs attention, or has been in `error` for 3 days | **The owner only** | (in `photo_source.updated`) | `photo_source_attention` |
| Google import progress / finished | The importer | `google_import.updated` | `google_import_done` (finished, only without an open websocket) |
| Reaction or quick reply on a source photo | The owner (the sender), unless `notify_reactions` is off for that source | `reaction` / `reply` (unchanged, with `feed_id`/`post_id` for feeds) | `reaction` / `reply` |

Rules that keep it calm:

- **No push for new photos, ever.** Not per photo, not per run, not a summary: for album targets the frame is the
  audience; for feeds this follows feeds.md (posts are not pushed to members).
- Source sends get **no `delivered` push** (it would fire once per run and frame); `send.updated` and the receipts
  still work.
- `photo_source_attention` at most once per source and error code per 7 days; recovery sends nothing. Transient
  errors push only after 3 days in `error` ("We haven't been able to reach photos.peeters.be since Tuesday").
- Reactions are human and stay on by default; `notify_reactions: false` silences them for one busy source (they still
  show in history and receipts). It is the owner's own switch: only the owner changes it.
- Receipts: source sends appear in the owner's sent history (`Send.via`), with per-frame delivery and seen state;
  feed posts have the usual receipts for the owner and feed admins (feeds.md §7).

## 11. Limits and configuration

Operator limits (`Limits`, `PUT /v1/operator/limits`, environment defaults):

| Limit | Default | Env | Meaning |
|---|---|---|---|
| `max_photo_sources_per_target` | 5 | `KINFRAME_MAX_PHOTO_SOURCES_PER_TARGET` | Sources per album or feed (`409 limit_reached`) |
| `max_photo_sources_per_user` | 20 | `KINFRAME_MAX_PHOTO_SOURCES_PER_USER` | Sources one account owns |
| `max_photo_source_items` | 10 000 | `KINFRAME_MAX_PHOTO_SOURCE_ITEMS` | Live items per source; beyond it new upstream assets are `skipped (over_limit)` and the source shows the warning `item_limit_reached` |
| `max_photo_source_run_bytes` | 2 GiB | `KINFRAME_MAX_PHOTO_SOURCE_RUN_MB` | Downloaded per run; the rest follows in the next runs |
| `max_google_import_items` | 500 | `KINFRAME_MAX_GOOGLE_IMPORT_ITEMS` | Items per Google import (the picker's own maximum is 2 000) |

Existing limits apply per item: `max_upload_bytes` per file, `max_video_seconds` per video, `max_items_per_send` per
source send and post.

Server configuration:

| Env | Default | Meaning |
|---|---|---|
| `KINFRAME_PHOTO_SOURCES` | `on` | Immich and iCloud sources (discovery `photo_sources`) |
| `KINFRAME_ICLOUD_SOURCES` | `on` | iCloud links (discovery `photo_source_providers` lists `icloud_link`) |
| `KINFRAME_PHOTO_SOURCE_SYNC_MINUTES` | 30 (min 10) | Interval; iCloud at least 60 |
| `KINFRAME_SOURCES_PRIVATE_NETWORKS` | `auto` | §12.3 |
| `KINFRAME_SOURCES_ALLOWED_HOSTS` | empty | Hosts or CIDRs always allowed (e.g. `immich.lan,192.168.1.20`) |
| `KINFRAME_SOURCES_CA_FILE` | empty | Extra trusted CA certificates (PEM) for a LAN Immich with a private CA |
| `KINFRAME_GOOGLE_CLIENT_ID`, `KINFRAME_GOOGLE_CLIENT_SECRET` | empty | §7.5 (discovery `google_photos_import`) |

Rate limits: test and Immich album listing 30 per hour per user (together); sync now §6.1; Google import starts 20
per hour per user; creating sources 30 per hour per user.

## 12. Security and privacy

### 12.1 What the server stores

| Data | Stored | Returned by the API |
|---|---|---|
| Link URL (Immich, iCloud) | Sealed (it is the secret) | `url_hint` (scheme, host, `…`), managers only |
| Link password | Sealed | `has_password` |
| Immich server URL | Sealed | `server_url` without path secrets, managers only |
| Immich API key | Sealed | `has_api_key` |
| Upstream album id, name | Plain | `name`, `immich_album_id` |
| Per asset: upstream id, checksum, caption and capture time as last seen, state | Plain (needed for dedupe, edits and deletions) | counts only |
| Downloaded files | Only until the master exists (product spec §8 decision 2); never the original | — |
| Google access token | Sealed on the import, deleted at the end (≤ 2 h) | never |
| Google refresh token | **Never** (§7.4) | — |

- **Sealing** is the calendar mechanism: AES-256-GCM via `internal/secrets`, with its own key derived by HKDF-SHA256
  from `KINFRAME_SECRETS_KEY` (or the signing key) and a separate info string (`kinframe photo source secrets v1`).
  Rotating the key makes them unreadable: sources go `needs_attention (credentials_unreadable)` until re-entered.
- Write-only fields (`api_key`, `password`; the link itself, which is never returned whole) are accepted on create and
  update and never echoed; `password: null` removes a password.
- Logs never contain links, keys, tokens or signed download URLs (hosts and asset ids only).
- GPS is stripped as for every upload; place names follow the **owner's** *share location names* setting.

### 12.2 Where photos come from, and who sees them

The server, not the phone, talks to Immich, iCloud and Google. The outside service sees the kinframe server's IP
address and a `kinframe/<version>` user agent. Photos end up wherever the target shows them: everyone who sees the
album's frame, or every frame subscribed to the feed. The connect sheet says so: "Everyone who can see Peeters kids'
frames will see the photos in this album, including new ones added later."

### 12.3 Network guard (SSRF)

User-supplied URLs (Immich) are fetched by the server, so they must not become a way into the server's own network.

**Finding**: the calendar fetcher had **no** such guard (a plain `http.Client`, `http://` and any address). Photo
sources introduce a shared guarded client (`internal/netguard`), and calendars use it too since this change.

The guard:

- resolves the host and checks **every resolved address at dial time** (so DNS rebinding can't swap in a private
  address after the check), and again on every redirect (at most 5, same rules);
- **always blocks** loopback (`127.0.0.0/8`, `::1`), unspecified, link-local (`169.254.0.0/16`, `fe80::/10`, which
  includes cloud metadata services), multicast and broadcast, and the server's own listen address;
- blocks **private networks** (RFC 1918, `fc00::/7`, CGNAT `100.64.0.0/10`, which Tailscale uses) unless they are
  allowed;
- requires `https://` for public addresses; plain `http://` is accepted only for an allowed private address (a LAN
  Immich at `http://192.168.1.10:2283`);
- verifies TLS always (no "skip verification" switch); extra CAs via `KINFRAME_SOURCES_CA_FILE`;
- caps sizes and times: metadata responses 64 MiB, a download `max_upload_bytes`, connect 10 s, metadata request
  60 s, a download 10 minutes, a run 15 minutes.

**Private networks: `KINFRAME_SOURCES_PRIVATE_NETWORKS = auto | on | off`, default `auto`.** `auto` allows them when the
server itself is a private server (the host of `KINFRAME_PUBLIC_URL` is `localhost` or resolves only to private
addresses: a kinframe on the home network), and blocks them otherwise. `on` allows them on any server (a home server
reachable from the internet with Immich on the same LAN, the main self-hosting case: set it in the compose file);
`off` never. `KINFRAME_SOURCES_ALLOWED_HOSTS` allows specific hosts or CIDRs regardless.

Why not simply "on" for self-hosters: the server cannot tell a family's home server from a public multi-family one,
and on a public one private addresses reach Postgres, Logto's admin port and other containers. `auto` is safe by
default on public servers and needs no setting on a LAN-only one; the common "home server with a public URL" case is
one explicit line, which the self-hosting docs will show. Discovery says `photo_sources_private_networks` so the
connect sheet can explain "This server can't reach addresses on your home network; use Immich's public address or ask
the server's operator".

Fixed providers are pinned: iCloud requests go only to hosts under `icloud.com` and `icloud-content.com`, Google
downloads only to `*.googleusercontent.com` and Google's API hosts; a response pointing elsewhere is
`format_changed`. They use the same guard otherwise.

### 12.4 iCloud disclaimer

iCloud shared albums are reached through endpoints Apple's web page uses, not a published API. They may change,
throttle or stop working without notice, and Apple's terms do not promise them to anyone. kinframe uses only what a
browser opening the public link would fetch, at most once an hour per album, and stops and tells the owner when the
answers stop making sense. The operator can turn the provider off.

## 13. Error codes

`PhotoSource.last_error_code` (and `PhotoSourceTestResult.error_code`, `ImmichAlbumList.error_code`). The three rows
marked "warning" are not error codes but `PhotoSource.warnings` (`PhotoSourceWarning`): they never stop syncing.
Clients show the text in their own language; `last_error` carries a short English detail for support.

| Code | Status it leads to | Shown to the user |
|---|---|---|
| `unreachable` | `error` | "Can't reach photos.peeters.be. Is the server on? We keep trying." |
| `tls_error` | `needs_attention` | "photos.peeters.be's certificate isn't trusted. Check its HTTPS setup." |
| `private_address_blocked` | `needs_attention` (on create: `422`) | "This server isn't allowed to reach addresses on a private network." (+ the discovery hint) |
| `auth_failed` | `needs_attention` (daily probe) | "Immich refused the API key (or link password). Enter it again." |
| `password_required` | `needs_attention` (daily probe) | "The link now needs a password." |
| `permission_missing` | `needs_attention` | "The API key is missing the permission *asset.download*." |
| `link_not_found` | `needs_attention` (daily probe) | "The link no longer works. It may have been deleted, or Public Website was turned off." |
| `link_expired` | `needs_attention` | "The Immich link expired on 3 March. Create a new one and paste it here." |
| `album_not_found` | `needs_attention` (daily probe) | "The album no longer exists in Immich." |
| `unsupported_link` | (on create/test only) | "This is an iCloud Link for a few photos, not a shared album. Share an album with Public Website on." |
| `downloads_disabled` | warning (stays `active`) | "Photos come in reduced quality because the link doesn't allow downloads." |
| `metadata_hidden` | warning | "The link hides photo details, so dates and descriptions are missing." |
| `item_limit_reached` | warning | "Only the newest 10 000 photos are synced." |
| `too_large` | `needs_attention` | "This album is too big to read in one go. Use a smaller album." |
| `format_changed` | `needs_attention` (iCloud: daily probe) | "iCloud answered in a way we don't understand. This can happen because Apple offers no official way to do this. We'll keep checking once a day." |
| `rate_limited_upstream` | `error` | "The service asked us to slow down. We'll try again later." |
| `upstream_error` | `error` | "photos.peeters.be had a problem (error 502). We keep trying." |
| `owner_lost_access` | `needs_attention` | "Anna can no longer manage this album, so syncing stopped. Take it over or stop syncing." |
| `credentials_unreadable` | `needs_attention` | "The stored key can't be read any more (the server's secret key changed). Enter it again." |
| `mass_removal` | `needs_attention` | "342 photos disappeared from the album. Remove them here too?" |

`GoogleImport.error_code`: `consent_denied` ("You didn't allow access"), `auth_failed`, `picker_expired`,
`google_unavailable`, `target_gone` (album or feed deleted meanwhile), `download_failed` (every item failed),
`server_error`. Per-item failures are counts, not import errors.

API problem codes (new): `source_test_failed` (`422`, create or connection change could not read the album; `detail`
holds the `PhotoSourceErrorCode`), `duplicate_source` (`409`, the same outside album already feeds this target;
`detail` holds the existing source id). Existing ones used: `feature_disabled`, `limit_reached`, `rate_limited`,
`validation_failed`, `missing_permission`, `not_found`, `invalid_transition`.

## 14. Edge cases

| Case | Behaviour |
|---|---|
| Outside album deleted | `album_not_found`/`link_not_found` → `needs_attention`; nothing is removed (errors are not deletions). The owner decides: new link, or delete the source keeping or removing its photos. |
| kinframe album deleted | Its sources are deleted; `remove_source_items` decides about the photos (§9.1). |
| Feed deleted | Its sources are deleted with it; the posts go with the feed anyway. |
| Frame removed | Its albums go, their sources too; nothing else to remove. |
| Source owner leaves the frame/feed, loses *manage library*, is demoted, deletes the account | `owner_lost_access`, take over or delete (§4.3). Photos keep their sender (a deleted account's photos keep its name, as today). |
| Owner deletes their account with "remove my items" | Their items go everywhere as today, including source photos; the sources are deleted. |
| Huge outside album (40 000 items) | `history` and `max_photo_source_items` cap it (newest first); the first import is spread over runs by the byte budget ("importing 1 200 of 10 000"); responses over 64 MiB → paging or `too_large`. |
| Immich down for days | `error` with backoff up to 6 h; one push after 3 days; photos already imported keep showing; it catches up when back. |
| Link rotated (new Immich link to the same album, iCloud Public Website off and on) | Paste the new link (`PATCH url`). Same upstream ids → nothing is re-imported. If the ids differ, the old ones look gone and the new ones new: the mass-removal guard asks before removing. |
| Link password changed | `auth_failed`; enter the new password. |
| HEIC | Downloaded as is (Immich original, Google `=d`), decoded by the pipeline. |
| RAW / DNG / TIFF / PSD | Immich: its `fullsize`/`preview` rendering; Google: `=w2560-h2560`; else `skipped (unsupported)`. |
| Video longer than `max_video_seconds` | `skipped (too_long)`, counted ("3 videos longer than 5 minutes were skipped"). |
| Video too big for `max_upload_bytes` | Immich playback encoding, else `skipped (too_large)`. |
| *include videos* turned off later | Already imported videos stay; new ones are skipped. Turning it on imports the skipped ones in the next run. |
| Live photo | Immich: live item; iCloud, Google: still. |
| Same photo in two sources of one target | Second is `duplicate` if bytes match (§8.1); otherwise both show until perceptual dedupe exists. |
| Same photo sent directly and synced | `duplicate` when the hashes match (originals, or with `original_sha256`); else both. |
| Someone purges a synced photo on the frame | `removed_locally`, never re-imported from this source, whatever happens upstream. |
| Someone edits a synced photo's caption on kinframe | Local caption wins from then on (§8.4). |
| Frame moderation rejects a source photo | `removed_locally`. |
| Clock and time zones of capture dates | §8.5. |
| Source paused for months | Nothing happens; resume runs a normal comparison (new photos are new, not history). |
| Pausing vs the frame | Pause stops syncing only; the photos stay on the frame (unlike pausing a feed subscription). |
| Two people add the same Immich album to the same target | The second gets `409 duplicate_source`. |
| Upstream asset changes type (photo replaced by a video with the same id) | Treated as a file change: media replaced (§8.4). |
| Google import while the target is deleted | `failed (target_gone)`; items not yet placed are discarded. |
| Google picker left open, app killed | The import stays `picking` until Google's session expires, then `expired`; `GET /v1/google-imports` finds it again. |
| Google Photos videos still processing | Retried 10 minutes, then counted as failed. |
| Picking more than `max_google_import_items` | Google's picker enforces the maximum itself (`maxItemCount`). |
| Server restarts mid-run / mid-import | Runs restart from `pending` rows; imports resume (re-listing for fresh base URLs) while the token is valid, else `failed (auth_failed)` with already copied items kept. |
| Secrets key rotated | `credentials_unreadable`. |

## 15. Data model

Migration `00015_photo_sources.sql` (as built it adds a few columns to this proposal, listed in server/NOTES.md
"Photo sources": `server_url`, `upstream_count`, `hold_since`/`last_new_at` for the feed debounce,
`replacement_media_id`, `upstream_added_at`, `retry_at`, `google_imports.target_kind`/`auth_url_enc`/`done_ids`,
`feed_posts.quiet`, `albums.only_in_album_mode`):

```sql
CREATE TABLE photo_sources (
    id                  uuid PRIMARY KEY,
    provider            text        NOT NULL CHECK (provider IN ('immich_link', 'immich_api', 'icloud_link')),
    owner_user_id       uuid REFERENCES users ON DELETE SET NULL,   -- null: owner gone (owner_lost_access)
    frame_id            uuid REFERENCES frames ON DELETE CASCADE,  -- album targets: the album's frame
    album_id            uuid REFERENCES albums ON DELETE CASCADE,
    feed_id             uuid REFERENCES feeds  ON DELETE CASCADE,
    name                text        NOT NULL,                       -- the outside album's name
    url_enc             bytea,                                      -- link, or Immich server URL (sealed)
    url_hint            text,
    api_key_enc         bytea,
    password_enc        bytea,
    upstream_album_id   text,                                       -- Immich album id; iCloud: null
    upstream_key        text        NOT NULL,                       -- sha256(provider|host|album id or link key): duplicate check
    upstream_host       text        NOT NULL,                       -- for per-host concurrency and hints
    resolved_host       text,                                       -- iCloud partition host learned from a redirect
    history             text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count       integer CHECK (history_count BETWEEN 1 AND 10000),
    on_upstream_delete  text        NOT NULL CHECK (on_upstream_delete IN ('mirror', 'keep')),
    include_videos      boolean     NOT NULL DEFAULT true,
    notify_reactions    boolean     NOT NULL DEFAULT true,
    status              text        NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'paused', 'error', 'needs_attention')),
    warnings            text[]      NOT NULL DEFAULT '{}',
    error_code          text,
    error_detail        text,
    failures            integer     NOT NULL DEFAULT 0,
    error_since         timestamptz,
    attention_pushed    jsonb       NOT NULL DEFAULT '{}',          -- error code -> last push time (7-day rule)
    pending_removals    integer     NOT NULL DEFAULT 0,
    change_hint         text,                                       -- Immich updatedAt+assetCount / iCloud streamCtag
    full_listing_at     timestamptz,
    initial_done        boolean     NOT NULL DEFAULT false,
    run_started_at      timestamptz,                                -- non-null while running
    last_run            jsonb,                                      -- {started_at, finished_at, added, updated, removed, skipped, failed, bytes}
    last_synced_at      timestamptz,
    last_attempt_at     timestamptz,
    next_sync_at        timestamptz NOT NULL DEFAULT now(),
    last_manual_sync_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((album_id IS NOT NULL AND frame_id IS NOT NULL AND feed_id IS NULL)
        OR (feed_id IS NOT NULL AND album_id IS NULL AND frame_id IS NULL)),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX photo_sources_upstream_unique ON photo_sources (COALESCE(album_id, feed_id), upstream_key);
CREATE INDEX photo_sources_album_idx ON photo_sources (album_id) WHERE album_id IS NOT NULL;
CREATE INDEX photo_sources_feed_idx ON photo_sources (feed_id) WHERE feed_id IS NOT NULL;
CREATE INDEX photo_sources_owner_idx ON photo_sources (owner_user_id);
CREATE INDEX photo_sources_due_idx ON photo_sources (next_sync_at) WHERE status <> 'paused';

CREATE TABLE photo_source_items (
    source_id          uuid        NOT NULL REFERENCES photo_sources ON DELETE CASCADE,
    upstream_id        text        NOT NULL,                        -- Immich asset id / iCloud photoGuid
    state              text        NOT NULL CHECK (state IN ('pending', 'imported', 'duplicate', 'skipped',
                                                             'failed', 'removed_locally', 'gone_upstream')),
    skip_reason        text CHECK (skip_reason IN ('video_off', 'too_long', 'too_large', 'unsupported', 'over_limit', 'history')),
    failure_code       text,
    attempts           integer     NOT NULL DEFAULT 0,
    item_id            uuid REFERENCES items ON DELETE SET NULL,
    send_id            uuid REFERENCES sends ON DELETE SET NULL,
    upstream_checksum  text,                                        -- Immich SHA-1 / iCloud derivative checksum
    content_sha256     text,
    upstream_caption   text,
    upstream_taken_at  timestamptz,
    upstream_updated   text,                                        -- provider's updatedAt, for edit detection
    kind               text CHECK (kind IN ('photo', 'video', 'live')),
    bytes              bigint,
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    imported_at        timestamptz,
    gone_at            timestamptz,
    PRIMARY KEY (source_id, upstream_id)
);
CREATE INDEX photo_source_items_item_idx ON photo_source_items (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX photo_source_items_pending_idx ON photo_source_items (source_id) WHERE state = 'pending';

CREATE TABLE google_imports (
    id                 uuid PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    frame_id           uuid REFERENCES frames ON DELETE CASCADE,
    album_id           uuid REFERENCES albums ON DELETE SET NULL,  -- null after deletion: target_gone
    feed_id            uuid REFERENCES feeds  ON DELETE SET NULL,
    status             text        NOT NULL CHECK (status IN ('needs_auth', 'picking', 'importing', 'done',
                                                              'failed', 'cancelled', 'expired')),
    return_uri         text        NOT NULL,
    state_hash         text UNIQUE,                                 -- sha256 of the OAuth state; cleared after use
    pkce_verifier_enc  bytea,
    access_token_enc   bytea,
    token_expires_at   timestamptz,
    picker_session_id  text,
    picker_uri         text,
    picker_expires_at  timestamptz,
    poll_interval_ms   integer,
    picked             integer     NOT NULL DEFAULT 0,
    imported           integer     NOT NULL DEFAULT 0,
    duplicates         integer     NOT NULL DEFAULT 0,
    skipped            integer     NOT NULL DEFAULT 0,
    failed             integer     NOT NULL DEFAULT 0,
    bytes              bigint      NOT NULL DEFAULT 0,
    send_ids           uuid[]      NOT NULL DEFAULT '{}',
    error_code         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz
);
CREATE INDEX google_imports_user_idx ON google_imports (user_id, created_at DESC);
CREATE INDEX google_imports_active_idx ON google_imports (status) WHERE status IN ('picking', 'importing');

-- Credit and the source send.
ALTER TABLE sends
    ADD COLUMN via text CHECK (via IN ('immich', 'icloud', 'google_photos')),
    ADD COLUMN photo_source_id uuid REFERENCES photo_sources ON DELETE SET NULL,
    ADD COLUMN google_import_id uuid REFERENCES google_imports ON DELETE SET NULL;
ALTER TABLE items
    ADD COLUMN via text CHECK (via IN ('immich', 'icloud', 'google_photos')),
    ADD COLUMN via_source_id uuid REFERENCES photo_sources ON DELETE SET NULL,
    ADD COLUMN captured_override timestamptz;                       -- upstream capture time, wins over EXIF
ALTER TABLE media ADD COLUMN original_sha256 text;                 -- client-declared hash of a prepared upload's original (§8.1)
CREATE INDEX media_original_sha_idx ON media (original_sha256) WHERE original_sha256 IS NOT NULL;
```

Notes:

- Secrets use `secrets.New(…, "kinframe photo source secrets v1", "kinframe photo source secrets from signing key v1")`.
- The scheduler mirrors the calendar service (tick 30 s, due index, `running` map, backoff), as a separate service
  (`internal/photosource`) with one provider interface: `Probe`, `List`, `Download`, `Login`. Downloads go through
  the worker's media path (`CreateMedia` with `SourcePath`, `JobProcessMedia`), so HEIC, video, live and prepared
  rules are the existing ones.
- Source sends: `sends.via`/`photo_source_id` set; the `delivered` push and `TouchLastSent` skip sends with
  `photo_source_id IS NOT NULL`. Feed posts are created with `createPosts`, so fanout, receipts and the
  `feed_post.updated` event are unchanged.
- `Item.captured_at` renders `COALESCE(items.captured_override, media.captured_at, items.captured_hint)`.
- Tombstones: the code paths that already turn feed deliveries into `declined` (purge, trash expiry, moderation
  reject, `removeFeedPostItem`) also set the matching `photo_source_items` row to `removed_locally`; removing a
  placement from an album does too for album sources.
- `Album.photo_source_count` is a count over `photo_sources` by `album_id`.
- The maintenance loop deletes finished Google imports after 7 days and clears tokens of imports older than 2 hours.

## 16. Release plan

Phase **+**, planned as **v1.3 Photo sources**, after v1.2 Birthdays:

1. Server: `internal/netguard` (also adopted by calendars), sources with Immich (link and API key), scheduler,
   tables, endpoints, events; web: "Sync from…" on albums and Sources on feeds, status, Sync now, pause, delete with
   the keep/remove choice.
2. Google Photos import: server (OAuth callback, picker, copying) and web ("Add from Google Photos" on albums and in
   a feed's post menu), operator docs.
3. Family apps (Android, iOS): the same screens, the Google flow with Custom Tabs / `ASWebAuthenticationSession`,
   `app.kinframe:/google-import`.
4. iCloud shared albums, marked best effort, in all clients at once (after a check of the current iCloud sharing
   options, Open question 5).
5. Frame (Android, browser frame): "via Immich" in the credit. Older frames show the photos without it.

Later (not now):

- WebDAV / Nextcloud folders as a source.
- Immich people/face-based albums ("everything with Oma in it") and smart searches.
- Two-way sync (photos sent to kinframe copied into Immich).
- Google Photos shared-album links (no API exists).
- Frame-local sources (a frame reading a folder or a share on its own network).

## 17. Decisions and open questions

Taken (2026-11-19), encoded above:

1. A source fills an album (managers of that album) or a feed (owner/admins); feed posts are attributed to the owner
   with "via"; feed posts are debounced (30 minutes quiet, at most 3 hours), split at `max_items_per_send` (§2, §4, §9).
2. Immich is the best-supported provider, shared link recommended, API key with album picking (§6.3, §6.4).
3. iCloud shared albums through the public website's endpoints, best effort, robust failure states (§6.5, §13).
4. Google Photos is an import through the Picker API, server-side OAuth, hidden when unconfigured (§7).
5. Server-side scheduled sync with backoff, Sync now, initial import, mirror/keep, dedupe, edits, limits, pause,
   delete with keep/remove, status (§5, §6, §8, §11).
6. Credentials sealed like calendar credentials, write-only; SSRF guard with an operator switch for private
   networks; TLS always; test endpoint (§12).
7. Frames need no update; the credit field is optional (§9.5).
8. Out of scope now: §16 "Later".

Made in this spec:

- **Notifications**: no pushes for new photos at all; one `photo_source_attention` push to the owner when a source
  needs them (at most weekly per error), or after 3 days of errors; reactions to the owner with a per-source
  `notify_reactions` switch; no `delivered` pushes for source sends; `google_import_done` once (§10).
- **Credit**: `ItemVia` on items, sends and posts; "Anna via Immich" / "Anna · Peeters kids via Immich", the service
  name only (§9.5).
- **Original vs preview**: Immich originals when readable, Immich's rendering for RAW and other unreadable formats,
  previews only when a link forbids downloads (§6.6).
- **Google tokens**: none kept beyond one import (`access_type=online`; a fresh token is reused within the hour) (§7.4).
- **Deletion defaults**: `mirror` for albums, `keep` for feeds, with the mass-removal guard (§8.3).
- **Private networks**: `auto` by default (allowed on LAN-only servers), `on`/`off` and an allow list (§12.3).

After design review (2026-11-19):

- **Network guard everywhere**: `internal/netguard` (§12.3) is built and calendar fetching moved behind it in the same
  change, with a release note (server/NOTES.md). Former open question 3.
- **Feed debounce** instead of one post per run (§9.2). Former open question 4 (appending to an existing post) is
  not needed.
- **Only in album mode**: the album flag `only_in_album_mode` (§9.1). Former open question 2.
- The other open questions keep the defaults of this spec.
- Provider protocols were checked against Immich's API spec and Google's Picker reference (§6.3, §7.2); iCloud has
  no documentation and is implemented from observed behaviour with hand-written fixtures.

Open questions:

1. **Albums are per frame.** "Every frame playing that album shows it" holds for one frame today. Several frames need
   a feed target (or one source per frame's album, imported twice, media shared). Multi-frame albums (feeds.md open
   question 1) would remove the gap.
2. **Synced photos play in "all".** Decided: the album flag *Only in album mode* (`only_in_album_mode`, §9.1).
3. **The SSRF guard is new for calendars too.** Decided: calendars use the guard with the same `auto` default
   (release note in server/NOTES.md).
4. **Appending to a recent post.** Decided differently: feed posts are debounced (§9.2), so no post has to grow.
5. **iCloud "share by link".** Recent Apple OS versions changed how shared albums can be shared; whether links other
   than *Public Website* use the same endpoints, and how Live Photos and edited items appear, must be checked against
   real albums before step 4 of the release plan.
6. **Taking over an API-key source** requires a new key; a link and its password carry over to the new owner. One
   could argue a link password is also personal; it was entered for the album, not the account.
7. **Google re-consent.** Without refresh tokens each import (after the first hour) shows Google's account chooser.
   If families find that tedious, an opt-in "stay connected" (an encrypted refresh token per user, revocable in
   Settings) is the alternative; it brings Google's data-handling review closer for public servers.
8. **Google verification** for public servers is the operator's job and may take weeks; the default hosted server
   needs it before the button can be offered to everyone.
9. **Exact-hash dedupe is weak across services** (§8.1). Perceptual hashing (planned in the product spec) is the
   real fix; `media.original_sha256` is a cheap step.
10. **`history: all` for feeds** posts the whole outside album as quiet history in posts of 200. For a 10 000-photo
    album that is 50 posts in the feed list; a "history" post type that the feed list collapses could be nicer.
11. **Rights of the owner vs other managers**: any album manager may pause or delete another person's source on the
    same album (as with albums today). A family may expect only the owner to stop it.
12. **Reaction notifications for a big source** go to one person; with `notify_reactions` off they are silent.
    Routing them to "the feed's admins" or "nobody but the history" are alternatives.
