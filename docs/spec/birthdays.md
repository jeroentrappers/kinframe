# Birthdays

Status: **draft** (2026-11-19). Extends `feeds.md` (a feed is "the family") and `product-spec.md` (§3 concepts, §4.9
countdowns). The API contract is in `docs/api/openapi.yaml` (tag `feeds`: `…/birthdays`, `…/birthday-greetings`,
`…/preferences`; tag `device`: `deviceGreetBirthday`; the `birthdays` change) and the protocol guide
`docs/api/README.md` §5.13. Tables are in §12 below.

## 1. Summary

A feed keeps a list of **birthday people**: Oma, Opa, the cousins, the new baby. Every frame subscribed to the feed
knows them. From a week before, the frame's countdown widget says "Oma turns 80 in 3 days"; on the day a warm card
says "🎈 Oma is 80 today!", balloons drift up once in a while, and a tap lets whoever is at the frame send "happy
birthday" back to the family. Family members get a short notification in the morning.

- **People, not accounts.** Oma and the baby usually have no account. A birthday person is a name and a date, with
  an optional year, an optional photo and an optional link to a feed member.
- **The feed owns the list.** Owner and admins manage it; posters suggest additions (or add directly when the feed
  allows it). Frames only show it.
- **The frame does the date maths.** The server sends each frame its list once (a `birthdays` change, like the
  calendar); the frame decides locally, in its own time zone, what is "today" and "in 3 days". It needs no network
  on the day.
- **Calm by design.** One card in the corner where the countdowns are, balloons for a few seconds at most once an
  hour, never waking the screen, nothing at all while the clock is not trusted.

## 2. Concepts

