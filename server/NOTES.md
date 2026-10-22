# Server implementation notes

Decisions taken where `docs/api/` was silent or ambiguous, and known gaps. Contract changes are listed separately.

## Contract changes

- None made by the server work. The coordinator added `dev_auth` to Discovery, `POST /dev/token` (tag `dev`) and the
  signed media URL description; the server implements them. `/dev/token` additionally accepts `"operator": true` to
  get the `kinframe:operate` scope (dev only, not in the contract).

## Code generation

- `docs/api/openapi.yaml` is OpenAPI 3.1. oapi-codegen cannot resolve references into schema properties
  (`#/components/schemas/Album/properties/schedule`) and handles 3.1 type arrays poorly, so `tools/specprep` rewrites
  a copy (inline deep refs, `type: [x, 'null']` → `nullable`, `oneOf [ref, null]` → nullable, labelled 3.0.3). The same
  prepared copy is embedded for request validation (kin-openapi). The contract file itself is untouched.
- `tusCreateUpload`, `putAvatar`, `getItemMedia`, `deviceGetMedia` and `devToken` are hand-written (tus, binary or
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
  `KINFRAME_MIN_FRAME_APP_VERSION_CODE`; heartbeat and state stay allowed. `X-Kinframe-App` is not parsed.
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
- Storage model: `media` holds the master and per-device renditions; `items` hold per-send metadata and thumbnails
  (thumbnails depend on the item's focal point and are regenerated when it changes).
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
- Frame imports (`/v1/device/imports`) create media without an owner; sender is the frame (`sender_id` = frame id).

## Change feed

- Stored compacted: one row per (frame, entity) holding the latest seq. Seqs are allocated under the frame row lock,
  so readers never see gaps. `since=0` is therefore a full snapshot (removals are omitted from snapshots).
- Changes are rendered from current state at read time. Placements pending moderation, failed or removed render as
  `placement.remove`. Placements enter the feed when their rendition is ready (or when changed).
- `reset` (with `next_seq: 0`) when `since` is ahead of the frame's head or below the floor raised by pruning removal
  entries older than `KINFRAME_FEED_RETENTION_DAYS`.
- Unclaimed devices get an empty feed. Acks don't produce feed entries (the frame already knows).

## Settings

- Stale `If-Match` is accepted when none of the patched fields changed since that version (field-level merge);
  otherwise `412 version_conflict`, after which the client re-applies, so the latest write wins. Missing `If-Match` →
  400. `version` and `kiosk.owner_pin_set` are read-only and ignored in patches. The merged document is validated
  against `FrameSettings`; unknown fields are preserved.

## Push

- Endpoints are stored for all kinds. Delivery is implemented for UnifiedPush (plain POST of a JSON payload with ids,
  localized title/body in en/nl). WebPush (needs RFC 8291 encryption + VAPID), FCM and APNs are not delivered yet.
  Push is only used when the user has no open websocket.

## Rate limits (in memory, per process)

Redeem 10/h per user and 30/h per IP (also used for replace-device); enroll 20/h per IP; device token 120/h per IP;
invites 60/h per user; dev tokens 120/h per IP.

## Not done yet (v0.1 follow-ups)

- Face detection for focal points (interface `worker.FocalDetector`, centre for now); perceptual duplicate detection.
- Weather change type, calendar, S3 storage.
- Garbage collection of media no longer referenced by any live placement.
- WebPush/FCM/APNs delivery.
- Media tool versions: the image is **Debian 13 (trixie)**: libvips 8.16, ffmpeg 7.1. It was moved off bookworm
  because libvips 8.14 there did **not** strip EXIF/GPS with `[Q=..,strip]`, which `TestPhotoPipeline` caught inside
  the image. The root cause in 8.14 wasn't investigated further. `make test-docker` (full suite with
  `KINFRAME_REQUIRE_MEDIA_TOOLS=1`) passes on trixie, and so does the suite on the dev laptop with Debian 13's system
  vips/ffmpeg. Production (appmire-hetz1) is Debian 13 as well.
