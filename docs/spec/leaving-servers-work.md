# Leaving a family and families on different servers: work split

The plan for building `leaving.md` and `multi-server.md` with five parallel workstreams: **server-leaving**,
**server-federation**, **web**, **android-family** and **ios**. The contract is fixed: `docs/api/openapi.yaml` (tags
`leaving`, `remote`, `federation`, plus changes to `me`, `frames`, `feeds`, `library`, `subscriptions`, `chats`,
`social`, `operator` and `discovery`), `docs/api/README.md` §5.21, §5.22 and §6, and the schema in
`server/internal/db/migrations/00036_leaving.sql` and `00037_federation.sql`. Generated code
(`server/internal/api/gen`, `server/internal/api/response.gen.go`, `web/src/lib/api/schema.d.ts`) is up to date with
it. The precedent is `family-social-work.md`; the same rules apply, restated here with this work's names.

## Rules for everyone

1. **Contract changes are rare and explicit.** If something in the contract is wrong or missing, change
   `openapi.yaml` (and the README) in a commit of its own titled "Contract: …", run `cd server && make generate`
   and `cd web && npm run generate`, and commit the generated files with it. Additive only (new optional fields,
   new operations); never rename or remove. Pull first; if another workstream changed the contract meanwhile,
   regenerate after the rebase instead of merging generated files by hand.
2. **Schema changes** go in new migrations, one per change, named after the area, in the area's number range:
   **server-leaving 00040–00044** (`000NN_leaving_….sql`), **server-federation 00045–00049**
   (`000NN_federation_….sql`). Never edit 00036 or 00037 once others have pulled them. If a range runs out, take the
   next free number above 00049 when you commit.
3. **Own files.** Put your code in files named for your area (below). Edits to shared files are small hooks: one
   call, a field or two, added in a block of their own; never reformat or reorder a shared file.
4. **Pull before work and rebase before every commit** (`git pull --rebase`); commit on main in small steps; run the
   tests of the packages you touched plus `go build ./... && go vet ./...`.
5. **Write only your own tables** (lists below). Reading any table is fine. Where you need the other area's behaviour
   that isn't built yet, call its helper in `*_shared.go` (the signatures are fixed; the bodies are stubs until the
   owner fills them) or write the query against the schema in your own file; don't wait.
6. **Stubs.** Each server area replaces exactly one stub file, `server/internal/api/leaving_stubs.go` or
   `federation_stubs.go`, operation by operation (delete a stub when you implement it; delete the file when it is
   empty). Stubs answer `501` (`problem.FeatureDisabled` with status 501). `server/internal/api/stubs.go` holds the
   shared helper: whoever deletes the last stub file deletes it too.
7. **Discovery flags** turn on when the area is complete on the server: `leavingDiscovery` sets `leaving`,
   `federationDiscovery` sets `remote_families` and `federation.enabled` (both already called from `GetDiscovery`).
   Clients hide everything while the flag is false, so half-built servers stay invisible. `server_id`,
   `server_name` and `base_url` are filled by server-federation early (step 1 of its order), because the clients'
   multi-account work needs them regardless of `remote_families`.
8. **i18n.** Server texts (push titles and bodies `push.<key>.title|body`, problem details) go in the area's feature
   catalog: `server/internal/i18n/locales/leaving/<lang>.json` and `locales/federation/<lang>.json` (create them; all
   37 languages, the test checks it), never in the main `locales/<lang>.json`. No new problem codes: the contract
   reuses `forbidden`, `limit_reached`, `already_decided`, `invalid_transition`, `invite_invalid`, `invite_expired`,
   `upstream_unavailable`, `unauthorized` and `cannot_message`. Client strings: each client has its own catalogs;
   prefix new keys with the area (`leaving.`, `servers.`) so parallel additions don't collide.
9. **Websocket event names and push keys** are fixed by README §6.2 and §6.3; send exactly those. Each event and key
   has one owner (below); others never send it.
10. Never mention any assistant in commits, docs or code. Commit as the user.

## Shared touch points (already wired)