- **Birthday person** (`BirthdayPerson`): belongs to one feed. Fields:
  - `display_name` (1–40 characters), the name the frame shows ("Oma", "Lotte").
  - `day` and `month` of the birthday; `birth_year` optional (1900 … this year).
  - `show_age` (default **off**): the frame and notifications show the age ("is 80 today", "80th birthday") only when
    the year is known and this is on.
  - `photo`: optional. An uploaded square photo (stored and served like a member avatar: 256×256 WebP at an
    unguessable URL), or, without one, the avatar of the linked member.
  - `user_id`: optional link to a **member of the same feed** (the person has an account). Clients prefill the name
    from the member; the member's avatar is the fallback photo; the linked member may edit the privacy-relevant
    fields of their own entry (§4). At most one person per feed links to the same member.
  - `status`: `pending` (a poster's suggestion), `approved` (on the list, shown on frames) or `rejected`.
- **Birthday occurrence**: the date in a given year: `day`/`month` of that year; **29 February falls on 28 February in
  non-leap years**.
- **Age**: on an occurrence in year Y, `Y − birth_year`. Shown only when `show_age` is on. With a year, occurrences
  in the year of birth are not birthdays (§10).
- **Name key**: the name normalized for comparing: Unicode NFKC, case-folded, diacritics removed, trimmed, inner
  whitespace collapsed ("Zoë  " = "zoe"). Within one feed, two live entries (pending or approved) may not share name
  key + day + month (`409 duplicate_birthday`). Across feeds the same rule merges entries on a frame (§6.5).
- **Birthday subscription**: an `active` subscription with `birthdays: true` (the default). A frame shows the approved
  people of every feed it has a birthday subscription to.
- **Greeting** (`FeedBirthdayGreeting`): "Oma's frame says happy birthday to Lotte 🎉", sent by a frame on the day,
  recorded in each feed that lists the person, shown in the family apps and notified to members who opted in.
- **Member preference** `birthday_notifications` (per member, per feed, default **on**): the morning notification
  and greeting notifications for that feed's people.
- **Feed setting** `posters_can_add_birthdays` (default **off**): posters add and edit their own entries directly
  instead of suggesting.

## 3. User stories

### Family app and web

1. *Add Oma.* Anna (feed owner) opens Peeters kids → Birthdays → Add. She types "Oma", picks 12 March, enters 1946,
   leaves "Show age" off and picks a photo (cropped to a square on the phone, like a profile photo). Oma is on the
   list at once and on every subscribed frame within seconds.
2. *Add yourself.* Ben (poster) taps "Add my birthday": the name and avatar come from his account and the entry is
   linked to him. Without *posters can add birthdays* it is a suggestion: "Waiting for Anna or another admin".
3. *Suggest the baby.* Carla (poster) suggests "Lotte, 2 June 2026". Anna gets a push "Carla suggests adding Lotte's
   birthday"; she opens it, corrects the spelling and approves. Carla gets "Lotte's birthday was added". Had Anna
   rejected it, Carla would see it as declined for 30 days.
4. *See what's coming.* Everyone in the feed sees the list sorted by the next birthday ("Oma · 12 March · in 5
   days"). Years show only where the viewer may see them (§9).
5. *Morning.* On 12 March at about 08:00 (in her own time zone) Carla gets "Today is Oma's birthday 🎈". With "Show
   age" on it would read "Today is Oma's 80th birthday 🎈".
6. *Greetings.* At 10:14 the notification "🎉 Opa's frame says happy birthday to Oma" arrives; the feed's Birthdays
   screen shows "Greetings today: Opa's frame 10:14, Kitchen 11:02".
7. *Mute.* Ben turns off "Birthday notifications" for Peeters kids (Feed → Notifications). He still sees the list.
8. *Edit and remove.* Anna changes Oma's photo, turns "Show age" on for the 80th, removes a cousin who asked to be
   left out. The frames follow at their next sync.
9. *Own entry.* Ben, linked to his entry, turns "Show age" off and removes his year. He could also remove the entry.

### Frame owner (frame manager)

1. In Oma's frame settings → Feeds, every subscription has "Birthdays: on/off" (default on). Turning it off takes that
   feed's people off the frame; photos are unaffected.
2. Subscribing (request, invite) offers the same switch; it is on unless turned off there.
3. Pausing or ending a subscription takes its people off the frame, like its photos.

### Frame viewer

1. From 5 March a countdown tile reads "7 days · Oma turns 80" (or "Oma's birthday"), between the event countdowns.
2. On 12 March, when the screen wakes in the morning, the corner shows the birthday card with Oma's photo:
   "🎈 Oma is 80 today!" (or "It's Oma's birthday today!"). A few balloons drift up across the screen for about six
   seconds, and again once an hour while the screen is on.
3. Opa taps the card: a small sheet "Say happy birthday to Oma from this frame?" with one large button. He taps it;
   the button turns into "Sent ✓ — the family will see it". The family gets the greeting. Tapping again the same day
   shows "Sent ✓" and sends nothing.
4. On the days two people share, the card lists both ("🎈 Birthdays today · Oma is 80 · Lotte"), and the sheet has
   one button per person.

## 4. Permissions

### 4.1 Feed roles × birthday actions

"Own entry" = the person is linked to the caller (`user_id`). "Added by me" = the caller created the entry (as a
direct add or a suggestion).

| Action | owner | admin | poster | poster with *posters can add birthdays* |
|---|---|---|---|---|
| See approved people (name, date, photo, show_age, link) | ✓ | ✓ | ✓ | ✓ |
| See `birth_year` while `show_age` is off | ✓ | ✓ | own entry and added by me | own entry and added by me |
| Add a person (approved at once) | ✓ | ✓ | — | ✓ |
| Suggest a person (`pending`) | — (adds directly) | — (adds directly) | ✓ | — (adds directly) |
| See pending and rejected suggestions | all | all | own suggestions | own suggestions |
| Edit or withdraw (delete) own pending suggestion | ✓ | ✓ | ✓ | ✓ |
| Approve / reject a suggestion (may edit it first) | ✓ | ✓ | — | — |
| Edit an approved person (name, date, year, show_age, photo, link) | ✓ | ✓ | own entry: `birth_year`, `show_age`, photo, unlink | added by me: all fields; own entry: as poster |
| Remove an approved person | ✓ | ✓ | own entry | added by me and own entry |
| Link a person to a feed member | ✓ (any member) | ✓ (any member) | in a suggestion (any member) | any member, on entries added by me |
| Change *posters can add birthdays* | ✓ | ✓ | — | — |
| Set own `birthday_notifications` for this feed | ✓ | ✓ | ✓ | ✓ |
| See greetings (`listFeedBirthdayGreetings`) | ✓ | ✓ | ✓ | ✓ |

Notes:

- A poster's own entry is theirs to control: they may hide their age, drop the year, change the photo, unlink, or
  remove themselves from the list, whoever added it.
- An admin approving a suggestion may change any field first (`updateFeedBirthday` on a pending entry, then approve).
- People outside the feed, including frame members who are not feed members, see nothing (`404 not_found`, as for
  feeds). The frame manager's subscription screen shows only how many people the feed lists ("12 birthdays").

### 4.2 Frame side

| Action | frame manager (owner, admin, *change settings*) | other frame member | at the frame |
|---|---|---|---|
| Turn `birthdays` on/off per subscription (`updateSubscription`, also at request time) | ✓ | — | — |
| See the birthdays the frame shows | in the feed only if a feed member; otherwise the count on the subscription | — | ✓ (card, countdowns, calendar) |
| Send a greeting (`deviceGreetBirthday`) | — | — | ✓ (anyone at the frame; once per person per day) |

## 5. States

### 5.1 Birthday person

```
           create by owner/admin, or poster with posters_can_add_birthdays
   ┌──────────────────────────────────────────────────────────────┐
   │                                                              ▼
create ──▶ pending ──────────approve (owner/admin)──────────▶ approved ──remove──▶ (deleted)
 (poster)    │  │                                                │
             │  └──withdraw (suggester) / remove (admin)──▶ (deleted)
             └──reject (owner/admin)──▶ rejected ──30 days──▶ (deleted)
```

| Status | Meaning | On frames | Counts toward `max_birthdays_per_feed` |
|---|---|---|---|
| `pending` | A poster's suggestion waiting for the feed's owner or an admin. Editable by the suggester and admins. | No | Yes |
| `approved` | On the feed's list. | Yes, on every birthday subscription | Yes |
| `rejected` | Declined. Visible to the suggester and admins for 30 days, then deleted. A new suggestion for the same person is allowed (the uniqueness rule ignores rejected rows). | No | No |

- Removing (`DELETE`) deletes the row, its photo and its greetings at once; there is no trash. The apps offer Undo
  (re-create from the cached entry, photo included while the client still has it) instead of "are you sure".
- Repeating a transition the entry is already in returns it unchanged (`200`); approving or rejecting a decided
  entry answers `409 already_decided`.
- Approving fails with `409 duplicate_birthday` when an approved entry with the same name key and date appeared
  meanwhile (the admin then rejects or edits the suggestion).

### 5.2 Greeting

A greeting has no states: it exists or not, one per **(birthday person, frame, occurrence date)**. It is kept 13
months, then deleted, and deleted at once with the person, the feed or the frame.

## 6. On the frame

### 6.1 What the frame knows

The frame keeps the last `birthdays` change (§11) in its local state, like the calendar. Every entry has a name, day,
month, `birth_year` **only if the age may be shown**, a photo URL (downloaded and cached like member avatars) and the
feeds it comes from. Everything else is computed on the frame.

### 6.2 Time

- **Time zone**: the frame's `locale.timezone` (the zone the calendar and the clock use). A day runs from local
  midnight to midnight.
- **Clock not trusted** (`clockWrong`: a frame without a clock battery that booted at its factory date, before NTP or
  the server's `hello.server_time` corrected it): no birthday card, no birthday countdown tiles, no balloons, no
  greeting button. As soon as the clock is trusted the frame evaluates the day and shows what applies; a card that
  appears this way counts as "first appearance" (§6.4).
- **Time zone change** (settings `locale.timezone`, or the system zone): the frame re-evaluates at once (it already
  restarts its minute ticker on `ACTION_TIMEZONE_CHANGED`). The card follows the new local date. Greetings are keyed
  by the local date at the moment of the tap, so a zone change can never produce two greetings for one date.
- **Midnight**: the minute ticker re-evaluates at every minute boundary, so the card appears at 00:00 and leaves at
  24:00. With the default sleep schedule (23:00–07:00) people first see it when the screen wakes.
- **Leap day**: 29 February people are "today" on 29 February in leap years and on 28 February otherwise, for the
  card, the countdown and the greeting date alike. Their age on 28 February of a non-leap year is `Y − birth_year`.

### 6.3 The birthday card

- **Where**: in the **top-right corner column where the countdown tiles are**, as the first entry, with the same
  maximum width (360 dp) and the same smoked-glass style on photos (one translucent fill, no blur; Mali-400 budget).
  On the paper empty state it goes where the countdowns go there (bottom right, surface card). It never covers more
  of the photo than the three countdown tiles it shares the column with (§6.6).
- **Content, one person**: the round photo (56 dp; without a photo a balloon on a warm `rose` disc), then
  "🎈 Oma is 80 today!" or "It's Oma's birthday today!" (localized phrases with plural/ordinal rules; the name is
  ellipsized, never the phrase).
- **Several people the same day** (after dedupe, §6.5): one card. The title "🎈 Birthdays today", then one line per
  person, up to three ("Oma · 80", "Lotte"), each with a 32 dp photo, then "and 2 more" when needed. Order: people
  with photos first, then by name.
- **Visibility**: shown whenever the countdown column is (not while the quick menu, the full-screen calendar, the
  event details, an invitation card, a join card, settings or a plugin detail are open; not in split mode, where the
  dashboard shows it instead, §6.7). It does **not** depend on `widgets.countdowns`: turning countdowns off hides the
  "in N days" tiles, not the day itself. The frame owner controls birthdays per subscription.
- **Tap**: opens the greeting sheet (§7). The card itself never navigates anywhere else.
- **Never wakes the screen.** A birthday does not change the display mode. During scheduled sleep, screen-off by
  hand, motion timeout or "dim instead of off", nothing about birthdays runs except the date check.

### 6.4 Balloons

- 5–7 balloons (vector shapes in warm palette colours, cached as small bitmaps once) rise from below the bottom edge
  to above the top edge over about **6 seconds**, each with a slight sideways sway, then the layer is removed.
  Each balloon is one translated layer: no full-screen fills, no blur, no shadows, within the Mali-400 fill budget.
- **When**: once when the card **first appears on an awake screen that day** (at wake-up, at midnight on a screen
  that is on, when the clock becomes trusted, or when the list arrives during the day), then **once an hour** after
  that while the card is visible, aligned to that first appearance (10:12, 11:12, …).
- **Skipped (not queued) when**: the screen is asleep, fading, off or dimmed; a video or the motion of a live photo is
  playing (decoder and GPU are busy on weak frames); the quick menu, settings, calendar, a card or the new-photo
  viewer is open; a transition is running (the next hourly run waits until the slide has settled). A skipped hourly
  run moves to the next hour. A screen that wakes counts as a first appearance again only if no balloons ran in the
  last hour.
- Balloons never block touch: they are drawn above the slideshow but pass every touch through.

### 6.5 Dedupe across feeds

A frame subscribed to both sides of the family can get "Oma" twice. Approved people from all its birthday
subscriptions are merged on **name key + day + month** (the year is not part of the key, so an entry without a year
merges with one that has it). The server does the merge before sending, so every frame gets one `FrameBirthday` per
person with `birthday_ids` (all merged entries) and `feed_ids`:

| Field | Rule |
|---|---|
| Primary entry (`id`) | The merged entry **with a photo** whose subscription was activated first; if none has a photo, the one whose subscription was activated first; ties: the oldest entry. |
| `name` | The primary's `display_name`. |
| Photo | The primary's photo (own photo, else its linked member's avatar). |
| `birth_year` | Sent only if **every** merged entry that has a year has `show_age` on, at least one has a year, and all given years agree. Otherwise absent: the most restrictive choice wins, and a conflict shows no age rather than a wrong one. |
| `feed_ids` / `birthday_ids` | Every merged feed and entry. A greeting goes to all of them (§7). |

Within one feed the uniqueness rule (§2) prevents duplicates. Two different people with the same name and birthday
in two feeds would merge; that is rare enough to accept (one card for both is still right on that day).

### 6.6 Coexistence with countdowns and other widgets

The top-right column shows at most **three entries** in this order:

1. The birthday card on the day (counts as one entry, whatever the number of people).
2. Countdown tiles: event countdowns (marked events, existing) and **birthday countdowns** merged.

Birthday countdowns:

- One for every occurrence 1 to 7 days ahead (from the local date): "Tomorrow · Oma turns 80", "3 days · Oma's
  birthday". People sharing a day share one tile ("3 days · Oma and Lotte", "3 days · Oma and 2 more").
- The tile looks like an event tile, with a small balloon in place of the colour dot.
- Shown only with `widgets.countdowns` on (default), like event countdowns.

Ordering and cut-off:

- Merged by date, nearest first; on the same day birthday tiles come before timed events and after nothing (birthdays
  are all-day); ties by title.
- At most three entries in total (two when the birthday card is there). If the cut leaves out every event countdown
  while one exists, the last birthday tile gives way to the nearest event countdown: an event the owner marked by
  hand always gets a slot.
- Tapping a birthday tile opens the frame's calendar on that day, as event tiles do.

Other widgets are unchanged:

| Widget / card | Rule with a birthday |
|---|---|
| Clock, date, weather (bottom corner) | Unchanged. |
| "N new" pill (top centre) and the new-photo viewer | Unchanged; the birthday card stays in its corner. Balloons wait until the viewer is closed. A new photo wakes the screen as today; the birthday does not. |
| Status cards, join requests, invitations pills (top left) | Unchanged. |
| Captions | Unchanged (bottom); the card is at the top. |
| Plugin widgets in the top-right slot | Below the countdown column, as today; the column has the same maximum height (three entries). |
| Hybrid calendar sidebar | "Today" starts with "🎈 Oma is 80 today" as an all-day row; the corner card still shows. |
| Split dashboard | The dashboard's "next 48 hours" lists the birthday first; no corner card (the column is hidden in split mode today). |
| Full-screen calendar (also timed views) | Every occurrence in the visible range is an all-day entry "🎈 Oma (80)" in colour `rose`, computed on the frame (not part of the `calendar` change, not editable, no countdown toggle). Tapping it on the day opens the greeting sheet; on other days a small detail card ("Oma · 12 March · turns 80"). No card, no balloons while the calendar is up. |

### 6.7 Old frame apps and browser frames

- Frame apps that don't know the `birthdays` change ignore it (clients must ignore unknown change types, API guide
  §7): they show nothing, and nothing else changes. The server withholds nothing.
