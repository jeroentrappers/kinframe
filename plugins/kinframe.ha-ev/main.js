// Electric car via Home Assistant (docs/plugins.md §9.3), e.g. a Kia EV3 through the Kia/Hyundai integration: battery
// level as a gauge, charging state and range as text. The car reports through the maker's cloud, often only every
// 30–60 minutes unless it charges, so the view says how old it is and the host greys it after 6 hours.

// ---- Home Assistant connection (the same in every kinframe.ha-* plugin until plugins can share modules, §11) ----

function baseUrl(s) {
  const h = String(s || '').trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(h) ? h : 'http://' + h;
}

function ha(ctx) {
  if (!ctx.state.ha) ctx.state.ha = { base: baseUrl(ctx.settings.base_url), entities: {}, live: false, error: null, nextId: 1 };
  return ctx.state.ha;
}

/** Applies a subscribe_entities event: "a" adds full states, "c" changes ("+" set, "-" removed attributes), "r" removes. */
export function applyEvent(entities, ev) {
  for (const id in ev.a || {}) {
    const e = ev.a[id];
    entities[id] = { state: e.s, attributes: e.a || {}, updated: Math.round((e.lu || e.lc || 0) * 1000) };
  }
  for (const id in ev.c || {}) {
    const d = ev.c[id];
    const e = entities[id] || (entities[id] = { state: null, attributes: {}, updated: 0 });
    const add = d['+'];
    if (add) {
      if ('s' in add) e.state = add.s;
      if (add.a) Object.assign(e.attributes, add.a);
      if (add.lu || add.lc) e.updated = Math.round((add.lu || add.lc) * 1000);
    }
    if (d['-'] && d['-'].a) for (const k of d['-'].a) delete e.attributes[k];
  }
  for (const id of ev.r || []) delete entities[id];
}

function haSubscribe(ctx, ids) {
  const h = ha(ctx);
  ctx.ws.connect(h.base.replace(/^http/i, 'ws') + '/api/websocket', {
    onMessage(ws, m) {
      if (m.type === 'auth_required') ws.send({ type: 'auth', access_token: ctx.settings.token });
      else if (m.type === 'auth_ok') ws.send({ id: h.nextId++, type: 'subscribe_entities', entity_ids: ids });
      else if (m.type === 'auth_invalid') { h.error = 'token'; ws.close(); }
      else if (m.type === 'event' && m.event) { applyEvent(h.entities, m.event); h.live = true; ctx.invalidate(); }
    },
    onClose() { h.live = false; },
  });
}

/** REST fallback while the WebSocket isn't live: one GET per entity. */
async function haPoll(ctx, ids) {
  const h = ha(ctx);
  for (const id of ids) {
    const r = await ctx.http.get(h.base + '/api/states/' + id, { headers: { Authorization: 'Bearer ' + ctx.settings.token } });
    if (r.status === 401) { h.error = 'token'; break; }
    if (r.status === 404) { h.entities[id] = { missing: true }; continue; }
    if (!r.ok) throw new Error('Home Assistant: HTTP ' + r.status);
    const e = r.json();
    h.entities[id] = { state: e.state, attributes: e.attributes || {}, updated: Date.parse(e.last_updated) || 0 };
  }
}

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

function ago(ctx, at) {
  const min = Math.floor((ctx.now() - at) / 60000);
  if (min < 2) return ctx.t('updated_now');
  if (min < 60) return ctx.t('updated_min', { n: min });
  if (min < 48 * 60) return ctx.t('updated_h', { n: Math.floor(min / 60) });
  return ctx.t('updated_d', { n: Math.floor(min / 1440) });
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
  if (c.updated) detail.items.push({ type: 'text', lines: [ago(ctx, c.updated)], icon: 'clock' });
  return { main, detail };
}

async function current(ctx) {
  const h = ha(ctx);
  const ids = Object.values(entities(ctx.settings)).filter(Boolean);
  if (!h.live) await haPoll(ctx, ids);
  if (h.error === 'token') throw new Error(ctx.t('token_refused'));
  const c = read(ctx.settings, h.entities, ctx.now());
  if (c.level === null) return null;
  if (c.updated) ctx.dataTime = c.updated;
  return views(c, ctx);
}

export default {
  async start(ctx) {
    if (!ctx.settings.base_url || !ctx.settings.token) return null;
    haSubscribe(ctx, Object.values(entities(ctx.settings)).filter(Boolean));
    return null;
  },
  async render(ctx) {
    if (!ctx.settings.base_url || !ctx.settings.token) return null;
    const v = await current(ctx);
    return v && v.main;
  },
  async detail(ctx) {
    const v = await current(ctx);
    return v && v.detail;
  },
};
