# Feeds: data model and materialization

Status: **implemented** in `server/internal/db/migrations/00017_feeds.sql` (designed 2026-11-18 against `00001_init.sql`
… `00012` and the stores in `server/internal/store/`; server/NOTES.md lists what the build added).

## 1. Tables

```sql
CREATE TABLE feeds (
    id                          uuid PRIMARY KEY,
    name                        text        NOT NULL,
    cover_item_id               uuid REFERENCES items ON DELETE SET NULL,
    auto_approve_subscriptions  boolean     NOT NULL DEFAULT false,
    invite_requires_approval    boolean     NOT NULL DEFAULT true,
    members_see_reactions       boolean     NOT NULL DEFAULT false,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    deleted_at                  timestamptz            -- soft delete while the removal job runs
);

CREATE TABLE feed_members (
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('owner', 'admin', 'poster')),
    joined_at  timestamptz NOT NULL DEFAULT now(),
    last_posted_at timestamptz,
    PRIMARY KEY (feed_id, user_id)
);
CREATE UNIQUE INDEX feed_members_one_owner ON feed_members (feed_id) WHERE role = 'owner';
CREATE INDEX feed_members_user_idx ON feed_members (user_id);

-- Like invites, plus a purpose and a short code. Join requests reuse the frame shape.
CREATE TABLE feed_invites (
    id         uuid PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('join', 'subscribe')),
    token      text        NOT NULL UNIQUE,
    code       text        NOT NULL UNIQUE,         -- 8 Crockford base32 chars, never all digits
    created_by uuid REFERENCES users ON DELETE SET NULL,
    max_uses   integer,
    uses       integer     NOT NULL DEFAULT 0,
    note       text,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX feed_invites_feed_idx ON feed_invites (feed_id);

CREATE TABLE feed_join_requests (
    id         uuid PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    invite_id  uuid REFERENCES feed_invites ON DELETE SET NULL,
    invited_by uuid REFERENCES users ON DELETE SET NULL,
    note       text,
    status     text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied')),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz
);
CREATE UNIQUE INDEX feed_join_requests_one_pending ON feed_join_requests (feed_id, user_id) WHERE status = 'pending';

CREATE TABLE subscriptions (
    id             uuid PRIMARY KEY,
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    frame_id       uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    status         text        NOT NULL CHECK (status IN ('pending', 'active', 'paused', 'rejected', 'removed')),
    history        text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count  integer CHECK (history_count BETWEEN 1 AND 1000),   -- last_n only
    requested_by   uuid REFERENCES users ON DELETE SET NULL,
    via_invite_id  uuid REFERENCES feed_invites ON DELETE SET NULL,
    decided_by     uuid REFERENCES users ON DELETE SET NULL,
    removed_reason text CHECK (removed_reason IN ('unsubscribed', 'removed_by_feed', 'frame_removed', 'feed_deleted', 'withdrawn')),
    activated_at   timestamptz,            -- first activation: the history boundary, never moved
    paused_at      timestamptz,
    ended_at       timestamptz,            -- rejected or removed; rows are deleted 30 days later
    backfill_state text NOT NULL DEFAULT 'idle' CHECK (backfill_state IN ('idle', 'queued', 'running')),
    reconciled_version integer NOT NULL DEFAULT 0,   -- bumped by every change that needs a reconcile
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX subscriptions_one_live ON subscriptions (feed_id, frame_id)
    WHERE status IN ('pending', 'active', 'paused');
CREATE INDEX subscriptions_frame_idx ON subscriptions (frame_id) WHERE status IN ('pending', 'active', 'paused');
CREATE INDEX subscriptions_feed_active_idx ON subscriptions (feed_id) WHERE status = 'active';

-- A post is one send in one feed; its items are the send's items (no copies).
CREATE TABLE feed_posts (
    id             uuid PRIMARY KEY,             -- UUIDv7: also the feed's time order
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    send_id        uuid REFERENCES sends ON DELETE SET NULL,
    poster_user_id uuid REFERENCES users ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    removed_at     timestamptz,
    removed_by     uuid REFERENCES users ON DELETE SET NULL
);
CREATE INDEX feed_posts_feed_idx ON feed_posts (feed_id, id DESC) WHERE removed_at IS NULL;
CREATE INDEX feed_posts_poster_idx ON feed_posts (poster_user_id, feed_id);
CREATE UNIQUE INDEX feed_posts_send_unique ON feed_posts (feed_id, send_id) WHERE removed_at IS NULL;

CREATE TABLE feed_post_items (
    post_id    uuid    NOT NULL REFERENCES feed_posts ON DELETE CASCADE,
    item_id    uuid    NOT NULL REFERENCES items ON DELETE CASCADE,
    position   integer NOT NULL,
    removed_at timestamptz,
    PRIMARY KEY (post_id, item_id)
);
CREATE INDEX feed_post_items_item_idx ON feed_post_items (item_id);

-- Which subscription delivered which post item, and through which placement.
-- 'placed' rows hold the placement; 'declined' rows are tombstones (the frame rejected or purged it)
-- that stop backfills from placing it again.
CREATE TABLE feed_deliveries (
    subscription_id uuid NOT NULL REFERENCES subscriptions ON DELETE CASCADE,
    post_id         uuid NOT NULL,
    item_id         uuid NOT NULL,
    placement_id    uuid REFERENCES placements ON DELETE SET NULL,
    state           text NOT NULL CHECK (state IN ('placed', 'declined')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription_id, post_id, item_id),
    FOREIGN KEY (post_id, item_id) REFERENCES feed_post_items ON DELETE CASCADE
);
CREATE INDEX feed_deliveries_placement_idx ON feed_deliveries (placement_id) WHERE placement_id IS NOT NULL;

-- Placement origin and holds.
ALTER TABLE placements
    ADD COLUMN origin text NOT NULL DEFAULT 'send' CHECK (origin IN ('send', 'share', 'feed', 'import')),
    ADD COLUMN origin_feed_id uuid REFERENCES feeds ON DELETE SET NULL,
    ADD COLUMN held_directly boolean NOT NULL DEFAULT true,  -- a send, forward, share or import holds it
    ADD COLUMN feed_suspended boolean NOT NULL DEFAULT false, -- only feed holds, all of them paused
    ADD COLUMN backfill boolean NOT NULL DEFAULT false;      -- placed as history: frames don't treat it as new
CREATE INDEX placements_origin_feed_idx ON placements (origin_feed_id, frame_id) WHERE origin_feed_id IS NOT NULL;
```