- The frame app lists `birthdays` in its heartbeat `features` once it shows them. The server only uses this to tell
  the apps (`Subscription.frame.birthdays_supported`) so the frame's subscription screen can say "Update the frame
  app to show birthdays". Browser frames (`/tv/frame`) list it when the web frame supports it.

## 7. Greetings

- **What it means**: someone at frame F said happy birthday to person P on date D. Not a message with text: the frame
  sends only `(P, D)`; the server knows F from the device token.
- **Frame UI**: tapping the card opens a sheet with one button per person on the card: "Say happy birthday to Oma"
  (with the photo). One tap sends; the button turns into "Sent ✓" and stays so for the rest of the day (persisted on
  the frame per local date). No confirmation, no text entry. The sheet closes after 15 s like the quick menu.
- **Call**: `PUT /v1/device/birthdays/{birthdayId}/greetings/{date}` with any id of `FrameBirthday.birthday_ids` (the
  frame uses `id`) and D = the local date. Idempotent: `204` whether it was recorded now or before.
- **Rate limit**: one greeting per person per frame per local date, enforced by the server (unique key) and by the
  frame (button state). The server also caps a frame at 50 greetings a day overall (`429`) against bugs.
- **Validation**: D must be an occurrence date of P (leap rule included) and within the server's UTC date −2 … +1
  days (time zones and a frame that was offline for a while); otherwise `422 validation_failed` (`errors[].field:
  date`). P must currently be on this frame (an approved entry of a feed with a birthday subscription of this frame),
  otherwise `404 not_found`.
