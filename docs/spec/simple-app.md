# The simple family app

Status: **accepted** (2026-11-20). Changes the navigation and wording of the family apps (Android, iOS, web) described
in `product-spec.md`, `feeds.md` and `family-social.md`. The data model and the API stay as they are, apart from one
approval rule (§7). Design canvas with before and after screens: https://claude.ai/artifact/Fi7MK3JssJLQM7p9U3uk8t

## 1. Why

Testers found the app too complicated: it was unclear what they had to do. The app had grown into two apps in one —
sending to frames (Frames, Send, History) and posting to families (Home, Families, Chats) — with seven tabs, two
places for people, two places for photos, and a three-step request to show a family on a frame.

## 2. The idea

An Instagram for the family. Three words: **family**, **post**, **frame**.

- **The family** is where photos are shared: typically one household. Anyone can start one, with or without a frame.
  People are often in several families (their own household, their parents', their children's); the app shows all
  of them together (§3.1), so someone in one family never notices the concept of "several".
- **Everyone is family.** One kind of member in the interface (the server keeps owner/admin/poster for permissions).
  Everyone sees everything and can post. Oma is a member like the rest; on her phone she can pick the simple view
  (§6).
- **Frames show families.** A frame "shows" one or more families (server: active subscriptions). Added now or later.

### 2.1 The reference family

Jeroen and Line have 3 kids; Jeroen has 2 brothers and a sister, each with a partner and 2 kids; Jeroen's and Line's
parents are alive. Each household is a family: "Trappers" (Jeroen, Line, kids), one per sibling, "Opa & Oma
Trappers", and Line's parents' family. Everyone posts once, to their own family.

- Jeroen's frame at home shows Trappers. Each sibling's frame shows their own family.
- Jeroen's parents' frame shows five families: their own and their four children's. Line's parents' frame shows
  Trappers and Line's siblings' families.
- Jeroen's parents are members of their children's families too, so on their phones Home shows all five together.
- When Opa and Oma want a photo to reach all their children, they pick several families when posting (§3.2).

The words "follow", "follower", "subscription" and "feed" do not appear in the interface. Use "shows this family",
"Add a frame", "frames that show this family".

## 3. Navigation

Phone tab bar, in this order: **Home · Frames · [+] · Chats · Family**. `+` is a prominent button, not a tab: it
opens the composer (§4). Chats only when the server has chats (discovery). Tablets and desktop: the same items in the
rail/sidebar. There is no Send tab, no History tab, no Families tab and no Settings tab any more.

There is no "current family" and no switcher. Home and the composer work across all the person's families; the
Family tab lists them when there are several. The **home family** is the family the person posts to most (ties: the
one they created, else the oldest membership); it is the composer default and is listed first. With no family at
all, see §8. The server gives the counts as `FeedSummary.my_post_count` in `GET /v1/feeds` (the caller's live
posts per family); the apps apply the tie rules.

### 3.1 Home

One timeline of all the person's families together (the existing social timeline), newest first, like following
several accounts.

- Top bar: "Home" with one family, or nothing extra; the activity (notifications) button.
- With several families, a row of filter chips under the top bar: **All** · each family by name (home family first).
  The choice is remembered per device. With one family there are no chips.
- Under it, a horizontal row of people and frames of the filtered family (with "All": of the home family), ending
  with an "Invite" bubble that opens that family's invite share sheet.
- Posts: poster and time, **the family it was posted in** (small label next to the time, only when the person has
  several families), the photo(s) edge to edge using `display_url` (fallback `thumb_url`), heart and comment buttons,
  "On 3 frames · seen by Oma" (existing receipts), who loved it, caption, "View comments". A post sent to several
  families appears once.
- Empty state: "Share your first photo" with the `+` action, and "Invite family" when the family has only you.

### 3.2 Composer (`+`)

Photos (picker first, as today), caption, trim/fit/focal tools as today. One destination row:

- Default: the home family — "Trappers · everyone and its 2 frames". With a Home filter chip on one family, that
  family. Nothing to choose.
- "Change" opens a sheet with the person's families as checkboxes (several can be ticked: one post to several
  families, which the server already supports as one send to several feeds), and per family its circles (existing
  circles) as an alternative to the whole family. The row then reads e.g. "3 families · 9 frames".
  Picking individual frames is **not** offered here.
- Share button: "Share".

Sending to a single frame (a direct send, unchanged on the server) is only reachable from that frame's screen
("Send something to this frame only"), which opens the same composer preset to that frame and says so.

### 3.3 Frames

A list of frame cards: frames the person manages or is a member of first, then frames showing their families.