| What | Where | Status |
|---|---|---|
| Discovery hooks | `internal/api/me.go` calls `leavingDiscovery`, `federationDiscovery` | done, bodies in `*_shared.go` (`federationDiscovery` says federation off) |
| Worker jobs | `RunJob` (`internal/api/feedjobs.go`) also tries `runLeavingJob`, `runFederationJob` | done; job kinds are `leaving.*`, `federation.*` |
| Background loops | `internal/app/app.go` starts `RunLeaving`, `RunFederation` | done (they wait for shutdown until filled) |
| Hand-written routes | `/federation/v1/subscriptions` (POST), `…/{subscriptionId}` (DELETE), `…/{subscriptionId}/changes` (GET), `…/{subscriptionId}/activity` (POST), `/federation/v1/ping` (POST): excluded in `gen/cfg.yaml`, mounted in `router.go` outside `/v1` (no user or device auth) | done; bodies in `federation_stubs.go` (`writeNotImplemented`) |
| Account deletion choice | `DeleteMe` uses `accountDeletionTakesPhotos(req)` (`leaving_shared.go`): `photos`, else `remove_items`, else take | done; `leave` keeps photos in place until server-leaving builds the handover |
| Federation change log | triggers in 00037 on `feed_posts` (insert, `removed_at`), `feed_post_items` (`removed_at`), `feed_birthdays`, `comments` write `federation_changes` while a family has a live remote subscriber; circle posts never | done. Go code never writes post, item, birthday or comment rows of `federation_changes`; taking away and keep-request restores reach remote frames through them. |
| Comment frame entries | `Comment.frame.server_name` added to the anonymous struct in `social.go` | done (field only) |
| Test of the migrations | `internal/db/leaving_migrate_test.go` | done |

### Cross-area helpers (fixed signatures)

| Helper | File | Owner | Called by |
|---|---|---|---|
| `accountDeletionTakesPhotos(req)` | `leaving_shared.go` | server-leaving (works) | `DeleteMe` |
| `memoryFilterFor(ctx, q, userID) (memoryFilter, error)`, `memoryFilter.Hides(posterID, postID)` | `leaving_shared.go` | server-leaving | engagement code (On this day, monthly recap, suggestions, weekly summary): server-leaving adds the calls in the `engagement*.go` files, one line per place |
| `shareLiveScope(ctx, q, a, b) (bool, error)` | `leaving_shared.go` | server-leaving | chat code (`sendMessage`, conversation rendering): server-leaving adds the calls |
| `placementTakenAway(ctx, frameID, []gen.Placement)` | `leaving_shared.go` | server-leaving | library code (`listPlacements`, `updatePlacement`, `bulkUpdatePlacements`) |
| `leavingDiscovery`, `runLeavingJob`, `RunLeaving` | `leaving_shared.go` | server-leaving | wired |
| `federationDiscovery` | `federation_shared.go` | server-federation | wired |
| `remoteFrameCount(ctx, q, feedID) (int, error)` | `federation_shared.go` | server-federation | server-leaving (`TakeAwayResult.remote_frames`) |
| `remoteFeedSubscriptions(ctx, feedID, callerID, status)` | `federation_shared.go` | server-federation | `listFeedSubscriptions` (server-federation adds the call) |
| `remoteSubscriptionAction(ctx, callerID, sid, "approve" / "reject" / "delete")` | `federation_shared.go` | server-federation | `approveSubscription`, `rejectSubscription`, `removeSubscription`, `getSubscription` (server-federation adds the calls at the top) |
| `remoteFrameActivity(ctx, placementID, kind, reaction, reply)` | `federation_shared.go` | server-federation | `deviceSetReaction`, `deviceSendReply`, `deviceAck` (server-federation adds the calls) |
| `remoteItemCredits(ctx, []gen.Item)` | `federation_shared.go` | server-federation | item rendering (library, sends, timeline; server-federation adds the calls) |
| `remoteFrameBirthdays(ctx, q, frameID)` | `federation_shared.go` | server-federation | the frame `birthdays` change builder (server-federation adds the call) |
| `federationFeedEnded(ctx, tx, feedID, reason)` | `federation_shared.go` | server-federation | `deleteFeed` and `updateFeed` (`allow_remote_frames` off) |
| `federationFrameRemoved(ctx, tx, frameID)` | `federation_shared.go` | server-federation | `removeFrame` |
| `runFederationJob`, `RunFederation` | `federation_shared.go` | server-federation | wired |

Where the two server areas touch the same behaviour:

- **Taking away a family post with remote frames**: server-leaving sets `feed_posts.removed_at`/`removed_by`/
  `taking_id` (and `feed_post_items` for partial restores); the 00037 trigger logs `post.remove` / `post.upsert`;
  server-federation's changes endpoint renders them and pings. Server-leaving only calls `remoteFrameCount` for the
  result. Remote frames are not asked to keep (B has no keep flow); a restore on A re-sends the post.
- **Remote placements on B** are `origin: feed` with `origin_feed_id` null and `origin_remote_follow_id` set. Feed
  code that assumes `origin = 'feed'` implies a local feed must check `origin_feed_id IS NOT NULL` (server-federation
  checks `store/feedsync.go` and the change feed and adds the guards). Taking away never touches them (they have no
  local sender).
- **Account deletion on A**: handover and take only touch local items; items on B owned by remote senders are B's.

## server-leaving

Stub file: `server/internal/api/leaving_stubs.go`. Own files: `internal/api/leaving*.go`, `internal/api/keep*.go`,
`internal/api/memories*.go`, `internal/api/ownership*.go`, `internal/store/leaving*.go`, `internal/store/keep*.go`,
`internal/i18n/locales/leaving/`.

- **Operations** (tag `leaving`): `getLeavePreview`, `listFormerPlaces`, `takeAwayFromFeed`, `takeAwayFromFrame`,
  `listFrameTakenAway`, `listFeedTakenAway`, `createKeepRequest`, `listMyKeepRequests`, `listFrameKeepRequests`,
  `listFeedKeepRequests`, `getKeepRequest`, `answerKeepRequest`, `getMemoryPreferences`, `updateMemoryPreferences`;
  (tag `operator`) `operatorSearchOwnership`, `operatorTransferFrameOwner`, `operatorTransferFeedOwner`,
  `operatorListOwnershipLog`.
- **Changes to existing operations**: `leaveFeed` (`LeaveFeedRequest`, 200 with `TakeAwayResult` when `photos` is
  sent, `leave_frame_ids`, comments to tombstones), `leaveFrame` (`LeaveFrameRequest`), `deleteMe` (`take` for every
  scope, `leave` = the handover with `items.former_sender_name`, `ownership_log`), `listPlacements`
  (`visibility=taken_away`, `taking_id`, `counts.taken_away`, `Placement.taken_away` via `placementTakenAway`; `purgePlacement` deletes a taken-away placement early),
  the library and trash queries (taken-away placements are `removed_at` and stay out everywhere else), item
  rendering (`Item.former_sender_name`), the engagement memories (`memoryFilterFor` in On this day, the recap as
  rendered per user, suggestions, the weekly summary), chats (`shareLiveScope`: `Conversation.read_only`,
  `read_only_reason: no_longer_contacts`, `sendMessage` → `403 cannot_message`), the change feed (taken-away =
  `placement.remove`, restored = `placement.upsert` with `backfill: true`).
- **Tables (write)**: `takings`, `taking_items`, `keep_requests`, `keep_request_items`, `ownership_log`,
  `memory_hidden_people`, `memory_hidden_posts`; columns `placements.taken_away_at`, `taken_away_until`,
  `taken_away_by`, `taking_id` (and `removed_at` for takings and restores), `feed_posts.taking_id` (and
  `removed_at`/`removed_by` for takings), `feed_post_items.taking_id` (and `removed_at`), `items.former_sender_name`,
  `items.handed_over_at`, `items.sender_user_id` (handover only); membership and ownership rows for operator transfers
  (`memberships`, `feed_members`).
- **Websocket events**: `placement.updated`, `keep_request.updated`; `post.removed` for posts it takes away (the
  social event, same payload), `membership.updated` / `feed.membership_updated` after operator transfers.
- **Push keys**: `photos_taken_away`, `keep_request`, `keep_answered`, `ownership_changed`.
- **Helpers to fill**: `memoryFilterFor`, `shareLiveScope`, `placementTakenAway`, `leavingDiscovery` (flag
  `leaving`), `runLeavingJob`, `RunLeaving` (the 30-day purge, keep request expiry, ownership log retention of 2
  years).
- **Rules to keep**: takings are idempotent; one keep request per scope and taking (unique indexes); blocked askers
  refused; only the leaver's own items; admin removals never take photos away; old clients get keep.

## server-federation

