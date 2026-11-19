# Email to frame

Status: **draft** (2026-11-19). Extends `product-spec.md` (§4.3 "Email to frame", §4.11 "Email-to-frame inbox"),
`feeds.md` (an address can post to a feed) and `photo-sources.md` (the `ItemVia` credit, source sends, batching,
calm notifications). The API contract is in `docs/api/openapi.yaml` (tag `email-inbox`: `/v1/frames/{id}/email-addresses`,
`/v1/feeds/{id}/email-addresses`, `/v1/email-addresses…`, the operator webhook `/v1/inbound/email`; `ItemVia.service:
email`) and the protocol guide `docs/api/README.md` §5.16. Tables are in §16 below.

## 1. Summary

Not every relative will install an app. Oma's brother has an email address and a phone that can attach photos to a
mail, and that is it. **Email to frame** gives a frame or a feed its own address: whoever is allowed mails photos to
it, and they appear on the frame (or in the feed, on every subscribed frame).

- **An address per target.** `oma-k7q2m9@in.kinframe.appmire.be` fills Oma's frame; `peeters-x4c9td@…` posts to the
  "Peeters kids" feed. Managers create, pause, rename, regenerate and delete them.
- **Not an open door.** The address is hard to guess, but it is not the only gate: only allowed senders get through.
  Members of the frame or feed are allowed automatically (by their verified account email); anyone else is held until
  a manager approves them once.
- **Spoof-proof.** A mail counts as being from `jan@example.org` only when `example.org` vouches for it (DMARC-aligned
  SPF or DKIM). Otherwise it is an unknown sender, whatever the `From:` line says.
- **Nothing new for frames.** Mailed photos are ordinary items and placements. Frames that know about it show "Jan via
  email"; older frames show the address owner's name.
- **Email is the fallback; the app is better.** Every mail kinframe sends back to a sender shows what the app adds and
  how to get it, prominently once, in one line afterwards, with a one-tap way to join the family in the app (§10.4).
- **Two ways in.** The server can receive mail itself (a small SMTP receiver, port 25), or the operator forwards mail
  from a provider to a signed webhook when port 25 can't be opened.

## 2. Concepts

- **Inbox domain**: one mail domain per server, chosen by the operator (`KINFRAME_EMAIL_INBOX_DOMAIN`, e.g.
  `in.kinframe.appmire.be`). Its MX record points at the server (or at the provider in front of the webhook). It is
  used for nothing else.
- **Inbox address** (`EmailAddress`): `<slug>-<random>@<inbox domain>`, connected to exactly one **target**:
  - a **frame** (`frame_id`): a mail becomes a direct send to that frame;
  - a **feed** (`feed_id`): a mail becomes a feed post (every active subscribed frame gets it, `feeds.md`).
  Fields: `slug` (friendly part, chosen by the manager), the random part, `name` (label shown in the apps and in
  replies, default the target's name: "Oma's frame"), `owner`, `status` (`active` or `paused`), settings
  (`anyone_may_send`, `send_confirmations`, `offer_app_invite`, `notify_reactions`), counts.
- **Owner**: the manager who created the address. Mail from senders without a matching kinframe account is **sent by
  the owner** (it needs an account with send rights), credited to the mail's sender (§9). If the owner loses their
  rights, ownership passes to the frame's owner or the feed's owner automatically (§4.3).
- **Sender**: the address in the mail's `From:` header, **only when authenticated** (§7). Unauthenticated mail has no
  sender as far as kinframe is concerned, only a *claimed* one.
- **Allowed senders** (per address): an allow-list of email addresses (added by managers, or by approving a pending
  sender), plus, automatically and without being listed, the verified account emails of the target's members with
  send rights (frame members with *send*; any feed member). Blocked senders are on the same list with state `blocked`.
- **Pending sender**: someone who mailed the address without being allowed. Their mail is **held** (up to 14 days)
  until a manager approves (sender allowed, held mail delivered) or rejects (held mail deleted, optionally blocked).
- **Held message**: one held mail: its extracted media (already processed into masters and thumbnails, never the raw
  mail), sender, subject, caption, authentication result, and expiry.
- **Email send**: a mail that gets through becomes **one send** (marked `via: email`) by the matched account or by the
  address owner. Frame target: placements on that frame. Feed target: one post (several when it has more than
  `max_items_per_send` items). Everything downstream (renditions, change feed, moderation, receipts, reactions,
  unsend) is the existing machinery.
- **Credit** (`ItemVia`): `service: email`, `label` = the sender's name ("Jan"), so frames show "Jan via email".
- **Reply**: a short mail back to an authenticated sender: delivered, held, or what went wrong (§10). Never to
  unauthenticated senders.

## 3. User stories

### Family app and web: frame manager

1. *Create an address.* Anna manages Oma's frame. Frame settings → **Email photos to this frame** → "Create an
   address". She types the friendly part "oma" (prefilled from the frame name) and gets
   `oma-k7q2m9@in.kinframe.appmire.be`. The screen explains: "Family members can mail photos here from the email
   address of their kinframe account. Anyone else needs your OK the first time."
2. *Share it.* She taps **Share** and picks WhatsApp: a ready-made message reads "You can now email photos to Oma's
   frame: oma-k7q2m9@in.kinframe.appmire.be. Just attach them; the subject becomes the caption. The first time, Anna
   approves your address." **Copy** copies only the address.
3. *Pre-approve.* Anna knows uncle Jan will mail from `jan.peeters@telenet.be`; she adds it under **Allowed senders**
   with the name "Jan". His first mail goes straight through.
4. *First mail from someone new.* Aunt Rita mails three photos from `rita@gmail.com`. Anna gets a notification: "Rita
   (rita@gmail.com) wants to send 3 photos to Oma's frame". The pending list shows Rita's mail: subject "Garden
   party", received 10:42, three thumbnails. Anna taps **Approve**: the photos go to the frame, Rita is now allowed,
   and Rita gets "Your 3 photos are on Oma's frame", with a link to join Oma's frame in the app.
5. *Spam or a stranger.* A mail from an address nobody knows: Anna taps **Reject** and ticks "Block this sender".
   Nothing reaches the frame; the sender is never told.
6. *Leaked address.* The address ended up in a forwarded mail chain. Anna taps **Regenerate**: the address becomes
   `oma-p3wd8h@…`; the old one stops working at once. Allowed senders stay; she shares the new one.
7. *Pause.* During a family row Anna pauses the address; mail to it bounces with "This address is paused". Resume
   reopens it.
8. *Recent mail.* "Jan says he sent photos yesterday": the address's **Recent mail** shows "Yesterday 18:03, Jan, no
   photos found (the photos were links to iCloud)".

### Family app and web: feed

1. Ben (admin of "Peeters kids") creates `peeters-x4c9td@…` under Feed → Settings → Email. Mail from any feed member's
   account email posts to the feed as that member. Grandpa's mail (approved once) posts as "Grandpa via email",
   owned by Ben.
2. One mail with 14 photos becomes one post "Grandpa via email · 14 photos", on all five subscribed frames.

### Sender (no app)

1. Jan attaches four photos in his phone's mail app, subject "Fwd: Re: pics from Sunday", and sends. A minute later
   they are on Oma's frame with the caption "pics from Sunday" and the credit "Jan via email".
2. He gets a reply: "Your 4 photos are on Oma's frame." Below it, a box "Next time, try the kinframe app: see when Oma
   has seen your photos, get her ❤ back, send in seconds from your photo library, videos and Live Photos too", with
   App Store, Google Play and web links and a **Join Oma's frame** button. Later replies only have one line: "Sending
   is easier in the kinframe app: <link>".
3. He installs the app from the link, signs in with `jan.peeters@telenet.be`, and lands on "Ask to join Oma's frame".
   Anna approves; from then on Jan is a member and his mails are sent as his account.
4. He mails a 25-minute video: "The video was longer than 5 minutes, so it was skipped." He mails only a Google Drive
   link: "We found no photos or videos attached. Links aren't downloaded; attach the photos themselves."

### Frame viewer

1. Mailed photos look like any other. The caption credit reads "Jan via email"; reactions go to the matched account,
   or to the address owner.

### Operator

1. Email is off by default (`KINFRAME_EMAIL_INBOX`). Turning it on needs an inbox domain and at least one way in: the
   SMTP receiver (`KINFRAME_EMAIL_SMTP_LISTEN`, port 25 open, an MX record, a TLS certificate) or the webhook
   (`KINFRAME_EMAIL_WEBHOOK_SECRET`). Discovery then says `email_inbox: true` with `email_inbox_domain`.
2. Replies need the outgoing mail settings the server already has (`KINFRAME_SMTP_*`); without them nobody gets
   replies, everything else works.
3. Limits (addresses per target, senders per address, message size, mails per day) are in `PUT /v1/operator/limits`.

## 4. Permissions

### 4.1 Frame addresses

"Frame manager" = owner, admin, or a member with *change settings* (as for subscriptions, `feeds.md` §4.2).

| Action | frame manager | other frame member | anyone else |
|---|---|---|---|
| Create an address (becomes its owner) | ✓ | — | — |
| List the frame's addresses, see counts and recent mail | ✓ | — | — |
| Rename, change slug, pause/resume, regenerate, change settings, take over, delete | ✓ | — | — |
| List, add, remove, block, unblock allowed senders | ✓ | — | — |
| See pending senders and held mail (sender, subject, thumbnails); approve, reject, block, deliver one held mail, delete one | ✓ | — | — |
| Mail photos to it | from their verified account email (with *send*) | from their verified account email (with *send*) | only when allowed, or with *anyone may send* |
| Hide, trash, purge a mailed photo; unsend | as any placement (*manage library*; the sender account or the owner as sender) | as any placement | — |

### 4.2 Feed addresses

| Action | feed owner | feed admin | poster |
|---|---|---|---|
| Create, list, change, regenerate, delete addresses; senders; pending and held mail | ✓ | ✓ | — |
| Mail photos to it (from the verified account email) | ✓ | ✓ | ✓ |
| Remove a post or item that came by mail | ✓ | ✓ | own posts (matched mail) only |

### 4.3 Owner and rights

- Every delivery checks that the owner can still send to the target (frame: member with *send*; feed: member). If not
  (left, removed, lost *send*, account deleted), ownership passes **automatically** to the frame's owner (frame
  address) or the feed's owner (feed address), an `email_address.updated` event goes to the managers, and the mail is
  delivered as theirs. No mail is lost or held for it. Any manager may also take over explicitly (`take_over: true`).
