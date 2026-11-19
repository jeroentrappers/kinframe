# Feeds

Status: **draft** (2026-11-18). Extends `product-spec.md` (§3 concepts, §4.3 sending, §4.6 library). The API contract
is in `docs/api/openapi.yaml` (tags `feeds`, `subscriptions`) and the protocol guide `docs/api/README.md` §5.12; the
proposed tables and the placement algorithm are in `feeds-data-model.md`.

## 1. Summary

Today kinframe is push-only: you are a member of a frame and every send picks frames. A **feed** adds a publish/
subscribe path next to it: a family (or anyone) publishes a feed, and frames **subscribe** to it. It is a private,
Instagram-style channel for a family: "Kids of the Peeters family" posts once, and Oma's frame, Opa's frame in the
care home and the aunt's kitchen frame all get the photos, without anyone having to remember every frame on every
send, and without every poster having to be a member of every frame.

- Direct sends stay exactly as they are. A send can go to frames and feeds at once.
- Media is stored once. Each subscribed, active frame gets an ordinary **placement** whose origin is the feed, so the
  frame needs almost nothing new: feed posts are photos like any other.
- Feeds are private. There is no directory, no search, no public feed, no follower count. You only get in with an
  invite, and a frame only subscribes with the consent of both sides.

## 2. Concepts

- **Feed**: a named channel with an optional cover (one of its own photos). Lives on the server, belongs to nobody's
  frame. Settings: *auto-approve subscriptions* (default off), *invites need approval* (default on), *members see
  reactions* (default off), *posters can invite* (default off, §11).
- **Feed membership**: links an account to a feed with a role. Separate from frame membership: being in a feed gives
  no rights on any frame, and being on a frame gives no rights in a feed.
  - `owner`: exactly one. Everything, including transferring ownership and deleting the feed.
  - `admin`: manage members (except the owner and other admins), invites, subscriptions and settings; remove any post.
  - `poster`: post to the feed, edit captions and fit/focal point of their own posts, unsend their own posts.
  - Every role can post. There is no read-only role: the audience of a feed is frames, not people.
- **Feed invite**: link, QR and short code, single- or multi-use, with expiry, like a frame invite. Two purposes:
  - `join`: become a poster. Needs approval by an admin unless *invites need approval* is off.
  - `subscribe`: lets the person who opens it subscribe one or more frames they manage, without joining the feed.
    Created by an admin, so redeeming it counts as pre-approved.
- **Subscription**: connects one frame to one feed, with a **status** and a **history option**.
- **Post**: what one send put in one feed: one or more items, in send order, with the poster and time. Items are the
  same items as in the poster's sent history (one item, one master, many placements).
- **Feed placement**: a placement whose origin is a feed (`origin: feed`). It behaves like any placement on the frame
  (delivery, seen, reactions, hide, trash, albums, eviction).

## 3. User stories

### Family app and web

1. *Create a feed.* Anna taps "New feed", names it "Peeters kids", optionally picks a cover later. She is owner.
2. *Invite posters.* Anna shares a join link (or QR, or code `KF7Q-M2XA`) in the family chat. Ben opens it, signs in,
   sees "Join Peeters kids (Anna)". With approval on, Anna gets a push and approves. Ben can now post.
3. *Subscribe a frame you manage.* Ben is also admin of Oma's frame. In the feed (or in the frame's settings →
   Feeds), he taps "Show on a frame", picks Oma's frame and a history option ("only new posts", "the last 50", "all").
   Ben is not a feed admin, so the request waits for Anna (push: "Ben wants to show Peeters kids on Oma's frame").
   Anna approves; the backfill starts.
4. *Invite a frame you don't manage.* Opa's frame is managed by the care-home contact Carla, who is in no feed.
   Anna creates a `subscribe` invite and sends it to Carla. Carla opens it, picks Opa's frame and "only new posts".
   Because an admin made the link, the subscription is active right away.
