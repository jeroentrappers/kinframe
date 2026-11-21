# The frame app on Google Play

People can turn an old Android tablet into a kinframe frame by installing the frame app from Google Play. Converted
Frameo frames keep getting the frame app as before: from GitHub releases, installed by the convert tool, updated by
the kinframe server.

Both are built from the same code as two **build flavors** of `android/frame` (dimension `distribution`):

| | `github` | `play` |
|---|---|---|
| Who gets it | Converted frames (`tools/convert`, website `/convert`), local development | Android tablets, from Google Play |
| Built by | `.github/workflows/release-frame.yml` on a `v*` tag: `:frame:assembleGithubRelease` → `kinframe-frame-<name>-<code>.apk` | `fastlane android frame_internal tag:v…` (`android/fastlane/Fastfile`): `:frame:bundlePlayRelease` → AAB |
| Signed with | The release key (`KINFRAME_KEYSTORE_*`, CI secrets `ANDROID_KEYSTORE_*`) | The frame's upload key (`KINFRAME_FRAME_UPLOAD_*`); Google re-signs with the app signing key, which is the same release key (see "Play App Signing") |
| Updates | Self-update from the server (docs/api/README.md §5.7): root `pm install` or the system installer, rollback on a crash loop | Google Play in-app updates; never downloads or installs an APK |
| Root | Used where the frame has it (backlight off, clock, power-off remount, reboot, installer) | Never tried: `su` is never started |
| Heartbeat `features` | `feed_backfill`, `birthdays` (unchanged) | the same plus `play` |
| Minimum Android | 6 (API 23) | 7 (API 24): Play's automatic protection requires it |
| Default | yes (`isDefault`, Android Studio, `./gradlew assemble…` without a flavor builds both) | |

Both use the application id `app.kinframe.frame` and the version scheme of the GitHub release for a tag
(`major·1 000 000 + minor·10 000 + patch·100 + n`, n = 99 for a release, N for `-beta.N`). Because Google signs the
Play build with the GitHub release key, an installed copy of either build accepts updates of the other: a tablet that
was set up from a GitHub APK can move to Play, and a Play install could take a GitHub APK.

Source sets: `src/main` holds everything shared, `src/github/java/.../update/` the self-update (`UpdateManager`,
`Installer`, `ApkDownloader`), `src/play/java/.../update/` the Play updates (`UpdateManager`, `PlayUpdater`,
`InAppUpdates`, `PlayInAppUpdates`) and `src/play/AndroidManifest.xml` the permissions the Play build leaves out. Both
`UpdateManager`s implement `AppUpdates` (`src/main/.../update/AppUpdates.kt`); the rest of the app only sees that.
`Distribution` (`src/main/.../Distribution.kt`) exposes the flavor (`BuildConfig.DISTRIBUTION`, `ROOT_ALLOWED`).

```sh
cd android
./gradlew :frame:testGithubDebugUnitTest :frame:testPlayDebugUnitTest
./gradlew :frame:assembleGithubRelease      # frame/build/outputs/apk/github/release/frame-github-release.apk
./gradlew :frame:bundlePlayRelease          # frame/build/outputs/bundle/playRelease/frame-play-release.aab
./gradlew :frame:installPlayDebug           # try the Play build on a tablet (in-app updates need a Play install)
```

## Policy decisions

