// kinframe plugin views, API 1 (docs/plugins.md §4). Types for plugin authors; the @kinframe/plugin-kit will ship
// this file. The JSON schema next to it (plugin-view.schema.json) and the frame's validator are the authority.
// Limits: 3 components deep (stack › stack › leaf), 24 components per view, 16 KB serialized, no line breaks.

export type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'accent' | 'info';

export type Icon =
  | 'bolt' | 'grid' | 'solar' | 'sun' | 'home' | 'battery' | 'battery-charging' | 'plug' | 'car' | 'flame' | 'gas'
  | 'meter' | 'water' | 'thermometer' | 'wind' | 'leaf' | 'euro' | 'fan' | 'lightbulb'
  | 'moon' | 'cloud' | 'clock' | 'calendar' | 'rain' | 'direction'
  | 'washing-machine' | 'trash' | 'door' | 'lock' | 'bike' | 'bus' | 'train' | 'user' | 'wifi' | 'bell'
  | 'info' | 'warning' | 'check' | 'arrow-up' | 'arrow-down' | 'arrow-left' | 'arrow-right'
  | 'music' | 'play' | 'pause' | 'skip-next' | 'skip-previous' | 'volume-up' | 'volume-down' | 'volume-off' | 'power';

/** Icon, big number, unit, optional label and trend arrow. `value` ≤ 16 chars, `unit` ≤ 8, `label` ≤ 32. */
export interface ValueView {
  type: 'value';
  /** Formatted with ctx.format ("1,2 kW"); a trailing unit after a (no-break) space is drawn smaller. */
  value: string;
  unit?: string;
  label?: string;
  icon?: Icon;
  /** Turns the icon clockwise, 0–360 degrees: the `direction` needle (north = up) shows a wind direction or heading. */
  icon_rotation?: number;
  trend?: 'up' | 'down' | 'flat';
  tone?: Tone;
}

/** 1–4 values stacked. */
export interface RowsView {
  type: 'rows';
  items: ValueView[];
  tone?: Tone;
}

/**
 * 0–100 %, ring (default) or bar. `label` ≤ 12 chars (default "68%"), `caption` ≤ 32.
 * A `dial` is two-sided: -100–100 %, zero at the top, positive clockwise in `tone`, negative anticlockwise in
 * `negative_tone` (default `tone`); "1.240 W" draws the unit small below the number. Only a dial takes `negative_tone`.
 */
export interface GaugeView {
  type: 'gauge';
  value: number;
  label?: string;
  caption?: string;
  icon?: Icon;
  style?: 'ring' | 'bar' | 'dial';
  tone?: Tone;
  negative_tone?: Tone;
  /** Up to three small values under a ring or dial (value ≤ 12 chars): today's ↑ 11,8 / ↓ 14,5 kWh. */
  sub?: { value: string; icon?: Icon; tone?: Tone }[];
}

export interface FlowNode {
  /** a-z, 0-9, - and _, ≤ 24 chars, unique in the flow. */
  id: string;
  icon: Icon;
  /** ≤ 16 chars. */
  value?: string;
  /** ≤ 16 chars; shown in the corner and the detail card, not in the band. */
  label?: string;
  tone?: Tone;
  /** A ring around the node, 0–100 (a home battery's charge). */
  percent?: number;
}

export interface FlowEdge {
  from: string;
  to: string;
  /** Magnitude ≥ 0 (any unit, relative to the largest edge); 0 = idle line. */
  power?: number;
}

/** 2–5 nodes, up to 8 edges, animated arrows. */
export interface FlowView {
  type: 'flow';
  nodes: FlowNode[];
  edges?: FlowEdge[];
  tone?: Tone;
}

/** 2–96 points; null is a gap. */
export interface SparklineView {
  type: 'sparkline';
  points: (number | null)[];
  min?: number;
  max?: number;
  label?: string;
  value?: string;
  show_range?: boolean;
  tone?: Tone;
}

/** 1–2 lines, ≤ 60 chars each. */
export interface TextView {
  type: 'text';
  lines: [string] | [string, string];
  icon?: Icon;
  tone?: Tone;
}

/** 1–4 items. In the band the frame chooses the axis (column: vertical, strip: horizontal). */
export interface StackView {
  type: 'stack';
  items: PluginView[];
  direction?: 'vertical' | 'horizontal';
  tone?: Tone;
}

/**
 * A picture from an http(s) URL your network permissions allow (album art). The frame fetches it (≤ 768 KB), decodes it
 * small and caches it. `description` ≤ 60 chars is read out by accessibility services.
 */
export interface ImageView {
  type: 'image';
  src: string;
  shape?: 'square' | 'round';
  description?: string;
  tone?: Tone;
}

/**
 * 1–5 round buttons; a tap calls your `action(ctx, id)`. Detail card only (not drawn in the corner or band). Needs
 * `"actions": true` in the manifest's permissions. `label` ≤ 16 chars; one `primary` button is drawn larger and filled.
 */
export interface ActionsView {
  type: 'actions';
  items: { id: string; icon: Icon; label?: string; tone?: Tone; primary?: boolean }[];
  tone?: Tone;
}

/**
 * A thin bar from `min` (default 0) to `max`, or a dot on the track (`style: 'marker'`, e.g. today's temperature between
 * its low and high). With `running` the frame advances `value` by 1 per second from `at` (epoch ms) between refreshes;
 * `time` shows seconds as "1:23 / 3:45".
 */
export interface ProgressView {
  type: 'progress';
  value: number;
  min?: number;
  max: number;
  at?: number;
  running?: boolean;
  label?: string;
  time?: boolean;
  style?: 'bar' | 'marker';
  tone?: Tone;
}

/** Nothing to show right now: the widget hides and frees its place. Only as the whole view (null keeps the last view). */
export interface NoneView {
  type: 'none';
}

export type PluginView = ValueView | RowsView | GaugeView | FlowView | SparklineView | TextView | StackView | ImageView | ActionsView | ProgressView;

export type RootView = PluginView | NoneView;
