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

The binary has four commands: `serve` (default), `migrate` (apply migrations and exit; `serve` also migrates on start),
`healthcheck` (used by the image's `HEALTHCHECK`) and `bootstrap-logto` (below).

## Configuration

All configuration is environment variables. Secrets never go in this repo; see `.env.example` for placeholders.

| Variable | Default | Meaning |
|---|---|---|
| `KINFRAME_ENV` | `development` | `development` or `production` |
| `KINFRAME_LISTEN` | `:8110` | Listen address |
| `KINFRAME_PUBLIC_URL` | `http://localhost:8110` | External base URL (links, tus `Location`, media URLs, discovery) |
| `KINFRAME_SERVER_NAME` | `kinframe` | Name shown by apps before sign-in (discovery) |
| `KINFRAME_DATABASE_URL` | — (required) | PostgreSQL URL |
| `KINFRAME_DATA_DIR` | `./data` | Media (`media/`), tus uploads (`uploads/`), dev signing key |
| `KINFRAME_WEB_DIR` | — | Built web SPA; served with SPA fallback (`/f/{code}`, `/i/{token}`, …) |
| `KINFRAME_SIGNING_KEY` | generated in dev | 32+ random bytes (hex/base64); signs device tokens, dev tokens and media URLs. Required in production |
| `KINFRAME_OIDC_ISSUER` | — | Logto issuer, e.g. `https://auth.example.org/oidc` (required unless dev auth) |
| `KINFRAME_OIDC_AUDIENCE` | `<public url>/v1` | Logto API resource indicator (`aud`) |
| `KINFRAME_OIDC_JWKS_URL` | from OIDC discovery | Override the JWKS location |
| `KINFRAME_OIDC_WEB_CLIENT_ID` / `KINFRAME_OIDC_MOBILE_CLIENT_ID` | — | Published in discovery |
| `KINFRAME_LOGTO_CONFIG_FILE` | `<data dir>/logto.json` | Written by `bootstrap-logto` (`issuer`, `web_client_id`, `mobile_client_id`, `api_resource`). Read at start; fills in the four OIDC settings above when their env vars are unset (env vars win) |
| `KINFRAME_DEV_AUTH` | off | Enable `POST /dev/token` (never in production) |
| `KINFRAME_TRUST_PROXY` | off | Use `X-Forwarded-For`/`X-Real-IP` for client IPs (rate limits) |
| `KINFRAME_WORKERS` | CPUs/2 | Media worker goroutines |
| `KINFRAME_VIPS`, `KINFRAME_VIPSHEADER`, `KINFRAME_FFMPEG`, `KINFRAME_FFPROBE` | names on `$PATH` | Tool binaries |
| `KINFRAME_MAX_ITEMS_PER_SEND` | `200` | Default limit (operator can change it at runtime) |
| `KINFRAME_MAX_VIDEO_SECONDS` | `300` | Default limit, after trim |
| `KINFRAME_MAX_UPLOAD_MB` | `2048` | Default tus upload limit |
| `KINFRAME_TRASH_RETENTION_DAYS` | `30` | Default trash retention |
| `KINFRAME_FEED_RETENTION_DAYS` | `30` | Removal entries kept in the change feed; older devices get `reset` |
| `KINFRAME_MIN_FRAME_APP_VERSION_CODE` | `0` (off) | Frames below this get `426 app_update_required` |
| `KINFRAME_COMMAND_TIMEOUT_SECONDS` | `10` | Remote command wait before `504` |
| `KINFRAME_JANITOR_INTERVAL_SECONDS` | `600` | Trash purge, feed pruning, upload expiry |
| `KINFRAME_PUSH_DISABLED` | off | Disable push delivery |
| `KINFRAME_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `KINFRAME_LOG_FORMAT` | `text` | `text` or `json` |
| `KINFRAME_VERSION` | `0.1.0` | Reported in discovery |
| `KINFRAME_FRAME_RELEASES_REPO` | `jeroentrappers/kinframe` | GitHub repo whose releases are mirrored for frame self-update (`off` disables) |
| `KINFRAME_FRAME_RELEASES_DIR` | — | Also serve every `*.apk` dropped in this directory (self-hosted builds; see below) |
| `KINFRAME_FRAME_RELEASES_POLL_MINUTES` | `30` | GitHub poll interval (also polled at start; ETag, rate limits respected) |
| `KINFRAME_FRAME_RELEASES_GITHUB_TOKEN` | — | Optional token for a higher GitHub API rate limit |
| `KINFRAME_FRAME_RELEASES_API` | `https://api.github.com` | GitHub API base (GitHub Enterprise, tests) |

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
for sign-up and sign-in, passkeys, required `name` at sign-up, language from the browser); and a custom access-token
script that adds `name` and `email` to tokens for the kinframe API only.

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
| `KINFRAME_LOGTO_CONFIG_FILE` | `$KINFRAME_DATA_DIR/logto.json` | Output file |

## Layout

```
cmd/kinframe-server   main: serve, migrate, healthcheck, bootstrap-logto
internal/api          HTTP handlers (strict server impl + tus, websockets, media), validation, auth middleware
internal/api/gen      generated by oapi-codegen (do not edit), plus the prepared spec used for request validation
internal/app          wiring and background loops (workers, janitor)
internal/logtoboot    bootstrap-logto: Logto Management API setup
internal/auth         Logto JWT validation, dev tokens, device tokens, argon2id, signed URLs
internal/store        PostgreSQL access (pgx), change feed, job queue
internal/db           pool, embedded goose migrations
internal/media        sniffing, geometry, vips/ffmpeg wrappers
internal/worker       media pipeline on the Postgres job queue (FOR UPDATE SKIP LOCKED)
internal/ws           websocket hub (device + user), commands
internal/settings     frame settings defaults and RFC 7396 merge patch
internal/notify       websocket/push fan-out (UnifiedPush delivery)
internal/releases     frame app release mirror (GitHub Releases + drop-in directory), APK manifest parser
internal/e2e          end-to-end tests over real HTTP
tools/                specprep (OpenAPI 3.1 → codegen input), respgen
```