- **Offline**: the tap is queued in the frame's outbox like reactions and sent when the frame is online again. Queued
  greetings older than two days, or answered with 404/422, are dropped silently; the button already showed "Sent".
- **Fan-out**: the server resolves P to its merged entries on F (the `birthday_ids` it sent) and records one greeting
  per entry, i.e. **one per feed that lists the person**, each with a snapshot of the frame's name and location
  label. Both sides of the family see that Opa's frame greeted Oma.
- **In the apps**: the feed's Birthdays screen shows today's greetings per person ("🎉 Opa's frame 10:14 · Kitchen
  11:02"); `listFeedBirthdayGreetings` returns them newest first (all feed members may see them; frame names are
  already visible to feed members for active subscriptions, `feeds.md` §9).
- **Notification**: websocket `birthday.greeting` to every feed member; push `birthday_greeting` to members with
  `birthday_notifications` on for that feed and no open websocket. A member in several feeds that list the person gets
  **one** push per greeting. Pushes for the same person and day replace each other on the phone (clients tag them with
  `birthday:<birthday_id>:<date>`), and the server writes the running count in the body: "🎉 Opa's frame and 2 other
  frames say happy birthday to Oma".

## 8. Notifications

All go over the user websocket and, without an open websocket, as push, localized, carrying IDs only (API guide
§6.3).

