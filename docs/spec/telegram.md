# Telegram bridge

Status: **draft** (2026-11-19). Extends `product-spec.md` (§3 concepts, §4.3 sending), `feeds.md` (a chat can post
to a feed) and follows the concepts of `photo-sources.md` (target, owner, `ItemVia` credit, quiet notifications) and
`email-inbox.md` (outside senders, allowed and pending senders, join requests from outside channels, app promotion).
The API contract is in `docs/api/openapi.yaml` (tag `telegram`: `/v1/frames/{id}/telegram-chats`,
`/v1/feeds/{id}/telegram-chats`, `/v1/telegram-chats…`, `/v1/telegram-codes…`, `/v1/me/telegram…`, the webhook
`/v1/telegram/webhook`; `ItemVia.service: telegram`; `JoinRequest.via: telegram`) and the protocol guide
`docs/api/README.md` §5.17; §19 below summarises it. Tables are in §16.

## 1. Summary

Many families already share photos in a Telegram group. The **Telegram bridge** lets that group feed a frame: photos
posted in the group (or sent to the bot in a private chat) arrive on Oma's frame, or in a feed and from there on every
subscribed frame, without anyone installing anything new.

- **One bot per server.** The operator creates a bot with BotFather and gives the server its token. Without a token
  everything Telegram is hidden.
- **Connect in two taps.** A frame manager or feed admin taps *Connect a Telegram group* in the app, Telegram opens,
  they pick the group, and the bot joins and binds that group to the frame or feed.
- **Quiet in the chat.** The bot doesn't chat. It confirms a photo with a 👍 reaction on the message, turns it into ❤
  when the frame reacts, and replies only when something went wrong (and then only to that one message).
- **The app stays the better way.** Every message the bot does send points to the app: what you'd gain (full quality,
  videos of any size, seeing when Oma saw it, her reactions), with a one-tap way in. Each person hears it once.
- **Nothing new for frames.** Telegram photos are ordinary items: "Jan via Telegram" on frames that know the credit,
  the sender's name on older ones.

## 2. Concepts

- **The bot**: the server's one Telegram bot (`KINFRAME_TELEGRAM_BOT_TOKEN`). Its username comes from `getMe` at start
  and is published in discovery, so clients can build `https://t.me/<bot>` links. The server talks to Telegram by
  **webhook** (Telegram calls the server) or **long polling** (the server asks Telegram; for servers without a public
  HTTPS address) (§7).
- **Chat** (`TelegramChat`): a binding of one Telegram chat to one **target**. Fields:
  - `chat_kind`: `group` (a basic group or supergroup, including forum groups) or `private` (a one-to-one chat with
    the bot).
  - `target`: exactly one of a **frame** (`frame_id`; the photos become a send to that frame, like a direct send) or a
    **feed** (`feed_id`; the photos become a post, and every active subscribed frame gets them).
  - `owner`: the account that connected the chat. Contributions from people who are not linked kinframe users (§2,
    *linked account*) are **sent by the owner**, with the contributor's Telegram name in the credit. The owner's
    rights are re-checked on every batch (§4.4).
  - Telegram's `chat_id` (changes once when a basic group becomes a supergroup, §12), the title (kept current), and
    for private chats the one Telegram user it belongs to.
  - Settings: `contributors` (`everyone` or `linked_only`, §4.3), `frame_replies` (post frames' quick replies into the
    chat, default off), `notify_reactions` (the owner gets reaction notifications for contributions they "sent",
    default off: the reactions already show in Telegram), `topic_id` (forum groups: only one topic counts; default
    null = all topics).
  - Status (§5.2), counts, the last delivery.
  - One chat binds to one target; a target can have several chats (`limits.max_telegram_chats_per_target`, 5).
- **Connect code** (`TelegramCode`, purpose `connect`): a one-time, short-lived code a manager creates in the app. It
  travels in a Telegram deep link: `https://t.me/<bot>?startgroup=<code>` (pick a group, the bot is added) or
  `https://t.me/<bot>?start=<code>` (a private chat with the bot). Telegram hands the code to the bot as
  `/start <code>`; the bot binds the chat it arrived in.
- **Linked account** (`TelegramLink`): a Telegram user tied to one kinframe account, made with a **link code**
  (purpose `link`, *Link my Telegram* in account settings → `https://t.me/<bot>?start=<code>`). A linked user's
  contributions are **their own sends** (their name, their receipts, their reactions, their moderation role), as long
  as they have the right to send to the target; otherwise they count as an outside contributor. One Telegram account
  ↔ one kinframe account.
- **Contributor** (`TelegramContributor`): a Telegram user whose photo was accepted (or refused) in a chat. The server
  keeps their Telegram user id and display name per chat, to credit them, to send the app promotion only once, and so
  managers can block one person.
- **Telegram send**: what one **batch** of contributions became: one send (marked `via`), placed on the frame or posted
  to the feed. A batch collects one contributor's media in one chat until they pause (§8.4): an album of ten photos,
  or five albums sent one after the other, is one send and one post.
- **Credit** (`ItemVia`, service `telegram`): "Jan via Telegram" (frame, direct), "Jan · Peeters kids via Telegram"
  (feed). As for email, `ItemVia.label` is the **person's name** and frames show it instead of the sender's name
  ("%1$s via Telegram"): for linked users their kinframe display name, for others their Telegram display name.
- **Join link**: what the app promotion offers a group member: a one-tap way to get the app and ask to join the frame
  or feed. It is the email inbox's join link (`email-inbox.md` §10.4): an ordinary single-use invite that always
  needs a manager's approval (§10.3).

## 3. User stories

### Family app and web: frame target

