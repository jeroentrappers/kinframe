// HomeWizard energy socket (docs/plugins.md §9.7): what the appliance on it draws now and used today, from the
// socket's local API v1 (HWE-SKT) every second, and a switch in the detail card.
//
// Also the template for a device with a button:
//   - update() reads the device; the on/off state changes rarely, so it is read every 10 s (and right before a switch),
//     not on every second's update.
//   - The detail card has an `actions` view (needs `permissions.actions`); the frame calls action(ctx, id) when the
//     owner taps it. The handler changes the device, keeps the answer in ctx.state, asks for a render and returns the
//     widget's new view, so the frame shows the result before the next update.
//   - The device's own lock (the HomeWizard app's "switch lock") and the owner's setting both win over the button:
//     a locked socket shows why instead of a button that wouldn't work.

import { configured, data, number, setState, state, total } from 'kinframe/homewizard';
import { dayTotals } from 'kinframe/day';
import { series } from 'kinframe/series';

const STATE_EVERY_MS = 10000;
const ICONS = ['plug', 'washing-machine', 'lightbulb', 'fan', 'car', 'bolt'];

/** One reading of `GET /api/v1/data`. */
export function parse(o) {
  return {
    powerW: number(o, 'active_power_w'),
    importKwh: total(o, 'total_power_import'),
    voltageV: number(o, 'active_voltage_v'),
    currentA: number(o, 'active_current_a'),
  };
}

/** `GET /api/v1/state` → `{ on, locked }`; on is null when the socket doesn't say. */
export function parseState(o) {
  return { on: typeof o.power_on === 'boolean' ? o.power_on : null, locked: o.switch_lock === true };
}

const day = (ctx) => dayTotals(ctx, ['kwh']);
const power = (ctx) => series(ctx, 'power');

export function record(ctx, r) {
  ctx.state.last = r;
  power(ctx).add(r.powerW, ctx.now());
  day(ctx).record({ kwh: r.importKwh });
}

export function recordState(ctx, s) {
  ctx.state.sw = s;
  ctx.state.swAt = ctx.now();
}

/** The latest reading, the switch, today's kWh and the last hour; null before the first reading. */
export function current(ctx) {
  const r = ctx.state.last;
  if (!r) return null;
  return { r, sw: ctx.state.sw || { on: null, locked: false }, todayKwh: day(ctx).since({ kwh: r.importKwh }).kwh, hour: power(ctx).buckets(), trend: power(ctx).trend() };
}

const icon = (settings) => (ICONS.includes(settings.icon) ? settings.icon : 'plug');

/** The widget: the power and what's plugged in (or today's use); "Off" when the socket is switched off. */
export function main(c, settings, ctx) {
  const f = ctx.format, t = (k) => ctx.t(k);
  const name = typeof settings.label === 'string' && settings.label.trim() ? settings.label.trim().slice(0, 32) : null;
  if (c.sw.on === false) return { type: 'value', value: t('off'), label: name || undefined, icon: icon(settings) };
  const label = name || (c.todayKwh !== null ? t('today') + ' ' + f.energy(c.todayKwh) : t('on'));
  return { type: 'value', value: f.power(c.r.powerW || 0), label, icon: icon(settings), trend: c.trend || undefined };
}

/** The card: the widget, the last hour, the numbers, and the switch (or why there is none). */
export function detail(c, settings, ctx) {
  const f = ctx.format, t = (k) => ctx.t(k);
  const items = [main(c, settings, ctx)];
  if (c.hour.filter((v) => v !== null).length >= 2) items.push({ type: 'sparkline', points: c.hour, min: 0, label: t('last_hour'), show_range: true });
  const rows = [];
  if (c.todayKwh !== null) rows.push({ type: 'value', value: f.energy(c.todayKwh), label: t('today'), icon: 'calendar' });
  if (c.r.voltageV !== null) rows.push({ type: 'value', value: f.number(c.r.voltageV, 0, 'V'), label: t('voltage'), icon: 'bolt' });
  if (c.r.currentA !== null) rows.push({ type: 'value', value: f.number(c.r.currentA, 1, 'A'), label: t('current'), icon: 'bolt' });
  if (rows.length) items.push({ type: 'rows', items: rows });
  if (settings.switch !== false && c.sw.on !== null) {
    if (c.sw.locked) items.push({ type: 'text', lines: [t('locked')], icon: 'lock' });
    else items.push({ type: 'actions', items: [{ id: 'power', icon: 'power', label: t(c.sw.on ? 'turn_off' : 'turn_on'), primary: true }] });
  }
  return { type: 'stack', items: items.slice(0, 4) };
}

export default {
  async update(ctx) {
    if (!configured(ctx)) return;
    record(ctx, parse(await data(ctx)));
    if (!ctx.state.sw || ctx.now() - ctx.state.swAt >= STATE_EVERY_MS) recordState(ctx, parseState(await state(ctx)));
  },

  render(ctx) {
    const c = current(ctx);
    return c && main(c, ctx.settings, ctx);
  },

  detail(ctx) {
    const c = current(ctx);
    return c && detail(c, ctx.settings, ctx);
  },

  async action(ctx, id) {
    if (id !== 'power' || ctx.settings.switch === false || !configured(ctx)) return null;
    // The lock may have changed in the app since the last update: ask the socket, not our copy.
    const s = parseState(await state(ctx));
    recordState(ctx, s);
    if (s.locked || s.on === null) return null;
    const answer = await setState(ctx, { power_on: !s.on });
    recordState(ctx, { on: typeof answer.power_on === 'boolean' ? answer.power_on : !s.on, locked: false });
    ctx.invalidate();
    const c = current(ctx);
    return c && main(c, ctx.settings, ctx);
  },
};
