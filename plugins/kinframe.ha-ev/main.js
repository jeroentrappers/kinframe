// Electric car via Home Assistant (docs/plugins.md §9.3), e.g. a Kia EV3 through the Kia/Hyundai integration: battery
// level as a gauge, charging state and range as text. The car reports through the maker's cloud, often only every
// 30–60 minutes unless it charges, so the view says how old it is and the host greys it after 6 hours.

import * as ha from 'kinframe/ha';

// ---- the car ----

const ent = (s) => (typeof s === 'string' && s.includes('.') ? s : null);

/** The entity ids to read: the Kia/Hyundai preset fills in the integration's usual names from the prefix. */
export function entities(s) {
  const p = s.preset !== 'custom' && s.prefix ? String(s.prefix).trim().toLowerCase() : null;
  const preset = p ? {
    battery_level: `sensor.${p}_ev_battery_level`, range: `sensor.${p}_ev_range`, charging: `binary_sensor.${p}_ev_battery_charge`,
    plugged: `binary_sensor.${p}_ev_battery_plug`, charge_minutes: `sensor.${p}_estimated_charge_duration`, updated: `sensor.${p}_last_updated_at`,
  } : {};
  const out = {};
  for (const k of ['battery_level', 'range', 'charging', 'plugged', 'charge_minutes', 'charge_power', 'updated']) out[k] = ent(s[k]) || preset[k] || null;
  return out;
}

const num = (e) => { if (!e || e.missing) return null; const v = parseFloat(e.state); return Number.isFinite(v) ? v : null; };
const on = (e) => !!e && !e.missing && e.state === 'on';

export function read(s, ents, now) {
  const ids = entities(s);
  const g = (k) => (ids[k] ? ents[ids[k]] : null);
  const level = num(g('battery_level'));
  const rangeE = g('range');
  const upd = g('updated');
  let updated = upd && !upd.missing ? Date.parse(upd.state) : NaN;
  if (!Number.isFinite(updated)) updated = (g('battery_level') && g('battery_level').updated) || null;
  return {
    level, range: num(rangeE), rangeUnit: (rangeE && rangeE.attributes && rangeE.attributes.unit_of_measurement) || 'km',
    charging: on(g('charging')), plugged: on(g('plugged')), minutes: num(g('charge_minutes')), power: num(g('charge_power')),
    powerUnit: (g('charge_power') && g('charge_power').attributes && g('charge_power').attributes.unit_of_measurement) || 'W',
    updated,
  };
}

export function views(c, ctx) {
  const f = ctx.format, t = (k, v) => ctx.t(k, v);
  const rangeText = c.range !== null ? f.number(c.range, 0, c.rangeUnit) : null;
  const status = c.charging
    ? (c.minutes && c.minutes > 0 ? t('charging_until', { time: f.time(ctx.now() + c.minutes * 60000) }) : t('charging'))
    : c.plugged ? t('plugged') : rangeText;
  const name = ctx.settings.name ? String(ctx.settings.name) : null;
  const gauge = {
    type: 'gauge', value: Math.max(0, Math.min(100, c.level)), label: f.percent(c.level), icon: c.charging ? 'battery-charging' : 'car',
    tone: c.charging ? 'good' : c.level < 20 ? 'warn' : undefined,
  };
  const second = [name, status === rangeText ? null : rangeText].filter(Boolean).join(' · ');
  const text = { type: 'text', lines: [status || name || f.percent(c.level), second].filter(Boolean).slice(0, 2) };
  const main = { type: 'stack', direction: 'horizontal', items: [gauge, text] };
  const rows = [];
  if (rangeText) rows.push({ type: 'value', value: rangeText, label: t('range'), icon: 'car' });
  if (c.charging && c.power !== null) rows.push({ type: 'value', value: c.powerUnit === 'kW' ? f.power(c.power * 1000) : f.power(c.power), label: t('power'), icon: 'plug', tone: 'good' });
  if (c.charging && c.minutes) rows.push({ type: 'value', value: f.time(ctx.now() + c.minutes * 60000), label: t('full_at'), icon: 'clock' });
  const detail = { type: 'stack', items: [main] };
  if (rows.length) detail.items.push({ type: 'rows', items: rows });
  // When the car last reported, in the frame's own words ("8 min ago"); the clock icon says what it is.
  if (c.updated) detail.items.push({ type: 'text', lines: [ctx.format.ago(c.updated)], icon: 'clock' });
  return { main, detail };
}

function current(ctx) {
  const h = ha.connection(ctx);
  ha.requireToken(ctx);
  const c = read(ctx.settings, h.entities, ctx.now());
  if (c.level === null) return null;
  if (c.updated) ctx.dataTime = c.updated;
  return views(c, ctx);
}

export default {
  async start(ctx) {
    if (!ha.configured(ctx)) return null;
    ha.subscribe(ctx, Object.values(entities(ctx.settings)).filter(Boolean));
    return null;
  },
  async update(ctx) {
    if (!ha.configured(ctx)) return;
    await ha.refresh(ctx, Object.values(entities(ctx.settings)).filter(Boolean));
  },
  render(ctx) {
    if (!ha.configured(ctx)) return null;
    const v = current(ctx);
    return v && v.main;
  },
  detail(ctx) {
    const v = current(ctx);
    return v && v.detail;
  },
};