| Event | Who | Websocket | Push key |
|---|---|---|---|
| A poster suggested a person | Feed owner and admins | `birthday.suggested` | `birthday_suggestion` |
| Suggestion approved / rejected | The suggester | `birthday.decided` | `birthday_decided` (texts `birthday_approved` / `birthday_rejected`) |
| Person added, changed, removed (any status the member may see) | Feed members | `birthday.updated` / `birthday.removed` | — |
| Morning of the day | Members with `birthday_notifications` on (§8.1) | `birthday.today` | `birthday_today` |
| A frame said happy birthday | All members (websocket); members with `birthday_notifications` on (push) | `birthday.greeting` | `birthday_greeting` |

### 8.1 The morning notification

- **Text**: "Today is Oma's birthday 🎈", "Today is Oma's 80th birthday 🎈" (age only with `show_age`, same rules as
  the frame), several: "Today is Oma's 80th birthday and Lotte's birthday 🎈" / "Today: 4 birthdays in the family 🎈
  Oma, Lotte, …".
- **One per member per day**: the member's birthdays from all their feeds with `birthday_notifications` on, merged on
  name key + day + month like on frames. The member's own entry (linked to them) is left out: nobody needs to be told
  their own birthday.
- **When**: at **08:00 in the member's time zone**, on the member's local date. The time zone is `User.timezone`, which
  the apps and the web set automatically from the device (like `locale`). Without one (old clients), the server uses
  the time zone most of the feed's active subscribed frames have (`locale.timezone`), then `Europe/Brussels` (the
  settings default).
  - *Why the member's zone*: families spread across countries (the aunt in Canada); a feed-wide 08:00 Brussels would
    wake her at 02:00. The server does not know a member's zone today, so this adds `timezone` to `User`; the
    frames' zones are the best fallback because the feed is about the people around those frames.
- **Delivery**: a server loop every 5 minutes picks members whose local time is between 08:00 and 20:00, who have
  not had today's notice yet (`birthday_notices`), and who have at least one birthday today. A server that was down
  at 08:00 catches up until 20:00; after that the day is skipped.
- **Preferences**: kinframe has no global notification settings or quiet hours yet; the per-feed
  `birthday_notifications` switch is the only preference, and phone-level notification settings apply. 08:00 is
  inside any reasonable quiet-hours window; when global quiet hours exist the notice is held until they end
  (Open question 1).

## 9. Privacy

- **Who sees birthdays**: members of the feed (all roles) and the frames with a birthday subscription to it. No one
  else: not frame members who are not in the feed, not other feeds, not the public. Unknown and forbidden
  feeds/entries both answer `404 not_found`.
- **Birth year**: never sent to frames unless `show_age` is on (then `birth_year` is sent, see below). In the apps
  the year is returned to owner/admins, to whoever added the entry and to the linked member; to other members only
  when `show_age` is on (then it is visible on the frame anyway).
  - *Why the year and not the age*: with the date known, an age and a year disclose exactly the same thing, so
    sending the year leaks nothing more. The year lets the frame compute "turns 80" a week ahead and "is 80 today" in
    its own time zone without the server re-emitting a change every day, and keeps the frame correct offline.
