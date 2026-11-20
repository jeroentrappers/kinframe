# kinframe server

Go backend for kinframe: REST API (`docs/api/openapi.yaml`), websockets, tus uploads, the per-frame change feed and the
media pipeline (libvips + ffmpeg). See `docs/api/README.md` for concepts and `NOTES.md` for decisions made where the
contract was silent.

## Quick start (dev auth, no Logto)

Requirements: Go 1.27 (`mise` installs it), Docker for Postgres, and `vips`/`vipsthumbnail`/`vipsheader` plus
`ffmpeg`/`ffprobe` on `$PATH` to process media (Debian: `apt install libvips-tools ffmpeg`).

```sh
make db-up      # Postgres 17 on 127.0.0.1:5433 (deploy/compose/docker-compose.dev.yml)
make run        # builds and runs on :8110 with KINFRAME_DEV_AUTH=1

# get a user token without Logto
curl -s localhost:8110/dev/token -d '{"email":"anna@example.org","name":"Anna"}'
# {"access_token":"…","token_type":"Bearer","expires_in":43200}
```

Add `"operator": true` to the `/dev/token` body for the `kinframe:operate` scope. The server refuses to start with
`KINFRAME_DEV_AUTH=1` when `KINFRAME_ENV=production`.

Whole stack in Docker instead: `docker compose -f ../deploy/compose/docker-compose.dev.yml --profile server up -d`
(add `--profile logto` for a local Logto on 127.0.0.1:8111/8112).

## Make targets

| Target | What it does |
|---|---|
| `make generate` | Regenerate `internal/api/gen` from `docs/api/openapi.yaml` (oapi-codegen strict server, via `tools/specprep`) |
| `make build` | Static binary in `bin/` |
| `make test` | All tests with `-race`. PostgreSQL 17 is embedded (downloaded once to `~/.cache/kinframe`); set `KINFRAME_TEST_DATABASE_URL` to use an existing server instead. Media tests skip when vips/ffmpeg are missing (set `KINFRAME_REQUIRE_MEDIA_TOOLS=1` to fail instead) |
| `make test-docker` | Same suite inside the Debian image toolchain, with vips and ffmpeg (`docker build --target test`) |
| `make lint` | `go vet` and staticcheck |
| `make run` | Run locally in dev auth mode against the compose Postgres |
| `make docker` | Runtime image (Debian trixie-slim, ffmpeg + libvips-tools, non-root uid 10001) |