- Matched senders (§8.2) need their own rights at delivery time: a member who left is no longer matched; their mail
  is treated as from a non-account sender (allowed only if they are on the allow-list).

### 4.4 Visibility

- Unknown addresses and addresses the caller may not manage answer `404 not_found`.
- Members' account emails are **never** listed as allowed senders: the address shows "Members of Oma's frame can send
  from their account email" instead. Managers see only the addresses they added or approved, and pending ones.
- The full address is a capability: it is shown to managers only. Frames never see it.

## 5. States

### 5.1 Inbox address

```
          create
            │
            ▼
   ┌──▶ active ── pause ──▶ paused ──┐
   │      │  ▲                 │     │
   │      │  └──── resume ─────┘     │
   │   regenerate / change slug:     │
   │   new local part, old one       │
   │   retired at once               │
   └──────┘                          │
   delete (any state), frame removed, feed deleted ──▶ (gone; local part retired; held mail deleted)
```

| Status | SMTP answer at `RCPT TO` | Webhook outcome | Apps |
|---|---|---|---|
| `active` | `250` | `accepted` | Green "Ready" with the last mail time |
| `paused` | `550 5.2.1 This address is paused` | `paused` (dropped) | Grey "Paused: mail to it bounces" |
| retired / unknown | `550 5.1.1 No such address here` | `unknown_recipient` | — |

- Repeating a transition (pause a paused address) returns it unchanged (`200`).
- A retired local part is never issued again (`email_retired_local_parts`).
- When the feature is turned off, the SMTP receiver doesn't run and the webhook answers `503`; addresses are kept.

### 5.2 Sender (per address)

```
 (unknown) ──mail, not allowed──▶ pending ──approve──▶ allowed ◀──add── (manager)
                                     │                    │ ▲
                                     │ reject             │ │ unblock / add
                                     ▼                    ▼ │
                          (row deleted; next mail      blocked ◀──block── (manager)
                           makes it pending again)
                                     │ reject + block
                                     └──────────────▶ blocked
```

| `state` | Meaning | Mail from them |
|---|---|---|
| `allowed` | On the allow-list (added by a manager, `origin: manual`, or approved, `origin: approved`). | Delivered (when authenticated) |
| `pending` | Mailed without being allowed; at least one held message. | Held (added to their held messages) |
| `blocked` | Rejected with block, or blocked by a manager. | Dropped silently, no reply |

- A pending sender whose held messages all expire (14 days) or are all deleted is removed (back to unknown).
- Reject without block deletes the row: if they mail again they become pending again, and managers are notified again
  at most once per 7 days per sender.
- Account members are never rows: they are matched at delivery time (§8.2).

### 5.3 Held message

| `reason` | Why it is held | What managers can do |
|---|---|---|
| `unknown_sender` | Authenticated, but not allowed. | Approve the sender (delivers all their held mail), reject (optionally block), or delete this one |
| `unverified_sender` | Not authenticated (§7): the `From:` can't be trusted, whatever it says, even if it names an allowed sender or a member. | **Deliver this one** (credited to the claimed name, owned by the owner; the sender is not allowed by it), or delete. Approving adds the claimed address to the allow-list, but its next mail still has to authenticate |

Held messages are deleted 14 days after they arrived (`expires_at`), when the sender is rejected, or when the address
goes. Approved or delivered ones become a send and the held copy goes.

## 6. Receiving mail

### 6.1 Two inbound paths

```
 sender's mail server ──SMTP:25──▶ kinframe SMTP receiver ─┐
                                                           ├─▶ checks ─▶ spool + queue ─▶ worker: parse, extract, send/post
 sender's mail server ──SMTP──▶ provider ──HTTPS POST──────┘    (auth, recipient, size)        replies via KINFRAME_SMTP_*
                               (MX at the provider)  /v1/inbound/email (HMAC)
```

Both end in the same queue and the same processing (§8). A server can run both (for example while moving the MX).

### 6.2 SMTP receiver (built in)

Listens on `KINFRAME_EMAIL_SMTP_LISTEN` (e.g. `:2525` in the container, published as port 25). It is a **receiver
only**: no `AUTH`, no submission, no relaying, ever.