**No self-update.** Google Play's Device and Network Abuse policy forbids an app from Play updating itself by any
other means than Play, and `REQUEST_INSTALL_PACKAGES` is only allowed for apps whose core purpose is installing
packages. The Play build therefore has no `REQUEST_INSTALL_PACKAGES`, no `FileProvider` for update APKs, never calls
`GET /v1/device/update`, and contains none of the installer code (it lives in the `github` source set only; R8 also
drops `DeviceApi.checkUpdate`). Settings hide the release channel (stable/beta comes from Play's tracks), keep "Check
for update" (it asks Play) and add "Open in Play Store".

**Play in-app updates** (`com.google.android.play:app-update`, `PlayUpdater`), on the self-update's cadence and idle
rules (`UpdatePolicy`):

- Checks 5 minutes to 24 hours after start, then every 24 hours plus up to 90 minutes of jitter; on the owner's
  `sync_now`; when someone taps "Check for update" or reaches the setup wizard's update step; and after a
  `426 app_update_required` from any device call (at most once every 10 minutes).
- A scheduled or remote check that finds an update starts the **flexible** flow only once nobody touched the frame
  for 15 minutes and the screen wasn't switched off by hand. Play asks the person (a small sheet over the photos);
  after they accept, the download runs in the background and kinframe installs it (`completeUpdate`, Play restarts the
  app) at the next idle moment. Declined: asked again at the next scheduled check.
- After a `426`, and when the person asks on the frame, the **immediate** flow starts at once (flexible when Play
  only allows that): the frame can't talk to its server until it is updated.
- On a tablet without the Play Store or Play services, or when the app wasn't installed from Play, the in-app update
  API fails; kinframe logs it and does nothing (a check someone asked for says it couldn't check).
- No rollback: Play doesn't allow installing an older version. Play's staged rollouts (`frame_promote rollout:0.1`)
  are the safety net instead.
- Heartbeat `update_state` keeps its values: `needs_user` while Play's sheet waits, `downloading`, `waiting`
  (downloaded, waiting for an idle frame), `installing`, `updated`, `failed` (`last_update_error`:
  `play_flow_failed`, `play_install_failed <code>`, `play_complete_failed`).

**No root.** Play may flag apps that try to gain root. `RootShell` checks `BuildConfig.ROOT_ALLOWED` (false in the
Play build, a compile-time constant, so R8 removes the code that starts `su`): `isAvailable()` is false and `run()`
fails without starting a process. Every root feature already falls back when root is missing.

**Clock.** The Play build relies on Android's network time. `TimeSync` still asks NTP servers, but only reports a wrong
clock (`clockWrong` hides the time UI and says the clock is wrong, until the clock is plausible again); it never sets
the clock.

**Home app (launcher).** The `HOME` intent filter stays: a kiosk on a tablet works by the person choosing kinframe as
the home app. kinframe doesn't use device admin, lock task mode, accessibility services or "display over other apps".
The setup wizard ends with a step that explains this and opens Android's chooser (`RoleManager.ROLE_HOME` on Android
10+, else `Settings.ACTION_HOME_SETTINGS`, else Android's settings), shown only while kinframe is not the home app (so
converted frames never see it). Frame settings show the same card while it isn't. Package visibility uses a `<queries>`
entry for the `HOME` intent only; there is no `QUERY_ALL_PACKAGES`.

**Battery.** kinframe doesn't ask for a battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is
restricted on Play to apps whose core function breaks without it). It doesn't need one: the frame keeps the screen on
(`FLAG_KEEP_SCREEN_ON`) and stays in the foreground, so Doze and App Standby never start; sync runs over its
websocket in the app process. The setup step suggests keeping the tablet plugged in.

**Exact alarms.** `SCHEDULE_EXACT_ALARM` is left out (Play reserves exact alarms for alarm and calendar apps). The
sleep schedule re-evaluates every 30 seconds while the app runs and falls back to inexact alarms; with the screen
kept on that is on time to the minute.

**Plugins.** Widgets run as JavaScript in an embedded QuickJS interpreter with no direct access to Android APIs
(docs/plugins.md); community bundles must be signed by the plugin registry. Play's policy on downloaded code exempts
code that runs in an interpreter with indirect access to Android APIs; no dex, jar or native code is ever downloaded.

**Target SDK.** `targetSdk = 36` (Android 16), `compileSdk = 37`, `minSdk = 23`. Play requires new apps and updates to
target Android 15 (API 35) since 31 August 2025 and, from 31 August 2026, Android 16 (API 36): the build meets the
current requirement. Raise `targetSdk` to 37 before the next deadline (expected August 2027; check Play Console's
"Target API level" notice). The native libraries (QuickJS, Realm, androidx graphics) have 16 KB-aligned `LOAD`
segments, as Play requires for apps targeting Android 15+.

**No foreground services**, no notifications, no ads and no analytics SDKs. The only Google library is the in-app
update library (with Play services basement), in the Play build only.

## Permissions

Merged manifests, checked with `aapt2 dump permissions` (`frame-github-release.apk` is identical to the build before
the flavors existed):

