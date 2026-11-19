# Families on different servers

Status: **approved, being built** (2026-11-19). Extends `product-spec.md` (decision 3: one server, several families),
`feeds.md` (subscriptions) and `family-social.md`.

## 1. Summary

kinframe is self-hostable, so families end up on different servers: Lies's family runs its own server A, Tom's
family uses the hosted server B. Tom belongs to both families, and Oma's frame (on B) should show the grandchildren's
photos that Lies's family posts on A.

Two pieces, in this order:

1. **One app, several servers.** The family apps sign in to more than one server at once and merge Home, Chats,
   frames, families and the send flow, with a small server label where it matters. No protocol between servers: each
   server only knows its own accounts. Joining a family on another server is opening its invite link: the app adds
   that server and signs in (one tap with Google or Apple).
2. **Frames following a family on another server.** A frame on server B subscribes to a family on server A, with
   consent of both sides (as for feeds on one server). Server B copies the family's posts from A and places them on
   the frame; the frame's reactions and quick replies go back to A's threads. A narrow, read-only, signed link
   between two servers; comments, chats and membership stay on their own server.

Full federation (members, threads and chats spanning servers) is out of scope (§7).

## 2. One app, several servers

### 2.1 Accounts

- The apps keep a list of **accounts**: `{server base URL, discovery, Logto session, user}`. One is *primary* (opens
  first, receives shares by default). Add one from Settings → Accounts → "Add a server", or by opening an invite or
  join link of a server the app isn't signed in to ("This family is on kinframe.janssens.be. Sign in there to join.").
- Each account has its own websocket, push registration (`POST /v1/me/push-endpoints` per server), caches and
  discovery flags. Signing out of one leaves the others.
- Same person, several accounts: nothing links them on the servers. The app shows one profile per account in
  Settings; in merged lists the user's own items show "You".

### 2.2 Merged views

- **Home**: the timelines of all accounts merged by time (each page from each server, interleaved), with the family
  name and, when the user has more than one account, a small server chip (the server's name from discovery, e.g.
  "Janssens"). Frames/families strip: all of them, grouped by server only when the same name appears twice.
- **Chats**: all conversations merged by last message; badge = sum. A chat lives on one server; new chats pick the
  account by the contact's server (contacts are per server).
- **Frames, families, history, settings of a frame/family**: merged lists; every detail screen talks to its own
  server.
- **Send flow and share sheet**: recipients from all accounts, grouped per server. A send to targets on two servers
  is two uploads, one per server (the app prepares the media once); progress and errors per server.
- **Notifications**: push payloads carry `server` (the server's base URL, new field) so a tap opens the right
  account; clients without it fall back to the account the push endpoint belongs to.
- **Web**: the web app is served by one server and signs in to that server only. It shows a hint "You also use
  kinframe on other servers? The apps show them all together." and the account switcher lists other servers as links
  (their own web app). Parity: "differs".

### 2.3 Server side

- Discovery gains `server_name` (operator-set, default the host name) and `server_id` (stable random id) so apps can
  label and deduplicate accounts.
- Push payloads gain `server` (base URL).
- Invite and join links already carry the server's host; the apps recognise links of any kinframe server (the
  `/.well-known/kinframe` document, §3.2) instead of only their own.

## 3. Frames following a family on another server

### 3.1 Flow

1. Anna (admin of "Janssens" on server A) creates a **subscribe invite** for frames, as today, and turns on
   "Frames on other servers may use this link" (off by default). The link is
   `https://kinframe.janssens.be/fi/<code>`.
2. Tom manages Oma's frame on server B. He opens the link in the app (signed in to B, not to A) or pastes it in the
   frame's settings → Families → "Add a family from another server".
3. Server B fetches `https://kinframe.janssens.be/.well-known/kinframe`, checks the code with A
   (`POST /federation/v1/subscriptions` signed by B, §3.2) and shows Tom: "Janssens (on kinframe.janssens.be) — 3
   frames. Photos from this family will be copied to this server (kinframe.appmire.be) and shown on Oma's frame."
   with the history option (only new / last 50 / all).
