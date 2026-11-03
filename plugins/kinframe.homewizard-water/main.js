// HomeWizard water meter (docs/plugins.md §9.6): the flow now and the litres used today, read from the meter's local
// API v1 (HWE-WTR on USB power) every two seconds.
//
// Also the template for a device on the home network that counts something:
//   - update() is the only place that talks to the device (through the host library kinframe/homewizard) and keeps
//     what it read: the latest reading in ctx.state, a short history (kinframe/series), today's total (kinframe/day).
//   - render() and detail() only draw from that, so they never wait for the device.
//   - parse(), record(), current() and main() are exported for the tests and are plain functions of their inputs.
//
// Drawn in the `water` tone (docs/plugins.md §4.3), orange (`warn`) once today passes a typical day.

import { configured, data, number } from 'kinframe/homewizard';
import { dayTotals } from 'kinframe/day';
import { series } from 'kinframe/series';

const DEFAULT_TYPICAL_L = 250;
const TONE = 'water';
/** Below this the meter is still (it reports 0, but allow for rounding). */
const STILL_LPM = 0.05;

/** One reading of `GET /api/v1/data`: `{ active_liter_lpm, total_liter_m3, … }`. */
export function parse(o) {
  return { flowLpm: number(o, 'active_liter_lpm'), totalM3: number(o, 'total_liter_m3') };
}

const day = (ctx) => dayTotals(ctx, ['m3']);
const flow = (ctx) => series(ctx, 'flow');

/** Keeps a reading: the latest one, the flow's history and today's starting total. */
export function record(ctx, r) {
  ctx.state.last = r;
  flow(ctx).add(r.flowLpm, ctx.now());
  day(ctx).record({ m3: r.totalM3 });
}

/** The latest reading, today's litres (null if the meter has no total) and the flow's last hour; null before the first. */
export function current(ctx) {
  const r = ctx.state.last;
  if (!r) return null;
  const m3 = day(ctx).since({ m3: r.totalM3 }).m3;
  return { r, todayL: m3 === null ? null : m3 * 1000, hour: flow(ctx).buckets() };
}

const flowing = (r) => r.flowLpm !== null && r.flowLpm > STILL_LPM;
const litres = (f, l) => f.number(l, 0, 'l');
const perMinute = (f, lpm) => f.number(lpm, 1, 'l/min');
// In a value the unit goes separately: "l/min" is longer than the frame splits off by itself.
const flowValue = (f, lpm) => ({ value: f.number(lpm, 1), unit: 'l/min' });
const typical = (settings) => (typeof settings.typical_l === 'number' && settings.typical_l > 0 ? settings.typical_l : DEFAULT_TYPICAL_L);

/** Today against a typical day: a ring, orange once it's past; the flow under it while water runs. */
function ring(c, settings, ctx) {
  const f = ctx.format;
  const day = typical(settings);
  const g = {
    type: 'gauge', value: Math.min(100, (c.todayL / day) * 100), label: litres(f, c.todayL), caption: ctx.t('today'),
    icon: 'water', tone: c.todayL > day ? 'warn' : TONE,
  };
  if (flowing(c.r)) g.sub = [{ value: perMinute(f, c.r.flowLpm), icon: 'arrow-down', tone: TONE }];
  return g;
}

/** The widget: litres today, or the flow while water runs (with today's litres as its label). */
export function main(c, settings, ctx) {
  const f = ctx.format, t = (k) => ctx.t(k);
  if (settings.style === 'gauge' && c.todayL !== null) return ring(c, settings, ctx);
  if (flowing(c.r)) {
    return { type: 'value', ...flowValue(f, c.r.flowLpm), label: c.todayL === null ? t('flowing') : t('today') + ' ' + litres(f, c.todayL), icon: 'water', tone: TONE };
  }
  if (c.todayL === null) return { type: 'value', ...flowValue(f, c.r.flowLpm || 0), label: t('flow_now'), icon: 'water' };
  return { type: 'value', value: litres(f, c.todayL), label: t('today'), icon: 'water' };
}

/** The card: today's ring, the last hour's flow, and the numbers. */
export function detail(c, settings, ctx) {
  const f = ctx.format, t = (k) => ctx.t(k);
  const items = [c.todayL === null ? main(c, {}, ctx) : ring(c, settings, ctx)];
  if (c.hour.filter((v) => v !== null).length >= 2) items.push({ type: 'sparkline', points: c.hour, min: 0, label: t('last_hour'), show_range: true, tone: TONE });
  const rows = [{ type: 'value', ...flowValue(f, c.r.flowLpm || 0), label: t('flow_now'), icon: 'water', tone: flowing(c.r) ? TONE : undefined }];
  if (c.todayL !== null) rows.push({ type: 'value', value: litres(f, c.todayL), label: t('today'), icon: 'calendar' });
  if (c.r.totalM3 !== null) rows.push({ type: 'value', value: f.volume(c.r.totalM3), label: t('meter_total'), icon: 'meter' });
  items.push({ type: 'rows', items: rows });
  return { type: 'stack', items };
}

export default {
  async update(ctx) {
    if (!configured(ctx)) return;
    record(ctx, parse(await data(ctx)));
  },

  render(ctx) {
    const c = current(ctx);
    return c && main(c, ctx.settings, ctx);
  },

  detail(ctx) {
    const c = current(ctx);
    return c && detail(c, ctx.settings, ctx);
  },
};