- **Photos** are stored like avatars: one 256×256 WebP at an unguessable URL that needs no auth (so frames and
  `<img>` can cache it). Replacing or removing the photo, or the person, deletes the file; the new one gets a new
  random name. The same trust model as avatars (product spec §8 decision 3: the operator sees everything).
- **Linked members** control their own entry (§4.1) and can remove it.
- **Removal reaches frames**: removing a person, rejecting it, turning `birthdays` off on a subscription, pausing,
  unsubscribing, removing the subscription and deleting the feed all emit a new `birthdays` change to the frames
  concerned; the frame drops the person and deletes the cached photo.
- **Greetings** reveal to feed members that a frame (by name) said happy birthday at a time. They carry no text and
  nothing about who stood at the frame.

## 10. Edge cases

| Case | Behaviour |
|---|---|
| Feed deleted | Its people, photos and greetings are deleted with it; every frame with a birthday subscription gets a new `birthdays` change (the subscriptions end anyway, `feeds.md` §6.3). |
| Person removed (or its date changed) during their day | The next change drops (or moves) it; the card updates or disappears at once, mid-balloon if need be. Removing deletes its greetings too. A queued greeting gets 404 and is dropped. |
| Person's name or photo edited on the day | The card updates; balloons do not run again for it. |
| Linked member leaves the feed, is removed, or deletes their account | The link is cleared (`user_id: null`); the entry stays. If it used the member's avatar as its photo, it has no photo from then on (frames get a new change). Their year-visibility rights end. |
| Linked member changes their avatar | Entries using it as fallback photo are re-emitted. |
| Suggester leaves the feed | The suggestion stays pending; admins see "a former member". |
| Subscription paused, `birthdays` turned off, unsubscribed, frame removed | The feed's people leave the frame with the next change (frame removed: nothing to send). Merged people stay if another birthday subscription still lists them; `id`, `name`, photo and `birth_year` are recomputed from what remains. |
| Subscription resumed / `birthdays` turned on | The people come back with the next change. On their day the card appears and counts as a first appearance. |
| Frame offline on the day | It shows the card from its stored list (computed locally); greetings are queued (§7). |
| Frame clock not yet trusted | Nothing birthday-related shows until it is (§6.2). |
| Frame time zone changed | Re-evaluated at once in the new zone (§6.2). |
| Frame asleep all day (sleep schedule covers it, or the screen was off) | Nothing is shown and nothing is queued; the family still gets the morning notification. |
| A video is on screen when the card first appears | The card shows; the balloons wait until the video has ended or the slide changed, within the first hour. |
| 29 February | §6.2; in the apps the date shows as "29 February" and the next occurrence as 28 February in non-leap years. |
| Invalid date | 30/31 February, 31 April etc. are rejected (`422 validation_failed`, field `day`); 29 February with a non-leap `birth_year` too. |
| Birth year later than this year, or before 1900 | `422 validation_failed` (`birth_year`). |
| Year of birth (Lotte born 2 June 2026, `birth_year` 2026) | The date of birth itself is not a birthday: in 2026 there is no card and no countdown for her. The first card is on 2 June 2027 ("Lotte is 1 today!" with `show_age`). Without a year the frame cannot tell and shows every occurrence. |
| Very old person (age > 120) | Allowed (the year floor is 1900). |
| Same person in two feeds with different photos, years or show_age | §6.5. |
| More than three people on one day | Card shows three and "and N more"; the greeting sheet lists all (scrolls). |
| Feed reaches `max_birthdays_per_feed` | Create and suggest answer `409 limit_reached`; pending suggestions count, rejected ones do not. |
| Same person suggested twice | The second answers `409 duplicate_birthday` with the existing entry's id in `detail`, also when the first is still pending. |
| Greeting for a person on a frame that no longer shows them | `404 not_found`; the frame drops it. |
| Feed renamed | Nothing changes on frames (birthdays carry feed ids, not names). |

## 11. Delivery to frames

- **A new change type `birthdays`** (entity: the frame, like `calendar` and `weather`), payload `FrameBirthdays`:
  `generated_at` and `people` (`FrameBirthday`: `id`, `name`, `day`, `month`, `birth_year`?, `photo_url`?,
  `feed_ids`, `birthday_ids`), sorted by month and day. `birthdays: null` means "drop your birthdays" (no birthday
  subscription lists anyone any more); snapshots (`since=0`) omit it then.
- **Why this shape**: it is exactly how the calendar reaches frames (one compacted per-frame document rendered from
  current state at read time), so the frame stores it the same way (`SyncEngine` keeps the latest JSON) and needs no
  new endpoint, no polling and nothing on the day itself. Settings were not used because they are a user-edited,
  versioned document; the `calendar` change was not used because it exists only for frames with calendar sources, is
  windowed (±30 days) and depends on the server's calendar feature being on.
