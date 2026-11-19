# Family social: work split

The plan for building `family-social.md` (all three phases at once) with seven parallel workstreams. The contract
is fixed: `docs/api/openapi.yaml` (tags `social`, `circles`, `chats`, `safety`, `engagement`, plus changes to
`feeds`, `sends`, `device`, `me`), `docs/api/README.md` §5.18–§5.20 and §6, and the schema in
`server/internal/db/migrations/00022_social.sql`, `00023_chat.sql`, `00024_engagement.sql`. Generated code
(`server/internal/api/gen`, `server/internal/api/response.gen.go`, `web/src/lib/api/schema.d.ts`) is up to date with
it.

## Rules for everyone

1. **Contract changes are rare and explicit.** If something in the contract is wrong or missing, change
   `openapi.yaml` (and the README) in a commit of its own titled "Contract: …", run `cd server && make generate`
   and `cd web && npm run generate`, and commit the generated files with it. Additive only (new optional fields,
   new operations); never rename or remove. Pull first; if another workstream changed the contract meanwhile,
   regenerate after the rebase instead of merging generated files by hand.
2. **Schema changes** go in new migrations (00025 onward, one per change, named after the area:
   `000NN_social_….sql`). Never edit 00022–00024 once others have pulled them. Take the next free number when you
   commit; if someone took it first, renumber yours.
3. **Own files.** Put your code in files named for your area (below). Edits to shared files are small hooks: one
   call, a field or two, added in a block of their own; never reformat or reorder a shared file.
4. **Pull before work and rebase before every commit** (`git pull --rebase`); commit on main in small steps; run the
   tests of the packages you touched plus `go build ./... && go vet ./...`.
5. **Write only your own tables** (lists below). Reading any table is fine. Where you need another area's behaviour
   that isn't built yet, call its helper in `*_shared.go` (the signatures are fixed; the bodies are stubs until the
   owner fills them) or write the query against the schema in your own file; don't wait.
6. **Stubs.** Each server area replaces exactly one stub file, `server/internal/api/<area>_stubs.go`, operation by
   operation (delete a stub when you implement it; delete the file when it is empty). Stubs answer
   `501` (`problem.FeatureDisabled` with status 501). `server/internal/api/stubs.go` holds the shared helper: whoever
   deletes the last stub file deletes it too.
7. **Discovery flags** turn on when the area is complete on the server: `socialDiscovery`, `chatDiscovery`,
   `engagementDiscovery` (already called from `GetDiscovery`) set the flag and the area's limits. Clients hide
   everything while the flag is false, so half-built servers stay invisible.
8. **i18n.** Server texts (push titles and bodies `push.<key>.title|body`, problem details, prompts) go in the
   area's feature catalog `server/internal/i18n/locales/<area>/<lang>.json` (`social`, `chat`, `engagement`; all 37
   languages, the test checks it), never in the main `locales/<lang>.json`. Problem titles for the new codes are
   already there (`comments_closed` in `social`, `cannot_message` and `audio_too_long` in `chat`). Client strings: each
   client has its own catalogs; prefix new keys with the area (`social.`, `chat.`, `engagement.`) so parallel
   additions don't collide.
9. **Websocket event names and push keys** are fixed by README §6.2 and §6.3; send exactly those. Each event and key
   has one owner (below); others never send it.
10. Never mention any assistant in commits, docs or code. Commit as the user.

## Shared touch points (already wired)