Notes:

- The migration backfills `origin` from existing data: `share` where `shared_by_user_id IS NOT NULL`, `import` where
  the item has `sender_frame_id`, else `send`. `held_directly` stays true for all existing rows.
- `placements_live_unique (frame_id, item_id) WHERE removed_at IS NULL` stays: one live placement per item and frame.
  `feed_deliveries` is how several paths share it.
- `send_id` on feed placements is set (the send that posted the item), so `GET /v1/sends/{id}` can find them, but the
  send's `frames[]` and the per-send `delivered` push filter on `origin <> 'feed'` (today they filter on
  `shared_by_user_id IS NULL`; `SendDeliveredOn` gets the same extra condition).
- A placement is **feed-held** when a `placed` delivery row points at it. It is live as long as
  `held_directly OR feed-held`.
- `feed_suspended` is a denormalization: true iff `NOT held_directly` and every `placed` delivery row for it belongs
  to a `paused` subscription. It is set in the same transaction as pause/resume and read by the change-feed renderer.

## 2. Materialization

Everything runs in one transaction per frame, taking the frame row lock first (`LockFrame`, which also serializes the
change feed, see `server/NOTES.md` "Change feed"), so feed writes and change-feed seqs stay consistent. Multi-frame
operations loop over frames in `frame_id` order (no deadlocks) and are split into jobs when large.

### 2.1 `place(sub, post_item, quiet)` (the one primitive)

```
lock frame
if delivery row (sub, post, item) exists → return            -- idempotent
p := live placement (frame, item)
if p exists:
    insert delivery (sub, post, item, p, 'placed')
    if not p.held_directly and sub is active: p.feed_suspended = false; feed bump p
    return
moderated := frame.moderation and poster is not owner/admin of the frame
insert placement { frame, item, send_id = post.send_id, origin = 'feed', origin_feed_id = sub.feed_id,
                   held_directly = false, backfill = quiet, status = pending, pending_moderation = moderated,
                   received_at = quiet ? post.created_at : now() }
insert delivery (sub, post, item, placement, 'placed')
ensure rendition for (item.media, frame screen size)           -- the existing pipeline job; placement becomes
                                                                -- 'ready' and enters the change feed then
```

### 2.2 `unplace(sub, post_item)`

```
lock frame
d := delivery (sub, post, item); if none → return
delete d
p := d.placement (if still live)
if p.held_directly or another 'placed' delivery points at p:
    recompute p.feed_suspended; feed bump p if it changed; return
remove p (RemovePlacements: removed_at = now(), feed 'placement.remove')
```

### 2.3 Operations