| Permission | `github` | `play` | Why (Play build) |
|---|---|---|---|
| `INTERNET` | yes | yes | Talk to the kinframe server; plugins on the home network. |
| `ACCESS_NETWORK_STATE` | yes | yes | Online/offline status, captive portal detection. |
| `ACCESS_WIFI_STATE` | yes | yes | Wi-Fi name and signal for the status card and the owner's health view. |
| `CHANGE_WIFI_STATE` | yes | yes | The setup wizard's Wi-Fi step (scan, join) on tablets that aren't connected yet. Normal permission. |
| `RECEIVE_BOOT_COMPLETED` | yes | yes | Starts the frame after boot when kinframe is the home app (some firmwares start the launcher late). Nothing happens on a tablet where it isn't. |
| `WAKE_LOCK` | yes | yes | Normal permission; alarm wake-ups and media playback. |
| `READ_EXTERNAL_STORAGE` | yes | `maxSdkVersion 32` | Import from and export to an SD card or USB stick on Android 6–12 (asked at runtime when the person starts it). Android 13+ uses the system folder picker instead. |
| `WRITE_EXTERNAL_STORAGE` | `maxSdkVersion 29` | `maxSdkVersion 29` | Export to SD/USB on Android 6–10. |
| `ACCESS_COARSE_LOCATION` (`maxSdkVersion 28`) | yes | **removed** | Wi-Fi scan results on Android 6–9; only granted with adb on converted frames. A tablet is on Wi-Fi already. (Also avoids the implied `android.hardware.location` feature, which would hide the app from tablets without it.) |
| `SCHEDULE_EXACT_ALARM` | yes | **removed** | See "Exact alarms". |
| `WRITE_SETTINGS` | yes | **removed** | System brightness on converted frames (granted with adb). On a tablet the window brightness is enough and the system brightness stays the person's own. |
| `REQUEST_INSTALL_PACKAGES` | yes | **removed** | Self-update. Play updates the Play build. |
| `<provider …updates>` (`FileProvider`) | yes | **removed** | Shared update APKs with the system installer. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | yes | yes | Added by AndroidX for its own receivers; signature-level, not a user permission. |