- **When the server emits it** (bump the frame's `birthdays` entry under the frame lock; the read renders it; a
  digest of the rendered payload avoids emitting unchanged content):
  - an approved person is created, edited or removed, or a suggestion is approved: every frame with a birthday
    subscription to that feed;
  - its photo changes, or the linked member's avatar changes while used as the photo: the same frames;
  - a subscription becomes active, is paused, resumed, removed, or its `birthdays` flag changes: that frame;
  - the feed is deleted, the link to a member is cleared: as above.
  No daily emission: nothing in the payload depends on today.
- **No heartbeat gating**: nothing is withheld from old apps; `features: ["birthdays"]` is informational (§6.7).
- **Photo download**: `photo_url` is a public avatar-style URL (no device token needed); the frame caches it under a
  hash of the URL and deletes files no entry references after applying a change.

## 12. Data model

Migration `00014_birthdays.sql` (proposal, against `00013_feeds.sql`):

```sql
ALTER TABLE feeds ADD COLUMN posters_can_add_birthdays boolean NOT NULL DEFAULT false;
ALTER TABLE feed_members ADD COLUMN birthday_notifications boolean NOT NULL DEFAULT true;
ALTER TABLE subscriptions ADD COLUMN birthdays boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN timezone text;                  -- IANA name, set by the apps (PATCH /v1/me)

CREATE TABLE feed_birthdays (
    id            uuid PRIMARY KEY,
    feed_id       uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    status        text        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    display_name  text        NOT NULL,
    name_key      text        NOT NULL,                      -- NFKC, case-folded, no diacritics, collapsed spaces
    month         smallint    NOT NULL CHECK (month BETWEEN 1 AND 12),
    day           smallint    NOT NULL CHECK (day BETWEEN 1 AND 31),
    birth_year    smallint             CHECK (birth_year >= 1900),
    show_age      boolean     NOT NULL DEFAULT false,
    user_id       uuid REFERENCES users ON DELETE SET NULL,  -- linked feed member; cleared when they leave
    photo_key     text,                                      -- avatar-style blob key; null = none (avatar fallback)
    created_by    uuid REFERENCES users ON DELETE SET NULL,
    suggested     boolean     NOT NULL DEFAULT false,        -- created as a suggestion
    decided_by    uuid REFERENCES users ON DELETE SET NULL,
    decided_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (make_date(2000, month, day) IS NOT NULL),          -- 2000 is a leap year: allows 29 Feb, rejects 30 Feb
    CHECK (birth_year IS NULL OR month <> 2 OR day <> 29
           OR (birth_year % 4 = 0 AND (birth_year % 100 <> 0 OR birth_year % 400 = 0)))
);
CREATE UNIQUE INDEX feed_birthdays_unique ON feed_birthdays (feed_id, name_key, month, day)
    WHERE status IN ('pending', 'approved');
CREATE UNIQUE INDEX feed_birthdays_one_per_user ON feed_birthdays (feed_id, user_id)
    WHERE user_id IS NOT NULL AND status IN ('pending', 'approved');
CREATE INDEX feed_birthdays_feed_idx ON feed_birthdays (feed_id, status);
CREATE INDEX feed_birthdays_day_idx ON feed_birthdays (month, day) WHERE status = 'approved';

-- One per (person, frame, occurrence date): the rate limit is the key.
CREATE TABLE birthday_greetings (
    id             uuid PRIMARY KEY,
    birthday_id    uuid        NOT NULL REFERENCES feed_birthdays ON DELETE CASCADE,
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    frame_id       uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    local_date     date        NOT NULL,
    frame_name     text        NOT NULL,                     -- snapshot for the activity list
    frame_location text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (birthday_id, frame_id, local_date)
);
CREATE INDEX birthday_greetings_feed_idx ON birthday_greetings (feed_id, created_at DESC);

-- The morning notice: at most one per member and local date.
CREATE TABLE birthday_notices (
    user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
    local_date date NOT NULL,
    sent_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, local_date)
);
```

Notes:

- `make_date` raises on an invalid date rather than returning null; the store validates first and answers
  `422 validation_failed`, the CHECK is the backstop (use an immutable SQL helper if `make_date` in a CHECK is
  refused).
- The frame's payload is rendered at read time: approved `feed_birthdays` of feeds with an `active` subscription of
  the frame where `subscriptions.birthdays`, merged per §6.5 (`activated_at` orders the primary). The change-feed
  entry kind is `birthdays` with entity = frame id, like `calendar`.
- Rejected rows and greetings older than 13 months are deleted by the maintenance loop; `birthday_notices` older than
  7 days too.
- The photo pipeline is the avatar one (square crop, 256×256 WebP, public `/avatars/b/{random}.webp`-style key).
- Limits: `max_birthdays_per_feed` (default 200; `KINFRAME_MAX_BIRTHDAYS_PER_FEED`, `PUT /v1/operator/limits`),
  counting `pending` and `approved`. Create/suggest is rate-limited at 60/h per user, like invites.

