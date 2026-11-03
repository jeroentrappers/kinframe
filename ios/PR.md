# iOS family app: SwiftUI app, share extension, background uploads, and Universal Links on the server

## Update: up to date with main (November 2026)

`origin/main` is merged in (181 commits since the branch point; one conflict in `server/internal/config/config.go`,
where both sides added fields: kept both). The API client and the string catalog are regenerated from main, and the
app has caught up with what the Android family app and `docs/ios-handoff.md` added since:

- **Media preparation and Live Photos** (handoff "Media preparation and Live Photos"): the upload queue prepares
  every photo as `photo-master-v1` and every video as `video-master-v1` before tus, and the composer picks Live Photos
  with their clip, sent as `kind: live` or as a still only.
- **Share to another frame** (handoff addendum): from the library and from a send in History, with a dry run,
  "Already there" / "Has some", Undo and "Shared by".
- **Plugins** in the frame settings, including the split layout ("Two photos and a dashboard") and community plugins
  from the registry, and the slideshow's **Live photos** setting.

Not added: calendars, countdowns and TV links. They are in the web app, but the Android family app has none of them
yet (only API calls in `android/core`), so they're left for when the family apps get them.

![Live Photos: composer, editor, library](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/live.png)
![Share to another frame: picker with a dry run, a shared photo, a send shared to a second frame](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/share.png)
![Plugins: section, catalog, editor, in Dutch](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/plugins.png)

### What the update does

- **Preparation** runs in the upload queue (`OutboxRunner`, an actor, not the main actor) and never in the share
  extension. Photos: capture time from EXIF `DateTimeOriginal` + `OffsetTimeOriginal` (as UTC without an offset),
  one ImageIO decode-and-downscale step (long edge 2560, EXIF orientation applied, never upscaled), redrawn in 8-bit
  sRGB, written with no source properties (JPEG 0.9; WebP if ImageIO ever lists it as an encoder). Videos: an
  `AVMutableComposition` with the trim and, when muted, without audio; a video composition that bakes the rotation in
  and renders at most 1920×1080 and 30 fps; `AVAssetExportPreset1920x1080` to MP4 with network optimization,
  `metadata = []` and `.forSharing()`. The send gets `captured_at`, `width`, `height`, `duration_ms` and no `trim`
  for a prepared video. "Preparing 2 of 5 · 40%" shows in the sending card. The master replaces the original in the
  queue; both are gone once the send is accepted.
- **Originals are never uploaded.** Every file of a send is prepared, one at a time, before any upload starts. When
  preparing a file fails, it goes as it is only if it's already within the master's limits (long edge ≤ 2560 px;
  ≤ 1920×1080 and ≤ 30 fps); otherwise the send stops with "A photo or video couldn't be made ready to send, and it's
  too large to send as it is." (`media_unprepared`) and Try again / Cancel. The same goes for Live Photo clips. GIF and
  BMP are prepared too (a GIF becomes its first frame, which is all the server keeps of it).
- **Live Photos:** the picker matches `.livePhotos`; for a Live Photo the app loads `PHLivePhoto` and writes its
  `.pairedVideo` resource with `PHAssetResourceManager` (iCloud allowed), without asking for library access. The
  clip is prepared like a video, muted and cut to 10 s, uploaded on its own, and sent as `live_video_upload_id`.
  "Live" badge on the composer tile, a "Send as a still photo only" switch in the item editor, "Live" badges in
  History and the library, and a long press there plays `GET /v1/items/{id}/media/motion`, muted (the redirect to the
  signed URL is followed without our token; a streamed clip gets the token).
- **The share extension prepares too.** After Send it runs the same queue with the same preparer, one file at a time
  (ImageIO's decode-and-downscale keeps a 48 MP photo well under the extension's memory limit, and the video export
  runs out of process), shows "Preparing 2 of 3 · 40%", and closes once every file is prepared and the uploads have
  started on the background session. A file that can't be prepared and is too large shows the error in the sheet and
  the job is taken out of the queue. If the sheet goes away before preparing is done, the job stays queued and the
  app prepares what's left the next time it runs (after the 90 s lease). An upload of an unprepared original queued by
  an older version starts over with the master.
- **Share to another frame:** `POST /v1/frames/{id}/placements:share` with an `Idempotency-Key`; a dry run over all
  candidate frames (where you can send, not the source) marks "Already there" (not selectable) and "Has some". The
  toast "Shared to …" has Undo, which purges the created placements per frame. Entry points: the library's selection
  bar, the photo sheet, and a send's detail (source: the first frame that still has every item). "Shared by …" in the
  photo sheet, as a mark on library tiles, and per frame in a send's delivery.
