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
against a dev server (see below). CI runs the unit tests on `macos-latest` without signing, on the simulator and as
Mac Catalyst (`-destination 'platform=macOS,variant=Mac Catalyst'`).

`SmokeTests` (in `KinframeTests`) starts the real app root signed in to a fake server with every optional feature on
and opens every tab, the composer, a frame and a family on each of their pages, the photo sources, the simple view and
a few pushed screens, once
with a working server and once with failing requests; any crash fails it. The fake server answers what it has no
hand-written answer for from `KinframeTests/Support/SmokeFixtures.swift`, generated from `docs/api/openapi.yaml` by
`python3 ios/tools/smoke-fixtures.py` (rerun after API changes). `bundle exec fastlane smoke_test [platform:mac]` runs
it alone; `release` and `mac_release` run it first.

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
| `smoke_test [platform:mac]` | Run `SmokeTests` on an iPhone simulator (`SMOKE_SIMULATOR` picks one) or as Mac Catalyst, no signing |
| `release [upload:true]` | Run the smoke test, archive Release and export the .ipa to `ios/build`; `upload:true` sends it to TestFlight |

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
(`CA92.1`, `1C8F.1` for the App Group), file timestamps (`C617.1`, the outbox) and system boot time (`35F9.1`, the
crash reporter) as required-reason APIs, and the collected data (email, name, user ID, photos and videos, other user
content, all for app functionality; crash and other diagnostic data, not linked). Keep them in step with the App
Privacy answers in App Store Connect (drafted in `ios/AppStore.md`).

### Crash reports

Official builds report crashes, app hangs and watchdog terminations to kinframe's GlitchTip, project `family-apple`
(`docs/spec/crash-reporting.md`), with sentry-cocoa (SPM product `SentrySPM`, which compiles it from source, linked
into KinframeKit; `KinframeKit/Core/CrashReporting.swift`). The app and the share extension each start it at launch.

- The build setting `KINFRAME_SENTRY_DSN` (empty by default) becomes Info.plist `KinframeSentryDSN`. Without a DSN the
  SDK isn't started at all, so debug builds, CI and other people's builds send nothing.
- Settings → **Send crash reports** (default on, kept in the App Group so the share extension follows it, applies at
  the next start) turns it off. It shows only in builds with a DSN.
- Reports carry no user, IP, device name, cookies, headers, query strings or bodies, no screenshots, view hierarchy
  or replay; tracing, sessions and failed-request events are off. `beforeSend`/`beforeBreadcrumb` enforce it
  (`KinframeTests/CrashReportingTests.swift`). Release `family-apple@<version>+<build>`, environment `production`,
  tags `app.platform` (`ios`, `mac-catalyst`) and `component` (`app`, `share-extension`).
- `release` and `mac_release` pass `KINFRAME_SENTRY_DSN` from the environment, and after archiving upload the
  archive's dSYMs (app, extension, KinframeKit) with `sentry-cli debug-files upload --project family-apple`, using
  `SENTRY_AUTH_TOKEN`, `SENTRY_URL` (default `https://errors.kinframe.appmire.be`) and `SENTRY_ORG` (default
  `kinframe`). Without the token or sentry-cli (`brew install getsentry/tools/sentry-cli`), or when the upload fails,
  they warn, print the command to upload later and go on. Both variables are read from
  `~/.appstoreconnect/signing.env` when not set.

To see what a build sends, point it at any HTTP server that logs requests, e.g.
`KINFRAME_SENTRY_DSN=http://0123456789abcdef0123456789abcdef@127.0.0.1:8977/1` in `xcodebuild … build`; envelopes
arrive at `POST /api/1/envelope/` (gzip). A crash is sent at the next start; run the app without the debugger
attached (`xcrun simctl launch`, or open the .app), which otherwise catches the crash first.

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
| `-kfTab home\|frames\|chats\|family\|send\|history\|settings`, `-kfFrame <id> [-kfSection photos\|calendar\|settings\|people]`, `-kfSend <id>` | Open a screen (`send` opens the composer, `history` and `settings` the pages under Family) |
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
| `Kinframe/` | The app: `App/` (entry, root state, toasts, push), `Onboarding/`, `Family/` (the family tab, profile and settings, getting started, simple view, shared pieces), `Frames/` (list, add, a frame's screen and pages), `Feeds/` (a family's members, frames and settings, invites, subscriptions), `Home/` (timeline, threads), `Chats/` (list, conversation, voice, people), `Social/` (shared social state and views: circles, settings, notifications), `Send/` (composer sheet, framing and trim editor), `History/` (sent history), `Settings/` (app settings, accounts), `Components/` |
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