| What | Where | Status |
|---|---|---|
| Discovery hooks | `internal/api/me.go` calls `socialDiscovery`, `chatDiscovery`, `engagementDiscovery` | done, bodies in `*_shared.go` |
| Worker jobs | `RunJob` (`internal/api/feedjobs.go`) first tries `runSocialJob`, `runChatJob`, `runEngagementJob` | done; job kinds are `social.*`, `chat.*`, `engagement.*` |
| Background loops | `internal/app/app.go` starts `RunSocial`, `RunChat`, `RunEngagement` | done |
| Hand-written route | `PUT /v1/conversations/{conversationId}/photo` → `putConversationPhoto` (excluded in `gen/cfg.yaml`) | mounted in `router.go`; body in `chat_stubs.go` |
| Push actions | `notify.Push.Actions` → payload `actions` | done; APNs category / FCM mapping by server-engagement |
| Problem codes | `problem.CannotMessage`, `CommentsClosed`, `AudioTooLong` (`internal/problem/social.go`) | done |
| Family and circle chats | database triggers in 00023 (`chat_feed_conversation`, `chat_circle_conversation`, `chat_feed_member_sync`, `chat_circle_member_sync`) | done: creating a feed or circle creates its chat, feed and circle membership changes are mirrored, deleting archives. Go code never writes family/circle conversations or their members. |
| `feed_engagement` rows | trigger `engagement_feed_created` in 00024 | done |
| Test of the migrations | `internal/db/social_migrate_test.go` | done |

### Cross-area helpers (fixed signatures in `server/internal/api/*_shared.go`)

