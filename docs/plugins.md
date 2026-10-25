# Plugins: design

Status: **design; step 1 is built** (the view format, its native components, the overlay and band layouts and a
built-in HomeWizard P1 widget, configured on the frame; see §12). This document is the contract plugin authors and
the kinframe apps will build against. Open questions are listed in §11.

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
icon from a fixed set of line icons (home, sun, grid, battery, car, plug, flame, water, thermometer, wind, leaf,
euro, ...; 40 in the first version, more can be added within API 1).

The exact format is [`docs/api/plugin-views/plugin-view.schema.json`](api/plugin-views/plugin-view.schema.json) (JSON
Schema) and [`plugin-view.d.ts`](api/plugin-views/plugin-view.d.ts) (TypeScript types for the plugin kit), with
examples in `docs/api/plugin-views/examples/` and views that must be rejected in `invalid/`. The limits: a tree at
most 3 components deep (`stack › stack › value`), at most 24 components per view (rows' values included), 16 KB
serialized; `value` ≤ 16 characters, `unit` ≤ 8, labels and captions ≤ 32, a gauge label ≤ 12, flow node texts ≤ 16,
text lines ≤ 60; 1–4 rows or stack items, 2–5 flow nodes, up to 8 edges, 2–96 sparkline points (`null` = gap). No line
breaks or control characters in any text, and unknown properties are rejected too, so a typo shows up in the preview
instead of being ignored. Edge `power` is a magnitude (≥ 0); the direction is `from` → `to`. A view that doesn't validate (unknown component, too deep, text too long) is not drawn; the frame
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

## 12. Step 1 as built (frame app)

What exists now, and where it differs from or adds to the design above. Everything runs on the frame; there is no
QuickJS, registry, server API or app UI yet.

| Part | Where |
|---|---|
| View model, strict JSON parser/validator, `ctx.format`, JSON builder for built-ins | `android/core/.../core/plugin/` (`PluginView.kt`, `PluginViewParser.kt`, `PluginFormat.kt`, `ViewJson.kt`) |
| Compose components (all forms), palette, shared animation clock, scale-to-fit | `android/core/.../core/plugin/ui/` (in core so the family app can use them for the live preview in step 3) |
| Icons | 40 new line icons in `KinIcons` (drawn for kinframe), mapped by name in `PluginIcons` |
| Schema, TypeScript types, examples, invalid examples | `docs/api/plugin-views/` (the tests read them; debug builds package them as assets) |
| Host, `PluginInstance`/`PluginContext`, `ctx.http` with `local_only`, layout geometry, overlay/band/detail UI | `android/frame/.../frame/plugins/` |
| Built-in HomeWizard P1 widget and mDNS discovery | `android/frame/.../frame/plugins/homewizard/` |
| Frame-local settings (Settings → Home widgets, behind the owner PIN) | `PluginSettingsSection.kt`, stored in the `plugins` SharedPreferences in the shape of §10's `plugins: [...]` |
| Dev preview (debug builds) | `PluginPreviewActivity` (`adb shell am start -n app.kinframe.frame/.debug.PluginPreviewActivity --es layout band ...`), a "Demo widgets" switch that cycles the examples, and `PluginScreenshotTest` |

Screenshots (neutral placeholder photos): [`docs/plugins/`](plugins/).

**Forms.** Every component has four forms: *corner* (overlay on the photo, white with the clock's text shadow and a
soft top-corner vignette), *detail* (the card, ink on the surface colour), *column* (the landscape band: icon above
value) and *strip* (the portrait band: side by side). A band of 180 dp or more (column) or 140 dp (strip), e.g. on a
1920×1080 frame, uses the corner form scaled to fit. In the band the frame picks a `stack`'s axis (column: vertical,
strip: horizontal). A `flow` in the corner puts the node with the most edges in the middle and the others at its start,
end, top and bottom; in the band the nodes form a line with that hub in the middle, and an edge that would have to
cross another node is shown as a short animated arrow beside its far node instead of a line around it.

