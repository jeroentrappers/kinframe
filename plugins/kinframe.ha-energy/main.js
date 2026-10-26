// Home Assistant energy flow (docs/plugins.md §9.1). Power sensors are mapped per node in the settings, because
// Home Assistant's Energy dashboard only knows energy (kWh) statistics, not live power. Several solar inverters are
// summed. Live updates come over the WebSocket API (subscribe_entities); until it is up, update() reads the REST API.

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

// ---- the energy model ----

const list = (v) => (Array.isArray(v) ? v : v ? [v] : []).filter((x) => typeof x === 'string' && x.includes('.'));

function entityIds(s) {
  return [...list(s.solar), ...list(s.grid), ...list(s.battery), ...list(s.battery_soc), ...list(s.home), ...list(s.ev)];
}

/** Watts from a power sensor: kW and MW are converted, a sensor without a unit is taken as W; null when unknown. */
export function watts(e) {
  if (!e || e.missing || e.state === null || e.state === undefined) return null;
  const v = parseFloat(e.state);
  if (!Number.isFinite(v)) return null; // "unavailable", "unknown"
  const u = (e.attributes && e.attributes.unit_of_measurement) || 'W';
  return u === 'kW' ? v * 1000 : u === 'MW' ? v * 1e6 : v;
}

/** Reads the mapped sensors into one consistent picture (W): solar ≥ 0, grid + import, battery + discharge, home, car. */
export function snapshot(s, ents) {
  const sum = (ids) => { let any = false, t = 0; for (const id of list(ids)) { const w = watts(ents[id]); if (w !== null) { any = true; t += w; } } return any ? t : null; };
  const solar = s.solar && list(s.solar).length ? Math.max(0, sum(s.solar) || 0) : null;
  let grid = sum(s.grid);
  if (grid !== null && s.grid_sign === 'export') grid = -grid;
  let battery = list(s.battery).length ? sum(s.battery) : null;
  if (battery !== null && s.battery_sign === 'charging') battery = -battery;
  const soc = list(s.battery_soc).length ? sum(s.battery_soc) : null;
  const ev = list(s.ev).length ? Math.max(0, sum(s.ev) || 0) : null;
  // All use (the car included): the sensor when there is one, else what comes in minus what goes out.
  let total = list(s.home).length ? sum(s.home) : null;
  if (total === null) total = Math.max(0, (solar || 0) + (grid || 0) + (battery || 0));
  const home = Math.max(0, total - (ev || 0));
  return { solar, grid, battery, soc, ev, home };
}

/**
 * Who feeds whom: solar first to the home, then the car, then the battery, then the grid; the battery next; the grid
 * covers the rest. The same split Home Assistant's own power-flow cards draw.
 */
export function flows(m) {
  const need = { home: m.home, ev: m.ev || 0, battery: Math.max(0, -(m.battery || 0)), grid: Math.max(0, -(m.grid || 0)) };
  const supply = [['solar', m.solar || 0], ['battery', Math.max(0, m.battery || 0)], ['grid', Math.max(0, m.grid || 0)]];
  const out = [];
  for (const [src, have0] of supply) {
    let have = have0;
    for (const sink of ['home', 'ev', 'battery', 'grid']) {
      if (have <= 0) break;
      if (sink === src) continue;
      const w = Math.min(have, need[sink]);
      if (w > 0.5) { out.push({ from: src, to: sink, power: Math.round(w) }); need[sink] -= w; have -= w; }
    }
  }
  return out;
}

