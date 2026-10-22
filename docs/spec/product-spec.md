# kinframe product specification

Status: **draft v0.1** (2026-10-22). Implementers work from this document, `docs/architecture.md` and
`docs/hardware/`. Frameo behaviour referenced here comes only from the clean-room specs `frameo-public-features.md`
(public sources) and `frameo-behaviour-spec.md` (behavioural analysis). See `docs/clean-room.md`.

## 1. Vision

kinframe lets families send photos and videos to digital photo frames. The frames run open-source software and talk to
a server the family controls. It matches Frameo's ease of use (a code on the frame, a share sheet on the phone, photos
appear in seconds) without paywalls, vendor lock-in, telemetry or fragile frames, and adds what Frameo users ask for:
remote management, web upload, album sync, an owner role and home-automation hooks.

**Principles**

1. **Grandparent-proof frame.** Nobody at the frame ever has to do anything after setup. Everything can be managed
   remotely by the people allowed to.
2. **Family-owned.** Self-hosted server, Logto accounts, no third-party analytics, no cloud dependency beyond what the
   family chooses (weather, push).
3. **Never lose a photo.** The server is the source of truth and the frame is a cache. A dead frame restores
   completely. Power loss never corrupts anything.
4. **Polish.** Smooth transitions, thoughtful empty states, clear errors with a next step, undo instead of "are you
   sure", and a calm, warm visual language.
5. **Runs on weak hardware.** The reference frame is an RK3126 with 1 GB RAM on Android 6 (`docs/hardware/denver-pff-1024.md`).

## 2. Personas

| Persona | Typical person | Needs |
|---|---|---|
| **Viewer** | Grandmother with the frame in her living room | Photos just appear. Can react with a tap. Never sees a settings screen unless she wants to. |
| **Owner** | Adult child who set up and gifted the frame | Manages members, settings, sleep schedule and storage remotely. Gets alerted when the frame goes offline. |
| **Member** | Family and friends | Send photos and videos from phone or web, see reactions, see what's on the frame. |
| **Operator** | Whoever runs the server (here: the user) | Easy self-hosting, backups, user management, updates. |

## 3. Concepts and terminology

- **Frame**: a device running the kinframe frame app. It has a name ("Oma's frame") and a location label ("Living
  room").
- **Account**: a person, authenticated by Logto. Frames are not accounts. They hold a revocable device credential
  issued at enrollment (`docs/architecture.md`).
- **Membership**: links an account to a frame with a **role** and **permissions**.
  - `owner`: exactly one per frame. Everything, including transferring ownership and removing the frame.
  - `admin`: everything except ownership transfer and frame removal.
  - `member`: sends media plus a set of granular permissions chosen by the owner or an admin (see 4.2).
- **Frame code**: a short-lived numeric code plus QR shown on the frame. Entering it while signed in either **claims**
  an unowned frame (you become owner) or **joins** an owned frame as a member.
- **Invite link**: created in the app or web by anyone with the *invite* permission. Lets someone join without being
  at the frame.
- **Item**: a photo or video on a frame, with a sender, caption, capture time, focal point and fit mode.
- **Album**: a named, ordered set of items. Albums live on the server and can be shared across a member's frames.
- **Rendition**: a frame-sized version of an item made by the server, plus thumbnails.

## 4. Feature specification

Phase key: **M** = MVP (v0.1, first end-to-end loop), **P** = parity (v0.5, everything Frameo does that we keep),
**+** = beyond Frameo (v1.0 and later, in priority order), **—** = deliberately not built.

### 4.1 Accounts and onboarding (phone, web)

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Server choice | Fixed vendor servers | The welcome screen offers the build's default server or **"Use your own server"** (enter a URL, or scan the QR code on the server's website). The app validates the URL by fetching `/.well-known/kinframe`, then shows the server's name and version before sign-in. It can be changed later in Settings → Server, which signs out. Deep links (`https://<server>/f/<code>`, invite links) switch to that server after asking the user. | M |
| Sign-in | Optional account (guest mode). Email, Google, Facebook, Apple. | Logto account required. Methods are configured by the operator: email one-time code (default, no password to forget), passkeys, Google, Apple. Accounts give multi-device use and recovery for free. | M |
| Profile | Display name and photo (256×256), sent to frames | Same. Display name and avatar shown on the frame next to captions. | M |
| Onboarding | Welcome → add frame → permissions → ready to send, resumable | Same flow, persisted per step. Permission prompts explain why: notifications for reactions and delivery, photos for picking. | P |
| Delete account | In app, with typed confirmation | Same. The user chooses whether their sent items stay on frames or are removed. | P |

