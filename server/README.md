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
| `make docker` | Runtime image (Debian bookworm-slim, ffmpeg + libvips-tools, non-root uid 10001) |

The binary has three commands: `serve` (default), `migrate` (apply migrations and exit; `serve` also migrates on start)
and `healthcheck` (used by the image's `HEALTHCHECK`).

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

## Layout

```
cmd/kinframe-server   main: serve, migrate, healthcheck
internal/api          HTTP handlers (strict server impl + tus, websockets, media), validation, auth middleware
internal/api/gen      generated by oapi-codegen (do not edit), plus the prepared spec used for request validation
internal/app          wiring and background loops (workers, janitor)
internal/auth         Logto JWT validation, dev tokens, device tokens, argon2id, signed URLs
internal/store        PostgreSQL access (pgx), change feed, job queue
internal/db           pool, embedded goose migrations
internal/media        sniffing, geometry, vips/ffmpeg wrappers
internal/worker       media pipeline on the Postgres job queue (FOR UPDATE SKIP LOCKED)
internal/ws           websocket hub (device + user), commands
internal/settings     frame settings defaults and RFC 7396 merge patch
internal/notify       websocket/push fan-out (UnifiedPush delivery)
internal/e2e          end-to-end tests over real HTTP
tools/                specprep (OpenAPI 3.1 → codegen input), respgen
```
