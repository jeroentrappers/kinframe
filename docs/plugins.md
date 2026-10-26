# Plugins: design

Status: **design; steps 1 and 2 are built** (the view format, its native components, the overlay and band layouts,
and the QuickJS plugin host with the HomeWizard P1, Home Assistant energy, car and now-playing plugins in JavaScript;
the weather station is still a Kotlin built-in; everything configured on the frame; see §12 and §13). This document is the contract plugin authors and
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
- **Clock in the band:** whenever the band shows, the clock, date and weather move into it (foot of the column, end
  of the strip), so the photo has no overlays at all and captions keep the whole bottom of the photo. In the narrow
  column the date is two short lines ("Sun" / "25 Oct"). Older layouts' `clock_in_band` is still read and ignored.
- **One grid:** the band is a cell of the same grid as a pair or collage: the warm dark background, an 8 dp gap
  around and between the cells and 14 dp rounded corners. The photo area gets the same frame (drawn over it as four
  thin bars and four corner masks, so nothing clips the moving photo layers on the Mali-400); on a pair it lands
  exactly on the gap and corners already in the rendered bitmap.
- **Empty band:** with the clock, date and weather all off and no plugin instances, or with all of them hidden (night,
  *on tap*, *only when the value changes*), the band collapses and the photo goes back to full screen, animated.
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
- **`refresh`**: `default_seconds` between `update` calls, `min_seconds` as the floor for `invalidate()`, and optional
  `stale_seconds`: how old the source's own measurement (`ctx.dataTime`) may be before the widget is greyed (a weather
  station that uploads every 10 minutes: 1800).
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