- Card: now-showing photo (or the last placed one), status chip (Online · showing now / Offline since …), name,
  "Shows Trappers" / "Shows Trappers and 4 other families" (or "Doesn't show a family yet" with an "Add a family"
  action for managers), today's reactions ("Oma loved 3 photos today").

  Server: `FrameSummary.now_showing` (`thumb_url`, `display_url`, `since`, `source`) and
  `FrameSummary.reactions_today` (`count`, `item_count`, the frame's local day) in `GET /v1/frames` and
  `GET /v1/frames/{id}`, for members who can see the library (API README §5.5, "Frame cards"). `source: screen` is
  the slide an online frame reports on screen ("showing now"); `last_placed` is the fallback for offline frames (the
  last placed photo). Frames that only show a family the person is in (not their own frames) aren't in
  `GET /v1/frames`; their cards come from the family's frames (`Subscription.frame`) without these fields.
- "Add a frame" at the end (pair with code, as today).
- Tapping a card opens the frame screen: the card on top, then one list —
  1. Photos on this frame (the existing library, albums inside)
  2. Send something to this frame only
  3. Calendar, weather and add-ons (existing calendar + plugins)
  4. Show on TV
  5. Frame settings and help (existing frame settings, people/admins of the frame, email and Telegram for this frame,
     remote help, leave/transfer)

  The frame's old tab row (Overview, Photos, People, Families, Calendar, Settings) is gone; the existing screens are
  reused behind these rows. Frame settings has **Families on this frame**: the families it shows, each removable by a
  manager, and "Add a family" (any family the manager is a member of; active at once, §7; families they are not in
  are added through that family's invite link for frames, as today).

### 3.4 Family

With one family, the tab opens straight into its profile. With several, it opens a list of the person's families
(cover, name, "12 people · 3 frames", home family first, "Start a family" at the bottom); each opens its profile. The
gear on the list opens the App settings.

The family as a profile.

- Header: cover (round), counts (posts · people · frames), name, member names line.
- Buttons: "Invite family" (primary) and "Add a frame" (pick one of your frames or pair a new one; it is shown at
  once, see §7).
- Segments: **Photos** (grid of the family's posts, newest first; a "Only mine" filter replaces the old History tab),
  **People** (members; admins can change roles, remove, approve join requests), **Frames** (frames showing the family,
  with status; admins approve or remove).
- Gear (top right) opens **Settings**, two sections:
  - *This family*: name and cover, circles, birthdays, more ways to add photos (photo sources, email, Telegram for the
    family), who can invite and approvals, leave or delete the family.
  - *App*: accounts and servers, notifications, privacy (crash reports), simple view (§6), sent history (the old
    History screen, kept for detail), about.

## 4. Wording (en; nl follows the same meaning)

| Old | New |
|---|---|
| Families (tab) | Family |
| New family | Start a family |
| Following frames / follows | Frames · shows this family |
| Show on a frame / Ask to follow | Add a frame |
| Earlier posts (dialog) | not asked; the last 50 come along (changeable in Frame settings) |
| Send (tab) | + / Share |
| History (tab) | Family › Photos › Only mine; Settings › Sent history |
| Settings (tab) | Family › gear |

## 5. Approvals and defaults

- Adding a frame you manage to a family you are a member of: active at once (§7), history option "the last 50".
  No dialog. Change later in Frame settings.
- Admin approval remains for a frame managed by someone who is not in the family (subscribe invite links, as today).

## 6. Simple view

A per-device setting (Settings › App › Simple view, and asked once after joining a family: "Normal view" / "Simple
view: big photos and a heart, for Oma").

- One photo at a time, large; poster name and time in large type; caption; a big "Send a heart" button; a "next"
  button and swipe up/down for the next/previous post.
- No tab bar. A small "Settings" text button in the top corner opens a sheet with "Normal view" and the account.
- Shows all the person's families together, like Home with "All", with the family name under the poster. Hearts are
  the existing reaction (love). Comments are not shown in simple view.
- Type at least 20 sp/pt for body, buttons at least 64 dp/pt high.

## 7. Server change: managing the frame is enough

Today a subscription request for a frame you manage is active at once only when you are a feed owner/admin
(`feeds.md` §4.1). New rule: it is also active at once when you are any member (poster) of the feed **and** you
manage the frame (frame owner/admin or *change settings*). Everyone else still needs approval. The default history
option the clients send is `last_n` 50. Update `feeds.md` §4.1, the permission table, and the tests.

## 8. Getting started and existing users

First start (signed in, no family, no frame): two choices —

- **Start a family**: name (prefilled "<surname> family" when known), then "Add Oma's frame now?" (pair with code)
  or "Later", then the invite share sheet.
- **Join my family**: paste a link or code (invite links open the app directly as today).

After joining someone else's family: the normal/simple view question (§6).

There are no existing users or older servers to stay compatible with. The apps assume a server with families
(discovery `feeds`) and social; no legacy frame-only navigation, no "start a family for your frame" migration card,
and no fallbacks for old flows. Someone without a family sees only the getting-started choices. Old app routes and
screens that the new navigation no longer reaches are deleted, not kept or redirected.

## 9. Out of scope

No changes to the frame app, the data model, sending/placement, chats, or the extras themselves (calendar, add-ons,
sources, email, Telegram, TV, remote help, multi-server) — only where they are reached from.