function view(m, ctx, detail) {
  const f = ctx.format, t = (k) => ctx.t(k), s = ctx.settings;
  const nodes = [];
  if (m.solar !== null) nodes.push({ id: 'solar', icon: 'solar', value: f.power(m.solar), label: t('solar'), tone: m.solar > 0.5 ? 'good' : undefined });
  if (m.grid !== null) nodes.push({ id: 'grid', icon: 'grid', value: f.power(Math.abs(m.grid)), label: t('grid'), tone: m.grid < -0.5 ? 'good' : undefined });
  nodes.push({ id: 'home', icon: 'home', value: f.power(m.home), label: t('home') });
  if (m.battery !== null) {
    nodes.push({ id: 'battery', icon: m.battery < -0.5 ? 'battery-charging' : 'battery', value: m.soc !== null ? f.percent(m.soc) : f.power(Math.abs(m.battery)),
      label: t('battery'), percent: m.soc !== null ? Math.max(0, Math.min(100, m.soc)) : undefined });
  }
  if (m.ev !== null && m.ev > 0.5) nodes.push({ id: 'ev', icon: 'car', value: f.power(m.ev), label: t('car') });
  const ids = new Set(nodes.map((n) => n.id));
  const edges = flows(m).filter((e) => ids.has(e.from) && ids.has(e.to));
  const flow = { type: 'flow', nodes, edges };
  if (!detail) {
    if (s.style === 'rows') {
      const rows = [];
      if (m.solar !== null) rows.push({ type: 'value', value: f.power(m.solar), label: t('solar'), icon: 'solar' });
      if (m.grid !== null) rows.push({ type: 'value', value: f.power(Math.abs(m.grid)), label: t(m.grid < -0.5 ? 'exporting' : 'importing'), icon: 'grid', tone: m.grid < -0.5 ? 'good' : undefined });
      rows.push({ type: 'value', value: f.power(m.home), label: t('home'), icon: 'home' });
      if (m.battery !== null) rows.push({ type: 'value', value: m.soc !== null ? f.percent(m.soc) : f.power(Math.abs(m.battery)), label: t(m.battery < -0.5 ? 'charging' : 'battery'), icon: m.battery < -0.5 ? 'battery-charging' : 'battery' });
      return { type: 'rows', items: rows.slice(0, 4) };
    }
    return flow;
  }
  const items = [flow];
  const hist = ctx.state.gridHistory || [];
  if (hist.filter((v) => v !== null).length >= 2) items.push({ type: 'sparkline', points: hist, label: t('last_hour'), show_range: true });
  return { type: 'stack', items };
}

/** One grid sample per 37.5 s (96 per hour) for the detail card's chart. */
function remember(ctx, m) {
  const slot = Math.floor(ctx.now() / 37500);
  if (ctx.state.slot === slot || m.grid === null) return;
  ctx.state.slot = slot;
  const h = ctx.state.gridHistory || (ctx.state.gridHistory = []);
  h.push(Math.round(m.grid));
  if (h.length > 96) h.shift();
}

export default {
  async start(ctx) {
    if (!ctx.settings.base_url || !ctx.settings.token) return null;
    haSubscribe(ctx, entityIds(ctx.settings));
    return null;
  },

  async update(ctx) {
    if (!ctx.settings.base_url || !ctx.settings.token) return;
    if (!ha(ctx).live) await haPoll(ctx, entityIds(ctx.settings));
  },

  render(ctx) {
    if (!ctx.settings.base_url || !ctx.settings.token) return null;
    const h = ha(ctx);
    const ids = entityIds(ctx.settings);
    if (h.error === 'token') throw new Error(ctx.t('token_refused'));
    const missing = ids.find((id) => h.entities[id] && h.entities[id].missing);
    if (missing) throw new Error(ctx.t('not_found', { id: missing }));
    const m = snapshot(ctx.settings, h.entities);
    if (m.grid === null && m.solar === null) return null;
    // No ctx.dataTime: Home Assistant only stamps a sensor when its value changes, so a quiet one (solar at night)
    // would look stale. A dropped connection shows through the host's own rules instead.
    remember(ctx, m);
    return view(m, ctx, false);
  },

  detail(ctx) {
    const h = ha(ctx);
    const m = snapshot(ctx.settings, h.entities);
    return view(m, ctx, true);
  },
};
