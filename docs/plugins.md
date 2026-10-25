# Plugins: design

Status: **design, not built yet.** This document is the contract plugin authors and the kinframe apps will build
against. Open questions are listed at the end.

## 1. What a plugin is

A plugin adds a small live **overlay** to the frame's slideshow: "the house uses 1.2 kW, the solar panels make 3.4 kW",
"the car is at 68 %, charging, full at 23:10". It runs **on the frame**, so it can reach devices on the family's home
network (Home Assistant, a HomeWizard P1 meter) that no cloud server can.

Contributors publish plugins in an open registry. A frame owner picks a plugin in the web or mobile app, chooses a
corner and fills in its settings. The frame downloads the plugin, runs it in a sandbox and draws what it returns.

Design goals, in order:

1. **Safe by default.** A plugin is third-party code on a device in someone's living room. It can only reach the hosts
   the owner typed into its settings, cannot read photos, other plugins' data or the frame's credentials, and is
   stopped if it uses too much CPU or memory.
2. **Looks like kinframe.** Plugins describe *what* to show; the frame draws it with its own type, colours and
   animations. A plugin can't make the frame ugly, unreadable or slow.
3. **Cheap on old hardware.** The reference frame is a Rockchip RK3126 with a Mali-400 and 1 GB RAM on Android 6. A
   plugin update must cost less than a cross-fade.
4. **Easy to write.** One JavaScript file and one manifest, no build step required, a local preview tool, and three
   example plugins to copy from.

Non-goals for the first version: plugins that replace the slideshow, full-screen apps, touch interaction beyond
"tap to expand", plugins on the family apps or the website, and paid plugins.

## 2. Where it shows: slots

The bottom of the screen is taken by the caption (left) and the clock and weather (right). Plugins get the top:

```
┌──────────────────────────────────────────────┐
│ [top-left]                       [top-right] │
│                                              │
│                  photo                       │
│                                              │
│ caption                          clock · wx  │
└──────────────────────────────────────────────┘
```

- **Slots:** `top-left` and `top-right`. Each slot holds up to **two** plugin instances stacked vertically. In portrait
  the same corners apply.
- **Size:** a widget is at most 30 % of the screen width and 20 % of its height per instance. The frame scales the
  content to fit; a plugin never sets pixel sizes.
- **Style:** the same translucent scrim, type scale and colours as the caption and clock, so the overlay matches the
  existing widgets. Light/dark follows the photo behind it (as the caption already does).
- **Behaviour:** overlays fade out during swipes and video playback with sound, and during the new-photo celebration,
  then fade back. Night mode (sleep schedule) hides them. The owner can set each instance to *always*, *only when the
  value changes* (show for 30 s after a change) or *on tap* (hidden until the screen is tapped).
- **Tap:** tapping a widget can open its **detail card**, a larger version the plugin describes the same way, closed
  by tapping outside it or after 20 s.

### 2.1 Layout mode: overlay or band

Corners on top of the photo are fine for one small value, but they cover part of it. The owner can instead choose the
**band** layout per frame: the slideshow shrinks to a **3:2** area (2:3 in portrait) and the space that frees up holds
the plugins, so nothing covers the photo.

The reference screens are 16:10, so a 3:2 photo area leaves a band of exactly 1/16 of the long side:

```
 landscape 1280×800                          portrait 800×1280
┌───────────────────────────────┬──────┐     ┌──────────────────────┐
│                               │ band │     │ band  800×80         │
│        photo 1200×800  (3:2)  │ 80×  │     ├──────────────────────┤
│                               │ 800  │     │                      │
│                               │      │     │  photo 800×1200      │
│ caption           clock · wx  │      │     │       (2:3)          │
└───────────────────────────────┴──────┘     │                      │
                                             │ caption   clock · wx │
                                             └──────────────────────┘
```

- **Why 3:2:** it is the shape of most camera photos, and close to phone photos (4:3), so in fill mode a typical
  landscape photo loses almost nothing to cropping. On a 16:10 screen today it loses 6 % of its height; in the 3:2 area
  it fits exactly.
- **What goes in the band:** the same plugin views, laid out for a narrow column (landscape: widgets stacked, icon
  above value) or a strip (portrait: widgets side by side, `top-left` slot first, then `top-right`). The view
  components (section 4) each have a compact form for this; plugins don't do anything different.
