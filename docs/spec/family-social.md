# Family social: comments, chats and gentle nudges to share

Status: **approved, being built** (2026-11-19). Extends `feeds.md` (the family space), `birthdays.md` and the API guide
`docs/api/README.md` (§5.5 reactions, §6 websockets and push). The contract is in `docs/api/openapi.yaml` (tags
`social`, `circles`, `chats`, `safety`, `engagement`) and `docs/api/README.md` §5.18–§5.20; the schema is in
migrations 00022–00024; the work split is `family-social-work.md`. The decisions in §12 were taken by the project
owner on 2026-11-19 (`product-spec.md` §8, decisions 9–14). Where this spec and the API guide differ in detail, the
API guide wins.

## 1. Summary

**Goal: kinframe becomes the private digital social hub for the extended family.** Grandparents, their children and
in-laws, grandchildren, cousins, the aunt abroad: everyone who belongs, in one private place, with the photo frames in
the living rooms as members of that family rather than as end points. It replaces the scattered family WhatsApp
groups, shared albums and "did you see the photo I sent?" calls, on a server the family controls.

Today the family app is a sending tool: you pick photos, pick frames or feeds, and see reactions from the frames. The
conversation about those photos happens somewhere else (WhatsApp, Telegram, a phone call). This spec makes the
family app the place where that conversation happens, around the photos, the way Instagram works for friends, but
private to the family and with the frames as members:

1. **A home timeline** of everything shared in your families, newest first, with photos large.
2. **Comment threads** on every post, with replies, reactions and @mentions. Oma's ❤ and "Call me!" from her frame
   appear in the thread next to the comments of the people.
3. **Chats**: one-to-one, small groups, and one chat per family. Photos sent in a chat can be put on a frame with one
   tap.
4. **Gentle gamification** that reminds and encourages people to share, built on the one reward that already works:
   *Oma saw your photo and loved it*. Collective (the family keeps the frames fresh together), never a ranking,
   never guilt, always capped and easy to turn off.

What stays the same: no public profiles, no discovery, no followers, no ads, no algorithmic feed, no third-party
analytics. You only see people you share a family or a frame with.

## 2. Principles

1. **The photo is the centre.** Every thread hangs off a photo or a post. Chats are there because families need them,
   but every photo in a chat is one tap away from a frame.
2. **The frame is a member.** A frame's reactions, quick replies and "seen" are part of the conversation, credited to
   the frame ("Oma's frame"). The frame never needs a keyboard.
3. **Private by default, family-sized.** Everything is visible only to the members of the family (feed) or chat it was
   posted in. Families are tens of people, not thousands: no pagination tricks, no feed ranking, chronological only.
4. **Encourage, don't nag.** Reminders are few, specific ("Opa's frame has had no new photos for 9 days"), rotate
   between people, respect quiet hours and can be snoozed or switched off in one tap. No streak-loss guilt, no
   red-dot inflation, no comparing people.
5. **Same server, same accounts.** Built on the existing items, placements, feeds, websocket and push. No new
   service, no new dependency.

## 3. Concepts

- **Family**: the user-facing name for a **feed** in the family app and on the web (§12, decision A). A family has
  members (owner, admin, poster), frames that subscribe to it, birthdays, and now a timeline, threads and a family
  chat. The API keeps the name `feed`.