| Helper | Owner | Called by |
|---|---|---|
| `postAccessFor(ctx, userID, postID) (postAccess, error)` | server-social | chat (shared posts, reports, `getPerson`), engagement (On this day, prompts, recaps) |
| `postSystemMessage(ctx, conversationID, gen.SystemMessage) (uuid.UUID, error)` | server-chat | engagement (`streak_milestone`, `monthly_recap`, `prompt`) |
| `familyConversationID(ctx, feedID)` | server-chat (works already) | engagement, social |
| `notifyBadge(ctx, userIDs...)` | server-chat | social (thread read/unread changes), chat |
| `afterPersonalSend(ctx, sendID)` | server-engagement | social (after `createSend`/`forwardSend` made placements and posts), chat (`putMessageOnFrame`), and the existing email and Telegram send paths (whoever touches them adds the call; server-engagement checks they're all there) |
| `frameFreshness(ctx, []gen.FrameSummary)`, `feedFreshness(ctx, []gen.FeedSummary)` | server-engagement | server-engagement adds the calls in `listFrames`/`getFrame` and `listMyFeeds`/`getFeed` |

Circle conversations: created by the trigger `chat_circle_conversation` when server-social inserts a `circles` row,
archived when it sets `circles.deleted_at`; members follow `circle_members` through `chat_circle_member_sync`. Social
finds a circle's chat with `SELECT id FROM conversations WHERE kind = 'circle' AND circle_id = $1`. Server-chat owns
the triggers (a change to them is a new migration by server-chat).

## server-social

Stub file: `server/internal/api/social_stubs.go`. Own files: `internal/api/social*.go`, `internal/api/circles*.go`,
`internal/store/social*.go`, `internal/store/circles*.go`, `internal/i18n/locales/social/`.

- **Operations** (tag `social`): `getTimeline`, `getPost`, `listComments`, `createComment`, `updateComment`,
  `deleteComment`, `setThreadState`, `markThreadRead`, `setThreadMute`, `listReactions`, `setReaction` (all target
  kinds, including `message`: check conversation membership against the chat tables).
- **Operations** (tag `circles`): `listCircles`, `createCircle`, `getCircle`, `updateCircle`, `deleteCircle`,
  `addCircleMembers`, `updateCircleMember`, `removeCircleMember`.
- **Changes to existing operations**: `createFeed`, `getFeed`, `listMyFeeds`, `updateFeed` (`members_see_receipts`,
  `new_members_can_post`, `members_can_create_circles`, `circle_count`, `mute`; the split of receipts vs frames'
  reactions); `updateFeedMember` (role `member`); `acceptFeedInvite`, `decideFeedJoinRequest` (joiners get
  `member` when `new_members_can_post` is off); `updateFeedPreferences` (`mute`); `listFeedPosts`, `getFeedPost`
  (circle visibility, `circle_*`, `thread`, receipts by `members_see_receipts`, `reactions` by
  `members_see_reactions`); `getFeedPostReceipts` (`members_see_receipts`); `createSend`, `forwardSend` (`member` →
  403, `circle_ids`, making `frame_posts`); `getItemMedia` (circle posts' items only for the circle); `unsend` and
  placement removal (set `frame_posts.removed_at` when a frame post's last placement goes); `deviceSetReaction`,
  `deviceSendReply` (frame entries); `store/feedsync.go` (circle reach when placing and backfilling).
- **Tables (write)**: `frame_posts`, `threads`, `comments`, `comment_mentions`, `thread_reads`, `reactions`,
  `push_bundles` (keys `comment:*`, `comment_reply:*`, `reaction:*`, `reaction_digest`), `circles`,
  `circle_members`, `circle_frames`; columns `feeds.members_see_receipts`, `feeds.new_members_can_post`,
  `feeds.members_can_create_circles`, `feed_members.role` (`member`), `feed_members.mute`, `feed_posts.circle_id`.
- **Websocket events**: `post.updated`, `post.removed`, `comment.created`, `comment.updated`, `comment.deleted`,
  `thread.read`, `reaction.updated`, `circle.updated`, `circle.removed`.
- **Push keys**: `comment`, `comment_reply`, `mention` (comments), `reaction_person`, `reaction_digest`.
- **Helpers to fill**: `postAccessFor`, `socialDiscovery` (flag `social`; limits `max_comment_chars` 2000,
  `max_mentions` 20, `max_circles_per_feed` 20), `runSocialJob`, `RunSocial` (comment bundles, the 19:00 reaction
  digest).
- **Body encryption**: comments' `body`/`body_enc` (README §5.18 "Storage") with `s.bodies` (`server.go`, shared
  with chat): when it is non-nil store `body_enc = s.bodies.Seal(text)` and `body = NULL`, else `body`; read both.

## server-chat

Stub file: `server/internal/api/chat_stubs.go`. Own files: `internal/api/chat*.go`, `internal/api/conversations*.go`,
`internal/api/messages*.go`, `internal/api/safety*.go`, `internal/api/linkpreview*.go`, `internal/store/chat*.go`,
`internal/store/safety*.go`, `internal/linkpreview/` (new package), `internal/i18n/locales/chat/`.

- **Operations** (tag `chats`): `listConversations`, `createConversation`, `getConversation`,
  `updateConversation`, `putConversationPhoto` (hand-written), `deleteConversationPhoto`, `addConversationMembers`,
  `updateConversationMember`, `removeConversationMember`, `updateConversationPreferences`, `listMessages`,
  `sendMessage`, `getMessage`, `updateMessage`, `deleteMessage`, `putMessageOnFrame`, `markConversationRead`,
  `sendTyping`, `listContacts`, `getPerson`, `getBadge`, `createLinkPreview`.
- **Operations** (tag `safety`): `listBlocks`, `blockUser`, `unblockUser`, `createReport`, `listFeedReports`,
  `resolveReport`; (tag `operator`) `listOperatorReports`.
- **Changes to existing operations**: `getMe`, `updateMe` (`show_read_receipts`); `getPerson.milestones` reads
  `user_milestones WHERE shown` itself (engagement's table, read only); `getItemMedia` (items of
  conversations for their members); `listMyFeeds`/`getFeed` (`conversation_id`); the media pipeline in
  `internal/worker` and `internal/media` for `audio` (AAC-LC mono 48 kHz 64 kbit/s MP4 master, waveform, 300 s
  limit, README §5.19); the account export (own messages and chat photos) when it exists.
- **Tables (write)**: `conversations`, `conversation_members` (direct and group only; family/circle rows are the
  triggers'), `messages`, `message_items`, `message_mentions`, `message_sends`, `link_previews`, `blocks`, `reports`,
  `push_bundles` (keys `conversation:*`); columns `items.conversation_id`, `messages.voice_item_id`, `media.kind` (`audio`), `media.waveform`,
  `users.show_read_receipts`; the chat triggers (via new migrations).
- **Websocket events**: `conversation.updated`, `conversation.removed`, `message.created`, `message.updated`,
  `read.updated`, `typing`, `badge.updated`, `report.created`.
- **Push keys**: `message`, `mention` (messages), `conversation_added`, `report`.
- **Family and circle membership sync**: done by the triggers; server-chat owns them and sends
  `conversation.updated`/`conversation.removed` where code changes membership of groups. For family/circle chats
  clients refresh on `feed.membership_updated`, `circle.updated` and `circle.removed` (README §5.18).
- **Helpers to fill**: `postSystemMessage`, `notifyBadge`, `chatDiscovery` (flags `chats`, `voice_messages` when
  ffmpeg is there, `link_previews` with `KINFRAME_LINK_PREVIEWS=1`; limits `max_message_chars` 4000,
  `max_message_items` 30, `max_group_members` 100, `max_voice_seconds` 300, `message_edit_hours` 24),
  `runChatJob` (link preview fetches, `chat.link_preview`), `RunChat`.
- **Config**: `KINFRAME_LINK_PREVIEWS` in `internal/config` (a block of its own), documented with the other server
  variables. Message bodies and report excerpts are sealed with `s.bodies` like comments (server-social above).

## server-engagement

Stub file: `server/internal/api/engagement_stubs.go`. Own files: `internal/api/engagement*.go`,
`internal/api/reminders*.go`, `internal/api/streaks*.go`, `internal/api/prompts*.go`, `internal/store/engagement*.go`,
`internal/i18n/locales/engagement/` (create it: push texts, `prompt.<key>` titles in 37 languages).

- **Operations** (tag `engagement`): `getFeedEngagement`, `updateFeedEngagement`, `getFeedStreak`,
  `listFeedPrompts`, `setFeedPrompt`, `resetFeedPrompt`, `listPromptLibrary`, `tagPostPrompt`, `getOnThisDay`,
  `getWeeklySummary`, `getFeedRecap`, `listMyMilestones`, `updateMyMilestone`, `getReminderPreferences`,
  `updateReminderPreferences`, `getPhotoRequest`, `listFramePhotoRequests`; (tag `device`) `deviceRequestPhotos`.
- **Changes to existing operations**: `listFrames`, `getFrame` (`freshness`, `photo_request`), `listMyFeeds`,
  `getFeed` (`freshness`, `streak`), `createSend` (`prompt_id`), the change feed (`internal/api/device.go`,
  `internal/store`): change types `comments`, `photo_request`, `prompts` and `Placement.prompt_id`;
  `internal/settings` defaults for `settings.social` (`show_comments` false, `ask_for_photos` true, `prompt_cards`
  true, `new_photos_from` true); `notify` APNs category `kinframe.reminder` and FCM `actions` for `Push.Actions`;
  the birthday morning code is untouched (birthday reminders are a separate kind).
- **Tables (write)**: `frame_freshness`, `feed_freshness`, `photo_requests`, `reminder_preferences`, `nudges`,
  `feed_engagement`, `feed_streaks`, `feed_streak_weeks`, `feed_streak_helpers`, `feed_prompts`,
  `feed_posts.prompt_id`, `weekly_summaries`, `feed_recaps`, `user_milestones`, `frame_comment_cards`,
  `frame_prompt_cards`; change feed rows of kinds `comments`, `photo_request`, `prompts`.
- **Websocket events**: `photo_request.created`, `photo_request.updated`, `streak.updated`, `prompt.updated`,
  `milestone.achieved`.
- **Push keys**: `frame_request`, `reminder_quiet`, `reminder_streak`, `reminder_birthday`, `weekly_summary` (with
  `actions` on the four reminders).
- **Helpers to fill**: `afterPersonalSend`, `frameFreshness`, `feedFreshness`, `engagementDiscovery` (flags
  `engagement`, `email_digest` when outgoing mail is configured), `runEngagementJob`, `RunEngagement` (the scheduler:
  quiet frames, streak close and reminder, birthday reminders, weekly summaries, monthly recaps, prompt rotation and
  announcement, request expiry, nudge outcomes, the email digest).

## web (`web/`)

- Types: `npm run generate` (already run; rerun after contract commits). Client: `openapi-fetch`.
- Screens: Home (timeline, strip with freshness and photo requests, On this day, prompt card), post detail with
  thread, Chats (list, conversation, new chat/group, group settings, voice messages with `MediaRecorder`: Opus in
  WebM on Chrome and Firefox, AAC in MP4 on Safari), family settings (roles incl. `member`, the new settings,
  circles, engagement settings, streak, prompts, reports), frame settings → *Show comments*, *Ask for new photos*,
  *Prompt cards*, *New photos from…*, Settings (reminders, email digest, read receipts, blocked people), operator
  reports. Rename "Feeds" to "Families" in the UI (decision A).
- Websocket: every event in README §6.2 rows added for family social; badge in the tab title and favicon.
- Push: WebPush payload `tag` and `actions` (`snooze_month`, `fewer_reminders` call `updateReminderPreferences`).
- Strings: `web/src/lib/i18n/en.ts` and the catalogs, keys prefixed `social.`, `chat.`, `engagement.`; the web is the
  source catalog the apps convert from (see `docs/client-parity.md`).
- Gate everything on discovery `social`, `chats`, `engagement`, `voice_messages`, `link_previews`, `email_digest`.
- Update the web cells in `docs/client-parity.md` (§ Family social) as features land.

## android-family (`android/family`, shared `android/core`)

- Regenerate the Kotlin client in `:core` from `openapi.yaml` (the module's openapi-generator task).
- The same screens and gates as the web; voice messages with `MediaRecorder` (AAC in MPEG-4, mono); push via
  UnifiedPush/FCM with notification `tag`s and the reminder actions; the app badge (`getBadge`, `badge.updated`).
- Strings converted from the web catalog (`strings_social.xml`, `strings_chat.xml`, `strings_engagement.xml`).
- Update the Android cells in `docs/client-parity.md`.

## ios (`ios/`: app, `KinframeKit`, `KinframeShare`, Mac Catalyst)

- Regenerate the Swift client in `KinframeKit` (swift-openapi-generator) from `openapi.yaml`.
- The same screens and gates; voice messages with `AVAudioRecorder` (AAC, `.m4a`, mono); notification categories
  `kinframe.reminder` with the two actions, `apns-collapse-id`/thread ids from `tag`; app badge from `Badge.total`.
  Share extension: "Share in chat" target optional (not required for parity).
- Strings in `Localizable.xcstrings`, converted from the web catalog.
- Update the iOS cells in `docs/client-parity.md`.

## frame (`android/frame`, and the browser frame `/tv/frame` in `web/`)

- **Owns** on the frame: the "Ask for new photos" quick-menu button (`deviceRequestPhotos`; shown while
  `settings.social.ask_for_photos`; "Asked today 💛" until `next_allowed_at`), the `photo_request` change, "New photos
  from Ben" on the next wake (`settings.social.new_photos_from`, from new non-backfill placements' senders), the
  `comments` change and the comment card (`settings.social.show_comments`: 8 s, once per photo per day, two lines,
  plain text on the overlay layer, no avatars), the `prompts` change and title cards (`settings.social.prompt_cards`,
  `Placement.prompt_id`), heartbeat `features: ["comments", "photo_requests", "prompts"]`.
- Strings in the frame app's catalogs (all frame languages), keys prefixed `social_`.
- Weak-hardware budget: no new decoding paths; everything on the existing overlay layer.
- Update the Frame cells in `docs/client-parity.md`.

## Order of work (suggested, not blocking)

1. server-social: `postAccessFor`, frame posts, threads and reactions first (chat and engagement call
   `postAccessFor`); server-chat: conversations and messages, then `postSystemMessage`; server-engagement: freshness
   and photo requests (phase 1), then the scheduler.
2. Clients build against the contract with the stubs answering 501 and the discovery flags false; they can test
   against a local server as soon as an area's flag turns on.