5. *Post.* In the send flow the recipient list shows frames and, in their own section, feeds ("Peeters kids · 5
   frames"). Ben selects one feed and Oma's frame; everything else (caption, trim, fit, focal point, limits) is as
   today.
6. *See receipts.* Ben's post shows "Seen on 4 of 5 frames" and the reactions with frame names ("❤ Oma's frame,
   😂 Kitchen"). Tapping opens per-frame detail. Anna, as admin, sees the same for every post. Other posters see the
   post and its photos but not its receipts or reactions (unless *members see reactions* is on).
7. *Unsend.* Ben unsends a post (or one photo of it); it disappears from every subscribed frame. Anna can remove any
   post the same way.
8. *Manage the subscription from the frame side.* In Oma's frame settings → Feeds, Ben sees each feed with its status
   and can pause, resume, change the history option, or unsubscribe.
9. *Moderate.* Opa's frame has moderation on. Feed posts land in its moderation queue like direct sends; Carla
   approves or rejects them there. Rejecting affects only Opa's frame.
10. *Leave or remove.* Members leave a feed (optionally removing their posts); admins remove members (optionally
    removing their posts); the owner transfers ownership or deletes the feed.

### Frame

1. A feed post arrives like a direct send: "N new" card, new-photo viewer, screen wakes when idle, reactions.
2. The caption credit reads "Anna · Peeters kids" (feed name in place of "shared by").
3. History is quiet: photos placed by a backfill (subscribing with history, changing the option, resuming) join the
   slideshow in their original order and never trigger the "N new" card or wake the screen.
4. *Feed mode*: the slideshow can play one feed only, set remotely like album mode (`slideshow.feed_id`), and from
   the frame's quick menu where the album picker is.
5. On the frame, someone may hide, delete or react to a feed photo; it affects only this frame.

## 4. Permissions

### 4.1 Feed roles × actions

| Action | owner | admin | poster |
|---|---|---|---|
| See the feed, its members, its active subscriptions (frame names) and all posts | ✓ | ✓ | ✓ |
| Post (send with the feed as recipient), forward an own send to the feed | ✓ | ✓ | ✓ |
| Edit caption/fit/focal point of own items, unsend own posts or items | ✓ | ✓ | ✓ |
| See receipts and reactions of own posts | ✓ | ✓ | ✓ |
| See receipts and reactions of others' posts | ✓ | ✓ | only with *members see reactions* |
| Remove anyone's post or item | ✓ | ✓ | — |
| Rename, set cover, change feed settings | ✓ | ✓ | — |
| Create, list and revoke invites; approve join requests | ✓ | ✓ | `join` invites (own) only with *posters can invite*; no approvals |
| Change roles (poster ↔ admin) | ✓ | posters only | — |
| Remove members (optionally with their posts) | ✓ | posters only | — |
| See pending/rejected subscriptions and who asked; approve, reject, remove any subscription | ✓ | ✓ | — |
| Request a subscription for a frame you manage | ✓ (active at once) | ✓ (active at once) | ✓ (needs approval) |
| Transfer ownership, delete the feed | ✓ | — | — |
| Leave the feed | after transfer | ✓ | ✓ |

### 4.2 Frame roles × subscription actions

"Frame manager" below means owner, admin, or a member with *change settings* on that frame.

| Action | frame manager | other frame member | feed admin (not frame manager) |
|---|---|---|---|
| Request a subscription (needs feed membership or a `subscribe` invite) | ✓ | — | — |
| Withdraw a pending request | ✓ (and the requester) | — | — |
| Pause, resume, change the history option | ✓ | — | — |
| Unsubscribe | ✓ | — | — |
| Approve / reject a pending request | — | — | ✓ |
| Remove an active or paused subscription | ✓ (unsubscribe) | — | ✓ |
| See the frame's subscriptions (feed name, cover, status) | ✓ | active ones | the feed's own |
| Set feed mode (`slideshow.feed_id`) | ✓ | — | — |
| Hide, trash, purge, approve (moderation) feed placements on the frame | as for any placement (*manage library*, moderation: owner/admin) | as for any placement | — |

A person who is both a feed admin and a manager of the frame gets an active subscription without approval.

## 5. States

### 5.1 Subscription

```
             request (requester is feed admin, auto-approve on, or subscribe invite by an admin)
   ┌──────────────────────────────────────────────────────────────┐
   │                                                              ▼
request ──▶ pending ──approve──▶ active ◀──resume── paused
              │  │                │  ▲               │
           reject withdraw        │  └─────pause─────┘ (frame side)
              ▼  │                │
          rejected  │    unsubscribe (frame side) / remove (feed admin) / frame or feed deleted
              │     ▼                ▼
              └──▶ removed ◀─────────┘
```

| Status | Meaning | Placements on the frame |
|---|---|---|
| `pending` | Waiting for a feed admin. | None. |
| `active` | Posts are placed on the frame. | Per the history option, plus every new post. |
| `paused` | The frame side paused it. | Kept on the server but **not shown**: they leave the frame (rendered as `placement.remove`) and come back on resume. No new placements are made while paused; on resume the posts made in the meantime are placed quietly. |
| `rejected` | A feed admin said no. Kept 30 days so the requester can see it, then deleted. A new request is allowed. | None. |
| `removed` | Ended by either side, or the frame or feed was deleted. Terminal; the row is kept 30 days for the history in the apps, then deleted. Subscribing again starts fresh. | Feed-only placements are removed (§6.3). |

At most one live (`pending`, `active` or `paused`) subscription per frame and feed.

**Why pause hides existing photos.** Pause is the frame side's "not now" for a feed: a busy period, a sad event in the
family, a guest using the room. If pausing only stopped new posts, the feed would keep filling the slideshow and the
only remedy would be unsubscribing, which throws away reactions, seen state and the history option. Hiding and
restoring makes pause fully reversible and frees the frame's storage meanwhile (the frame re-downloads on resume).
Photos that also reached the frame directly stay, because they are not the feed's.

### 5.2 History option

Set per subscription by the frame side, at request time or later.

| Option | Placed when the subscription becomes active | Afterwards |
|---|---|---|
| `none` | Nothing. | Every new post. |
| `last_n` (N = 1…1000) | The N most recent items (not posts) of the feed at activation time. | Every new post. |
| `all` | Every live item of the feed. | Every new post. |

- The option describes **history only**; it is not a rolling limit. "Last 50" never removes item 51 later.
- The boundary is the subscription's first activation (`activated_at`). Changing the option later recomputes the
  history from that same boundary: going up (`none` → `last_n` → `all`, or a bigger N) **backfills** quietly; going
  down **trims** feed-only placements of history items that are no longer wanted. Posts made after the boundary are
  never trimmed.
- Items that someone on the frame deleted, or that its moderation rejected, are never placed again by a backfill.
- Backfill runs as a background job (frame renditions may have to be made for that screen size first). The
  subscription carries `backfill: {state, placed, total}` so apps can show progress. Very large backfills place the
  newest first.

### 5.3 Post lifecycle

```
send ──▶ processing ──▶ live ──▶ removed (poster unsend, admin removal, member removed with posts,
                         │                 account deleted with items, feed deleted, sender's DELETE /sends/{id})
                         └──▶ failed (media could not be processed; never placed, shown to the poster only)
```

Per item and subscribed frame, the feed placement follows the normal placement states (`pending → ready → delivered →
seen`, `failed`, plus `pending_moderation`, `hidden`, `trashed`), and additionally:

| Frame-side state | Cause | Effect on the post |
|---|---|---|
| `declined` | The frame's moderation rejected it, or someone purged it on that frame. | Counted as "removed on this frame" in receipts; never re-placed on that frame. |
| `suspended` | The subscription is paused. | Not counted in "N of M"; listed as paused in receipts. |

Removing one item of a multi-item post removes it everywhere; the post disappears when its last item is gone.

## 6. Behaviour and edge cases

### 6.1 Sending

- A feed is a recipient next to frames. One send can mix frames and feeds; recipients must not be empty.
- Validation is all-or-nothing as today: the caller must be a member of every feed (any role) and have *send* on every
  frame. Limits (items per send, video length after trim, supported types, live items) are the same; each feed
  counts as one recipient, so a feed with 30 frames does not multiply the item limit.
- The server stores each item once and makes one post per feed. Placements are materialized per subscribed `active`
  frame. Renditions are made per screen size, reused across frames of the same size, exactly as for direct sends;
  focal point / face centring is per item.
- `POST /v1/sends/{id}/forward` with `feed_ids` posts an existing send to feeds (no new upload). Sharing a frame's
  library to a feed is not in this version (§11).

### 6.2 The same item reaching a frame twice

A frame never holds two live placements of one item. When a send targets frame F directly and a feed F subscribes to,
or two feeds F subscribes to, F gets **one** placement:

- Its `origin` is the first path that created it, with direct sends winning within one send. Credit follows the origin:
  direct shows "Anna", feed shows "Anna · Peeters kids".
- It remembers every path that holds it (direct, and each feed). It is removed by a feed-side action only when **no**
  other path holds it: unsubscribing from feed A, or unsending the post from feed A, leaves a placement that also came
  directly or via feed B.
- A later direct forward of the item to F upgrades the existing feed placement to "held directly" instead of being
  skipped silently; the forward reports it in `skipped` as `already_on_frame` as today.

### 6.3 Removal paths

| Event | Server effect |
|---|---|
| Poster unsends a post or item / admin removes it | Post (item) removed from the feed; on every frame the feed-held link goes away and feed-only placements are removed. |
| Sender's `DELETE /v1/sends/{id}` | Removes direct placements, the send's posts in all feeds and their feed placements. |
| Frame side unsubscribes / feed admin removes the subscription | Subscription `removed`; feed-only placements of that feed on that frame are removed. `slideshow.feed_id` resets to null if it pointed at this feed. Frame managers are notified when a feed admin removed it, the feed admins when the frame side left. |
| Frame removed | Its subscriptions become `removed` (feed admins see "Frame removed"). Placements go with the frame as today. |
| Feed deleted (owner) | All posts removed, all subscriptions `removed`, all feed-only placements removed on every frame, `slideshow.feed_id` reset; frame managers notified. Items stay in the posters' sent history. |
| Member leaves or is removed, without "delete posts" | Their posts stay (credit unchanged). |
| … with "delete posts" | Their posts are removed as by an unsend. |
| Account deleted with "remove my items" | As above for every feed. Without it, posts stay with the soft-deleted sender's name. |
| Frame purges or its moderation rejects a feed placement | That frame only: `declined`, not re-placed by backfills. |
| Frame hides or trashes a feed placement | That frame only, as any placement. Restoring from trash restores it. |
| Frame loses its last manager / requester leaves the feed | Nothing: a subscription belongs to the frame, not to the person who asked. |

### 6.4 Moderation

A frame's moderation applies to feed posts exactly as to direct sends: with moderation on, a feed placement starts
`pending_moderation` unless the poster is owner or admin of that frame. Approving shows it; rejecting purges it on
that frame only (`declined`). The poster sees "awaiting approval" per frame in the receipt detail, without the
moderator's identity.

### 6.5 Offline frames and catch-up

Nothing in the feed path needs the frame online. Placements are created server-side; the frame catches up through
its change feed when it reconnects (§5.4 of the API guide). Because the feed is compacted, a frame that was offline
while a post was made and unsent, or while its subscription was paused and resumed, sees only the net result.
Receipts count an offline frame as "not delivered yet" and show its last-seen time.

### 6.6 Storage

- **Server**: no extra media. A post reuses the send's items; a subscription adds at most one frame rendition per new
  screen size per item (made in the background during backfill) and placement rows. The subscribe dialog estimates
  the backfill ("about 1 240 photos and videos, 3.1 GB") from the items and the frame's screen size.
- **Frame**: feed placements are cached and evicted like any other (least recently shown first). A large `all` history
  on a small frame is fine: evicted items are re-downloaded before they show. The owner sees storage in the app as
  today; frames with `storage_state: critical` get a warning next to the history option.
- **Operator limits** (discovery `limits`): `max_feeds_per_user` (default 20), `max_subscriptions_per_feed` (default
  100), `max_feed_backfill_items` (default 5000 per subscription; `all` is capped there, newest first).

### 6.7 Frame side details

- New posts are "new" on the frame (card, viewer, wake). Backfilled placements carry `backfill: true` and are not.
- `received_at` of a backfilled placement is the post time, so "order: received" interleaves history correctly
  instead of putting the whole backfill on top.
- Feed mode: `slideshow.feed_id` (null = all). It is mutually exclusive with `slideshow.album_id`: setting one clears
  the other. An empty or unknown feed plays everything, like album mode.
- Renaming a feed updates the credit on every frame (the server re-emits the affected placements).

## 7. Receipts and reactions

- Per post and per item: `frames` (active subscriptions that hold it), `delivered`, `seen`, `awaiting_approval`,
  `paused` (suspended), `removed` (declined on that frame). A frame counts as delivered/seen for a post when every
  item of the post is. Paused frames are not in the denominator ("Seen on 4 of 5 frames").
- Per frame (receipt detail): frame name, location label, online/last seen, per-item status, reaction and replies.
  Hidden and trashed are **not** revealed; they count as delivered or seen like for direct sends.
- Reactions and quick replies are per placement, as today, and go to the item's sender (the poster) with the frame
  name. Feed admins see them in receipts; other members only with *members see reactions*.
- Placements of a feed are not part of the send's `frames[]` (that stays the list of direct recipients); the send
  lists its posts in `feeds[]` with the same summary, so the history screen shows "Peeters kids · seen on 4 of 5".

## 8. Notifications

All go over the user websocket and, without an open websocket, as push (UnifiedPush, WebPush; FCM/APNs where
available), localized, carrying IDs only (API guide §6.3).

| Event | Who | Push key |
|---|---|---|
| Subscription requested | Feed owner and admins | `subscription_request` |
| Subscription approved / rejected | The requester | `subscription_decided` |
| Subscription removed by a feed admin, or feed deleted | Frame owner and admins | `subscription_removed` |
| Frame side unsubscribed | Feed owner and admins (websocket only; no push) | — |
| Join request (feed invite with approval) | Feed owner and admins | `feed_join_request` |
| Join approved / role changed / removed | That member | `feed_membership` |
| Reaction or quick reply on a feed placement | The poster (the item's sender) | `reaction`, `reply` (with `feed_id`, `post_id`) |
| Delivery and seen progress | The poster and feed admins, websocket only (`feed_post.updated`) | — |

There is deliberately no per-frame `delivered` push for feed posts (it would fire once per subscribed frame); the
receipts show it. New posts are not pushed to other feed members: the audience is the frames.

## 9. Privacy

- **No discovery.** Feeds are reachable only by membership, a valid invite or an existing subscription. Feed IDs are
  never accepted from people outside these; unknown and forbidden feeds both answer `404 not_found`.
- **What a feed sees of a frame**: its name and location label (to every feed member, for active subscriptions), its
  online state and per-post delivery, seen and reactions (to the poster and admins), and the requester's display name
  (to admins). Never its members, owner, settings, other content, hidden/trashed state or who moderated.
- **What a frame sees of a feed**: its name, cover and the posters' display names and avatars on the photos (like any
  sender), and to frame managers: the feed's member count and admins' names, so they know whom they are subscribing
  to. Not the feed's other subscriptions.
- The subscribe dialog says plainly: "Members of Peeters kids will see this frame's name, and posters will see when
  their photos were seen and how the frame reacted."
- Poster location names follow the poster's own *share location names* setting, unchanged.
- Leaving a feed does not remove your posts unless you choose so; deleting your account offers the same choice.
- The operator can see everything, as for frames (product spec §8, decision 3).

## 10. Release plan

Phase **+** (beyond Frameo), planned as **v1.1 Feeds**, after v1.0:

1. Server: tables, materialization, endpoints, change feed fields, notifications; web: feeds, members, invites,
   subscriptions, send-to-feed, receipts.
2. Family apps (Android, iOS): the same, plus invite deep links `https://<server>/fi/<token>` and codes in the
   existing "Enter a code" field.
3. Frame: credit "Anna · feed", `backfill` (quiet history), feed mode. Older frame apps already show feed posts as
   normal photos; they only miss the credit suffix and treat backfills as new.

## 11. Decisions and open questions

Settled after design review (2026-11-18):

1. **Pause semantics**: as designed. While paused, the feed's placements leave the frame (they are kept on the
   server and rendered as `placement.remove`) and come back on resume, together with the posts made meanwhile,
   placed quietly (`backfill`). Photos that also reached the frame directly, or through another active feed, stay.
