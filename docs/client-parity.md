# Client parity: web, Android and iOS family apps

One row per user-facing feature of the web family app (`web/`), compared with the Android family app
(`android/family`) and the iOS app (`ios/`, branch `ios-app`).

How to read the cells:

- **yes**: the feature is there and behaves like the web app.
- **new**: added to the Android app on the `family-parity` branch.
- **differs**: there, but done the platform's way. The notes say how.
- **skipped**: deliberately left out. The notes say why.
- **no**: missing.
- **?**: not checked. The iOS column is filled in only where the `ios-app` branch makes it obvious; a separate
  effort covers iOS.

Strings for the new Android screens come from the web catalog (`web/src/lib/i18n/en.ts` and `catalogs/nl.ts`),
converted key by key into `android/family/src/main/res/values*/strings_*.xml`, so the wording is the same.
Android-only strings are marked in those files.

## Shell, sign-in and live updates

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Four sections: Frames, Send, History, Settings | yes | yes | yes | Android: bottom bar on phones, rail on tablets. |
| Sign in with Logto, dev token on dev servers | yes | yes | yes | |
| Choose or change server | yes | yes | yes | |
| Server unreachable at start, with Try again | yes | yes | ? | Android: profile error panel with retry. |
| Live updates over the user websocket | yes | yes | yes | |
| Live join request toast with Review | yes | new | ? | |
| "Reconnecting…" indicator | yes | skipped | ? | Android reconnects silently and shows push notifications while the app is closed; the socket status isn't observable across reconnects yet. |
| Undo instead of "are you sure" (bulk library actions, album removal, unsend) | yes | yes | ? | |
| Language picker (36 languages) | yes | new | ? | Android: Settings → Preferences sets `PATCH /me {locale}`, the push locale and, on Android 13+, the app's language. The app's own strings are English and Dutch for now (`docs/i18n.md`). |
| Right-to-left layout for Arabic and Hebrew | yes | yes | ? | Compose mirrors the layout; directional icons are auto-mirrored. |

## Public pages

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Landing page | yes | skipped | skipped | The app opens on its welcome and sign-in screens instead. |
| Shop and reservation cancel (`/shop`, `/shop/cancel`) | yes | skipped | ? | A public storefront for buying frames; it isn't part of using a frame. |
| Operator reservations (`/settings/reservations`) | yes | skipped | ? | An operator back-office tool with CSV export, meant for a desktop. |
| Weather stations (`/stations`, `/station/[id]`) | yes | skipped | ? | Public dashboards with charts, not tied to an account. |
| Convert a Frameo frame (`/convert`) | yes | skipped | skipped | Needs WebUSB and a USB cable from a computer to the frame. |
| Privacy and terms | yes | differs | ? | Android: links in Settings open the server's own `/privacy` and `/terms` pages. |
| TV page (`/tv/[frameId]`) and the browser as a frame (`/tv/frame`) | yes | skipped | skipped | These run on the TV's browser. The app makes links for them and can show them on a connected TV (see "Show on TV"). |

## Frames and adding a frame

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Frames list with online status, role, location, send button | yes | yes | yes | |
| "Browser" badge for browser frames | yes | new | ? | |
| Needs-update note | yes | yes | ? | |
| Drag files onto a frame card to send | yes | skipped | skipped | No desktop drag and drop on a phone; the share sheet does this job. |
| Add a frame with its 8-digit code (claimed, joined, already, pending) | yes | yes | yes | |
| Name the frame right after claiming it | yes | yes | yes | |
| QR and frame-code links (`/f/{code}`) | yes | yes | yes | App links. |
| Invite links (`/i/{token}`): preview, accept, pending | yes | yes | yes | |
| Link to the Frameo convert tool | yes | skipped | ? | See `/convert`. |

## Frame: Overview

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Remote control: previous, next, sleep, wake, identify | yes | yes | yes | Android also has "sync now". |
| Edit name and location | yes | yes | ? | Android: in a rename dialog. |
| Facts: owner, members, photos, screen, since | yes | yes | ? | |
| Health: Wi-Fi, storage, cached, uptime, version, last seen, unclean shutdown | yes | yes | yes | Browser frames hide Wi-Fi, storage and cache (new on Android). |
| Transfer ownership (admins first, confirm step, optionally leave) | yes | new | partial | iOS has a transfer sheet without the confirm step or leaving. |
| Owner leaves by handing over first | yes | new | ? | |
| Move to a new frame (replace the device by its code) | yes | new | no | |
| Remove frame (type the name to confirm) | yes | yes | yes | |
| Leave frame, optionally removing my photos | yes | yes | yes | |

## Frame: Photos (library)

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| On the frame / hidden / trash, with counts | yes | yes | yes | |
| Select, select all; hide, unhide, trash, restore with undo | yes | yes | yes | Android: long press or tap while selecting. |
| Share to another frame (dry-run coverage, undo) | yes | yes | yes | |
| Photo viewer with its actions | yes | new | ? | Android: tapping a photo opens it. |
| Albums: chips, new, rename, delete | yes | new | no | |
| Add to an album, remove from an album with undo, use as cover | yes | new | no | |
| Arrange an album | yes | differs | no | Android: arrows on each photo instead of dragging. |
| Album schedule (every year between two dates) | yes | new | no | |
| Play an album on the frame / show all photos | yes | new | no | |
| On this day (memories strip) | yes | new | no | |
| Show on TV: whole library, album, selection, one photo | yes | new | no | |