Not used by either build: `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `USE_EXACT_ALARM`, fine or
background location, camera, microphone, contacts, accounts.

To check a build:

```sh
bt=$ANDROID_HOME/build-tools/37.0.0
$bt/aapt2 dump permissions frame/build/outputs/apk/play/release/frame-play-release.apk    # :frame:assemblePlayRelease
$bt/aapt2 dump permissions frame/build/outputs/apk/github/release/frame-github-release.apk
```

## Screen on and the sleep schedule without root

How a frame sleeps today (`schedule/DisplayController`): the activity always has `FLAG_KEEP_SCREEN_ON`. At a scheduled
sleep the photo fades to black (1.5 s) and the window brightness goes to 0 (`screenBrightness`), which Android maps to
the panel's lowest backlight. Only on a converted frame with root, and only while kinframe is the home app, it then
writes 0 to `/sys/class/backlight/*/brightness` so the backlight is really off; `WRITE_SETTINGS` (granted with adb)
also lets the brightness setting move the system brightness there. Touch wakes the frame; any brightness change
restores the backlight. Dimmed windows use the lowest brightness without going black.

On a tablet from Play the same happens without the root step:

- At night the screen is black at the lowest backlight, not off. On most LCD tablets a faint glow is visible in a
  dark room; OLED panels are effectively off. Power use is a fraction of normal but not zero: keep the tablet plugged
  in.
- Touch wakes the frame, as on a converted frame (a powered-off screen would need the power button, so kinframe never
  lets Android turn the screen off).
- The system brightness and its auto-brightness stay the person's own; kinframe only sets its window's brightness.
- The power button still turns the screen off; pressing it again brings the frame back, over the lock screen while
  kinframe is the home app. While another app is in front, that app's screen timeout applies.

## Heartbeat

The Play build adds `"play"` to `Heartbeat.features` (`FrameFeatures.PLAY`, `FrameFeatures.forDistribution`), so the
server and owners can tell which build a frame runs (`FrameHealth.last_heartbeat.features`). It is informational:
the server stores `features` as it does today and changes nothing; servers that don't know it ignore it. The GitHub
build sends exactly the list it sent before.

## Releasing the Play build

A `v*` tag releases the Play build through `.github/workflows/release.yml` ([release.md](release.md)), with the
secrets below as environment secrets. By hand:

The GitHub release workflow is unchanged in what it produces: same task output, same asset names
(`kinframe-frame-<name>-<code>.apk` and `.sha256`), same signing. For the Play build of the same tag:

```sh
cd android
bundle exec fastlane android frame_internal tag:v0.3.0     # AAB, uploaded to the internal testing track
bundle exec fastlane android frame_promote track:production rollout:0.1
bundle exec fastlane android frame_promote track:beta      # for vX.Y.Z-beta.N tags (open testing)
bundle exec fastlane android frame_metadata version_code:30099   # store listing from fastlane/frame-metadata/
```

`frame_build` derives the version name and code from the tag exactly as `release-frame.yml` does (default: the `v*`
tag on HEAD), so both builds of a tag carry the same version. A new app's releases stay in draft until Google has
reviewed it (`PLAY_RELEASE_STATUS=completed` once the app is live).

Environment (`~/.config/play/kinframe-signing.env`, read by the Fastfile; never in the repository):

| Variable | |
|---|---|
| `KINFRAME_FRAME_UPLOAD_KEYSTORE` | Path to the frame's **upload** keystore (not the release key). |
| `KINFRAME_FRAME_UPLOAD_KEYSTORE_PASSWORD` | Its store password. |
| `KINFRAME_FRAME_UPLOAD_KEY_ALIAS` | The key alias. |
| `KINFRAME_FRAME_UPLOAD_KEY_PASSWORD` | The key password. |
| `SUPPLY_JSON_KEY` | The Play service account's JSON key (shared with the family app; it needs Release manager on the frame app too). |
| `KINFRAME_SENTRY_DSN_FRAME` | The `frame-android` GlitchTip project's DSN (`docs/spec/crash-reporting.md`). Without it the build reports no crashes (the lane warns). The family lanes read `KINFRAME_SENTRY_DSN_FAMILY` (project `family-android`). |
| `SENTRY_AUTH_TOKEN` | A GlitchTip auth token allowed to upload debug files. With it (and `sentry-cli` on the PATH) the lanes upload R8's `mapping.txt` to GlitchTip after building; without it they warn and skip the upload. |
| `SENTRY_URL`, `SENTRY_ORG` | Optional; default `https://errors.kinframe.appmire.be` and `kinframe`. |

Without `KINFRAME_FRAME_UPLOAD_KEYSTORE` the Gradle build falls back to the debug key (local testing only); the lane
refuses to build.

The GitHub build (`release-frame.yml`) takes the same two from repository secrets: `KINFRAME_SENTRY_DSN_FRAME` and
`SENTRY_AUTH_TOKEN`, both optional. Play still gets the mapping too (`frame_internal`), for Android vitals.

The store listing (en-US, nl-NL: title, short and full description, release notes) is in
`android/fastlane/frame-metadata/android/`, separate from the family app's `fastlane/metadata/`. The graphics are
there too, per locale under `images/`, and `frame_metadata` uploads them with the texts: `icon.png` (512×512, the
launcher icon), `featureGraphic.png` (1024×500) and five landscape screenshots each in `tenInchScreenshots/`
(2560×1440: photo with caption, quick menu, pair, forecast, setup code) and `sevenInchScreenshots/` (1920×1080: the
welcome after claiming instead of the forecast, whose sheet needs the 10-inch width).
They match the family listing (`android/tools/storeshots/compose.mjs`): the same headline type, colours and framing.

To regenerate them (after a UI change, or to change a headline):

1. `./gradlew :frame:testGithubDebugUnitTest --tests app.kinframe.frame.StoreScreenshotTest` renders the real
   composables with Robolectric, in English and Dutch on a 10-inch (1280×800 dp, xhdpi) and a 7-inch (1024×600 dp,
   hdpi) screen, to `android/frame/build/screenshots/store/`. The photos are the CC0 / public-domain ones in
   `ios/tools/storeshots/photos/` (licences in `CREDITS.md` there); the people's names and captions are made up.
2. `npm ci` in `ios/tools/storeshots/` (once, for Playwright), then `node android/tools/storeshots/compose-frame.mjs`
   adds the headlines (in that script) and writes the images, the feature graphic and the icon. It flattens with
   ImageMagick, as Play wants no alpha channel in screenshots and the feature graphic.

## Play App Signing: handing over the release key

Google must sign the Play build with the key that signs the GitHub releases, or the two builds can't update each
other and converted frames (which pin the release certificate, `web/src/lib/convert/apk.ts`) would see a different
signer. Play App Signing lets you upload your existing key once, encrypted for Google, when you set up the app. Do
this before the first release: once Google has generated a key of its own for the app, the GitHub key can't be used.

1. Play Console → **Create app** (see below), then **Test and release → Setup → App signing** (or the prompt on the
   first release). Choose **Use a different key** → **Export and upload a key from Java keystore**.
2. Download `pepk.jar` and the **encryption public key** (`encryption_public_key.pem`) from that page.
3. On a trusted machine with the release keystore (the one behind the `ANDROID_KEYSTORE_B64` secret):

   ```sh
   java -jar pepk.jar --keystore=kinframe-release.jks --alias="$KINFRAME_KEY_ALIAS" \
     --output=kinframe-frame-signing-key.zip --include-cert --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   ```

   It asks for the store and key passwords. Upload `kinframe-frame-signing-key.zip` on the same page.
4. Create a separate **upload key** for the frame app (if it leaks, Google can reset it; the release key stays safe):

   ```sh
   keytool -genkeypair -v -keystore ~/.config/play/kinframe-frame-upload.jks -alias kinframe-frame-upload \
     -keyalg RSA -keysize 4096 -validity 10000
   keytool -export -rfc -keystore ~/.config/play/kinframe-frame-upload.jks -alias kinframe-frame-upload \
     -file kinframe-frame-upload.pem
   ```

   Upload `kinframe-frame-upload.pem` as the upload key certificate, and set the `KINFRAME_FRAME_UPLOAD_*` variables.
5. Check: **App signing key certificate** SHA-256 in Play Console must equal the release certificate's
   (`apksigner verify --print-certs kinframe-frame-<version>.apk` on a GitHub release asset).
6. Delete the zip and the `.pem` files; keep the keystores in a password manager or offline backup. Never commit a
   keystore, password, `pepk` output or the service account key.

## Play Console steps (by hand)

1. **Create app**: name "kinframe: Tablet Photo Frame" (or similar), default language English (United States), App,
   Free. The package name `app.kinframe.frame` is fixed by the first upload.
2. **App signing**: as above, before the first release.
3. **App content**:
   - Privacy policy: `https://kinframe.appmire.be/privacy`.
   - Ads: no ads.
   - App access: the frame needs no sign-in on the device. Tell reviewers: "Open the app, choose a language and Wi-Fi;
     the frame shows an 8-digit code. To see photos, claim it at https://kinframe.appmire.be with any account (or the
     kinframe family app) and send a photo." Optionally provide a test account.
   - Content rating questionnaire: category "Utility, productivity, communication or other"; no violence, sexual
     content, profanity, drugs or gambling. Users can share photos with people they invited (answer "yes" to users
     interacting/sharing content). Expected rating: Everyone / PEGI 3 (with "Users interact").
   - Target audience: 18 and over (the frame is set up by adults; it is not designed for children), so the Families
     policy does not apply.
   - News app: no. Government app: no. Financial features: none. Health: none.
   - **Data safety** (check against the current form; these are the facts):
     - Data is sent to the family's kinframe server (the hosted service at kinframe.appmire.be or their own), over
       HTTPS. No data is shared with third parties; no advertising or analytics.
     - Collected: **Photos and videos** (only when someone imports from an SD card or USB stick on the frame; optional,
       app functionality); **App info and performance → Crash logs / Diagnostics** (crash count, uptime, storage,
       update state in the heartbeat; app functionality); **Device or other IDs** (the frame's device id and secret
       generated at enrollment; app functionality, account management); **Other user-generated content** (hearts and
       quick replies sent from the frame; optional). The Wi-Fi network name and signal are part of the diagnostics.
     - Not collected: location, personal info, contacts, messages, audio, calendar from the device, web history.
     - Deletion: the owner removes the frame, or deletes their account, in the family app or on the website (see the
       privacy policy).
   - Permissions declarations: none needed (no restricted permissions in the Play build).
4. **Store listing**: from `fastlane/frame-metadata/android/` (`frame_metadata` lane, or paste it), plus the icon,
   feature graphic and at least two tablet screenshots. Category: Lifestyle (or Photography). Contact e-mail and
   website `https://kinframe.appmire.be`.
5. **Devices**: phones can run the app too; nothing to exclude. Play filters ABIs (ARM only) and Android 6.0+
   automatically.
6. **Testing**: upload with `frame_internal`, add testers to the internal track, install from Play on a tablet,
   check the home-app step and an in-app update (upload a second internal build with a higher version), then
   promote. In-app updates only work for an install from Play.
7. **Service account**: grant the existing fastlane service account (`SUPPLY_JSON_KEY`) access to the new app with
   Release manager rights (Users and permissions).