### 4.2 Frames, roles and permissions

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Claim a new frame | No owner concept | The frame shows a frame code and QR. The first signed-in user to redeem it becomes **owner**. | M |
| Join via frame code | 12 h numeric code, digits or QR | Same: 8 digits shown as `1234 5678`, plus a QR deep link `https://<server>/f/<code>`. Valid 12 h, regenerable, rate-limited on the server. The frame must be online. | M |
| Invite link | A friend with "share code" permission can share the frame's code | Members with *invite* create invite links in app or web (single-use or multi-use, with expiry). Joining needs approval by owner or admin unless they set "auto-accept". | P |
| Roles | None: whoever is at the frame decides | owner / admin / member. Ownership transfer is possible. | M |
| Member permissions | 3 user-visible toggles (share code, see photos, manage photos) plus prompt-only ones | *send* (default on), *invite*, *see library*, *manage library* (hide, delete, show now, edit any caption), *change settings*. Defaults are configurable per frame. | P |
| Approve on frame | Every remote capability needs a tap on the frame | Optional per-frame setting, **"Ask on the frame before someone new joins"** (off by default). Remote management never needs anyone at the frame. | + |
| Remove member | From frame settings, optionally deleting their photos | From app or web (owner/admin) and from the frame. Option to also delete their items. | P |
| Friend list on frame | Name, avatar, last seen | Same, read-only by default. Removing people from the frame is allowed unless the owner disables it. | P |

### 4.3 Sending media (phone, web)

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Pick media | In-app gallery, share sheet, camera | Same. **Android/iOS share target** (images and videos, mixed), system photo picker (no full-library permission needed), camera capture. | M |
| Per send | 10 photos (Free) / 100 (Plus). One video. No mixing. | **No paywall.** Default limit 200 items per send, set by the operator. Photos and videos can be mixed. | M |
| Video length | 15 s / 2 min | Default max **5 min**, set by the operator. Trimming in the app (min 3 s). "Include audio" toggle. | P |
| Recipients | Several frames per send | Same, with online/offline state and last seen. | M |
| Caption | 140 characters, one per photo or "apply to all" | **280 characters**, per item or apply to all. Emoji supported (the frame bundles an emoji font, since Android 6 has old emoji). | M |
| Adjust | Fill/fit, focal point by drag, landscape and portrait preview, face auto-centre | Same. Face auto-centre is computed on the **server** (on-device in the app is optional), so the web gets it too. | P |
| Web upload | None | Drag and drop, folder upload, paste from clipboard. Resumable uploads. | M |
| Delivery | Peer-to-peer, retry queue on the phone (n × 2 h) | Upload to the server once and the server delivers to every frame. The phone can close right after upload. Frames get pushed instantly when online and catch up when they come back. Per frame and item: *uploaded → delivered → seen*. | M |
| Sent history | Ongoing/delivered, % progress, reactions per item, edit caption, forward, cancel | Same, plus *seen* state. History syncs across the sender's devices and web. | P |
| Edit after sending | Caption only, sender's phone only | Caption, focal point and fit, from any of the sender's devices. Frames show "edited". | P |
| Unsend | Not possible on the free tier | The sender can always remove their own items from frames. | P |
| Duplicate guard | "Already sent" badge | Server-side duplicate detection (exact and perceptual hash), so the same photo isn't sent twice to a frame by different senders. | + |
| Greetings | Themed overlay templates | — for now. Possible later as user-contributed SVG themes. | — |
| Email to frame | None | Each frame gets an optional secret inbound address. Attachments from approved senders become items. | + |

