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
| `desktop/` | Windows, macOS and Linux app (Rust, egui) with bulk import of whole folders |
| `tools/convert/` | Convert a stock Frameo frame into a kinframe frame and back: command-line twin of the website's `/convert` |
| `tools/mictest/` | Tiny APK that checks whether a frame has a working microphone |

## Components

- **Frame app**: kiosk launcher for Android-based frames (Android 6+, including stock Frameo hardware). Install it
  on a stock frame from the website (`/convert`, over USB in Chrome or Edge) or with `tools/convert/`; both can switch
  the frame back. Tested frames: `docs/hardware/supported-devices.md`.
- **Family apps**: native Android and iOS apps with share-sheet sending.
- **Website**: send, manage frames and members, browse the library.
- **Desktop app** (Windows, macOS, Linux): sign in, add frames, and send whole folders or albums at once, with
  progress, duplicate detection and uploads that resume after a restart. See `desktop/README.md`.
- **Server**: Go, PostgreSQL, Logto for accounts. One `docker compose up` to self-host.

## Licensing

kinframe is dual-licensed.

- **Open source: AGPL-3.0-or-later** (see `LICENSE`). Free to use, change and self-host. If you distribute kinframe,
  or run a modified version as a service for others, you must make the complete source of your version available
  under the same license.
- **Commercial license**, from Appmire CommV. For manufacturers and companies that want to ship kinframe in their
  own frames or run it for their customers without the AGPL's obligations: under their own brand, without
  publishing their changes, with support and updates. Contact jeroen@appmire.be.

Contributions are welcome under the [Contributor License Agreement](CLA.md), which keeps both options possible;
see `CONTRIBUTING.md`.

The kinframe name and logo are not covered by either license. Frameo is a trademark of its respective owner;
kinframe is not affiliated with or endorsed by Frameo.