2. **Subscribe invites**: yes. An owner/admin creates a `subscribe` invite; whoever redeems it subscribes frames
   they manage (owner, admin or *change settings*) without joining the feed, and the subscriptions are active at
   once because an admin made the link.
3. **Who may invite**: owner and admins by default. The feed setting *posters can invite* (`posters_can_invite`,
   default off) lets posters create `join` invites and list and revoke their own; `subscribe` invites stay with
   owner and admins.
4. **Feed mode vs album mode**: a separate `slideshow.feed_id`, mutually exclusive with `album_id` (setting one
   clears the other), reset when the subscription ends.
5. **Older frames**: the frame app announces that it shows backfilled placements quietly with
   `features: ["feed_backfill"]` in its heartbeat. Until a frame's latest heartbeat lists it, the server withholds
   placements with `backfill: true` from that frame's change feed (an older app would show a whole history as new)
   and emits them as soon as it does. New posts are never withheld. Browser frames are always current.

Still open:

1. **Albums as feeds** (decision 8): not merged now. Today albums are per frame in the server (`albums.frame_id`),
   although product spec §4.6 says an album can be shown on several frames; a later simplification could make a
   server-side album a feed without posters (or a feed a shared album), so "one album on several frames" and "a
   family channel" become one concept.
2. **Sharing from a frame's library to a feed** (`placements:share` with `feed_ids`) would let a frame member
   republish photos they didn't send. Left out: it blurs credit and consent. Forwarding an own send is allowed.
3. **Rolling limit**: some frames might want "keep only the last N from this feed" (a retention policy). Not
   included; `last_n` is history only.
4. **New-post push to members**: not sent (the audience is frames). A digest ("Ben posted 12 photos to Peeters kids")
   could be an opt-in later.
5. **Receipt granularity for multi-item posts**: delivered/seen per frame is "all items". "Any item" reads nicer
   for big posts but overstates; the per-item detail is always available.