4. A records a **remote subscription** (frame name "Oma's frame", server B's id and name). With a pre-approved invite
   it is active; otherwise it waits for Anna like any subscription request ("Tom wants to show Janssens on Oma's frame
   (kinframe.appmire.be)"). A's admins see remote frames in the subscriptions list with the server name and can
   remove them any time.
5. B pulls the family's posts and places them on Oma's frame as placements with `origin: remote_feed`, credited
   "Lies · Janssens". The frame needs nothing new.

### 3.2 Server identity and requests

- Each server has an Ed25519 key pair (generated at first start, stored with the server's secrets) and publishes
  `/.well-known/kinframe`: `{server_id, server_name, base_url, api_version, federation: {enabled, public_key,
  endpoints}}`.
- Server-to-server requests use HTTP Message Signatures (RFC 9421) with that key over method, path, `content-digest`,
  date and the `kinframe-server` header (the caller's base URL). The receiver fetches and caches the caller's
  well-known document (refreshed daily and on signature failure; key rotation keeps the old key valid for 7 days).
- Federation is on by default and can be turned off by the operator (`KINFRAME_FEDERATION=off`) or limited to an
  allow list of hosts (`KINFRAME_FEDERATION_ALLOW`). Outgoing requests go through netguard (public addresses only).

### 3.3 Data flow (pull, with a ping)

- `GET /federation/v1/subscriptions/{id}/changes?cursor=` (signed by B): A returns, in order, the family's post
  additions (post id, poster display name, caption, capture time, focal point, fit, item ids with master URLs signed
  for 1 hour, circle audience never: circle posts are not shared with remote frames), removals (unsend, admin
  removal, taking away per `leaving.md`, trims), birthdays (same rules as the local `birthdays` change, show_age
  respected) and comment summaries when the remote frame has *show comments* on (three newest per post, first names).
  Same cursor and backfill semantics as the local feed placement.
- A sends a signed ping `POST {B}/federation/v1/ping {subscription_id}` after new changes (debounced 10 s); B also
  polls every 15 minutes. Missed pings never lose data.
- B downloads each master once per item, verifies size and type, makes the frame's renditions as for any item, and
  stores the item as owned by a **remote sender** record (server A + poster display name). Removal on A removes
  placements on B; B deletes items no longer referenced.
- Back to A: B forwards the frame's reaction and quick reply on a remote placement
  (`POST /federation/v1/subscriptions/{id}/activity {post_id, item_id, reaction|reply}`), and "seen". A shows them in
  the thread as frame entries "Oma's frame (kinframe.appmire.be)" and counts them in receipts.
- Unsubscribe: either side (`DELETE /federation/v1/subscriptions/{id}` signed, or locally on B). B removes the
  placements per the frame's choice (keep what is there / remove all), like a local subscription.

### 3.4 Privacy and trust

- Anna sees, before a remote subscription becomes active, which server will receive copies and who runs it (the
  server name). Feed setting "Allow frames on other servers" (default off) must be on.
- Only what a local subscribed frame gets is shared: whole-family posts (never circle posts, chats, comments beyond
  the frame's comment card, member lists or emails).
- Server B's operator can see the copies, as with any photo on B; this is stated in the confirmation.
- Abuse: A's admins can remove a remote frame and block a server for their family; the operator can block a server
  instance-wide.

## 4. User stories (short)

1. Tom adds Lies's server in the app (opening her family's invite link) and sees both families in one Home.
2. Tom shares 5 photos from his gallery to "Janssens" (server A) and "Oma's frame" (server B): two uploads, one sheet.
3. Oma's frame on B follows "Janssens" on A; Oma taps ❤ on a photo Lies posted; Lies sees "❤ Oma's frame
   (kinframe.appmire.be)" in her thread.
4. Anna removes the remote frame; it stops getting new photos and, per Tom's earlier choice, keeps or drops the rest.

## 5. API (sketch; the contract goes into `openapi.yaml`)

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/kinframe` | Server identity and federation endpoints (public) |
| Discovery: `server_id`, `server_name` | Labels in the apps |
| Push payloads: `server` | Open the right account |
| `POST /v1/frames/{id}/remote-subscriptions {invite_url, history}` / `GET` / `DELETE …/{sid}` | Frame managers on B |
| Feed: `allow_remote_frames` setting; subscriptions list shows `remote: {server_name, server_url}`; invites `allow_remote` | Admins on A |
| `POST /federation/v1/subscriptions` | B asks A (signed), with the invite code |
| `GET /federation/v1/subscriptions/{id}/changes`, `POST /federation/v1/ping`, `POST …/activity`, `DELETE …/{id}` | Data flow (signed) |
| Operator: `KINFRAME_FEDERATION`, `KINFRAME_FEDERATION_ALLOW`, blocked servers list | Trust |

## 6. Decisions

- **A. Multi-account in the Android and iOS apps; the web stays single-server** with links to the others.
- **B. Pull with ping** between servers (robust against downtime), signed with Ed25519 HTTP Message Signatures.
- **C. Remote frames get whole-family posts only**; circle posts, chats and member data never leave the server.
- **D. Federation on by default, per-family opt-in** ("Allow frames on other servers", off by default).

## 7. Not now

- Members, threads or chats spanning servers (full federation). If ever needed, prefer an existing protocol
  (Matrix for chats) over inventing one.
- Moving a family from one server to another (export/import) — a separate topic.