### 4.4 Frame: slideshow and display

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Timer | 2 s–24 h, default 30 s, presets 5 s–24 h | Same range and presets, default 30 s. | M |
| Transition | Cross-fade about 800 ms | Cross-fade 800 ms (default). **Ken Burns** pan/zoom and **slide** as options, within the GLES 2.0 budget. | M / + |
| Order | Received, taken or shuffle, plus reverse. Shuffle shows the received date. | Received, taken or shuffle, plus reverse. Shuffle has no repeats per cycle and is **weighted toward recent items**. The caption date can be set independently (taken or received). | M |
| Fill/fit | Global default plus per item. Bars are blurred, black or gradient. | Same. Default fill, blurred bars. | M |
| Portrait on landscape | Narrow or awkward crop | **Smart pairing**: two portrait photos side by side on a landscape frame (and vice versa), automatically, when it looks better than cropping. | + |
| Collage | 2–6 per screen, layouts by orientation | Same. | P |
| Widgets | Caption, avatar, clock, date, weather, hybrid calendar | Same, each on/off. Caption shows sender, relative date and "edited". | M (caption, clock) / P (rest) |
| Video | Autoplay, play once and hold / loop / advance, volume, auto-mute | Same options and defaults (autoplay on, play once and hold). Volume keys work. | P |
| Pinch zoom, swipe | Yes | Same. | M (swipe) / P (zoom) |
| Album mode | Play one album or all | Same, plus play albums **on a schedule** (e.g. the "Christmas" album in December) and **"On this day"** memories. | P / + |
| New-photo experience | "N new" card → new-photo viewer → react/adjust/album → manage | Same flow. New items play first, then return to normal order. The screen wakes on arrival when idle (not during scheduled sleep). | M |
| Photo info | Detail view | Tap → info sheet: sender, caption, date taken, date received, location name (opt-in). | P |

### 4.5 Frame: interaction and quick menu

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Quick menu | Tap: Wi-Fi status, album, power, calendar, photos, add friend, settings, photo actions | Same structure. Auto-hides after 15 s. Settings return to the slideshow after 5 min idle. | M |
| Reactions | 4 reactions, double-tap, sent to sender by push | Same 4 (love, laugh, wow, sad) plus "none". Double-tap = love. Animated. Sender gets a push. | M |
| Quick replies | None | Pick from a few preset messages ("Beautiful!", "Call me", "Miss you") to send back to the sender. No keyboard needed. | + |
| Hide / delete | Hide, permanent delete (no trash) | Hide, delete with **undo snackbar**, and a server-side **trash for 30 days**. | M |
| Scheduled actions | Deferred until 60 s after last touch | Same. | M |
| Accessibility | Text size 3 steps, display size 5 steps | Same, plus high-contrast captions and larger touch targets for the quick menu. | P |

### 4.6 Frame: library and albums

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Gallery | Grid, multi-select, sort, filter by sender/type/source/visibility | Same on the frame. The same library is also browsable in app and web for members with *see library*. | P |
| Albums | Frame-local only | **Server-side albums**, editable from frame, app and web. An album can be shown on several frames. | P |
| Storage | Warn below 256 MB, refuse below 128 MB, no auto-delete | The frame cache is managed automatically. Least-recently-shown items are evicted from the frame when space runs low and re-downloaded on demand. The owner sees storage in the app. Nothing is lost because the server keeps everything. | P |
| Share to another frame | None | From a frame's library (single or multi-select) or the sent history: **"Share to another frame"** puts the same photos on other frames you can send to, without uploading again (the server reuses the stored media). Needs *see library* on the source (or being the sender) and *send* on the target; the target's moderation applies and frames that already have it are skipped. The original sender keeps the credit and hears about reactions; frames show "Anna · shared by Ben". Undo, the target's owner/admins, the sharer and the original sender's unsend can remove it again. | + |

### 4.7 Frame: power, sleep, brightness, presence

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Sleep schedules | Multiple, per weekday, default 23:00–07:00 | Same, editable remotely. | M |
| Sleep behaviour | Black fade 1.5 s, then OS sleep. Still receives photos. | Same. Option to **dim** instead of turning off. | M |
| Brightness | 15–255, default 204. Adaptive only with a light sensor. | Same range and default. **Brightness schedule** (e.g. dimmer in the evening), or **sun-based** brightness from the frame's location, since most frames have no light sensor. Light sensor used when present. | M / P |
| Motion sensor | 300 ms poll, timeout 30 min (when hardware exists) | Same when present (per-board capability file). | P |
| Presence via home automation | None | **Wake/sleep and brightness from Home Assistant** (MQTT or webhook) using any PIR, phone-presence or room sensor. | + |
| Power off | OS shutdown; "safe to unplug" never shown | **Shutting down → flush → read-only remount → "It's now safe to unplug"** (see `kinframe-additions.md`). Crash-safe writes, so unplugging anytime is harmless anyway. | M |
| Power menu | Power off, restart, sleep | Same. | M |
| Remote control | None | Owner/admin: sleep now, wake, restart, show item now, next/previous, change any setting. | P |

