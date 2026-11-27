# Framing: zoom and position per orientation

Status: built 2026-11-27 (server, web, iOS, Android); open: edit after sending on iOS and web, the frame's own editor. Extends the focal point (product-spec.md §4 "Adjust", §5 "Fill/fit").

## 1. Why

A photo has one focal point and a fit mode. In fill mode a screen shows the photo at the smallest zoom that covers
it, centred on the focal point. That zoom crops one axis only (a 4:3 photo loses top and bottom on a landscape
frame, the sides on a portrait one), so the one point already positions landscape and portrait separately. What
people can't do is **zoom in**: show only Oma's face out of a wide group photo, or a tighter crop for the portrait
frame than for the landscape one.

Framing adds, per screen orientation, a zoom and a centre. It changes how the photo is shown on frames and the TV,
never the photo itself: feed posts, thumbnails, the library and downloads stay the whole photo (§9).

## 2. Data

```
Framing      { landscape?: FramingView, portrait?: FramingView }
FramingView  { x: 0..1, y: 0..1, zoom: 1..4 }
```

- `x`, `y`: the centre of the visible part, as fractions of the master rendition from its top-left (the same
  coordinates as `FocalPoint`). `zoom`: 1 is today's cover crop, 2 shows half the width and half the height of it.
- Either view may be missing. A screen uses the view of its orientation: **landscape when width ≥ height**, else
  portrait (a square screen is landscape).
- Values are rounded to 3 decimals by the server. Out of range: `422 validation_failed` (field `framing`).
- Photos and live photos only. Videos ignore framing (the server drops it, as for `trim` on photos).
- Framing applies only when the effective fit is `fill`. In fit mode it is kept but unused, so switching back to
  fill brings it back.

Where it lives:

| Object | Field | Meaning |
|---|---|---|
| `Item` | `framing` (nullable) | The sender's framing. |
| `SendCreate.items[]` | `framing` | Set at send time. |
| `PATCH /v1/items/{id}` | `framing` | Change it later (sender or managers, like the focal point); `null` clears it. |
| `Placement` | `framing_override` (nullable) | A frame's own framing for this placement (frame owner at the frame). |
| `PATCH /v1/device/placements/{id}` | `framing` | Set the override; `null` clears it. |
| `TvSlide` | `framing` (nullable) | The effective framing for the TV (override, else the item's). |
| `FederationItem` | `framing` | Carried to frames on other servers. |
| `Discovery` | `framing: true` | Senders offer zoom only when the server stores it (§7). |

### 2.1 Compatibility with the focal point

`focal_point` stays and stays meaningful. When an item gets a framing and no explicit `focal_point`, the server sets
the focal point to the centre of the landscape view (else the portrait view). Senders send both anyway. So:

- old frame apps, the old TV app, and frames on servers without framing show the photo centred where the sender
  meant, at zoom 1;
- thumbnails (centred on the focal point) follow the framing's centre;
- clients that don't know framing keep working.

## 3. Geometry (the one rule every renderer follows)

Screen `W×H`, photo `w×h` (after rotation and EXIF orientation), view `{x, y, zoom}`:

```
s  = max(W / w, H / h)          // today's cover scale
cw = min(w, W / (s · zoom))     // visible part, in photo pixels
ch = min(h, H / (s · zoom))
left = clamp(x·w − cw/2, 0, w − cw)
top  = clamp(y·h − ch/2, 0, h − ch)
```

The rectangle `(left, top, cw, ch)` of the photo is drawn to fill the screen. With `zoom = 1` and `{x, y}` = the
focal point this is exactly today's `FocalCrop.fillSource`, so nothing changes for photos without framing. The rule
uses the real screen shape, so a view made in the 16:10 editor preview also works on 16:9 or 1024×600 frames
(slightly more or less shows at the edges, centred on the same point).

### 3.1 Which view a screen uses

For the screen's orientation `o`, the first that exists:

1. the placement's `framing_override[o]` (the frame owner's choice);
2. the placement's `focal_override` as `{x, y, zoom: 1}` (frames made before framing);
3. the item's `framing[o]`;
4. the item's `focal_point` as `{x, y, zoom: 1}`, else the centre.

### 3.2 Fill or fit

- A view from steps 1 or 3 counts as **fill chosen**: the frame fills even when the crop is "too aggressive" (a
  portrait photo on a landscape frame), the case that otherwise falls back to fit or a smart pair.
- Without one, today's rules apply unchanged.

### 3.3 Sharpness

Frames decode the cached rendition at just the screen resolution. With framing they decode enough for the visible
part: the required source size is the screen size times `zoom` (`FocalCrop.sampleSize` gets `W·zoom × H·zoom`).
The master rendition's long edge is at most 2560 px, so a zoom of 4 on a 4:3 photo leaves about 640 photo pixels
across a 1280 px screen.