## Frame: People

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Join requests: approve, deny | yes | yes | yes | |
| Members with role and permissions | yes | yes | yes | |
| Remove member, optionally their photos | yes | yes | yes | |
| Make owner from the member list | yes | new | ? | |
| Invite links: expiry, uses, note; copy, share, revoke | yes | yes | yes | Android shares through the system share sheet. |
| Joins need approval switch | yes | yes | ? | |

## Frame: Calendar

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Calendar tab when the server has calendars and you may send or change settings | yes | new | no | |
| Calendars: add an ICS link or a CalDAV account | yes | new | no | |
| Test a calendar; pick one or more CalDAV calendars | yes | new | no | |
| Name and colour (12-colour palette), show on frame on/off | yes | new | no | |
| Sync now, sync status and errors, polling while syncing | yes | new | no | |
| Edit and remove a calendar (also the received-events calendar) | yes | new | no | |
| Send an event to the frame (title, all day, times, place, note) | yes | new | no | Android uses the system date and time pickers. |
| Event photo, above or behind the details, with a live card preview | yes | new | no | The photo is prepared on the phone (photo-master-v1) before it is uploaded. |
| Sent events: status, withdraw, live answers | yes | new | no | Push notifications for answers open this tab. |
| Calendar settings: view, start on today, fade past, week numbers, sidebar, countdown widgets, sync window | yes | new | no | |
| Timed calendar views (times and days) | yes | new | no | |
| Preview of what the frame shows: list and month, weather, countdowns | yes | new | no | |
| Count down to an event from its details | yes | new | no | |

## Frame: Settings

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Interval (5 s to 24 h) | yes | yes | yes | Android gained 10 m, 30 m, 2 h, 6 h and 12 h. |
| Order, reverse, transition, fill | yes | yes | yes | |
| Background around photos that don't fill the screen | yes | new | ? | |
| Which album the frame shows | yes | new | no | |
| On this day | yes | new | no | |
| Live photos, caption, clock | yes | yes | ? | |
| Brightness; note for browser frames | yes | yes | yes | Browser note is new on Android. |
| Frame language | yes | new | ? | |
| Location search and weather, °C/°F | yes | yes | yes | |
| Sleep schedules | yes | yes | yes | |
| Plugins: layout, instances, editor, community confirm, updates | yes | yes | yes | |
| Version conflict: merge or review | yes | yes | ? | |

## Send

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Pick photos and videos | yes | yes | yes | Android: the system photo picker. |
| Share into the app from other apps | no | yes | yes | Android share sheet (`ACTION_SEND` / `SEND_MULTIPLE`). |
| Choose a folder, paste, drop anywhere | yes | skipped | skipped | Desktop browser inputs; the picker and the share sheet cover a phone. |
| Captions, apply to all | yes | yes | yes | |
| Fill or fit, focal point, landscape and portrait previews | yes | yes | yes | |
| Trim videos, keep or drop the sound | yes | yes | yes | |
| Live and Motion Photos | yes | yes | yes | |
| Prepare on the device (photo-master-v1, video-master-v1) | yes | differs | yes | Android never uploads full originals: a file that can't be prepared goes as it is only when it is already within 2560 px or 1080p/30 fps, otherwise the send stops with an error. The web app still falls back to the original. |
| Resumable uploads, background sending, retry | yes | yes | yes | Android: WorkManager with a progress notification. |
| Recipients, offline note | yes | yes | yes | |
| Turn on notifications after the first send (PushOffer) | yes | differs | ? | Android asks for the notification permission once and explains it in Settings. |

## History

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Sends grouped by day, filter all / ongoing / delivered | yes | yes | yes | |
| Per-frame delivery stages, reactions and replies | yes | yes | yes | |
| Send detail, edit captions | yes | yes | yes | |
| Unsend | yes | yes | yes | |
| Share a send to another frame | yes | yes | yes | |
| Show a send on TV | yes | new | no | |
| Outbox (queued, uploading, failed, retry, discard) | no | yes | yes | Phones keep sending in the background. |

## Account settings

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Display name | yes | yes | yes | |
| Profile photo: change, remove | yes | new | ? | Android crops a centred square JPEG on the phone. |
| Language | yes | new | ? | See "Language picker". |
| Notifications on/off | yes | differs | yes | Android: UnifiedPush or Firebase, and the system notification permission. |
| Server info and limits | yes | yes | yes | |
| Sign out | yes | yes | yes | |
| Privacy and terms links | yes | new | ? | |

## Show on TV

The web app reaches a TV with the Google Cast web SDK (Chromium), the browser's Presentation API, or the Remote
Playback API for one video. Android doesn't use any of these: it uses Android's own route, a presentation display.

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Show photos at the TV's own pace, with interval | yes | new | no | |
| Mirror the frame live; move the frame and the TV together | yes | new | no | |
| Make a TV browser a frame by its code | yes | new | no | |
| Use this browser as a frame | yes | skipped | skipped | A phone isn't a frame. |
| Link for the TV's browser: copy, open, turn off, active links | yes | new | no | Android also shares the link through the share sheet. |
| Cast with Google Cast (Chromecast) | yes | differs | no | Android: "Present on a screen" shows the TV page on a TV connected with the system's Cast screen (Chromecast, Google TV) or Miracast. No Google Play services needed. If no TV is connected it opens the Cast settings and starts as soon as one connects. |
| Presentation API | yes | differs | no | Android: an `android.app.Presentation` with the TV page in a WebView, using a short-lived `cast` link. |
| Play one video with Remote Playback | yes | skipped | no | "Present on a screen" plays videos too. |
| Now playing: previous, next, play/pause, stop | yes | differs | no | Android sends the TV page the keys a remote sends. It has no position or caption from the TV, and the interval is chosen before starting. |
| Mini bar on every screen while showing | yes | new | no | |
