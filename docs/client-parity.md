# Client parity: web, Android and iOS family apps

One row per user-facing feature of the web family app (`web/`), compared with the Android family app
(`android/family`) and the iOS app (`ios/`: the SwiftUI app, `KinframeKit` and the `KinframeShare` share extension).

How to read the cells:

- **yes**: the feature is there and behaves like the web app.
- **new**: added in the parity work: on Android on the `family-parity` branch, on iOS in the parity update described
  in `ios/PR.md`.
- **differs**: there, but done the platform's way. The notes say how.
- **skipped**: deliberately left out. The notes say why.
- **no**: missing.
- **planned**: designed (spec and API contract), not built yet.

The iOS column was checked against the iOS source on main, with `ios/PR.md` as a guide.

Strings for the new Android screens come from the web catalog (`web/src/lib/i18n/en.ts` and `catalogs/nl.ts`),
converted key by key into `android/family/src/main/res/values*/strings_*.xml`, so the wording is the same.
Android-only strings are marked in those files. iOS does the same with `Localizable.xcstrings`; iOS-only strings are
in `ios-strings*.json`.

## Shell, sign-in and live updates

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Four sections: Frames, Send, History, Settings | yes | yes | yes | Android: bottom bar on phones, rail on tablets. |
| Sign in with Logto, dev token on dev servers | yes | yes | yes | |
| Choose or change server | yes | yes | yes | |
| Server unreachable at start, with Try again | yes | yes | yes | Android: profile error panel with retry. iOS: the frames list shows the error with Try again, and a "Can't reach the server" banner stays until the server answers. |
| Live updates over the user websocket | yes | yes | yes | |
| Live join request toast with Review | yes | new | yes | |
| "Reconnecting…" indicator | yes | skipped | no | Android reconnects silently and shows push notifications while the app is closed; the socket status isn't observable across reconnects yet. iOS also reconnects silently: the socket has a status, but nothing shows it. |
| Undo instead of "are you sure" (bulk library actions, album removal, unsend) | yes | yes | yes | |
| Language picker (36 languages) | yes | new | differs | Android: Settings → Preferences sets `PATCH /me {locale}`, the push locale and, on Android 13+, the app's language. The app's own strings are English and Dutch for now (`docs/i18n.md`). iOS offers only System, English and Nederlands; the choice also sets the account locale. |
| Right-to-left layout for Arabic and Hebrew | yes | yes | no | Compose mirrors the layout; directional icons are auto-mirrored. iOS can't run in Arabic or Hebrew yet (English and Dutch only), so it never lays out right to left. |

## Public pages

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Landing page | yes | skipped | skipped | The app opens on its welcome and sign-in screens instead. |
| Shop and reservation cancel (`/shop`, `/shop/cancel`) | yes | skipped | skipped | A public storefront for buying frames; it isn't part of using a frame. iOS: "Reserve a frame" in Settings opens the web shop when the server has one. |
| Operator reservations (`/settings/reservations`) | yes | skipped | new | An operator back-office tool with CSV export, meant for a desktop. iOS has it in Settings for operators: filter, totals, status and internal note per reservation, CSV export through the share sheet. |
| Weather stations (`/stations`, `/station/[id]`) | yes | skipped | skipped | Public dashboards with charts, not tied to an account. Discovery doesn't say whether a server has them, so iOS doesn't link to them either. |
| Convert a Frameo frame (`/convert`) | yes | skipped | skipped | Needs WebUSB and a USB cable from a computer to the frame. |
| Privacy and terms | yes | differs | differs | Android and iOS: links in Settings open the server's own `/privacy` and `/terms` pages. |
| TV page (`/tv/[frameId]`) and the browser as a frame (`/tv/frame`) | yes | skipped | skipped | These run on the TV's browser. The app makes links for them and can show them on a connected TV (see "Show on TV"). |

## Frames and adding a frame

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Frames list with online status, role, location, send button | yes | yes | yes | |
| "Browser" badge for browser frames | yes | new | new | |
| Needs-update note | yes | yes | yes | |
| Drag files onto a frame card to send | yes | skipped | skipped | No desktop drag and drop on a phone; the share sheet does this job. |
| Add a frame with its 8-digit code (claimed, joined, already, pending) | yes | yes | yes | |
| Name the frame right after claiming it | yes | yes | yes | |
| QR and frame-code links (`/f/{code}`) | yes | yes | yes | App links. |
| Invite links (`/i/{token}`): preview, accept, pending | yes | yes | yes | |
| Link to the Frameo convert tool | yes | skipped | new | See `/convert`. iOS: the add-frame screen links to the web's `/convert` page, which opens in Safari. |