- **Extended family and circles**: an extended family is big and has branches. One family (say "Peeters", 40 people
  over three generations) can have **circles**: smaller groups inside it, such as "Anna's household", "The cousins"
  or "Oma and her children". A circle has its own chat and can be picked as the audience of a post ("Share with:
  whole family / The cousins"); its posts and threads are visible only to the circle, and frames get them only if the
  frame subscribes to the family and its manager is in the circle (or the circle is set to reach that frame). A person
  can be in several families (their own and their partner's), and Home and Chats show all of them together, with the
  family as a label. Circles are phase 2 (§11, decision F).
- **Member roles** gain one: `member`, who can read, comment, react and chat but not post photos (for the cousin who
  only wants to follow along, or for a family that wants posting limited). Existing roles keep every right they have;
  `poster` and above can also comment and chat. A family setting *new members can post* (default on) picks the role
  joiners get.
- **Post**: unchanged (what one send put in one feed). It gains a **thread**.
- **Thread**: the comments on one post. Comments have one level of replies (a reply to a reply is a reply to the same
  top comment, quoting the person), like Instagram.
- **Comment**: text (up to 2 000 characters, emoji, @mentions of family members), optionally one photo or GIF-free
  sticker set later (phase 3). Editable by its author (shows "edited"), deletable by its author and by family admins.
- **Reaction**: a person's emoji on a post, a single photo of a post, a comment or a chat message. A fixed set that
  matches the frame's: ❤️ 😂 😮 😢, plus 👍 and 🙏 for people. One reaction per person per target; the latest wins.
  Frame reactions keep living on the placement (§5.5) and are shown in the same row, credited to the frame.
- **Frame entry**: a read-only line in a thread made by a frame: its reaction, its quick reply ("Oma's frame: Call
  me!"), and once per post "Seen on 4 frames". Not editable, not deletable by members (only hidden with the post).
- **Conversation**: a chat. Kinds:
  - `direct`: two people. Exists at most once per pair.
  - `group`: 3–100 people, a name and an optional photo; created by anyone, any member can add people they share a
    family or a frame with; admins (the creator by default) can remove people and rename.
  - `family`: exactly one per feed, created with the feed (or lazily on first use for existing feeds); its members are
    the feed's members, always. Leaving the family leaves the chat.
- **Message**: text and/or up to 30 photos and videos (the same upload pipeline and master renditions as sends, so a
  chat photo is an ordinary `Item`), or a share of a post ("Look at this one" with the post card). Messages can be
  replied to (quoted), reacted to, edited by the author for 24 hours, and deleted by the author ("This message was
  deleted" stays in place).
- **Read state**: per person and conversation, the last read message. In `direct` and in groups up to 10 people the
  others see "Seen" (can be turned off per person, then they don't see others' either). Threads have a per-person
  "read up to" too, for the unread badge only.

### Who can talk to whom

- You can start a `direct` chat or add someone to a group only if you share a family or a frame with them. There is
  no search for people and no way to message a stranger.
- Blocking a person hides their messages and comments for you, stops their direct messages and stops them adding
  you to groups. It does not remove them from shared families (that is the admins' call); reporting a comment or
  message notifies the family's admins (in a family) or the server operator (elsewhere).

## 4. User stories

### Home timeline

1. Ben opens the family app. The **Home** tab shows posts from his two families ("Peeters" and "Janssens") newest
   first: the poster, the family, the photos (swipe through a multi-photo post), the caption, the reaction row
   (people and frames together: "❤ Oma's frame, Anna and 3 others") and "View all 6 comments" with the two newest.
2. Above the timeline, a strip of his **frames and families** with a freshness ring (§9.1) and, when a frame asked for
   photos, a badge "Oma would like new photos".
3. Direct sends to frames he is a member of appear in the timeline only for the sender and members with
   `see_library` on that frame, labelled with the frame ("To Oma's frame"). They have a thread too, visible to the same
   people (phase 2, §11).

### Comments

4. Ben taps a post, types "Look at her face 😂" and mentions @Anna. Anna gets a push "Ben mentioned you: Look at her
   face 😂" (mentions always notify unless she muted the post). The poster, Clara, gets "Ben commented on your
   photo", bundled with other comments within 10 minutes ("Ben and 2 others commented").
5. Anna long-presses Ben's comment and reacts 😂; she replies under it.
6. Oma taps ❤ on the frame. The thread shows "❤ Oma's frame" in the reaction row and, the first time, a frame entry
   "Oma's frame loved this" in the thread; Clara gets the push she gets today, the others see it in the thread
   without a push.
7. Anna (admin) deletes an unkind comment; the thread shows nothing (no tombstone for moderated comments) and the
   author sees "Removed by a family admin".

### Chats

8. Ben opens **Chats**: the family chats ("Peeters", "Janssens"), a group "Cousins weekend" and a direct chat with
   Anna, sorted by latest message, with unread counts.
9. In "Cousins weekend" Ben sends 12 photos. Under the photos: **Put on a frame**. He taps it, picks Oma's frame and
   the Peeters family, and it goes through the normal send flow (caption, fit, limits). The chat shows "Ben put 12
   photos on Oma's frame and in Peeters", and Oma's reactions on them later appear under that message.
10. From any post Ben can tap **Share in chat** to drop the post card into a chat ("Did you see this?").
11. Ben's niece has no account. The family chat shows an invite card (the family's join link) and email/Telegram are
    unchanged; chat is app and web only.

### Frame viewer

12. Oma's frame has *Show comments* on (frame setting, off by default, §8). When a photo with comments comes up, a
    small card fades in at the bottom for 8 seconds: "Anna: Look at her face 😂". Long comments are cut after two
    lines. Only comments from the family the photo came from, never chat messages.
13. Oma misses the grandchildren. In the frame's quick menu she taps **Ask for new photos**. The frame shows "Your
    family will get a message"; the family members get one push "Oma would like new photos 💛" (§9.3).

## 5. Permissions

| Action | Family owner/admin | Poster | Member | Frame member (`see_library`) |
|---|---|---|---|---|
| See the family's posts, threads and family chat | ✓ | ✓ | ✓ | only placements on that frame, no threads |
| Post photos to the family | ✓ | ✓ | – | via frame sends as today |
| Comment, react, reply, mention | ✓ | ✓ | ✓ | – (phase 2: threads on direct sends) |
| Edit / delete own comments and messages | ✓ | ✓ | ✓ | – |
| Delete anyone's comment in the family | ✓ | – | – | – |
| Turn threads off for one post | ✓, and the poster | own posts | – | – |
| Start direct chats / add to groups | anyone they share a family or a frame with | | | |

- The feed setting *members see reactions* (`feeds.md` §2) becomes **on by default** for new families and is
  replaced by *members see receipts* (per-frame "seen" details), off by default: reactions are social now, receipts
  stay with the poster and admins.
- A frame's own moderation (photos waiting for approval) applies before a post appears on that frame; the thread is
  visible in the app as soon as the post exists.

## 6. Data model

As built: migrations 00022_social.sql, 00023_chat.sql and 00024_engagement.sql. The sketch below was the proposal;
in the schema threads hang off feed posts and frame posts (direct sends to one frame), circles and their chats are
added, and family and circle chat membership is kept by database triggers.

```
Feed ─< Post ─< Comment (parent_id: one level) ─< CommentMention
          │        └─< Reaction (target: comment)
          ├─< Reaction (target: post | post_item)
          └─ thread_state (open | closed)
User ─< ThreadRead (post_id, read_up_to)

Conversation (kind: direct | group | family, feed_id for family, name, photo_item_id)
  ├─< ConversationMember (user_id, role: admin | member, last_read_message_id, muted_until, show_read_receipts)
  └─< Message (author_id, body, reply_to_id, shared_post_id, edited_at, deleted_at)
         ├─< MessageItem ──> Item       (photos and videos; one Item, any number of placements later)
         └─< Reaction (target: message)

Block (user_id, blocked_user_id)
Nudge (user_id, kind, subject_id, sent_at, outcome)     -- §9: what was sent, to rate-limit and to learn
FrameRequest (frame_id, created_at, answered_at)        -- "Ask for new photos"
FamilyStreak (feed_id, current_weeks, best_weeks, freezes_left, week_of)  -- §9.2
```

- One generic `reactions` table: `(target_kind, target_id, user_id, emoji, created_at)`, unique on target and user.
- Message bodies and comments are stored as plain text with mention spans (`[{user_id, start, length}]`); clients
  render links as links without previews in phase 1 (no server-side fetching of arbitrary URLs).
- Bodies are encrypted at rest with the server's at-rest key when encryption at rest is configured (decision 1 in
  `product-spec.md` §8; §12, decision B).
- `ThreadRead`/`last_read_message_id` drive unread counts; the server returns them with the timeline and the chat
  list so badges are right on every device.
- A deleted account's comments and messages become "Former member"; its photos follow the existing rules.
- Export (account data export) includes your own comments and messages and the photos you sent in chats.

## 7. API (sketch; the contract is `openapi.yaml` and README §5.18–§5.20)

| Endpoint | Purpose |
|---|---|
| `GET /v1/timeline?before=` | Home: posts from your families (+ your direct sends, phase 2) with reaction summaries, comment counts, two newest comments, unread flags |
| `GET /v1/feeds/{id}/posts/{post}/comments?after=` | A thread, oldest first, replies nested under their top comment |
| `POST …/comments {body, parent_id?, mentions}` / `PATCH` / `DELETE …/comments/{c}` | Comment, edit, delete |
| `PUT /v1/reactions/{target_kind}/{id} {emoji\|null}` | React to a post, post item, comment or message |
| `PUT …/posts/{post}/thread {state}` | Turn comments off/on |
| `PUT …/posts/{post}/read {up_to}` | Mark a thread read |
| `GET /v1/conversations` / `POST /v1/conversations {kind, member_ids, name}` | Chat list (with unread counts and last message) / start a chat (direct is idempotent per pair) |
| `GET /v1/conversations/{id}/messages?before=` / `POST …/messages {body, item_ids, reply_to_id, shared_post_id}` | Read / send |
| `PATCH`/`DELETE …/messages/{m}`, `PUT …/read {message_id}` | Edit, delete, mark read |
| `PATCH /v1/conversations/{id}` / `POST …/members` / `DELETE …/members/{u}` | Rename, add, remove, leave |
| `POST /v1/conversations/{id}/messages/{m}/send {frame_ids, feed_ids, …}` | "Put on a frame": a normal send of the message's items |
| `POST /v1/blocks {user_id}` / `POST /v1/reports {target_kind, target_id, reason}` | Block, report |
| `GET`/`PUT /v1/me/nudges` | Reminder preferences (§9.5) |
| Device: `POST /v1/device/requests` | The frame's "Ask for new photos" |
| Device: change feed `comments` (§8) | The newest comments for placements on the frame, when *Show comments* is on |

Websocket events on `/v1/ws`: `comment.created|updated|deleted`, `reaction.updated`, `message.created|updated|deleted`,
`conversation.updated`, `read.updated` (other people's read state, for "Seen"), `typing` (chats only, not stored).
Media in messages uses the existing upload endpoints (prepared uploads included); the item gets `via: chat`.

## 8. The frame

- **Reactions and quick replies** are unchanged on the frame. On the server they also become frame entries in the
  post's thread (§3). A quick reply on a chat photo that was put on a frame shows under the chat message too.
- **Show comments** (frame setting, off by default; owners and `change_settings` members): the frame receives the
  three newest comments per placement through the change feed (`comments` change, text and author first name only,
  max 280 characters each) and shows the newest one as a card for 8 seconds when the photo appears, once per photo per
  day. Never chat messages, never comments from a family the frame doesn't subscribe to. Weak-hardware budget: plain
  text, no avatars, drawn on the existing overlay layer.
- **Ask for new photos** (quick menu, on by default, can be hidden by the owner): sends a `FrameRequest`. At most one
  per frame per 24 hours; the button then shows "Asked today 💛". When new photos arrive the frame shows "New photos
  from Ben" on the next wake, closing the loop for the viewer.
- Nothing else changes on the frame: no chat on the frame in this spec (a frame with a camera/microphone and video
  calls is a separate topic).

## 9. Gamification: encouraged and reminded to share

The core loop is already there: *you share → Oma sees it → Oma ❤ → you get told*. Everything below exists to start
that loop more often and to make the reward more visible. Nothing ranks people against each other.

### 9.1 Frame freshness

- Every frame has a **freshness**: days since it last received a new photo from a person (feeds, sends, chats put on
  the frame; photo sources and Google imports count half, birthdays and calendar not at all, so automatic syncs can't
  hide that nobody personally shared).
- Shown as a ring around the frame's photo in the Home strip and on the frame screen: full and warm (shared today) →
  thinner and paler over 14 days. Words, not numbers, on the ring: "Fresh", "4 days", "2 weeks quiet".
- Tapping a frame with a pale ring opens "Send something to Oma's frame" with **suggestions**: the photos you took
  since your last share (on-device only, never uploaded until you pick them), "On this day" photos from the family's
  history (§9.4), and the current prompt (§9.3).

### 9.2 Family streak (collective)

- A family has a **weekly streak**: the number of weeks in a row in which the family together posted at least
  *goal* photos (default 3; admins pick 1–20). Weeks run Monday to Sunday in the family's time zone.
- Shown on the family header ("🔥 12 weeks") with who helped this week as a row of avatars (no counts per person).
- **Freezes**: one per month is granted automatically and used automatically on a missed week (holidays happen); a
  streak is never "lost" with a scary message, it quietly restarts: "New streak: 1 week".
- Milestones (4, 12, 26, 52 weeks) get a small celebration card in the family chat posted by kinframe, which members
  can react to. Admins can turn streaks off for the family.

### 9.3 Prompts and requests

- **Weekly prompt** (optional per family, admins can turn it on): a short theme on Monday, e.g. "Something you cooked",
  "Your view right now", "An old photo of you". The server ships a localized list (36 languages like the rest);
  admins can write their own or skip a week. Posts can be tagged with the prompt; the family timeline groups them
  ("6 answers to *Your view right now*"). The frame can show the prompt as a title card before the answers (phase 3).
- **Frame requests** ("Ask for new photos", §8): one push to every family member who has sent to that frame in the
  last 6 months (or to all members if nobody has), "Oma would like new photos 💛", with the frame's freshness. The
  first person who sends something answers it: the others get no further reminder, and the requester's frame shows who
  answered.
- **Birthdays** (`birthdays.md`): three days before a birthday in a family, members who have photos with that person
  in the family's history get "Oma's birthday is on Friday: send a photo with her?" (once per birthday).

### 9.4 Rewards that make sharing feel good

- **Seen and loved**: already pushed to the sender; now also shown on the post ("Seen on 4 frames · ❤ from Oma's
  frame") and summarised weekly: "Your photos were seen 58 times on 3 frames this week; Oma loved 4". Opt-out.
- **On this day**: in Home, once a day, "1 year ago in Peeters" with photos from that day; one tap shares it again to
  a frame or in the family chat. Old photos are the easiest thing to share.
- **Monthly family recap**: on the 1st, a card in the family chat: photos shared, the most-loved photo, the frames
  that were freshest, new members. It is about the family, not about individuals.
- **Personal milestones**, private to you (visible on your own profile only, unless you choose to show them): first
  photo, 10/100/1000 photos shared, first video, a year of sharing, 100 frame ❤ received. Small, quiet, no confetti
  storms.
- Explicitly **not**: leaderboards, "Anna shared more than you", points, levels, public badges, streaks per person,
  notifications that someone else is doing better.

### 9.5 Reminders: rules and budget

| Reminder | Who | When | Limit |
|---|---|---|---|
| Frame is getting quiet | One or two family members, rotating: people who sent to that frame before, longest since their last share first | Frame freshness reaches the family's *quiet after* (default 7 days) | per frame once per 7 days; per person once per 7 days across all reminders of this table |
| Streak at risk | One member who usually shares (rotating) | Sunday 17:00 when the week's goal isn't met | once a week per family |
| Frame asked for photos | Members as in §9.3 | Right away | per frame once per 24 h |
| Birthday coming | Members with photos of the person | 3 days before | once per birthday |
| Weekly "seen and loved" summary | Everyone with sends that week | Sunday evening | weekly, skipped when empty |

- Nobody gets more than **two reminders a week** from this table in total (frame requests excepted: they come from a
  person). Quiet hours from the phone's settings, else 21:00–09:00 local time; reminders wait until morning.
- Every reminder push has **Snooze 1 month** and **Fewer reminders** actions; settings have *Reminders: normal /
  fewer (half the budget, no streak reminders) / off*. Frame requests can be turned off separately.
- Reminders are personal and specific ("Opa's frame has had no new photos for 9 days. You last shared 3 weeks
  ago."), never generic engagement bait.
- The `Nudge` table records each reminder and whether a share followed within 24 hours. The server uses it only to
  stop reminding people who never respond (after 4 unanswered reminders in a row, that person drops to *fewer*
  automatically and is told once). No analytics leave the server.

## 10. Notifications

New push keys, same payload rules as §6.3 (IDs plus localized title and body):

| Key | To | Collapsing |
|---|---|---|
| `comment` | The poster; people who commented in the thread (if they didn't mute it) | per post, 10-minute bundle |
| `comment_reply` | The author of the top comment | per comment |
| `mention` | The mentioned member | none |
| `reaction_person` | The author of the post/comment/message, only for the first 3 per target, then a daily digest line | per target |
| `message` | Conversation members not in the chat right now, unless muted | per conversation (newest replaces older) |
| `frame_request`, `reminder_quiet`, `reminder_streak`, `reminder_birthday`, `weekly_summary` | §9 | per frame / family |

- The app badge is the number of conversations with unread messages plus threads with unread mentions or replies to
  you; not every comment.
- Mute per post, per conversation (1 h, 8 h, 1 week, always), per family (posts only, chat only, everything).
- Email stays a sending channel. A daily or weekly **email digest** of comments and messages for people who don't use
  the app is phase 3; outgoing mails keep promoting the app (`email-inbox.md`).

## 11. Phasing

1. **Phase 1, threads and reminders** (the photos are already there, so this has the most effect for the least work):
   home timeline, comments and replies, person reactions, mentions, frame entries in threads, `member` role,
   pushes and badges; frame freshness, frame requests ("Ask for new photos") and the *frame is getting quiet*
   reminder; reminder settings and budget.
2. **Phase 2, chats**: direct, group and family chats, photos and videos in chats, "Put on a frame", "Share in chat",
   read receipts, typing, block and report; threads on direct sends to frames; circles inside a family (their chat
   and posts with a circle as audience).
3. **Phase 3, the rest of the game**: family streaks, weekly prompts (and the frame's prompt title card), On this day,
   weekly summary, monthly recap, personal milestones, birthday nudges, *Show comments* on the frame, email digest,
   voice messages and link previews (server-side fetching, opt-in by the operator).

Each phase ships on all clients together (Android, iOS/Mac, web), following `docs/client-parity.md`, and the frame
parts only where listed.

## 12. Decisions

Decided by the project owner on 2026-11-19, each as recommended (recorded in `product-spec.md` §8, decisions 9–14):

- **A. Feeds are called "families" in the apps and on the web.** API, database and code names stay `feed`. One
  family can still subscribe many frames; a person can be in several families.
- **B. No end-to-end encryption for chats** in phases 1–2. Message and comment bodies are encrypted at rest when the
  operator configured at-rest encryption (`KINFRAME_SECRETS_KEY`), and the apps say plainly who runs the server and
  that its operator could read messages. MLS-based E2E for text-only direct/group chats can be revisited later.
- **C. Direct chats only with people you share a family or a frame with.**
- **D. Comments on the frame: possible, off by default** (*Show comments*).
- **E. Streak goal default: 3 photos a week per family** (admins pick 1–20).
- **F. One family per extended family, with circles inside it**, rather than many small families linked together.

All three phases of §11 are being built at the same time; each still ships on all clients together.
