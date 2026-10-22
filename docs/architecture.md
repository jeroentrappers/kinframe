# kinframe architecture (draft)

> Status: draft. Feature scope is finalized from `docs/spec/` once the clean-room analysis is done.

## Components

| Path | Component | Stack | Users |
|------|-----------|-------|-------|
| `server/` | Backend API, media pipeline, realtime hub | Go, PostgreSQL, ffmpeg + libvips | everything |
| `android/` | One Gradle project with modules `:core` (API client, models, Logto, design system), `:frame` (frame app: kiosk launcher on the photo frame) and `:family` (Android family app) | Kotlin, Jetpack Compose, Media3 | frames, family members |
| `ios/` | iOS family app, with a share extension | Swift, SwiftUI (built on macOS/Xcode) | family members |
| `web/` | Website: family web app + frame owner admin | SvelteKit (static SPA, served by the backend) | family members, owners |
| `provisioning/` | Turn a stock Frameo device into a kinframe device | adb + shell | whoever sets up a frame |
| `deploy/` | docker compose: backend, Postgres, Logto, reverse proxy | Docker | self-hosters |

```
 family app (Compose / SwiftUI) ─┐                       ┌─ Logto (OIDC) ── Postgres (logto db)
 browser (SvelteKit SPA) ────────┼── HTTPS ── backend ───┤
                                 │   (REST + WS)         └─ Postgres (kinframe db), media volume
 frame (Kotlin / Compose) ───────┘   outbound only: frames never accept inbound connections
```

## Identity and auth

- **People** sign in with **Logto** (self-hosted, in the same compose stack). Logto handles sign-up, email/passkey/social
  sign-in, MFA and account management.
  - `web/` is a Logto *SPA* application; `mobile/` is a Logto *Native* application (PKCE, system browser).
  - The backend is a Logto *API resource*. Clients request access tokens for that resource; the backend validates the
    JWT locally (Logto JWKS, issuer, audience, expiry) and maps `sub` to a kinframe user row created on first use.
- **Frames** do not have a person behind them, so they don't use Logto. They get a **device credential** from the
  backend through pairing:
  1. A fresh frame calls `POST /v1/devices/enroll` and receives a device id plus secret. It shows a short **pairing code**.
  2. A signed-in user enters the code in the app or website and becomes the frame's **owner**.
  3. The frame authenticates with its device secret, which is stored hashed server-side and can be revoked per frame.
- **Invites**: an owner invites family to a frame with a code, link or QR. Accepting one requires a Logto sign-in, so
  every sender is a real, revocable account. Roles per frame are `owner`, `admin` and `member`.

## Server discovery (custom servers)

The family app and the frame are not tied to one server. Every server exposes an unauthenticated discovery document:

```
GET /.well-known/kinframe
{
  "name": "Trappers family",            // shown in the app before sign-in
  "version": "0.1.0",
  "api": "https://frames.example.org/v1",
  "auth": {
    "issuer": "https://auth.frames.example.org/oidc",
    "mobile_client_id": "…",            // Logto native app
    "web_client_id": "…",               // Logto SPA
    "api_resource": "https://frames.example.org/v1"
  },
  "limits": { "max_items_per_send": 200, "max_video_seconds": 300 }
}
```

- The app stores the chosen server and its discovery document, then runs OIDC (PKCE) against that server's Logto.
- The mobile redirect URI is fixed for all servers (`app.kinframe:/callback`, plus a universal/app link where
  possible). The compose bootstrap registers it in Logto automatically, so self-hosters configure nothing by hand.
- Frames use only `api`, since they don't sign in with Logto.

## Data flow

- Uploads go from client to backend, either resumable or as multipart for small files. The backend keeps the original
  and makes renditions sized to each target frame's resolution: JPEG/WebP for photos, H.264 MP4 for video. It strips
  location metadata from the renditions.
- Frames hold a **websocket** to the backend for instant "new content / settings changed" notifications. They also do a
  periodic **manifest sync** (ETag-based) as the source of truth. Downloads are resumable and the frame works offline
  from its local cache.
- Frame reports flow back to the backend: heartbeat, storage, app version, "photo shown", reactions. Senders see
  delivery state and reactions in the family app.
- Phone notifications: UnifiedPush/ntfy by default (no Google dependency), with FCM/APNs optional for store builds.

## Self-hosting

A single `docker compose up` runs backend, Postgres, Logto and Caddy (TLS). Media storage is a volume, or optionally
any S3-compatible bucket.