## Frame: Overview

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Remote control: previous, next, sleep, wake, identify | yes | yes | yes | Android also has "sync now". |
| Edit name and location | yes | yes | new | Android: in a rename dialog. |
| Facts: owner, members, photos, screen, since | yes | yes | yes | |
| Health: Wi-Fi, storage, cached, uptime, version, last seen, unclean shutdown | yes | yes | yes | Browser frames hide Wi-Fi, storage and cache (new on Android). |
| Transfer ownership (admins first, confirm step, optionally leave) | yes | new | new | |
| Owner leaves by handing over first | yes | new | new | |
| Move to a new frame (replace the device by its code) | yes | new | new | |
| Remove frame (type the name to confirm) | yes | yes | yes | |
| Leave frame, optionally removing my photos | yes | yes | yes | |

## Frame: Photos (library)

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| On the frame / hidden / trash, with counts | yes | yes | yes | |
| Select, select all; hide, unhide, trash, restore with undo | yes | yes | yes | Android: long press or tap while selecting. |
| Share to another frame (dry-run coverage, undo) | yes | yes | yes | |
| Photo viewer with its actions | yes | new | yes | Android: tapping a photo opens it. iOS: in a sheet. |
| Albums: chips, new, rename, delete | yes | new | new | |
| Add to an album, remove from an album with undo, use as cover | yes | new | new | |
| Arrange an album | yes | differs | new | Android: arrows on each photo instead of dragging. iOS: drag in an Arrange sheet. |
| Album schedule (every year between two dates) | yes | new | new | |
| Play an album on the frame / show all photos | yes | new | new | |
| On this day (memories strip) | yes | new | new | |
| Show on TV: whole library, album, selection, one photo | yes | new | new | |

## Frame: People

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Join requests: approve, deny | yes | yes | yes | |
| Members with role and permissions | yes | yes | yes | |
| Remove member, optionally their photos | yes | yes | yes | |
| Make owner from the member list | yes | new | new | |
| Invite links: expiry, uses, note; copy, share, revoke | yes | yes | yes | Android shares through the system share sheet. iOS too; revoking has Undo (new). |
| Joins need approval switch | yes | yes | no | iOS shows which hint applies (approval needed or not), but has no switch. |

## Frame: Calendar

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Calendar tab when the server has calendars and you may send or change settings | yes | new | new | iOS: a fifth section; the section picker then scrolls. |
| Calendars: add an ICS link or a CalDAV account | yes | new | new | |
| Test a calendar; pick one or more CalDAV calendars | yes | new | new | |
| Name and colour (12-colour palette), show on frame on/off | yes | new | new | |
| Sync now, sync status and errors, polling while syncing | yes | new | new | |
| Edit and remove a calendar (also the received-events calendar) | yes | new | new | |
| Send an event to the frame (title, all day, times, place, note) | yes | new | new | Android and iOS use the system date and time pickers. |
| Event photo, above or behind the details, with a live card preview | yes | new | new | The photo is prepared on the phone (photo-master-v1) before it is uploaded. |
| Sent events: status, withdraw, live answers | yes | new | new | Push notifications for answers open this tab. iOS tells the sender with a toast anywhere in the app. |
| Calendar settings: view, start on today, fade past, week numbers, sidebar, countdown widgets, sync window | yes | new | new | |
| Timed calendar views (times and days) | yes | new | new | |
| Preview of what the frame shows: list and month, weather, countdowns | yes | new | new | |
| Count down to an event from its details | yes | new | new | |

## Frame: Settings

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Interval (5 s to 24 h) | yes | yes | yes | Android gained 10 m, 30 m, 2 h, 6 h and 12 h. |
| Order, reverse, transition, fill | yes | yes | yes | |
| Background around photos that don't fill the screen | yes | new | yes | |
| Which album the frame shows | yes | new | new | |
| On this day | yes | new | new | |
| Live photos, caption, clock | yes | yes | yes | |
| Brightness; note for browser frames | yes | yes | yes | Browser note is new on Android and iOS. |
| Frame language | yes | new | new | |
| Location search and weather, °C/°F | yes | yes | yes | |
| Sleep schedules | yes | yes | yes | |
| Plugins: layout, instances, editor, community confirm, updates | yes | yes | yes | |
| Version conflict: merge or review | yes | yes | yes | |