**Several accounts** (`docs/spec/multi-server.md` §2, README §5.22). The app keeps a list of accounts in the App
Group (`AccountStore`: server address and discovery, the first is primary), one `AppModel` each with its own
`AuthService`, API client, websocket, push registration, caches and discovery flags (`Kinframe/App/Accounts.swift`).
The primary model owns navigation; the others forward tabs, paths and preselections to it, and every route carries
its account so a detail screen gets the right model in the environment. Home, Chats (badge = the sum), Frames,
Families, History and the composer list every account, with a server chip (discovery `server_name`) when there is
more than one; Settings → Accounts adds a server (the onboarding screens run with the new account's model), signs out
of one, makes one primary and opens each account's own Settings. A tab shows when any account has it (Home and Chats
follow `social`/`chats`/`engagement` of every account, Families `feeds`), and inside each post, chat, card and button
follows its own account's flags, user id and roles. Home shows every account's strip, newest unseen milestone,
prompts, On this day and weekly summary, each talking to its own server. Merged lists that page (Home, History) show
only as far back as every server has loaded and page the server holding the list back (`AccountMerge.merged`,
`toLoad`), so they stay in time order. "Enter a code", "Add frame" and "New family" ask which server first when
there is more than one (`ServerChoiceSheet`). The composer checks a send against the limits of the servers it goes to
(the strictest of each, `ComposeLimits`), also in the share extension. Each account's Google Photos import, keep
request answers and social sheets show over the tabs (`SecondaryAccountPresenters`). A send to recipients on two
servers is one outbox job per server (`ComposeModel.makeJobs`); the first prepares the media and the others take its
masters (`OutboxJob.preparedBy`); the runner holds one session per server. The share extension reads the same list
and shows recipients grouped per server. A tapped notification opens the account named by the payload's `server`. A
link of a server without an account asks "This family is on kinframe.janssens.be. Sign in there to join." and adds
that server after sign-in. An install from before keeps its session: its account is `default`, whose Keychain names
are the old ones.

**Server and sign-in.** Onboarding validates a server by fetching `/.well-known/kinframe` and shows its name and
version. `ServerConfig` (address plus discovery) lives in the App Group's user defaults. `AuthService` signs in with
the Logto Swift SDK: Authorization Code with PKCE in `ASWebAuthenticationSession` (ephemeral, so there's no extra
consent prompt), redirect `app.kinframe:/callback`, the API resource from discovery and scopes
`openid profile email offline_access kinframe:operate`. The SDK runs only the browser sign-in, with its persistent
storage off: its single Keychain slot never holds any account's tokens. Each account keeps its refresh token, ID token
and current access token in the shared Keychain under its own names (`LogtoSlot`), so the share extension uses the
same sessions. Refreshing is one `refresh_token` grant at the account's own Logto tenant (`LogtoTokenEndpoint`, found
through its OpenID configuration) with the account's own refresh token. Refreshes of one account are serialized: one at
a time per account in the process (`RefreshRegistry`), and between the app and the share extension by a file lock in
the App Group (`FileSessionLock`, `flock`), under which the Keychain is read again, so a token the other process just
refreshed is used instead of spending the rotated refresh token twice. A 401 passes the refused token: when another
request already replaced it, that token is used and nothing is refreshed. A token is cached and sent only when its
`iss` and `aud` are the account's issuer and API resource (`TokenAudience`). A refused refresh token (`invalid_grant`)
ends only that account's session; network trouble keeps it. Signing in or out while a refresh runs wins. Tested with
fakes in `KinframeTests/SessionTests.swift`. On dev servers a dev token from `/dev/token` replaces Logto. New accounts still called "New member" get the name from the ID token, or
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