| Operation | Algorithm |
|---|---|
| **New send with feeds** | In the send transaction: create the items, the send, and per feed one `feed_posts` row plus `feed_post_items`. After commit, enqueue `feed.fanout(post)`; the job calls `place(sub, item, quiet=false)` for every `active` subscription of the feed and every item. Direct frames of the same send are placed first, in the send transaction, so a frame that is both direct and subscribed ends up `origin = send` with a feed delivery row attached. |
| **Subscription activated** (approve, auto-approve, admin request, subscribe invite) | Set `activated_at = now()` if null, status `active`, enqueue `feed.reconcile(sub)`. |
| **`reconcile(sub)`** (activation, history change, resume) | `wanted` = live post items with `post.created_at >= activated_at`, plus history: none / the newest `history_count` live items before `activated_at` / all live items before it (capped at `max_feed_backfill_items`). For each wanted item without a delivery row: `place(sub, item, quiet = post.created_at < reconcile start)`. For each `placed` row whose item is not wanted (trim): `unplace`. `declined` rows are left alone in both directions. Processes newest first in batches of 200 items per transaction; the job re-enqueues itself until done, and restarts from scratch if `reconciled_version` moved meanwhile. |
| **History change** | Update `history`/`history_count`, bump `reconciled_version`, enqueue `reconcile`. Pending subscriptions just store it. |
| **Pause** | Status `paused`, `paused_at = now()`. For each `placed` placement of the sub: recompute `feed_suspended`; feed bump the ones that became suspended (they render as `placement.remove`). New posts skip paused subscriptions in `fanout`. |
| **Resume** | Status `active`; recompute `feed_suspended` and bump the ones that came back; then `reconcile(sub)`, which quietly places the posts made during the pause (they are after `activated_at`, created before the reconcile). |
| **Unsubscribe / remove subscription** | Status `removed`, `ended_at`, `removed_reason`; `unplace` every `placed` row (batched job), then delete the delivery rows (tombstones too). If `settings.slideshow.feed_id` is this feed, set it to null (a `settings` change). |
| **Unsend a post or item / admin removal** | Mark `feed_post_items.removed_at` (and `feed_posts.removed_at` when none are left); for every delivery row of those items: `unplace`. |
| **Sender's `DELETE /v1/sends/{id}`** | Existing path for direct placements, plus "unsend" of every post of that send. |
| **Member removed with posts / account deleted with items** | "Unsend" of every live post of that user in the feed (or all feeds). |
| **Frame purges, or moderation rejects, a feed placement** | Existing `RemovePlacements`, plus: every `placed` delivery row pointing at it becomes `declined` with `placement_id = null`. `held_directly` is irrelevant: the frame removed the photo. |
| **Frame hides/trashes/restores** | Unchanged; delivery rows stay `placed`. Trash purge after 30 days counts as a purge (→ `declined`). |
| **Frame removed** | Existing removal (placements removed, memberships deleted); subscriptions `removed` (`frame_removed`). |
| **Feed deleted** | `deleted_at`, all subscriptions `removed` (`feed_deleted`), then a job "unsends" every post and finally deletes the feed row (cascades). |
| **Feed renamed** | Feed bump every live placement with `origin_feed_id = feed` on each subscribed frame (re-renders the credit name). |
| **Direct forward/share hits an existing feed placement** | Set `held_directly = true`, `feed_suspended = false`, bump; still reported as `skipped: already_on_frame`. |

### 2.4 Idempotency and retries

- The delivery primary key `(subscription, post, item)` plus `placements_live_unique (frame, item)` make `place`
  safe to run twice: fanout and reconcile jobs can crash and rerun without duplicates.
- `unplace` is a no-op without a delivery row.
- Reconcile is a pure function of (subscription row, live posts, delivery rows), so a rerun converges. Jobs carry the
  subscription's `reconciled_version` and stop if it moved (the newer job takes over).
- `POST /v1/sends` keeps its `Idempotency-Key`; the post rows are created in the send's transaction, so a retried send
  never posts twice. Approve/reject/pause/resume are state transitions: repeating the same one returns the current
  subscription (`200`), conflicting ones `409 invalid_transition` or `409 already_decided`.

## 3. Change feed

- No new change types. Feed placements are `placement.upsert` / `placement.remove` like any other, with additional
  fields `origin`, `origin_feed_id`, `origin_feed_name`, `feed_ids` and `backfill` (see `Placement` in the contract).
- The renderer adds one rule to the existing ones ("pending moderation, failed or removed render as
  `placement.remove`"): **`feed_suspended` placements render as `placement.remove`** too. Resume bumps them, so they
  come back as `placement.upsert` (the frame re-downloads; media is content-addressed).
- `feed_ids` is the set of feeds whose `placed` delivery rows hold the placement for an active subscription. The frame
  uses it for feed mode, like `album_ids` for album mode.
- Settings: `slideshow.feed_id`, cleared to null by unsubscribe/removal/feed deletion and mutually exclusive with
  `slideshow.album_id` (setting one clears the other in the merged document).

## 4. Read paths

- **Receipts**: per post, join `feed_deliveries` (active and paused subscriptions) → `placements` → status, reaction,
  replies; `declined` rows count as removed. One query per post page, grouped by `post_id, subscription_id`.
- **UserCanSeeItem** gains: a member of a feed with a live post of the item.
- **Library** (`listPlacements`) gets an optional `feed_id` filter (frame members browsing "from Peeters kids").
- **Who gets reaction pushes**: unchanged (the item's sender), with `feed_id`/`post_id` added from the placement's
  delivery rows.