## Send

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Pick photos and videos | yes | yes | yes | Android: the system photo picker. |
| Share into the app from other apps | no | yes | yes | Android share sheet (`ACTION_SEND` / `SEND_MULTIPLE`). iOS: the share extension. |
| Choose a folder, paste, drop anywhere | yes | skipped | skipped | Desktop browser inputs; the picker and the share sheet cover a phone. |
| Captions, apply to all | yes | yes | yes | |
| Fill or fit, focal point, landscape and portrait previews | yes | yes | yes | |
| Trim videos, keep or drop the sound | yes | yes | yes | |
| Live and Motion Photos | yes | yes | yes | |
| Prepare on the device (photo-master-v1, video-master-v1) | yes | yes | yes | No client uploads full originals: a file that can't be prepared goes as it is only when it is already within 2560 px (web: a JPEG or WebP) or 1080p/30 fps, otherwise the item shows an error and isn't sent. The web app falls back to WebAssembly for photos (HEIC through libheif); a browser without an AAC encoder sends videos muted, with a note on the item. iOS prepares in the share extension too. |
| Resumable uploads, background sending, retry | yes | yes | yes | Android: WorkManager with a progress notification. iOS: a background `URLSession`. |
| Recipients, offline note | yes | yes | yes | |
| Turn on notifications after the first send (PushOffer) | yes | differs | differs | Android asks for the notification permission once and explains it in Settings. iOS asks once after the first send, only in builds with APNs turned on (off by default). |

## History

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Sends grouped by day, filter all / ongoing / delivered | yes | yes | yes | |
| Per-frame delivery stages, reactions and replies | yes | yes | yes | |
| Send detail, edit captions | yes | yes | yes | |
| Unsend | yes | yes | yes | |
| Share a send to another frame | yes | yes | yes | |
| Show a send on TV | yes | new | new | |
| Outbox (queued, uploading, failed, retry, discard) | no | yes | yes | Phones keep sending in the background. |

## Feeds