| Step | Behaviour |
|---|---|
| Connect | Per-IP limits (§12.2). Greeting `220 <KINFRAME_EMAIL_SMTP_HOSTNAME> ESMTP kinframe`. With `KINFRAME_EMAIL_PROXY_PROTOCOL=on` a PROXY protocol v1/v2 header is required first (for a TCP proxy in front; otherwise the client IP for SPF is the proxy's). |
| `EHLO`/`HELO` | Advertises `SIZE <max_email_message_bytes>`, `8BITMIME`, `PIPELINING`, `ENHANCEDSTATUSCODES`, `SMTPUTF8`, `CHUNKING`, and `STARTTLS` when a certificate is configured. Never `AUTH`. The HELO name is kept for SPF. |
| `STARTTLS` | TLS 1.2+, certificate and key from `KINFRAME_EMAIL_TLS_CERT_FILE` / `…_KEY_FILE`, re-read when the files change (certbot renewals). Opportunistic by default: senders without TLS still deliver (most do TLS). Handshake timeout 10 s. |
| `MAIL FROM` | Kept for SPF. A `SIZE=` above the limit → `552 5.3.4 Message too big`. The null sender `<>` is accepted (bounces, autoreplies) but such mail is never replied to. |
| `RCPT TO` | **The recipient is checked here**, so unknown addresses are refused in the dialogue and the sender's own server bounces (kinframe sends no bounce). Domain other than the inbox domain → `550 5.7.1 Relaying denied`. Local part (case-insensitive, a `+tag` suffix ignored) unknown or retired → `550 5.1.1`; paused → `550 5.2.1`; `postmaster`/`abuse` → accepted only when `KINFRAME_EMAIL_POSTMASTER` is set (§6.5). At most 20 recipients per mail (`452 4.5.3`). After 5 refused recipients in one connection: `421 4.7.0` and close (address guessing). |
| `DATA` / `BDAT` | Streamed to the scratch volume with a hard cap at the size limit (`552 5.3.4` beyond). Headers over 256 KiB → `552 5.3.4`. |
| End of data | Authentication (§7, DNS budget 20 s). `From:` domain's DMARC policy is `reject` and the mail fails it → `550 5.7.1 Rejected by the sender domain's DMARC policy`. A DNS temporary error that decides the outcome → `451 4.7.0 Temporary authentication failure, try again later`. Otherwise the spooled file and its envelope and authentication results are committed (fsync, queue row) **before** `250 2.0.0 Queued as <id>`: once accepted, kinframe is responsible for the mail. |
| Timeouts | 5 minutes per command, 10 minutes for the data, 30 minutes per session; at most `KINFRAME_EMAIL_SMTP_MAX_SESSIONS` (100) at once. |

Library (§15): `github.com/emersion/go-smtp` (MIT): `Backend`/`Session` with `Mail`, `Rcpt`, `Data`, limits,
STARTTLS, `CHUNKING`. Its `Rcpt` hook is where the address lookup runs.

### 6.3 Webhook (for operators who can't open port 25)

`POST /v1/inbound/email` (`receiveInboundEmail`, operator integration, not for clients), on when
`KINFRAME_EMAIL_WEBHOOK_SECRET` is set.

- **Body**: the raw message exactly as received (`Content-Type: message/rfc822`), at most `max_email_message_bytes`
  (`413`).
- **Envelope headers**: `X-Kinframe-Envelope-To` (the recipient(s), comma-separated; required: BCC recipients exist
  only here), `X-Kinframe-Envelope-From` (the `MAIL FROM`, may be empty), and, when the provider knows them,
  `X-Kinframe-Client-Ip` (the sending server's address) and `X-Kinframe-Helo`.
- **Signature**: `X-Kinframe-Timestamp` (Unix seconds) and `X-Kinframe-Signature: v1=<hex>` where `<hex>` is
  HMAC-SHA256 with the shared secret over

  ```
  "v1" LF timestamp LF envelope_to LF envelope_from LF client_ip LF helo LF hex(sha256(body))
  ```

  (header values as sent, empty strings for absent ones). The server compares in constant time, accepts timestamps
  within ±5 minutes, and refuses a signature it has seen in the last 10 minutes (replays). `401 unauthorized`
  otherwise. The secret may hold two comma-separated values (new, old) for rotation.
- **Authentication results**: by default the server runs its own checks (§7): DKIM from the body, SPF only when
  `X-Kinframe-Client-Ip` is present (without it, DKIM is the only way to authenticate, which covers the big mail
  providers, §7.4). With `KINFRAME_EMAIL_WEBHOOK_TRUST_AUTHSERV_ID=<id>` it instead trusts the **topmost**
  `Authentication-Results` header whose authserv-id is exactly `<id>` (the provider's own), reading `dmarc=pass` with
  `header.from`, and ignores every other such header (anyone can write one).
- **Answer**: `202` with one outcome per envelope recipient (`accepted`, `unknown_recipient`, `paused`,
  `rejected_dmarc`, `too_large`); the provider must not retry a `2xx` or `4xx`. `5xx` and `429` are retried by the
  provider; a retried message is recognised (§8.6) and not delivered twice.
- **Recipient pre-check**: `POST /v1/inbound/email:check` (`checkInboundEmailRecipients`, same signature over the
  JSON body) answers which recipients exist and are active, so a provider that can reject during its own SMTP
  dialogue (a Cloudflare Email Worker's `setReject`, a Postfix policy service) does so instead of accepting and
  dropping.
- **Rate limit**: 600 requests per minute in total (`429` with `Retry-After`).

Typical setups (documented for operators, §14):

| Setup | Client IP for SPF | Notes |
|---|---|---|
| Cloudflare Email Routing → Email Worker posting to the webhook | no | A ~30-line worker (§14.3); DKIM-only authentication. |
| Own Postfix/Exim on another host, `pipe` transport to `kinframe-server deliver-email` | yes | The subcommand reads the message on stdin, signs and posts it; Postfix passes `${sender}`, `${recipient}`, `${client_address}`, `${client_helo}`. |
| A provider's inbound-parse service (Mailgun, Postmark, SES) | depends | Needs a small adapter that fetches the raw message and posts it signed; not built in (§17 open question 4). |

### 6.4 Queue

- One row per accepted message (`email_inbound`): spool path, envelope, client IP, HELO, authentication results,
  received time, attempts. Workers pick rows in order; a crash leaves the row and the next start resumes.
- The spooled raw message is deleted as soon as processing finished (success or final failure); at the latest after
  24 hours (then the message is dropped and logged as `failed`).
- Processing runs per recipient address (§8); the parse happens once per message.

### 6.5 postmaster and abuse

RFC 5321 expects `postmaster@` to exist. With `KINFRAME_EMAIL_POSTMASTER=<address>`, mail to `postmaster@` and
`abuse@` the inbox domain is forwarded there through the outgoing mailer as an attachment (`message/rfc822`) to a new
mail from the server's own address (never relayed with the original envelope). Without it they are refused like any
unknown address. These two are the only mailboxes that ever forward anything.

## 7. Anti-spoofing

### 7.1 Rule

A mail's sender is the single address in its `From:` header **only if the mail passes DMARC-aligned SPF or DKIM**
for that address's domain. Otherwise the mail is **unauthenticated**: it is never matched to an account or to an
allow-list entry, never answered, and at best held as `unverified_sender` (§5.3). This holds whatever the domain's
DMARC policy is (`p=none` included, and also when the domain publishes no DMARC record at all).

### 7.2 Checks

Computed once per message, at the end of the SMTP data phase (or by the worker for webhook mail):

1. **From**: exactly one `From:` header with exactly one address (RFC 5322 parse, IDN domains in A-label form).
   Otherwise unauthenticated (`from_invalid`).
2. **SPF** (RFC 7208): `check_host(client IP, MAIL FROM domain)`, or the HELO name when `MAIL FROM` is `<>`. Result
   `pass`, `fail`, `softfail`, `neutral`, `none`, `temperror`, `permerror`. DNS lookups capped at 10 (the RFC limit),
   void lookups at 2. Skipped (`not_checked`) without a usable client IP: webhook without `X-Kinframe-Client-Ip`, or
   a client IP that is a private or Docker bridge address (the port mapping hides the real one; logged once as a setup
   warning, §14.2).
3. **DKIM** (RFC 6376): verify every `DKIM-Signature` (at most 5 checked; RSA ≥ 1024 bits and Ed25519; `l=` body
   lengths are honoured but such signatures count only when the whole body is covered; expired `x=` fails). Result per
   signature with its `d=` domain.
4. **DMARC** (RFC 7489): look up `_dmarc.<From domain>`, else `_dmarc.<organizational domain>` (Public Suffix List,
   `golang.org/x/net/publicsuffix`). Alignment: **SPF aligned** when SPF passed for a `MAIL FROM` domain that matches
   the From domain (relaxed: same organizational domain; strict when the record says `aspf=s`); **DKIM aligned** when a
   passing signature's `d=` matches the same way (`adkim`). No record: relaxed alignment is still required. The result
   records `dmarc=pass|fail|none|temperror`, the policy (`p`, or `sp` for subdomains) and which check aligned.
5. **Authenticated** = DMARC alignment passed via SPF or DKIM.

Existing `Authentication-Results`, `Received-SPF` and ARC headers in the message are ignored (anyone can write them),
except the provider's own in the webhook trust mode (§6.3).

### 7.3 Policy outcomes

| Result | SMTP path | Webhook path |
|---|---|---|
| Aligned pass | Accepted; sender = From address | Same |
| Fail with `p=reject` | Refused in the dialogue (`550 5.7.1`): the domain asked for it | Dropped (`rejected_dmarc`), no reply |
| Fail with `p=quarantine`, `p=none`, or no record | Accepted as unauthenticated (held as `unverified_sender` when the address would otherwise deliver or hold it) | Same |
| `temperror` deciding the outcome | `451 4.7.0` (the sender's server retries) | Retried by the worker 3 times over 30 minutes, then unauthenticated |

With *anyone may send* an unauthenticated mail is delivered (there is nothing to verify against), credited to the
claimed display name, owned by the address owner, never matched, never answered.

### 7.4 Why this is enough in practice

Gmail, Outlook/Hotmail, iCloud, Yahoo, GMX/web.de, Proton and most ISP mail (Telenet, Proximus, KPN, Orange) DKIM-sign
with the From domain, so their users authenticate even through forwarders that break SPF. A small self-hosted domain
without SPF or DKIM stays unauthenticated: its mail is held as unverified each time and can be delivered by hand
(§17 open question 3). Domains that let any of their users send as any other user (some small company servers) can't
be told apart; that is the domain's own weakness and accepted.

### 7.5 Libraries

`github.com/emersion/go-msgauth` (MIT): `dkim.Verify`, `dmarc.Lookup` (record parsing) and `authres` (parsing
`Authentication-Results` for the webhook trust mode). SPF: `blitiri.com.ar/go/spf` (MIT; the SPF checker of
chasquid), with a resolver of our own for timeouts and the lookup budget. ARC is not evaluated (no maintained Go
verifier; §14 edge cases, open question 2).

## 8. Processing algorithm

Per accepted message, after §6 and §7:

### 8.1 Recipients

1. Collect the envelope recipients on the inbox domain (SMTP `RCPT TO`, webhook `X-Kinframe-Envelope-To`; a webhook
   without the header falls back to `To:`/`Cc:` addresses on the inbox domain). `To:`/`Cc:` addresses elsewhere are
   ignored: kinframe never writes to anyone but the sender.
2. Look each one up again (it may have been paused or regenerated since `RCPT`): unknown or paused → that recipient
   is dropped (logged).
3. **Loop and robot guard**: the mail is dropped for all recipients, without reply, when it is an automatic message:
   `Auto-Submitted` other than `no`, `Precedence: bulk|junk|list`, a `List-Id` or `List-Unsubscribe` header **and** no
   usable media, `MAIL FROM` `<>`, a `From:` local part `mailer-daemon` or `postmaster`, a delivery report
   (`multipart/report`), or a calendar invitation (top-level `text/calendar`, or `Content-Class:
   urn:content-classes:calendarmessage`) without images. Out-of-office replies to kinframe's own replies end here.

### 8.2 Sender decision (per recipient address)

```
authenticated? ──no──▶ anyone_may_send? ──yes──▶ deliver (owner; claimed name; no reply)
     │                        └──no──▶ hold (unverified_sender)
    yes
     ▼
blocked on this address? ──yes──▶ drop silently
     │no
     ▼
From == verified email of an account with rights on the target? ──yes──▶ deliver as that account
     │no
     ▼
on the allow-list (allowed)? ──yes──▶ deliver (owner; sender's name)
     │no
     ▼
anyone_may_send? ──yes──▶ deliver (owner; sender's name)
     │no
     ▼
hold (unknown_sender): pending sender, notify managers (§11)
```

- **Account match**: the authenticated From address (lower-cased) equals `users.email` of a non-deleted account with
  `email_verified`, which is a frame member with *send* (frame address) or a feed member (feed address). Exactly one
  account can match (`users_email_verified_unique`). Comparison is exact apart from case: no provider-specific
  rewriting (Gmail dots, `+tags`).
- **Verified account emails**: `users.email` is Logto's primary email, which Logto sets only after a one-time-code
  check or from a social provider that verified it (Google, Apple). *Finding*: today the server writes `users.email`
  only when the account row is created and never refreshes it; this change refreshes it (and `email_verified`) from
  the access-token claims on each sign-in where they differ, and the bootstrap's access-token script adds
  `email_verified` (Logto's `primaryEmail` present → true). An email that two accounts claim is matched to neither.
- Before media extraction a held or delivered decision is only provisional: a mail without usable media is never held
  (§8.4).

### 8.3 MIME parsing

The message is parsed once (`github.com/emersion/go-message`, MIT; charsets via `golang.org/x/text`) as a tree, with
hard limits (§12.3): depth 8, 200 parts, 64 KiB headers per part. Walking the tree:

| Part | Treatment |
|---|---|
| `image/*`, `video/*`, `application/octet-stream` (and parts whose file name looks like media), as attachment or inline (`Content-ID`, also when only referenced from HTML) | **Candidate**: decoded (base64, quoted-printable, 7bit/8bit/binary; anything else skipped as `unsupported`) to scratch with a size cap, then sniffed by magic bytes (`media.Sniff`; the declared type is never trusted) |
| `text/plain` (the first one outside attachments) | Caption fallback (§8.5) |
| `text/html` | **Never rendered, never fetched.** Only used for the caption fallback when there is no `text/plain` (tags stripped with `golang.org/x/net/html`'s tokenizer). Remote images and links are ignored. |
| `multipart/*` | Walked (`alternative`: the richest branch that holds images wins, the others' text is ignored) |
| `message/rfc822` (forwarded as attachment) | Walked as a nested message (within the depth limit); its images count, its headers don't |
| `multipart/signed` (S/MIME, PGP) | The signed content is walked; the signature is ignored |
| `multipart/encrypted`, `application/pkcs7-mime` (`smime-type=enveloped-data`) | Can't be read: `encrypted` (§10) |
| `text/calendar`, `application/ics` | Ignored |
| Archives (zip, rar, 7z, tar, gz), `application/ms-tnef` (`winmail.dat`), documents (PDF, Office) | Ignored, never unpacked (`unsupported`) |

### 8.4 Media extraction

For each candidate, in the order it appears in the mail:

1. **Kind** by sniffing: photo (JPEG, PNG, HEIC/HEIF, WebP, GIF, also AVIF since the pipeline reads it) or video
   (MP4, MOV, WebM, 3GP, Matroska). Anything else: `unsupported`.
2. **Size**: more than `max_upload_bytes` → `too_large`.
3. **Tiny images** (signatures, logos, tracking pixels, emoji): a photo whose long side is under **300 px** (read from
   the header without decoding) → `too_small`. Also images over 100 megapixels → `too_large` (decompression bombs).
4. **Videos** follow kinframe's video rules: longer than `max_video_seconds` → `too_long` (never trimmed silently),
   audio kept, no Live Photo pairing (mail apps send the still only, or the clip as a separate video).
5. **Duplicates**: the same sha256 twice in the mail → once; the same file already live in the target (live
   placements on the frame; live post items of the feed; `media.source_sha256` and `original_sha256`, as
   `photo-sources.md` §8.1 step 2) → `duplicate`, nothing created. Media reuse for the same sender's same file as
   for any upload.
6. **Count**: more than `max_items_per_send` usable items → the extra ones go into the next send (frame) or post
   (feed) of the same mail, up to 5 × `max_items_per_send`; beyond that `over_limit`.
7. **Capture time**: the file's EXIF/QuickTime time as for any upload; otherwise the mail's `Date:` header (within the
   last 20 years and not more than a day in the future) as `captured_hint`; otherwise the receive time.

No usable media at all → the mail is **dropped** for this address (outcome `no_media`, or `too_large`/`too_long`
when everything was too big or too long, `encrypted` when it was encrypted), with a reply when §10 allows it. It is
never held: there would be nothing to approve.

Usable files go through the normal media path (`CreateMedia` with `SourcePath`, `JobProcessMedia`): masters and
renditions as for an upload, scratch files deleted once the master exists (product spec §8 decision 2). Held mail is
processed the same way at once (masters and thumbnails, no items), so approving later only creates the send.

### 8.5 Caption

- **Subject**, trimmed, with leading reply and forward markers removed, repeatedly and case-insensitively: `Re:`,
  `Fwd:`, `Fw:`, `Tr:`, `Wg:`, `Aw:`, `Antw:`, `Doorst:`, `RV:`, `SV:`, `VS:`, `R:`, `I:`, `Enc:`, `Odp:`, `Sv:`, also
  with `[…]` list tags and numbered forms (`Re[2]:`). An empty, or a subject that is only a file name (`IMG_1234.JPG`,
  `Photo 12-05-26`), or a default subject a phone puts in ("Photo", "Foto", "Image", "Sent from my iPhone") counts as
  no subject.
- **No subject**: the first non-empty line of the text body, before any quote (`>`, "On … wrote:", "Op … schreef:",
  the localized forms), signature separator (`-- `) or a known device signature ("Sent from my iPhone",
  "Verzonden met mijn iPhone", "Envoyé de mon iPhone", "Von meinem iPhone gesendet", "Get Outlook for …"), at most 280
  characters.
- The caption applies to **every** item of the mail (as "apply to all" in the app). At most 280 characters, control
  characters removed.

### 8.6 Delivery (send or post)

1. **Idempotency**: message key = sha256 of the `Message-ID` (normalised), or, without one, of `Date`, `From`,
   `Subject` and the sha256 list of the usable files. A key already delivered or held for this address in the last 30
   days → `duplicate` (provider retries, an SMTP client retrying after a lost `250`, the same mail sent twice).
2. **Group** recipients by who the send is by: when one mail reaches several addresses and the send would be by the
   same account (the matched sender, or the same owner with the same credit), they become **one send** with all their
   frames (`frame_ids`) and feeds (`feed_ids`), so a frame reached directly and through a feed gets each photo once
   (`feeds.md` §6.2). Otherwise one send per group; the content check (§8.4 step 5) keeps the second from repeating
   photos already on a frame.
3. **Create** the send through the normal send path: `Send.via` / `Item.via` = `{service: email, label: <name>,
   address_id}`, caption per item, sender = matched account or owner. Frame target: placements on the frame (status
   `pending` until renditions are ready). Feed target: one post per `max_items_per_send` items, items in mail order.
   Moderation as in §9.3. Not quiet: mailed photos are new on the frame ("N new", screen wake), like any send.
4. **Record** the outcome in `email_messages` (§16), emit `email_address.updated` (counts, last mail), and queue the
   reply (§10).

## 9. Attribution, moderation and credit

### 9.1 Who the send is by

| Sender | Send by | `ItemVia.label` | Frames show | Reactions go to |
|---|---|---|---|---|
| Matched account (§8.2) | That account (in their sent history, like an upload) | The account's display name | "Anna via email" | The account |
| Allowed or approved non-account sender | The address owner | The display name from `From:` (else the allow-list name, else the local part) | "Jan via email" | The owner, unless `notify_reactions` is off for the address |
| Unauthenticated mail delivered by hand or with *anyone may send* | The address owner | The claimed display name, marked unverified in the apps | "Jan via email" | The owner (same switch) |

- Mailing-list display names of the form "Jan via Family List" keep only "Jan" when the From address is the list's.
- `label` is at most 40 characters, control characters and line breaks removed.
- The owner sees these sends in their history marked "via email from Jan"; they can unsend them like their own.

### 9.2 Credit on frames and in the apps

| Path | Today's frames and apps | With `via` |
|---|---|---|
| Direct (frame address) | the owner's name ("Anna") for non-account senders, the account's name otherwise | "Jan via email" |
| Feed | "Anna · Peeters kids" | "Jan · Peeters kids via email" |
| Shared on | "Anna · shared by Ben" | "Jan via email · shared by Ben" |

- For `service: email` the frame shows `label` **instead of** the sender's name ("%1$s via email", localized); for the
  photo services it stays "sender via label". Old frames show the sender's name (for non-account mail: the owner).
  **Decided** as a transitional limitation: frames self-update, and the release note says so (the photos still
  arrive).
- The address itself is never shown on frames.

### 9.3 Moderation

A frame's moderation applies as to any send: with moderation on, a placement starts `pending_moderation` unless the
sender is owner or admin of that frame. For mail that the **owner** sends on behalf of a non-account sender, the
exemption does **not** apply: on a moderated frame such photos always wait in the queue, even when the owner is an
admin, because the person who chose them isn't. (Approving the sender once is about who may send; moderation is about
what shows on a care-home frame.) Feeds have no moderation of their own; each subscribed frame's applies.

## 10. Replies to senders

### 10.1 When a reply is sent

A reply is sent only when **all** of these hold:

1. Outgoing mail is configured (`KINFRAME_SMTP_HOST`) and `KINFRAME_EMAIL_REPLIES` is not `off`.
2. The mail was **authenticated** (§7). The reply goes to the authenticated `From:` address, never to `Reply-To:`,
   `Sender:`, other recipients or `MAIL FROM`. (Unauthenticated mail is never answered: no backscatter to forged
   addresses.)
3. It was not an automatic message (§8.1 step 3) and the sender is not blocked.
4. The reply kind is on for the address (table below) and within the rate limits (§10.3).

| Kind | When | Default | Example (English) |
|---|---|---|---|
| `delivered` | Something was delivered (also after a manager approved held mail) | per address `send_confirmations`, **on** | "Your 4 photos are on Oma's frame." (+ "1 video was longer than 5 minutes and was skipped. 2 photos were already there.") |
| `held` | The first held mail of a new pending sender (not repeated for their later mails) | always | "Your email to Oma's frame is waiting for the family to approve your address. We'll let you know." |
| `no_media` | Nothing usable | always | "We found no photos or videos attached. Links (iCloud, Google Drive, WeTransfer) aren't downloaded; attach the photos themselves." |
| `too_large` | Every file too large, too long or too big in pixels | always | "The video was longer than 5 minutes, so it wasn't sent. Shorter videos (or the app, which can trim) work." |
| `encrypted` | The mail was encrypted | always | "This email was encrypted, so we couldn't read the photos. Please send it without encryption." |

Never answered: unknown or paused addresses (the SMTP dialogue already told the sender's server, which bounces);
mail refused by DMARC; blocked senders; rejected senders ("Reject" is silent); delivery of a mail delivered by hand
from an unverified sender; duplicates.

### 10.2 Form

- **From**: `KINFRAME_SMTP_FROM` (the server's own sender, display name the server name). **To**: the sender.
  `Subject: Re: <original subject>` (at most 100 characters, the original's markers stripped first).
  `In-Reply-To`/`References` to the original `Message-ID`, `Auto-Submitted: auto-replied` (RFC 3834),
  `X-Auto-Response-Suppress: All`, `Precedence: auto_reply`.
- Plain text and simple HTML (the shop mailer's `multipart/alternative`), no images, no tracking, no content of the
  mail other than the subject and counts. The address's `name` ("Oma's frame") is used, never the frame's location or
  the members.
- Language: the matched account's locale; else the frame's `locale.language` setting (frame address) or the address
  owner's locale (feed address); else English. All strings exist in the server's 36 locales (`internal/i18n`).
- The mailer gains headers (`In-Reply-To`, `References`, `Auto-Submitted`) and a custom `MAIL FROM` stays the
  `From:` address (the operator's relay requires it); bounces of replies go to that mailbox, not back into the inbox.

### 10.3 Rate limits

- `delivered`: at most one per sender and address per 10 minutes; mails in between are counted into the next
  confirmation ("Your 9 photos from your last 3 emails are on Oma's frame") or, after 10 minutes without mail,
  summarised once.
- Failure notices (`no_media`, `too_large`, `encrypted`): at most 3 per sender per address per day.
- `held`: once per pending period (§5.2).
- Per address at most 100 replies a day, per server `KINFRAME_EMAIL_MAX_REPLIES_PER_HOUR` (500). Over a limit the
  reply is skipped (recorded), never queued for later.

### 10.4 The app block

Email is the fallback; the app is the better experience. Every reply carries an **app block** that says what the
sender misses and how to get the app.

- **What it says**: see when the photos arrive and when they've been seen; get reactions and replies back from the
  frame; send in seconds from the phone's photo picker; videos (with trimming) and Live Photos.
- **Links**: App Store and Google Play from discovery's `app_versions.<platform>.store_url` (the operator's
  `KINFRAME_APP_IOS_STORE_URL` / `KINFRAME_APP_ANDROID_STORE_URL`), the web app (`KINFRAME_PUBLIC_URL`), and the
  server's own landing page (`KINFRAME_PUBLIC_URL`) when no store link is configured. A platform without a link is
  left out.
- **Long, once**: the first reply to a sender address (per server, not per address; tracked in
  `email_reply_recipients.app_intro_sent_at`) gets the full block with the heading "Next time, try the app".
  Every later reply ends with a one-line footer: "Sending is easier in the kinframe app: <link>". A sender who never
  had a long one (because the first reply was rate limited) gets it with the next reply.
- **Already using the app**: a matched account with a registered app push endpoint (`unifiedpush`, `fcm`, `apns`) or
  an app request in the last 90 days gets no app block at all.
- **One tap into the family** (*join link*): in the long block, and in the footer while it is valid, a button "Join
  Oma's frame in the app" (feed: "Join Peeters kids in the app"). It is an ordinary invite link (`https://<server>/i/
  <token>` for frames, `/fi/<token>` for feeds; universal/app links already registered, so it opens the app when it is
  installed and the web, with store links, otherwise). The server makes it with the existing invite machinery, only
  when:
  - the mail was **authenticated** and the sender is **allowed** on that address (allow-list or approved; not
    *anyone may send* strangers, not unverified mail, not pending senders: their `held` notice gets the app block
    without a join button; the `delivered` mail after approval gets it);
  - the sender is not a member already (matched accounts get "Open kinframe" instead);
  - the address has `offer_app_invite` on (default on; managers can turn it off).
  The invite is single use, valid **30 days**, created by the address owner with the note "Jan (jan@…) via email",
  and **always needs a manager's approval**, whatever the frame's `invite_requires_approval` or the feed's setting:
  the join request carries `via: email`, `via_name` ("Jan · jan.peeters@telenet.be") and whether the account's
  verified email matches the address the link was mailed to (`via_matches`), so the manager sees "Jan · asked to
  join from the email link · jan.peeters@telenet.be ✓". These fields and the invite columns below are **generic for
  every outside channel** (shared with `telegram.md`): a later channel adds only an enum value. One live email invite per (address, sender) at a time, reused until it expires or is used; these invites are
  not listed in `listInvites`/`listFeedInvites` (they are the server's, not a member's) and are revoked with the
  address or the sender's allow-list entry.
- The app block is part of the reply, never a mail of its own, so it is under the same rate limits.

## 11. Notifications

All over the user websocket and, without an open websocket, as push, localized, IDs only (README §6.3).

| Event | Who | Websocket | Push key |
|---|---|---|---|
| An address was created, changed, paused, regenerated, taken over or its counts changed | The target's managers (§4) | `email_address.updated` | — |
| An address was deleted (also with its frame or feed) | The same | `email_address.removed` | — |
| A new pending sender (their first held mail) | The target's managers | `email_sender.pending` | `email_pending_sender` ("Rita wants to send 3 photos to Oma's frame") |
| More mail from a pending sender | The same | `email_sender.pending` (updated counts) | — |
| Held mail expires in 3 days and nobody decided | The same | — | `email_pending_sender` (text `email_pending_expiring`), once per pending sender |
| A pending sender was approved or rejected, a sender added, removed or blocked | The same | `email_sender.decided` | — |
| Reaction or quick reply on a mailed photo | The send's sender (matched account, or the owner unless `notify_reactions` is off) | `reaction` / `reply` (unchanged) | `reaction` / `reply` |
| A join request from an email link | Frame owner/admins (feed owner/admins), as any join request | `join_request` / `feed.join_request` | `join_request` / `feed_join_request` |

- **No push for delivered mail**: mailed photos are new on the frame; the managers don't need a push per mail, and
  email sends get no `delivered` push (the sender is told by mail). `send.updated` and receipts work as usual.
- Pending-sender pushes are at most one per pending sender per 7 days (re-pending after a reject included) and at most
  10 per address per day.

## 12. Limits, configuration and security

### 12.1 Limits and configuration

Operator limits (`Limits`, `PUT /v1/operator/limits`, environment defaults):

| Limit | Default | Env | Meaning |
|---|---|---|---|
| `max_email_addresses_per_target` | 5 | `KINFRAME_MAX_EMAIL_ADDRESSES_PER_TARGET` | Addresses per frame or feed (`409 limit_reached`) |
| `max_email_senders_per_address` | 200 | `KINFRAME_MAX_EMAIL_SENDERS_PER_ADDRESS` | Allowed and blocked senders per address |
| `max_email_message_bytes` | 50 MiB | `KINFRAME_MAX_EMAIL_MESSAGE_MB` | One mail, as received (SMTP `SIZE`, webhook body) |
| `max_email_messages_per_address_day` | 200 | `KINFRAME_MAX_EMAIL_MESSAGES_PER_ADDRESS_DAY` | Mails processed per address per day; more are dropped (`rate_limited`, logged, no reply) |
| `max_email_held_messages` | 100 | `KINFRAME_MAX_EMAIL_HELD_MESSAGES` | Held mails per address (and 10 per pending sender); the oldest are dropped first |

Existing limits apply per item: `max_upload_bytes` per file, `max_video_seconds` per video, `max_items_per_send` per
send and post.

Server configuration:

| Env | Default | Meaning |
|---|---|---|
| `KINFRAME_EMAIL_INBOX` | `off` | Turns the feature on (discovery `email_inbox`, needs a domain and an inbound path) |
| `KINFRAME_EMAIL_INBOX_DOMAIN` | — | The inbox domain (discovery `email_inbox_domain`) |
| `KINFRAME_EMAIL_SMTP_LISTEN` | empty | SMTP receiver address, e.g. `:2525`; empty: no receiver |
| `KINFRAME_EMAIL_SMTP_HOSTNAME` | the inbox domain | Greeting and EHLO name; should match the MX host name and the certificate |
| `KINFRAME_EMAIL_TLS_CERT_FILE`, `KINFRAME_EMAIL_TLS_KEY_FILE` | empty | STARTTLS certificate (PEM); without them no STARTTLS (a start-up warning) |
| `KINFRAME_EMAIL_REQUIRE_TLS` | `off` | Refuse `MAIL FROM` before STARTTLS (`530 5.7.0`) |
| `KINFRAME_EMAIL_PROXY_PROTOCOL` | `off` | Expect a PROXY protocol header (TCP proxy in front) |
| `KINFRAME_EMAIL_SMTP_MAX_SESSIONS` | 100 | Concurrent SMTP sessions |
| `KINFRAME_EMAIL_WEBHOOK_SECRET` (or `…_FILE`) | empty | Enables `/v1/inbound/email`; one secret, or `new,old` |
| `KINFRAME_EMAIL_WEBHOOK_TRUST_AUTHSERV_ID` | empty | Trust the provider's `Authentication-Results` with this authserv-id instead of checking (§6.3) |
| `KINFRAME_EMAIL_DNS_SERVER` | system resolver | Resolver for SPF/DKIM/DMARC (`host:port`); a local validating resolver is recommended |
| `KINFRAME_EMAIL_REPLIES` | `on` | Replies to senders (also needs `KINFRAME_SMTP_*`) |
| `KINFRAME_EMAIL_MAX_REPLIES_PER_HOUR` | 500 | Server-wide reply cap |
| `KINFRAME_EMAIL_POSTMASTER` | empty | Where `postmaster@`/`abuse@` mail goes (§6.5) |
| `KINFRAME_EMAIL_HELD_DAYS` | 14 (1–14) | How long held mail is kept |

Rate limits (API): creating addresses 30/h per user, regenerating 10/h per address, adding senders 120/h per user.

### 12.2 Security: no open relay, no amplifier, no DoS

- **No relaying is possible**: the receiver accepts only recipients on the inbox domain that exist (`RCPT` time),
  offers no `AUTH`, and nothing it receives is ever sent on. Outgoing mail is only the fixed templates of §10 (to an
  authenticated sender, rate limited) and the postmaster forward to one configured address.
- **No backscatter**: unknown recipients, paused addresses, size and DMARC-reject are refused during the dialogue, so
  bounces come from the sender's own server; unauthenticated mail is never answered.
- **Connections**: per IP (IPv6 per /64) at most 5 concurrent sessions, 30 connections a minute and 200 mails an
  hour; 20 refused recipients in an hour → `421` for an hour. Global session cap. Slow clients hit the command and
  data timeouts.
- **Webhook**: HMAC with replay protection (§6.3); without a configured secret the endpoint answers `404`. It is
  never reachable with user tokens and has no other effect than queuing mail.
- **The address is a capability, not the gate**: the random part (≥ 6 characters from 31, ~ 887 million per slug) and
  RCPT-time guessing limits make guessing impractical, and a guessed address still only leads to a held mail.
- **Address guessing via the pre-check**: `email:check` needs the webhook secret.

### 12.3 Hostile messages

| Threat | Defence |
|---|---|
| Huge mail | `SIZE` and a hard cap while streaming (SMTP), body limit (webhook) |
| Huge MIME trees, deep nesting | Depth 8, 200 parts, 64 KiB headers per part; beyond: the rest of the tree is skipped (logged) |
| Encoding amplification | base64 and quoted-printable only shrink; decoded bytes are capped at the message size; no other transfer encodings are decoded |
| Zip bombs, archives | Never unpacked; TNEF not decoded |
| Image bombs | Header-only dimension read, 100 megapixel cap, then the media pipeline's own limits |
| Malformed files | Sniffed and probed like uploads; failures skip that file only |
| HTML (scripts, tracking pixels, remote content) | Never rendered or fetched; only text is extracted with a tokenizer |
| Header injection in replies | Replies use fixed templates; the quoted subject has line breaks removed and is length-limited |
| Parser bugs | Parsing runs in the worker, per message under a 60 s time budget and with panics recovered (the message is dropped as `failed`) |

### 12.4 Privacy and retention

| Data | Kept | Until |
|---|---|---|
| Raw message (spool file) | Only while queued and processed | Deleted after processing, at the latest after 24 h |
| Extracted media | As items (delivered) or as masters and thumbnails without items (held) | Like any item / with the held message |
| Held message metadata (sender, display name, subject, caption, authentication result, file list) | Yes | 14 days, or the decision |
| `email_messages` (time, sender address and name, outcome, counts, message key, reply kind, send/post ids) | Yes | 30 days (dedupe, rate limits, *Recent mail*) |
| Reply recipients (`app_intro_sent_at`) | Sender address and time | 2 years after the last reply |
| Mail bodies, other headers, `To:`/`Cc:` lists | **Never** | — |

- **Logs** carry the address id, the sender's domain and a hash of the sender address, sizes, outcome codes and
  authentication results; never bodies, subjects, file names or full addresses.
- GPS and other metadata are stripped from mailed photos as for every upload; place names follow the send's sender's
  *share location names* setting (the owner's for non-account mail).
- The ready-made share message and every app screen say who sees the photos: "Everyone who can see Oma's frame".

## 13. Error codes

`EmailOutcome` (in *Recent mail*, `EmailMessage.outcome`, webhook results):

| Code | Meaning | Shown to managers |
|---|---|---|
| `delivered` | Sent or posted (possibly with skipped files) | "Jan · 4 photos delivered" |
| `held` | Waiting for approval (`unknown_sender` or `unverified_sender`) | "Rita · waiting for your approval" |
| `no_media` | Nothing usable found | "Jan · no photos found" |
| `too_large` | Every file too large or too long | "Jan · the video was too long" |
| `encrypted` | Encrypted mail | "Jan · encrypted, couldn't be read" |
| `duplicate` | The same mail again | "Jan · already received" |
| `blocked` | From a blocked sender, dropped | "Blocked sender · dropped" |
| `rejected_dmarc` | Failed the sender domain's `p=reject` policy | "Not really from example.org · refused" |
| `ignored` | An automatic message (out of office, bounce, calendar invite) | "Automatic message · ignored" |
| `rate_limited` | Over `max_email_messages_per_address_day` | "Too many emails today" |
| `failed` | Couldn't be processed (server error, parser time budget) | "Couldn't be processed" |

Webhook recipient outcomes: `accepted`, `unknown_recipient`, `paused`, `rejected_dmarc`, `too_large`.

Skip reasons per file (`EmailSkipReason`): `too_small`, `duplicate`, `unsupported`, `too_large`, `too_long`,
`over_limit`.

API problem codes (new): `duplicate_sender` (`409`, the address is already on the address's sender list; `detail`
holds its id). Existing ones used: `feature_disabled`, `limit_reached`, `rate_limited`, `validation_failed` (slug,
email), `missing_permission`, `not_found`, `already_decided` (a pending sender already decided), `invalid_transition`,
`unauthorized` (webhook signature), `upload_too_large` (webhook body).

## 14. Operator setup

### 14.1 DNS

```
in.kinframe.appmire.be.        MX   10 mx.kinframe.appmire.be.
mx.kinframe.appmire.be.        A    203.0.113.10
mx.kinframe.appmire.be.        AAAA 2001:db8::10
in.kinframe.appmire.be.        TXT  "v=spf1 -all"                  ; the inbox domain never sends
_dmarc.in.kinframe.appmire.be. TXT  "v=DMARC1; p=reject"           ; nobody may send as it
```

- The MX host's reverse DNS should resolve back to it (some senders check). The inbox domain must not be the domain
  of `KINFRAME_SMTP_FROM` (replies come from the server's normal sender, which needs its own SPF/DKIM).
- Webhook setups point the MX at the provider instead.

### 14.2 Port, firewall, TLS (SMTP receiver)

- Open TCP 25 inbound on the host firewall (and at the cloud provider; inbound 25 is normally allowed even where
  outbound 25 is blocked). Publish only that port from the container, e.g. compose `ports: ["25:2525"]` with
  `KINFRAME_EMAIL_SMTP_LISTEN=:2525` (the server runs as non-root and can't bind 25 itself).
- The client IP must reach the server for SPF: Docker's iptables port mapping keeps it; the userland proxy (IPv6 to an
  IPv4-only container, `userland-proxy` setups) replaces it with the bridge address. The server detects a private
  client IP, skips SPF (DKIM still works) and logs a start-up-style warning once. Use IPv6 in the container network,
  `network_mode: host`, or a TCP proxy with `KINFRAME_EMAIL_PROXY_PROTOCOL=on` to fix it.
- TLS: a certificate for the MX host name (`certbot certonly … -d mx.kinframe.appmire.be` next to the nginx vhosts),
  mounted read-only into the container; `KINFRAME_EMAIL_TLS_CERT_FILE=/etc/letsencrypt/live/…/fullchain.pem`, `…_KEY_FILE=
  …/privkey.pem` (readable by the container user). Renewals are picked up without a restart. MTA-STS and DANE are
  later.
- Check: `swaks --to oma-k7q2m9@in.kinframe.appmire.be --server mx.kinframe.appmire.be --tls --attach photo.jpg`, and
  that `swaks --to someone@gmail.com --server mx.kinframe.appmire.be` is refused (`550 5.7.1 Relaying denied`).
- `docs/deploy.md` and the Ansible role should get these steps (a `kinframe_email_*` group of variables, the port
  mapping and the certificate mount) when this is built.

### 14.3 Webhook example (Cloudflare Email Worker)

```js
export default {
  async email(message, env) {
    const body = new Uint8Array(await new Response(message.raw).arrayBuffer());
    const ts = Math.floor(Date.now() / 1000).toString();
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", body))]
      .map(b => b.toString(16).padStart(2, "0")).join("");
    const input = ["v1", ts, message.to, message.from, "", "", hash].join("\n");
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.KINFRAME_SECRET),
      { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const sig = [...new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(input)))]
      .map(b => b.toString(16).padStart(2, "0")).join("");
    const res = await fetch("https://kinframe.example.org/v1/inbound/email", {
      method: "POST", body,
      headers: { "Content-Type": "message/rfc822", "X-Kinframe-Timestamp": ts, "X-Kinframe-Signature": "v1=" + sig,
                 "X-Kinframe-Envelope-To": message.to, "X-Kinframe-Envelope-From": message.from },
    });
    if (res.status >= 500 || res.status === 429) throw new Error("kinframe unavailable"); // Cloudflare retries
    const out = await res.json();
    if (out.recipients?.[0]?.outcome !== "accepted") message.setReject("No such address");
  },
};
```

(A real worker calls `email:check` first and rejects before reading the body.)

## 15. Libraries and licences

The server is AGPL-3.0-or-later; all of these are compatible permissive licences.

| Need | Library | Licence |
|---|---|---|
| SMTP receiver | `github.com/emersion/go-smtp` | MIT |
| MIME parsing, charsets | `github.com/emersion/go-message` (+ `golang.org/x/text`, already used) | MIT (BSD-3) |
| DKIM verify, DMARC records, `Authentication-Results` | `github.com/emersion/go-msgauth` | MIT |
| SPF | `blitiri.com.ar/go/spf` | MIT |
| Organizational domain | `golang.org/x/net/publicsuffix` (already an indirect dependency) | BSD-3 |
| HTML to text (caption fallback) | `golang.org/x/net/html` | BSD-3 |
| PROXY protocol | `github.com/pires/go-proxyproto` | Apache-2.0 |

Outgoing replies use the existing `shop.SMTPMailer` (`KINFRAME_SMTP_*`), extended with extra headers; it moves to a
shared package (`internal/mail`) since it is no longer only the shop's.

## 16. Data model

Migration `00020_email_inbox.sql` (the highest existing one is `00019_photo_sources.sql`):

```sql
CREATE TABLE email_addresses (
    id                  uuid PRIMARY KEY,
    local_part          text        NOT NULL UNIQUE,              -- '<slug>-<random>', lower case
    slug                text        NOT NULL CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,22}[a-z0-9])?$'),
    random              text        NOT NULL CHECK (random ~ '^[23456789abcdefghjkmnpqrstuvwxyz]{6,16}$'),
    name                text        NOT NULL,                     -- label: "Oma's frame"
    frame_id            uuid REFERENCES frames ON DELETE CASCADE,
    feed_id             uuid REFERENCES feeds  ON DELETE CASCADE,
    owner_user_id       uuid REFERENCES users ON DELETE SET NULL, -- null only between owner loss and fallback
    created_by_user_id  uuid REFERENCES users ON DELETE SET NULL,
    status              text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused')),
    anyone_may_send     boolean     NOT NULL DEFAULT false,
    send_confirmations  boolean     NOT NULL DEFAULT true,
    offer_app_invite    boolean     NOT NULL DEFAULT true,
    notify_reactions    boolean     NOT NULL DEFAULT true,
    last_received_at    timestamptz,
    regenerated_at      timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((frame_id IS NOT NULL) <> (feed_id IS NOT NULL))
);
CREATE INDEX email_addresses_frame_idx ON email_addresses (frame_id) WHERE frame_id IS NOT NULL;
CREATE INDEX email_addresses_feed_idx ON email_addresses (feed_id) WHERE feed_id IS NOT NULL;

CREATE TABLE email_retired_local_parts (
    local_part  text PRIMARY KEY,
    retired_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_senders (
    id                 uuid PRIMARY KEY,
    address_id         uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    email              text        NOT NULL,                      -- lower case
    display_name       text,
    state              text        NOT NULL CHECK (state IN ('allowed', 'blocked', 'pending')),
    origin             text        NOT NULL CHECK (origin IN ('manual', 'approved', 'mail')),
    added_by_user_id   uuid REFERENCES users ON DELETE SET NULL,
    invite_id          uuid,                                      -- live email join link (frame or feed invite)
    pending_notified_at timestamptz,
    expiring_notified  boolean     NOT NULL DEFAULT false,
    last_message_at    timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (address_id, email)
);

CREATE TABLE email_inbound (                                      -- the queue; rows live minutes
    id              uuid PRIMARY KEY,
    spool_path      text        NOT NULL,
    path            text        NOT NULL CHECK (path IN ('smtp', 'webhook')),
    envelope_from   text,
    envelope_to     text[]      NOT NULL,
    client_ip       inet,
    helo            text,
    size_bytes      bigint      NOT NULL,
    auth            jsonb,                                        -- §7 results (null: webhook, checked by the worker)
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    received_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_held_messages (
    id              uuid PRIMARY KEY,
    address_id      uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    sender_id       uuid        NOT NULL REFERENCES email_senders ON DELETE CASCADE,
    reason          text        NOT NULL CHECK (reason IN ('unknown_sender', 'unverified_sender')),
    message_key     text        NOT NULL,
    sender_email    text        NOT NULL,                         -- as claimed
    sender_name     text,
    subject         text,
    caption         text,
    auth            jsonb       NOT NULL,
    media           jsonb       NOT NULL,                         -- [{media_id, kind, width, height, duration_ms, bytes, filename, captured_hint}]
    skipped         jsonb       NOT NULL DEFAULT '{}',
    received_at     timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    UNIQUE (address_id, message_key)
);
CREATE INDEX email_held_expiry_idx ON email_held_messages (expires_at);

CREATE TABLE email_messages (                                     -- 30-day log: dedupe, rate limits, Recent mail
    id              uuid PRIMARY KEY,
    address_id      uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    message_key     text        NOT NULL,
    sender_email    text,
    sender_name     text,
    authenticated   boolean     NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('delivered', 'held', 'no_media', 'too_large', 'encrypted',
                                    'duplicate', 'blocked', 'rejected_dmarc', 'ignored', 'rate_limited', 'failed')),
    items           integer     NOT NULL DEFAULT 0,
    skipped         jsonb       NOT NULL DEFAULT '{}',
    send_ids        uuid[]      NOT NULL DEFAULT '{}',
    reply_kind      text CHECK (reply_kind IN ('delivered', 'held', 'no_media', 'too_large', 'encrypted')),
    replied_at      timestamptz,
    received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX email_messages_address_idx ON email_messages (address_id, received_at DESC);
CREATE INDEX email_messages_key_idx ON email_messages (address_id, message_key);
CREATE INDEX email_messages_sender_idx ON email_messages (address_id, sender_email, received_at DESC);

CREATE TABLE email_reply_recipients (                             -- per sender address, server-wide (§10.4)
    email             text PRIMARY KEY,
    app_intro_sent_at timestamptz,
    last_reply_at     timestamptz NOT NULL
);

-- Credit: email joins the photo services.
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email')),
    ADD COLUMN email_address_id uuid REFERENCES email_addresses ON DELETE SET NULL,
    ADD COLUMN via_label text;
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email')),
    ADD COLUMN via_label text,                                    -- email: the sender's name
    ADD COLUMN via_email_address_id uuid REFERENCES email_addresses ON DELETE SET NULL;

-- Held media: processed masters without items (deleted with the held message).
ALTER TABLE media ADD COLUMN held_message_id uuid REFERENCES email_held_messages ON DELETE SET NULL;

-- Verified account emails (§8.2).
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX users_email_verified_unique ON users (lower(email))
    WHERE email_verified AND deleted_at IS NULL;                  -- a duplicate stays unverified, matched to nobody

-- Join links from replies (§10.4); generic per outside channel (email now, telegram next).
ALTER TABLE invites
    ADD COLUMN via text CHECK (via IN ('email')),                 -- set: a channel invite (not listed, always needs approval)
    ADD COLUMN via_ref text;                                      -- email: the email_senders id
ALTER TABLE feed_invites
    ADD COLUMN via text CHECK (via IN ('email')),
    ADD COLUMN via_ref text;
ALTER TABLE join_requests
    ADD COLUMN via_name text,                                     -- shown to managers: "Jan · jan@…"
    ADD COLUMN via_ref text,                                      -- email: the address the link was mailed to
    ADD COLUMN via_matches boolean;
ALTER TABLE feed_join_requests
    ADD COLUMN via_name text,
    ADD COLUMN via_ref text,
    ADD COLUMN via_matches boolean;
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite', 'email'));
ALTER TABLE feed_join_requests ADD COLUMN via text NOT NULL DEFAULT 'invite' CHECK (via IN ('invite', 'email'));
```

Notes:

- `invites`, `join_requests` (00001), `feed_invites`, `feed_join_requests` (00017) exist under these names;
  `sends_via_check`/`items_via_check` are the names PostgreSQL gave the inline `via` CHECKs added in 00019.
- Held media rows are not referenced by items; garbage collection of unreferenced media is a known gap (NOTES), so
  rejecting, deleting and expiring held mail deletes their masters and thumbnails explicitly.
- Email sends: `sends.via = 'email'`, `email_address_id`, `via_label`; the `delivered` push skips them, like source
  sends. Feed posts go through `createPosts`, so fanout, receipts and `feed_post.updated` are unchanged.
- Maintenance: queue rows older than 24 h are dropped, held messages after `expires_at`, `email_messages` after 30
  days, `email_reply_recipients` after 2 years, retired local parts never.
- Code: `internal/inbox` (receiver, webhook verification, authentication, MIME walk, media extraction, replies), the
  endpoints in `api/emailinbox.go`, the worker job `email_process`.

## 17. Release plan, decisions and open questions

### 17.1 Release plan

Phase **+**, planned as **v1.4 Email to frame**, after v1.3 Photo sources:

1. Server: tables, addresses and senders API, the webhook, authentication, MIME processing, sends and posts, held
   mail, replies (with the app block and join links), events and pushes; `deliver-email` subcommand; operator docs
   (`docs/deploy.md`, Ansible variables). Web: "Email photos to this frame/feed", pending senders with previews,
   *Recent mail*, the credit.
2. Server: the SMTP receiver (port 25, STARTTLS, limits), then switching the hosted server's inbox domain to it.
3. Family apps (Android, iOS): the same screens, share sheet text, pending-sender notifications, join requests "from
   the email link".
4. Frame (Android, browser frame): "Jan via email" in the credit. Older frames show the owner's name.

Later (not now): reactions and quick replies forwarded to non-account senders by mail; extra verified email addresses
per account (`/v1/me/emails`); MTA-STS/DANE for the inbox domain; ARC; inbound-parse adapters for Mailgun, Postmark
and SES; a per-address "only with this subject word" filter.

### 17.2 Decisions

Taken (2026-11-19), encoded above:

1. Targets: a frame (direct send) or a feed (post); managed by frame managers / feed owner and admins; several per
   target, each can be paused, renamed, regenerated or deleted (§2, §4, §5.1).
2. Format `<slug>-<random>@<inbox domain>`, random ≥ 6 characters from an unambiguous alphabet; a capability, not the
   only gate (§2, §12.2).
3. Allowed senders: an allow-list plus the target's members' verified account emails; others held as pending
   senders, approved once or rejected (optionally blocked); *anyone may send*, default off (§5.2, §8.2).
4. DMARC-aligned SPF or DKIM, else unknown sender, never matched (§7).
5. Content rules, caption, capture time; no usable media → reply and drop (§8.4, §8.5).
6. Attribution: matched account, else the owner with `ItemVia {service: email, label}`; one mail = one post (split per
   cap); moderation applies (§9).
7. Replies through the existing mailer, rate limited, never to unauthenticated senders (§10).
8. Both inbound paths: SMTP receiver and HMAC webhook (§6).
9. Clients with copy/share, settings, senders, pending senders with previews, notifications, "via email",
   discovery `email_inbox` + `email_inbox_domain` (§3, §11).
10. Held mail at most 14 days, raw mail not kept, logs without bodies (§12.4).
11. Email is the fallback, the app the better experience: every reply carries the app block, long once per sender
    address and a one-line footer afterwards, with a join link (an approval-required single-use invite) for
    authenticated, allowed senders only; mail in the target's language, all 36 locales (§10.4).

Made in this spec:

- **Reply policy**: only to authenticated `From:` addresses; never for automatic mail, blocked or rejected senders,
  unknown or paused addresses (refused in the dialogue instead); `delivered` coalesced to one per 10 minutes, failures
  3 a day, `held` once per pending period, caps per address and server (§10.1, §10.3).
- **DMARC handling**: alignment required whatever the policy and also without a DMARC record; `p=reject` failures are
  refused in the SMTP dialogue (dropped on the webhook), other failures are held as `unverified_sender` and can only
  be delivered by hand; DNS temporary errors are `451` (SMTP) or retried (webhook) (§7.3).
- **Webhook auth**: HMAC-SHA256 over a versioned canonical string covering the envelope and the body hash, timestamp
  ±5 minutes, replay cache, two secrets for rotation; own checks by default, the provider's
  `Authentication-Results` only when its authserv-id is configured; a signed recipient pre-check (§6.3).
- **Libraries**: go-smtp, go-message, go-msgauth, blitiri SPF, x/net publicsuffix and html, go-proxyproto; all
  permissive and AGPL-compatible (§15).
- **Ownership fallback**: an owner who loses rights is replaced automatically by the frame or feed owner, so mail is
  never stuck (§4.3).
- **Moderation**: mail the owner sends on behalf of a non-account sender is always moderated on moderated frames
  (§9.3).
- **Recipients in the dialogue**: unknown and paused addresses are refused at `RCPT`, so bounces come from the
  sender's own server (§6.2).
- **Grouping**: one mail to several addresses becomes one send when it is by the same account and credit (§8.6).
- **Held media are processed at once** (masters and thumbnails, no originals), so previews work and approval is
  instant (§8.4).
- **Regenerate** retires the old address at once (no grace period) and keeps the allow-list (§3, §5.1).
- **Members' emails are never shown** to managers (§4.4).
- **Channel-generic join links**: `JoinRequest.via` gains `email`, plus generic `via_name`/`via_matches`, and
  invites and join requests get generic `via`/`via_ref` columns, shared with `telegram.md` (§10.4, §16).
- **Recent mail** (30-day log) for managers, so "it didn't arrive" can be answered (§3, §13).

### 17.3 Open questions

1. **Moderation of owner-sent mail** (§9.3) deviates from "as for any send" (where an admin owner would skip the
   queue). It is safer for care-home frames, but on a moderated family frame an approved uncle's photos wait every
   time. Alternative: exempt allow-listed senders on frames whose moderation setting allows it.
2. **Mailing lists and ARC.** A list that rewrites `From:` to the list (common since DMARC) authenticates as the list,
   which then has to be approved as one sender ("Family list"); a list that keeps `From:` and breaks DKIM makes every
   mail unverified. Trusting ARC from well-known forwarders (Google Groups, Microsoft 365) would fix the latter, but
   needs an ARC verifier and a trust list.
3. **Senders that never authenticate** (small self-hosted domains without SPF/DKIM) are held every time and delivered
   by hand. A per-sender "trust this address without verification" switch would help them and make spoofing that
   sender possible; not offered for now.
4. **Provider adapters.** Only the raw-MIME webhook is built; Mailgun, Postmark and SES need small adapters (or an
   operator-written worker/lambda). Which ones to ship depends on what self-hosters use.
5. *(Decided 2026-11-19)* **Old frames' credit**: frames without the `via` field show the owner's name for
   non-account mail ("Anna" for Jan's photos). Accepted as a transitional limitation: frames self-update; a release
   note says so.
6. **Reactions to non-account senders** by mail ("Oma ❤ your photo") is out of scope now; it would be the strongest
   reason for relatives to keep mailing, and would need the same reply policy.
7. **Several emails per person**: a member who mails from a work address is not matched (it is a non-account sender
   after approval). Verified extra addresses per account (`/v1/me/emails`) would fix it.
8. **Logto email verification for social accounts**: whether every social connector only fills `primaryEmail` with a
   verified address needs checking per connector (Google and Apple do; Facebook and GitHub may not).