### 3.4 Ken Burns

With a view from steps 1 or 3 (the person chose the crop), Ken Burns stays inside it: it starts and ends at the
view, scaled between 1.00 and 1.08, with a drift of at most 3% of the screen. No wide pull-out to the whole photo.
Without one, today's motion applies unchanged.

The motion, the same on every renderer (frame and TV must match): from the placement's Ken Burns random sequence
(the same seed as today), `scale = 1.04 + r1 · 0.04`, `reach = min(0.03, (scale − 1) / 2) · (0.5 + 0.5 · r2)`,
`angle = r3 · 2π`. Two keys on the usual Catmull-Rom loop: the view itself `{scale 1, x 0, y 0}`, then
`{scale, cos(angle) · reach, sin(angle) · reach}` (translations as fractions of the screen), one key every half the
slide's hold time, so the slide starts and ends at the view.

On the TV, `TvSlide.framing` is already the effective framing; when the frame has only a focal override (§3.1 step 2)
the server sends no framing and `focal_point` is that override.

### 3.5 Pairs and collages

A tile uses the view's centre (`x`, `y`) as its focal point and ignores `zoom`: tiles have their own shape and size.
A photo framed with zoom > 1 for the screen's orientation is not paired (the sender wants it shown big).

## 4. Editor (family apps: Android, iOS, web)

Today the item editor has a "tap to set the focal point" image, the fill/fit choice, and two small previews
(landscape 16:10, portrait 10:16). With framing (only when `discovery.framing` and fill is on):

- **The previews become editors.** A tap on a preview opens it large (a sheet / dialog on phones, inline on wide
  screens) with the frame shape as a window over the photo:
  - drag to move, pinch (touch) or the zoom slider (all platforms, and the only way with a mouse besides the wheel)
    to zoom from 1× to 4×;
  - the window can't leave the photo (the clamp of §3);
  - **Reset** returns this orientation to the focal point at 1× (removes the view);
  - **Done** keeps it.
- The small previews always show the result (§3 with the preview's 16:10 or 10:16 shape).
- Tapping the large image to set the focal point stays. It moves both views' centres to that point and keeps their
  zoom. With no views it is the old focal point.
- **Soft warning** under the editor when the visible part is less than 960 photo pixels across the screen's long
  side, computed from the item's width and height scaled to the master's 2560 px long edge: "May look less sharp on
  the frame." Not a block.
- Accessibility: the editor has actions "Zoom in", "Zoom out", "Move left/right/up/down" (10% of the visible part)
  and "Reset"; its value reads "Landscape: 2×, centred 40%, 30%".
- The same editor opens for edits after sending (`PATCH /v1/items/{id}`), next to the caption.

Sending: `framing` contains only the orientations the person changed. `focal_point` is the landscape view's centre
(else the portrait one's, else the tapped point).

## 5. Frame (Android frame app, browser frame)

- Renderers use §3.1 to pick the view, §3 to crop, §3.3 to decode, §3.4 for Ken Burns, §3.5 for pairs.
- The frame honours `framing_override` (§3.1). An editor for it on the frame (drag and a slider for the current
  orientation, saving `framing_override` for that orientation, "Reset" clearing it) is a later phase: frames have no
  on-device focal point editor today either, the override comes only from the API.
- The frame stores `framing` (item) and `framing_override` (placement) with the placement. Frames without network
  keep working with what they stored.

## 6. TV

`TvSlide.framing` is the effective framing (override, else the item's) and `focal_point` stays as today. The TV picks
the view for its orientation (always landscape in practice) and follows §3 and §3.4.

## 7. Server

- Columns `items.framing jsonb` and `placements.framing_override jsonb`, null by default.
- Validation and rounding (§2), the focal point derived from a framing (§2.1), framing dropped for videos.
- Send create, item update (propagates like the focal point: placements show the edit on the next sync), device
  placement update, placement and TV responses, federation in both directions.
- `Discovery.framing: true`.
- Photo items sent from chat or by email inbox and Frameo imports have no framing.

## 8. Parity

| | Android family | iOS / Mac | Web | Android frame | Browser frame | TV |
|---|---|---|---|---|---|---|
| Show framing (§3) | — | — | — | yes | yes | yes (web; iOS TV stage without Ken Burns) |
| Edit framing when sending | yes | yes | yes | — | — | — |
| Edit framing after sending | yes | later | later | — | — | — |
| Frame owner's override: shown | — | — | — | yes | yes | yes |
| Frame owner's override: editor on the frame | — | — | — | later | later | — |

## 9. Not in scope

- Cropping the photo itself (what feed posts, thumbnails and downloads show).
- Rotation in the editor (frames already rotate placements by 90°).
- Framing for videos.
- Per-frame framing by the sender (one framing per orientation for all recipients).