- **Side:** the owner picks left or right for the column in landscape; the portrait strip is always at the top, away
  from the caption.
- **Band background:** the frame's paper colour, or a blurred, darkened edge of the current photo for a softer look
  (owner's choice). It cross-fades with the photo.
- **Empty band:** without any plugin instances, or when all are hidden (night, *on tap*, *only when the value
  changes*), the band collapses and the photo goes back to full screen, animated. The clock and weather can optionally
  move into the band so the photo has no overlays at all.
- Other screen shapes keep the rule: a 16:9 frame gets a wider band (3:2 of 1920×1080 leaves 300 px), a 4:3 frame
  has no room and uses the overlay layout.

## 3. What a plugin is made of

```
ha-energy-flow/
  plugin.json        manifest (below)
  main.js            the code: one ES module, no imports
  icon.svg           shown in the plugin catalog
  README.md          shown in the catalog; how to set it up
  locales/nl.json    optional translations of the manifest's labels and the plugin's own strings
```

### 3.1 Manifest

```json
{
  "id": "community.ha-energy-flow",
  "name": "Home Assistant energy flow",
  "version": "1.2.0",
  "api": 1,
  "author": { "name": "Jane Doe", "url": "https://github.com/janedoe" },
  "license": "MIT",
  "description": "Solar, grid, home and battery power from Home Assistant.",
  "slots": ["top-left", "top-right"],
  "refresh": { "min_seconds": 5, "default_seconds": 10 },
  "permissions": {
    "http": ["settings.base_url"],
    "websocket": ["settings.base_url"]
  },
  "settings": [
    { "key": "base_url", "type": "url", "label": "Home Assistant address",
      "placeholder": "http://homeassistant.local:8123", "required": true, "local_only": true },
    { "key": "token", "type": "secret", "label": "Long-lived access token", "required": true,
      "help": "Profile → Security → Long-lived access tokens in Home Assistant." },
    { "key": "solar", "type": "entity", "label": "Solar power sensor", "domain": "sensor", "unit": "W" },
    { "key": "grid", "type": "entity", "label": "Grid power sensor (positive = import)", "domain": "sensor", "required": true },
    { "key": "battery", "type": "entity", "label": "Battery power sensor", "domain": "sensor" },
    { "key": "unit", "type": "choice", "label": "Show in", "options": ["W", "kW"], "default": "kW" }
  ]
}
```

- **`id`** is reverse-DNS-like and unique in the registry. `community.` is the prefix for third-party plugins,
  `kinframe.` for the ones the project maintains.
- **`api`** is the plugin API version (section 4). The frame refuses plugins for an API it doesn't know.
- **`permissions`** lists what the code may do, and every network permission names the **setting** that holds the
  address. The plugin can't hard-code hosts: it can only talk to what the owner typed in. This is the main safety rule.
  - `local_only: true` on a URL setting makes the frame refuse anything that doesn't resolve to a private address
    (RFC 1918, link-local, `.local`, IPv6 ULA). The energy plugins use it, so a stolen token can't be sent to the
    internet through them.
  - A plugin that needs an internet API instead (e.g. a public transport departure board) declares a fixed host:
    `"http": ["https://api.example.org"]`. The catalog shows that host to the owner before they install it.
- **Setting types:** `text`, `number`, `boolean`, `choice`, `url`, `secret`, `entity` (Home Assistant entity, see
  6.1), `color`, `duration`. Each has `label`, optional `help`, `required`, `default`. The web and mobile apps render
  the form from this list; plugins never ship their own settings UI. Labels are translated through `locales/`.

### 3.2 Code

`main.js` exports one object. Everything is async and returns plain data.

```js
export default {
  // Called once when the instance starts or its settings change. Return the first view, or null while loading.
  async start(ctx) {
    ctx.ws.connect(ctx.settings.base_url.replace(/^http/, 'ws') + '/api/websocket', {
      onOpen: (ws) => ws.send({ type: 'auth', access_token: ctx.settings.token }),
      onMessage: (ws, msg) => { /* keep the latest states in ctx.state */ },
    });
    return null;
  },

  // Called every `refresh` seconds and after ctx.invalidate(). Return the view to draw.
  async render(ctx) {
    const s = ctx.state;
    return {
      type: 'flow',
      nodes: [
        { id: 'solar', icon: 'sun', value: ctx.format.power(s.solar) },
        { id: 'grid', icon: 'grid', value: ctx.format.power(Math.abs(s.grid)) },
        { id: 'home', icon: 'home', value: ctx.format.power(s.home) },
      ],
      edges: [
        { from: 'solar', to: 'home', power: s.solarToHome },
        s.grid > 0 ? { from: 'grid', to: 'home', power: s.grid } : { from: 'solar', to: 'grid', power: -s.grid },
      ],
    };
  },

  // Optional: the detail card on tap.
  async detail(ctx) { /* same view format, more rows */ },

  // Optional: called before the instance is stopped.
  async stop(ctx) {},
};
```

`ctx` is the plugin's whole world:

| `ctx.` | What |
|---|---|
| `settings` | The owner's settings, secrets included, read-only. |
| `state` | A plain object the plugin may keep between calls (lost on restart). |
| `store` | Small persistent key-value storage, 64 KB per instance (e.g. yesterday's total). |
| `http.get/post(url, options)` | Fetch. Only URLs allowed by `permissions.http`. JSON or text body. 10 s timeout, 1 MB response limit. |
| `ws.connect(url, handlers)` | One WebSocket per instance, same rules. The frame reconnects with backoff and calls `onOpen` again. |
| `invalidate()` | Ask for a `render` now (e.g. after a WebSocket message). Throttled to `refresh.min_seconds`. |
| `format.power/energy/percent/temperature/duration/time(value)` | Formats in the frame's language and units, so plugins don't each reinvent "1,2 kW". |
| `t(key, vars)` | The plugin's own translated strings from `locales/`. |
| `log(...)` | Goes to the frame's diagnostics, visible to the owner, never uploaded with secrets in it. |
| `now()`, `timezone` | The frame's clock and time zone. |

There is no `fetch`, `XMLHttpRequest`, timers beyond what the runtime gives, file system, or access to the photos.

## 4. What a plugin can draw: views (API 1)

A view is a small tree of **components** the frame renders natively in Compose. The set is deliberately small; new
components come with a new API version.

| Component | Shows | Example |
|---|---|---|
| `value` | Icon, big number, unit, optional label and trend arrow | `⚡ 1,2 kW  ↑` |
| `rows` | Up to 4 `value`s stacked | grid / solar / home |
| `gauge` | Ring or bar from 0–100 % with a label inside | car battery 68 % |
| `flow` | Up to 5 nodes (icon + value) with animated arrows between them, speed by power | solar → home ← grid |
| `sparkline` | A small line chart of up to 96 points, optional min/max | power over the last 24 h |
| `text` | One or two short lines (max 60 chars each) | "Charging · full at 23:10" |
| `stack` | Vertical or horizontal group of the above, max depth 2 | gauge + text |

Every component accepts `tone` (`neutral`, `good`, `warn`, `bad`, `accent`), which maps to the frame's palette, and an
icon from a fixed set of about 60 line icons (home, sun, grid, battery, car, plug, flame, water, thermometer, wind,
leaf, euro, ...). A view that doesn't validate (unknown component, too deep, text too long) is not drawn; the frame
keeps the previous view and logs why. When `render` throws or times out three times in a row, the widget shows a
discreet "unavailable" state and the owner sees the error in the app.

Because the frame owns the drawing, the same view works in a corner, in the detail card, on the web preview and in a
future TV or tablet frame, and animations (flow arrows, value changes) are done once, well, in the frame's GPU path.

## 5. How it runs on the frame

```
 owner's app ──settings──▶ server ──change feed──▶ frame app
                                                    │
                                   plugin host ─────┤ per instance:
                                   (sandbox)        │   QuickJS runtime  ◀── main.js
                                                    │   ctx bridge (http/ws/store/format)
                                                    │   view validator ──▶ Compose overlay
```

- **Runtime:** each instance runs in its own **QuickJS** context (small, embeddable, has memory limits and an
  interrupt handler for CPU limits, runs fine on armeabi-v7a and Android 6). A plugin gets **8 MB** of heap and **50 ms
  of CPU per call**; over the limit, the call is aborted and counted as a failure. All instances share one background
  thread at low priority, so a plugin can never stall the slideshow's main thread.
- **Network:** every `ctx.http` and `ctx.ws` call goes through the host, which checks the URL against the manifest's
  permissions and `local_only` before opening a socket, follows no redirects to other hosts, and rate-limits to
  `refresh.min_seconds`. Plain `http://` is allowed only for private addresses (home devices rarely have TLS).
- **Secrets** (`secret` settings) are stored on the server encrypted, sent to the frame over the device channel, kept
  in the app's private storage, and given only to that instance. The apps never show a secret again after it's saved
  (only "set · replace"). They are left out of logs, diagnostics exports and backups.
- **Lifecycle:** instances start after the slideshow is up, stop at night (sleep schedule) and during setup, and
  restart with the new code when the plugin updates. Several instances of one plugin (e.g. two cars) are fully
  separate.
- **Offline:** the last good view stays on screen, greyed after 3× the refresh interval, hidden after 15 minutes.

## 6. Settings in the apps

The frame's settings page gets a **Plugins** section: the installed instances per slot, "Add plugin" (opens the
catalog), and per instance its form, a live preview and the last error. The form comes from the manifest.

### 6.1 Discovery helpers

Typing IP addresses and entity IDs is where home automation integrations usually lose people, so the host offers
two helpers the forms can use:

- **Find on network:** a `url` setting may declare `"discover": "_home-assistant._tcp"` or
  `"discover": "_hwenergy._tcp"`. The frame browses mDNS for that service and offers the results in the app's form.
  (The frame does this, not the app, because only the frame is guaranteed to be on that network.)
- **Entity picker:** an `entity` setting lists the Home Assistant entities that match `domain` and `unit`, fetched by
  the frame through the plugin's own `base_url` and `token`, so the owner picks "Solar power" from a list instead of
  typing `sensor.solaredge_ac_power`.

## 7. Publishing: the registry

- **Where:** a public Git repository, `kinframe/plugins`. Each plugin is a folder, added and updated by pull request.
  A plugin can also live in its author's own repository, with a small entry in the registry pointing to a tagged
  release and its sha256.
- **Review:** CI checks the manifest against a schema, runs the plugin against recorded fixtures in the preview tool,
  and fails on forbidden patterns (`eval`, `Function`, obfuscated code, minified code over a size limit). A
  maintainer reviews every new plugin and every permission change by hand; a version that only changes code within the
  same permissions can be merged by the plugin's own maintainers after CI passes.
- **Signing:** the registry's CI builds each release into a bundle (`plugin.json`, `main.js`, assets) and signs its
  sha256 with the registry key. Frames only run bundles with a valid registry signature, and the kinframe server
  mirrors the bundles (as it does for frame app releases), so frames never fetch code from an author's server.
- **Self-hosters and developers** can switch on *developer mode* per frame (in the owner's settings, with a warning),
  which allows unsigned bundles uploaded through the app or loaded from the preview tool. It turns itself off after
  24 hours.
- **Updates:** frames update plugins automatically within the same permissions. A new version that asks for **more**
  permissions waits until the owner approves it in the app.
- **Withdrawal:** a version can be revoked in the registry; frames stop it on the next sync and fall back to the
  previous version or switch the instance off.
- **Licence:** plugins in the registry must use an OSI-approved licence. They are separate works and are not covered
  by the kinframe CLA.

## 8. Tooling for authors

- `npx @kinframe/plugin-kit new my-plugin`: scaffold with the manifest, a `main.js`, a README and fixtures.
- `npx @kinframe/plugin-kit dev`: runs the plugin in the same QuickJS sandbox on the author's computer and renders
  its views in a browser preview at frame size, in both corners, light and dark, with the real network or with
  recorded fixtures. It also checks the permission rules, so "works in dev" means "works on the frame".
- `npx @kinframe/plugin-kit push --frame <code>`: sends the plugin to a frame in developer mode.
- TypeScript types for `ctx` and the views, so authors get autocompletion even in plain JS.

## 9. The first three plugins (maintained by the project, `kinframe.*`)

### 9.1 Home Assistant energy flow (`kinframe.ha-energy`)

- **Settings:** Home Assistant URL (mDNS discovery, `local_only`), long-lived token, and entity pickers for solar,
  grid, battery and home power. If Home Assistant's own Energy dashboard is configured, the plugin reads its
  configuration (`energy/get_prefs` over the WebSocket API) and fills the sensors in automatically.
- **Data:** Home Assistant WebSocket API, `subscribe_entities` for the chosen sensors, so changes arrive in about a
  second without polling.
- **View:** `flow` with solar → home, grid ⇄ home, battery ⇄ home; arrows show direction and speed. Detail card: today's
  totals (import, export, self-consumption) and a 24 h `sparkline`.

### 9.2 HomeWizard P1 meter (`kinframe.homewizard-p1`)

- **Settings:** the meter's address (mDNS `_hwenergy._tcp` discovery, `local_only`). The owner must switch on **Local
  API** for the meter in the HomeWizard Energy app.
- **Data:** the meter's local HTTP API (`/api/v1/data`), polled every 5 s: active power (import negative for export),
  per-phase power, today's import/export and gas. The newer authenticated local API (v2, HTTPS with a token issued by
  pressing the meter's button) is supported when the meter offers it; the plugin's setup tells the owner to press the
  button and the frame requests the token, storing it as a `secret`.
- **View:** `value` with the current power, `tone` good when exporting and warn above a threshold the owner sets.
  Detail card: per-phase rows and today's totals. No cloud account, no Home Assistant needed.

### 9.3 Electric car via Home Assistant (`kinframe.ha-ev`), e.g. a Kia EV3

- **Settings:** Home Assistant URL and token (shared with 9.1 if both are installed: the app offers to reuse them),
  and entity pickers for battery level, charging state, range, charge-complete time and plug state. A preset for the
  **Kia/Hyundai integration** (Kia Connect / Bluelink, a custom Home Assistant integration) preselects its usual
  entities, so most owners only confirm.
- **Data:** Home Assistant WebSocket subscriptions, like 9.1. The car's own data reaches Home Assistant through the
  manufacturer's cloud, often only every 30–60 minutes unless charging; the widget shows how old the value is.
- **View:** `gauge` with the battery %, `text` "Charging · full at 23:10" / "Plugged in" / "312 km". *Only when the
  value changes* is a good default: the car appears in the corner while it charges and when it's unplugged.
- Because this one only reads Home Assistant entities, it is effectively "any battery gauge": a home battery or an
  e-bike works with the same plugin and a different preset.

## 10. Changes to kinframe

| Part | Work |
|---|---|
| API (`openapi.yaml`) | `GET /v1/plugins` (catalog from the mirrored registry), `GET /v1/plugins/{id}/{version}/bundle`, plugin instances per frame in frame settings (`plugins: [{instance_id, plugin_id, version, slot, order, visibility, settings, secret_refs}]`), `PUT/DELETE` per instance, secrets write-only. Device side: instances and bundles in the change feed, `POST /v1/device/plugins/{instance}/status` for last error and discovered values. |
| Server | Registry mirror and signature check (same pattern as `internal/releases`), encrypted secret storage, settings validation against the manifest, the change feed entries. |
| Frame app | Plugin host: QuickJS (`armeabi-v7a`), the `ctx` bridge with the permission checks, the view validator and the Compose components, slot layout and visibility rules, mDNS discovery, diagnostics. |
| Web and family apps | Catalog, add/remove/reorder per slot, the form renderer for the setting types (incl. secret and entity picker), live preview using the same view components. |
| New repos | `kinframe/plugins` (registry + CI + signing), `@kinframe/plugin-kit` (scaffold, dev preview, push, types). |

Suggested order: (1) the view format and the Compose components with a built-in, hard-coded HomeWizard widget, to
get the look right on the Denver; (2) the QuickJS host and permission checks, with that widget rewritten as the first
plugin; (3) settings, catalog and secrets end to end; (4) the registry, signing and the plugin kit; (5) the two Home
Assistant plugins.

## 11. Open questions

1. **More slots?** A `side` slot next to the calendar sidebar, or a full-width bottom ticker, would suit some plugins
   (departures, energy prices). Start with the two corners and see what people ask for.
2. **Family-wide or per frame?** Settings are per frame. A family with two frames in one house types the Home
   Assistant token twice; "copy from another frame" in the app may be enough.
3. **Plugins on the TV page.** The browser frame (`/tv/frame`) can't reach the home network from the server, but the
   browser itself is often on it. Running the same plugins in a Web Worker there is possible later. The browser
   frame's device protocol lives in `web/src/lib/device/` (`FrameEngine.on('changes' | 'slide' | 'command')` gives
   a runtime the same moments a frame-side plugin host sees), so a runtime can hook in there.
4. **Notifications.** "Car fully charged" as a full-screen message would be useful but also annoying; leave it out of
   API 1.
5. **Review capacity.** Hand review of every new plugin and permission change is the safety net; it needs at least two
   maintainers with the right to approve.
