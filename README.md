# kinframe

Open-source, self-hostable software for Wi-Fi digital photo frames: a clean-room alternative to Frameo.

Family members send photos and videos from their phone or the web. The frame shows them seconds later and sends
reactions back. There are no paywalls, no telemetry, and the server is yours.

> **Status:** early development. Specs and API contract are done, and implementation is starting.

## What's here

| Path | What |
|---|---|
| `docs/spec/product-spec.md` | Product specification: every feature, with Frameo comparison and release phase |
| `docs/api/` | API contract (`openapi.yaml`) and protocol guide |
| `docs/architecture.md` | Components, auth (Logto), server discovery, data flow |
| `docs/deploy.md` | Hosted server (kinframe.appmire.be) and self-hosting |
| `docs/hardware/` | Hardware profiles of tested frames |
| `docs/i18n.md` | The 36 languages: where texts live, adding strings, right-to-left, glossary per language |
| `docs/clean-room.md` | How kinframe was specified without copying Frameo |
| `server/` | Go backend *(coming)* |
| `web/` | SvelteKit website *(coming)* |
| `android/` | Frame app and Android family app (Kotlin, Compose) *(coming)* |
| `ios/` | iOS family app (SwiftUI) *(coming)* |
| `tools/mictest/` | Tiny APK that checks whether a frame has a working microphone |

## Components

- **Frame app**: kiosk launcher for Android-based frames (Android 6+, including stock Frameo hardware).
- **Family apps**: native Android and iOS apps with share-sheet sending.
- **Website**: send, manage frames and members, browse the library.
- **Server**: Go, PostgreSQL, Logto for accounts. One `docker compose up` to self-host.

## License

AGPL-3.0-or-later. See `LICENSE`.

Frameo is a trademark of its respective owner. kinframe is not affiliated with or endorsed by Frameo.