1. *Connect the family group.* Anna manages Oma's frame. Frame → Settings → Telegram → **Connect a Telegram group**.
   The sheet explains in two lines ("Photos posted in the group appear on Oma's frame. Everyone in the group can
   send.") and shows **Open Telegram** (on a phone) or a QR code with the same link (on the web). Telegram asks which
   group; she picks "Peeters family". The bot joins, posts one short welcome (§10.1), and the sheet turns into
   "Connected to Peeters family ✓".
2. *It just works.* Ben posts three photos of the kids in the group. A few seconds later each message gets a 👍. About
   a minute after his last photo they are on Oma's frame as one send: "3 new · Ben via Telegram". When Oma taps ❤ on
   the frame, the 👍 under Ben's photo becomes ❤.
3. *Ben hears about the app once.* Under Ben's first photo the bot replies (silently, no notification sound): "Thanks
   Ben, your photos are on Oma's frame. With the kinframe app you'd see when Oma has seen them and send videos in full
   quality." with a **Get the app** button. Ben is never told again.
4. *Something is off.* The bot can't see photos in the group (privacy mode, §6). The sheet shows amber: "The bot only
   sees commands in Peeters family. Make it an admin of the group, or ask the server's operator to turn off privacy
   mode." Anna gets one notification about it.
5. *Only family members.* Anna's group is large (cousins, neighbours). She switches *Who can send* to "Only people who
   are on Oma's frame". Photos of people who haven't linked a kinframe account that is on the frame are ignored; each
   such person gets one quiet reply with "Ask to join Oma's frame".
6. *Block someone.* A cousin keeps posting memes. Anna opens Telegram → Peeters family → People, and blocks him: his
   photos are ignored from now on, silently.
7. *Disconnect.* Anna taps **Disconnect**: the bot says goodbye in the group and leaves. Photos already on the frame
   stay. Removing the bot from the group in Telegram has the same effect.

### Family app and web: feed target

1. *A family channel.* Anna (owner of the feed "Peeters kids") connects the "Peeters kids 📸" group to the feed. Each
   person's batch becomes one post: "Ben · 12 photos via Telegram", on all five subscribed frames.
2. *Admins.* Ben, a feed admin, sees the connected group, its status and its people, may change its settings or
   disconnect it.

### Telegram users

1. *My own Telegram.* Carla prefers Telegram to apps. In the kinframe app: Account → **Link my Telegram** → Telegram
   opens a chat with the bot, she taps **Start**: "Linked to Carla Peeters on kinframe ✓". Her photos in any connected
   group now go out as hers: her name, her receipts in the app.
2. *Send privately.* On Oma's frame Carla taps **Send from my Telegram** (a private chat). Telegram opens the bot;
   from then on anything she sends the bot goes to Oma's frame.
3. *Opa has no app.* Anna taps **Invite someone to send by Telegram**, shares the link with Opa. He taps it and
   **Start**; his photos to the bot reach Oma's frame as "Opa via Telegram", sent on Anna's behalf.
4. *Original quality.* Telegram shrinks photos. `/help` and the welcome say: "For full quality, send photos as a file
   (📎 → File), or use the kinframe app."
5. *Commands.* `/status` answers "This group sends to Oma's frame (connected by Anna). Last photo: today 10:32."
   `/help` explains in the user's language. `/stop` disconnects, for frame or feed managers with a linked account.

### Frame viewer

1. Telegram photos look like any other. The caption reads "Jan via Telegram" (or "Jan · Peeters kids via Telegram");
   the reaction reaches Jan in Telegram (and in the app if he's linked).
2. A batch is one send: one "N new" card, one screen wake, as for a send from the app.

### Operator

1. Create a bot with BotFather (`/newbot`), set `KINFRAME_TELEGRAM_BOT_TOKEN` (or `…_FILE`). Turn privacy mode off
   with BotFather `/setprivacy` → Disable (recommended), or tell families to make the bot an admin (§6).
2. With a public HTTPS `KINFRAME_PUBLIC_URL` the server registers its webhook itself; behind NAT it long-polls
   (`KINFRAME_TELEGRAM_MODE`, §7).
3. Optionally run a local Bot API server to accept files up to 2 GB instead of 20 MB (§9.3).
4. Limits are in `PUT /v1/operator/limits` like the others (§14).

## 4. Permissions

### 4.1 Frame targets

"Frame manager" = owner, admin, or a member with *change settings* on that frame (as for feed subscriptions).

| Action | frame manager | other frame member | anyone |
|---|---|---|---|
| Create a connect code (group or private chat) for the frame (becomes the chat's owner) | ✓ | — | — |
| List the frame's chats (title, kind, status, owner, counts, last delivery) | ✓ | — | — |
| Change settings, take over, block/unblock a contributor, disconnect | ✓ | — | — |
| See contributors (Telegram display names, counts, blocked) | ✓ | — | — |
| Hide, trash, purge, react to a Telegram photo | as any placement | as any placement | — |
| `/stop` in the chat | ✓ (with a linked Telegram account) | — | — |
| `/status`, `/help` in the chat | ✓ | ✓ | every chat member |
| Link own Telegram account, unlink | ✓ | ✓ | any kinframe account |

### 4.2 Feed targets

| Action | feed owner | feed admin | poster |
|---|---|---|---|
| Create a connect code for the feed (becomes the chat's owner) | ✓ | ✓ | — |
| List the feed's chats, their status and contributors | ✓ | ✓ | — |
| Change settings, take over, block, disconnect | ✓ | ✓ | — |
| See posts made from Telegram and their credit | ✓ | ✓ | ✓ (like any post) |
| Receipts and reactions of Telegram posts | ✓ | ✓ | own linked posts; others only with *members see reactions* |
| Remove a Telegram post or item | ✓ | ✓ | own linked posts |
| `/stop` in the chat | ✓ (linked) | ✓ (linked) | — |

### 4.3 Who counts as a contributor

The **chat is the trust boundary**: a manager decided that this group may send to the target.

| Sender in the chat | `contributors: everyone` (default) | `contributors: linked_only` |
|---|---|---|
| Linked Telegram user whose kinframe account may send to the target (frame: member with *send*; feed: any role) | **Their own send** (their role decides moderation) | Their own send |
| Linked user without that right | Outside contribution, sent by the owner, credited with their kinframe display name | Refused: one quiet reply with a join link (§10.3) |
| Unlinked Telegram user | Outside contribution, credited with their Telegram display name | Refused: one quiet reply with a join link |
| Anonymous group admin, a post sent on behalf of a channel (`sender_chat`) | Outside contribution, credited with `author_signature` or the chat/channel title | Refused, silently (no one to tell) |
| Blocked contributor (§3) | Ignored silently | Ignored silently |
| Other bots (`from.is_bot`) | Ignored | Ignored |

**Private chats**: a private chat exists only after someone redeemed a connect code there, so its one Telegram user is
the contributor the manager invited. They count as in a group with `contributors: everyone`; with `linked_only` they
must be linked with send rights (the app's *Send from my Telegram* links and connects in one step, §5.1). Someone who
messages the bot without a binding gets a short help reply with the app links and nothing is sent: there is **no
pending queue** for strangers (unlike email's unknown senders, a stranger in Telegram can't be reached later anyway,
and the bot's username is public).

Why no approval queue for groups: Telegram groups already have their own membership, and the person who connected the
group is in it. Frame **moderation** (*approve before showing*) still applies to every outside contribution (§8.7).

### 4.4 The owner's rights are re-checked

Every batch starts by checking that the owner may still create the chat (frame manager / feed owner or admin). If not
(left, demoted, deleted the account), the chat's owner becomes the **frame's owner** (feed: the feed's owner)
automatically, as for email addresses (`email-inbox.md` §4.3), so a family group never gets stuck; the new owner gets
the `telegram_chat.updated` event and one `telegram_attention` push ("Peeters family now sends to Oma's frame on your
behalf"). Any manager may also **take over** at any time (`PATCH {take_over: true}`): they become the owner and the
sender of future outside contributions. Sends already made keep their sender.

### 4.5 Visibility

- Unknown chats and chats the caller may not manage answer `404 not_found`.
- Telegram user ids are never returned to clients; contributors are addressed by an opaque id.
- The group title is shown to managers only; other members see only the credit on the photos.
- Only the Telegram display name reaches frames (in `ItemVia.label`), never the username or phone number.

## 5. States

### 5.1 Codes

```
create ──▶ open ──/start <code> in a chat──▶ used
             │
             └── 30 minutes, or revoked ──▶ expired
```

| Purpose | Created by | Deep link | On `/start <code>` |
|---|---|---|---|
| `connect`, `chat_kind: group` | a manager of the target | `https://t.me/<bot>?startgroup=<code>` | In a group or supergroup: binds it. In a private chat: replies "Open this link on the device where you use Telegram and pick a group." and leaves the code open. |
| `connect`, `chat_kind: private` | a manager | `https://t.me/<bot>?start=<code>` | In a private chat: binds it to the redeeming Telegram user. In a group: refused, code stays open. |
| `connect`, `chat_kind: private`, `link_account: true` (*Send from my Telegram*) | a manager | `?start=<code>` | Also links the redeeming Telegram user to the code's creator (as `link`) |
| `link` | any account | `?start=<code>` | In a private chat: links the Telegram user to the creator. |
| `join` (made by the server for the app promotion, §10.3) | — | `?start=<code>` | In a private chat with the intended Telegram user only: sends the app links and the join link. |

- A code is 16 characters from `[A-Za-z0-9]` with a 1-character purpose prefix (`c`, `p`, `l`, `j`), within
  Telegram's limit for start parameters (64 characters of `A-Za-z0-9_-`). Stored hashed (sha256), single use,
  expires after **30 minutes** (`join` codes: 30 days). Creating a new connect code for the same target and kind
  revokes the caller's previous open one.
- Clients follow the code: `telegram_code.updated` (websocket) when it is used, with the resulting `chat_id` or
  `error_code`; `GET /v1/telegram-codes/{id}` for polling.
- Redeem errors are answered in the chat (§15.2): expired, used, chat already connected (to this or another target),
  target at its chat limit, creator lost the right. A wrong code costs a rate-limit token (10 per chat per hour).
- A `link` for a Telegram account that is linked to another kinframe account is refused in the chat ("This Telegram
  account is already linked to another kinframe account. Unlink it there first.") and the code is
  `error_code: already_linked`.

### 5.2 Chat binding

```
/start <code> ──▶ active ──── pause ───▶ paused
                  │  ▲   ◀─── resume ───
                  │  │
  bot can't see   │  │  bot made admin,
  photos, bot     │  │  group re-added
  changed         ▼  │
             needs_attention

any state ── disconnect (app, /stop), bot removed, chat gone, target deleted ──▶ disconnected
                                                   (kept 30 days for history, then deleted)
```

| Status | Meaning | Media | UI |
|---|---|---|---|
| `active` | Bound and working. | Accepted | Green "Connected · last photo 10:32" |
| `needs_attention` | Something a person must fix: `error_code` (§15.1): `privacy_mode`, `bot_changed`. | `privacy_mode`: whatever the bot still sees (replies to the bot) is accepted; otherwise refused (one reply per contributor per day) | Amber, with the fix |
| `paused` | A manager paused it in the app. The bot stays in the group and says nothing. | Ignored silently (not queued) | Grey "Paused" |
| `disconnected` | Ended: `ended_reason` = `disconnected` (app), `stopped` (`/stop`), `bot_removed`, `chat_gone`, `bot_blocked` (private chat: the user blocked the bot), `target_deleted`. | — | Listed under "Earlier" for 30 days |

- Chats in a forum group bound with `topic_id` accept media only from that topic.
- A chat can be connected again after `disconnected`: a fresh connect code makes a new binding.
- At most one live binding per Telegram chat on the server, whatever the target.

### 5.3 Linked account

`linked` (with `can_message`: the user has a private chat with the bot that is not blocked) or nothing. Unlinking (in
the app, or `/unlink` in the private chat) deletes the row; later contributions count as an outside contributor.
Deleting the kinframe account unlinks. A Telegram user who blocks the bot stays linked, `can_message: false`.

### 5.4 Contribution (per Telegram message with media)

| `state` | Meaning |
|---|---|
| `collecting` | Downloaded and processing, in an open batch. |
| `sent` | Part of a send (`item_id`, `send_id`). |
| `duplicate` | The target already had the same file (§8.6); nothing new was made. |
| `skipped` | Not sent: `skip_reason` = `too_large_for_bot`, `too_large`, `too_long`, `unsupported`, `spoiler`, `not_allowed` (linked only, blocked, paused), `rate_limited`, `topic` (other topic). |
| `failed` | Download or processing failed 3 times (`failure_code`). |

Rows are kept 90 days (reactions are mirrored back while the row exists), then deleted; the items stay.

## 6. Privacy mode: can the bot see the photos?

Telegram bots in groups run in **privacy mode** by default: they receive only commands, replies to their own
messages, messages that mention them and service messages. A photo posted normally is invisible to them. There are two
ways out:

1. **Privacy mode off** for the bot (operator, once): BotFather → `/setprivacy` → pick the bot → *Disable*. Telegram
   applies it only to groups the bot joins **afterwards**: a group that added the bot before must remove and add it
   again. Recommended: families need do nothing.
2. **The bot is an admin** of the group (family, per group): admins always receive every message. No admin rights are
   needed beyond the role itself; the connect sheet suggests unticking all rights.

Detection, deterministic:

- At start the server calls `getMe`; `can_read_all_group_messages` tells whether privacy mode is off. Discovery
  publishes `telegram_privacy_mode` so the connect sheet can say up front "After connecting, make the bot an admin of
  the group."
- On binding, and on every `my_chat_member` update for the bot (it was promoted or demoted), the server records the
  bot's status in that chat (`member` / `administrator`). The chat **sees photos** when privacy mode is off **and the
  bot joined after it was turned off** (`joined_at` later than the server's recorded `privacy_off_since`), or when the
  bot is an admin.
- If not, the chat is `needs_attention (privacy_mode)`: the welcome message says "Make me an admin of this group so I
  can see the photos (Group info → Administrators → Add)" and the owner gets one push. Promoting the bot fixes it at
  once (`my_chat_member`); the bot reacts 👍 to nothing retroactively (it never saw those photos) but says "Ready ✓"
  once.
- Media in replies to the bot still reach it in privacy mode and are accepted, so a family can work around it in a
  pinch.

## 7. Delivery: webhook or long polling

`KINFRAME_TELEGRAM_MODE` = `auto` (default) | `webhook` | `polling` | `off`.

- **Webhook** (`auto` when `KINFRAME_PUBLIC_URL` is `https://` on port 443, 80, 88 or 8443, the ports Telegram
  calls, and its host is not private): at start the server calls `getWebhookInfo` and, if the URL, secret or
  `allowed_updates` differ, `setWebhook` with
  - `url` = `<KINFRAME_PUBLIC_URL>/v1/telegram/webhook` (or `KINFRAME_TELEGRAM_WEBHOOK_URL`),
  - `secret_token` = a value derived from the server's secrets key (HKDF-SHA256, info `kinframe telegram webhook v1`,
    43 base64url characters), so every process of the server computes the same one and a restart doesn't re-register,
  - `allowed_updates` = `["message", "edited_message", "my_chat_member"]`,
  - `max_connections` 10, `drop_pending_updates` false.
  Telegram sends each update as a POST with the header `X-Telegram-Bot-Api-Secret-Token`; requests without the exact
  value get `401` (constant-time compare). The handler stores the update and answers `200` at once (§8.1);
  processing happens afterwards, so a slow download never makes Telegram retry. Telegram retries non-2xx answers and
  keeps undelivered updates for 24 hours.
- **Long polling** (`auto` otherwise: `localhost`, private addresses, plain http): the server first calls
  `deleteWebhook` (a bot can't do both), then loops `getUpdates` with `timeout=50`, `allowed_updates` as above and
  `offset` = last stored `update_id + 1`. The offset is stored in the database, so a restart neither loses nor repeats
  updates. Network errors back off from 1 s to 60 s.
- **One consumer.** Only one process registers the webhook or polls: a Postgres advisory lock elects it; others
  serve webhook requests (any process may receive them) but don't poll.
- `off`, or no token: no bot, discovery `telegram: false`, endpoints `503 feature_disabled`, the webhook `404`.
- **Bot changed.** The server stores the bot's id. If `getMe` returns a different id (the operator replaced the bot,
  not just rotated its token), all live chats go `needs_attention (bot_changed)`: they belong to the old bot.
  Rotating the token of the same bot (BotFather `/revoke`) is transparent: the webhook is re-set at start.
- Commands are registered at start with `setMyCommands` per language (§11), scoped to private chats and to group
  chats.

## 8. Processing an update

### 8.1 Intake (webhook handler or poll loop)

1. Verify the secret header (webhook). Body at most 1 MiB.
2. **Filter before storing** (minimal data, §13): keep an update only if it is
   - a message (or edited message) with `photo`, `video`, `animation`, or a `document` whose MIME type is `image/*`
     or `video/*`;
   - a bot command for this bot (`/start`, `/help`, `/status`, `/stop`, `/unlink`; in groups `/cmd` or
     `/cmd@<bot>`);
   - a service message the bot needs: `migrate_to_chat_id`/`migrate_from_chat_id`, `new_chat_title`,
     `forum_topic_created`/`forum_topic_edited` (topic names for the topic setting), `left_chat_member` for the bot,
     `group_chat_created`/`supergroup_chat_created` (bot added with a fresh group);
   - a `my_chat_member` update (the bot added, removed, promoted, demoted, blocked in a private chat).
   Everything else (text, stickers, voice, polls, reactions, members joining) is dropped **unstored** and the update
   id is simply acknowledged. Kept updates are reduced to the fields the server uses (ids, sender id and display name,
   language code, chat id/type/title, thread id, media file ids and sizes, caption, media group id, spoiler flag,
   forward origin type, date): message text is never stored.
3. **Dedupe**: insert into `telegram_updates` keyed by `update_id`; an existing id is a no-op (Telegram re-delivery,
   two processes, a replayed poll).
4. Answer `200` (webhook) or advance the offset (polling).

### 8.2 Worker

Updates are processed **in order per chat** (one worker at a time per chat id, many chats in parallel, at most
`KINFRAME_TELEGRAM_WORKERS`, default 4). Per update:

1. **Chat lookup** by `chat_id` among live bindings (following a recorded migration). No binding:
   - `/start <code>` → redeem (§5.1).
   - `/start` without code, `/help` in a private chat → help with the app links (§10.4).
   - Media in a private chat → "This chat isn't connected to a frame. In the kinframe app, open a frame and tap *Send
     from my Telegram*." with the app links, at most once per day per user.
   - Media in an unbound group (the bot was added by hand, or the binding ended) → nothing; on being added without a
     code (`my_chat_member` → member) the bot posts once "To connect this group to a frame, use *Connect a Telegram
     group* in the kinframe app." and **leaves** after 10 minutes if no code arrives.
2. **Service and membership**: `my_chat_member` (left/kicked → `disconnected (bot_removed)`; private `kicked` →
   `bot_blocked`; promoted/demoted → re-evaluate privacy, §6), migration (§12), title changes, topic names.
3. **Commands** (§11).
4. **Media**: status check (`paused` → ignore; `needs_attention` → §5.2), topic filter, **sender decision** (§4.3),
   blocked contributors, rate limits (§14), spoiler (§8.5). A refusal sets the contribution `skipped` with its reason
   and, where the table in §15.2 says so, a reply.
5. **Download** (§9): pick the file, check its size before downloading, `getFile`, stream to scratch with a cap, sniff
   by magic bytes, sha256, dedupe (§8.6), then create the media and its processing job as for an upload (the existing
   worker path: HEIC, video, prepared rules unchanged). The download is deleted once the master exists.
6. **Collect**: add the item to the open batch for (chat, contributor) (§8.4) and set 👍 on the message (§10.2). The
   confirmation comes when the file is safely in, not when the frame shows it.
7. When the batch closes: **send or post** (§8.4), record the send on each contribution, emit `telegram_chat.updated`.

### 8.3 Edits

`edited_message`:

- **Caption changed**: becomes the caption of the item(s) it set (§8.5), unless a kinframe user edited the caption
  since (`caption_edited`), exactly like photo-source upstream edits. Frames don't show "edited". Applied whether or
  not the frame has shown the photo yet: a frame shows the current caption the next time.
- **Media replaced** (Telegram lets the author swap the photo of a message): ignored in this version (the original
  stays). Open question 2.
- Edits of messages the server never accepted (older than the binding, refused) are ignored.

### 8.4 Batching: one send per burst

- A **batch** belongs to one chat and one contributor (one sender decision: linked or outside). It opens with the
  first accepted media and closes when that contributor has sent nothing more for **60 seconds**, or **10 minutes**
  after it opened, or at `limits.max_items_per_send` (200) items; then a new one opens.
- Telegram albums (`media_group_id`, at most 10 items, delivered as separate messages a moment apart) always fall in
  one batch: a batch never closes while a media group it holds received a message less than 5 seconds ago.
- Someone posting 40 photos as four albums in a minute makes one send and one feed post, not four. Two people posting
  at the same time make two sends, each with its own credit.
- On close: one **send** by the sender (the linked user, or the owner for outside contributions), `Send.via` =
  telegram, items in message order:
  - frame target: placements on the frame (moderation §8.7);
  - feed target: one post (`FeedPost.via`), fanned out to the subscribed frames as for any post.
- Email has no window (one mail = one send, `email-inbox.md` §8.6): a mail is already a unit, a chat is a stream.
  The values here are tuned for chat (people post in bursts seconds apart); kept after review.

### 8.5 What becomes of a message

| Telegram | kinframe |
|---|---|
| `photo` (array of `PhotoSize`) | The **largest** size. Telegram re-encodes photos as JPEG, at most 1280 px on the long edge (2560 px when the sender chose HD) and without EXIF: the capture time is the message date. Good enough for most frames; the welcome and `/help` recommend *send as file* for originals. |
| `document` with `image/*` (JPEG, PNG, WebP, HEIC/HEIF, AVIF, GIF) | The original file, with its EXIF (capture time; GPS stripped as for every upload). Other types (RAW, PDF, ZIP) are ignored silently: it's chat traffic, not a failed photo. |
| `video`, `document` with `video/*` | A video item within kinframe's rules: longer than `limits.max_video_seconds` → skipped `too_long`, never trimmed silently; audio kept (`include_audio` default). Telegram may have re-encoded it too. |
| `animation` (a GIF, which Telegram stores as silent MP4) | A muted video item. |
| `video_note` (round video), `voice`, `audio`, `sticker` | Ignored (not memories for a frame). Open question 3. |
| `caption` | The caption, first 280 characters (kinframe's limit; longer ones are cut at a word with "…"). Entities (bold, links) dropped; custom emoji become their fallback emoji. In an album, the one caption is applied to **every item of that album** (people caption the album, not a photo); if several messages of an album carry their own captions, each keeps its own. |
| `has_media_spoiler` | Skipped `spoiler`: the sender hid it on purpose, a frame can't. Reaction 🙈 instead of 👍; the first time per chat also a short reply explaining it. |
| `forward_origin` (forwarded) | Accepted; credited to the **forwarder** (they chose to share it). The original author is not named (they may be hidden, a channel, or outside the family). Capture time: the original message's date. |
| `sender_chat` (anonymous admin, on behalf of a channel) | §4.3 |
| `media_group_id` | §8.4 |
| Live photos | Telegram has none; iOS sends the still. |

### 8.6 Dedupe

In this order:

1. **(chat, message id)**: each message is processed once (with the update dedupe, §8.1).
2. **`file_unique_id` within the target**: Telegram's stable id of the file, the same for a photo forwarded around or
   posted twice. A match with a live contribution in a chat of the same target marks it `duplicate`, before
   downloading.
3. **Content hash within the target**, as photo sources (§8.1 there): sha256 against live placements on the frame /
   live items of the feed's posts, `media.source_sha256` and `original_sha256`. A duplicate gets 👍 anyway (from the
   contributor's view it worked) and nothing new is made.
4. **Media reuse** of the sender's same file.

### 8.7 Moderation

A frame's moderation applies as to any send, with one difference: **outside contributions are always moderated when
the frame has moderation on**, even when the owner (their technical sender) is the frame's owner or admin, because
the owner didn't choose these photos. Linked contributors follow their own role, as in the app. Rejecting one marks
the contribution `skipped (not_allowed)`; its message keeps the 👍 (the bot does not announce rejections in a group).

## 9. Media download

### 9.1 The file

- Size is known before downloading (`PhotoSize.file_size`, `Video.file_size`, `Document.file_size`). Over the bot's
  download limit (§9.3) → `too_large_for_bot`; over `limits.max_upload_bytes` → `too_large`; both with a reply.
- `getFile` returns a `file_path`; the server downloads `https://api.telegram.org/file/bot<token>/<file_path>`
  (valid at least one hour) with connect 10 s and a 10-minute cap, through the normal HTTP client (Telegram is a
  fixed, operator-configured host; the user-URL guard of photo-sources §12.3 doesn't apply). The URL contains the
  token: it is never logged (§13).
- Failures (5xx, timeouts) retry with back-off up to 3 times within 30 minutes; the batch waits for its pending
  downloads (at most 10 minutes) and then closes without them; a late success opens a new batch.

### 9.2 Quality

| Sent as | What arrives | Frame result |
|---|---|---|
| Photo (default) | JPEG ≤ 1280 px (HD: ≤ 2560 px), no EXIF | Fine on 1280×800 frames; soft on larger screens; capture time = message time |
| File (📎 → File) | The original | Full quality, original capture time |
| Video | Possibly re-encoded by Telegram, often > 20 MB | Fails on the cloud Bot API beyond 20 MB (§9.3) |
| The kinframe app | The original, prepared for frames | Best: plus receipts, reactions, any size |

### 9.3 The 20 MB limit and a local Bot API server

The cloud Bot API lets bots download files of at most **20 MB**: most phone videos longer than half a minute and
many original photos sent as files are bigger. The bot replies "This video is too big for the Telegram bot (over
20 MB). Send it with the kinframe app instead." with the app button: the limit becomes a reason to use the app.

An operator can lift it by running Telegram's open-source **local Bot API server** (`telegram-bot-api`, with the
operator's own `api_id`/`api_hash` from my.telegram.org), which allows downloads up to 2 000 MB:

- `KINFRAME_TELEGRAM_API_URL` = its base URL (default `https://api.telegram.org`). The server calls `logOut` on the
  cloud API once before switching (Telegram requires it) and documents the step.
- In its `--local` mode `getFile` returns an absolute path on the Bot API server's disk instead of a download path:
  `KINFRAME_TELEGRAM_LOCAL_FILES_DIR` names where that directory is mounted in the kinframe container (shared volume),
  and the server reads and then deletes the file there.
- `KINFRAME_TELEGRAM_MAX_DOWNLOAD_MB` (default 20, or 2000 with a local server) caps it further; `max_upload_bytes`
  always applies. Discovery publishes `telegram_max_file_bytes` so the help can name the real limit.

`docs/deploy.md` should get a compose snippet for this when it is built (not part of this spec's changes).

## 10. Messages the bot sends

The bot's voice is short, warm and rare. Every message is **silent** (`disable_notification: true`) except replies to
commands, and every one that a person reads for the first time **points to the app**.

### 10.1 Welcome (once per binding)

In the group (or private chat), right after connecting:

> 👋 Photos and videos posted here now appear on **Oma's frame** (connected by Anna).
> For the best quality send them as a file, or use the kinframe app: full quality, videos of any length, and you'll
> see when Oma has seen them.
> [Get the app]  [Help]

With `privacy_mode` the second line is replaced by the admin instruction (§6). In a forum group it is posted in the
topic where `/start` arrived (General when the deep link was used).

### 10.2 Reactions, not replies

- Accepted media: 👍 on the message (`setMessageReaction`). For an album: on the **first message of the album**, once
  (one API call per album, not per photo).
- A frame reacts (`love`, `laugh`, `wow`, `sad`): the reaction on that item's message becomes ❤, 😁, 🤩 or 😢. Bots can
  set one reaction per message, so it replaces the 👍. With several frames (a feed), the most recent reaction wins;
  when a frame removes its reaction the next most recent stays, else 👍 returns. Album items: on the album's first
  message, latest reaction of any of its items.
- Skipped as `spoiler`: 🙈. Other skips: no reaction, a reply (§15.2).
- Telegram's set of emoji bots may use is fixed (✅ is not in it, hence 👍), and a group may restrict reactions: if
  `setMessageReaction` is refused (`REACTION_INVALID`), the server remembers that for the chat and stops trying; the
  confirmation is then silent (the chat setting shows "Reactions are turned off in this group").
- Quick replies from frames (`beautiful`, `call_me`, `miss_you`, `thanks`): only with `frame_replies: true` (default
  off), as a silent reply to the message: "Oma's frame: Beautiful! 😍" (localized, §11). At most one per message and
  frame.
- Reactions reach the app as before: linked senders get them in the app and as push like any reaction; for outside
  contributions the owner gets them only with `notify_reactions` (default off, since the group sees them).

### 10.3 App promotion and the join link

- **When**: once per Telegram user per chat, attached to the reply-worthy moment: their first accepted contribution
  (or their first refusal in `linked_only` mode). Never to linked users, never twice. Tracked in
  `telegram_contributors.promo_sent_at`.
- **What**: a silent reply to that message:
  > Thanks Ben! Your photos are on Oma's frame. In the kinframe app you'd see when Oma has seen them, get her reactions
  > and send videos in full quality.
  > [Get the app]

  In `linked_only` mode: "Only people on Oma's frame can send here. [Ask to join]".
- **The button** is a deep link to a private chat with the bot, `https://t.me/<bot>?start=j<code>` (a `join` code bound
  to that Telegram user, the chat and the target, valid 30 days). In a group everyone sees the button, so the bot
  checks that the person who taps **Start** is the user it was meant for; anyone else gets the general help. In the
  private chat the bot sends:
  > Get kinframe: [App Store] [Google Play] [Web]
  > Then tap here to ask to join Oma's frame: [Ask to join]

  **Ask to join** is the email inbox's join link (`email-inbox.md` §10.4): an ordinary invite link
  (`https://<server>/i/<token>` for frames, `/fi/<token>` for feeds, which open the app when installed and the web with
  store links otherwise), made by the server with the existing invite machinery on behalf of the chat's owner: single
  use, valid 30 days, note "Ben (Telegram)", **always needs a manager's approval**, not listed in `listInvites`, one
  live invite per (chat, contributor), revoked with the chat or when the contributor is blocked (the invite's generic
  `via: telegram`, `via_ref` = the contributor). The join request carries the generic channel fields: `via:
  telegram`, `via_name` = "Ben · Telegram, Peeters family" and `via_matches: null` (Telegram can't tell whether the
  kinframe account that opens the link belongs to Ben), so the manager sees "Ben · asked to join from Telegram
  (Peeters family)". Because the bot handed the link to the verified Telegram user in a private chat, accepting it
  also **links** their Telegram account (§2): once approved, their photos are their own sends.
- Like email's `offer_app_invite`, the chat setting `offer_app_invite` (default on) lets managers drop the join button
  (the app links stay). Users already linked, or contributors who are members already, get no join button.
- Store links: `app_versions.<platform>.store_url` (operator, README §5.15), else the public kinframe listings; web:
  discovery `web`.
- A promotion is never sent more often than once per chat per 10 minutes (a burst of new people in a big group gets
  their replies spread out, or not at all once the limit is hit; they'll get it next time they post).

### 10.4 Help, errors and goodbye

- `/help` (and `/start` without a code in a private chat): what the bot does here, *send as file* for quality, the size
  limit, and the app links.
- Error replies (§15.2): a short reply to the message concerned, at most one per message, and per chat at most one per
  error code per 10 minutes (further ones are just skipped, counted in the app).
- Goodbye on disconnect (app or `/stop`): "This group no longer sends to Oma's frame. Photos already there stay." then
  `leaveChat` (groups). In a private chat the bot stays (it can't leave) and ignores media with one reply a day.

## 11. Commands and languages

| Command | Where | Who | Does |
|---|---|---|---|
| `/start <code>` | per §5.1 | anyone with a code | Connect, link, join |
| `/start` | private | anyone | Help and app links (linked users: their status) |
| `/help` | both | anyone | §10.4 |
| `/status` | both | anyone in the chat | "This group sends to Oma's frame (connected by Anna). Last photo: today 10:32." plus, for a linked manager, problems ("The bot can't see photos: make it an admin"). Nothing more: no member names, no counts. |
| `/stop` | both | linked managers of the target (§4) | Disconnect (§10.4). Others: "Only Oma's frame's managers can disconnect, in the app or here once they've linked Telegram." |
| `/unlink` | private | a linked user | Unlinks their account |

- In groups the bot answers commands addressed to it (`/status@<bot>`, or bare `/status` when it is the only bot);
  command replies are normal (not silent) replies to the command.
- **Language**: messages to one person (DM, replies to their message) use their kinframe locale when linked, else
  their Telegram `language_code` mapped to the server's 36 locales (`docs/i18n.md`: `pt-br` → `pt`, `nn`/`no` → `nb`,
  `iw` → `he`, `in` → `id`), else the owner's locale. Messages to the whole group (welcome, goodbye, frame replies) use
  the **owner's** locale. `setMyCommands` registers command descriptions for every locale (`language_code` scope).
- Texts live in the server's existing message catalog (`internal/i18n`), keys `telegram_*`.

## 12. Edge cases

| Case | Behaviour |
|---|---|
| Bot removed from the group, or the group deleted | `my_chat_member` left/kicked → `disconnected (bot_removed)`; the managers see it in the app, the owner gets one push. API calls answering "chat not found" / "bot was kicked" do the same (`chat_gone`). Photos already sent stay. |
| Basic group upgraded to a supergroup (`migrate_to_chat_id`) | The binding's `chat_id` moves to the new id (from either service message, or from a `400` answer carrying `parameters.migrate_to_chat_id`); old id kept as `previous_chat_id` for late updates. Message ids start over in the supergroup: reactions for photos sent before the upgrade are no longer mirrored (their rows are closed). |
| Bot demoted from admin, privacy mode on | `needs_attention (privacy_mode)` (§6) |
| Frame deleted / feed deleted | Bindings `disconnected (target_deleted)`; the bot says goodbye and leaves. |
| Owner leaves the frame/feed, loses rights, deletes account | The frame's (feed's) owner takes over automatically (§4.4) |
| A member leaves the Telegram group | Nothing: their contributor row stays for credit and the promotion flag; their photos stay. If they had linked, the link stays (it is per account, not per chat). |
| Linked user loses send rights on the frame | Their next photos count as an outside contribution (or refused in `linked_only`). |
| A chat already bound to another target | Redeeming another connect code there is refused: "This group already sends to Oma's frame. Disconnect it first." One chat → one target. |
| Same group, two kinframe servers | Each server has its own bot; both bots may be in one group; each handles its own binding. |
| Forwarded messages | Accepted, credited to the forwarder (§8.5). |
| Channel posts | Channels can't be connected (out of scope); `channel_post` updates aren't requested. A post a linked channel automatically forwards into a discussion group arrives with `is_automatic_forward` and `sender_chat` = the channel: an outside contribution credited with the channel title (`everyone`), refused in `linked_only`. |
| Forum groups (topics) | All topics count by default; `topic_id` restricts to one, chosen in the app from topic names the bot has seen (Telegram has no API to list topics; the General topic is always offered). Replies go into the message's topic (`message_thread_id`). |
| Spoilers | Skipped, 🙈 (§8.5). |
| Very large albums | Telegram caps an album at 10; bigger selections arrive as several albums, which one batch merges (§8.4) up to 200 items per send. A flood beyond `max_telegram_items_per_chat_hour` is skipped `rate_limited` with one reply. |
| Media sent while the server was down | Telegram keeps updates 24 hours; the webhook catches up, polling resumes from the stored offset. Older ones are lost (Telegram's limit): accepted. |
| Deleting a message in Telegram | **Does not unsend.** The Bot API has no deletion event for ordinary chats (only for business accounts). The welcome and `/help` say "Deleting a photo here doesn't remove it from the frame; remove it in the kinframe app." Senders who are linked unsend in the app; outside contributions are removed by the owner or the frame's managers. A `/remove` reply command was considered and left out (§18). |
| Caption edited after the frame showed it | Updated (§8.3). |
| The group restricts reactions | Silent confirmation (§10.2). |
| The bot is muted / can't send messages (group permissions) | Replies fail with `403`; the server stops trying for the chat (`can_send: false` in the app's status), media still flows. |
| Secret chats | Bots can't take part in Telegram secret chats; only cloud chats work. |
| A user blocks the bot (private chat) | `disconnected (bot_blocked)` for a private binding; the link stays with `can_message: false`. |
| Huge private chat traffic from one person | Same rate limits (§14). |
| Two contributions of the same file by two people | The second is `duplicate` (§8.6). |
| Operator removes the token | Bindings stay in the database, inactive; discovery hides Telegram; re-adding the same bot resumes them. |

## 13. Security and privacy

### 13.1 What the server stores

| Data | Stored | Returned |
|---|---|---|
| Bot token | Environment / `_FILE` only, never in the database | never |
| Webhook secret | Derived from `KINFRAME_SECRETS_KEY` at start (§7), not stored | never |
| Chat: Telegram chat id, type, title, topic names | Plain, while bound + 30 days | title to managers |
| Contributors: Telegram user id, display name (first + last name), counts, blocked, promo sent | Plain, per chat, deleted with the binding | display name to managers |
| Linked account: Telegram user id, display name, `@username`, language code | Plain, until unlinked | name and `@username` to the account itself |
| Captions | As item captions (only the ones used) | as captions |
| Message text, stickers, other updates | **Never** (filtered before storing, §8.1) | — |
| Updates kept for processing | Reduced JSON, deleted once processed; the `update_id` row 48 hours (dedupe) | — |
| Contribution rows (message id ↔ item) | 90 days | counts only |
| Downloaded files | Until the master exists, as every upload | — |
| Codes | sha256 only | the code once, to its creator |

- Logs carry chat ids and update ids, never the token, file URLs (they contain the token), captions or names.
  The HTTP client to Telegram redacts `/bot<token>/` from errors.
- GPS is stripped as for every upload; place names follow the **sender's** *share location names* setting (the
  owner's for outside contributions).

### 13.2 Abuse and limits

- The webhook accepts only the secret; optionally the server can also restrict it to Telegram's published address
  ranges (`KINFRAME_TELEGRAM_WEBHOOK_IP_CHECK=on`, off by default because reverse proxies hide the source address).
- Rate limits (§14) per chat, per Telegram user and globally, for both intake and the bot's own outbound calls
  (Telegram itself allows a bot about one message per second per chat, 20 per minute per group and 30 per second
  overall; the server stays well below and honours `429 retry_after`).
- A stranger who finds the bot can do nothing without a code; codes are 16 random characters, single use, 30 minutes,
  wrong guesses rate-limited.
- Linking proves control of the Telegram account (the code arrives from that account's private chat) and of the
  kinframe account (only its owner sees the code). Forwarding a link code to someone else would let them send as you:
  codes expire in 30 minutes, the app shows the linked Telegram name, and the bot confirms "Linked to Carla Peeters".

### 13.3 What people are told

The connect sheet: "Everyone in Peeters family can send photos to Oma's frame. The bot sees every message in the group
but keeps only photos, videos and their captions. Telegram is not end-to-end encrypted: photos pass through
Telegram's servers." The welcome repeats the first sentence in the group, so the group knows.

## 14. Limits and configuration

Operator limits (`Limits`, `PUT /v1/operator/limits`):

| Limit | Default | Env | Meaning |
|---|---|---|---|
| `max_telegram_chats_per_target` | 5 | `KINFRAME_MAX_TELEGRAM_CHATS_PER_TARGET` | Live chats per frame or feed (`409 limit_reached`; in the chat: §15.2) |
| `max_telegram_chats_per_user` | 20 | `KINFRAME_MAX_TELEGRAM_CHATS_PER_USER` | Chats one account owns |
| `max_telegram_items_per_chat_hour` | 300 | `KINFRAME_MAX_TELEGRAM_ITEMS_PER_CHAT_HOUR` | Accepted media per chat per hour; beyond: `rate_limited` |

Existing limits apply per item and send: `max_upload_bytes`, `max_video_seconds`, `max_items_per_send`.

Server configuration:

| Env | Default | Meaning |
|---|---|---|
| `KINFRAME_TELEGRAM_BOT_TOKEN` (or `…_FILE`) | empty | The bot (discovery `telegram`) |
| `KINFRAME_TELEGRAM_MODE` | `auto` | `auto`, `webhook`, `polling`, `off` (§7) |
| `KINFRAME_TELEGRAM_WEBHOOK_URL` | `<public URL>/v1/telegram/webhook` | Override behind unusual proxies |
| `KINFRAME_TELEGRAM_API_URL` | `https://api.telegram.org` | Local Bot API server (§9.3) |
| `KINFRAME_TELEGRAM_LOCAL_FILES_DIR` | empty | Its `--local` file directory as mounted here |
| `KINFRAME_TELEGRAM_MAX_DOWNLOAD_MB` | 20 (2000 with a local server) | Bot download cap |
| `KINFRAME_TELEGRAM_WORKERS` | 4 | Chats processed in parallel |
| `KINFRAME_TELEGRAM_WEBHOOK_IP_CHECK` | `off` | §13.2 |

Rate limits (in memory, per process, like the others): codes 30/h per user; wrong codes 10/h per chat; per Telegram
user 100 accepted media per hour across chats; error replies 1 per code per chat per 10 min; promotions 1 per chat per
10 min; outbound messages 1 per 3 s per group chat, reactions 1 per second per chat, 20 calls per second globally.

## 15. Error codes

### 15.1 Chat status (`TelegramChat.error_code`, `ended_reason`)

| Code | Status | Shown to managers |
|---|---|---|
| `privacy_mode` | `needs_attention` | "The bot only sees commands in Peeters family. Make it an admin of the group (Group info → Administrators), or ask the server's operator to turn off privacy mode." |
| `bot_changed` | `needs_attention` | "This server uses a different Telegram bot now. Connect the group again." |
| `cannot_post` | warning (`warnings`), stays `active` | "The bot isn't allowed to post in the group, so it can't tell people when something goes wrong." |
| `reactions_off` | warning | "Reactions are turned off in this group, so the bot confirms photos silently." |
| `disconnected`, `stopped`, `bot_removed`, `chat_gone`, `bot_blocked`, `target_deleted` | `disconnected` (`ended_reason`) | "Disconnected by Anna" / "Disconnected with /stop by Ben" / "The bot was removed from the group" / "The group no longer exists" / "Opa blocked the bot" / — |

Push `telegram_attention` to the owner only, at most once per chat and code per 7 days; `bot_removed`/`chat_gone`
also push once. Recovery pushes nothing.

### 15.2 In the chat (per message, `TelegramContribution.skip_reason`)

| Reason | Reply (localized, silent, rate-limited) |
|---|---|
| `too_large_for_bot` | "This file is over 20 MB, too big for the Telegram bot. Send it with the kinframe app instead." [Get the app] |
| `too_large` | "This file is too big for the frame (over 2 GB)." |
| `too_long` | "This video is longer than 5 minutes, so it can't go to the frame. Trim it, or send it with the kinframe app." |
| `unsupported` / corrupt | "I couldn't read this file." |
| `spoiler` | 🙈 reaction; first time per chat: "Photos marked as spoiler aren't sent to the frame." |
| `not_allowed` (`linked_only`) | Promotion with **Ask to join** (§10.3), once per user; afterwards silent. |
| `not_allowed` (blocked, paused) | Silent |
| `rate_limited` | "That's a lot of photos at once! The rest didn't go to the frame; try again in an hour or use the app." (once) |
| `needs_attention` | "This group can't send to Oma's frame right now. Anna has been told." (once per user per day) |

Redeem replies: "This link has expired. Create a new one in the kinframe app." (`code_expired`), "This link was already
used." (`code_used`), "This group already sends to Oma's frame." (`already_connected`), "Oma's frame already has 5
Telegram chats." (`limit_reached`), "Open this link in a private chat with the bot." / "…and pick a group."
(`wrong_chat_kind`), "This Telegram account is already linked to another kinframe account." (`already_linked`).
`TelegramCode.error_code` carries the same codes for the app.

### 15.3 API problem codes

New: `telegram_already_linked` (`409`, linking when the account already has a Telegram link: unlink first). Used:
`feature_disabled` (no bot), `limit_reached`, `rate_limited`, `validation_failed`, `missing_permission`, `not_found`,
`invalid_transition`.

## 16. Data model

Migration **`00021_telegram.sql`**: numbered after the email inbox's migration (`00020`, per `email-inbox.md`; the
last migration in the tree today is `00019_photo_sources.sql`). If the email inbox lands later or not at all, this
takes the next free number.

```sql
-- The bot's own state (one row).
CREATE TABLE telegram_bot (
    id                       boolean PRIMARY KEY DEFAULT true CHECK (id),
    bot_id                   bigint      NOT NULL,
    username                 text        NOT NULL,
    can_read_all_group_msgs  boolean     NOT NULL,
    privacy_off_since        timestamptz,                -- first seen with privacy mode off (§6)
    mode                     text        NOT NULL CHECK (mode IN ('webhook', 'polling')),
    poll_offset              bigint      NOT NULL DEFAULT 0,
    updated_at               timestamptz NOT NULL DEFAULT now()
);

-- Intake and dedupe (§8.1). Payload cleared once processed; rows deleted after 48 h.
CREATE TABLE telegram_updates (
    update_id     bigint      PRIMARY KEY,
    chat_id       bigint,
    payload       jsonb,                                  -- reduced update; null after processing
    state         text        NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'failed')),
    attempts      integer     NOT NULL DEFAULT 0,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz
);
CREATE INDEX telegram_updates_pending_idx ON telegram_updates (chat_id, update_id) WHERE state = 'pending';

CREATE TABLE telegram_codes (
    id              uuid PRIMARY KEY,
    code_hash       text        NOT NULL UNIQUE,         -- sha256
    purpose         text        NOT NULL CHECK (purpose IN ('connect', 'link', 'join')),
    chat_kind       text CHECK (chat_kind IN ('group', 'private')),
    link_account    boolean     NOT NULL DEFAULT false,
    created_by      uuid REFERENCES users ON DELETE CASCADE,          -- null for join codes
    frame_id        uuid REFERENCES frames ON DELETE CASCADE,
    feed_id         uuid REFERENCES feeds  ON DELETE CASCADE,
    for_tg_user_id  bigint,                                           -- join codes: the intended Telegram user
    for_chat_id     uuid,                                             -- join codes: the binding (no FK: survives it)
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    result_chat_id  uuid,                                             -- the binding it created
    error_code      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (purpose <> 'connect' OR ((frame_id IS NULL) <> (feed_id IS NULL)))
);

-- A binding (TelegramChat).
CREATE TABLE telegram_chats (
    id                 uuid PRIMARY KEY,
    tg_chat_id         bigint      NOT NULL,
    previous_chat_id   bigint,                            -- before a supergroup migration
    bot_id             bigint      NOT NULL,
    chat_kind          text        NOT NULL CHECK (chat_kind IN ('group', 'private')),
    is_forum           boolean     NOT NULL DEFAULT false,
    title              text        NOT NULL,              -- group title / private: the user's display name
    private_tg_user_id bigint,                            -- private chats: the one contributor
    owner_user_id      uuid REFERENCES users ON DELETE SET NULL,
    frame_id           uuid REFERENCES frames ON DELETE SET NULL,  -- set null → target_deleted handled in code
    feed_id            uuid REFERENCES feeds  ON DELETE SET NULL,
    contributors       text        NOT NULL DEFAULT 'everyone' CHECK (contributors IN ('everyone', 'linked_only')),
    frame_replies      boolean     NOT NULL DEFAULT false,
    notify_reactions   boolean     NOT NULL DEFAULT false,
    topic_id           bigint,                            -- forum: message_thread_id, null = all
    topics             jsonb       NOT NULL DEFAULT '{}', -- thread id -> name, as seen
    status             text        NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active', 'needs_attention', 'paused', 'disconnected')),
    error_code         text,
    warnings           text[]      NOT NULL DEFAULT '{}',
    ended_reason       text,
    ended_by_user_id   uuid REFERENCES users ON DELETE SET NULL,
    bot_status         text        NOT NULL DEFAULT 'member' CHECK (bot_status IN ('member', 'administrator')),
    bot_joined_at      timestamptz NOT NULL DEFAULT now(),
    attention_pushed   jsonb       NOT NULL DEFAULT '{}',
    welcome_sent_at    timestamptz,
    last_promo_at      timestamptz,
    last_delivery_at   timestamptz,
    counts             jsonb       NOT NULL DEFAULT '{}', -- sent, duplicates, skipped, failed, contributors
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    ended_at           timestamptz,
    CHECK (status = 'disconnected' OR (frame_id IS NULL) <> (feed_id IS NULL))
);
CREATE UNIQUE INDEX telegram_chats_live_idx ON telegram_chats (tg_chat_id) WHERE status <> 'disconnected';
CREATE INDEX telegram_chats_frame_idx ON telegram_chats (frame_id) WHERE frame_id IS NOT NULL;
CREATE INDEX telegram_chats_feed_idx ON telegram_chats (feed_id) WHERE feed_id IS NOT NULL;
CREATE INDEX telegram_chats_owner_idx ON telegram_chats (owner_user_id);

CREATE TABLE telegram_links (
    tg_user_id     bigint      PRIMARY KEY,
    user_id        uuid        NOT NULL UNIQUE REFERENCES users ON DELETE CASCADE,
    display_name   text        NOT NULL,
    username       text,
    language_code  text,
    can_message    boolean     NOT NULL DEFAULT true,
    linked_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE telegram_contributors (
    id             uuid PRIMARY KEY,                      -- opaque id for the API
    chat_id        uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    tg_user_id     bigint      NOT NULL,                  -- or the sender_chat id for anonymous/channel senders
    display_name   text        NOT NULL,
    language_code  text,
    blocked        boolean     NOT NULL DEFAULT false,
    promo_sent_at  timestamptz,
    sent_count     integer     NOT NULL DEFAULT 0,
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chat_id, tg_user_id)
);

-- One row per accepted or refused media message (§5.4); deleted after 90 days.
CREATE TABLE telegram_contributions (
    chat_id          uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    message_id       bigint      NOT NULL,
    tg_chat_id       bigint      NOT NULL,                -- at the time (migrations change it)
    media_group_id   text,
    contributor_id   uuid REFERENCES telegram_contributors ON DELETE SET NULL,
    sender_user_id   uuid REFERENCES users ON DELETE SET NULL,  -- the kinframe sender (linked user or owner)
    file_unique_id   text,
    state            text        NOT NULL CHECK (state IN ('collecting', 'sent', 'duplicate', 'skipped', 'failed')),
    skip_reason      text,
    failure_code     text,
    attempts         integer     NOT NULL DEFAULT 0,
    batch_id         uuid,
    item_id          uuid REFERENCES items ON DELETE SET NULL,
    send_id          uuid REFERENCES sends ON DELETE SET NULL,
    caption_source   boolean     NOT NULL DEFAULT false,  -- this message's caption set the album's captions
    reaction_shown   text,                                -- emoji currently set by the bot
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, message_id)
);
CREATE INDEX telegram_contributions_item_idx ON telegram_contributions (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX telegram_contributions_file_idx ON telegram_contributions (file_unique_id);

-- Open batches (§8.4).
CREATE TABLE telegram_batches (
    id              uuid PRIMARY KEY,
    chat_id         uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    contributor_id  uuid        NOT NULL REFERENCES telegram_contributors ON DELETE CASCADE,
    sender_user_id  uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    outside         boolean     NOT NULL,                 -- sent by the owner on the contributor's behalf, always moderated
    opened_at       timestamptz NOT NULL DEFAULT now(),
    last_at         timestamptz NOT NULL DEFAULT now(),
    closes_at       timestamptz NOT NULL,
    items           integer     NOT NULL DEFAULT 0
);
CREATE INDEX telegram_batches_due_idx ON telegram_batches (closes_at);

-- Credit: telegram joins the photo services and email. sends.via_label / items.via_label (the person's name)
-- come from 00020_email_inbox.sql and are reused.
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email', 'telegram')),
    ADD COLUMN telegram_chat_id uuid REFERENCES telegram_chats ON DELETE SET NULL;
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email', 'telegram')),
    ADD COLUMN via_telegram_chat_id uuid REFERENCES telegram_chats ON DELETE SET NULL;

-- Join links from the app promotion (§10.3): the email inbox's generic channel columns (00020) gain 'telegram'.
-- invites.via_ref / feed_invites.via_ref = the telegram_contributors id; join requests: via_name
-- ("Ben · Telegram, Peeters family"), via_ref (the telegram_contributors id), via_matches null.
ALTER TABLE invites DROP CONSTRAINT invites_via_check;
ALTER TABLE invites ADD CONSTRAINT invites_via_check CHECK (via IN ('email', 'telegram'));
ALTER TABLE feed_invites DROP CONSTRAINT feed_invites_via_check;
ALTER TABLE feed_invites ADD CONSTRAINT feed_invites_via_check CHECK (via IN ('email', 'telegram'));
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite', 'email', 'telegram'));
ALTER TABLE feed_join_requests DROP CONSTRAINT feed_join_requests_via_check;
ALTER TABLE feed_join_requests ADD CONSTRAINT feed_join_requests_via_check CHECK (via IN ('invite', 'email', 'telegram'));
ALTER TABLE telegram_chats ADD COLUMN offer_app_invite boolean NOT NULL DEFAULT true;
```

Notes:

- `internal/telegram`: Bot API client (token redaction, `429 retry_after`, migration errors), intake (webhook handler
  hand-written and excluded from codegen like the OAuth callback; poll loop with the advisory lock), workers per
  chat, the batch closer (tick 5 s on `telegram_batches_due_idx`), outbound queue (reactions, replies, rate limits).
  `telegramtest` has an httptest fake of the Bot API.
- Telegram sends: `sends.via = 'telegram'`, `telegram_chat_id` set. The `delivered` push and `TouchLastSent` skip
  sends whose batch was `outside` (the owner didn't send them); linked senders get the normal pushes.
- Moderation: a placement from an `outside` batch starts `pending_moderation` whenever the frame has moderation on
  (§8.7).
- Reactions back: the existing reaction/reply paths call a hook with the placement's item; when a
  `telegram_contributions` row exists for it, the outbound queue sets the emoji (§10.2).
- Maintenance: processed updates after 48 h, contributions after 90 days, disconnected bindings after 30 days, used or
  expired codes after 7 days (join codes 30 days after expiry).

## 17. Release plan

Phase **+**, planned as **v1.5 Telegram**, after v1.4 Email to frame (`email-inbox.md`):

1. Server: bot client, webhook and polling, intake, bindings with frame and feed targets, sender decision, batching,
   reactions back, commands in English and Dutch, then all 36 locales; web: the Telegram section on frames and feeds
   (connect with QR and link, status, settings, people, disconnect), *Link my Telegram* in account settings, the
   credit.
2. Family apps (Android, iOS): the same screens; connect opens `https://t.me/<bot>?startgroup=…` (Telegram's app
   handles it; without Telegram the link opens the web page that offers the store), the credit "via Telegram".
3. Frame (Android, browser frame): "Jan via Telegram" in the credit (`label` instead of the sender for
   `service: telegram`, as for email). Older frames show the sender's name (the owner, for outside contributions).
4. Operator docs: BotFather steps, privacy mode, webhook vs polling, the local Bot API server compose snippet
   (`docs/deploy.md`, when built).

Later (not now): channels as a source, other messengers (WhatsApp Business API, Signal) on the same model, a target
picker for linked users in private chats, unsend from Telegram.

## 18. Decisions and open questions

Taken (2026-11-19), encoded above:

1. One bot per server, configured by the operator; webhook with secret token, registered at start, or long polling;
   hidden when unconfigured (§7, discovery `telegram`).
2. Connect from the app with a one-time code and a `startgroup`/`start` deep link; one chat → one target, several chats
   per target; disconnect from the app or by removing the bot; supergroup migrations (§5, §12).
3. Privacy mode off (operator) or bot admin (group), detected and reported (§6).
4. The group is the trust boundary; `linked_only` setting; *Link my Telegram*; outside contributions sent by the owner
   and credited with the Telegram name; private chats only through a connect code (§4.3).
5. Media rules: largest photo, image documents for originals, videos and animations within limits, albums as one send,
   captions, caption edits, deletions don't unsend, 20 MB limit and the local Bot API server (§8, §9).
6. Quiet feedback: reactions, ❤ mirrored from frames, frame replies optional, rate-limited error replies (§10.2).
7. App promotion once per person, with the join link (§10.3).
8. Commands `/start`, `/help`, `/status`, `/stop`, localized (§11).
9. Security: secret token, update dedupe, rate limits, minimal data, retention, token as a secret with `_FILE` (§13).
10. Clients: Telegram section per frame and feed, *Link my Telegram*, credit (§17).

Made in this spec:

- **Private chats**: no pending queue. A private chat is bound by a connect code like a group, so its one user was
  invited by a manager; strangers get help and the app links. *Send from my Telegram* links and connects in one step.
- **Batching**: per chat and contributor, 60 s quiet / 10 min / 200 items, albums never split (§8.4).
- **Outside contributions are always moderated** on frames with moderation on, although the owner is their sender
  (§8.7).
- **Albums share their caption** across their items (§8.5).
- **Confirmation is 👍** (✅ is not an emoji bots may set); one reaction per album; frame reactions replace it (bots
  have one reaction per message).
- **Spoilers are skipped** with 🙈; forwards accepted and credited to the forwarder; round videos, voice and stickers
  ignored.
- **The join link goes through a private chat with the bot**, so the person who taps it is verified and accepting it
  can link their Telegram account safely (§10.3).
- **Ownership falls back to the frame's (feed's) owner** when the chat's owner loses their rights, as for email
  addresses, instead of photo sources' `needs_attention` (§4.4).
- **Reaction notifications to the owner are off by default** for outside contributions (`notify_reactions`): the group
  already sees them.

After design review (2026-11-19):

- **Join requests use the generic channel shape** shared with the email inbox: `JoinRequest.via: telegram` with
  `via_name` and `via_matches` (null for Telegram); invites and join requests carry `via`/`via_ref` (§10.3, §16).
  Former open question 3.
- **Old frames' credit**: frames without the `via` field show the owner's name for outside contributions ("Anna"
  for Jan's photos). Accepted as a transitional limitation, as for email. Former open question 1.
- **Defaults kept**: the 60 s / 10 min batch window (former open question 2); no `/remove` command (4); no 👀 "seen"
  reaction (8); no target picker in private chats (9); the owner's locale for group-wide messages (10).

Open questions:

1. **Admin rights in the deep link**: `?startgroup=<code>&admin=…` can ask Telegram to add the bot as an admin right
   away, which makes privacy mode irrelevant. It needs at least one right (none is "harmless"); asking for
   `pin_messages` only is a candidate. To be tested against Telegram's clients later.
2. **Edited media** (a message's photo replaced) is ignored; photo sources replace media in place, which could be
   reused.
3. **Round videos** (`video_note`) are ignored; some families use them as little greetings for Oma.
4. **Reactions per album** sit on the first message; whether Telegram's clients show that reaction for the album as a
   whole must be checked on iOS, Android and desktop before release.
5. **Unsend from Telegram** (a `/remove` reply), a **"seen" reaction**, a **target picker** in private chats and a
   **per-chat language** stay out unless families ask for them.

## 19. Contract summary

Applied to `docs/api/openapi.yaml` (tag `telegram`) and `docs/api/README.md` §5.17 (2026-11-19):

- **Discovery**: `telegram`, `telegram_bot_username`, `telegram_privacy_mode`, `telegram_max_file_bytes`.
- **Limits**: `max_telegram_chats_per_target` (5), `max_telegram_chats_per_user` (20),
  `max_telegram_items_per_chat_hour` (300).
- **Codes**: `POST /v1/frames/{frameId}/telegram-codes`, `POST /v1/feeds/{feedId}/telegram-codes`
  (`TelegramConnectCodeCreate {chat_kind, link_account}`), `POST /v1/me/telegram/link-code`, `GET|DELETE
  /v1/telegram-codes/{codeId}` (`TelegramCode`: `purpose`, `status`, `url` and `code` only on create, `chat_id`,
  `error_code`).
- **Chats**: `GET /v1/frames/{frameId}/telegram-chats`, `GET /v1/feeds/{feedId}/telegram-chats`, `GET|PATCH|DELETE
  /v1/telegram-chats/{chatId}` (`TelegramChat` with `target` (`TelegramChatTarget`), `owner`, `status`,
  `error_code`, `warnings`, `ended_reason`, `contributors`, `frame_replies`, `notify_reactions`, `offer_app_invite`,
  `topic_id`, `topics`, `counts`; `TelegramChatUpdate` with `paused` and `take_over`), `GET
  /v1/telegram-chats/{chatId}/contributors`, `PATCH …/contributors/{contributorId} {blocked}`.
- **Linked account**: `GET|DELETE /v1/me/telegram` (`TelegramLink`).
- **Webhook**: `POST /v1/telegram/webhook`, security scheme `telegramWebhook` (header
  `X-Telegram-Bot-Api-Secret-Token`); hand-written, excluded from code generation (`telegramWebhook` in
  `server/internal/api/gen/cfg.yaml`'s `exclude-operation-ids`).
- **Shared schemas**: `PhotoService` gains `telegram`; `ItemVia.label` is the person's name for `telegram` (as for
  `email`) and `ItemVia.telegram_chat_id` is new; `JoinRequest.via` gains `telegram` (with the generic `via_name`,
  and `via_matches` always null); problem code `telegram_already_linked`.
- **Events**: `telegram_chat.updated`, `telegram_chat.removed`, `telegram_code.updated`, `telegram_link.updated`;
  push key `telegram_attention`; `chat_id` in push `ids`.
