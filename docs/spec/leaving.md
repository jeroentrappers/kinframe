# Leaving a family: separations, divorces and the photos that stay

Status: **approved, being built** (2026-11-19). Extends `feeds.md` (leaving, removing members), `family-social.md`
(timeline, chats, On this day) and the API guide `docs/api/README.md`.

## 1. Summary

Families change: people separate, divorce, fall out or just drift away. Until now leaving was designed for drifting
away: you leave, your photos quietly stay or quietly go, depending on the path. For a separation that is not good
enough. Two things are true at once:

- The photos of the grandchildren are family history for both sides. Oma's frame must not go empty overnight because
  her son-in-law left.
- The person who leaves has a right to their own photos and to be left alone: what they sent is their personal data,
  and they decide whether it stays.

The rules below make the choice explicit, give it a notice period, protect people from being hurt by their own app
(memories of an ex), and stop contact that is no longer wanted.

## 2. Principles

1. **The sender decides about their own photos**, always, also after leaving or being removed. That right never
   expires (it is their personal data).
2. **Nothing disappears unnoticed.** Photos taken away from a frame wait 30 days in that frame's trash, visible to its
   owner, who is told and can ask to keep them.
3. **Staying is the default**, because it is what families almost always want; taking photos away is one clear choice
   away, never hidden.
4. **Removal by admins never deletes photos.** Admins can still remove individual posts, as today.
5. **No one is shown what hurts them.** Memories (On this day, recaps, suggestions) can leave a person out, privately.

## 3. User stories

1. *Leaving a family.* Tom and Lies divorce. Tom opens Lies's family "Janssens" → Leave. The sheet asks:
   - **Your photos (214):** "Leave them with the family" (default: they stay in the family and on its frames, still
     credited to Tom) or "Take them with me" (removed from the family and its frames; frames keep them in their trash
     for 30 days and their owners are told).
   - **Your comments (38):** "Leave them" (default) or "Delete them too".
   - **Frames you are a member of through this family:** Oma Janssens's frame, Kitchen frame — each with a checkbox
     "Leave this frame too" (checked by default for frames that get this family's photos and where Tom is not the
     owner).
   - A last line: "You can still remove any of your photos later from your history."
2. *Taking photos away.* Tom chose "Take them with me". His posts leave the family timeline at once, the frames stop
   showing them at once, and each frame's owner gets: "Tom took 214 photos away from Oma Janssens's frame. They stay
   in the frame's trash until 19 December." The frame's trash lists them under "Taken away by Tom".
3. *Asking to keep.* Lies (owner of Oma's frame) taps **Ask Tom to keep them** and may pick photos ("the 12 with the
   children"). Tom gets one push and an item in his history: "Lies asks to keep 12 photos on Oma Janssens's frame."
   He can allow all, allow some or decline. Allowed photos go back on the frame as before (still Tom's photos: he can
   remove them again later). Declined or unanswered photos are deleted from the frame when the 30 days end. One ask
   per frame per taking-away.
4. *Being removed.* An admin removes Tom from the family. His photos stay; he loses access to the family. In his
   sent history he still sees his own photos and can take them away later (story 2 applies, with the same notice).
5. *A frame you share.* Tom and Lies bought a frame together; Lies is owner, Tom admin. Tom leaves the frame with
   "Take my photos with me" or "Leave them". If they disagree about who owns a frame or family, the server operator
   can transfer ownership (§5.4).
6. *Leaving a frame.* Leaving a frame directly (not through a family) asks the same photo question for the photos
   Tom sent to that frame.
7. *Deleting the account.* Account deletion asks: "Take my photos with me" (default here: they leave every frame and
   family with the 30-day notice) or "Leave my photos to the families" (each photo is handed over to the owner of
   the family it was posted in, or the owner of the frame it was sent to; it stays credited "Tom (former member)").
   Handing over is a one-time consent recorded with the deletion.
8. *Memories.* Lies opens Settings → Memories → "Leave people out" and adds Tom. On this day, the monthly recap,
   "Send something to Oma's frame" suggestions and the weekly summary no longer show posts by Tom to Lies. Nobody
   is told. She can also hide a single memory ("Don't show this memory again").
9. *No more chatting.* Tom and Lies no longer share a family or a frame. Their direct chat stays readable but
   becomes read-only ("You can no longer message Lies"). Either can still block the other.
10. *Circles.* Lies's parents want to keep getting photos of the children from Tom. Ideally the parents and Tom share a small circle "Grandchildren" and Tom keeps posting there
    while he no longer sees the rest of the family. That needs a family role that sees only one circle, which is not
    built now (§6, decision B); today Tom either stays in the family (possibly as Reader) or posts to Oma's frame
    directly as a frame member.

## 4. Rules

### 4.1 What "take away" does

Taking away applies to a set of the leaver's **own items** in a scope: a family (its feed posts), a frame (its direct
placements), or everything (account deletion). For each item in scope:

- **Family posts** (`feed_posts` by the leaver in that family): the post is removed from the family (timeline,
  threads hidden with it); its feed placements on every subscribed frame are **taken away** (below). The leaver's
  copy in their sent history stays.
- **Placements** (direct or from the family): the placement gets `taken_away_at`, `taken_away_until` (+30 days) and
  `taken_away_by`; it leaves the slideshow and the library at once and appears in the frame's trash under "Taken away
  by Tom". The frame deletes its copy at once (cache) and restores it from the server only if it comes back.
- After `taken_away_until`, placements still taken away are purged like trash. Items with no placements, posts and
  messages left follow the existing media lifecycle.