## 13. Release plan

Phase **+**, planned as **v1.2 Birthdays**, after v1.1 Feeds:

1. Server: table, endpoints, `birthdays` change, greetings, notifications and the morning loop, `User.timezone`;
   web: Birthdays screen in a feed (list, add, suggest, approve, photo, greetings), feed setting, notification switch,
   subscription switch, and sending `timezone`.
2. Family apps (Android, iOS): the same, plus "Add my birthday" and push handling (tagging greetings per person/day).
3. Frame (Android): card, countdown merge, balloons, calendar entries, greeting sheet, `features: ["birthdays"]`;
   browser frame (`/tv/frame`) after. Older frame apps show nothing and lose nothing.

Phase 2 (not now):

- Tagging photos as "of Oma" so they play more often on her birthday (needs people tagging on items).
- Birthdays outside feeds: a per-frame birthday list for frames that subscribe to no feed.
- Importing from contacts or calendars (vCard `BDAY`, a calendar's birthday events).
- Greetings from the apps (members saying happy birthday, shown on the person's frame).
- Other yearly days (anniversaries, "in memory of").

## 14. Decisions and open questions

Taken (2026-11-19), encoded above:

1. People, not accounts; optional link to a feed member (§2).
2. Owner/admins manage; posters suggest; `posters_can_add_birthdays` (default off) lets them add (§4).
3. On the day: a card over the slideshow, balloons on first appearance and hourly, one card for several people, sleep
   respected, no more coverage than the existing widgets (§6).
4. Tap to greet, once per person per frame per day (§7).
5. Countdowns from 7 days before, merged with event countdowns (§6.6).
6. Morning notification per member and feed, default on, at 08:00 in the member's zone (§8.1).
7. `birthdays` per subscription, default on; dedupe across feeds (§6.5).
8. Birth year to frames only with `show_age` (§9).
9. Delivery as a `birthdays` change; no feature gating (§11).
10. Out of scope: photo tagging, per-frame lists, imports (§13).
11. `max_birthdays_per_feed` 200; discovery `birthdays` (only with `feeds`).

Open questions (proposals in this spec that go beyond or bend the decisions above):

1. **No notification preferences or quiet hours exist** in kinframe today. "Respecting existing notification
   prefs/quiet hours" therefore means only the per-feed switch and the phone's own settings. Global quiet hours (and
   a global "all birthday notifications" switch) would be a separate feature; the morning notice would then wait for
   them to end.
2. **`User.timezone` is new.** The decision left member vs feed time zone open; the member's zone needs the apps to
   send it. Until they do, the fallback is the feed's frames' most common zone. Alternative: a `timezone` on the feed
   (one 08:00 for everyone), simpler but wrong for families abroad.
3. **One switch for two kinds of notifications.** Decision 4 says greetings go to "members who opted in"; this spec
   reuses `birthday_notifications` (morning + greetings) rather than adding a second switch. A family with many
   frames may want greetings off but the morning notice on.
4. **Card in the corner, not centred.** "A warm card over the slideshow" and "not cover more of the photo than the
   existing widgets" pull in different directions; the spec picks the countdown corner. A larger centred card for the
   first minute after wake-up would be warmer but covers the photo; worth testing on a real frame.
5. **Most restrictive `show_age` across feeds** (§6.5): one side of the family hiding Oma's age hides it on every frame
   that also subscribes to the other side. The alternative (per-feed choice, primary wins) shows the age on frames
   that one admin wanted hidden.
6. **Birth year visibility in the apps** (§9) is narrower than "members can see the list": posters see the year only
   with `show_age` on (or for their own and their own additions).
7. **The birthday person's own frame.** On Oma's own frame, the button says "Say happy birthday to Oma". The server
   cannot tell whose frame it is. A per-subscription or per-frame "this frame belongs to …" link (or reusing the
   frame name) could turn it into "Thank everyone" instead. Not designed.
8. **In memoriam.** A person who passed away: today an admin removes them (or the frame owner turns birthdays off).
   A gentle "would have been 90" mode without balloons is a phase-2 idea.
9. **A frame-wide switch** (`widgets.birthdays`) was not added: decision 7 puts control per subscription. A frame with
   several feeds needs several taps to turn birthdays off; `widgets.countdowns` already hides the "in N days" tiles.
10. **Reserved event slot** (§6.6): a marked event countdown always keeps one slot even if three birthdays are closer.
    This is a judgement call; the alternative is pure date order.
11. **Photo URLs without auth** follow the avatar model. Birthday photos of children who have no account might
    deserve signed, expiring URLs instead (frames would then need to re-fetch them); avatars made the opposite
    choice for cacheability.
12. **Merging different people** who share a name and birthday across two feeds (§6.5) is accepted as rare. A frame
    manager cannot split them.
