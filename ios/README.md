# kinframe for iOS

The family app for iPhone and iPad: claim or join frames with their code, send photos and videos, follow delivery and
reactions, and manage frames. SwiftUI, Swift 6 with strict concurrency, iOS 17 and later. It mirrors the web app
(`web/`), which is the UX reference, and talks to the server only through the API in `docs/api/`.

## Build, run and test

Built and tested with Xcode 26.1 and the iOS 26.1 simulator. No signing is needed for the simulator.

```sh
open ios/Kinframe.xcodeproj                      # scheme Kinframe, any iPhone simulator

# or from the command line
cd ios
xcodebuild -project Kinframe.xcodeproj -scheme Kinframe \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

`Kinframe` runs the unit tests (`KinframeTests`, no network). `KinframeUITests` is a separate scheme for tests
against a dev server (see below). CI runs the unit tests on `macos-latest` without signing.

### Signing

The app is published under the **Appmire** Apple Developer team. The team is set in exactly one place,
`ios/Config/Signing.xcconfig` (`DEVELOPMENT_TEAM = QB4NQU492U`, Appmire GCV). Simulator builds sign ad hoc and need
no account. On a device the app and the share extension sign **manually** with profiles from
[fastlane match](https://docs.fastlane.tools/actions/match/): `match Development <bundle id>` for Debug,
`match AppStore <bundle id>` for Release (`project.yml`). The certificates and profiles live encrypted in the private
repo `jeroentrappers/apple-certificates` (branch `appmire`). Never commit certificates or profiles here.

fastlane is pinned in `ios/Gemfile` (`bundle install` once, then `bundle exec fastlane <lane>` from `ios/`). It needs
an App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`) and `MATCH_PASSWORD`, read from
`~/.appstoreconnect/env` and `~/.appstoreconnect/signing.env` when not set. Signing identities go into a dedicated
keychain (`~/Library/Keychains/kinframe-signing.keychain-db`, password `KINFRAME_KEYCHAIN_PASSWORD`); on CI
(`CI=true`) into a temporary one.

| Lane | What |
|---|---|
| `certificates` | Fetch the development and App Store certificates and profiles, read-only |
| `register_device name:"…" udid:…` | Register a device and regenerate the development profiles with it |
| `renew_certificates` | Create missing certificates and profiles (changes the Apple account) |
| `release [upload:true]` | Archive Release and export the .ipa to `ios/build`; `upload:true` sends it to TestFlight |

The app and the share extension share data through the App Group `group.app.kinframe.family` and the keychain group
`$(AppIdentifierPrefix)app.kinframe.family.shared`. Universal Links use the associated domain
`applinks:kinframe.appmire.be`; the server serves the matching `apple-app-site-association` when
`KINFRAME_APPLE_TEAM_ID` is set (see `server/README.md`).

### Releasing

`bundle exec fastlane release` archives a Release build signed with the App Store profiles and exports it with
`ios/ExportOptions.plist`; `release upload:true` also uploads it to App Store Connect for TestFlight. The version is
the latest `vX.Y.Z` tag without its pre-release suffix and the build number is the commit count; override with
`MARKETING_VERSION=…` and `BUILD_NUMBER=…`.

The app, the share extension and KinframeKit each carry a `PrivacyInfo.xcprivacy`: no tracking, UserDefaults
(`CA92.1`, `1C8F.1` for the App Group) and file timestamps (`C617.1`, the outbox) as required-reason APIs, and the
collected data (email, name, user ID, photos and videos, other user content, all for app functionality). Keep them
in step with the App Privacy answers in App Store Connect (drafted in `ios/AppStore.md`).

### Against a dev server

A dev server runs in dev-auth mode, so you sign in with any email instead of Logto:

```sh
docker compose -f deploy/compose/docker-compose.dev.yml --profile server up -d --build   # server on :8110
```

In the app choose **Use your own server**, enter `http://localhost:8110` (plain HTTP is allowed for local
addresses), and use the **Developer sign-in** form. The simulator reaches the Mac's `localhost`; on a device use the
Mac's LAN address.

Debug builds understand a few launch arguments, handy for screenshots and manual testing:

| Argument | Effect |
|---|---|
| `-kfServer http://localhost:8110 -kfDevEmail me@example.org [-kfDevName Me]` | Choose that server and sign in with a dev token |
| `-kfReset YES` | Forget server, session and onboarding progress |
| `-kfStep welcome\|server\|signIn` | Start onboarding at that step |
| `-kfTab frames\|feeds\|send\|history\|settings`, `-kfFrame <id> [-kfSection overview\|photos\|people\|settings]`, `-kfSend <id>` | Open a screen |
| `-kfFeed <id> [-kfFeedSection posts\|members\|frames\|birthdays\|settings]` | Open a feed |
| `-kfOpen <url>` | Open a link, e.g. `http://localhost:8110/f/12345678` |
| `-kfCompose /a.heic,/b.mov [-kfCaption …] [-kfComposeFrames <ids>] [-kfAutoSend YES] [-kfEditFirst YES]` | Stage files in the composer; `/still.heic+/clip.mov` stages a Live Photo |
| `-kfScrollTo plugins`, `-kfSheet share\|photo\|pluginAdd\|pluginEdit` | On a frame page: scroll to the plugins, open the share picker, a photo, the plugin catalog or the first plugin |
| `-kfSection calendar` with `-kfScrollTo calendars\|sentEvents\|calendarSettings`, `-kfSheet calendarAdd\|calendarEvent\|calendarMonth\|calendarDetail` | The Calendar section: scroll to a part, open "Add a calendar", "Send an event", the month view or an event |
| `-kfSharePreview … -kfComposeFrames <ids> -kfAutoSend YES` | Pick those frames in the share preview and send (the extension's path, with preparation) |
| `-kfScrollTo display`, `-kfAlbum <id or name>`, `-kfSheet albumNew\|albumSelect\|albumPick\|albumSchedule\|albumArrange\|albumRename\|albumPlay` | On a frame page: scroll to the screen settings; open an album in the library; open the new-album sheet, select two photos, the add-to-album picker, an album's schedule, arrange or rename sheet, or play it on the frame |
| `-kfScrollTo details\|ownership`, `-kfSheet transfer\|leave\|replace\|member [-kfTransferConfirm YES]` | On a frame page: scroll to the details or ownership, open the transfer (at its confirm step), the owner's leave, moving to a new frame, or the first member you can edit (with `-kfSection people`) |
| `-kfSheet reservations\|deleteAccount`, `-kfScrollTo signOut` | In Settings: the operator's shop reservations (sample data on a server without a shop), the delete-account sheet, the end of the page |
| `-kfDevOperator YES` | The dev token carries the operator scope (together with `-kfReset YES` to sign in again) |
| `-kfSharePreview /a.jpg,/b.mov` | Show the share extension's composer inside the app |
| `-kfSheet tv [-kfTvMode show\|mirror\|frame] [-kfTvLink YES] [-kfTvStart YES] [-kfTvStage YES]` | On a frame's Photos: open "Show on TV" in that mode, make a TV link, start the AirPlay slideshow (also without a TV), and show the TV's picture inside the sheet |
| `-kfLanguage en\|nl\|system` | Language |

```sh
xcrun simctl launch booted app.kinframe.family -kfServer http://localhost:8110 -kfDevEmail me@example.org
```

There's no real frame needed for testing: a frame is a device that enrolls (`POST /v1/devices/enroll`), shows a code
(`POST /v1/device/code`) and acknowledges what it receives (`/v1/device/changes`, `/v1/device/acks`). Any small
script doing that, or the frame app in an Android emulator, works.

The UI tests need such a server, a signed-in simulator and some environment (`TEST_RUNNER_KF_MEDIA`,
`TEST_RUNNER_KF_FRAME_ID`, `TEST_RUNNER_KF_PHOTOS=1`); see `KinframeUITests/ShareExtensionUITests.swift`.

## Project layout

| Path | What |
|---|---|
| `project.yml` | XcodeGen spec. `Kinframe.xcodeproj` is generated from it and checked in: after changing targets or adding files, run `xcodegen` in `ios/` |
| `Config/Signing.xcconfig` | `DEVELOPMENT_TEAM` |
| `Kinframe/` | The app: `App/` (entry, root state, toasts, push), `Onboarding/`, `Frames/` (list, add, detail with overview, photos, people, settings), `Feeds/` (list, a feed's posts, members, frames and settings, invites, subscriptions), `Home/` (timeline, strip, threads), `Chats/` (list, conversation, voice, people), `Social/` (shared social state and views: circles, settings, notifications), `Send/` (composer, framing and trim editor), `History/`, `Settings/`, `Components/` |
| `KinframeShare/` | The share extension (`ShareViewController` hosts the composer from KinframeKit) |
| `KinframeKit/` | Framework shared by both: generated API client, auth, discovery, upload queue, live socket, logic, design system, strings |
| `KinframeKit/API/Generated/` | Generated by `tools/generate-api.sh`, don't edit |
| `KinframeKit/Resources/Localizable.xcstrings` | Generated by `tools/strings.py`, don't edit |
| `KinframeTests/`, `KinframeUITests/` | Tests |
| `tools/` | Code generation (API client, strings, app icon) |

### Regenerating

```sh
ios/tools/generate-api.sh      # API client from docs/api/openapi.yaml (swift-openapi-generator, pinned in tools/openapi)
                               # also on Linux, e.g. inside the swift:5.10 container image
ios/tools/strings.py           # Localizable.xcstrings from the web catalogs plus KinframeKit/Resources/ios-strings.json
swift ios/tools/make-icon.swift ios/Kinframe/Resources/Assets.xcassets/AppIcon.appiconset
cd ios && xcodegen             # the Xcode project
```

`generate-api.sh` first runs a small `specprep` (in `tools/openapi`) that inlines references deeper than a
component and turns `oneOf [X, null]` into `X`, the same idea as `server/tools/specprep`; the spec itself stays
OpenAPI 3.1. Afterwards `tools/openapi/split.py` lays the output out as it is checked in: the Swift 6 import header
(`public import …`, whatever toolchain ran the generator) and `Types.swift` split into `Types.swift`,
`Types+Components*.swift` and `Types+Operations.swift`.

## Architecture

**Server and sign-in.** Onboarding validates a server by fetching `/.well-known/kinframe` and shows its name and
version. `ServerConfig` (address plus discovery) lives in the App Group's user defaults. `AuthService` signs in with
the Logto Swift SDK: Authorization Code with PKCE in `ASWebAuthenticationSession` (ephemeral, so there's no extra
consent prompt), redirect `app.kinframe:/callback`, the API resource from discovery and scopes
`openid profile email offline_access kinframe:operate`. Refresh tokens are in the Keychain (Logto SDK); the current
access token is cached there too, so the share extension uses the same session. Before a refresh the Logto client is
rebuilt from the Keychain, so a refresh token rotated by the other process is never reused. On dev servers a dev
token from `/dev/token` replaces Logto. New accounts still called "New member" get the name from the ID token, or
are asked once.

**API.** `API` wraps the generated `Client` with a middleware that adds the bearer token and `Accept-Language`,
retries once after a 401 with a fresh token, and turns RFC 9457 problems into `APIError`, whose `message` is
localized from the stable `code` like the web app.

**Sending.** The composer (`ComposeModel`) stages picked files (originals: HEIC, MOV, …) in the App Group and
validates them against discovery `limits`. Sending creates an `OutboxJob` in `<App Group>/Outbox/<id>/` and hands it
to the `OutboxRunner`:

- first every file is prepared, one at a time: photos become `photo-master-v1` (long edge 2560, sRGB, no metadata),
  videos and Live Photo clips `video-master-v1` (≤ 1080p, ≤ 30 fps, trim and mute baked in). Originals are never
  uploaded: when preparing fails, a file goes as it is only if it's already within those limits, otherwise the send
  stops with `media_unprepared`;
- each file is uploaded with tus 1.0 at `/v1/uploads`: creation-with-upload, then `PATCH` in 4 MiB chunks, `HEAD` to
  learn the offset after an interrupted request, a fresh upload when the old one expired, and the
  `Kinframe-Existing-Item` shortcut for duplicates; three files at a time;
- then `POST /v1/sends` with an `Idempotency-Key` stored in the job, so a retry never sends twice;
- every request runs in a background `URLSession`, so a send continues when the app is suspended or closed; every step
  is persisted, and a response that arrives after a relaunch (`handleEventsForBackgroundURLSession`) is applied to
  the job it was tagged with. A lease in the job says which process drives it.

The share extension uses the same queue: it saves the job, prepares every file while the sheet shows "Preparing 2 of
3", starts the uploads on its own background session, then closes; the app takes over when the system wakes it for
that session, or when it's opened (and prepares what's left if the sheet went away early).

**Live updates.** `LiveSocket` keeps `/v1/ws?access_token=…` open while the app is in the foreground, reconnecting
with back-off (1 s to 60 s, ±20 %) and right away with a new token after close code 4401. History patches reactions
and replies in place and refetches a send on `send.updated`; frame dots follow `frame.status`.

**Frame settings** are edited as a JSON document, so fields this app doesn't know survive, and saved as an RFC 7396
merge patch with `If-Match`. After a `412` the edit is rebased like the web app does: retried when the other change
touched other fields, otherwise shown for review.

**Design.** `KinframeKit/Design/Theme.swift` holds the web tokens (`web/src/app.css`): warm colours for light and dark,
radii, capsule buttons with 44 pt targets, cards. Headings use the serif design (the counterpart of the web's
Fraunces). Text uses Dynamic Type styles and controls carry VoiceOver labels.

**Strings** come from the web catalogs, with the same keys, `{placeholder}` syntax and `.one`/`.other` plurals, plus
iOS-only strings in `ios-strings.json` (English and Dutch for now). `L("key", ["name": …])` and `Lp(…)` look them up;
the language can be changed in Settings.

**Show on TV.** `TvSheet` mirrors the web's dialog: a private TV link (`purpose: tv` or `mirror`) to open in the TV's
browser, with share, copy and the active links to turn off, and "Use another screen", which claims the code a TV
browser shows at `/tv/frame`. Casting goes over AirPlay: with the phone mirrored to an Apple TV, the app's
`TvSceneDelegate` gets the external display (`UIWindowSceneSessionRoleExternalDisplayNonInteractive`) and, only while
a session runs, puts a window there. `TvCast` creates a `cast` link, fetches `GET /v1/tv/{token}` and plays the
slideshow natively (fit, focal point, caption and byline, videos to the end, a refetch after `refresh_after`); the
phone is the remote, and Stop revokes the link. A mirror loads the frame's TV page in a web view on the TV. Without
a session there's no window on the external display, so iOS mirrors the phone as usual.

**Feeds** (`docs/spec/feeds.md`) appear only when discovery says `feeds: true`: a Feeds tab with your feeds, and a
feed page with Posts (receipts and reactions where the server includes them, unsend or remove a post or one photo),
Members (roles, join requests, invites to join or to subscribe a frame as link, QR and code, leave, make owner),
Frames (the feed's subscriptions: show on a frame you manage, approve, reject, remove) and, for owners and admins,
Settings (name, cover, the four switches, delete). The calls are in `KinframeKit/API/FeedsAPI.swift` and the rules
the screens share (code parsing, who may do what, receipt text, subscription actions, history options, push routes)
in `KinframeKit/Core/Feeds.swift`. Feeds are recipients next to frames in the composer and the share extension
(`feed_ids` in the outbox job; a send needs at least one frame or feed), the history shows each feed's "Seen on 4 of
5 frames", and a send can be posted to a feed later (`POST /v1/sends/{id}/forward {feed_ids}`). A frame's settings
list its feeds (pause, resume, history option, unsubscribe, add one of your feeds or use a subscribe invite code) and
can play one feed (`slideshow.feed_id`). Feed invite links are `https://<server>/fi/<token>` (Universal Link) or
`app.kinframe://fi/<token>?server=<url>`; the "Enter a code" field also takes feed codes, which have letters
(`KF7Q-M2XA`) where frame codes are 8 digits. The websocket's feed events show toasts with Review, and a tapped
notification opens the feed, its post's receipts or the frame's feeds (`PushRoute`).

**Birthdays** (`docs/spec/birthdays.md`) appear only when discovery says `birthdays: true` (with `feeds`): a feed's
Birthdays section lists its people by their next birthday (29 February falls on 28 February in other years; the year
of birth itself is not a birthday), with the age where the year is visible, today's greetings from the frames and the
member's own "Birthday notifications" switch. Owner and admins (and posters with *posters can add birthdays*, a feed
setting) add people directly; other posters suggest them, and admins approve, decline or correct suggestions first.
The form has name, day and month, an optional year, *show age*, a link to a feed member ("Add my birthday" links you)
and a square photo uploaded like an avatar; the linked member may change only the year, the age, the photo or unlink.
Removing has Undo (the entry is created again, photo included). A frame's settings → Feeds has *Show birthdays* per
subscription (with "update the frame app" when the frame doesn't report `birthdays`), and subscribing (from a feed, a
frame or a subscribe invite) offers the same switch. The app keeps `User.timezone` equal to the device's zone (for the
08:00 morning notification). Live `birthday.*` events show toasts; tapped birthday notifications open the feed's
Birthdays, and older greeting notifications for the same person and day are cleared from Notification Center when
the app becomes active. The rules are in `KinframeKit/Core/Birthdays.swift`, the calls in `FeedsAPI.swift`, the
strings in `ios-strings-birthdays.json`.