Stub file: `server/internal/api/federation_stubs.go`. Own files: `internal/api/federation*.go`,
`internal/api/remote*.go`, `internal/store/federation*.go`, `internal/store/remote*.go`, a new package
`internal/federation/` (RFC 9421 signing and verifying with Ed25519, `Content-Digest`, well-known fetch and cache,
key generation and rotation, the HTTP client through `internal/netguard`), `internal/i18n/locales/federation/`.

- **Operations** (tag `remote`): `previewRemoteInvite`, `listRemoteSubscriptions`, `createRemoteSubscription`,
  `updateRemoteSubscription`, `deleteRemoteSubscription`, `listFeedBlockedServers`, `blockFeedServer`,
  `unblockFeedServer`; (tag `operator`) `operatorListBlockedServers`, `operatorBlockServer`,
  `operatorUnblockServer`, `operatorListRemoteServers`; (tag `federation`, hand-written in `federation*.go`)
  `federationSubscribe`, `federationUnsubscribe`, `federationChanges`, `federationActivity`, `federationPing`.
- **Changes to existing operations**: `getDiscovery` (`server_id` from `server_identity`, `server_name`, `base_url`,
  `api_version`, `federation`, `remote_families`), `createFeed`/`getFeed`/`updateFeed` (`allow_remote_frames`;
  turning it off calls `federationFeedEnded`), `createFeedInvite`/`listFeedInvites` (`allow_remote`, only on
  `subscribe` invites of families with `allow_remote_frames`), `listFeedSubscriptions` and approve/reject/delete
  (remote rows, `Subscription.remote`), `deleteFeed`, `removeFrame`, the device reaction, reply and ack handlers
  (`remoteFrameActivity`), placements and items rendering (`Placement.origin_remote`, `Item.remote_sender`; the
  device change feed sends `origin: feed` with `origin_feed_name` and no `origin_remote`), the frame birthdays change
  (`remoteFrameBirthdays`), social rendering of remote frame entries and reactions (`Comment.frame.server_name`,
  `FrameReactionRef.server_name`, receipts counting `remote_frame_seen`), `internal/notify` (every payload gets
  `server` = the public base URL).
- **Config** (in `internal/config`, a block of its own, documented with the other server variables):
  `KINFRAME_FEDERATION` (`on` default, `off`), `KINFRAME_FEDERATION_ALLOW` (comma-separated hosts),
  `KINFRAME_SERVER_LABEL` (discovery `server_name`, default the public host name).
- **Tables (write)**: everything created in 00037: `server_identity`, `federation_keys`, `remote_servers`,
  `blocked_servers`, `remote_subscribers`, `federation_changes` (`subscription` rows; the other kinds are the
  triggers'), `remote_frame_reactions`, `remote_frame_seen`, `remote_activity_seen`, `remote_follows`,
  `remote_senders`, `remote_items`, `remote_activity_outbox`; columns `feeds.allow_remote_frames`,
  `feed_invites.allow_remote`, `comments.remote_subscriber_id`, `comments.frame_server_name` (frame entries of remote
  frames: `kind = 'frame'`, `frame_id` null), `items.remote_sender_id`, `placements.origin_remote_follow_id`; on B the
  copied `items`, `media`, renditions and `placements` rows (through the existing store functions); the 00037
  triggers (via new migrations).
- **Websocket events**: `remote_subscription.updated` (B); `subscription.request` / `subscription.updated` for remote
  rows on A (same payloads as local ones, with `subscription.remote`).
- **Push keys**: none new; reuses `subscription_request`, `subscription_removed`, `reaction`, `reply` for remote
  subscriptions and remote frame activity, and adds `server` to every payload.
- **Helpers to fill**: everything in `federation_shared.go`.
- **Rules to keep**: never share circle posts, chats, member lists or emails; only signed requests; outgoing calls
  through netguard with back-off; the cursor is stored only after a page is applied; pings never carry data; master
  URLs signed for 1 hour; downloads verified (size, type, sha-256); B's operator note in the confirmation.

Suggested order: (1) `server_identity`, discovery `server_id`/`server_name`/`base_url`, the push `server` field (the
clients' multi-account work needs them); (2) keys, the well-known `federation` object, signing and verifying with
tests against RFC 9421's Ed25519 example; (3) subscribe, changes, the pull worker, placements on B; (4) ping,
activity, removals, blocks; (5) the flag.

## web (`web/`)

- Types: `npm run generate` (already run; rerun after contract commits). Client: `openapi-fetch`.
- Leaving: the leave sheets for families and frames (photos keep/take, comments, "Leave this frame too" from
  `getLeavePreview`), the result line, "Take my photos away from Janssens" in the sent history
  (`listFormerPlaces`, `takeAwayFromFeed`/`takeAwayFromFrame`), account deletion with take/leave.
- Keep requests: the frame trash's "Taken away by Tom" groups (`listFrameTakenAway`,
  `listPlacements?visibility=taken_away`), "Ask Tom to keep them" with a picker, family-side groups for owners and
  admins, the leaver's requests in their history with allow all / some / decline (`getKeepRequest`,
  `answerKeepRequest`), `keep_request.updated` and `placement.updated`.