The binary has five commands: `serve` (default), `migrate` (apply migrations and exit; `serve` also migrates on start),
`healthcheck` (used by the image's `HEALTHCHECK`), `bootstrap-logto` and `migrate-blobs` (both below).

## Configuration

All configuration is environment variables. Secrets never go in this repo; see `.env.example` for placeholders.

| Variable | Default | Meaning |
|---|---|---|
| `KINFRAME_ENV` | `development` | `development` or `production` |
| `KINFRAME_LISTEN` | `:8110` | Listen address |
| `KINFRAME_PUBLIC_URL` | `http://localhost:8110` | External base URL (links, tus `Location`, media URLs, discovery) |
| `KINFRAME_SERVER_NAME` | `kinframe` | Name shown by apps before sign-in (discovery) |
| `KINFRAME_DATABASE_URL` | — (required) | PostgreSQL URL |
| `KINFRAME_DATA_DIR` | `./data` | Media with the fs backend (`media/`), tus uploads (`uploads/`), pipeline scratch (`tmp/`), dev signing key |
| `KINFRAME_WEB_DIR` | — | Built web SPA; served with SPA fallback (`/f/{code}`, `/i/{token}`, …) |
| `KINFRAME_SIGNING_KEY` | generated in dev | 32+ random bytes (hex/base64); signs device tokens, dev tokens and media URLs. Required in production |
| `KINFRAME_OIDC_ISSUER` | — | Logto issuer, e.g. `https://auth.example.org/oidc` (required unless dev auth) |
| `KINFRAME_OIDC_AUDIENCE` | `<public url>/v1` | Logto API resource indicator (`aud`) |
| `KINFRAME_OIDC_JWKS_URL` | from OIDC discovery | Override the JWKS location |
| `KINFRAME_OIDC_WEB_CLIENT_ID` / `KINFRAME_OIDC_MOBILE_CLIENT_ID` / `KINFRAME_OIDC_DESKTOP_CLIENT_ID` | — | Published in discovery |
| `KINFRAME_LOGTO_CONFIG_FILE` | `<data dir>/logto.json` | Written by `bootstrap-logto` (`issuer`, `web_client_id`, `mobile_client_id`, `desktop_client_id`, `api_resource`). Read at start; fills in the OIDC settings above when their env vars are unset (env vars win) |
| `KINFRAME_LOGTO_M2M_ID` / `KINFRAME_LOGTO_M2M_SECRET` | — | The `kinframe bootstrap` M2M app (printed once by `bootstrap-logto`). When set, `DELETE /v1/me` also deletes the user's Logto account, with its social sign-in links; without them only the kinframe account is deleted |
| `KINFRAME_LOGTO_URL` | the issuer without `/oidc` | Where the server reaches Logto for that (e.g. `http://logto:3001`); requests carry the issuer's host in `Host`/`X-Forwarded-*` |
| `KINFRAME_DEV_AUTH` | off | Enable `POST /dev/token` (never in production) |
| `KINFRAME_TRUST_PROXY` | off | Use `X-Forwarded-For`/`X-Real-IP` for client IPs (rate limits) |
| `KINFRAME_WORKERS` | CPUs/2 | Media worker goroutines |
| `KINFRAME_VIPS`, `KINFRAME_VIPSHEADER`, `KINFRAME_FFMPEG`, `KINFRAME_FFPROBE` | names on `$PATH` | Tool binaries |
| `KINFRAME_MAX_ITEMS_PER_SEND` | `200` | Default limit (operator can change it at runtime) |
| `KINFRAME_MAX_FEEDS_PER_USER` | `20` | Feeds one account may own (operator can change it at runtime) |
| `KINFRAME_MAX_SUBSCRIPTIONS_PER_FEED` | `100` | Frames one feed may be shown on (operator can change it at runtime) |
| `KINFRAME_MAX_FEED_BACKFILL_ITEMS` | `5000` | Cap of a subscription's `history: all` (operator can change it at runtime) |
| `KINFRAME_MAX_VIDEO_SECONDS` | `300` | Default limit, after trim |
| `KINFRAME_MAX_UPLOAD_MB` | `2048` | Default tus upload limit |
| `KINFRAME_TRASH_RETENTION_DAYS` | `30` | Default trash retention |
| `KINFRAME_FEED_RETENTION_DAYS` | `30` | Removal entries kept in the change feed; older devices get `reset` |
| `KINFRAME_MIN_FRAME_APP_VERSION_CODE` | `0` (off) | Frames below this get `426 app_update_required` |
| `KINFRAME_APP_ANDROID_MIN_BUILD`, `KINFRAME_APP_IOS_MIN_BUILD`, `KINFRAME_APP_MACOS_MIN_BUILD` | unset (off) | Family apps (`X-Kinframe-App: kinframe-family-<platform>/…`) below this build (versionCode / `CFBundleVersion`) get `426 app_update_required` on user endpoints and a blocking update screen (API README §5.15) |
| `KINFRAME_APP_ANDROID_RECOMMENDED_BUILD`, `KINFRAME_APP_IOS_RECOMMENDED_BUILD`, `KINFRAME_APP_MACOS_RECOMMENDED_BUILD` | unset | Family apps below this build show an "update available" banner |
| `KINFRAME_APP_ANDROID_STORE_URL`, `KINFRAME_APP_IOS_STORE_URL`, `KINFRAME_APP_MACOS_STORE_URL` | unset (the app's default store page) | Where the update button goes; must be an `http(s)` URL |
| `KINFRAME_COMMAND_TIMEOUT_SECONDS` | `10` | Remote command wait before `504` |
| `KINFRAME_JANITOR_INTERVAL_SECONDS` | `600` | Trash purge, feed pruning, upload expiry |
| `KINFRAME_PUSH_DISABLED` | off | Disable push delivery |
| `KINFRAME_VAPID_PUBLIC_KEY` / `KINFRAME_VAPID_PRIVATE_KEY` | generated | WebPush VAPID key pair (base64url P-256 public point and private scalar). Set both or neither; when unset the server generates a pair once and keeps it in `<data dir>/vapid.json` (mode 0600). Keep it stable: browser subscriptions are bound to the public key |
| `KINFRAME_FCM_CREDENTIALS_FILE` | — | Firebase service account JSON key; enables FCM push to the family app’s store builds (needs push enabled) |
| `KINFRAME_APNS_KEY_FILE` | — | APNs auth key (.p8) for push to the iOS app (needs push enabled); with `KINFRAME_APNS_KEY_ID`, `KINFRAME_APNS_TEAM_ID` and `KINFRAME_APNS_TOPIC` (default `app.kinframe.family`) |
| `KINFRAME_VAPID_SUBJECT` | public URL if https, else `mailto:kinframe@localhost` | Contact push services see in the VAPID token (`mailto:` or `https://`) |
| `KINFRAME_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `KINFRAME_LOG_FORMAT` | `text` | `text` or `json` |
| `KINFRAME_VERSION` | `0.1.0` | Reported in discovery |
| `KINFRAME_FRAME_RELEASES_REPO` | `jeroentrappers/kinframe` | GitHub repo whose releases are mirrored for frame self-update (`off` disables) |
| `KINFRAME_FRAME_RELEASES_DIR` | — | Also serve every `*.apk` dropped in this directory (self-hosted builds; see below) |
| `KINFRAME_FRAME_RELEASES_POLL_MINUTES` | `30` | GitHub poll interval (also polled at start; ETag, rate limits respected) |
| `KINFRAME_FRAME_RELEASES_GITHUB_TOKEN` | — | Optional token for a higher GitHub API rate limit |
| `KINFRAME_FRAME_RELEASES_API` | `https://api.github.com` | GitHub API base (GitHub Enterprise, tests) |
| `KINFRAME_BLOB_BACKEND` | `fs` | Where processed media is stored: `fs` (`<data dir>/media`) or `s3` (see [Media storage](#media-storage)) |
| `KINFRAME_BLOB_FS_ROOT` | `<data dir>/media` | Root of the fs store |
| `KINFRAME_S3_ENDPOINT` | — | `host:port` or URL (`https://…` sets TLS) of the S3 API. Required for `s3` |
| `KINFRAME_S3_REGION` | `us-east-1` | Bucket region |
| `KINFRAME_S3_BUCKET` | — | Bucket. Required for `s3` |
| `KINFRAME_S3_ACCESS_KEY` / `_SECRET_KEY` | — | Credentials. Required for `s3` |
| `KINFRAME_S3_USE_SSL` | `true` | TLS when the endpoint has no scheme |
| `KINFRAME_S3_PATH_STYLE` | `false` | Path-style requests; MinIO/Silo and most self-hosted servers need `true` |
| `KINFRAME_S3_PREFIX` | — | Key prefix inside the bucket (share a bucket) |
| `KINFRAME_S3_CREATE_BUCKET` | `true` | Create the bucket at start when missing (needs the permission) |
| `KINFRAME_MEDIA_REDIRECT` | `off` | `presigned`: signed media URLs and avatars 302 to 15-minute presigned bucket URLs (s3 only) |
| `KINFRAME_S3_PUBLIC_ENDPOINT` | the endpoint | Endpoint presigned URLs point at, when clients reach the bucket under another name |
| `KINFRAME_APPLE_TEAM_ID` | — | Apple Developer Team ID the iOS family app is signed with. When set, `/.well-known/apple-app-site-association` lists `<team id>.<bundle id>` so `/f/<code>` and `/i/<token>` links open the iOS app. Unset: the file is not served |
| `KINFRAME_APPLE_BUNDLE_ID` | `app.kinframe.family` | Bundle id of the iOS app, for the same file (change it for your own build) |
| `KINFRAME_WEATHER` | `on` | `off` disables weather for all frames and `/v1/geocode` (discovery `weather: false`) |
| `KINFRAME_OPEN_METEO_URL` | `https://api.open-meteo.com` | Open-Meteo forecast API base (e.g. a self-hosted Open-Meteo) |
| `KINFRAME_OPEN_METEO_GEOCODING_URL` | `https://geocoding-api.open-meteo.com` | Open-Meteo geocoding API base (place search) |
| `KINFRAME_WEATHER_REFRESH_MINUTES` | `30` | How long fetched weather stays fresh (minimum 15); up to 5 min jitter is added |
| `KINFRAME_CALENDAR` | `on` | `off` disables calendar sync, sent events and countdowns (discovery `calendar: false`) |
| `KINFRAME_SHOP_FILE` | — | Optional pre-order shop config (YAML or JSON, see `docs/deploy.md` → *Pre-order shop*). Unset = no shop. Validated at startup |
| `KINFRAME_MOLLIE_API_KEY` | — | Mollie API key (`test_…`/`live_…`) for shops with `payments` (online orders); required then. Secret |
| `KINFRAME_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` | port `587` | The server's own mail (shop confirmations); the same variables configure Logto's sign-in mails. 465 = implicit TLS, 587 = STARTTLS required, other ports STARTTLS if offered. `_FROM` is required with `_HOST` |
| `KINFRAME_CALENDAR_SYNC_MINUTES` | `15` | Interval between syncs of each ICS/CalDAV source (minimum 5); up to 3 min jitter, back-off to 2 h on errors |
| `KINFRAME_SECRETS_KEY` | derived from `KINFRAME_SIGNING_KEY` | 32+ bytes (hex or base64) that encrypt stored calendar links and CalDAV passwords, photo source links, passwords and API keys and Google access tokens (AES-256-GCM, a separate HKDF key per kind). When unset, a key is derived from the signing key with HKDF, so rotating the signing key then also invalidates stored calendar credentials: affected calendars show `auth_failed` until the link or password is entered again. Set a dedicated key to rotate them independently |
| `KINFRAME_PHOTO_SOURCES` | `on` | `off` disables Immich and iCloud photo sources (discovery `photo_sources: false`; docs/spec/photo-sources.md) |
| `KINFRAME_ICLOUD_SOURCES` | `on` | `off` disables iCloud shared-album links only (best effort, unofficial endpoints) |
| `KINFRAME_PHOTO_SOURCE_SYNC_MINUTES` | `30` | Interval between syncs of each photo source (minimum 10; iCloud at least 60); up to 5 min jitter, back-off from 1 min to 6 h on errors |
| `KINFRAME_SOURCES_PRIVATE_NETWORKS` | `auto` | Whether user-entered URLs (photo sources **and calendars**) may reach private networks (RFC 1918, `fc00::/7`, CGNAT): `auto` only when the public URL's host is `localhost` or resolves to private addresses, `on` always (home server with Immich on the LAN), `off` never. Loopback and link-local (cloud metadata) are always blocked; plain `http://` only for allowed private addresses |
| `KINFRAME_SOURCES_ALLOWED_HOSTS` | — | Comma-separated host names, IPs or CIDRs that may use private addresses and `http://` regardless (`immich.lan,192.168.1.20`) |
| `KINFRAME_SOURCES_CA_FILE` | — | Extra trusted CA certificates (PEM) for a LAN Immich with a private CA (TLS is always verified) |
| `KINFRAME_MAX_PHOTO_SOURCES_PER_TARGET` / `KINFRAME_MAX_PHOTO_SOURCES_PER_USER` | `5` / `20` | Photo sources per album or feed, and per owner (operator can change them at runtime) |
| `KINFRAME_MAX_PHOTO_SOURCE_ITEMS` | `10000` | Live items per photo source; newer upstream items beyond it are skipped |
| `KINFRAME_MAX_PHOTO_SOURCE_RUN_MB` | `2048` | Bytes one sync run downloads; the rest follows 2 minutes later |
| `KINFRAME_GOOGLE_CLIENT_ID` / `KINFRAME_GOOGLE_CLIENT_SECRET` (or `KINFRAME_GOOGLE_CLIENT_SECRET_FILE`) | — | Google Cloud OAuth client (Web application) with the Photos Picker API enabled and the redirect URI `<public URL>/v1/oauth/google/callback`; enables "Add from Google Photos" (discovery `google_photos_import`). Set both or neither |
| `KINFRAME_MAX_GOOGLE_IMPORT_ITEMS` | `500` | Items per Google Photos import (the picker caps at 2000) |
| `KINFRAME_TELEGRAM_BOT_TOKEN` (or `…_FILE`) | — | The server's Telegram bot (BotFather token); enables the Telegram bridge (discovery `telegram`; docs/spec/telegram.md, see *Telegram* below). Secret |
| `KINFRAME_TELEGRAM_MODE` | `auto` | `webhook` (Telegram calls `/v1/telegram/webhook`), `polling` (the server long-polls), `off`; `auto` = webhook when the public URL is `https://` on port 443, 80, 88 or 8443 and not a local or private host, else polling |
| `KINFRAME_TELEGRAM_WEBHOOK_URL` | `<public URL>/v1/telegram/webhook` | Webhook address to register, behind unusual proxies (`https://` only) |
| `KINFRAME_TELEGRAM_API_URL` | `https://api.telegram.org` | Bot API base; a local `telegram-bot-api` server lifts the 20 MB download limit |
| `KINFRAME_TELEGRAM_LOCAL_FILES_DIR` | — | Where the local Bot API server's `--local` directory is mounted in this container (files are read and then deleted there) |
| `KINFRAME_TELEGRAM_MAX_DOWNLOAD_MB` | `20` (`2000` with a local server) | Largest file the bot downloads (discovery `telegram_max_file_bytes`); `max_upload_bytes` always applies |
| `KINFRAME_TELEGRAM_WORKERS` | `4` | Chats processed in parallel (each chat's updates in order) |
| `KINFRAME_TELEGRAM_WEBHOOK_IP_CHECK` | `off` | `on` also requires webhook calls to come from Telegram's published ranges (needs `KINFRAME_TRUST_PROXY` behind a proxy) |
| `KINFRAME_MAX_TELEGRAM_CHATS_PER_TARGET` / `KINFRAME_MAX_TELEGRAM_CHATS_PER_USER` | `5` / `20` | Connected Telegram chats per frame or feed, and per owner (operator can change them at runtime) |
| `KINFRAME_MAX_TELEGRAM_ITEMS_PER_CHAT_HOUR` | `300` | Photos and videos accepted per Telegram chat per hour |
| `KINFRAME_LINK_PREVIEWS` | `off` | `on` lets the server fetch previews of links in chat messages (title, description, image re-encoded to WebP; discovery `link_previews`; API README §5.19). Only public addresses are fetched, whatever `KINFRAME_SOURCES_PRIVATE_NETWORKS` says; 5 s and 1 MiB per page, 5 MiB per image, cached 7 days |
| `KINFRAME_FEDERATION` | `on` | Families on different servers (API README §5.22): frames here may follow families on other kinframe servers and families here may allow frames on other servers (each family opts in). `off` turns it all off: discovery `federation.enabled: false` and `remote_families: false`, every `/federation/v1/...` call answers `403`. Other servers are reached at public addresses only, with signed requests (Ed25519 key made at first start, stored in the database, sealed with `KINFRAME_SECRETS_KEY` when set, rotated every 180 days with the old key valid 7 more days) |
| `KINFRAME_FEDERATION_ALLOW` | — | Comma-separated hosts (`kinframe.example.org`, with `:port` when not 443): when set, this server federates with these servers only |
| `KINFRAME_FEDERATION_DEV_LOOPBACK` | off | Local development only: `1` lets this server reach other servers on `127.0.0.1` (two local servers on two ports, e.g. the web's two-server integration specs). Refused unless `KINFRAME_DEV_AUTH=1`; production servers reach public addresses only |
| `KINFRAME_REMOTE_HELP` | `off` | `on` lets the server operator start remote help: a temporary tailnet tunnel from a rooted frame to the operator's Headscale for ADB (docs/spec/remote-help.md, API README §5.23). Needs all of the variables below; discovery `remote_help` is true only then |
| `KINFRAME_HEADSCALE_URL` | — | Headscale control URL, e.g. `https://headscale.example.org` (also the frame's `--login-server`) |
| `KINFRAME_HEADSCALE_API_KEY` (or `_FILE`) | — | Headscale API key (`headscale apikeys create`), sent as `Authorization: Bearer` |
| `KINFRAME_HEADSCALE_USER` | — | Headscale user frames register under, e.g. `kinframe-frames` (looked up to its id) |
| `KINFRAME_REMOTE_HELP_ADB_KEY` | — | The operator's ADB public key, one line of `~/.android/adbkey.pub`; frames allow it for the session only |
| `KINFRAME_REMOTE_HELP_POLICY_CHECKED` | — | `1`: you confirm the Headscale ACL of docs/spec/remote-help.md §5.3 is in place (support devices reach frames on port 5555 only, frames reach nothing). Sessions can't start without it |
| `KINFRAME_REMOTE_HELP_TAILSCALE_VERSION` | `1.104.7` | Tailscale release frames download (static linux arm/arm64 tarballs from pkgs.tailscale.com); another version needs `KINFRAME_REMOTE_HELP_TAILSCALE_SHA256_ARM` and `…_ARM64`. Pre-auth keys are sealed at rest with `KINFRAME_SECRETS_KEY` (else a key derived from the signing key) |
| `KINFRAME_SERVER_LABEL` | the host of `KINFRAME_PUBLIC_URL` | This server's name for apps signed in to several servers (discovery `server_name`, the server chip) and for other servers ("Oma's frame (Appmire)") |

### Calendars

`internal/calendar` syncs each frame's calendar sources on the server (README §5.8 of the API docs): ICS/webcal links
with `If-None-Match`/`If-Modified-Since`, and CalDAV (`/.well-known/caldav` → principal → calendar home → a
`calendar-query` REPORT limited to the frame's window). Documents are parsed with go-ical and recurrences expanded
with rrule-go in the event's own wall clock (RRULE, RDATE, EXDATE, RECURRENCE-ID overrides, IANA/Windows/VTIMEZONE
zones), so DST changes never shift a series. The last fetched document is kept gzipped, so a moving window (every
midnight, or a settings change) re-expands without a refetch. Each frame's sources are merged into one `calendar`
change, emitted only when what the frame shows changed. Frames never contact calendar servers. The server fetches
whatever URL a member with `change_settings` enters, including LAN addresses (a Nextcloud next to the server is a
normal setup), so only give that permission to people you trust.

### Telegram

One bot per server (docs/spec/telegram.md). Families connect a Telegram group (or a private chat with the bot) to a
frame or a feed from the app; the bot turns each person's burst of photos into one send or post, confirms with a 👍
reaction and mirrors the frame's reactions.

1. In Telegram, talk to [@BotFather](https://t.me/BotFather): `/newbot`, pick a name and a username, copy the token
   into `KINFRAME_TELEGRAM_BOT_TOKEN` (or a file named by `KINFRAME_TELEGRAM_BOT_TOKEN_FILE`).
2. Recommended: `/setprivacy` → the bot → **Disable**. Bots in privacy mode only see commands in groups, so photos
   stay invisible unless the family makes the bot an admin of the group. Telegram applies the change only to groups
   the bot joins afterwards (a group that added it before must remove and add it again); the server notices it at
   its next start (discovery `telegram_privacy_mode`), and chats that can't see photos show `privacy_mode`.
3. Optionally `/setuserpic` and `/setdescription`; the server registers the command menu itself (every language).
4. With a public `https://` `KINFRAME_PUBLIC_URL` the server registers its webhook at start (the secret is derived
   from `KINFRAME_SECRETS_KEY`, or the signing key, so all processes agree); otherwise it long-polls. Only one process
   (a Postgres advisory lock) polls and processes updates; any process may receive webhook calls.
5. Files: Telegram's cloud Bot API lets bots download at most 20 MB. To accept up to 2 GB, run Telegram's
   `telegram-bot-api` server with your own `api_id`/`api_hash` (my.telegram.org) in `--local` mode, point
   `KINFRAME_TELEGRAM_API_URL` at it and mount its working directory into the kinframe container
   (`KINFRAME_TELEGRAM_LOCAL_FILES_DIR`). The server calls `logOut` on the cloud API once before switching.

Replacing the bot (a different bot id) puts every connected chat in `needs_attention (bot_changed)`; rotating the
token of the same bot (`/revoke`) is transparent. Removing the token hides Telegram; connected chats wait in the
database and resume when the same bot comes back.

### Email to frame

Frames and feeds can get inbox addresses (`<slug>-<random>@<inbox domain>`, docs/spec/email-inbox.md): allowed senders
mail photos to them. Off by default; it needs an inbox domain and at least one way in.

| Variable | Default | Meaning |
|---|---|---|
| `KINFRAME_EMAIL_INBOX` | `off` | `on` turns the feature on (discovery `email_inbox`, `email_inbox_domain`) |
| `KINFRAME_EMAIL_INBOX_DOMAIN` | — | The inbox domain, used for nothing else (its MX points at this server or at the provider in front of the webhook). Must not be the domain of `KINFRAME_SMTP_FROM` |
| `KINFRAME_EMAIL_SMTP_LISTEN` | empty | Built-in SMTP receiver, e.g. `:2525` (published as port 25). Receive only: no AUTH, no relaying |
| `KINFRAME_EMAIL_SMTP_HOSTNAME` | the inbox domain | Greeting/EHLO name; match the MX host and the certificate |
| `KINFRAME_EMAIL_TLS_CERT_FILE` / `_KEY_FILE` | empty | STARTTLS certificate (PEM), re-read when the files change; without them no STARTTLS (a start-up warning) |
| `KINFRAME_EMAIL_REQUIRE_TLS` | `off` | Refuse `MAIL FROM` before STARTTLS |
| `KINFRAME_EMAIL_PROXY_PROTOCOL` | `off` | Require a PROXY protocol v1/v2 header (TCP proxy in front) |
| `KINFRAME_EMAIL_SMTP_MAX_SESSIONS` | `100` | Concurrent SMTP sessions (per client address: 5, 30 connections/min, 200 mails/h) |
| `KINFRAME_EMAIL_WEBHOOK_SECRET` (or `_FILE`) | empty | Enables `POST /v1/inbound/email` and `…:check` (HMAC-SHA256); `new,old` rotates |
| `KINFRAME_EMAIL_WEBHOOK_TRUST_AUTHSERV_ID` | empty | Trust the provider's own `Authentication-Results` (this authserv-id) instead of checking SPF/DKIM/DMARC |
| `KINFRAME_EMAIL_DNS_SERVER` | system resolver | `host:port` for SPF/DKIM/DMARC lookups (a local validating resolver is recommended) |
| `KINFRAME_EMAIL_REPLIES` | `on` | Replies to authenticated senders (also needs `KINFRAME_SMTP_*`) |
| `KINFRAME_EMAIL_MAX_REPLIES_PER_HOUR` | `500` | Server-wide reply cap |
| `KINFRAME_EMAIL_POSTMASTER` | empty | Where mail to `postmaster@`/`abuse@` the inbox domain is forwarded (as an attachment); empty: refused |
| `KINFRAME_EMAIL_HELD_DAYS` | `14` | How long held mail is kept (1–14) |
| `KINFRAME_MAX_EMAIL_ADDRESSES_PER_TARGET`, `_SENDERS_PER_ADDRESS`, `_MESSAGE_MB`, `_MESSAGES_PER_ADDRESS_DAY`, `_HELD_MESSAGES` | 5, 200, 50, 200, 100 | Limit defaults (`PUT /v1/operator/limits`) |

Operator steps for the SMTP receiver: an MX record for the inbox domain pointing at the server's host name (with
`A`/`AAAA` and matching reverse DNS), `v=spf1 -all` and `_dmarc … p=reject` on the inbox domain (it never sends),
TCP 25 open inbound (host firewall and cloud provider), a container port mapping such as `25:2525`, and a certificate
for the MX host name mounted read-only. The client IP must reach the server for SPF: with Docker's userland proxy the
server sees a bridge address, skips SPF (DKIM still works) and warns once; use IPv6 in the container network, host
networking or a TCP proxy with `KINFRAME_EMAIL_PROXY_PROTOCOL=on`. Test with `swaks --to <address> --server <mx>
--tls --attach photo.jpg`, and check that `swaks --to someone@gmail.com --server <mx>` is refused (`550 5.7.1`).

Without port 25, point the MX at a provider and post each raw message to the webhook (docs/spec/email-inbox.md §6.3,
§14.3), or pipe from your own Postfix: `kinframe-server deliver-email --recipient ${recipient} --sender ${sender}
--client-ip ${client_address} --helo ${client_helo}` (reads the message on stdin; needs
`KINFRAME_EMAIL_WEBHOOK_SECRET` and `KINFRAME_PUBLIC_URL` or `--url`; exit 75 = try again, 67 = unknown recipient).

## Media storage

Processed media (masters, frame renditions, posters, thumbnails, avatars) lives in a blob store
(`internal/blob`): the local filesystem (`fs`, default) or any S3-compatible bucket (`s3`: AWS S3, MinIO/Silo,
Hetzner Object Storage, Backblaze B2, Cloudflare R2, …) via minio-go. Originals are never stored: tus uploads stay in
`<data dir>/uploads` and are deleted once the master exists. vips and ffmpeg need real files, so each pipeline job
works in its own `<data dir>/tmp/job-*` directory (masters are downloaded there when the store is remote) and removes
it on every exit path; the janitor prunes anything a crash left behind after 6 h.

Serving is unchanged for clients: the server streams objects with `Range`, `ETag` and `If-None-Match` (ranged GETs
against the bucket, so a `Range` request or a 304 never downloads the whole object). Frame renditions keep their
sha256 ETag; other files get the store's ETag (fs: modification time and size as before; s3: the object ETag, so
caches revalidate once after a switch). With `KINFRAME_MEDIA_REDIRECT=presigned` signed media URLs and avatars
redirect to presigned bucket URLs instead, for buckets clients can reach; frame downloads always stream.

Local development: `docker compose -f ../deploy/compose/docker-compose.dev.yml --profile s3 up -d` starts Silo on
127.0.0.1:9100 (console 9101); the variables are in `.env.example`. Tests start their own Silo container through the
Docker CLI (falling back to `sg docker -c` for shells that predate docker group membership) and skip the S3 cases
without Docker; `KINFRAME_REQUIRE_S3=1` makes them fail instead, `KINFRAME_TEST_S3_ENDPOINT` (+ `_ACCESS_KEY`,
`_SECRET_KEY`) uses an existing server.

### migrate-blobs

`kinframe-server migrate-blobs --from fs --to s3` copies every object between the configured stores (either
direction). It reads only the blob variables (`KINFRAME_DATA_DIR`/`KINFRAME_BLOB_FS_ROOT` and `KINFRAME_S3_*`;
`KINFRAME_BLOB_BACKEND` is ignored), so it runs next to a live server. Objects already in the destination with the
same size and sha256 are skipped, so it is idempotent and resumable; every copy is re-read and its sha256 checked.
Progress is logged every 10 s; it exits non-zero when any object failed (run it again to retry).

| Flag | Default | Meaning |
|---|---|---|
| `--from`, `--to` | `fs`, `s3` | Backends |
| `--dry-run` | off | Only report what would be copied |
| `--prefix` | — | Only keys with this prefix (`media/`, `items/`, `avatars/`) |
| `--workers` | `4` | Parallel copies |
| `--no-verify` | off | Skip re-reading copies (size is still checked) |
| `-v` | off | Log every object |

Switching a server: run it once while the server still uses `fs`, switch `KINFRAME_BLOB_BACKEND=s3` and restart,
then run it again to pick up what was written in between. The fs copy stays until you delete it. The production
procedure is in `docs/deploy.md`.

## Weather

Weather comes from [Open-Meteo](https://open-meteo.com): free, no API key, data under CC BY 4.0 ("Weather data by
Open-Meteo.com"). The server fetches it for frames with a location and the weather widget on, caches it in
PostgreSQL per location rounded to 2 decimals and pushes it to frames as `weather` changes
(`docs/api/README.md` §5.4). With 30-minute refreshes each distinct location costs about 50 requests a day, well under
Open-Meteo's free limit of 10 000 a day for non-commercial use. Failed fetches back off from 1 minute up to the refresh
interval and keep the last good data. Place search (`GET /v1/geocode`) is cached in memory for a day.

## Frame app releases (self-update)

Frames update only from their own server (`GET /v1/device/update`, docs/api/README.md §5.7). The server keeps a
mirror in `<data dir>/frame-releases/`:

- **GitHub**: releases of `KINFRAME_FRAME_RELEASES_REPO` with an asset `kinframe-frame-<name>-<code>.apk` and its
  `kinframe-frame-<name>-<code>.apk.sha256` (written by `.github/workflows/release-frame.yml`). Prereleases are the
  beta channel. The newest 3 versions plus the newest stable one are kept; a release deleted on GitHub is withdrawn
  from the mirror at the next poll. Mirrored APKs survive restarts and keep being served while GitHub is unreachable.
- **Directory**: any `*.apk` in `KINFRAME_FRAME_RELEASES_DIR` is served as is. A version name with a `-` suffix
  (`0.3.0-beta.1`) is beta. An optional `<file>.apk.json` sidecar can set `{"prerelease": true, "release_notes": "…"}`.
  The APK must be signed with the same key as the app installed on the frames, or frames refuse it.

Package name, version code and `minSdkVersion` are always read from the APK's binary `AndroidManifest.xml` (a small
parser in `internal/releases`, no aapt needed). APKs that are not `app.kinframe.frame`, whose sha256 does not match
the `.sha256` asset, or whose version code disagrees with the asset name are refused.

## Logto bootstrap

`kinframe-server bootstrap-logto` configures a Logto OSS 1.43 instance for kinframe through Logto's public APIs and
writes `KINFRAME_LOGTO_CONFIG_FILE`. It is idempotent: every step compares with what Logto has and a second run logs
"nothing changed". Logs go to stderr; stdout carries only the one-time M2M credentials. The deployment procedure is in
`docs/deploy.md` ("First deploy: Logto bootstrap").

What it ensures: API resource `<KINFRAME_PUBLIC_URL>/v1` (1 h tokens, scope `kinframe:operate`); user role
`kinframe-operator` with that scope (assigned to `KINFRAME_OPERATOR_EMAILS` that have an account); SPA `kinframe web`;
native app `kinframe app`; the SMTP connector with English code emails; sign-in experience (passwordless email codes
for sign-up and sign-in, passkeys, required `name` at sign-up, language from the browser); social sign-in connectors
for the providers configured below, shown on the sign-in page in the order Apple, Google, Microsoft, Facebook, GitHub;
the kinframe look on Logto's pages (logo, colours, dark mode, `internal/logtoboot/signin.css`); a custom
access-token script that adds `name` and `email` to tokens for the kinframe API only; and, with
`KINFRAME_LOGTO_GLITCHTIP_URL`, a Traditional web app `GlitchTip` (redirect `<url>/accounts/oidc/logto/login/callback/`)
whose client ID, a client secret (Logto's existing one is reused; a new one only when none stays valid for a day),
the issuer and the operators' Logto user IDs go to `KINFRAME_LOGTO_GLITCHTIP_FILE` (mode 0600) for the glitchtip
Ansible role (`docs/spec/crash-reporting.md` §1).

Social connectors get fixed IDs, so their callback URLs are known before the first run:
`<KINFRAME_LOGTO_ENDPOINT>/callback/{apple,google,microsoft,facebook,github}`. A provider is on when its client ID is
set; changing a value updates the connector; removing it leaves the connector and its button alone unless
`KINFRAME_SOCIAL_REMOVE_UNCONFIGURED=true`, which deletes the connectors kinframe created for unset providers. Only
identity scopes are requested (Google `openid profile email`, Apple `name email`, Microsoft Graph `User.Read`, Facebook
`email,public_profile`, GitHub `read:user user:email`). Secrets are never logged. Provider registration is described
in `docs/deploy.md` ("Social sign-in").

The branding loads `/logo-light.svg`, `/logo-dark.svg`, `/favicon.svg` and `/fonts/fraunces-latin-wght-normal.woff2`
from `KINFRAME_PUBLIC_URL` (files in `web/static`; the server sends `Access-Control-Allow-Origin: *` for `/fonts/`).
The wordmark SVGs are generated by `tools/brand/mklogo.py`.

Management API access, in order of preference:
- `KINFRAME_LOGTO_M2M_ID`/`_SECRET`: client-credentials token. Used when set.
- `KINFRAME_LOGTO_ADMIN_USERNAME`/`_PASSWORD`: signs in to the admin tenant like the console does. On a fresh Logto
  (admin sign-in mode `Register`) it **registers** that account first, which makes it the console admin and closes
  registration. With admin credentials the run also ensures an M2M app `kinframe bootstrap` with the "Logto Management
  API access" role and prints its ID and secret once when it creates it.

| Variable | Default | Meaning |
|---|---|---|
| `KINFRAME_PUBLIC_URL` | — (required) | kinframe's external base URL; gives the API resource, redirect URIs and CORS origin |
| `KINFRAME_LOGTO_ENDPOINT` | — (required) | Logto's `ENDPOINT`, exactly as Logto is configured |
| `KINFRAME_LOGTO_URL` | the endpoint | Where to reach Logto core from here (e.g. `http://logto:3001`); requests carry the endpoint's host in `Host`/`X-Forwarded-*` |
| `KINFRAME_LOGTO_ADMIN_ENDPOINT` | — | Logto's `ADMIN_ENDPOINT`, exactly; required with admin credentials |
| `KINFRAME_LOGTO_ADMIN_URL` | the admin endpoint | Where to reach the admin port (e.g. `http://logto:3002`) |
| `KINFRAME_LOGTO_ADMIN_USERNAME` / `_PASSWORD` | — | Console admin (created on a fresh Logto) |
| `KINFRAME_LOGTO_M2M_ID` / `_SECRET` | — | Management API app, printed by the first run |
| `KINFRAME_OPERATOR_EMAILS` | — | Comma-separated; existing users get `kinframe-operator` |
| `KINFRAME_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` | port `587` | Logto's email connector. 465 = implicit TLS, 587 = STARTTLS required, other ports STARTTLS if offered. Required unless Logto already has an email connector. The password is never logged |
| `KINFRAME_SOCIAL_APPLE_CLIENT_ID` | — | Sign in with Apple **Services ID**. Logto's Apple connector needs nothing else (`_TEAM_ID`, `_KEY_ID`, `_PRIVATE_KEY`, `_CLIENT_SECRET` are ignored with a warning) |
| `KINFRAME_SOCIAL_GOOGLE_CLIENT_ID` / `_SECRET` | — | Google OAuth web client |
| `KINFRAME_SOCIAL_MICROSOFT_CLIENT_ID` / `_SECRET` / `_TENANT` | tenant `common` | Microsoft Entra app (connector `azuread-universal`). `common` = personal and work/school accounts; also `consumers`, `organizations`, a tenant ID or domain |
| `KINFRAME_SOCIAL_FACEBOOK_CLIENT_ID` / `_SECRET` | — | Meta app with Facebook Login |
| `KINFRAME_SOCIAL_GITHUB_CLIENT_ID` / `_SECRET` | — | GitHub OAuth app |
| `KINFRAME_SOCIAL_REMOVE_UNCONFIGURED` | `false` | Delete kinframe's connectors for providers whose env vars are gone |
| `KINFRAME_SOCIAL_LINK_BY_EMAIL` | `true` | Link a first social sign-in to the existing account with the same verified email (Logto `automaticAccountLinking`); `false` makes Logto ask the user |
| `KINFRAME_LOGTO_BRANDING` | `true` | Apply the kinframe logo, colours and CSS to Logto's sign-in pages |
| `KINFRAME_LOGTO_PASSWORD_SIGNIN` | `false` | Let accounts that have a password (set by an admin, e.g. an App Review account) sign in with it next to email codes; sign-up stays passwordless |
| `KINFRAME_TERMS_URL` / `KINFRAME_PRIVACY_URL` | — | Legal links on the sign-up page; none when unset |
| `KINFRAME_LOGTO_CONFIG_FILE` | `$KINFRAME_DATA_DIR/logto.json` | Output file |
| `KINFRAME_LOGTO_GLITCHTIP_URL` | — | GlitchTip's origin (e.g. `https://errors.example.org`); when set, operators can sign in to GlitchTip with Logto |
| `KINFRAME_LOGTO_GLITCHTIP_FILE` | `$KINFRAME_DATA_DIR/glitchtip-oidc.json` | GlitchTip sign-in settings, with the client secret (mode 0600) |

## Layout

```
cmd/kinframe-server   main: serve, migrate, healthcheck, bootstrap-logto, migrate-blobs, deliver-email
internal/api          HTTP handlers (strict server impl + tus, websockets, media), validation, auth middleware
internal/api/gen      generated by oapi-codegen (do not edit), plus the prepared spec used for request validation
internal/app          wiring and background loops (workers, janitor)
internal/logtoboot    bootstrap-logto: Logto Management API setup
internal/auth         Logto JWT validation, dev tokens, device tokens, argon2id, signed URLs
internal/store        PostgreSQL access (pgx), change feed, job queue
internal/db           pool, embedded goose migrations
internal/blob         blob stores (filesystem, S3) for processed media, migration between them
internal/media        sniffing, geometry, vips/ffmpeg wrappers
internal/worker       media pipeline on the Postgres job queue (FOR UPDATE SKIP LOCKED)
internal/ws           websocket hub (device + user), commands
internal/settings     frame settings defaults and RFC 7396 merge patch
internal/notify       websocket/push fan-out (UnifiedPush delivery)
internal/releases     frame app release mirror (GitHub Releases + drop-in directory), APK manifest parser
internal/weather      Open-Meteo client, per-location weather cache and refresh loop, place search
internal/mailin       email to frame: SMTP receiver, webhook signatures, SPF/DKIM/DMARC, MIME walk, captions, replies
internal/e2e          end-to-end tests over real HTTP
tools/                specprep (OpenAPI 3.1 → codegen input), respgen
```
