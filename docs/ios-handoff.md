# Handoff: kinframe iOS family app

This is the brief for building the native iOS family app. It is written for a fresh Claude Code session on a Mac
(mini4: macOS 27, Xcode 26.1.1, iOS 26.1 simulator). Read it fully before starting.

**Apple team: Appmire.** The app is published under the **Appmire** Apple Developer team, not Secutec or bpost. The
certificates currently on mini4 belong to other teams (Secutec `3WBW3LE88P`, an expired bpost one), so don't use them.
Until the Appmire team is added to Xcode on mini4, build for the simulator only (no signing needed). The Team ID is a
build setting (`DEVELOPMENT_TEAM`, set in one place such as an `.xcconfig`), never hard-coded elsewhere.

## Standing rules (non-negotiable)

1. **No AI attribution anywhere.** No `Co-Authored-By` trailers, no "Generated with Claude", nothing similar, in
   commits, PR descriptions, code, comments or docs. Commit as `Jeroen Trappers <jeroen.trappers@gmail.com>`.
2. **Clean room.** kinframe is a clean-room alternative to Frameo. Work only from this repo's `docs/`, plus your own
   knowledge of iOS. Never decompile or reference Frameo app code.
3. **No secrets in the repo.** The repo is public. No keys, certificates, provisioning profiles or tokens in git.
4. **Branch + PR.** Work on branch `ios-app`, push it, open a PR against `main`. Never force-push `main`.
5. Keep commits logical and messages descriptive.

## What kinframe is

kinframe is an open-source, self-hostable replacement for Frameo photo frames:

| Part | What it is |
|---|---|
| **Frame** | Android kiosk app (Kotlin/Compose, `android/frame`) |
| **Server** | Go backend + Postgres + Logto (`server/`), live at **https://kinframe.appmire.be** |
| **Website** | SvelteKit SPA (`web/`), the most complete client today and the **UX reference** |
| **Android family app** | Kotlin/Compose (`android/family`), being built in parallel. Keep feature names consistent. |
| **iOS family app** | Your job: SwiftUI in `ios/` |

Families use the phone apps to claim frames with a code, send photos and videos, see delivery and reactions, and
manage frames.

Read these, in this order:

| Doc | What it covers |
|---|---|
| `docs/spec/product-spec.md` | Product spec. Esp. §2 personas, §3 concepts, §4.1–4.3 accounts/frames/sending, §4.6 library, §5 non-functional, §6 defaults, §8 decisions. |
| `docs/architecture.md` | Components, **server discovery**, Logto. |
| `docs/api/README.md` | API conventions, auth, flows (claim/join, invites, sends, change feed, websockets, push). |
| `docs/api/openapi.yaml` | **The contract.** Generate the Swift client from it. |
| `docs/deploy.md` | Logto app registration: native redirect URIs. |
| `web/src/` | Reference implementation of every flow. Mirror its behaviour and copy. Its i18n catalogs are in `web/src/lib/i18n/`. |

## Technical decisions