### 4.8 Frame: connectivity and setup

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Setup wizard | Language → Wi-Fi → time → update → restore/new → name → get the app | Language → Wi-Fi → time zone → update → **frame code to claim** (QR plus digits; the owner names the frame from the app) → done. Restore needs no extra step because the server already has the library. | M |
| Wi-Fi | Scan list, password, hidden networks, captive portal, details, reset | Same. Captive portal via an in-app web view. 2.4 GHz only on the reference hardware. | M (basic) / P |
| Offline | Fully usable offline | Same. The cache plays offline. Status card for offline, "online but can't reach server" and captive portal. | M |
| Server address | Fixed vendor servers | The build's default server, or a custom one. It can be set at provisioning (adb, the usual path for self-hosters), or typed in the wizard ("Use your own server", on-screen keyboard, with `https://` prefilled and common typos tolerated). Frames have no camera, so they can't scan a QR. The frame validates the URL via `/.well-known/kinframe` and shows the server name before continuing. It can be changed later from frame settings (owner PIN) and is never changeable remotely. | M |
| LAN delivery | Direct phone→frame on LAN via mDNS | Not needed: the frame downloads from the server, which can be on the LAN. | — |

### 4.9 Frame: calendar and weather

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| Weather | Proxy to a commercial API, auto or manual location | **Open-Meteo** (no API key), fetched by the server and cached for all frames. Location set by the owner. | P |
| Calendar | ICS/Google/Outlook/iCloud; 4-day, week and month views; hybrid sidebar; countdowns; timed view. Limits per tier. | ICS and CalDAV synced by the server (not the frame), with no limits. Views and hybrid sidebar as Frameo. Countdowns and "send event to frame" free. Google and Microsoft OAuth later. | + |

### 4.10 Frame: external media, backup, updates, recovery

| Feature | Frameo | kinframe | Phase |
|---|---|---|---|
| SD/USB import | Import, play from card, export | Import (uploaded to the server so everyone sees it, or kept local-only) and export. Play from card. | P |
| PC transfer | USB MTP into the camera folder | — on the frame. Web upload replaces it. | — |
| Backup | SD backup (free); cloud backup (paid) with recovery key | **Inherent**: the server holds every item's master rendition. The operator backs up the server. Per-frame export to SD on demand. | M |
| Restore | Only on a reset frame, needs a tap on the frame | Replace a frame from the app: "Move to new frame" swaps the device credential and the new frame downloads everything. Nobody needs to touch the old one. | P |
| App updates | Self-update every 24 h, beta channel | Self-update from the server (which mirrors signed releases) with stable and beta channels, silent install via root, rollback on crash loop. | P |
| Crash-loop guard | Skip the current media, then a recovery screen | Same. Also reports to the owner. | P |
| Diagnostics | Support page plus QR | Same QR idea, plus owner-visible health in the app (Wi-Fi strength, storage, uptime, app version, last sync). | P |
| Factory reset | Clear data / OS reset | Clear data from frame settings or remotely by the owner. | P |
| Retail mode | Yes | — | — |
| Telemetry | Default-on analytics | **None.** Health data goes only to the family's own server. | M |

### 4.11 Home automation and integrations (beyond Frameo)

| Feature | Phase |
|---|---|
| Home Assistant integration (MQTT discovery or REST): screen on/off, brightness, next/previous, show item, presence wake, frame state as entities | + (1) |
| Album sync sources per frame or album: Immich album, Google Photos shared album (as far as the API permits), WebDAV/Nextcloud folder, iCloud shared album link | + (2) |
| Email-to-frame inbox | + (3) |
| "On this day" and scheduled albums | + (4) |
| Moderation queue: the owner approves items before they show (useful for frames in care homes) | + (5) |
| Google Cast / DLNA: show a frame's library on a TV | + (6) |
| Optional pre-order shop for operators who sell frames: product cards on the landing page, reservations without payment, operator list and CSV export (API README §5.11). Off unless configured; open-source servers have no shop | + (7) |

## 5. Non-functional requirements

**Frame app**

| Area | Requirement |
|---|---|
| Platform | Android 6.0 (API 23) and later, `armeabi-v7a` and `arm64-v8a`, 1 GB RAM, OpenGL ES 2.0. Must run smoothly on the RK3126 reference. |
| Performance | Next photo decoded ahead of time at screen size. Cross-fade at ≥30 fps on the reference. Cold start to the first photo in under 5 s after boot completes. |
| Memory | Never decode beyond screen resolution. Java heap use under 96 MB. |
| Robustness | Crash-safe storage: temp file, fsync, rename, plus a WAL-mode SQLite index. Survives power loss at any moment. Crash-loop protection. |
| Security | Outbound-only networking, TLS, revocable device credential. No inbound listeners (no adb over TCP in production). Kiosk: the user cannot leave the app without an owner PIN or adb. |
| Root use | Optional. With root: silent updates, disabling Frameo and the OEM updater, read-only remount on shutdown, system settings. Without root: degrade gracefully on a plain Android tablet. |
| Hardware capabilities | Per-board capability file (light sensor, motion sensor path, battery, rotation, max video decode). Settings appear only when the capability exists. |