**Animation and cost.** Value changes roll (old value up and out, new one in, 380 ms) and gauges sweep, both read in
`graphicsLayer`/draw lambdas only. Flow arrows are chevrons moving along each edge at 10–60 dp/s by the square root of
the edge's share of the largest power; all flows share one clock that ticks at 25 fps (not every vsync) and only
while a visible view has a moving edge, and only draw lambdas read it, so arrows never recompose or relayout. The draw
pass allocates nothing per frame (geometry is computed during layout).

**Band.** Geometry exactly as §2.1 (`BandGeometry`): 1280×800 → 1200×800 + 80 px column (left or right), 800×1280 →
80 px strip + 800×1200, 1920×1080 → 300 px, 1024×768 → no room, overlay. A band thinner than 64 dp also falls back to
the overlay, as does band mode next to the calendar sidebar (no room for both). The band opens and closes in 450 ms,
animated in the layout and draw phases only. The slides are rendered for the *settled* photo area (the renderer gets
the real area size, so Ken Burns, swipes, captions, video placement and the texture warm-up all work in the 3:2
area); while the band moves, the current render is scaled to cover the changing area, and 1.2 s after the band has
settled the slides are re-rendered for the new size. That re-render costs a few seconds of CPU on the RK3126 each time
the band opens or closes, so *always* is the better visibility mode for widgets in the band; *when it changes* or *after
a tap* suit the corners. Band background: paper, or "from the photo": the photo's edge next to the band shrunk to a
few pixels and stretched back with bilinear filtering (a blur without a blur pass), darkened, cross-fading with the
photo. *Clock and weather in the band* moves them to the foot of the column or the end of the strip (the band then
never collapses while awake).

**Visibility.** Overlay corners fade out while a finger drags the photo or a slide transition runs, while a video
plays with sound (video volume above 0), while new photos are waiting (the "N new" pill, our new-photo moment) and on
the paper empty state; the top-right corner also gives way to the live-photo "Live" mark. These don't apply to the band,
which covers nothing. With the quick menu open the corners move down below its top bar instead of hiding. *After a
tap* means 30 s after any tap on the slideshow (the tap still opens the quick menu as before). Night: instances stop
when the screen sleeps and the band collapses; the last view is kept and shown greyed in the morning until fresh data
arrives. The detail card pauses the slideshow, shows the instance name as its title and closes after 20 s.

**Host rules.** One background thread at background priority for all instances; 10 s per call; a view that doesn't
validate is not drawn and doesn't count as a failure (the previous view stays, the reason is logged and shown in the
settings); a throw or timeout is a failure, and failures back off (2×, 4×, … the refresh interval, at most 60 s).
Status: greyed after 3× the refresh interval without a good view, "unavailable" (a discreet icon) after 3 failures
with nothing to show, hidden after 15 minutes. `invalidate()` wakes the instance at once (the throttle to
`refresh.min_seconds` comes with step 2).

**`local_only`.** Enforced in OkHttp's DNS step: every address a name resolves to must be private, checked when the
connection opens, so a name can't pass a check and then re-resolve to a public address. Private = RFC 1918,
169.254/16, fc00::/7, fe80::/10 (and IPv4-mapped forms of those). Loopback, 100.64/10 (carrier-grade NAT) and
everything else are refused. No redirects are followed; 10 s timeout; 1 MB response limit.

