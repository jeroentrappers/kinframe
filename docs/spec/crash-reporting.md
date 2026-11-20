# Crash reporting

kinframe's own builds report crashes and unhandled errors to a self-hosted [GlitchTip](https://glitchtip.com), an
open-source server that accepts the Sentry SDKs' reports. Nothing goes to a third party.

## 1. Where reports go

- GlitchTip (7.0 or later) runs at `https://errors.kinframe.appmire.be` on appmire-hetz1 (kinframe-deploy, role `glitchtip`),
  organization `kinframe`, with sign-up closed.
- Operators sign in with kinframe's Logto: `bootstrap-logto` registers the app `GlitchTip` and writes
  `glitchtip-oidc.json` (`KINFRAME_LOGTO_GLITCHTIP_URL`), and the role links each operator's Logto user to the GlitchTip
  account with the same email (`glitchtip_oidc_file`). Anyone else is refused; the password sign-in stays.
- One project per client:

  | Project | Client | SDK |
  |---|---|---|
  | `kinframe-server` | Go server | `sentry-go` |
  | `kinframe-web` | SvelteKit web app | `@sentry/browser` |
  | `family-android` | Android family app | `io.sentry:sentry-android` |
  | `frame-android` | Android frame app, GitHub and Play flavors | `io.sentry:sentry-android` |
  | `family-apple` | iOS and Mac Catalyst family app (and its share extension) | `sentry-cocoa` (SPM) |

- New issues mail the operator (GlitchTip project alerts).

## 2. On only in official builds

A client reports only when it was built or configured with a DSN. Without one the SDK is not started at all, so
debug builds, CI, forks and self-hosters' own builds send nothing.

- **Android:** Gradle property `kinframe.sentryDsn` (fastlane and `release-frame.yml` pass it from
  `KINFRAME_SENTRY_DSN_FAMILY` / `KINFRAME_SENTRY_DSN_FRAME`) → `BuildConfig.SENTRY_DSN`. The manifest's auto-init is
  disabled; the app calls `SentryAndroid.init` itself when the DSN is not empty.
- **Apple:** build setting `KINFRAME_SENTRY_DSN` (fastlane `release`/`mac_release` pass it from the environment) →
  Info.plist key `KinframeSentryDSN`, read at launch.
- **Server:** env `KINFRAME_SENTRY_DSN`, empty by default. A self-hoster may point it at their own GlitchTip/Sentry.
- **Web:** env `KINFRAME_SENTRY_WEB_DSN` on the server, published in the discovery document as `sentry_dsn`
  (omitted when unset); the web app starts the SDK after reading discovery. The desktop app is a native client
  without crash reporting.

A DSN is not a secret (it only allows sending events), but it still lives in env/config, not in the repo.

## 3. What a report contains

The stack trace, the exception, the release (`<project>@<version>+<build>`), environment `production`, OS and
device model, app state (foreground, memory) and breadcrumbs of navigation and lifecycle.

It never contains personal data:

- `sendDefaultPii` off; no user, email, account id, IP address (`user.ip_address` dropped, server-side
  "scrub IP" on), cookies or `Authorization` headers.
- HTTP breadcrumbs and request data keep method, host, path and status; query strings and bodies are dropped
  (tokens and file names travel in them).
  The server and web also replace ids, tokens and file names in paths with `*` (invite, TV and signed media
  links carry tokens in the path); web reports keep only the User-Agent header.
- Sessions (release health, which carry an installation id), failed-request events and click breadcrumbs off.
- No screenshots, view hierarchy, session replay or attachments. Performance tracing off (sample rate 0); ANR /
  app-hang detection on.
- Every SDK has a `beforeSend` hook that enforces the above, so a later SDK default can't widen it.

## 4. Opting out

- **Family apps (Android, iOS, Mac) and web:** a setting "Send crash reports" (default on), stored on the device,
  explained in one line ("Anonymous crash details help us fix bugs. No photos, messages or personal data.").
  It takes effect at the next start.
- **Frame:** always on in official builds (an owner-managed device with no personal data in reports).
- **Server:** the operator's choice through the env var.

The privacy policy (`/privacy`) lists crash reports: what is sent, where it is stored, retention (GlitchTip
deletes events after 90 days) and the opt-out.

## 5. Readable stack traces

- **Android:** release builds upload R8's `mapping.txt` to GlitchTip (`sentry-cli upload-proguard`, auth token
  `SENTRY_AUTH_TOKEN`; GlitchTip 7.0 or later applies it) and to Play (Android vitals). The build writes the
  mapping's ProGuard UUID into the app (`assets/sentry-debug-meta.properties`) so reports match their upload.
- **Apple:** release lanes upload the dSYMs with `sentry-cli debug-files upload` (GlitchTip 7.0 or later for the
  universal Mac Catalyst dSYMs).
- **Web:** the build uploads source maps with `sentry-cli sourcemaps upload` and doesn't serve them.
- **Server:** Go stack traces are readable as is.

Uploads need `SENTRY_URL=https://errors.kinframe.appmire.be`, `SENTRY_ORG=kinframe`, the project and
`SENTRY_AUTH_TOKEN`; a missing token skips the upload with a warning instead of failing the release.