- **Plugins:** layout (off, on the photo, beside the photo with side and background, two photos and a dashboard with
  side), the instances with status ("Waiting for the frame", errors, "Not on this frame", withdrawn from the
  registry, an update waiting) and on/off (an incomplete one opens its form instead), "Add plugin" with built-in and
  community plugins, what a community plugin may do before it's added, and the editor: every manifest setting type
  (text, number, url with what the frame found on the network, colour with a swatch, duration, choice, switch,
  secret with Set / Change / Remove, Home Assistant entities with search, time zones with search and UTC offsets),
  presets with `{placeholders}`, corner, visibility, approving or putting off a registry update, remove. The manifest
  translations are used for the app's language. Every change is saved on its own with `If-Match`; a `412` is retried
  once on the newer settings, and a `422`'s field errors show at their fields. The frame's report is refreshed every
  3 s while the editor is open.
- **Frame settings:** "Live photos": play when shown, play on tap, photo only.

### How the update was checked

- 88 unit tests, all passing (26 new): the preparation policy (sizes, bitrate, trim and the 10 s cut, EXIF times),
  a real ImageIO run on a 4000×3000 Display P3 JPEG with GPS and orientation 6 (comes out 1920×2560, sRGB, without
  EXIF date, GPS or orientation), the runner with a fake preparer (prepared still and muted clip uploaded with
  `prepared`, the send body with `live_video_upload_id`, `width`, `height`, `captured_at`; a prepared video without
  `trim` and with `duration_ms`), sharing (coverage from a dry run, targets, the source frame, "Shared by"), and the plugin settings logic
  (manifest reading, the server's checks, presets, instance JSON keeping unknown fields and sending only changed
  secrets, 422 field mapping, status, registry update diffs, the frame's report, entity filters, time zones).
- Against a local dev server built from this branch (Docker, own database) with three scripted frames: a Live Photo
  and a photo sent from the composer were uploaded prepared (the server logged `pipeline=prepared` for the still, the
  photo and the motion clip), a share between frames with the dry-run marks, "Shared by" in History and the library,
  and the Plugins section with a reported catalog (built-in and a community plugin), saving layout changes.
- Screenshots above (iPhone 17 simulator, light; the plugins section also in Dutch).
- Always preparing (6 more tests, 94 in all): the limits for going as-is, read from real files too; a file that
  can't be prepared but is within the limits goes as it is; one that is too large fails the send with
  `media_unprepared` before anything is uploaded, also when it's a Live Photo's clip; every file is prepared before
  the first upload; an unfinished upload of an original starts over with the master; the share sheet waits until
  every file (and clip) is prepared. On the dev server, the share sheet (in-app preview) sent a 4800×3600 JPEG and a
  video: the queue uploaded a 2560×1920 master and a 1440×1080 MP4, and the server logged `pipeline=prepared` for both.

### Left after the update

- **Device:** `DEVELOPMENT_TEAM` is still empty and the iPhone SE wasn't reachable, so the update ran on the
  simulator only. Live Photo picking needs a real library: the simulator has no Live Photos, so the picker path
  (`PHLivePhoto` → `.pairedVideo`) is untested; the rest of the Live path was tested with a staged still and clip.
- Video preparation is covered by the policy tests and the dev-server run with a short clip, not by a unit test of
  the export itself.
- Long-press playback of a motion clip wasn't tried in the simulator (it needs a press gesture).
- Calendars, countdowns and TV links (see above).

### Questions about the update

4. ~~Should the share extension upload originals, or leave the job for the app to prepare?~~ Decided: photos and
   videos are always prepared on the device and originals are never uploaded. The extension now prepares in the
   sheet before closing (see above), so a share still doesn't wait for the app to be opened.
5. Should Live Photos get a "Live sound" switch? The handoff mentions it; the Android app has none, so the clip is
   always sent muted for now.

## The original pull request

## What this is

The native iOS family app from `docs/ios-handoff.md`, at v0.1 parity with the web app: onboarding with server choice
and Logto sign-in, frames, sending photos and videos (also from the share sheet), history with delivery and
reactions, frame management, and settings. SwiftUI, Swift 6 strict concurrency, iOS 17+. Everything is in `ios/`;
`ios/README.md` covers building, running against a dev server, and the architecture.

The server gets a small `/.well-known/apple-app-site-association` handler for Universal Links, and CI gets an iOS job.

## Screenshots

Light and dark, iPhone 17 simulator, against a local dev server.

Onboarding: welcome, server choice, sign-in, adding a frame (with a wrong code)

![Onboarding, light](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/onboarding-light.png)
![Onboarding, dark](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/onboarding-dark.png)

Frames, the composer, framing and trim, and the share extension's composer

![Sending, light](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/send-light.png)
![Sending, dark](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/send-dark.png)

History, a send with delivery per frame, and settings

![History, light](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/history-light.png)
![History, dark](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/history-dark.png)

A frame: overview with remote control and health, photos, people, settings

![Frame, light](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/frame-light.png)
![Frame, dark](https://raw.githubusercontent.com/jeroentrappers/kinframe/ios-app/ios/docs/screenshots/frame-dark.png)

## What works

- **Onboarding:** welcome → server (the default, or "Use your own server" validated through `/.well-known/kinframe`,
  showing name and version) → sign-in → the name prompt (only for "New member" accounts) → add your first frame. The
  step is persisted. Dev servers (`dev_auth: true`) get the developer sign-in form.
- **Sign-in:** the Logto Swift SDK, Authorization Code + PKCE in `ASWebAuthenticationSession`, redirect
  `app.kinframe:/callback`, issuer, client id and API resource from discovery, scopes
  `openid profile email offline_access kinframe:operate`. Refresh tokens in the Keychain, shared with the share
  extension through a keychain group; the client is rebuilt from the Keychain before refreshing, so a rotated refresh
  token is never reused by the other process.
- **API client:** generated by `swift-openapi-generator` from `docs/api/openapi.yaml` and checked in
  (`ios/tools/generate-api.sh`; a small specprep inlines deep refs and nullable `oneOf`s). A middleware adds the
  token and `Accept-Language`, retries once after a 401, and maps problems to localized messages by `code`.
- **Frames:** list with online state and roles, empty state; add with the 8-digit code (formatted `1234 5678`, paste
  button, auto-submit when complete) with the claimed / joined / already a member / pending results and naming after
  a claim. Universal Links for `/f/{code}` and `/i/{token}` on `kinframe.appmire.be`, and `app.kinframe://` links
  for any server; a link for another server asks before switching.
- **Send:** PhotosPicker with original files (HEIC, JPEG, PNG, MOV, MP4, …, no library permission), camera capture,
  mixed photos and videos, captions (280, shared or per item, "use for all"), fill/fit with a focal point and
  landscape and portrait previews in the selected frame's shape, video trim (3 s to `max_video_seconds`) and
  "include sound", frames with online state, `max_items_per_send` and the other limits from discovery. After
  sending, the app opens the send in History.
- **Background uploads:** tus 1.0 (creation-with-upload, `PATCH` in 4 MiB chunks, `HEAD` to resume, restart when an
  upload expired, the duplicate shortcut), three files at a time, then `POST /v1/sends` with a persisted
  `Idempotency-Key`. Requests run in a background `URLSession`; each step is persisted in the App Group, and responses
  that arrive after a relaunch are applied to their job. Network failures retry when the app comes back.
- **Share extension:** "Share → kinframe" for images and videos. A compact composer (frames, caption) stages the
  files in the App Group, queues the job and starts it on its own background session, then closes; the app finishes
  it if needed. It remembers the frames last shared to.
- **History:** sends newest first by day, filters, per-frame stage (processing → on its way → delivered → seen),
  reaction and quick-reply chips, the upload queue on top, paging. Send detail with delivery per frame and item,
  caption editing and unsend with undo. Live over `GET /v1/ws?access_token=…` with back-off and reconnect on 4401.
- **Frame detail** by role and permission: overview (online, health from `/health`, details), remote commands (previous,
  next, sleep, wake, identify, sync now), library grid with signed thumbnails and hide / trash / restore (single or
  a selection, with undo), people (join requests with let in / decline, roles and permissions, remove), invite links
  through the share sheet with expiry, uses and a note, revoke; remote settings (interval, order, transition, fill,
  background, captions, clock, brightness, sleep schedules, weather location via `/v1/geocode`, temperature unit) as
  a JSON merge patch with `If-Match` and the web's conflict rebase; leave, remove (type the name) and ownership
  transfer.
- **Settings:** name, avatar (`PUT /v1/me/avatar`), language (system, English, Nederlands; also sets the account
  locale), server details and limits, another server, sign out.
- **Design:** the web tokens (colours, radii) in light and dark, serif headings, 44 pt targets, Dynamic Type styles,
  VoiceOver labels and actions (for example moving the focal point), haptics. App icon from the kinframe mark.
- **Localization:** `Localizable.xcstrings` generated from the web catalogs (same keys, placeholders and plurals) plus
  iOS-only strings, English and Dutch.
- **Push (behind a flag):** with `KINFRAME_APNS_ENABLED = YES` the app asks for permission after the first send and
  registers `POST /v1/me/push-endpoints {kind: "apns"}`. Off by default; see below.
- **Server:** `GET /.well-known/apple-app-site-association` for `<team id>.<bundle id>` with `/f/*` and `/i/*`,
  from `KINFRAME_APPLE_TEAM_ID` and `KINFRAME_APPLE_BUNDLE_ID` (default `app.kinframe.family`); not served without a
  team. Unit and e2e tests; documented in `server/README.md`.
- **CI:** an `ios` job on `macos-latest` builds and runs the unit tests on a simulator without signing.

## How it was checked

- 62 unit tests (`xcodebuild -scheme Kinframe test`): code formatting and validation, discovery parsing and server
  addresses, deep links, send limits and trim, framing maths, merge patches and rebase, delivery stages, the tus state
  machine, the upload runner end to end against a `URLProtocol` fake (chunking, lost response then `HEAD`, resuming a
  job another process started, duplicates, token refresh), and the add-frame, history, frame settings, composer and
  server-choice view models.
- End to end against a local dev server (dev-auth mode, server and media tools in Docker) with a scripted frame that
  enrolls, stays online and acknowledges: claiming through a `/f/` link; sending JPEG, HEIC and MP4 that were
  processed, delivered, seen and reacted to; a send to an offline frame updating live in History when the frame came
  online (seen, 😂, "Miss you"); an invite link accepted by another account and let in from People; a remote command;
  a settings save. UI tests in the `KinframeUITests` scheme cover several of these.
- The share extension from the Photos app, with the app not running: the send arrived, delivered and seen.
- Screenshots of every main screen in light and dark (above).
- Production: the sign-in sheet opens Logto's page for `kinframe.appmire.be` with the client id, redirect and resource
  from discovery. Signing in there wasn't tried: no account was available for testing.

## What's left

- **Signing:** `DEVELOPMENT_TEAM` in `ios/Config/Signing.xcconfig` is empty until the Appmire team is added to Xcode.
  Only simulator builds were made; nothing ran on a device yet.
- **Production sign-in and a real frame:** needs a test account, and a frame code to claim, to try the whole loop on
  `kinframe.appmire.be` and a physical frame.
- **Universal Links in production:** set `KINFRAME_APPLE_TEAM_ID` to the Appmire Team ID on the hosted server
  (deployment repo). Associated domains are fixed at build time, so self-hosted servers use the `app.kinframe://`
  links instead.
- **APNs on the server.** The app side is ready behind the flag. The server needs:
  - an APNs auth key (`.p8`) with its Key ID and the Appmire Team ID, stored as secrets;
  - topic `app.kinframe.family`;
  - to send through `api.push.apple.com` for App Store / TestFlight builds and `api.sandbox.push.apple.com` for
    development builds. `PushEndpointCreate` has no field for the environment; the app could send it (for example in
    `device_name` or a new field), or the server could try production and fall back to sandbox on `BadDeviceToken`;
  - the same events and payloads as the other push kinds (IDs plus a localized title and body).
  Then build with `KINFRAME_APNS_ENABLED = YES` and add the Push Notifications capability.
- **Background wake-ups across processes:** a share whose upload outlives the extension relies on the system waking
  the app for that background session. The code handles it, but the simulator test only covered shares that finished
  while the extension was still running.
- Not in this PR: albums, the moderation queue, "move to new frame", deleting the account, "show now", scanning a
  server QR code at onboarding, the other 34 languages (they follow from `docs/i18n.md`).
- Generated enums reject unknown values; a new server enum value needs the client regenerated.

## Questions

1. Should the share extension preselect the frames shared to last time (current behaviour), or always start empty
   when there's more than one frame?
2. For APNs, which way to tell the server about sandbox vs production tokens (see above)?
3. Is the `app.kinframe://f/<code>?server=<url>` link format fine for self-hosted servers, or should the web app's
   `/f/` page offer an "Open in the app" button with it?