**HomeWizard P1 (built in).** Polls `GET http://<host>/api/v1/data` every 5 s. Fields used: `active_power_w` (negative =
returning), `active_power_l1_w`…`l3_w`, `total_power_import_kwh`/`export_kwh` (or the sum of the `_t1`/`_t2` tariff
totals on older firmware) and `total_gas_m3`. The v1 API only has running totals, so *today* is counted from the
first reading of the day (kept in `ctx.store`). Main view: a `value` (bolt "Using" / sun "Returning", trend arrow on a
10 % change over 30 s, `good` when returning, `warn` above the owner's threshold) or, as an option, a `flow` grid ⇄ home.
Detail: the value, the last hour as a sparkline (96 buckets), per-phase rows and today's used, returned and gas.
Discovery browses `_hwenergy._tcp` with NsdManager while the settings are open (resolving one service at a time, as
Android 6 requires), shows "Local API is off" from the TXT record, and picks the meter when there is exactly one. The
v2 API (HTTPS + token) is not supported yet.

**Changes to the design above.** None of substance; these are additions or decisions the design left open: the band
fallbacks (64 dp minimum, not beside the calendar sidebar), render-after-settle for the band animation, unknown
properties are rejected, edge power is a magnitude, the first icon set has 40 icons, compact flows use direction
arrows for non-adjacent edges, and the new-photo celebration is the "N new" moment (the frame has no separate
celebration animation).

**Performance (emulator only, not yet measured on the Denver).** The dev preview with neutral placeholder photos that
cross-fade (800 ms) and zoom continuously like Ken Burns, on an Android 6 x86_64 emulator at 1280×800 mdpi, 4 cores,
1 GB, **software rendering (SwiftShader)**; per-frame times from `dumpsys gfxinfo framestats` over 30 s each:

| Configuration | Redraws/s | UI thread ms (p50 / p90 / p99) | RenderThread ms (p50 / p90 / p99) |
|---|---|---|---|
| No widgets, slideshow moving | 58.8 | 0.55 / 1.57 / 3.61 | 16.96 / 17.59 / 18.21 |
| Band (landscape) + flow + value | 58.9 | 0.61 / 1.07 / 3.61 | 16.94 / 17.58 / 18.20 |
| Band, "from the photo" background | 58.9 | 0.64 / 1.03 / 3.64 | 16.93 / 17.59 / 18.18 |
| Band + 5-node flow | 58.9 | 0.64 / 1.05 / 3.70 | 16.93 / 17.59 / 18.20 |
| Band (portrait strip) + flow + value | 58.9 | 0.99 / 1.39 / 2.04 | 16.71 / 17.33 / 17.85 |
| Corners + flow + value | 58.9 | 1.01 / 1.42 / 1.92 | 16.65 / 17.29 / 17.88 |
| Band + flow, nothing else moving | 25.5 | 0.63 / 0.88 / 1.89 | 4.82 / 5.30 / 6.91 |
| No widgets, nothing moving | 0 | – | – |

With software rendering the RenderThread time is the full-screen photo being rasterised and is the same with or
without widgets; the widgets add well under a millisecond on the UI thread. The one real cost is the last-but-one row:
an animating flow makes the screen redraw 25 times a second while the slideshow is otherwise still (without widgets it
doesn't redraw at all). On the Mali-400 each of those redraws is roughly one full-screen texture blit plus the band,
the same kind of work as a Ken Burns frame but at 25 instead of ~56 fps, so it should fit, but it costs power and must
be confirmed on the frame, together with swipe smoothness and the cross-fade with the band open, before this ships.
The emulator says nothing about the Mali-400's fill rate. Reproduce with the dev preview
(`--es layout band|overlay|off --ez sim true|false`) and `adb shell dumpsys gfxinfo app.kinframe.frame framestats`.

**Screenshots.** [`docs/plugins/`](plugins/): `band_*`, `overlay_*`, `detail_landscape.png`, `gallery.png` (every example
in the corner, column and strip forms) and `detail_forms.png` come from `PluginScreenshotTest` (Robolectric, 1280×800
and 800×1280 mdpi, 1920×1080 xhdpi); `emulator_*` are from the dev preview on the emulator.

**What step 2 (the QuickJS host) plugs into.** `PluginInstance` is the seam: a QuickJS-backed implementation calls
`start`/`render`/`detail`/`stop` in the script and returns their JSON; `PluginHost` already validates it, keeps the last
good view, counts failures, backs off, applies visibility and stops instances at night. Still to do there: the 50 ms CPU
and 8 MB heap limits (QuickJS interrupt handler and memory limit; today only a 10 s timeout per call), the `invalidate`
throttle, `ctx.ws`, `ctx.t` from the bundle's `locales/`, URL permissions from the manifest (today `PluginHttp` takes
an `allowed` predicate and a `localOnly` flag, which built-ins set to "local only"), the `discover` helper generalised
from `HomeWizardDiscovery` to any service type, and reporting `InstanceState.error` to the server. The HomeWizard widget
becomes `kinframe.homewizard-p1` in JavaScript; `HomeWizardTest` and the example views are its fixtures, and
`PluginPrefs`/`InstanceConfig` give way to the instances in the frame settings (same shape).