**Navigation** follows `docs/spec/simple-app.md` (the server always has families and social): tabs Home · Frames ·
`+` · Chats · Family (a sidebar on iPad and Mac); `+` opens the composer sheet (`Send/ComposerSheet.swift`) instead
of a tab. Home merges every family of every account (filter chips with several families, remembered per device; a
send to several families shows once); its bell opens Activity (`Home/ActivityView.swift`: frames asking for photos,
people who want to join and frames waiting for approval for admins, posts with new comments or a mention). The home family (posted to most by
the server's `my_post_count`; ties: created, then oldest, then name) is the composer's default; "Change" ticks several families or a circle per family. Frames lists cards (the photo
the frame shows from `now_showing`, "Online · showing now" only for a slide on screen, today's hearts from
`reactions_today`; the drawing without them) and opens a frame as one list (photos, send to this frame only, calendar and add-ons, TV, settings and help); Family is
the profile (Photos with "Only mine", People, Frames, "Invite family", "Add a frame" with the last 50 posts and no
questions) or, with several families, a list of them; the gear has This family and App (accounts, notifications,
privacy, simple view, sent history). Without a family the app shows only getting started (`Family/GettingStarted.swift`).
The simple view (`Family/SimpleView.swift`) is a per-device setting, asked once after joining a family. The logic the
screens share is in `KinframeKit/Core/SimpleApp.swift`.

**Feeds** (`docs/spec/feeds.md`, "families" in the app): the family profile with Photos (receipts and reactions where the server includes them, unsend or remove a post or one photo),
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
reports; a frame's settings gain *Show comments*, *Ask for new photos*, *Prompt cards* and *New photos from…*. A
frame's request (`frame_request` push, `photo_request.*` events, the Home badge) or a quiet-frame reminder
(`reminder_quiet`) for a frame you're not on opens "Send something to Oma's frame" with the families whose posts
reach it preselected (`PhotoRequest.feed_ids`, else the families you post to with an active subscription of the frame;
`FrameRequestTargets`); requests heard of that way stay on the Home strip until answered or expired.
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

- Universal Links work for `kinframe.appmire.be` only: associated domains are fixed in the entitlements at build time,
  and an app can't claim other servers' domains generically (every server serves an `apple-app-site-association`,
  but iOS only checks the domains the app lists). Feed invite links (`/fi/<token>`) open the app only when that
  server's `apple-app-site-association` lists `/fi/*`. Links of other servers still reach the app:
  - **Tapped inside the app** (a chat, a comment, a caption): the root `openURL` handler opens every kinframe link in the
    app (`Accounts.openInApp`), whatever its server; an unknown host is checked first (`/.well-known/kinframe`), so an
    ordinary web page with a similar path still opens in the browser. (iOS doesn't open an app's own Universal Links
    from inside the app either, so this also covers `kinframe.appmire.be`.)
  - **Pasted**: "Paste" in "Enter a code" takes a whole link: a frame code of that server fills the boxes, an invite
    or a link of another server goes where links go (`DeepLink.find` finds the link in pasted text).
  - **Shared**: "Share → kinframe" on a link (Safari, Messages, a chat app) leaves it in the App Group and the app opens
    it the next time it comes to the front (`PendingLink`; a share extension can't open its app itself).
  - **Custom scheme**: `app.kinframe://f/<code>?server=<url>`, `app.kinframe://i/<token>?server=<url>`,
    `app.kinframe://fi/<token>?server=<url>` or `app.kinframe://open?url=<link>`.

  Then the account on that server handles it, or the app asks to sign in there (or, for a family invite, to show the
  family on a frame you manage here).
- The simulator doesn't let UI tests look inside a share extension, so its composer is tested through the in-app
  preview; sharing from Photos is covered by an opt-in test that taps by position.
- Generated string enums reject values they don't know. If the server adds an enum value (a new placement status, for
  example), regenerate the client. The photo-source enums (provider, status, warnings, history, deletion policy, error
  codes, the Google import's status and errors, `ItemVia.service`) are the exception: `specprep` turns them into
  plain strings and `Core/PhotoSources.swift` maps them, so a newer server's values show a neutral fallback.
