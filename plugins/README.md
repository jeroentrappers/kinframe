# Plugins

JavaScript plugins for the kinframe frame (docs/plugins.md). Each folder is one plugin:

```
<id>/
  plugin.json      the manifest (§3.1)
  main.js          one ES module with a default export (§3.2)
  locales/*.json   translations: the plugin's own strings, and "@name" for its name
  fixtures/        recorded data for the tests (never secrets)
```

The `kinframe.*` folders here are built into the frame app (copied into its assets at build time; fixtures stay out).
`examples/` holds community-style plugins that are tested but not shipped; they were written to find out what the view
format still lacks (docs/plugins.md §11.1).

Tests run every plugin in the real QuickJS sandbox on the JVM: `android/frame/src/test/java/app/kinframe/frame/plugins/js/`.

## Libraries and templates

A plugin may import the frame's own libraries, and nothing else (docs/plugins.md §13.1). They live in
`android/frame/src/main/assets/plugin-host/lib/`:

| Import | For |
|---|---|
| `kinframe/ha` | A Home Assistant connection: WebSocket subscription, REST fallback, token errors, service calls. |
| `kinframe/homewizard` | HomeWizard Energy devices' local API v1: readings, device info, the socket's on/off state. |
| `kinframe/day` | "How much today" from a meter's running total, kept across restarts. |
| `kinframe/series` | A live value's recent history: trend arrows and "last hour" sparklines. |

Starting a new integration, copy the plugin closest to it:

- a device on the home network that measures something: `kinframe.homewizard-water` (or the P1);
- a device with a button: `kinframe.homewizard-socket`;
- anything Home Assistant knows: `kinframe.ha-ev` (entities and a preset), `kinframe.ha-media` (buttons that call
  services), `kinframe.ha-energy` (several entities into one flow);
- an internet API: `examples/community.*`.

Each keeps the same shape: `update()` fetches through a library and records what it read in `ctx.state` (or
`ctx.store`); `render()` and `detail()` draw from that without the network; the parsing and view functions are plain
exported functions with tests against a fake device (`FakeHomeWizard`, `FakeHomeAssistant`) and fixtures.
