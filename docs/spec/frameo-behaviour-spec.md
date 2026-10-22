# Frameo: Behavioural Functional Specification (clean-room)

Audience: kinframe implementers. This document describes **what the Frameo frame and phone apps do and how they
behave**, in our own words. It contains no source code, no copied identifiers beyond what is needed to name a
behaviour, no verbatim UI strings, no assets, and no secrets, keys, credentials, or authentication material.
Read it together with `frameo-public-features.md`, the public-sources inventory, which carries citations to Frameo's
own documentation. Where the two disagree, this document reflects what the shipping software actually does in the
versions below.

Analysed versions (behaviour may differ in other versions):
- **Frame app** 1.32.16 (Android 6+ frames). Reference device: 10" frame, 1280x800, Rockchip RK3126, 1 GB RAM,
  Android 6.0.1.
- **Phone app** (Android) 1.40.13.

Evidence markers:
- **[O]** observed in the shipping app's behaviour, configuration, or resources;
- **[D]** stated in Frameo's public documentation or release notes;
- **[I]** inferred, i.e. plausible but not traced end to end.

Terminology used here (ours, not Frameo's):
- *frame* = the photo frame device.
- *phone* = a phone running the companion app.
- *friend* = a person whose phone is paired with a frame. One friend can have several phones ("clients").
- *relay* = the always-on internet rendezvous servers.
- *mailbox* = the store-and-forward service for offline delivery.
- *subscription tiers*: **Free**, **Plus** (Frameo+), and **Premium**.

---

## Part A — System architecture as observed

### A.1 Components
1. **Frame app.** It is the device's home screen and kiosk. It handles the slideshow, gallery, settings, pairing,
   media reception, sleep/brightness/sensors, backup, calendar, weather, and self-updates. [O]
2. **Phone app.** It handles the account, the frame list, media selection and preparation, sending, sent history,
   reactions inbox, remote gallery management, cloud backup control, calendar setup, and subscriptions. [O]
3. **Relay network.** Regional rendezvous servers in five groups: US West, US East, Central Europe (with a London
   member), Singapore, and Hong Kong. Frames hold a persistent connection to one group; phones connect to frames
   *through* it by peer identity. Server traffic uses port 443. [O]
4. **REST backend.** Handles accounts, account-synced frame lists, frame authentication, pairing-code prefix
   reservation, the push gateway, the "what changed" poll endpoint, entitlements, online-backup metadata and
   storage URLs, remote alerts, a weather/location proxy, and app updates. [O]
5. **Mailbox system.** It is new in frame 1.32 and has three roles: authentication, a message mailbox, and an
   encrypted media blob store. It lets phones send to frames that are off or offline. [O frame side; see A.5]
6. **Push.** Phones receive pushes via the mobile OS push service (FCM on Android). Frames have **no** push
   channel; they poll instead (see A.6). [O]

### A.2 Identity
- Every device has a long-term **Curve25519 key pair**. Its **peer ID** is the 32-byte public key, written as 64 hex
  characters. [O]
- **Phone identity**: generated locally on first run and stored in app-private storage. It can be regenerated, for
  example on account switch or reset, which yields a new peer ID. [O]
- **Frame identity**: a **factory-provisioned licence** containing the frame's private key and an issuer-signed
  blob; the peer ID is derived from it. [O]
  - A frame without a valid licence stops at a "waiting for licence" screen and never connects. [O][D]
  - The licence is mirrored to several persistent locations, including a raw partition that survives app-data
    wipes, so a "clear data" reset keeps the identity. [O]
  - A full Android factory reset is a separate, heavier path. [O]
- **Frame certificate**: frames present an issuer-signed certificate. Phones carry a built-in allow-list of issuer
  public keys, presumably one per manufacturer or production batch, and drop peers whose issuer is unknown. [O]
  - kinframe note: an equivalent "device attestation by issuer key" is optional for an open system. A simpler
    trust-on-first-pair model may be preferable.
- A random **device UUID** per install or device is used for telemetry and REST headers. [O]

### A.3 Transport and encryption (described generally)
- **Phone↔frame sessions are end-to-end encrypted** between the two peer keys, even when relayed. [O]
  - The handshake follows the **CurveCP** family: Curve25519 key agreement, XSalsa20-Poly1305 boxes, nonce
    sequencing, and a server cookie step. [O library strings]
  - No TLS is used on this channel. [O]
  - A third-party client confirms the same over the LAN. [D third party]
- **Connection paths** (the phone tries both):
  - *Remote*: the phone asks the relay to connect it to frame X. [O]
  - *Local LAN*:
    - The frame opens a TCP listener on a random high port and advertises it by **mDNS/DNS-SD** under a Frameo
      service type. The service instance name is derived from the frame's peer ID. [O]
    - Phones browse for that service and, if they find the frame, open a direct connection. **The local path is
      preferred for sending** (faster, and saves mobile data). [O]
    - The frame re-publishes after Wi-Fi changes, with back-off from 30 s up to 1 h. [O]
  - The phone needs a "local network" permission on newer Android; it is requested during onboarding and can be
    skipped. [O]
- **Pairing authentication**: the short numeric code acts as a shared secret for an authenticated key exchange
  between phone and frame keys (password-authenticated, PAKE-style; the code is never sent in the clear). After
  that, both sides store each other's public keys permanently. [O lib states; D third party for PAKE detail]
  - Pairing is always done **via the relay**, not the LAN, by the phone app. [O]
- **Frame→phone pushes**: a payload is encrypted with a public-key box from the frame's key to the phone's key and
  posted to a push gateway, which delivers it via FCM. The Frameo backend cannot read it. [O]
- **Frame↔backend REST** (challenge-response): the server seals a challenge to the frame's public key; the frame
  opens it and exchanges it for a short-lived bearer token. The token is refreshed when less than 5 min of
  validity remains. [O]
- **Phone↔backend REST**: OpenID Connect accounts with email/password, plus token exchange for Google and Facebook
  sign-in. Unauthenticated calls use an app-level client token. [O]
- **Mailbox (offline delivery)**: a per-pair **double-ratchet** session, Signal-style. [O frame side]
  - Each media file is encrypted with a random per-file key and integrity digest; that key travels inside the
    ratchet-encrypted message.
  - The storage provider sees only ciphertext.
  - Frameo publicly claims E2EE with double-ratchet encryption. [D]
- **Cloud backups**: encrypted on the frame before upload, with a password/salt scheme. The user holds a
  word-based **recovery key** that Frameo does not have. [O][D]
- **TLS for REST on old Android**: the frame ships its own bundle of root certificates so that Android 6 devices
  keep working after public CA root changes. [O]

### A.4 Wire protocol (message level)
- **Framing** [O]:
  - Each application message = a 4-byte protocol-version field + a 4-byte message-type field + a protobuf body.
    Current protocol version is 18.
  - Messages above about 16 KB are split into numbered chunks of about 16 KB and reassembled in order; out-of-order
    delivery restarts reassembly.
  - The version a peer reports gates features:
    - large segments need version ≥4;
    - resumable transfers need ≥9;
    - online backup needs ≥10;
    - remote gallery features need ≥13.
- **Acknowledgements** [O]:
  - Requests that need confirmation carry an acknowledgement ID (a timestamp). The receiver answers with a receipt
    carrying that ID and an optional error code.
  - Error codes: unauthorized, server error, bad request, generic failure, missing permission, missing media item,
    failed sending media item, not found, declined, existing backup present, not ready for restore, limit reached.
- **Message families** (both apps share one schema; there are no frame-only messages) [O]:

| Family | Messages (our names) | Direction |
|---|---|---|
| Liveness/info | heartbeat; info request; frame info; client info | both / F→P / P→F |
| Media transfer | media header; media data segment; receipt; media metadata update (caption/crop); resume request + "bytes already held" reply | P→F (and F→P for remote downloads) |
| Pairing/invite | request a shareable pairing code; pairing code + time-to-live | P→F / F→P |
| Reactions | reaction on a media item | F→P |
| Push envelope | encrypted wrapper (nonce + ciphertext) for messages delivered via push | F→P |
| Permissions | permission request (one or several types, with reason: feature gate, restore, access expired) | P→F (frame shows prompt) |
| Remote gallery | list all media IDs (segmented), list all media metadata, fetch media (thumbnail at size W×H or full), change visibility (hide/unhide), delete, "display this now" | P→F with replies |
| Backup/restore | initiate backup, get/send backup password, backup status, back up now, initiate restore, restore (with password), restore status | both |
| Calendar | add calendar (ICS URL or provider tokens), sync now, remove, get statuses, statuses; send event invitation (optional photo) + accept/decline reply | P→F / F→P |
| Service refresh | "re-check service X now" (mailbox, subscription; frame also knows account pairings and backup) | P→F |
| Async setup | create mailbox session: request → reply with mailbox ID + ratchet public key | P→F (frame implements; Android phone 1.40.13 does not send it) |

- **Frame info contents** [O]:
  - frame name and location label;
  - screen width and height;
  - *this phone's* permission flags;
  - backup type (none / external / cloud);
  - state (normal / ready-for-restore);
  - API environment (prod / test);
  - mailbox availability;
  - **maximum decodable video width and height**.
  - On the frame side, receiving an info request also triggers re-sending of any unacknowledged reactions and
    event responses to that phone.
- **Client info contents** [O]: display name, account ID, and the ID of the friend's profile picture.
- **There is no message to read or change frame settings, reboot, or update the frame remotely.** Remote control is
  limited to: gallery listing, thumbnails, hide/unhide, delete, and show-now; backup; calendar; and permission
  requests. [O]

### A.5 How photos travel (end to end)
1. The user selects media and recipients on the phone. [O]
2. The phone prepares **one file per distinct recipient screen size** (see A.7). [O]
3. For each recipient that is **currently online** (any message received within the last 30 s):
   - Resume if possible: ask the frame how many bytes it already holds and wait up to 10 s for the answer.
   - Send the header: ID, size, extension, caption, focal point, scale mode, capture date, content type, and
     extras such as a video's poster image.
   - Stream segments with simple flow control.
   - Wait up to **120 s** for the receipt.
   [O]
4. The frame receives:
   - Before accepting a transfer, it checks that free space after the file would still be at least 128 MB, and
     refuses otherwise.
   - It writes the partial file to a staging area and deduplicates.
   - It moves the file into the media store, records metadata, and generates a thumbnail.
   - If needed, it runs on-device face detection to set the focal point.
   - It raises the "new photo" experience and sends the receipt.
   [O]
5. **Offline recipients** stay "remaining" and the phone retries [O]:
   - Immediately on the next app start.
   - In the background with linear back-off: attempt n waits n × 2 h. Optionally Wi-Fi-only.
   - During active sending, a foreground service holds a wake lock for up to 30 min, and a 5 s watchdog restarts it
     if it is killed.
6. **Mailbox path** (frame 1.32 side):
   - Message types: text, content (media reference + caption + capture time + type-specific placement data), and a
     transfer result. [O]
   - The frame persists pending items first, downloads up to 3 in parallel with retries, and verifies the
     checksum. [O]
   - Videos are decrypted while streaming, with a download-then-decrypt fallback. [O]
   - Once stored, the frame acknowledges (30 s timeout) and the server copy is deleted. [O]
   - Frameo's release notes (Aug 2026) advertise "send while the frame is off". [D]
   - **The Android phone app 1.40.13 we examined does not contain the mailbox client.** Its "delivers when online"
     behaviour is the phone-side retry queue. [O]
   - Treat full offline delivery as rolling out (possibly iOS-first or later builds). [I]
7. **Reactions back** [O]:
   - If the sender's phone is connected, the reaction goes directly.
   - Otherwise it goes through the push gateway (encrypted envelope → FCM → phone decrypts).
   - Retries: first after 30 s, then exponential back-off from 15 min up to 12 h.
   - If more than 1000 reactions are pending, they are discarded.

### A.6 Polling, liveness, and connection management
- **Frame → relay** [O]:
  - The connection is persistent and starts once setup is complete.
  - Reconnect back-off runs from roughly 30 s up to 10 min.
  - After a Wi-Fi change, the frame reconnects if 13 s or more have passed since the last attempt.
  - The relay group is chosen automatically by **nearest timezone offset** (circular distance); a hidden developer
    override exists.
  - On connect, the frame sends device properties such as model, firmware fingerprint, app version, Wi-Fi strength
    and malware findings.
- **Phone → relay** [O]:
  - The phone connects **only while the app is visible or a send is in progress**.
  - Its connect loop retries with back-off from 0 to 10 s.
  - It sends a heartbeat to each peer that has been silent for 10 s or more.
  - Each placed call times out after 9 s.
  - It sends an info request whenever the frame's name is unknown.
  - The relay group is chosen by timezone, with a country override for one region.
- **Frame "what changed" poll** [O]:
  - Every **5 min** (minimum spacing 30 s), the frame fetches a tiny list of (service, timestamp) pairs.
  - Services: account-synced pairings, cloud backup, subscription entitlement, and mailbox.
  - Only services whose timestamp moved get re-fetched.
  - Phones can short-circuit the wait with a "refresh service now" message.
- **Peer online** = last message within 30 s. Frames show friends' "last seen". [O]
- **Wi-Fi watchdog on frame** [O]:
  - Checks every 30–60 s. After 3 consecutive failures (no internet, not connected to the relay, and not behind a
    captive portal) it forces a reconnect.
  - Its own connectivity probe distinguishes "no internet" from "captive portal".
  - It also shows a separate "connected to Wi-Fi but not to the service" state.

### A.7 Image and video processing
- **Photos (phone side)** [O]:
  - Decode, applying orientation.
  - Scale so that the photo's **short edge equals the frame's long screen edge**, which lets the frame fill or crop
    in *either* orientation. **Never upscale**; a low-resolution warning is logged.
  - Re-encode as **WebP, quality 85**. Sources that are already small WebP files pass through unchanged.
  - HEIC and other formats are converted first.
  - The capture date from EXIF is sent as metadata. No other EXIF is sent.
  - Prepared files are cached per (media, screen size).
- **Focal point / scale mode** [O]:
  - The sender chooses *fill* (crop around a focal point) or *fit* (letterbox).
  - The focal point defaults to the photo centre, or to the **mean of detected face centres** (on-device face
    detection; minimum face size 5 % of the image).
  - The frame does the crop at display time. The original prepared file is kept, so the frame can re-crop on
    rotation.
  - The frame runs its own face detection when needed: on a ≤512 px downscale, nudged 5 % downward, with a 5 s
    timeout.
- **Videos (phone side)** [O]:
  - Transcode to **H.264, 30 fps, I-frame every 16 s**.
  - Bitrate = output width × height × 4 bits/s.
  - Dimensions are even and fit within min(screen, frame's maximum decodable size).
  - Audio: **AAC stereo 44.1 kHz 96 kbps**, or no audio if the sender unticks "include audio".
  - Rotation metadata is normalised.
  - The trim range is applied during transcode.
  - A poster image (WebP q85) is sent with the video.
- **Profile picture**: centre-cropped 256×256, WebP q80, sent to each frame as a special media type. [O]
- **Greetings**:
  - A templated overlay; a template is an HTML-like layout of decorative images positioned by relative
    percentages around the user photo.
  - The frame keeps the template package and renders the greeting natively, adapting it to any aspect ratio.
  - The phone also sends a rendered preview.
  - About 57 templates are bundled; seasonal sets are prioritised by date window and country.
  - Only one photo per greeting; no video.
  [O]
- **Local thumbnails**: the history list uses 480×480 WebP. The remote gallery requests thumbnails at the grid cell
  size. [O]
- **Google Cast (Plus)**: the phone runs a small local HTTP server and serves WebP q90 frames to a custom Cast
  receiver. [O]

### A.8 Storage layout and limits (frame)
- **Shared storage folder for the app** [O]:
  - received originals;
  - video thumbnails;
  - greeting templates;
  - friends' profile pictures;
  - cache;
  - restore staging.
  - Marked to be ignored by the system media scanner.
- **App-private storage** [O]:
  - the main object database (media, friends, clients, albums, schedules, reactions, backup bookkeeping, mailbox
    sessions);
  - a separate calendar database;
  - gallery thumbnails;
  - partial incoming transfers;
  - settings split into "backed-up user settings" and "device-only state".
- **Data model highlights** [O]:
  - *Media*: file, received time, capture time, source (sent / imported / PC-transferred), sender friend and
    client, visible/hidden, new flag, size, caption, sender-edited caption flag, and kind-specific data:
    - photo: focal point, dimensions, scale mode, rotation;
    - video: dimensions, duration, has-audio, rotation, poster;
    - greeting: template, focal point, scale mode.
  - *Friend*: ID, name, account ID, profile picture, archived flag, new flag, last seen, **six permission flags**,
    and one or more clients (peer ID, protocol version, push token, origin, last seen).
  - *Reaction*: media, type, ordering index, acknowledged flag, sent-via-push flag.
  - *Album*: name, created, ordered media list.
  - *Schedule*: type (sleep or calendar view), from/to times, weekday set, enabled, trashed.
- **Thresholds** [O]:
  - "Running out of space" warning below **256 MB** free, checked every 15 min with a 24 h snooze.
  - Below **128 MB** headroom the frame **refuses incoming media**.
  - The storage screen breaks usage down into photos, videos, unsupported files, and system.

### A.9 Hardware integration (frame)
- **Capability discovery through firmware properties** [O]. The device image declares:
  - whether it has a light sensor, a battery, deep-sleep support, and an RTC;
  - the *path* of the motion-sensor value file;
  - the *path* of the LED light-strip control file, whether the strip is single-colour, and how many effects it
    has;
  - whether USB PC-transfer is supported;
  - default language, timezone, brightness, video volume, notification volume and strip brightness;
  - model name, a regulatory ID, and a pixel-aspect correction for panels with non-square pixels.
  - **Settings appear only when the capability exists.**
  - kinframe should adopt an equivalent per-board capability file.
- **Brightness** [O]:
  - Set through the OS's system brightness, 0–255; the user range is **15–255**, default **204** (or the firmware
    default).
  - The per-window brightness is also set, with a 1 % floor.
  - No direct backlight sysfs writes.
- **Adaptive brightness** [O]:
  - Shown only with a light sensor; default off.
  - It simply enables the OS's automatic brightness mode; there is **no custom curve**.
  - The app watches the resulting value and stores it as the user brightness.
- **Motion (PIR) sensor** [O]:
  - The frame reads a one-byte value file every **300 ms**: 1 = presence, 0 = absence.
  - On presence it cancels any pending screen-off and wakes the screen, except during scheduled sleep or after a
    manual screen-off.
  - On absence it starts the user's timeout and then turns the screen off.
  - Timeout options: off, 2, 5, 15 and 30 min, 1, 2 and 4 h; default **30 min**.
- **Screen off/on** [O]:
  - Off = a black full-screen overlay fades in over **1.5 s**, then the OS "sleep" key event; the overlay closes
    after 5 s.
  - On = a wake lock plus the OS "wake-up" key event.
  - If the screen comes on with no known reason, the frame assumes the power button was pressed and counts it as
    user interaction.
- **LED light strip** (rare hardware) [O]:
  - Modes: adaptive (colour follows the photo on screen, using a cache of recent photo colours), fixed colour, or
    off; single-colour strips only have on/off.
  - Optional numbered effects; separate brightness (15–255).
  - Colours are normalised so the brightest channel is at full intensity.
  - A hardware key toggles the strip.
- **Battery** (if present) [O]:
  - Indicator in the quick menu.
  - Warning at ≤10 %; at ≤5 % a "will power off" warning.
  - A charging message is shown when plugged in.
- **Orientation** [O]: follows the accelerometer through the OS (auto-rotate between portrait and landscape).
  Rotation is locked during imports. Layouts and collage sets differ by orientation.
- **Power button** [O]:
  - Handled by the OS for sleep/wake.
  - The in-app power dialog offers power off, restart, or sleep.
  - The boot screen warns users to always switch off with the button and never pull power.
- **Audio** [O]:
  - Separate notification and video volumes.
  - The frame puts the OS into do-not-disturb to silence system sounds and suppresses the loud-volume warning.
  - A notification chime plays for new photos, PC transfers and received events.
- **Deep sleep / power saving** (only if supported; default on) [O]:
  - During sleep, Wi-Fi is switched off and OS idle mode is allowed.
  - It needs an RTC to wake on time; without Wi-Fi during sleep the time cannot be corrected.
  - A notification warns if the time could not update.
  - This was added for EU ErP energy-directive compliance. [D]

### A.10 Kiosk, boot, recovery (frame)
- **Home screen**: the app registers as the device launcher. On first start it uses **root** to [O]:
  - grant itself runtime permissions;
  - disable the system status/navigation UI and the stock launcher;
  - turn off OS power-idle mode for itself;
  - switch to manual brightness;
  - disable the OS captive-portal probe and automatic timezone (the app manages these itself);
  - allow sideloaded installs (for self-update);
  - enable network time.
- **Staying in front** [O]:
  - Every screen is immersive full-screen.
  - A foreground service runs with a persistent notification.
  - Wake and Wi-Fi locks are held while the UI is active.
  - If the process starts on anything other than the home screen, it restarts itself.
- **Boot sequence** [O]:
  - A splash screen waits for deferred initialisation.
  - For about 3 s within the first 2 min after boot it shows a **hidden recovery button**.
  - Then: factory-test mode if a provisioning config file is present on removable storage; otherwise "waiting for
    licence" if there is no licence; otherwise a "welcome <owner>" power notice, followed by the setup wizard or
    the slideshow.
- **Crash-loop protection** [O]:
  - Crashes within 5 min of the previous start are counted.
  - Above a threshold the frame assumes the current photo is to blame and **skips to another media item**.
  - Repeated failure shows a **critical-error screen** with an error code and the device ID. It offers: set up
    Wi-Fi, check for an update, back up to external storage, install an update from external storage, **hide all
    photos**, or reset.
- **Clean-vs-dirty shutdown accounting**, for telemetry. [O]
- **Self-reboot flag**: the frame sets a system flag that makes the app reboot the device once on its next start.
  It is used after maintenance and resets. [O]
- **Malware scrubbing** [O]:
  - On every start, the app checks for several known pre-installed malware families found in cheap Android
    hardware.
  - It removes the offending packages and files via root and (on old Android) blackholes about 150 command
    servers in the system hosts file.
  - It reports findings.
- **Factory/QA tools** [O]:
  - A hidden menu (tap the version number 5 times) and an SD-card-triggered provisioning mode.
  - Licence push/revoke, plus about 50 hardware self-tests: touch accuracy, orientation, brightness, audio, video
    decode up to 8K test files, motion sensor, light strip, deep-sleep alarm, root, root CAs, a malware/firmware
    sanity check, presence of forbidden hardware (camera/mic), unwanted apps, build properties, single boot slot,
    and more.
  - Results go to SD.
  - kinframe: an OEM self-test/provisioning tool is valuable.

### A.11 Update mechanism
- **Only the frame app updates itself.** Firmware/OS updates come from the OEM's separate updater. [O][D]
- **Check cadence** [O]:
  - Every **24 h**.
  - Never sooner than **15 min after the last touch**, with up to 90 min of random jitter when an interaction
    triggered the check.
  - Only after setup, and not while the user has switched the screen off.
- **Request and download** [O]:
  - The request carries version code, Android API level, channel (production or beta), device ID and peer ID.
  - The response carries the target version, minimum API level, download URL and rollout info.
  - There is a primary update host and a fallback host.
  - Downloads are resumable.
- **Validation and install** [O]:
  - The package name must match and the version must be higher (equal is allowed for manual installs).
  - A known signature-bypass package format is rejected.
  - With root, the update installs **silently** and the app restarts; without root, the OS installer is used.
  - A "frame updated" notification appears afterwards, with release notes.
- **Manual paths** [O]: "check for update" in About, update from an APK on external storage, and the update step
  during setup and recovery.
- **Beta program** [O]:
  - A switch in About, default off; joining restarts the frame and changes the update channel.
  - The ADB access switch is also in About. Older versions revealed it only after joining the beta. [D]
- **Remote alert config** [O]:
  - Fetched at most daily with jitter.
  - It can show an info, warning, or "stop" banner/dialog. On the phone, "stop" blocks app use (a kill switch or
    forced update).

### A.12 Telemetry
- **Frame** [O]:
  - Analytics, crash reporting and performance tracing, gated by "share anonymous analytics", which is **default
    on**.
  - A **daily status event** with friend/media counts, disk usage, Wi-Fi strength, relay group, hardware flags,
    uptime, boot counters, language, orientation, channel, model, and more.
  - Discrete events for settings changes, friend add/remove, media-received timing, time corrections, and Wi-Fi
    loss.
  - Device properties are sent to the relay on each connect.
- **Phone** [O]:
  - Firebase analytics, crash reporting and remote config.
  - A customer-data pipeline and marketing-automation SDK (in-app and push messaging).
  - Facebook SDK (login and install events), ad-attribution APIs, and a paywall-optimisation SDK.
  - Analytics opt-out is default **on** (i.e. sharing enabled); "personalised messages" and marketing e-mail are
    separate opt-ins.
- kinframe: privacy-first is a clear differentiator.

### A.13 Permissions requested
- **Frame** (system/priv-app with root) [O]:
  - network, Wi-Fi state and change, multicast;
  - storage, including "all files";
  - location (for Wi-Fi scanning);
  - wake lock, set timezone, write settings, change configuration (locale), notification policy, boot completed;
  - install packages, foreground service, phone state (device identifiers).
  - **No camera, microphone, or Bluetooth.** Cameras and microphones are treated as forbidden hardware in factory
    tests.
- **Phone** [O]:
  - internet, network state, Wi-Fi multicast and nearby devices (LAN discovery);
  - camera (take photo/video in app, QR scan);
  - photos/videos read, including the newer "selected photos only" mode;
  - notifications, foreground data-sync service, wake lock, boot completed, vibrate, biometric;
  - billing, and the ad-ID/attribution permissions pulled in by SDKs.

---

## Part B — Frame app: screens, flows, settings

### B.1 First-run setup wizard [O]
Seven steps with a page-dot indicator:
1. **Language**: a list of all supported languages in native names. A hidden path here enters **retail/demo
   mode**.
2. **Wi-Fi** (details in B.9):
   - Scanned list with signal strength; manual entry for a hidden network; password dialog.
   - Connection states shown: scanning, authenticating, connected, no internet, wrong password.
   - Captive-portal login through an in-app web view.
   - **Skippable**, with a warning that updates, received photos and automatic time will not work.
3. **Date & time**: automatic or manual; timezone.
4. **Update check**: offers to download and install a newer version, with progress and failure states. Recommended
   before continuing.
5. **Restore or new**: restore from a cloud backup or from external storage, or set up as new.
6. **Name your frame**: the owner's name and the frame location label (room), both required, max 40 characters.
7. **Get the app**:
   - Explains installing the phone app and creating an account.
   - Shows the **pairing code** (with QR) and how long it stays valid (12 h).
   - Help explains where to enter it in the app.
   - When the first phone pairs, a celebratory "<name> is connected" screen with animation appears.

**Other first-run behaviour**
- **Empty-frame auto-standby** [O]:
  - Applies when there is no media, backup or calendar, and no touch for **10 min**.
  - A warning appears near the start of the countdown.
  - A cancellable countdown dialog runs for the last 2 min.
  - Then the screen goes off (or the frame shuts down if no Wi-Fi is configured).
  - Purpose: avoid burn-in in shops and boxes.
- **Intro guide** (replayable from Help): five slides on adding friends via a code. [O]
- **"Run setup again"** (Help): repeats language, Wi-Fi, time and the get-the-app page, and keeps data. [O]

### B.2 Main screen: slideshow [O]
**Timing and transition**
- Default **30 s** per item; minimum **2 s**; maximum 24 h.
- Presets: 5 s, 15 s, 30 s, 1 min, 5 min, 10 min, 30 min, 1 h, 24 h, plus a custom non-linear slider whose step
  coarsens (1 s → 10 s → 1 min → 15 min → 1 h).
- Public docs say 1 s minimum. [D]
- Transition: **cross-fade of about 800 ms**. Manual swipe left/right navigates an infinite loop.
- Pinch-zoom and pan on the current photo.
- No Ken Burns effect observed.

**Order**
- Date received (default), date taken, or shuffle, each with a reverse toggle. The default is **newest first**.
- Shuffle is a seeded permutation that persists, so there are no repeats within a cycle. [I]

**Scaling and background**
- Global "fill frame" default **on** (crop around the focal point).
- Changing it asks whether to apply the new mode to all existing photos.
- When fitting, the bars are **blurred** copies of the photo (default), black, or a colour gradient taken from the
  photo's palette.
- Per-photo override in Adjust.

**Overlays (widgets)**
- Caption (on), sender profile photo (on), clock (on), weather (on), date (on).
- Calendar "hybrid" sidebar (off): side panel in landscape, bottom in portrait.
- Event countdowns (on, Premium): up to 3 stacked, refreshed every 15 min.
- The caption shows the sender and an "edited" mark if the sender changed it later.

**Video**
- Autoplay (on).
- Playback mode: loop until the timer ends / **play once and hold** (default) / play once and advance immediately.
- Video volume (slider; hidden if there is no audio output).
- Auto-mute after **10 min** without interaction (off by default).
- Controls auto-hide after 4 s.

**Collage mode** (off by default)
- 2–6 photos per screen. The default depends on screen size: under 9" → 2, 9–15" → 3, 15" and up → 4.
- Layouts are chosen to match each photo's orientation; separate sets exist for landscape and portrait frames.
- At most one video and one greeting per collage.
- Tapping a tile gives quick actions.

**New-media experience**
- An arrival card ("N new") opens a dedicated new-photos viewer with a counter badge.
- Per-item actions: react, adjust, add to album, hide, delete.
- An end screen offers "manage new photos" (the gallery with them preselected) or resume.
- Arrival of media or a new friend **wakes the screen** if it was in idle standby (not during scheduled sleep).

**Remote events surfaced on the slideshow**
- A friend asked to display a photo; if an album is playing and the photo is not in it, the frame asks before
  leaving album mode.
- "N photos deleted remotely by X" and "X changed your slideshow remotely" notices.

**Interaction rules**
- Any touch pauses automatic advancing.
- Scheduled actions (sleep, calendar view, background updates) are **deferred until 60 s after the last touch**.

**Empty states**
- No photos yet: an invitation to use the phone app, with an offline hint that SD/USB works.
- All photos hidden: its own explanation.

### B.3 Quick menu (tap on slideshow) [O]
- **Top bar**: Wi-Fi status (opens Wi-Fi settings), album selector (what the slideshow plays), and a power button
  with battery level on battery hardware.
- **Main actions**: add friend, settings, my calendar, my photos. A "new" badge marks new features.
- **Current-photo actions**: add to album, adjust, react, hide, delete.
- **Clock/weather**:
  - Tapping it opens a weather panel: location, today, 1-day and 4-day views, and morning/afternoon/evening/night
    periods.
  - Tapping the date opens the calendar.
- **Power dialog**: power off, restart, sleep.
- **Auto-hide timing**: the menu hides after **15 s**; other menu variants use 45 s and 120 s.
- **Settings and other screens return to the slideshow after 5 min idle.**
- **External storage inserted** → a prompt offering play from it, import, or back up.
- **Double-tap** on a photo opens reactions.

### B.4 Reactions (frame) [O]
- Four reactions with animations: heart/like, laugh, surprised, sad. They are picked from the quick menu or by
  double-tap.
- They cannot be used on imported media (there is no sender to notify).
- The newest reaction replaces the previous one; "none" removes it.
- Delivery: see A.5.7. If the sender was removed, nothing is sent.
- The protocol knows love, laugh, sad, surprised and none; the phone app has display text for a few more (happy,
  angry), probably for other platforms or future use. [O/I]

### B.5 Gallery ("My photos") and albums [O]
**Gallery**
- Thumbnail grid with fast scroll.
- Multi-select by long-press or button, drag-to-select with edge auto-scroll, and select all.
- **Sort**: date received, date taken, or file size; newest/oldest or biggest/smallest. Gallery sort does not
  change slideshow order.
- **Filter**: sender(s); type (photos/videos/greetings); source (sent from app / imported from external storage /
  transferred from computer); visibility (shown/hidden); reset.
- **Detail view**:
  - Swipe pager showing type, video duration, sender with avatar, date taken, date received, size and source.
  - Actions: show now on slideshow, add to / remove from album, adjust, hide/unhide (with undo snackbar), delete
    (permanent, with confirmation; there is no trash).
- **Empty states**: none on frame / all hidden / no filters selected / no match / empty album.

**Adjust**
- Drag to set the focal point, toggle fill/fit, rotate 90°.
- Edit the caption, **only for imported media**. Sent media captions can only be edited by the sender from the
  phone.
- Videos and greetings are mostly not adjustable (only imported videos can be).

**Albums**
- Create, rename, delete (photos stay), add/remove items, and "play in slideshow" (album mode).
- The album selector in the quick menu switches the album; exiting returns to all photos.
- A playing marker and counts are shown.

### B.6 Friends and pairing (frame) [O]
**Add friend**
- Requires the frame to be online.
- Shows the code **in groups of two digits**, a QR code holding a link that opens the phone app with the code, and
  the expiry time.
- A spinner runs while pairing is enabled (45 s timeout). An expired state offers refresh.

**Code mechanics**
- Numeric only.
- *Online*:
  - The frame obtains a server-reserved numeric prefix (lease from 2 min to 7 days) and appends at least 3 random
    digits.
  - The total is at least 7 digits; the phone accepts 7–20.
- *Server unreachable*: 12 random digits, or **14 during peak gifting dates in late December**.
- Lifetime = min(12 h, the server lease). The same code is reused until it expires.
- While the add-friend screen is open, the frame keeps its pairing window open in rolling 2-minute windows.
- A clock jump detected beyond the lifetime resets the code.

**My friends list**
- Avatar, name, last seen ("online", "N minutes/hours/days… ago", "not yet seen"), a new-friend badge, and an
  "allowed to invite" marker.

**Per-friend permissions** (each a switch)
- Allow inviting others (receive and share the frame's code).
- Allow seeing frame photos.
- Allow managing frame photos (hide/unhide/delete; turning it on implies "see", and turning "see" off clears
  "manage").
- Also present but only granted through prompts: manage backup, view the backup recovery key, and set up own
  calendars.
- Premium calendar entitlements are layered on top.

**Roles**
- There is **no owner/admin role** in the data model. Whoever is physically at the frame is in charge; Frameo
  documents the same. [O][D]

**Permission requests from phones**
- Shown as a frame notification that opens an allow/deny dialog, phrased per permission (see photos, manage photos,
  invite friends, back up, set up calendar).
- Reminds the user that permissions can be changed later.

**Remove friend**
- Confirmation, with an option to also delete every photo that friend sent.
- Also removes their synced calendars.
- Closes their connections.

**Account-synced pairings**
- Phones whose user account already has this frame are authorised automatically, refreshed via the 5-minute
  poll. This is what lets a user log in on a new phone and keep their frames.

### B.7 Settings tree (frame) — complete [O]
Two-pane layout: section list on the left, details on the right. Retail mode shows only Retail, Wi-Fi, Backup,
Help and About.

**1. My frame**
- Frame name (the owner name shown to friends; max 40) and frame location (room label).
- Language: the 36-language list.
- Weather:
  - Location: automatic (network/IP-derived city) or manual (country → region → city search).
  - Temperature unit: °C/°F, default by locale [I].
- Date and time:
  - Date and time are manual when there is no network.
  - Time zone: default from firmware, else system.
  - First day of week: locale default, overridable.
  - **24-hour format default on**.

**2. Manage photos**
- Storage bar: count, used/total, breakdown.
- My photos (gallery).
- Transfer from computer (if supported).
- Import from external storage.
- Export to external storage, with the last export time. Blocked while a backup runs.
- **Play from external storage**: default off. When on, SD/USB photos join the slideshow without being copied.

**3. My friends** — see B.6.

**4. Display**
- Adaptive brightness (light sensor only; off).
- Brightness (15–255; default 204).
- Sleep mode → schedule list (B.8).
- Calendar timed view → schedule list (Premium).
- Motion sensor timeout (sensor only; default 30 min).
- Power saving (deep-sleep hardware only; default on; enabling and disabling both explain the time-sync trade-off).
- Light strip (strip only):
  - Mode: adaptive/fixed/off, or on/off for single-colour strips.
  - Effect: none or 1..N.
  - Brightness (default 204 or firmware value).
  - Colour (fixed mode).
- Accessibility:
  - **Text size**: default / large (×1.15) / extra large (×1.3).
  - **Display size**: five density steps, default middle.

**5. Slideshow**
- Timer.
- Fill frame.
- Frame background (disabled while fill is on).
- Display order and reverse.
- Widgets: caption, profile photo, clock, weather, date, hybrid calendar, countdowns.
- Collage: enable, maximum photos.
- Video: volume, playback mode, autoplay, auto-mute.

**6. Calendar**
- List of synced calendars plus "add calendar". Adding shows a QR code that points the phone to its setup flow,
  since setup happens on the phone.
- View options:
  - Hybrid view.
  - Calendar view: 4 days / week / month.
  - Start on today (on).
  - Fade past events (on).
  - Week numbers.
  - Countdowns (Premium).
  - Timed view (Premium).
  - Date/time shortcuts.
- Per calendar:
  - Header showing name, last sync and inactive state.
  - Editable display name.
  - Event colour: 9 palettes, each with light and dark variants.
  - Sync now.
  - Remove, with confirmation.
- Local "received events" calendar:
  - Holds invitations accepted from friends.
  - Removing it deletes all received events.

**7. Wi-Fi** — see B.9.

**8. Notifications**
- Master switch (on).
- Notification volume.
- Per-type switches, all default on: storage space, backup, network, calendar sync, new photos, transfer from
  computer, new friends, software update, feature news, subscription status, date/time problems.

**9. Backup and restore** — see B.11.

**10. Help**
- Guide.
- Run setup again.
- Support info page:
  - About 40 diagnostic fields: app version, peer ID, brand/model, Android/firmware, serial, language, date/TZ,
    screen, max video size, MAC, connection details, RAM, free space, backup state, root, uptime, sleep, hardware
    flags, and so on.
  - The same data as a QR code, so support can read it from a photo.
- Privacy policy link.

**11. About**
- Brand/model.
- Peer ID.
- App version (tapping it 5 times opens the hidden system/factory menu).
- Android version.
- Check for update: last-check time, manual check, install from external storage, release notes.
- Beta program (off).
- ADB access (off, with a risk warning).
- Third-party attributions.
- Share anonymous analytics (**on**).

**Retail mode**
- Downloads a language-specific demo media pack.
- Reduced settings.
- Disallowed actions show a "not allowed in retail" notice.
- No notifications.
- Leaving retail mode requires a reset.

### B.8 Sleep schedules [O][D]
**Schedules**
- Any number of schedules. Each has a sleep time, a wake time, a weekday set (default all seven) and an enabled
  switch.
- Deleting a schedule shows an undo snackbar.
- A default schedule is created on first start: **23:00 → 07:00 every day**. [D for times; O for creation]
- The summary line shows the next sleep and wake.

**Engine**
- Computes the next boundary across all schedules and sets an exact OS alarm (or an in-process timer when the
  boundary is within 5 min).
- Ignores triggers more than 1 h late.
- Defers actions until 60 s after the last touch.

**While asleep**
- The screen is off via the overlay and key event (A.9).
- Touching the screen or pressing the power button wakes it.
- The motion sensor does not wake it during scheduled sleep.
- The frame **keeps receiving** photos while asleep, unless deep sleep has turned Wi-Fi off.

**Calendar timed view**
- Same schedule model; while active, the frame shows the full-screen calendar instead of photos.
- Default new schedule 07:00–09:00 [D]. Premium only.

### B.9 Wi-Fi [O][D]
**Status and details**
- Current network status: connecting, online, login required (captive portal), waiting.
- Signal shown in five words, from unusable to excellent.
- Network list with connect and forget.
- Details dialog: IP, MAC, DNS, gateway, BSSID, frequency, channel and width, link speed, security, internet
  yes/no, captive portal yes/no.
- "Reset Wi-Fi" removes all saved networks and restarts the frame.

**Security support**
- Open, WEP and WPA/WPA2-PSK, with protected-management-frames handled.
- No enterprise. WPA3 depends on the hardware.
- Mostly 2.4 GHz hardware.
- IPv4 only.

**Captive portals**
- Detected by the frame's own probe.
- Login happens in an in-app web view that closes when done.

**Scanning**
- Every 5–30 s while the picker is open; once an hour when idle.
- Signal is sampled every 30 s for telemetry.

### B.10 Import, export, PC transfer, external playback [O]
**Import** from SD card or USB stick
- Grid of found files, select all, and sort by date or name before importing.
- Progress shown, with success, cancel and failure states (no storage, no space).

**Supported files**
- Photos: JPEG, PNG, WebP; HEIC on Android 10+ frames.
- Videos: MP4 and 3GP.
- Progressive JPEGs trigger a "slow loading" warning.

**Video import rules**
- Hard reject:
  - bitrate above roughly 11 Mbps on low-RAM devices (≤550 MB) or above roughly 101 Mbps otherwise;
  - resolution above what the hardware decoder supports.
- Soft warnings, with "don't warn again":
  - duration ≤3 s or ≥5 min;
  - file ≥128 MB (shown as a share of storage);
  - bitrate above 15 Mbps on screens up to 1.5 MP, or above 50 Mbps;
  - resolution above about 1280×960 or 1920×1440 depending on the screen.

**Export**
- Copies all photos and profile pictures to an export folder on external storage.
- Mutually exclusive with a running backup.

**Transfer from computer**
- A switch exposes the frame over USB in PTP/MTP mode.
- Guide: connect, open the device, copy into its camera folder, eject.
- A directory watcher picks up new files and announces them with a notification and chime.
- Stats: photo, video and unsupported-file counts with sizes.
- Unsupported files can be listed and removed in bulk.
- Source label: "PC transferred".

**Play from external storage** — see B.7.2.

**External storage requirements** [D]: FAT32 media, microSD up to 32 GB.

### B.11 Backup and restore (frame) [O][D]
**Two kinds of backup**
- **External storage** (SD/USB):
  - Contains photos, friends and settings. The latest backup overwrites the previous one.
  - Automatic backup:
    - Change-driven; postponed while changes keep arriving, up to 10 times or 24 h after the first pending
      change (documentation says "within 30 h" [D]).
    - Retries with back-off from 5 min to 24 h, at most once per 15 min.
    - Shows the last successful time.
- **Cloud** (Plus):
  - Owned by a friend who has a subscription. There can be several cloud backups.
  - Setup from the frame: pick a friend → the friend accepts in the app. Setup from the phone: the frame shows an
    allow prompt.
  - Client-side encrypted.
  - **Recovery key = 5 words** from a 7776-word diceware list. It is shown on the frame (and phone) with a
    "keep it safe" warning.
  - Status values: latest time, scheduled, in progress, N pending changes, outdated.
  - Controls: automatic switch, back up now, delete (two-step; a local-only delete is offered when offline).
  - Incremental, based on file hash and modification time [O].
  - Contents: media, settings, metadata, captions, reactions; calendar data since 2026 [D].

**Restore**
- Only possible on a **freshly reset frame** during setup.
- From external storage: pick the backup folder. Friend connections from a *different* frame are not restored.
- From cloud:
  - The frame shows a code; the phone pairs, picks a backup, authorises restore to this frame's ID, and sends the
    password.
  - The frame is **usable while the rest downloads** in the background, with progress shown.
  - The restore can be stopped.
  - Thumbnails are regenerated rather than restored.
  - Restore refuses with "update required" if the backup is from a newer version.
  - While waiting, the frame shows a placeholder name and short ID.

**Reset options**
- **Clear frame data**: deletes media, friends, settings, Wi-Fi and the mailbox identity, then reboots. The
  device licence is kept.
- **Factory reset**: an OS-level wipe that also removes app updates.
- Both recommend a backup first.
- A hardware reset pinhole (hold 5 s) exists on many models [D].

### B.12 Calendar (frame) [O][D]
**Views**
- Full-screen calendar with 4-day, week and month views; a "today" button; week numbers.
- Event details: time range, sender, and a countdown toggle (Premium). Details auto-close after 5 min.
- The menu offers add friend, add calendar, sync now, guide, settings and power.

**Sources**
- ICS/iCal URL (with guides for Google, iCloud, Outlook and Yahoo links), CalDAV, Google Calendar API, Microsoft
  Graph, and the local received-events calendar.
- For provider accounts, the **phone performs OAuth and hands long-lived tokens to the frame**; the frame then
  syncs directly.

**Sync behaviour**
- Every **15 min**, with a window of ±30 days.
- Back-off up to 2 h on failure.
- Statuses: ok, failed, waiting for accept, in progress, inactive, tokens expired.

**Limits**
- Free: **1 synced calendar per frame**.
- Premium: unlimited, on up to 5 frames the subscriber grants.

**Received events** (Premium sender)
- Arrive as a notification; accept/decline is final.
- Accepted events go to the local calendar with a heart marker.
- The sender is told the outcome.

**Recurrence**
- Supports standard recurrence rules and exceptions.

### B.13 Weather [O]
- Fetched through Frameo's proxy (OpenWeather data) by latitude/longitude.
- Location is automatic (IP-based city) or manual search.
- Refresh:
  - Forecast: daily with jitter; retry back-off from 30 s to 24 h.
  - Location: refreshed with back-off from 5 min to 3 days.
- Display: small or large clock/date/weather widget, plus the weather panel (B.3).

### B.14 Notifications board (frame) [O]
**Board**
- Cards stack in a corner over the slideshow.
- Three styles (info, warning, error), sorted by priority, swipe to dismiss. Most snooze for 24 h.

**Card types**
- Storage low / full.
- No backup yet; backup failed or outdated (cloud or SD).
- Offline; online but not reaching the service; captive portal.
- Calendar sync failed or offered; permission requests.
- N new photos; N new friends; event received.
- PC-transfer results.
- App updated (with changelog) or update failed.
- Wrong date/time (checked 30 s after start); time not updated during deep sleep.
- Subscription granted / expired / "ask a friend to restore Premium".
- Feature news and feature introductions (collage, albums, calendar, Premium).
- Restore status.

### B.15 Subscription surfaces on the frame [O]
- A promo carousel lists Premium/Plus benefits with a QR code to subscribe in the app.
- **The frame never sells anything itself**; it asks friends to grant access.
- Frame-side gating applies only to frame-executed features: calendar countdowns, timed view, unlimited
  calendars. Send limits are enforced by the phone. [O/I]
- Losing an entitlement schedules clean-up of inaccessible data 30 days later. [O]

### B.16 Languages (frame) [O]
- **36 UI languages**, selectable in-app:
  - Bulgarian, Czech, Hungarian, Slovak, Croatian, Hebrew, Danish, Dutch, English (US), Finnish, French, German,
    Italian, Norwegian, Polish, Portuguese, Spanish, Swedish;
  - Korean, Simplified and Traditional Chinese, Japanese, Vietnamese;
  - Russian, Serbian (Cyrillic), Greek, Slovenian, Latvian, Lithuanian, Ukrainian, Turkish, Romanian;
  - Arabic, Hindi, Indonesian, Thai.
- RTL is supported for Arabic and Hebrew.
- Emoji rendering uses a bundled colour-emoji font for old Android.
- The firmware can set the default language.

---

## Part C — Phone app: screens and flows

### C.1 Start-up routing [O]
On launch, the app checks in this order:
1. A remote "stop" alert → a blocking screen.
2. A restorable session from the OS-level cross-device credential store → offer to restore.
3. The account token has become invalid → a re-login screen, with an option to switch account, which wipes local
   data.
4. No profile yet → welcome.
5. Onboarding unfinished → resume at the saved step.
6. Otherwise → the main gallery.

### C.2 Account model [O]
**Three states**: no profile → **guest** (local only; just a display name) → **full account**.

**Full account**
- Sign-in: email and password, or Google or Facebook.
- Sign-up:
  - Fields: first and last name, email, password with confirmation.
  - Required terms checkbox; optional marketing opt-in.
  - Email verification screen with resend and "wrong email?".
  - If account creation fails server-side, the app falls back to offering guest mode.
- Account benefits:
  - The **frame list is stored server-side**, so a new phone gets all frames back without codes.
  - Multi-device use.
  - Required for subscriptions and cloud backup.

**Guest-to-account conversion**
- Nudged once, after at least 1 send, at least 1 frame and 7 days of install.
- Converting keeps all connections.

**Profile**
- Display name (what frames show as the sender).
- Profile photo from camera or library, sent to all frames. A nudge repeats every 5 days or more until a photo is
  set or explicitly removed.

**Security and account management**
- Change password (via email reset).
- Log out, with a warning that local history and profile photo are lost.
- **Delete account**:
  - The user must type a confirmation word and tick an acknowledgement.
  - There is an extra warning if a subscription is active: store billing continues, and cloud backups are deleted.

**Support info**
- Copyable account ID, app version, device, OS, server group and peer ID.

### C.3 Onboarding [O]
**Order**
1. Welcome by name.
2. **Add your first frame**; can be deferred, with a hint about where the add icon lives.
3. "Is this your own frame?" (recorded for analytics only).
4. A connected celebration with animation and **chime**.
5. Notification permission (explains reactions and delivery updates).
6. Photo permission (explains privacy: nothing leaves the phone until you send).
7. Local-network permission (faster sends on the same Wi-Fi; skippable).
8. "Ready to send": the free-tier limit and the two-step howto.

Each step is persisted, so onboarding resumes where it stopped.

### C.4 Navigation [O]
- Bottom bar with five tabs: **Gallery | Calendar | Frames | History | Menu**.
- Menu covers the account, subscription, settings and help.
- The phone UI is portrait-locked except the full-screen media viewer.

### C.5 Adding a frame [O]
**Entry points**
- The add icon on the Gallery and Frames tabs, the "add new frame" row in the recipient list, and app links.

**Code entry**
- Digits only; non-digits are stripped.
- Valid length 7–20.
- An inline error appears for invalid codes.

**QR scan**
- Uses the platform scanner module, with a "preparing" state while it downloads.
- An error is shown if the QR is not a frame code.

**Help sheet**
- Animation showing where the code appears on the frame: the end of setup, or quick menu → add friend.

**Connecting**
- Requires internet. Pairing goes via the relay, retrying every 1 s for up to **30 s**.
- Failure text covers a wrong code, an expired code, or the frame being offline.

**After pairing**
- Waits up to 5 s for the frame's name (2 s if it is already known).
- If the frame is in "ready for restore" state, the restore flow starts.
- If logged in, the frame is added to the account's synced list.

**Invite others** (needs the "invite" permission)
- The phone asks the frame for a code (15 s timeout).
- It shows the code, a locally generated QR code, copy and share buttons, and the validity time.
- The share text includes an app link carrying the code.
- Without the permission, the phone offers to *request* it; the owner must allow it on the frame.

### C.6 Frames tab and frame details [O]
**Frames list**
- Each frame with its name and status: online, last seen N ago, not yet seen, or needs update.
- Empty state: ask the frame owner for a code.

**Frame details**
- Name and owner, status, frame ID.
- Cloud backup block, restore status, calendars, Premium access.
- "See frame photos", "invite friends".
- Overflow menu: info, give/remove Premium access, delete frame.
- Delete frame offers variants to also archive its cloud backup and/or remove Premium access.
- **Feature gating by frame version**: a "frame needs update" dialog tells the user how to update on the frame.

### C.7 Gallery tab (media picker) [O]
**Browsing**
- Device photos/videos grid with fast scroll and scroll-to-top.
- Album selector: all, device albums, folders, storage volumes; a file-manager picker.
- Media-type filter.
- Camera button: take photo / record video.

**Permissions**
- Handles limited ("selected photos") access with a banner to manage the selection.
- Denied state links to settings.

**Selection rules**
- Tap to select; multi-select mode with a count.
- **Photos per send: 10 (Free) / 100 (Plus/Premium).** Exceeding shows a notice plus an upsell.
- **One video per send; photos and videos cannot be mixed.**
- **Greeting mode**: exactly one photo.

**Badges and sharing in**
- Previously sent items show a "sent to <frame> / N frames" badge.
- Share-into-app from other apps:
  - Single image or video; multiple images (no multiple videos).
  - Over-limit shares are truncated with a notice.
  - Mixed types are rejected.

### C.8 Send flow [O]
**1. Adjust**
- A per-item editor with a thumbnail strip.
- Photo tabs: *Adjust* and *Caption*. Video tabs: *Trim* and *Caption*.
- **Adjust**:
  - Drag the focal point.
  - Toggle fill/fit.
  - **Preview both landscape and portrait** frame orientations.
  - Auto-adjust from face detection, with an "adjusted" badge.
- **Caption**:
  - Max **140 characters** with a counter.
  - "Apply to all", with an overwrite confirmation when items already have their own captions.
- **Trim**:
  - Range handles over a filmstrip, with a duration readout.
  - **Minimum 3 s; maximum 15 s (Free) / 120 s (Plus).** An upsell appears at the cap.
  - "Include audio" toggle.
  - An error is shown for corrupt or unsupported video.
- First-use tutorial sheets for adjust, caption and trim.
- Leaving with unsaved edits asks to discard; removing an item asks for confirmation.

**2. Recipients**
- Frames with checkboxes and online status.
- A newly paired frame shows "awaiting details".
- An "add frame" row.
- A dialog appears if a selected frame is too old for this media type.

**3. Send**
- A foreground notification shows progress ("frame x of y", %).
- Completion toast.
- For offline frames: a "will deliver when online" toast or dialog.

**Settings that affect sending**
- **Resend in background**: default on.
- **Wi-Fi only**: default off.

### C.9 History tab [O]
- Two sections: **Ongoing** (unsent or partial) and **Delivered**.
- **Each entry shows**:
  - thumbnails;
  - recipient frames;
  - caption(s);
  - % progress;
  - an offline banner ("N frames offline, will deliver");
  - **reaction counters per type** on each photo.
- **Actions**:
  - **Edit caption(s) after sending** (pushed to frames as a metadata update; frames show "edited").
  - Forward to other frames (handles missing local files).
  - Cancel delivery.
  - Remove from history.
  - Help.
- "Clear history" removes delivered entries only; photos stay on the frames.
- Calendar-event deliveries show accepted / declined / waiting.

### C.10 Reactions and notifications (phone) [O]
**Reactions**
- Arrive live or by push.
- One reaction per (frame, photo); newer replaces older, and "none" deletes it and withdraws the notification.
- Notification: a title with the emoji, "<name> reacted …"; tapping opens the history item. Grouped with a
  summary.

**Notification channels**
- Sending progress, reactions, cloud backup, permission requests from frames, general news, cast.

**Other notifications**
- A frame requests cloud backup setup or Premium access.
- An event was accepted or declined.
- A permission was granted.

**Push registration**
- The push token is registered with the backend together with the phone's peer ID, on token refresh and on each
  foreground (at least 60 s apart).

### C.11 Remote frame gallery (Plus + permission) [O]
**Header and filters**
- Frame name, status, and counts of photos, videos and hidden items.
- Filters: visible / hidden.

**Grid and actions**
- Thumbnails fetched from the frame; multi-select.
- Actions: **hide/unhide, delete (permanent on frame), show on frame now** (not for hidden items), share to other
  apps, forward to other frames, Google Cast.
- Full-screen viewer with caption and date. Remote video playback may be unavailable.

**Offline and feedback**
- When the frame is offline, the gallery uses a cache and shows a "couldn't refresh" banner.
- Result toasts.
- Up to 1000 IDs per bulk request [D third party].

### C.12 Calendar tab [O]
**Overview**
- Calendars per frame, with sync all, sync now, remove and status per calendar.

**Setup wizard** (bottom sheet)
1. Choose a frame.
2. Choose a provider:
   - Google (read-only scope);
   - Outlook (read scope);
   - iCloud (guide to publish a public link);
   - Yahoo (ICS guide);
   - other ICS URL (with a warning if it does not look like a calendar link).
3. Choose calendars.
4. Wait for the owner to allow it on the frame.
5. Done.

Error states: limit reached, connection issue, frame offline.

**Send event** (Premium)
- Fields: title (required), all-day, start/end, location, description, optional photo with placement.
- A preview shows it exactly as on the frame.
- Send to a frame; track the response in History.

**Countdowns and timed view** (Premium)
- Toggled per event; timed view is configured on the frame.

### C.13 Subscriptions [O][D]
**Tiers and prices**
- Plus and Premium, monthly or yearly, sold through the app store.
- Free trial and savings badges; prices come from the store.
- 2026 public prices: Plus $1.99/mo or $16.99/yr; Premium $7.99/mo or $69.99/yr. [D]

| Capability | Free | Plus | Premium |
|---|---|---|---|
| Frames | unlimited | unlimited | unlimited |
| Photos per send | 10 | 100 | 100 |
| Video length | 15 s | 2 min | 2 min |
| Remote see/manage frame photos | – | ✓ | ✓ |
| Cloud backup | – | up to 5 frames | up to 5 frames |
| Google Cast | – | ✓ | ✓ |
| Synced calendars | 1 per frame | 1 per frame | unlimited (5 frames) |
| Send events, countdowns, timed calendar view | – | – | ✓ |

**Entitlement model**
- A subscription belongs to the **phone user's account** and is *granted to up to 5 frames*.
- After a grant, the phone tells the frame to re-check its entitlement.
- Frames can request access from a subscribed friend.
- Cross-store subscriptions show "manage on the other store".
- A Premium → Plus downgrade takes effect at the end of the period.
- After purchase, a welcome flow covers choosing Premium frames and suggested first steps.
- The paywall is served through a third-party paywall SDK at about 7 trigger points: limits reached, remote-manage
  tapped, backup tapped, send-event tapped, upgrade buttons.

### C.14 Cloud backup (phone side) [O]
**Requirements**
- Account (not guest), subscription, frame protocol ≥10, and the "manage backup" permission.

**Setup**
- Create a backup record → the frame shows an accept prompt → the frame encrypts and uploads.
- The phone receives and stores the **recovery key** (shown once with copy; autocomplete against the word list
  when typed).
- Account-level limit on backups; backups are kept **2 months** after the subscription ends, with the deletion date
  shown.

**Status values**
- Scheduled, in progress, latest, failed (code), outdated (no success in over 5 days), missing subscription,
  frame needs update, managed by a friend.
- Inactive (archived) backups are listed under the account.

**Actions**
- Back up now, unlink (archive), delete, restore another frame, show recovery key (device authentication may be
  required [I]).

**Restore**
1. Prepare the frame: reset it and pick cloud restore during setup.
2. Scan or enter the frame code.
3. Select the backup.
4. Enter the recovery key if it is not stored.
5. Progress.
6. Done, with a suggestion to set up a new backup.

### C.15 Phone settings [O]
- **Profile**: display name.
- **Network**:
  - Resend in background (on).
  - Wi-Fi only (off).
  - Local-network discovery permission.
- **Notifications**: opens the OS settings.
- **Help**: help centre, support info, user-panel survey, video guide.
- **About**:
  - Beta program (store testing link), privacy, terms, OSS licences.
  - Share analytics (on, per device).
  - Personalised messages (on).
  - Marketing news (account-synced).
  - Social links.
- **Hidden developer options**: server-group override, API environment, push token display, greeting config, tier
  override.

### C.16 Other phone behaviours [O]
- **In-app review prompt**:
  - Once, after at least 3 sends, at least 1 frame and 7 days.
  - A pre-question first: satisfied → store review; not satisfied → feedback/help.
- **Updates**: in-app update via the store, plus a self-update path for non-store builds.
- **Languages**: at least 16 (Arabic, German, English, Spanish, French, Hindi, Indonesian, Italian, Japanese,
  Korean, Portuguese, Russian, Thai, Turkish, Vietnamese, Chinese). It follows the system locale; there is no
  in-app picker. The full store bundle may have more. [O lower bound]
- **Tasks/"chores" lists shown on frames**: only UI text exists on Android; likely upcoming. [O/I]

---

## Part D — Numbers and defaults (quick reference)

| Area | Value |
|---|---|
| Slideshow interval | default 30 s; min 2 s; max 24 h; presets 5 s–24 h |
| Transition | cross-fade ≈800 ms |
| Fill frame / background | on / blurred |
| Order | date received, newest first |
| Widgets default | caption, avatar, clock, weather, date on; hybrid off; countdowns on (Premium) |
| Video | autoplay on; play once; auto-mute off (10 min); controls hide 4 s |
| Collage | off; 2–6; default 2/3/4 by screen size |
| Quick menu hide / settings auto-close | 15 s / 5 min |
| Scheduled-action defer after touch | 60 s |
| Brightness | 15–255, default 204 |
| Adaptive brightness | off (OS curve) |
| Motion sensor | 300 ms poll; default 30 min; options off–4 h |
| Sleep default | 23:00–07:00 daily |
| Screen-off fade | 1.5 s |
| Empty-frame standby | 10 min idle, 2 min countdown |
| Storage warnings | <256 MB warn; <128 MB headroom refuses media |
| Battery warnings | ≤10 %, ≤5 % |
| Name/location length | 40 chars |
| Pairing code | numeric, ≥7 (server prefix + ≥3 random), 7–20 accepted; offline fallback 12 (14 in late December); ≤12 h |
| Pairing attempt (phone) | 1 s retry, 30 s total |
| Peer online | last message ≤30 s |
| Phone heartbeat | after 10 s silence; call timeout 9 s |
| Frame poll | 5 min (min 30 s) |
| Relay reconnect | ≈30 s → 10 min |
| Message chunk | ≈16 KB |
| Resume wait / receipt wait | 10 s / 120 s |
| Photo encode | WebP q85; short edge = screen long edge; no upscale |
| Video encode | H.264 30 fps, bitrate w·h·4, I-frame 16 s, AAC 96 kbps stereo |
| Video length | min 3 s; max 15 s Free / 120 s Plus |
| Photos per send | 10 Free / 100 Plus |
| Caption | 140 chars |
| Profile picture | 256×256 |
| Background resend | n × 2 h; default on; Wi-Fi-only off |
| Send service | wake lock ≤30 min; watchdog 5 s |
| Reaction retry | 30 s, then 15 min → 12 h; cap 1000 |
| Calendar | sync 15 min; ±30 days; Free 1 per frame |
| Countdowns | max 3 in slideshow; refresh 15 min |
| Cloud backup | recovery key 5 words; outdated after 5 days; kept 2 months post-subscription; max 5 frames |
| SD auto backup | change-driven, ≤24 h after first change (docs: 30 h); retry 5 min → 24 h |
| App update check | 24 h, ≥15 min idle, jitter ≤90 min |
| Remote alert config | daily with ≤1 h jitter (frame); each foreground ≥60 s apart (phone) |
| NTP | 9 s timeout, 6 attempts, back-off 2 s → 1 h; clock-jump check 5 min |
| Wi-Fi watchdog | 30–60 s, 3 strikes |
| Import video | warn ≤3 s / ≥5 min / ≥128 MB / >15–50 Mbps; reject >11 or >101 Mbps |
| Languages | frame 36; phone ≥16 |

---

## Part E — Polish details

**Animations and transitions**
- Slideshow cross-fade.
- Black fade to sleep.
- Menus that open downward and close upward (slight slide plus fade).
- Slide-in/out screen transitions; shared-element transitions from grid to full screen on the phone.
- Vector (Lottie-style) animations for:
  - each reaction;
  - the "friend connected" moment (with a chime on the phone);
  - new-photos arrival;
  - "ready to send";
  - "where to find the code";
  - send-flow tutorials.
- An animated page-dot indicator in the frame's setup and intros.
- A cycling cross-fade illustration in the intro guide.

**Empty states**
- Every list has a titled empty state with a next step:
  - Frame: no photos, all hidden, filters empty, empty album.
  - Phone: no frames, no history, no backups, no calendars, empty folder.
  - Remote gallery: none, all hidden, no hidden.

**Error handling**
- Consistent dialogs: title, explanation, retry, and a help-centre link.
- Specific frame-offline dialogs for each remote action.
- Version-too-old dialogs that explain how to update.
- The frame shows "unable to show photo" or "unable to play video — resend" placeholders instead of crashing.
- Crash-loop skipping, and a recovery screen that can hide all photos.

**Undo**
- Snackbars with undo for hide and for deleting a sleep schedule.

**Offline behaviour**
- Frame:
  - fully usable offline with SD/USB and PC transfer;
  - clear offline / not-reaching-service / captive-portal cards;
  - pairing explicitly needs the internet.
- Phone:
  - queues sends and retries automatically;
  - shows offline banners and last-seen times;
  - remote gallery falls back to cache.

**Time robustness**
- The frame treats the clock as untrusted until network time succeeds (later than build dates, no jumps).
- Time-sensitive features wait for a trusted clock: pairing-code lifetimes, mailbox identity, clean-up timers.

**Guided discovery**
- Feature-introduction carousels (albums, collage, calendar, Premium).
- One-time tooltips and tutorial sheets.
- Nudges are rate-limited (profile photo every 5 days; guest conversion once; review once).

**Accessibility**
- Frame: app-level text size (3 steps) and display size (5 steps), but very few screen-reader labels.
- Phone: moderate content descriptions, day/night themes and haptics.
- Neither is strong for screen-reader users.

**Localisation**
- Frame 36 languages with RTL and bundled emoji.
- Phone 16+ languages.
- Greeting templates localised and region- and season-aware.

**Diagnostics UX**
- Support pages on both sides show copyable data, and the frame shows a QR code of its diagnostics so non-technical
  users can just photograph the screen.

---

## Part F — Gaps, weaknesses, and "and more" ideas for kinframe

1. **Remote settings.** No phone-side control of frame settings (interval, brightness, sleep, order). kinframe
   could offer full remote configuration with permissions.
2. **Owner/admin role.** Frameo has none; anyone at the frame decides. kinframe could add an optional owner and
   delegated admins, which helps for elderly relatives' frames.
3. **Send limits.** 10 photos and 15 s video on the free tier; no mixed photo+video; a single video per send.
4. **Album sync and automation.** No auto-sync from shared albums (Google Photos, iCloud, Immich, Nextcloud), no
   scheduled or "on this day" playback, and no playlist pushed from the phone (albums are frame-local only).
5. **Offline delivery.** Store-and-forward is only arriving now, and the phone app examined still relies on its own
   retry queue. Pairing requires vendor servers. kinframe: self-hostable relay/mailbox and a LAN-only mode.
6. **Local API.** None for home automation (screen on/off, next photo, show photo, brightness). Hobbyists resort to
   ADB.
7. **Accessibility.** Weak screen-reader support on the frame. The phone is portrait-only, with no tablet layout.
8. **Captions.** Plain text, 140 characters. No voice notes, no reply threads; reactions are one-way (no text
   reply).
9. **Telemetry.** Heavy, and default-on on both sides. Many marketing SDKs on the phone.
10. **Deletion.** No trash or undo on the frame.
11. **Hardware assumptions.** 2.4 GHz only, IPv4 only, no WPA3/enterprise, FAT32 SD up to 32 GB, no macOS for
    USB transfer.
12. **Root and OEM firmware.** Relies on OEM root and the stock OS; the firmware is often insecure. kinframe
    could target a hardened image, or run unprivileged with a device-owner/kiosk API.
13. **Brightness.** Auto-brightness just uses the OS curve, with no custom curve or night dimming schedule. Motion
    and sleep are basic (no "dim instead of off", no presence-based wake during sleep with quiet hours).
14. **Ideas.**
    - A web/desktop sender.
    - Family shared inbox.
    - Moderation queue ("approve before showing").
    - Per-sender or per-album schedules.
    - Duplicate detection across senders.
    - Photo-of-the-day for remote relatives.
    - Two-way reactions with short text.
    - An export/backup to user-owned storage (NAS, S3) without a subscription.

---

## Provenance and limits of this document
- **Frame app**: behaviour derived from the shipping app obtained from the user's own device. **Phone app**:
  behaviour derived from the store build. Public docs were used to cross-check. Raw analysis is held outside this
  repository.
- **Not determined**:
  - exact relay keepalive and port details inside the native networking library;
  - the precise "play once" end-of-video timing;
  - weather refresh cadence beyond "daily";
  - the internals of deep-sleep power saving;
  - the mailbox client on phones (absent from the Android build examined).
- Behaviour may change in later versions (frame 1.33 is announced).