- **Comments** (only with "Delete them too"): the leaver's comments in that family become tombstones ("Comment
  deleted"), as if deleted one by one.
- **Chat messages** are not touched by leaving a family (they belong to the chat, and the leaver can delete their
  own); family and circle chats follow membership as today.
- Taking away is idempotent and can't be undone by the leaver except through "Ask to keep" from the other side (or by
  sending the photos again).

### 4.2 Ask to keep

- Who: the frame's owner and admins (`manage_library`), for photos taken away from their frame; family owner and
  admins for photos taken away from their family (the post comes back to the timeline if allowed).
- One request per (scope, taking-away) with an optional selection (default: all). The leaver gets the push
  `keep_request` once; no reminders. Their history shows open requests until answered or expired.
- Answers: allow all, allow some (pick), decline. Allowed: `taken_away_at` cleared, the placement (and the post)
  is back, credited as before. The answer is final for that request.
- The request expires with the 30 days. Blocked people can't send keep requests (the leaver blocked the asker: the
  button says "Tom doesn't accept requests").

### 4.3 Removal by admins and leaving without a choice

- Removing a member (family or frame) never takes photos away. The removed person keeps the right to take away their
  own photos from their sent history (per send: "Remove from all frames and families", existing unsend) and per
  scope ("Take my photos away from Janssens") for families and frames they no longer belong to; both use §4.1.
- Old clients leaving without the choice: the server applies **keep** (the default).

### 4.4 Account deletion

- The deletion request carries `photos: take | leave` (default take).
- `leave`: every item that still has a live feed post or placement is handed over: `items.owner_user_id` becomes the
  family's owner (for feed posts) or the frame's owner (for direct placements); when an item is in several scopes it
  goes to the first family's owner (by post time), and other scopes keep pointing at it. The credit stays as a
  frozen display name with "(former member)". Items only in the leaver's history are deleted. The handover is logged
  (who, when, how many) for the operator.
- `take`: §4.1 for every scope, then the account is deleted as today; the 30-day trash notice still runs.

### 4.5 Leaving frames together with a family

- The leave sheet lists frames where the leaver is a member (not owner) and that subscribe to the family. Each one
  checked is left in the same request, with the same photo choice applied to that frame's direct placements by the
  leaver. Owners must transfer ownership first (existing rule).

### 4.6 Ownership by the operator

- `POST /v1/operator/frames/{id}/owner` and `POST /v1/operator/feeds/{id}/owner` {user_id, reason}: the operator moves
  ownership to an existing member. The old owner becomes admin (or is removed with `remove_previous: true`). Both are
  notified, and the change is logged with the reason.

### 4.7 Memories that leave people out

- Per user: `hidden_people` (user ids) and `hidden_memories` (post ids). Applied to: On this day, the monthly recap
  card as rendered for that user ("most-loved photo" picks the next one), suggestions in "Send something to Oma's
  frame", the weekly summary, and the frame? **No**: frames are shared and show what the family sent.
- Private: nobody is told, nothing changes for others. Hiding someone does not block them.

### 4.8 Contact that stops

- A direct chat between two people who no longer share a live family or frame becomes **read-only** for both: the
  history stays readable (and deletable message by message), sending answers `403 cannot_message`. It becomes
  writable again if they share a family or frame again. Group chats are unchanged (admins manage them); family and
  circle chats follow membership.

## 5. API (sketch; the contract goes into `openapi.yaml`)

| Endpoint | Change |
|---|---|
| `POST /v1/feeds/{id}/leave` | Body (optional) `{photos: keep\|take, comments: keep\|delete, leave_frame_ids: []}`; response says how many photos were taken away and from which frames |
| `POST /v1/frames/{id}/leave` | Body (optional) `{photos: keep\|take}` |
| `POST /v1/feeds/{id}/take-away`, `POST /v1/frames/{id}/take-away` | For people who already left or were removed: take their own photos away from that scope (also works for current members) |
| `GET /v1/me/leave-preview?feed_id=` | Counts and the frame list for the leave sheet |
| `GET /v1/frames/{id}/taken-away` | The frame's "taken away" groups (by person and date, counts, until) for managers |
| `POST /v1/keep-requests` `{frame_id\|feed_id, taking_id, item_ids?}` / `GET /v1/me/keep-requests` / `POST /v1/keep-requests/{id}/answer {allow: all\|some\|none, item_ids?}` | Ask to keep and answer |
| `DELETE /v1/me` | Body `{photos: take\|leave}` |
| `POST /v1/operator/frames/{id}/owner`, `POST /v1/operator/feeds/{id}/owner` | Operator ownership transfer |
| `GET`/`PUT /v1/me/memories` | `hidden_people`, `hidden_memories` |
| Device change feed | Taken-away placements are removed (existing `placement` removal); restored ones come back |

Push keys: `photos_taken_away` (frame owners/admins, family owner/admins), `keep_request` (the leaver),
`keep_answered` (the asker), `ownership_changed` (old and new owner). Websocket events: `placement.updated`,
`feed_post.updated`/`post.removed` as today, `keep_request.updated`.

## 6. Decisions

- **A. Defaults.** Leaving a family or frame: keep. Deleting the account: take. Comments: keep.
- **B. Circle-only membership for someone who left** (story 10) is not built now: it needs a family role that sees
  only one circle. Until then the family keeps Tom in the family (as Reader) or not at all.
- **C. 30 days** of notice for photos taken away; one keep request per taking-away; no reminders.
