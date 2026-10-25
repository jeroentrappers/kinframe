// kinframe plugin views, API 1 (docs/plugins.md §4). Types for plugin authors; the @kinframe/plugin-kit will ship
// this file. The JSON schema next to it (plugin-view.schema.json) and the frame's validator are the authority.
// Limits: 3 components deep (stack › stack › leaf), 24 components per view, 16 KB serialized, no line breaks.

export type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'accent';

export type Icon =
  | 'bolt' | 'grid' | 'solar' | 'sun' | 'home' | 'battery' | 'battery-charging' | 'plug' | 'car' | 'flame' | 'gas'
  | 'meter' | 'water' | 'thermometer' | 'wind' | 'leaf' | 'euro' | 'fan' | 'lightbulb'
  | 'moon' | 'cloud' | 'clock' | 'calendar'
  | 'washing-machine' | 'trash' | 'door' | 'lock' | 'bike' | 'bus' | 'train' | 'user' | 'wifi' | 'bell'
  | 'info' | 'warning' | 'check' | 'arrow-up' | 'arrow-down' | 'arrow-left' | 'arrow-right';

/** Icon, big number, unit, optional label and trend arrow. `value` ≤ 16 chars, `unit` ≤ 8, `label` ≤ 32. */
export interface ValueView {
  type: 'value';
  /** Formatted with ctx.format ("1,2 kW"); a trailing unit after a (no-break) space is drawn smaller. */
  value: string;
  unit?: string;
  label?: string;
  icon?: Icon;
  trend?: 'up' | 'down' | 'flat';
  tone?: Tone;
}

/** 1–4 values stacked. */
export interface RowsView {
  type: 'rows';
  items: ValueView[];
  tone?: Tone;
}

/** 0–100 %, ring (default) or bar. `label` ≤ 12 chars (default "68%"), `caption` ≤ 32. */
export interface GaugeView {
  type: 'gauge';
  value: number;
  label?: string;
  caption?: string;
  icon?: Icon;
  style?: 'ring' | 'bar';
  tone?: Tone;
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

export type PluginView = ValueView | RowsView | GaugeView | FlowView | SparklineView | TextView | StackView;
