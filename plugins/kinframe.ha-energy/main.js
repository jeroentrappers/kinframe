// Home Assistant energy flow (docs/plugins.md §9.1). Power sensors are mapped per node in the settings, because
// Home Assistant's Energy dashboard only knows energy (kWh) statistics, not live power. Several solar inverters are
// summed. Live updates come over the WebSocket API (subscribe_entities); until it is up, update() reads the REST API.

import * as ha from 'kinframe/ha';

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
    if (!ha.configured(ctx)) return null;
    ha.subscribe(ctx, entityIds(ctx.settings));
    return null;
  },

  async update(ctx) {
    if (!ha.configured(ctx)) return;
    await ha.refresh(ctx, entityIds(ctx.settings));
  },

  render(ctx) {
    if (!ha.configured(ctx)) return null;
    const h = ha.connection(ctx);
    const ids = entityIds(ctx.settings);
    ha.requireToken(ctx);
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
    const h = ha.connection(ctx);
    const m = snapshot(ctx.settings, h.entities);
    return view(m, ctx, true);
  },
};
