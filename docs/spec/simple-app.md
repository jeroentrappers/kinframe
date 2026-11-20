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

- **The family** is where photos are shared. Anyone can start one, with or without a frame. The app opens straight
  into it; someone in one family never sees a list of families.
- **Everyone is family.** One kind of member in the interface (the server keeps owner/admin/poster for permissions).
  Everyone sees everything and can post. Oma is a member like the rest; on her phone she can pick the simple view
  (§6).
- **Frames show the family.** A frame "shows" a family (server: an active subscription). Added now or later.

The words "follow", "follower", "subscription" and "feed" do not appear in the interface. Use "shows this family",
"Add a frame", "frames that show this family".

## 3. Navigation

Phone tab bar, in this order: **Home · Frames · [+] · Chats · Family**. `+` is a prominent button, not a tab: it
opens the composer (§4). Chats only when the server has chats (discovery). Tablets and desktop: the same items in the
rail/sidebar. There is no Send tab, no History tab, no Families tab and no Settings tab any more.

The **current family** drives Home, Family and the composer default. With several families, tapping the family name
on Home or Family opens a switcher sheet; the choice is remembered per device. With none, see §8.

### 3.1 Home

The current family's timeline (the existing social timeline, filtered to that family), newest first.

- Top bar: the family name (switcher affordance only with several families) and the activity (notifications) button.
- Under it, a horizontal row of the family: people (avatars) and frames (now-showing thumbnail, a green ring when
  online), ending with an "Invite" bubble that opens the family invite share sheet.
- Posts: poster and time, the photo(s) edge to edge using `display_url` (fallback `thumb_url`), heart and comment
  buttons, "On 3 frames · seen by Oma" (existing receipts), who loved it, caption, "View comments".
- Empty state: "Share your first photo" with the `+` action, and "Invite family" when the family has only you.

### 3.2 Composer (`+`)

Photos (picker first, as today), caption, trim/fit/focal tools as today. One destination row:

- Default: the current family — "Peeters family · everyone and its 3 frames". Nothing to choose.
- "Change" opens a sheet: another family (if in several), or a circle of the current family (existing circles).
  Picking individual frames is **not** offered here.
- Share button: "Share".

Sending to a single frame (a direct send, unchanged on the server) is only reachable from that frame's screen
("Send something to this frame only"), which opens the same composer preset to that frame and says so.

### 3.3 Frames

A list of frame cards: the current family's frames first, then other frames the person is a member of.

- Card: now-showing photo (or the last placed one), status chip (Online · showing now / Offline since …), name,
  "Shows Peeters family" (or "Doesn't show a family yet" with an "Add to Peeters family" action for managers),
  today's reactions ("Oma loved 3 photos today").
- "Add a frame" at the end (pair with code, as today).
- Tapping a card opens the frame screen: the card on top, then one list —
  1. Photos on this frame (the existing library, albums inside)
  2. Send something to this frame only
  3. Calendar, weather and add-ons (existing calendar + plugins)
  4. Show on TV
  5. Frame settings and help (existing frame settings, people/admins of the frame, email and Telegram for this frame,
     remote help, leave/transfer)

  The frame's old tab row (Overview, Photos, People, Families, Calendar, Settings) is gone; the existing screens are
  reused behind these rows. Which family the frame shows is a line in Frame settings ("Shows Peeters family ·
  Change").

### 3.4 Family

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
- Uses the current family. Hearts are the existing reaction (love). Comments are not shown in simple view.
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

Existing users with frames but no family keep working: `+` sends to their frames (the composer shows a frame picker
in place of the family row, as before), and Home shows a card "Start a family for Oma's frame" that creates a family,
adds the frame(s) they manage to it (active at once) and offers the invite link. Existing families and subscriptions
need no migration.

When the server has no feeds (discovery `feeds` off, self-hosted older servers), the apps keep the old frame-only
navigation.

## 9. Out of scope

No changes to the frame app, the data model, sending/placement, chats, or the extras themselves (calendar, add-ons,
sources, email, Telegram, TV, remote help, multi-server) — only where they are reached from.