**Photo sources** (`docs/spec/photo-sources.md`) appear only when discovery says `photo_sources: true` (the providers
from `photo_source_providers`) or `google_photos_import: true`. Album managers get "Sync from…" in an album's menu and
the album's sources under its header; feed owners and admins find the feed's sources in its Settings section; Settings
→ My photo sources lists the ones you set up. Each source shows its status (synced, importing 40 of 220, paused, the
error with its fix as a button), last and next sync, counts and warnings, with Sync now, pause, resume, change, take
over and stop syncing (keeping or removing the imported photos); a held-back mass removal is confirmed or kept. The
connect sheet takes an Immich shared link (recommended, optional password, a Paste button), an Immich server with an
API key and an album picker, or an iCloud shared album link (best effort, with how to turn on Public Website in
Photos), tests the connection, then asks for the first import, what happens to photos removed upstream, videos and
reaction notifications; feed copy explains that new photos are posted once the album has been quiet for 30 minutes (at
most 3 hours). A first import of more than 200 photos into an album suggests *Only in album mode*, which is also a
switch in the album menu. "Add from Google Photos" (album menu, a feed's Post menu) runs Google's consent in an
`ASWebAuthenticationSession` ending at `app.kinframe:/google-import`, opens the picker with `UIApplication.open` (the
Google Photos app when installed), and follows the import by polling and `google_import.updated` until it's done, with
cancel and undo; an unfinished import is picked up again after a relaunch. Credits read "Anna via Immich" in the library,
history and feed posts. `photo_source_attention` and `google_import_done` notifications open the album, the feed's
settings or the import. The rules are in `KinframeKit/Core/PhotoSources.swift`, the calls in
`KinframeKit/API/PhotoSourcesAPI.swift`, the strings in `ios-strings-photo-sources.json`.

**Family social** (`docs/spec/family-social.md`, README §5.18–§5.20) appears area by area, as discovery says
`social` (with `feeds`), `chats` (with `voice_messages`, `link_previews`) and `engagement` (with `email_digest`);
feeds are called families everywhere in the app (decision A). *Home* shows the strip of frames and families with
their freshness ring and "Oma would like new photos", your new milestone, this week's prompts, On this day and last
week's "seen and loved", then the timeline (`getTimeline`), consecutive prompt answers under one header. A post opens
its thread: comments oldest first with one level of replies (a reply to a reply quotes the person), frame entries,
reactions (❤️ 😂 😮 😢 👍 🙏; tap for ❤️, hold for the rest), @mentions picked while typing and sent as UTF-16 spans,
edit and delete, moderators' removal, close, mute, read state. *Chats* lists family, circle, group and direct chats;
a conversation has replies, reactions, edits within 24 hours, tombstones, photos and videos (prepared like sends and
uploaded with tus in the foreground, `ChatUpload`), voice messages (`AVAudioRecorder`, AAC-LC mono 48 kHz 64 kbit/s in
`.m4a`, a live waveform; played from the item's master), shared post cards, link previews when the operator turned
them on, "Put on a frame", read receipts, typing, mute, archive and group management; the operator note says chats
aren't end-to-end encrypted. Families gain circles (a section of the family page, an audience in the composer, a
chat), the `member` role (not offered as a recipient), the streak, the family chat, a mute and, for admins, the new
switches, the encouragement settings, prompts and reports. Settings gains read receipts, blocked people, reminders
(level, frame requests, summary, birthdays, snooze, quiet hours, email digest), milestones and, for operators, their
reports; a frame's settings gain *Show comments*, *Ask for new photos*, *Prompt cards* and *New photos from…*.
`SocialStore` (app) keeps the chat list, badge, typing, reminders, milestones and caches; the websocket's family
social events arrive as `LiveEvent.social`, pushes route through `PushRoute.social`. Reminder notifications have
Snooze 1 month and Fewer reminders (category `kinframe.reminder`), chat notifications a reply field (category
`kinframe.message`), and the app badge follows `getBadge`/`badge.updated`. The rules are in
`KinframeKit/Core/Social.swift`, `Chat.swift`, `Engagement.swift` and `SocialPush.swift`, the calls in
`KinframeKit/API/SocialAPI.swift`, `ChatAPI.swift` and `EngagementAPI.swift`, the strings in
`ios-strings-social.json`, `ios-strings-chat.json` and `ios-strings-engagement.json`. Debug builds also take
`-kfTab home|chats`, `-kfPost <id>` and `-kfChat <id>`.

**Families on other servers** (`docs/spec/multi-server.md` §3, README §5.22) appear only when discovery says
`remote_families`. A frame's settings get *Families on other servers*: "Add a family from another server" takes a
pasted `/fi/<token>` link of another kinframe server (also the `app.kinframe://fi/…?server=` form), checks it with
`previewRemoteInvite` and confirms naming both servers, that this server's operator can see the copies, and whether
the family's admins approve first, with the history option, birthdays and what happens to the copies when it ends;
the list shows each follow's status (waiting, following, not syncing with the error, ended with its reason), copy
progress, photo count and last sync, with the on-end choice and unsubscribe (keep or remove the photos), kept fresh
by `remote_subscription.updated`. `RemoteFollowSheet(api:frame:inviteURL:done:)` is the same flow without a fixed
frame (pick one you manage). Family settings get "Allow frames on other servers" (turning it off ends every remote
follow, asked first) and the blocked servers; subscribe invites can allow frames on other servers; remote frames in
the Frames section carry their server and "Block this server". Remote frame entries and reactions read "Oma's frame
(kinframe.appmire.be)", and copied photos in the library are credited "Lies · Janssens". The rules are in
`KinframeKit/Core/RemoteFamilies.swift`, the calls in `KinframeKit/API/RemoteAPI.swift`, the screens in
`Kinframe/Remote/`, the strings in `ios-strings-remote.json`.
**Leaving** (`docs/spec/leaving.md`, README §5.21) appears only when discovery says `leaving`; without it the old
leave dialogs stay. Leaving a family opens a sheet from `getLeavePreview`: your photos stay (default) or go with you,
your comments stay or are deleted, and "Leave this frame too" per frame (checked where the server suggests it; owned
frames aren't offered); the toast carries the result line ("214 photos were taken away from 2 frame(s). They stay in
the frames' trash until 19 December."). Leaving a frame asks the same photo question (also after a transfer). The
history lists keep requests to you (allow all, pick some, decline; final) and the families and frames you left that
still show your photos, with "Take my photos away". A frame's trash and a family's Members section (managers) show
"Taken away by Tom" groups with the day they go, the photos of a taking (`visibility=taken_away`) and "Ask Tom to keep
them" for all or picked photos with a note ("Tom doesn't accept requests" when blocked). Account deletion asks take
(default) or leave to the families; handed-over photos are credited "Tom (former member)". Settings → Memories
leaves people out and shows the hidden memories; On this day has "Don't show this memory again". Direct chats with
someone you no longer share a family or frame with say "You can no longer message Lies". `photos_taken_away`,
`keep_request`, `keep_answered` and `ownership_changed` notifications and the `placement.updated` /
`keep_request.updated` events route through `PushRoute.leaving` and `LiveEvent.leaving`. The rules are in
`KinframeKit/Core/Leaving.swift`, the calls in `KinframeKit/API/LeavingAPI.swift`, the screens in `Kinframe/Leaving/`,
the strings in `ios-strings-leaving.json`.

**Push.** APNs is behind the `KINFRAME_APNS_ENABLED` build setting (off). When on, the app asks for notification
permission after the first send and registers the device token with `POST /v1/me/push-endpoints {kind: "apns"}`.
The server can't send APNs yet; until then the websocket covers the app while it's open.

## Limits and notes

- Universal Links work for `kinframe.appmire.be` only, because associated domains are fixed at build time. Join and
  invite links of other servers open through `app.kinframe://f/<code>?server=<url>`,
  `app.kinframe://i/<token>?server=<url>`, `app.kinframe://fi/<token>?server=<url>` or `app.kinframe://open?url=<link>`.
  Feed invite links (`/fi/<token>`) open the app only when the server's `apple-app-site-association` lists `/fi/*`.
- The simulator doesn't let UI tests look inside a share extension, so its composer is tested through the in-app
  preview; sharing from Photos is covered by an opt-in test that taps by position.
- Generated string enums reject values they don't know. If the server adds an enum value (a new placement status, for
  example), regenerate the client. The photo-source enums (provider, status, warnings, history, deletion policy, error
  codes, the Google import's status and errors, `ItemVia.service`) are the exception: `specprep` turns them into
  plain strings and `Core/PhotoSources.swift` maps them, so a newer server's values show a neutral fallback.
