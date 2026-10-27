// HomeWizard P1 meter (docs/plugins.md §9.2): polls the meter's local API v1 every second (its own update rate) on
// the home network.
// Styles: "value" (the number with a trend arrow), "gauge" (a two-sided dial in watts) and "flow" (grid ⇄ home).

import { configured, data, number, phases, total } from 'kinframe/homewizard';
import { dayTotals } from 'kinframe/day';
import { series } from 'kinframe/series';

const DEFAULT_MAX_W = 4000;

/** One reading of `GET /api/v1/data`. Meters omit what they don't measure; old firmware only has tariff totals. */
export function parse(o) {
  return {
    activePowerW: number(o, 'active_power_w'),
    phasesW: phases(o),
    importKwh: total(o, 'total_power_import'),
    exportKwh: total(o, 'total_power_export'),
    gasM3: number(o, 'total_gas_m3'),
  };
}

// The day's starting totals (ctx.store 'day', "date|import|export|gas") and the live power's last hour.
const day = (ctx) => dayTotals(ctx, ['imp', 'exp', 'gas']);
const power = (ctx) => series(ctx, 'power');
const totals = (r) => ({ imp: r.importKwh, exp: r.exportKwh, gas: r.gasM3 });

/** Keeps a reading: the latest one, the power history and the day's totals. */
export function record(ctx, r) {
  ctx.state.last = r;
  power(ctx).add(r.activePowerW, ctx.now());
  day(ctx).record(totals(r));
}

/** What render and detail draw from: the latest reading, its trend, the last hour and today's totals; null before the first. */
export function current(ctx) {
  const r = ctx.state.last;
  if (!r) return null;
  const p = power(ctx);
  return { r, trend: p.trend(), hour: p.buckets(), day: day(ctx).since(totals(r)) };
}

export function main(r, tr, settings, ctx, day) {
  const f = ctx.format, t = (k) => ctx.t(k);
  const p = r.activePowerW === null ? 0 : r.activePowerW;
  const warnW = typeof settings.warn_w === 'number' ? settings.warn_w : 0;
  const exporting = p < -0.5;
  const tone = exporting ? 'good' : warnW > 0 && p > warnW ? 'warn' : undefined;
  if (settings.style === 'gauge') {
    const maxW = typeof settings.max_w === 'number' && settings.max_w > 0 ? settings.max_w : DEFAULT_MAX_W;
    const g = {
      type: 'gauge', value: Math.max(-100, Math.min(100, (p / maxW) * 100)), label: f.number(Math.abs(p), 0, 'W'),
      style: 'dial', tone: 'info', negative_tone: 'good',
    };
    // Today's kWh under the dial, returned (green, up) and used (violet, down); no unit, the column is too narrow.
    if (day && day.imp !== null) {
      g.sub = [];
      if (day.exp !== null) g.sub.push({ value: f.number(day.exp, 1), icon: 'arrow-up', tone: 'good' });
      g.sub.push({ value: f.number(day.imp, 1), icon: 'arrow-down', tone: 'info' });
    }
    return g;
  }
  if (settings.style === 'flow') {
    return {
      type: 'flow',
      nodes: [
        { id: 'grid', icon: 'grid', value: f.power(Math.abs(p)), label: t('grid') },
        { id: 'home', icon: 'home', label: t('home') },
      ],
      edges: [exporting ? { from: 'home', to: 'grid', power: -p } : { from: 'grid', to: 'home', power: p }],
      tone,
    };
  }
  return { type: 'value', value: f.power(Math.abs(p)), label: t(exporting ? 'returning' : 'using'), icon: exporting ? 'sun' : 'bolt', trend: tr || undefined, tone };
}

export function detail(r, hour, day, ctx) {
  const f = ctx.format, t = (k) => ctx.t(k);
  const items = [main(r, null, {}, ctx)];
  if (hour.filter((v) => v !== null).length >= 2) items.push({ type: 'sparkline', points: hour, label: t('last_hour'), show_range: true });
  if (r.phasesW.length > 1) items.push({ type: 'rows', items: r.phasesW.map((w, i) => ({ type: 'value', value: f.power(w), label: 'L' + (i + 1), icon: 'bolt' })) });
  const totals = [];
  if (day.imp !== null) totals.push({ type: 'value', value: f.energy(day.imp), label: t('today_import'), icon: 'grid' });
  if (day.exp !== null && day.exp > 0) totals.push({ type: 'value', value: f.energy(day.exp), label: t('today_export'), icon: 'sun', tone: 'good' });
  if (day.gas !== null) totals.push({ type: 'value', value: f.volume(day.gas), label: t('today_gas'), icon: 'flame' });
  if (totals.length) items.push({ type: 'rows', items: totals });
  return { type: 'stack', items: items.slice(0, 4) };
}

export default {
  async update(ctx) {
    if (!configured(ctx)) return;
    record(ctx, parse(await data(ctx)));
  },

  render(ctx) {
    const c = current(ctx);
    return c && main(c.r, c.trend, ctx.settings, ctx, c.day);
  },

  detail(ctx) {
    const c = current(ctx);
    return c && detail(c.r, c.hour, c.day, ctx);
  },
};