Private family channels that frames subscribe to (`docs/spec/feeds.md`, API guide §5.12). Everything below is hidden
when discovery doesn't say `feeds: true`. The iOS column describes the feeds work in `ios/` (not yet run on a
device or simulator, see `ios/README.md`); the web and Android columns follow their own feeds work.

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Feeds list and New feed, hidden when the server has no feeds | yes | new | new | iOS: a Feeds tab that appears only with discovery `feeds`. |
| Posts, newest first, with "Seen on 4 of 5 frames" and reactions by frame where allowed | yes | new | new | Receipts and reactions show only where the server includes them: own posts, every post for owners and admins, everyone's with *members see reactions*. |
| A post's receipts per frame (delivered, seen, awaiting approval, paused, removed) | yes | new | new | |
| Unsend a post or one of its photos; admins remove any post | yes | new | new | With Undo instead of "are you sure". |
| Members and roles, remove (optionally with their posts), leave (optionally with my posts), make owner | yes | new | new | Admins manage posters only, as for frames. |
| Join requests: approve, deny | yes | new | new | |
| Invites to join and to subscribe a frame: link, QR, code (`KF7Q-M2XA`); share, copy, revoke | yes | new | new | iOS draws the QR with Core Image; revoking has Undo. Members can create join invites when the feed has *posters can invite*. |
| Feed settings: name, cover from the feed's photos, auto-approve frames, invites need approval, members see reactions, posters can invite; delete | yes | new | new | |
| The feed's frames: show on a frame you manage, approve or reject requests, remove | yes | new | new | |
| Feed invite links (`/fi/{token}`): preview, join, or pick frames to subscribe | yes | new | new | iOS: Universal Link (the server's `apple-app-site-association` must list `/fi/*`) and `app.kinframe://fi/<token>?server=<url>`. |
| One "Enter a code" field for 8-digit frame codes and feed codes | yes | new | new | iOS: the number pad by default, with a Letters switch; pasted messages are searched for a code. |
| Feeds as recipients next to frames, at least one recipient | yes | new | new | iOS: in the Send tab and the share extension; "Post" in a feed preselects it. |
| Sent history with feed recipients and their receipts; post a send to a feed | yes | new | new | |
| Frame settings → Feeds: subscribe (a feed you're in, or a subscribe invite), history none / last N / all, pause and resume, change history, unsubscribe | yes | new | new | Pausing takes the feed's photos off the frame until resumed. |
| Play one feed on the frame | yes | new | new | `slideshow.feed_id`; choosing an album clears it and the other way round. |
| Live updates and notifications for subscription requests and decisions, join requests, reactions on feed posts | yes | new | differs | iOS: toasts from the websocket while open; a tapped notification opens the feed, its post or the frame's feeds, but APNs stays behind the build flag (off) until the server sends APNs. |

## Birthdays

A birthday list per feed, shown on every subscribed frame (`docs/spec/birthdays.md`, API guide §5.13). Designed, not
built yet: every cell that applies is **planned**. Hidden when discovery doesn't say `birthdays: true`. The Frame column is the
Android frame app; the browser frame (`/tv/frame`) follows it.

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| A feed's Birthdays list, sorted by the next birthday, with age where visible | planned | planned | new | — | The year shows only to admins, whoever added the entry and the linked member, unless *show age* is on. |
| Add a person (name, day and month, optional year, show age, photo, link to a member); "Add my birthday" | planned | planned | new | — | Photo cropped to a square on the device and uploaded like a profile photo. |
| Suggest a person (posters), withdraw; approve or reject, edit first (admins) | planned | planned | new | — | Pending suggestions show "waiting for an admin". |
| Edit and remove, with Undo; linked members control their own entry | planned | planned | new | — | |
| Feed setting *posters can add birthdays* | planned | planned | new | — | |
| Birthday notifications on/off per feed | planned | planned | new | — | `PATCH /feeds/{id}/preferences`. |
| Send the device time zone (`PATCH /me {timezone}`) | planned | planned | new | — | For the 08:00 morning notification. |
| Frame settings → Feeds: *Birthdays* on/off per subscription; "update the frame app" hint | planned | planned | new | — | |
| Greetings from frames, live and as notifications (one per person and day, updated) | planned | planned | differs | — | iOS: today's greetings under each person and a toast from the websocket; a tapped notification opens the feed's Birthdays. APNs stays behind the build flag; older greeting notifications for the same person and day are removed from Notification Center when the app opens (`birthday:<id>:<date>`). |
| Morning notification, suggestion and decision notifications | planned | planned | differs | — | iOS: toasts from the websocket (with Review); tapped notifications open the feed's Birthdays; APNs behind the build flag. |
| Countdown tiles "Oma turns 80 · 3 days", merged with event countdowns | — | — | — | planned | Follows `widgets.countdowns`. |
| Birthday card on the day, one card for several people | — | — | — | planned | Hidden while the clock is not trusted. |
| Balloons on first appearance and once an hour | — | — | — | planned | Never while asleep, dimmed or playing a video. |
| Tap to say happy birthday (once per person per day, queued offline) | — | — | — | planned | |
| Birthdays in the calendar views, sidebar and split dashboard | — | — | — | planned | |
| `features: ["birthdays"]` in the heartbeat | — | — | — | planned | |

## Photo sources

Albums and feeds synced from Immich or iCloud, and "Add from Google Photos" (`docs/spec/photo-sources.md`, API guide
§5.14). Designed; the iOS column describes the photo-sources work in `ios/` (not yet run on a device or simulator). Hidden when discovery doesn't say
`photo_sources: true` (sources; providers from `photo_source_providers`) or `google_photos_import: true` (Google). The
Frame column is the Android frame app; the browser frame (`/tv/frame`) follows it.

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| "Sync from…" on an album (album managers): Immich shared link (recommended), Immich server + API key with album picker, iCloud shared album link | planned | planned | new | — | Test before connecting: album name, counts, size, warnings. iCloud shows the best-effort note. iOS: in the album menu; Paste button; the iCloud sheet explains how to turn on Public Website in Photos. |
| Sources on a feed (owner/admins) | planned | planned | new | — | Feed defaults: import the last 20, keep photos removed upstream. iOS: in the feed's Settings section. |
| Initial import (everything / last N / from now on), removals mirror or keep, include videos, reaction notifications | planned | planned | new | — | |
| Status on the album or feed: last and next sync, counts, "importing 40 of 220", error with the fix as a button | planned | planned | new | — | Live from `photo_source.updated`. |
| Sync now, pause and resume, replace link/password/key, take over | planned | planned | new | — | iOS: taking over an Immich API-key source asks for a new key. |
| Stop syncing: keep or remove the imported photos; deleting an album with sources asks the same | planned | planned | new | — | |
| Settings → Photo sources (the ones I set up) | planned | planned | new | — | `GET /v1/photo-sources`. |
| Confirm a mass removal | planned | planned | new | — | `syncPhotoSource {confirm_removals: true}`. iOS: or keep them (switches the source to *keep*). |
| "Add from Google Photos" on an album and in a feed's post menu | planned | planned | new | — | Web: popup with `/autoclose`. Android: Custom Tab for consent, Google Photos app (or Custom Tab) for the picker, `app.kinframe:/google-import`. iOS: `ASWebAuthenticationSession`, then the Google Photos app or `SFSafariViewController`. iOS: album menu and the feed's Post menu. |
| Google import progress, result, cancel, undo; resume after the app was closed | planned | planned | new | — | `google_import.updated`, `GET /v1/google-imports`. iOS: progress sheet from polling and `google_import.updated`; refreshed when the app returns from Google Photos. |
| Notifications: a source needs attention; a Google import finished | planned | planned | differs | — | No notifications for new photos. iOS: a toast with Review from the websocket; tapped notifications open the album, the feed's settings or the import. APNs stays behind the build flag. |
| Album option *Only in album mode*; suggested when a first import adds more than 200 photos | planned | planned | new | — | iOS: a switch in the album menu, a marker on the album chip and header. |
| "via Immich" in history, feed posts and the library | planned | planned | new | — | `Send.via`, `FeedPost.via`, `Item.via`. |
| Credit "Anna via Immich" / "Anna · Peeters kids via Immich" | — | — | — | planned | Older frames show "Anna" / "Anna · Peeters kids". |

## Email to frame

Inbox addresses for frames and feeds (`docs/spec/email-inbox.md`, API guide §5.16). Designed, not built. Hidden when
discovery doesn't say `email_inbox: true`. Managers only: frame owner, admins and `change_settings` members; feed
owner and admins.

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| "Email photos to this frame" in frame settings and "Email" in feed settings: list addresses with status and last mail | planned | planned | planned | — | |
| Create an address (friendly part prefilled from the name), rename, change the friendly part, pause/resume, regenerate, delete | planned | planned | planned | — | Regenerate and slug changes say the old address stops at once. |
| Copy the address; Share with a ready-made message for a relative | planned | planned | planned | — | Android: share sheet; iOS: `ShareLink`; web: Web Share API, else copy. Text in the user's language. |
| Settings: anyone may send, confirmations to senders, join link in replies, reaction notifications | planned | planned | planned | — | "Senders get no replies" note when `replies_available` is false. |
| Allowed senders: add (with a name), remove, block, unblock | planned | planned | planned | — | Members are not listed: "Members can send from their account email". |
| Pending senders with held mail previews (sender, subject, time, thumbnails); approve, reject, block; deliver or delete one held mail | planned | planned | planned | — | Unverified mail: warning and only "Deliver this one" / "Delete". |
| Recent mail (30 days) with outcomes and replies | planned | planned | planned | — | |
| Notifications: someone new is waiting; held mail expires soon | planned | planned | planned | — | `email_pending_sender`; tap opens the pending list. |
| Join requests "from the email link", with the email match | planned | planned | planned | — | `JoinRequest.via: email`, `via_name`, `via_matches` (generic for all channels). |
| "via email" in history, feed posts and the library | planned | planned | planned | — | `Send.via`, `FeedPost.via`, `Item.via` with `service: email`: show `label` instead of the sender. |
| Credit "Jan via email" / "Jan · Peeters kids via email" | — | — | — | planned | Older frames show the sender's name (the address owner for non-account mail). |

## Telegram

Telegram groups and private chats connected to frames and feeds (`docs/spec/telegram.md`, API guide §5.17).
Designed, not built. Hidden when discovery doesn't say `telegram: true`. Chats are managed by frame owner, admins and
`change_settings` members, or feed owner and admins; linking an account is for everyone.

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| "Telegram" in frame settings and feed settings: connected chats with title, status, last photo, owner | planned | planned | planned | — | Disconnected chats under "Earlier" for 30 days. |
| Connect a Telegram group: open the `?startgroup=` link, or show it as a QR code; follow the code until connected | planned | planned | planned | — | Phones open Telegram with the link; web shows the QR and the link. `telegram_privacy_mode`: say up front to make the bot an admin. |
| Send from my Telegram (private chat, links the account) and Invite someone to send by Telegram (share the `?start=` link) | planned | planned | planned | — | Share text in the user's language. |
| Chat settings: who can send (everyone / only linked members), frame replies into the chat, join link in the app message, reaction notifications (owner), topic (forum groups); pause, resume, take over, disconnect | planned | planned | planned | — | `notify_reactions` only for the owner. |
| Status and fixes: privacy mode ("make the bot an admin"), bot changed, can't post, reactions off | planned | planned | planned | — | Texts per code: `telegram.md` §15. |
| People in a chat: display name, linked, count; block, unblock | planned | planned | planned | — | Telegram user ids are never shown. |
| Notification: a chat needs attention, was disconnected, or passed to you | planned | planned | planned | — | `telegram_attention`; tap opens the chat. |
| Join requests "from Telegram" | planned | planned | planned | — | `JoinRequest.via: telegram`, `via_name`; `via_matches` is always null. |
| "via Telegram" in history, feed posts and the library | planned | planned | planned | — | `Send.via`, `FeedPost.via`, `Item.via` with `service: telegram`: show `label` instead of the sender. |
| Credit "Jan via Telegram" / "Jan · Peeters kids via Telegram" | — | — | — | planned | Older frames show the sender's name (the chat's owner for outside contributions). |

## Family social

Kinframe as the private social hub of the family (`docs/spec/family-social.md`, API guide §5.18–§5.20, work split in
`docs/spec/family-social-work.md`). Designed, not built: every cell that applies is **planned**. Feeds are called
**families** in the apps and on the web (the API keeps `feed`). Hidden when discovery doesn't say `social`, `chats`
or `engagement` respectively. The Frame column is the Android frame app; the browser frame (`/tv/frame`) follows it.

### Home and threads (`social`)

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| "Feeds" renamed "Families" everywhere in the UI | planned | planned | planned | — | Decision A. API names stay. |
| Home tab: timeline of every family's posts and own frame posts, newest first, photos large, swipe through multi-photo posts, family or "To Oma's frame" label | planned | planned | planned | — | `getTimeline` with `before`. |
| Reaction row: people and frames together ("❤ Oma's frame, Anna and 3 others"), who-reacted sheet | planned | planned | planned | — | `ReactionSummary`, `listReactions`. |
| React to a post, one photo of a post, a comment (❤️ 😂 😮 😢 👍 🙏) | planned | planned | planned | — | `setReaction`. |
| "View all N comments" with the two newest on the timeline | planned | planned | planned | — | `ThreadSummary.latest_comments`. |
| Thread: comments oldest first, one level of replies, reply to a reply quotes the person | planned | planned | planned | — | `listComments` (flat, nest by `parent_id`). |
| Write, edit ("edited"), delete own comments; tombstone "Comment deleted"; "Removed by a family admin" for the author | planned | planned | planned | — | |
| @mentions with a picker of people who can see the post | planned | planned | planned | — | Offsets in UTF-16 code units. |
| Frame entries in the thread ("Oma's frame loved this", "Oma's frame: Call me!") and "Seen on 4 frames" | planned | planned | planned | — | |
| Moderators: remove anyone's comment, turn comments off for a post (also the poster) | planned | planned | planned | — | `deleteComment`, `setThreadState`. |
| Unread state per thread, mark read; mute a post | planned | planned | planned | — | `markThreadRead`, `setThreadMute`. |
| Notifications: comment (bundled), reply, mention, reactions (first three, then a daily line) | planned | planned | planned | — | Push keys `comment`, `comment_reply`, `mention`, `reaction_person`, `reaction_digest`. |
| App badge: unread chats + threads with unread mentions or replies | planned | planned | planned | — | `getBadge`, `badge.updated`. |
| Family role `member` (follows along, comments and chats, no posting); *new members can post* | planned | planned | planned | — | Send screen hides families where you are `member`. |
| Family settings *members see reactions* (on for new families) and *members see receipts* | planned | planned | planned | — | |
| Mute a family: posts, chat or everything | planned | planned | planned | — | `updateFeedPreferences {mute}`. |
| Circles: list, create, rename, members, extra frames, delete, leave; circle as audience when sending ("Share with: whole family / The cousins") | planned | planned | planned | — | `circles` tag; `SendCreate.circle_ids`. |
| Show comments on the frame: a card with the newest comment for 8 s when a photo with comments appears (frame setting *Show comments*, off by default) | planned | planned | planned | yes | App/web: the setting. Frame: the `comments` change, once per photo per day, plain text on the overlay layer; the setting is also in the frame's own settings. Browser frame (`/tv/frame`): planned. |

### Chats (`chats`)

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| Chats tab: family, circle, group and direct chats by latest message, unread counts, archive | planned | planned | planned | — | `listConversations`. |
| Start a direct chat or a group with contacts (people you share a family or a frame with) | planned | planned | planned | — | `listContacts`, `createConversation`. Decision C. |
| Group: name, photo, add people, remove (admins), make admin, leave | planned | planned | planned | — | |
| Messages: text with mentions, up to 30 photos and videos, replies (quoted), reactions, edit within 24 h, delete ("This message was deleted") | planned | planned | planned | — | Normal tus uploads. |
| Voice messages (record, up to 5 minutes, waveform, playback) | planned | planned | planned | — | AAC in m4a (iOS, Android, Safari) or Opus in WebM/Ogg (Chrome, Firefox); the server stores AAC. |
| Put on a frame from a chat message (frames, families, circles; normal send flow) | planned | planned | planned | — | `putMessageOnFrame`; frame reactions show under the message. |
| Share in chat from any post (post card) | planned | planned | planned | — | `shared_post_id`. |
| Read receipts "Seen" (direct and groups up to 10), with the *show read receipts* setting | planned | planned | planned | — | `User.show_read_receipts`. |
| Typing and recording indicator | planned | planned | planned | — | `sendTyping`, `typing` event. |
| Mute a chat (1 h, 8 h, 1 week, always) | planned | planned | planned | — | |
| Link previews in messages (only when the operator turned them on) | planned | planned | planned | — | Discovery `link_previews`. |
| Invite card in the family chat for relatives without an account (the family's join link) | planned | planned | planned | — | |
| Block and unblock people; Settings → Blocked people | planned | planned | planned | — | |
| Report a comment, message or post; family admins' and operators' report lists, remove or dismiss | planned | planned | planned | — | Operators: web only. |
| Notifications: messages (one per chat, newest replaces older), added to a group, reports | planned | planned | planned | — | `message`, `conversation_added`, `report`. |
| Person profile: shared families and frames, start a chat, block, shown milestones | planned | planned | planned | — | `getPerson`. |
| "The operator of this server could read messages" note | planned | planned | planned | — | Decision B. |

### Encouragement to share (`engagement`)

| Feature | Web | Android | iOS | Frame | Notes |
|---|---|---|---|---|---|
| Home strip of frames and families with a freshness ring ("Fresh", "4 days", "2 weeks quiet") | planned | planned | planned | — | `FrameSummary.freshness`, `FeedSummary.freshness`. |
| "Send something to Oma's frame" with suggestions (recent photos on the device, On this day, the prompt) | planned | planned | planned | — | Device photos never leave the phone until picked. Web: On this day and the prompt only. |
| Ask for new photos (frame quick menu; "Asked today 💛"); setting to hide the button | — | — | — | yes | `deviceRequestPhotos`, `photo_request` change; `settings.social.ask_for_photos`. Frame: queued offline, then "Asked today 💛" or "Ben sent new photos 💛" until `next_allowed_at`. Browser frame: planned. |
| "Oma would like new photos" badge and notification; who answered | planned | planned | planned | — | `FrameSummary.photo_request`, push `frame_request`. |
| "New photos from Ben" on the next wake | — | — | — | yes | `settings.social.new_photos_from`. From new non-backfill placements' senders and the request's `answered_by_name`; kept in Room until the screen is awake. Browser frame: planned. |
| Reminder settings: normal / fewer / off, frame requests, weekly summary, birthday reminders, snooze, quiet hours; "we sent fewer reminders" notice | planned | planned | planned | — | `getReminderPreferences`, `updateReminderPreferences`. |
| Reminder notifications with Snooze 1 month and Fewer reminders actions | planned | planned | planned | — | `reminder_quiet`, `reminder_streak`, `reminder_birthday`, `weekly_summary`. |
| Family streak on the family header ("🔥 12 weeks", who helped this week); admins: goal, on/off | planned | planned | planned | — | Decision E: default 3. |
| Weekly prompts: this week's prompt, answer with it, "6 answers to …"; admins: turn on, pick, write own, skip | planned | planned | planned | — | |
| Prompt title card before the answers | — | — | — | yes | `prompts` change, `Placement.prompt_id`, `settings.social.prompt_cards`. Frame: a title card over the first answer of a run (no extra slide). Browser frame: planned. |
| On this day in Home, share again | planned | planned | planned | — | `getOnThisDay`. |
| Weekly "seen and loved" summary | planned | planned | planned | — | `getWeeklySummary`. |
| Monthly family recap card in the family chat | planned | planned | planned | — | `monthly_recap` system message, `getFeedRecap`. |
| Personal milestones (private, optionally shown on the profile) | planned | planned | planned | — | |
| Email digest of comments and messages: off / daily / weekly | planned | planned | planned | — | Only with discovery `email_digest`. |
| Family settings: quiet after N days, monthly recap, time zone | planned | planned | planned | — | `updateFeedEngagement`. |
| `features: ["comments", "photo_requests", "prompts"]` in the heartbeat | — | — | — | yes | Android frame app (both flavors). |

## Account settings

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Display name | yes | yes | yes | |
| Profile photo: change, remove | yes | new | yes | Android crops a centred square JPEG on the phone. iOS too. |
| Language | yes | new | differs | See "Language picker". |
| Notifications on/off | yes | differs | differs | Android: UnifiedPush or Firebase, and the system notification permission. iOS: APNs, behind a build flag that is off by default because the server can't send APNs yet; without it Settings says this version doesn't get notifications. |
| Server info and limits | yes | yes | yes | |
| Link my Telegram: link (opens `?start=` in Telegram), show the linked name, unlink | planned | planned | planned | Only with discovery `telegram`. |
| Sign out | yes | yes | yes | |
| Privacy and terms links | yes | new | new | |

## Show on TV

The web app reaches a TV with the Google Cast web SDK (Chromium), the browser's Presentation API, or the Remote
Playback API for one video. Android doesn't use any of these: it uses Android's own route, a presentation display.
iOS has none of them either (the Cast web SDK doesn't run in WebKit). It uses AirPlay: with the phone mirrored to an
Apple TV (or a cable), the app puts its own window on the external display while a session runs, and the phone is the
remote. For every other TV it makes links for the TV's browser.

| Feature | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Show photos at the TV's own pace, with interval | yes | new | new | iOS: the app plays the slideshow itself on the AirPlay screen, from a `cast` link that ends with the session. |
| Mirror the frame live; move the frame and the TV together | yes | new | new | iOS: the frame's TV page in a web view on the AirPlay screen, with the frame remote on the phone. |
| Make a TV browser a frame by its code | yes | new | new | |
| Use this browser as a frame | yes | skipped | skipped | A phone isn't a frame. |
| Link for the TV's browser: copy, open, turn off, active links | yes | new | new | Android and iOS also share the link through the share sheet. |
| Cast with Google Cast (Chromecast) | yes | differs | differs | Android: "Present on a screen" shows the TV page on a TV connected with the system's Cast screen (Chromecast, Google TV) or Miracast. No Google Play services needed. If no TV is connected it opens the Cast settings and starts as soon as one connects. iOS: AirPlay instead of Google Cast; Screen Mirroring has to be on first. Other TVs get a link. |
| Presentation API | yes | differs | differs | Android: an `android.app.Presentation` with the TV page in a WebView, using a short-lived `cast` link. iOS: an external display scene that gets a window only while a session runs, so otherwise iOS mirrors the phone as usual. |
| Play one video with Remote Playback | yes | skipped | skipped | "Present on a screen" plays videos too. On iOS the AirPlay slideshow plays videos to the end. |
| Now playing: previous, next, play/pause, stop | yes | differs | differs | Android sends the TV page the keys a remote sends. It has no position or caption from the TV, and the interval is chosen before starting. iOS: the phone is the remote for the app's own slideshow (previous, pause, next, interval, position and caption); stop also turns off the link. |
| Mini bar on every screen while showing | yes | new | no | iOS: the session keeps running when the Show on TV sheet closes, but the remote is only in that sheet. |