- Memories: Settings → Memories → "Leave people out" and "Don't show this memory again" on memory cards.
- Chats: the read-only line "You can no longer message Lies" (`read_only_reason: no_longer_contacts`).
- Operator: ownership transfer (search, pick the new owner, reason, remove the previous owner) and the log; blocked
  servers and remote servers lists.
- Remote families: frame settings → Families → "Add a family from another server" (preview, history, confirmation
  naming both servers), the list with status and on-end choice, unsubscribe; family settings → "Allow frames on other
  servers", invites with "Frames on other servers may use this link", remote entries in the subscriptions list
  (server name, approve/reject/remove), blocked servers. Remote frame entries in threads ("Oma's frame
  (kinframe.appmire.be)").
- Multi-server: the hint "You also use kinframe on other servers? The apps show them all together." and the account
  switcher's links to other servers (decision A: the web stays single-server; parity "differs").
- Gate on discovery `leaving` and `remote_families`. Strings in `web/src/lib/i18n/en.ts` and the catalogs, keys
  prefixed `leaving.` and `servers.`; the web is the source catalog the apps convert from.
- Update the web cells in `docs/client-parity.md` (§ Leaving and other servers) as features land.

## android-family (`android/family`, shared `android/core`)

- Regenerate or extend the models in `:core` from `openapi.yaml`.
- The same leaving, keep request, memories, read-only chat and remote family screens and gates as the web (operator
  screens only where the app already has operator screens).
- **Multi-account**: an account list (Settings → Accounts → "Add a server", primary account, sign out of one), one
  API client, websocket, push registration, cache and discovery per account; merged Home (timelines interleaved by
  time, server chip when there are several accounts), Chats (merged by last message, badge = sum), frames, families,
  history; the send flow and share sheet with recipients grouped per server and one upload per server (media
  prepared once, progress and errors per server); push routing by the payload's `server` (else the endpoint's
  account); invite and join links of any kinframe server (fetch `/.well-known/kinframe`, add the server after sign-in,
  "This family is on kinframe.janssens.be. Sign in there to join."); dedupe accounts by `server_id`.
- Strings converted from the web catalog (`strings_leaving.xml`, `strings_servers.xml`).
- Update the Android cells in `docs/client-parity.md`.

## ios (`ios/`: app, `KinframeKit`, `KinframeShare`, Mac Catalyst)

- Regenerate the Swift client in `KinframeKit` from `openapi.yaml`.
- The same screens, gates and multi-account work as android-family; the share extension lists recipients of all
  accounts grouped per server (accounts shared through the app group); APNs routing by `server`; universal links of
  other servers' invites open the add-server flow.
- Strings in `Localizable.xcstrings`, converted from the web catalog.
- Update the iOS cells in `docs/client-parity.md`.

## Not in this work

- The frame apps: remote placements look like feed placements and taken-away ones arrive as `placement.remove`, so
  nothing changes on frames.
- A family role that sees only one circle (`leaving.md` decision B); members, threads or chats spanning servers, and
  moving a family between servers (`multi-server.md` §7).

## Order of work (suggested, not blocking)

1. server-federation: identity and discovery fields and the push `server` field first; server-leaving: takings and
   the leave bodies first (clients' leave sheets), then keep requests, then the handover and memories.
2. Clients build against the contract with the stubs answering 501 and the discovery flags false; multi-account needs
   only `server_id`, `server_name`, `base_url` and `server`, and can start at once against two local servers.