`main.js` exports one object and returns plain data. Fetching and drawing are separate: **`update`** is the only
call on a timer that may use the network and keeps what it got in `ctx.state`; **`render`** and **`detail`** draw from
that and can't use the network (`ctx.http` throws there), so the frame never waits for a slow device to draw, and the
50 ms budget for drawing is all computation.

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

  // Called every `refresh` seconds, right before render: fetch, and keep the data in ctx.state.
  async update(ctx) {
    if (ctx.state.live) return; // the WebSocket keeps ctx.state current
    const r = await ctx.http.get(ctx.settings.base_url + '/api/states', { headers: { Authorization: 'Bearer ' + ctx.settings.token } });
    ctx.state.solar = /* … */ 0;
  },

  // Called after every update and after ctx.invalidate(). Draw from ctx.state; no network here.
  render(ctx) {
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
| `store` | Small persistent key-value storage, 64 KB per instance (e.g. yesterday's total): `get/set` strings, `getJson/setJson`. Over the limit, `set` throws. |
| `http.get(url, options)`, `http.post(url, body, options)` | Fetch, in `start`, `update`, `action` and WebSocket handlers (not in `render` or `detail`). Only URLs allowed by `permissions.http`. Resolves to `{ status, ok, contentType, text, json() }`; a non-2xx status doesn't throw (the plugin decides). `options.headers` (not `Host`, `Cookie` or `User-Agent`: the frame sends `kinframe-frame/<version>` so services can tell who calls); a `body` object is sent as JSON. 10 s timeout, 1 MB response limit, no redirects. |
| `ws.connect(url, handlers)` | One WebSocket per instance, same rules. The frame reconnects with backoff and calls `onOpen` again. |
| `invalidate()` | Ask for a `render` now (e.g. after a WebSocket message), without an `update`. Throttled to `refresh.min_seconds`. |
| `format.power/energy/percent/temperature/number/duration/time(value)` | Formats in the frame's language and units, so plugins don't each reinvent "1,2 kW". `temperature` follows the frame's °C/°F (optionally with decimals); `number(value, decimals, unit)` for anything else ("2,3 m/s"). |
| `dataTime` | Optional: when the source measured what the view shows (epoch ms). For sources that update every few minutes; the frame greys the view when it is older than `manifest.refresh.stale_seconds`, even while fetching works. |
| `t(key, vars)` | The plugin's own translated strings from `locales/`. |
| `log(...)` | Goes to the frame's diagnostics, visible to the owner, never uploaded with secrets in it. |
| `now()`, `timezone`, `language` | The frame's clock, time zone and language tag ("nl", "zh-CN"). |
| `localTime(epochMs?)` | The frame's calendar for an instant: `{ date: "2026-10-25", year, month, day, hour, minute, weekday }` (weekday 1 = Monday) in the frame's time zone. QuickJS's `Date` only knows the device's own zone. |

There is no `fetch`, `XMLHttpRequest`, timers beyond what the runtime gives, file system, or access to the photos.

Besides `render`, `start`, `detail` and `stop`, a plugin with `"actions": true` in its permissions exports
`async action(ctx, id)`: the owner tapped one of its buttons (§4.2). It may return a view to show at once; the frame
renders again right after either way. `render` may return `{ "type": "none" }` to hide the widget (and free its place
in the band) when there is nothing to show; `null` means "no new view" and keeps the last one.

## 4. What a plugin can draw: views (API 1)

A view is a small tree of **components** the frame renders natively in Compose. The set is deliberately small; new
components come with a new API version.

| Component | Shows | Example |
|---|---|---|
| `value` | Icon, big number, unit, optional label and trend arrow | `⚡ 1,2 kW  ↑` |
| `rows` | Up to 4 `value`s stacked | grid / solar / home |
| `gauge` | Ring or bar from 0–100 % with a label inside, or a two-sided `dial` (−100–100 %, zero at the top) | car battery 68 %; grid 1.240 W |
| `flow` | Up to 5 nodes (icon + value) with animated arrows between them, speed by power | solar → home ← grid |
| `sparkline` | A small line chart of up to 96 points, optional min/max | power over the last 24 h |
| `text` | One or two short lines (max 60 chars each) | "Charging · full at 23:10" |
| `stack` | Vertical or horizontal group of the above, max depth 2 | gauge + text |
| `image` | A picture from an http(s) URL, square or round, size by form (§4.2) | album art |
| `actions` | 1–5 round buttons, tappable in the detail card only (§4.2) | ⏮ ⏯ ⏭ |
| `progress` | A thin bar from min to max, or a marker dot on the track; can move on by itself | track position, today's temperature in its range |
| `none` | Hide the widget; only as the whole view | nothing playing |

A `gauge` takes up to three small `sub` values under the ring or dial (an icon, usually an arrow, in a tone, and the
number in text colour: today's ↑ 11,8 / ↓ 14,5 kWh beside the live power). A `flow` node takes `percent`, drawn as a
ring around it (a home battery's charge).

Every component accepts `tone` (`neutral`, `good`, `warn`, `bad`, `accent`, `info` (violet)), which maps to the frame's palette, and an
icon from a fixed set of line icons (home, sun, grid, battery, car, plug, flame, water, thermometer, wind, leaf,
euro, rain, music, play, pause, skip-next, volume-up, ...; 51 so far, more can be added within API 1). A `value` can turn its icon with `icon_rotation` (0–360
degrees clockwise): the `direction` icon is a vane arrow pointing north, for a wind direction or a heading.

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

### 4.2 Pictures, buttons and moving bars

Added for the now-playing plugin (§9.5), and generic enough for others (a camera snapshot, a washing machine's cycle).

- **`image`** names an http(s) URL; the plugin never handles bytes. The *host* fetches it through the plugin's own
  network permissions (so `local_only` and the allowed origins apply exactly as to `ctx.http`), at most 768 KB, decodes
  it subsampled to at most 336 px on its long side (cheap on the RK3126; a 168 dp picture stays sharp on a 2× screen),
  and keeps the last 6 per instance. Until it has loaded the view shows a quiet tile of the same size, so nothing jumps.
  Sizes: 56 dp in the band, 76 dp in the corner, 168 dp in the detail card; square with rounded corners, or round.
  An image that fails to load stays the tile and is logged; it never fails the view.
- **`actions`** are drawn only in the **detail card**: on the photo or in the band a tap opens the card, so a widget
  can never turn the photo into a remote control by accident, and a tap's meaning stays the same for every widget. The
  manifest must say `"actions": true` (shown to the owner in the catalog); a view with buttons from a plugin without it
  is refused like an invalid one. A press runs `action(ctx, id)` on the plugin thread (same CPU and time limits), the
  card's 20 s auto-close restarts, the instance renders again and the card fetches `detail()` afresh. Buttons are 52 dp
  (a `primary` one 64 dp, filled), with a label under them and an accessibility label. A compact control row in the
  band is deliberately left out (§11).
- **`progress`** is a thin bar (`min` 0 by default, `max`), or with `style: "marker"` a dot on the track. With
  `running` and `at` (epoch ms the value was measured), the frame moves the value on by one per second until `max`,
  redrawing once a second only while it runs, so a track's position moves smoothly although the plugin refreshes every
  15 s. `time` shows the values as `1:23 / 3:45`.
- **`none`** hides the widget until the next view (the music stopped).

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

- **Runtime:** each instance runs in its own **QuickJS** runtime (small, embeddable, has memory limits and an
  interrupt handler for CPU limits, runs fine on armeabi-v7a and Android 6). A plugin gets **8 MB** of heap and **50 ms
  of JavaScript per call** (time spent awaiting `ctx.http` doesn't count); over the limit, the call is aborted and
  counted as a failure. On the timer the host calls `update` and then `render`; a failed `update` skips that render
  and the last view stays. All instances share one background thread at low priority, so a plugin can never stall the
  slideshow's main thread. Details of the implementation: §13.
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

## 9. The first plugins (maintained by the project, `kinframe.*`)

### 9.1 Home Assistant energy flow (`kinframe.ha-energy`)

(As built, with what real installations need beyond this design: §13.)

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
- **View:** `value` with the current power, `tone` good when exporting and warn above a threshold the owner sets; or a
  `dial` gauge: violet (`info`) clockwise while using, green (`good`) anticlockwise while returning, only the watts in
  the middle, full scale set by the owner (default 4000 W either way). Detail card: per-phase rows and today's totals. No cloud account, no Home Assistant needed.

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

### 9.4 Weather station (`kinframe.weather-station`), e.g. Weathercloud

- **Settings:** the station (`source`, for now only `weathercloud`, and `device`: the station's code, or the link to
  its public page, `https://app.weathercloud.net/d<code>` → `<code>`), a style (*Temperature*: one value, or *All
  values*), wind in km/h (mph with °F), m/s or Beaufort, how often to check (every 5, 10, 15 or 30 minutes; 5 is the
  minimum). Pressure is always hPa, temperatures follow the frame's °C/°F. The widget is switched on in the frame's
  Settings → **Weather**, next to the forecast (below).
- **Data, and the catch:** Weathercloud has **no published API**. Its own web app loads
  `GET https://app.weathercloud.net/device/values?code=<code>` (with `X-Requested-With: XMLHttpRequest`):
  `{"epoch":1792941451,"bar":988.8,"temp":12.8,"hum":56,"wdir":253,"wspd":2.3,"dew":4.2,"heat":12.8,"rainrate":0,
  "rain":15.1,"uvi":0,"solarrad":31.7,"wspdavg":2.3,"wdiravg":253}` (°C, %, hPa, m/s, degrees the wind comes *from*,
  mm today, mm/h, W/m², UV index; `epoch` is the measurement time, about every 10 minutes), and
  `GET /device/info/<code>` for the station's city and altitude (numbers there come as strings). Because this is
  unofficial it can change or stop without notice; the settings say so. To be a good citizen the widget asks at most
  every 5 minutes, only while the screen is on, backs off after errors (2×, 4× the interval), asks for the station's
  place only when the detail card opens (at most once a day), and sends a `User-Agent` naming kinframe. Permission:
  the fixed host `https://app.weathercloud.net` only.
- **Sources are pluggable** (`StationSource`): readings are normalised to metric (`StationReading`), so an Ecowitt
  gateway's local API (`local_only`), the Weather Underground PWS API with the owner's key (a `secret`), or a station
  that uploads to the kinframe server ("custom server" upload in the station's own app, received and relayed by the
  server) can be added as another source without touching the views.
- **Views:** *Temperature*: a `value` with the temperature (one decimal), a trend arrow when it moved 0.5° or more in
  the last hour, and a small line "56 % · W 8 km/h" (no direction in calm air). *All values*: two `rows` tables:
  temperature, humidity with dew point, wind (a `direction` arrow turned to where the wind blows, the compass point
  and gusts when the station reports them); rain today (and the rate while it rains), pressure with its 3-hour
  tendency (rising/falling by 1 hPa or more, else steady) and sunlight/UV. In the portrait strip the tables show
  icons and values only. Detail card: temperature with the station's city and altitude, "Updated 4 min ago" (or "No
  new reading since 13:05"), "feels like" when it differs, 24-hour temperature and pressure sparklines, and every
  value. Labels longer than the view limits (some translations) are shortened with "…", never rejected.
- **History:** the last 26 hours of temperature and pressure, one sample per 5 minutes at most, are kept in
  `ctx.store` (a few KB), so trends and charts survive a restart.
- **Stale:** the widget sets `ctx.dataTime` to the station's `epoch`; a reading older than 30 minutes greys the
  widget even while Weathercloud answers (the station is offline). Fetch errors follow the host's rules.
- **Two weather sources, two switches.** The frame's Settings → **Weather** has *Weather forecast* (from the
  internet for the frame's location, Open-Meteo: `widgets.weather`, synced like every setting and also in the apps,
  off without a location) and *My weather station* (this widget, frame-local) side by side. The temperature is shown
  once:

  | Forecast | Station | Clock's weather block | Station widget |
  |---|---|---|---|
  | on | off | forecast icon, temperature, low/high | — |
  | off | on | nothing | its temperature, humidity, wind (as set) |
  | on | on | forecast icon and low/high with the **station's** temperature (while its reading is under 30 minutes old, else the forecast's) | everything **except** the temperature: *Temperature* style leads with humidity (the wind as its label), *All values* drops the temperature row |

  This replaces the earlier opt-in *Use my weather station for the current temperature* (`use_for_weather`, now
  ignored). The host passes `hide_temp: true` in the widget's settings while the forecast is on, so a plugin
  version of the widget gets the same hint.
- Screenshots: [`docs/plugins/`](plugins/) `station_*.png`, `weather_both_on.png`, `weather_station_only.png`, `settings_weather.png`.

### 9.5 Now playing via Home Assistant (`kinframe.ha-media`)

- **Settings:** Home Assistant URL and token, the `media_player` entity (a Spotify, Sonos or Cast player as Home
  Assistant sees it), and whether to keep showing while paused.
- **Data:** the player's state and attributes over `subscribe_entities` (title, artist, album, `entity_picture`, which
  Home Assistant serves through its own `/api/media_player_proxy/…?token=…` on the same address, `media_duration`,
  `media_position` with `media_position_updated_at`, `volume_level`, `supported_features`).
- **View:** album art and title/artist in the corner or band; nothing (`none`) while nothing plays. Detail card:
  larger art, title and artist · album, a progress bar that moves by itself, and buttons for what the player supports:
  previous, play/pause (primary), next, quieter, louder (§4.2). Buttons call `media_player` services over the REST API
  and update the card at once.
- **Alternative:** a direct Spotify integration (§11.1, item 12).

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

### 11.1 What writing the first plugins showed (step 2)

Implementing every plugin of §9 and four community-style examples (`plugins/examples/`: iRail departures, air
quality, electricity prices, bin day) against real data showed what API 1 lacked. **Added** (small and generic, each
needed by a first-party plugin): `image`, `actions` and `progress` (§4.2, now playing), `none` (now playing, bin day),
`percent` rings on flow nodes (the home battery), `sub` values under a gauge (the P1 dial with today's totals, after
the HomeWizard Energy Display's hero ring), a `marker` style and `min` on `progress` (a value inside a range, the same
display's temperature row), media icons, `multiple` entity settings (two inverters summed into one solar node),
`ctx.localTime` (the P1's "today", bin day) and JSON helpers on `ctx.store`. **Proposed**, with the evidence:

1. **A `list` component** (a time, a title and a trailing badge per row, a tone per row). *Departures:* `rows` hold one
   value and a 32-character label, so "22:23 +3 · Brussels Airport - Zaventem · spoor 2" had to be cut
   ("Brussels Airport - Za… · spoor 2"), and in the band, where rows drop their labels, only the times remain.
2. **Relative dates and times in `ctx.format`** ("tomorrow", "Mon 27 Oct", "8 h ago"). *Bin day and the car* each
   carry their own "Today/Tomorrow" and "Updated {n} min ago" strings in every language, which every plugin would
   repeat. Needs the frame's own translations (ICU's relative formatter needs API 24; the frame is API 23).
3. **Sparkline markers, bars and longer series.** *Electricity prices:* a day has 92, 96 or 100 quarter hours (the
   fixture is the day the clocks went back: 100), the sparkline takes 96, so the plugin averages down; and it can't
   mark "now" or the cheapest hour, which is the whole point of a price chart. A `bars` style with a highlighted index
   (and up to 100 points) would do.
4. **The frame's location in `ctx`** (coarse, from the weather settings). *Air quality* asks the owner to type
   latitude and longitude although the frame already knows its place for the forecast.
5. **Shared code between plugins.** The Home Assistant connection (auth, `subscribe_entities`, the REST fallback) is
   copied into all three `kinframe.ha-*` plugins. Plugins are one module with no imports; the plugin kit (§8) should
   bundle authors' modules into one `main.js` (esbuild), so the frame stays import-free.
6. **One Home Assistant connection per frame**, not per instance. Energy, two cars and now playing open four WebSockets
   with four copies of the same token. A host-level "integration" (shared base URL and token, entity subscriptions
   multiplexed) would also give the settings form its entity picker (§6.1) for free.
7. **Manifest defaults for the owner's choices:** a default visibility (*only when it changes* suits the car, §9.3),
   and setting **presets** (the Kia/Hyundai entity names are filled in from a prefix by the plugin itself today).
8. **Typography for values:** raised small decimals ("12 €67", "21 °1", as on the HomeWizard Energy Display) read well
   from across a room. It could be automatic for any `value` with decimals, frame-wide, rather than a plugin option.
9. **Colours per quantity** (electricity violet, water blue, gas pink, solar green, heat orange) instead of only
   semantic tones. `info` (violet) is effectively "electricity" already. Adding `water`, `gas`, `heat` tones would keep
   "plugins never pick colours" while letting a water widget look like water; it needs checking against both
   palettes (paper and photo) and colour-blind pairs before it goes in.
10. **Per-day water and gas.** The P1 detail has today's gas; a HomeWizard water meter (local API `/api/v1/data`,
    `total_liter_m3`, `active_liter_lpm`) would be a small plugin with the same day-baseline pattern, shown as a ring
    against a typical day (the Energy Display's water ring).
11. **Buttons outside the detail card.** A compact control row in the band (just ⏯) was left out on purpose (§4.2);
    revisit if owners ask for it.
12. **A direct Spotify plugin** instead of going through Home Assistant: the Web API's player endpoints need OAuth
    (authorization code with PKCE) done in the family app or on the server, a refresh token stored as a plugin
    `secret` (step 3), a fixed host permission for `api.spotify.com`, and **Spotify Premium** for anything that
    controls playback (reading "currently playing" works without). Spotify's rate limits and its developer terms
    (apps in development mode are limited to 25 users until approved) also apply. Through Home Assistant none of that
    is kinframe's problem, and Sonos and Cast come along.

## 12. Step 1 as built (frame app)

What exists now, and where it differs from or adds to the design above. Everything runs on the frame; there is no
QuickJS, registry, server API or app UI yet.

| Part | Where |
|---|---|
| View model, strict JSON parser/validator, `ctx.format`, JSON builder for built-ins | `android/core/.../core/plugin/` (`PluginView.kt`, `PluginViewParser.kt`, `PluginFormat.kt`, `ViewJson.kt`) |
| Compose components (all forms), palette, shared animation clock, scale-to-fit | `android/core/.../core/plugin/ui/` (in core so the family app can use them for the live preview in step 3) |
| Icons | 42 line icons in `KinIcons` (drawn for kinframe), mapped by name in `PluginIcons` |
| Schema, TypeScript types, examples, invalid examples | `docs/api/plugin-views/` (the tests read them; debug builds package them as assets) |
| Host, `PluginInstance`/`PluginContext`, `ctx.http` with `local_only`, layout geometry, overlay/band/detail UI | `android/frame/.../frame/plugins/` |
| Built-in HomeWizard P1 widget and mDNS discovery | `android/frame/.../frame/plugins/homewizard/` |
| Built-in weather station widget (Weathercloud) | `android/frame/.../frame/plugins/station/` |
| Frame-local settings (Settings → Home widgets, behind the owner PIN) | `PluginSettingsSection.kt`, stored in the `plugins` SharedPreferences in the shape of §10's `plugins: [...]` |
| Dev preview (debug builds) | `PluginPreviewActivity` (`adb shell am start -n app.kinframe.frame/.debug.PluginPreviewActivity --es layout band ...`), a "Demo widgets" switch that cycles the examples, and `PluginScreenshotTest` |

Screenshots (neutral placeholder photos): [`docs/plugins/`](plugins/).

**Forms.** Every component has four forms: *corner* (overlay on the photo, white with the clock's text shadow and a
soft top-corner vignette), *detail* (the card, ink on the surface colour), *column* (the landscape band: icon above
value) and *strip* (the portrait band: side by side). A band of 180 dp or more (column) or 140 dp (strip), e.g. on a
1920×1080 frame, uses the corner form scaled to fit. In the band the frame picks a `stack`'s axis (column: vertical,
strip: horizontal). A `flow` in the corner puts the node with the most edges in the middle and the others at its start,
end, top and bottom; in the band the nodes form a line with that hub in the middle, and an edge that would have to
cross another node is shown as a short animated arrow beside its far node instead of a line around it. In the strip,
`rows` show icons and values without their labels (as the column does), and a strip that is a little too full
shrinks to at most 0.75 instead of cutting off its last widget.

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
photo. The clock, date and weather always move into an open band (foot of the column or end of the strip), so with
any of them on the band never collapses while awake.

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
Status: greyed after 3× the refresh interval without a good view, or once the source's own measurement
(`ctx.dataTime`) is older than the plugin's `staleAfterSeconds`; "unavailable" (a discreet icon) after 3 failures
with nothing to show; hidden after 15 minutes (or 3× the interval plus a minute for slow plugins). Plugins that refresh
less often than every 15 s back off to at most 4× their interval instead of a minute, so an error never makes them ask
more often. `invalidate()` wakes the instance at once (the throttle to
`refresh.min_seconds` comes with step 2).

**`local_only`.** Enforced in OkHttp's DNS step: every address a name resolves to must be private, checked when the
connection opens, so a name can't pass a check and then re-resolve to a public address. Private = RFC 1918,
169.254/16, fc00::/7, fe80::/10 (and IPv4-mapped forms of those). Loopback, 100.64/10 (carrier-grade NAT) and
everything else are refused. No redirects are followed; 10 s timeout; 1 MB response limit.

**HomeWizard P1 (built in).** Polls `GET http://<host>/api/v1/data` every 5 s. Fields used: `active_power_w` (negative =
returning), `active_power_l1_w`…`l3_w`, `total_power_import_kwh`/`export_kwh` (or the sum of the `_t1`/`_t2` tariff
totals on older firmware) and `total_gas_m3`. The v1 API only has running totals, so *today* is counted from the
first reading of the day (kept in `ctx.store`). Main view: a `value` (bolt "Using" / sun "Returning", trend arrow on a
10 % change over 30 s, `good` when returning, `warn` above the owner's threshold), a `dial` gauge (setting `max_w`, the
full scale, default 4000 W; past it the arc stays full and the number keeps counting) or a `flow` grid ⇄ home.
Detail: the value, the last hour as a sparkline (96 buckets), per-phase rows and today's used, returned and gas.
Discovery browses `_hwenergy._tcp` with NsdManager while the settings are open (resolving one service at a time, as
Android 6 requires), shows "Local API is off" from the TXT record, and picks the meter when there is exactly one. The
v2 API (HTTPS + token) is not supported yet.

**Weather station (built in).** As §9.4. Polls `GET https://app.weathercloud.net/device/values?code=<code>` with
`X-Requested-With: XMLHttpRequest` every 5–30 minutes; `PluginHttp` takes an `allowed` predicate per plugin (here only
that host over HTTPS, not `local_only`) and request headers, and sends `User-Agent: kinframe-frame/<version>
(+https://github.com/jeroentrappers/kinframe)`. Numbers may be JSON numbers or strings; implausible values (humidity
over 100 %, pressure 0) are dropped. The settings accept the code, `d<code>` or any link containing `/d<code>` or
`code=<code>`. The two weather switches live in `weather/WeatherSettingsSection.kt`; `WeatherSources` holds the
rule above (`forecastOn`, and `forHost`, which adds `hide_temp` to the station instance while the forecast is on, so
the host restarts it with the new view). `FramePlugins.stationNow` carries every reading to the slideshow, which
shows it instead of the forecast's current temperature (`Weather.withStation`) while it is at most 30 minutes old;
with the forecast off there is no weather block to put it in.

**Changes to the design above.** None of substance; these are additions or decisions the design left open: the band
fallbacks (64 dp minimum, not beside the calendar sidebar), render-after-settle for the band animation, unknown
properties are rejected, edge power is a magnitude, the icon set has 42 icons (40 plus `rain` and `direction`, added
with `icon_rotation` on `value` for the weather station before API 1 went to third parties), `ctx.format.number`,
`ctx.dataTime` and request headers in `ctx.http.get`, compact flows use direction arrows for non-adjacent edges, and
the new-photo celebration is the "N new" moment (the frame has no separate celebration animation).

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

**Screenshots.** [`docs/plugins/`](plugins/): `band_*`, `overlay_*`, `station_*` (the weather station in the corners,
the band column and strip, and its detail card), `detail_landscape.png`, `gallery.png` (every example in the corner,
column and strip forms) and `detail_forms.png` come from `PluginScreenshotTest` (Robolectric, 1280×800 and 800×1280
mdpi, 1920×1080 xhdpi); `emulator_*` are from the dev preview on the emulator.

**What step 2 (the QuickJS host) plugs into.** `PluginInstance` is the seam; see §13 for how it was done.

## 13. Step 2 as built: the plugin host

| Part | Where |
|---|---|
| Manifest parser and validation, bundles, locales | `android/frame/.../frame/plugins/js/` (`PluginManifest.kt`, `PluginBundle.kt`) |
| Permissions, `local_only`, http and WebSocket | `js/PluginNet.kt` |
| The QuickJS instance and the `ctx` bridge | `js/QuickJsPluginInstance.kt`, `assets/plugin-host/prelude.js` |
| First-party plugins (shipped in the APK as assets `plugins/<id>/`) | repository `plugins/kinframe.*` (manifest, `main.js`, `locales/`, `fixtures/`) |
| Community-style examples (tests only, not shipped) | `plugins/examples/community.*` |
| Tests | `frame/src/test/.../plugins/js/` (sandbox, P1, Home Assistant with a fake server, examples, timing), `androidTest/.../QuickJsOnDeviceTest` (Android 6) |

**Engine.** [quickjs-kt](https://github.com/dokar3/quickjs-kt) 1.0.15 (Apache-2.0, maintained, QuickJS 2026-06):
minSdk 23, prebuilt for armeabi-v7a, arm64-v8a, x86 and x86_64, a desktop build for JVM unit tests (so the tests run
the real engine), coroutine-friendly async bindings, memory and stack limits, and an interrupt that cancelling a call triggers. (Its
own execution-time limit is wall clock from the start of an evaluation, awaits included, so the host keeps the
budget itself; see *Sandbox*.) Building
QuickJS-ng with the NDK and our own JNI would have given the same at the cost of maintaining the glue; Zipline and
the older `app.cash.quickjs` target Kotlin/JS modules or are unmaintained. The APK grows by about **0.8 MB** (the
library is 382 KB compressed for armeabi-v7a and 413 KB for arm64-v8a; both stay, since a 64-bit-only device can't
fall back to 32-bit libraries).

**Sandbox.** One runtime per instance (nothing shared between instances), 8 MB heap, 256 KB stack, 50 ms of
JavaScript per call (1 s at start, for compiling the module and for every call until the first render has finished:
on the Denver that cold first render took about 68 ms, since it also starts threads and loads classes). The host
counts that time itself: its clock stops
while a `ctx.http` call is in flight, and a watchdog on its own thread checks it every 5 ms and cancels the call when
it's over budget, which interrupts the JavaScript. (The first beta on the Denver used QuickJS's own limit, which
counts the wait too: a P1 answer slower than 50 ms over Wi-Fi failed about one render in 60.) An endless loop is
interrupted, also after an await (an interrupt inside an async function arrives as a rejected promise, which the host
still reports as "took longer than 50 ms"); running
out of memory fails the call and the runtime is rebuilt on the next one. A 10 s wall-clock limit per call stays (the
host's), and cancelling a call interrupts it. The raw bindings are hidden behind `ctx` (the global `__kf` is removed
after the prelude). What QuickJS can't contain is a crash in native code, which would take the app down; that's the
engine's own robustness, not something the host can isolate in one process.

**Calls.** `main.js` is loaded once as an ES module (`import plugin from 'main'`); each call then evaluates a tiny
*script* (`__kf_call('render')`) whose result comes back through a binding, because evaluating a promise doesn't
return its value, and because evaluating a new module per call would keep every module in memory (measured: +450 KB
per 500 calls as modules, flat as scripts). Calls are serialised per instance; a WebSocket message waits for a running
render, and its handler has the same budget as a call. `update` runs on its own clock (every refresh interval, longer
after failures), so frequent `invalidate()`s from a WebSocket only cause renders and never hold off an update. While
`render` or `detail` runs, the `http` binding refuses with "render can't use the network: fetch in update()". The
host logs, per instance and every 60 renders, the render times and the update times with and without the network.

**Network.** `PluginPermissions` turns the manifest's entries into origins (`settings.<key>` through
`LocalAddress.baseUrl`, or a fixed https origin). A request must match one (scheme, host, port, under the setting's
path). It stays on the home network when the setting is `local_only` or the URL is plain `http://`: names resolve
through `LocalAddress.dns`, and IP literals (which OkHttp doesn't pass to the DNS) are checked separately. WebSockets
follow the same rules (`permissions.websocket`), ping every 30 s, and reconnect with backoff (2 s doubling to 60 s,
reset after a successful open), calling `onOpen` again.

**Plugins as built.**

- `kinframe.homewizard-p1`: the Kotlin widget ported line by line (the old tests, unchanged in substance, now run
  against the plugin in QuickJS); same plugin id and settings, so existing frames keep their configuration. The dial
  shows today's returned and used kWh under it.
- `kinframe.ha-energy`: power sensors mapped per node (Home Assistant's Energy dashboard only has kWh statistics),
  several solar sensors summed, sign conventions per sensor (`grid_sign`, `battery_sign`), a house sensor without a
  unit taken as W, the car charger split out of the house, the battery's charge as a ring. Flow allocation: solar to
  the house, the car, the battery, then the grid; the battery next; the grid covers the rest. Live through
  `subscribe_entities`, REST while the socket is down. No `dataTime`: Home Assistant stamps a sensor only when it
  changes, so a quiet one (solar at night) would look stale.
- `kinframe.ha-ev`: the Kia/Hyundai preset finds `sensor.<prefix>_ev_battery_level` and friends; any other battery
  works with explicit sensors (the fixture's second car has only level and range). `dataTime` from the car's own
  "last updated", greyed after 6 h.
- `kinframe.ha-media` (§9.5): now playing through `media_player`, buttons only for what the player supports
  (`supported_features`), volume by `volume_set` ±5 % or `volume_up/down`.

**Performance.** JavaScript per render with the data in hand, JVM on a desktop core: P1 (gauge plus detail) 0.18 ms,
energy flow 0.11 ms, car 0.05 ms, now playing 0.05 ms (p50, `PluginTimingTest`). On an Android 6 emulator the P1
plugin starts in 5.7 ms and renders in 0.16 ms (`QuickJsOnDeviceTest`). The RK3126's Cortex-A7 is roughly 10–20×
slower than a desktop core, so expect 1–4 ms per render and well under 100 ms to start: far inside the budget, and
negligible next to drawing. On the Denver (0.7.0-beta.1, the P1 as a dial): the runtime boots in 68 ms with a
104 KB heap, which grows to about 128 KB; a render including the HTTP request to the meter takes 68 ms at p50 and
up to 283 ms, nearly all of it the network. Without the network (0.7.0-beta.2, the host's clock: JavaScript plus the
bridge around it) a render takes 9.7 ms at p50. The host logs these per instance every 60 renders
(`KinFramePlugins`).

**Screenshots** (views straight from the plugins, run in QuickJS on the fixtures; a drawn stand-in for album art):
[`docs/plugins/`](plugins/) `js_band_column.png` (energy flow with the battery ring, now playing), `js_strip.png`
(car charging, P1 dial), `js_corners.png`, `js_detail_media.png` (buttons, progress), `js_detail_energy.png`,
`js_detail_car.png` and `new_components.png` (the additions in every form).

**Still open (steps 3–5).** Settings for JavaScript plugins come from the manifest only in principle: the frame's
settings screen still has its hand-made P1 and station sections, and the Home Assistant plugins can only be configured
by writing their instance settings (no form, no entity picker, no secret storage yet: step 3, with the server API of
§10). No registry, signing or plugin kit (step 4). The weather station is still Kotlin (it feeds the slideshow's own
weather corner). `InstanceState.error` isn't reported to the server yet.