**Server**

| Area | Requirement |
|---|---|
| Delivery | New item visible on an online frame within **5 s** of upload completing (median, 1280×800 photo, decent Wi-Fi). |
| Media | Photo renditions: short edge = frame's long screen edge, never upscaled, WebP q85, EXIF capture time kept, GPS stripped from renditions. Video: H.264 + AAC, within the frame's reported max decode size, 30 fps, poster image. HEIC/HEVC/MOV input supported. |
| Scale | One small server (2 vCPU, 2 GB RAM) handles ≥50 frames and ≥200 users. |
| Ops | Single `docker compose up`: backend, Postgres, Logto, Caddy. Documented backup (Postgres dump plus the media volume). |

**Phone and web**

| Area | Requirement |
|---|---|
| Uploads | Background-safe on phones. A send continues after the app is closed. |
| Platforms | iOS 16+, Android 8+, evergreen browsers. |
| Accessibility | Screen-reader labels throughout, dynamic type, WCAG AA contrast. |

**Product-wide**

| Area | Requirement |
|---|---|
| Privacy | No third-party analytics or ad SDKs anywhere. Push via UnifiedPush/ntfy, with FCM/APNs only in store builds. |
| Localization | i18n from day one. MVP: English and Dutch, community translations afterwards. RTL-ready. |

## 6. Default values

The defaults are Frameo's, except the last three rows.

| Setting | Default |
|---|---|
| Slideshow timer | 30 s |
| Transition | Cross-fade, 800 ms |
| Order | Date received, newest first |
| Fill frame / bars | On / blurred |
| Widgets | Caption on, avatar on, clock on, date on, weather off until a location is set |
| Video | Autoplay on, play once and hold, auto-mute off |
| Sleep | 23:00–07:00 every day |
| Brightness | 204 of 255 |
| Quick-menu auto-hide / settings auto-close | 15 s / 5 min |
| Defer scheduled actions after touch | 60 s |
| Frame code validity | 12 h |
| Caption length | 280 characters |
| Items per send / max video length | 200 / 5 min (operator-configurable) |
| Trash retention | 30 days |

## 7. Release plan

1. **v0.1 MVP: the loop works**
   - Server: enrollment and frame code, Logto auth, upload, renditions, websocket delivery, reactions.
   - Frame: setup wizard, slideshow with cross-fade, caption/clock widgets, new-photo experience, reactions,
     hide/delete, sleep schedule, brightness, safe power-off.
   - Web: sign in, claim/join a frame, upload, history with delivery and reactions.
   - Mobile: sign in, add a frame, share target and picker, send, history, reaction pushes.
   - Provisioning script for the Denver frame.
2. **v0.5 Parity**: everything marked **P**. This includes roles and permissions UI, remote settings, library and
   albums, collage, video options, Wi-Fi details, weather, SD import/export, self-update, diagnostics, replacing a frame.
3. **v1.0 And more**: everything marked **+**, in the order of 4.11, then calendar, smart portrait pairing, Ken Burns,
   moderation.

## 8. Decisions

Decided by the project owner on 2026-10-22.

1. **No end-to-end encryption in v1.** TLS in transit, optional encryption at rest. The server needs to see photos to
   make renditions, run face detection, serve the web gallery and keep backups. Whoever runs the server is trusted.
2. **No full-resolution originals.** The server never stores the uploaded original. Per item it stores:
   - **One master rendition:** photos at most 2560 px on the long edge (WebP q90, capture time and orientation
     applied, GPS stripped); videos at most 1080p H.264/AAC.
   - **Per-frame-size renditions and thumbnails**, derived from the master and regenerable at any time. Frames up to
     1440p, including ones added later, get full quality.
   - The upload is deleted as soon as the master is written.
   - Phones and browsers may make the master themselves (smaller uploads, less server CPU). The server verifies it
     and keeps it without re-encoding, but always strips metadata itself; anything that doesn't conform is processed
     as usual (API README §5.3, "Prepared uploads").
3. **One server can host several unrelated families.** Frames and memberships isolate them. There is no extra tenant
   concept, and the operator role can see everything.