- **SwiftUI**, Swift 6 strict concurrency.
- **Deployment target: iOS 17.** That covers older family iPhones; the current simulator is 26.1.
- **Xcode project** at `ios/Kinframe.xcodeproj` with app target **Kinframe** (bundle id `app.kinframe.family`) and a
  **Share Extension** target (bundle id `app.kinframe.family.share`). They share data through an App Group
  `group.app.kinframe.family`. Signing team: the **Appmire** team via `DEVELOPMENT_TEAM` in an `.xcconfig` (automatic signing; don't commit profiles).
- **API client:** Apple's `swift-openapi-generator` + `swift-openapi-urlsession`, generated from
  `docs/api/openapi.yaml`. Use a build plugin, or check in the generated code with a script to regenerate it.
  - If the generator chokes on OpenAPI 3.1 features, mirror the server's `server/tools/specprep` approach: rewrite a
    copy of the spec before generation.
- **Auth: Logto** via the official Logto Swift SDK (`logto-io/swift`), or AppAuth-iOS if the SDK is unsuitable. Details
  are under **Server and auth** below.
- **Uploads:** tus 1.0 at `/v1/uploads`. Implement the small subset you need yourself:
  - creation, `PATCH` with `Upload-Offset`, `HEAD` to resume, 4 MiB chunks;
  - a **background `URLSession`** so sends continue when the app is closed or the photo picker is dismissed;
  - `Idempotency-Key` on `POST /v1/sends`.
- **Photos:** `PhotosPicker` (no full library permission), HEIC/HEIF/JPEG/PNG and videos (MOV/MP4/HEVC) uploaded as-is.
  The server converts everything (see product spec §8: it keeps only a capped master rendition).
- **Persistence:** SwiftData or plain files for the outbox/history cache. Keychain for tokens.
- **Design:** warm and calm, the same tokens as the web app (`web/src/app.css`: colours, radii). Light and dark mode,
  Dynamic Type, VoiceOver labels, 44 pt touch targets. The app should feel like a polished consumer app.
- **Localization:** String Catalogs (`Localizable.xcstrings`). Start with English + Dutch, mirroring the web catalogs'
  wording. The rest of the 36 languages follow later from the shared glossary in `docs/i18n.md` once it lands.

## Server and auth

1. **Server choice.** The default is `https://kinframe.appmire.be`. The user can choose "Use your own server" and enter a
   URL. Validate by fetching `GET <server>/.well-known/kinframe` and show the server name before sign-in.
2. **Discovery gives** `auth.issuer`, `auth.mobile_client_id` and `auth.api_resource`. Live values today:
   - issuer `https://auth.kinframe.appmire.be/oidc`
   - mobile client id `o7tjvpv0uz2bcoazbedgo`
   - resource `https://kinframe.appmire.be/v1`

   Always read them from discovery; never hard-code them.
3. **OIDC: Authorization Code + PKCE** through `ASWebAuthenticationSession`.
   - redirect URI `app.kinframe:/callback`, post-logout `app.kinframe:/logout` (already registered by the server's Logto
     bootstrap)
   - scopes `openid profile email offline_access`, plus `kinframe:operate` (only granted to operators)
   - resource = `api_resource`; refresh tokens kept in the Keychain
4. **API calls** use `Authorization: Bearer <access token for the resource>`.
5. **New users** sign up with an email code. Logto asks for their name at sign-up. If `GET /v1/me` still returns the
   placeholder display name `New member`, ask "What should your family call you?" once and `PATCH /v1/me`.
6. **Dev mode.** When discovery says `"dev_auth": true`, show a dev sign-in form posting `{email, name}` to `/dev/token`.
   - Run a dev server locally: `cd server && make db-up && make run` needs Docker on the Mac; see `server/README.md`.
   - Or ask for a dev server elsewhere.

## Features (v0.1 parity with the web app)

1. **Onboarding:** welcome → server choice → sign in → name prompt if needed → add your first frame. Notification
   permission is requested later, after the first send.
2. **Frames tab:**
   - frames list (name, location, online/last seen, role) with an empty state
   - **Add frame** with the 8-digit code: formatted `1234 5678`, paste-friendly, `POST /v1/frames/redeem`
   - result states: claimed, joined, pending approval
   - **Universal Links** for `https://<server>/f/{code}` and `/i/{token}`, with the custom scheme as a fallback
3. **Send:**
   - pick photos and videos, mixed
   - captions (≤280, apply to all), fill/fit + focal point (tap on the preview, with landscape and portrait frame
     previews)
   - video trim (min 3 s; max from `limits.max_video_seconds`) and an "include audio" toggle
   - choose frames (online state); upload progress; then go to the send in History
   - respect `limits.max_items_per_send`
4. **Share Extension:** "Share → kinframe" from Photos and other apps for images and videos.
   - The extension shows a compact composer: frames + caption. It hands the files to the main app's background upload
     queue through the App Group and returns quickly.
5. **History:**
   - sends newest first, with per-frame status (processing → delivered → seen)
   - reaction and quick-reply chips; edit caption; unsend
   - live updates over the user websocket `GET /v1/ws?access_token=…`
6. **Frame detail** (by role/permission):
   - overview (health, online), library grid (signed `thumb_url`), hide/trash/restore
   - members & roles, invites (share sheet), join requests (approve/deny)
   - remote settings (interval, transition, order, fill, brightness, sleep schedules, weather location via
     `GET /v1/geocode`)
   - remote commands (next, previous, sleep, wake, identify, sync_now)
7. **Settings:** profile (name, avatar upload `PUT /v1/me/avatar`), server info, sign out, language.
8. **Push:** APNs is **not** wired up on the server yet: only UnifiedPush and WebPush are. Build the app side behind a
   flag:
   - register for remote notifications and `POST /v1/me/push-endpoints` with kind `apns`
   - leave a note in the PR describing what the server needs (APNs auth key `.p8` stored as a secret, topic
     `app.kinframe.family`)
   - until then, rely on the websocket while the app is open

**Universal Links** need the server to serve `/.well-known/apple-app-site-association` for the app id
`<AppmireTeamID>.app.kinframe.family` (Team ID from config, e.g. `KINFRAME_APPLE_TEAM_ID`). Add that to the Go server: a small handler, configurable via env, with a test, in
the same PR.

## Quality bar and verification

- **Tests:** unit tests (XCTest/Swift Testing) for:
  - code formatting/validation, discovery parsing
  - tus resume logic (with a URLProtocol stub)
  - send validation against limits, deep-link parsing
  - view models
- **Build & test from the command line:** `xcodebuild -scheme Kinframe -destination 'platform=iOS Simulator,name=iPhone 17' test`.
  Use whichever simulator device names exist (`xcrun simctl list devices`).
- **UI review:** run in the simulator, take screenshots (`xcrun simctl io booted screenshot`) of each main screen in
  light and dark mode, look at them and iterate until polished. Compare with the web app.
- **End to end:** sign in against production with a real account if one is provided, or against a local dev server in
  dev-auth mode. Claim a test frame only if the user provides a code, then send a photo and confirm it reaches the
  frame (the user can confirm on the physical frame).
- **CI:** add a `macos-latest` GitHub Actions job (build + unit tests on a simulator) to `.github/workflows/ci.yml`,
  without signing.

## Deliverable

- **Pull request:** a PR from `ios-app` with the app, the share extension, tests, the AASA handler, the CI job, and an
  `ios/README.md` (build, run, architecture, how to point at a dev server).
- **PR description:** what works and what's left, with screenshots. No AI attribution.
- **Questions:** raise them in the PR description or ask the user (Jeroen) directly rather than guessing on
  product-level decisions.
