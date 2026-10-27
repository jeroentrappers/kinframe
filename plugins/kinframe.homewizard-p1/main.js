// HomeWizard P1 meter (docs/plugins.md §9.2): polls the meter's local API v1 every second (its own update rate) on
// the home network.
// Styles: "value" (the number with a trend arrow), "gauge" (a two-sided dial in watts) and "flow" (grid ⇄ home).

const SAMPLES = 720; // one hour at 5 s
const STEP_MS = 5000; // the history keeps one averaged sample per 5 s, however often the meter is read
const BUCKETS = 96;
const HOUR_MS = 3600000;
const DEFAULT_MAX_W = 4000;

/** One reading of `GET /api/v1/data`. Meters omit what they don't measure; old firmware only has tariff totals. */
export function parse(o) {
  const d = (k) => (typeof o[k] === 'number' ? o[k] : null);
  const total = (prefix) => {
    const all = d(prefix + '_kwh');
    if (all !== null) return all;
    const parts = [d(prefix + '_t1_kwh'), d(prefix + '_t2_kwh')].filter((v) => v !== null);
    return parts.length ? parts.reduce((a, b) => a + b, 0) : null;
  };
  return {
    activePowerW: d('active_power_w'),
    phasesW: ['active_power_l1_w', 'active_power_l2_w', 'active_power_l3_w'].map(d).filter((v) => v !== null),
    importKwh: total('total_power_import'),
    exportKwh: total('total_power_export'),
    gasM3: d('total_gas_m3'),
  };
}

/** Recent samples (ctx.state) and the day's starting totals (ctx.store). */
function history(ctx) {
  if (!ctx.state.h) ctx.state.h = { samples: new Array(SAMPLES).fill(NaN), times: new Array(SAMPLES).fill(0), head: 0, count: 0, last: null, day: null, slotStart: 0, n: 0 };
  return ctx.state.h;
}

function add(h, r, at) {
  h.last = r;
  if (r.activePowerW === null) return;
  const lastIdx = (h.head - 1 + SAMPLES) % SAMPLES;
  if (h.count > 0 && at - h.slotStart < STEP_MS) {
    h.n++;
    h.samples[lastIdx] += (r.activePowerW - h.samples[lastIdx]) / h.n;
    h.times[lastIdx] = at;
    return;
  }
  h.samples[h.head] = r.activePowerW;
  h.times[h.head] = at;
  h.slotStart = at; h.n = 1;
  h.head = (h.head + 1) % SAMPLES;
  if (h.count < SAMPLES) h.count++;
}

/** Up or down when the last reading differs from the 30 s before it by more than 10 % (and 50 W). */
function trend(h) {
  if (h.count < 4) return null;
  const lastIdx = (h.head - 1 + SAMPLES) % SAMPLES;
  const now = h.samples[lastIdx];
  let sum = 0, n = 0;
  for (let k = 1; k <= 6; k++) {
    if (k >= h.count) break;
    const v = h.samples[(lastIdx - k + SAMPLES) % SAMPLES];
    if (!Number.isNaN(v)) { sum += v; n++; }
  }
  if (n === 0) return null;
  const avg = sum / n;
  const delta = now - avg;
  if (Math.abs(delta) < Math.max(50, Math.abs(avg) * 0.1)) return null;
  return delta > 0 ? 'up' : 'down';
}

/** The last hour in 96 buckets (≈37 s each), averaged; null where there was no reading. */
function lastHour(h) {
  if (h.count === 0) return [];
  const lastIdx = (h.head - 1 + SAMPLES) % SAMPLES;
  const end = h.times[lastIdx];
  const start = end - HOUR_MS;
  const sums = new Array(BUCKETS).fill(0), ns = new Array(BUCKETS).fill(0);
  for (let k = 0; k < h.count; k++) {
    const i = (lastIdx - k + SAMPLES) % SAMPLES;
    const tt = h.times[i];
    if (tt < start) break;
    const b = Math.min(BUCKETS - 1, Math.max(0, Math.floor(((tt - start) / HOUR_MS) * (BUCKETS - 1))));
    sums[b] += h.samples[i]; ns[b]++;
  }
  return sums.map((s, i) => (ns[i] === 0 ? null : s / ns[i]));
}

function updateDay(h, r, ctx) {
  const today = ctx.localTime(ctx.now()).date;
  if (h.day === null) {
    // Restore the day's baseline after a restart.
    const p = (ctx.store.get('day') || '').split('|');
    if (p.length === 4) h.day = { date: p[0], imp: num(p[1]), exp: num(p[2]), gas: num(p[3]) };
  }
  if (!h.day || h.day.date !== today) {
    h.day = { date: today, imp: r.importKwh, exp: r.exportKwh, gas: r.gasM3 };
    const s = (v) => (v === null || v === undefined ? '' : String(v));
    ctx.store.set('day', today + '|' + s(r.importKwh) + '|' + s(r.exportKwh) + '|' + s(r.gasM3));
  }
}

function num(s) { if (s === '' || s === undefined) return null; const v = Number(s); return Number.isNaN(v) ? null : v; }

function today(h, r) {
  const since = (v, base) => (v === null || base === null || base === undefined ? null : Math.max(0, v - base));
  return { imp: since(r.importKwh, h.day && h.day.imp), exp: since(r.exportKwh, h.day && h.day.exp), gas: since(r.gasM3, h.day && h.day.gas) };
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

function baseUrl(host) {
  const h = String(host).trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(h) ? h : 'http://' + h;
}

export default {
  async update(ctx) {
    const host = ctx.settings.host;
    if (!host) return;
    const res = await ctx.http.get(baseUrl(host) + '/api/v1/data', { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const r = parse(res.json());
    const h = history(ctx);
    add(h, r, ctx.now());
    updateDay(h, r, ctx);
  },

  render(ctx) {
    const h = history(ctx);
    if (!h.last) return null;
    return main(h.last, trend(h), ctx.settings, ctx, today(h, h.last));
  },

  async detail(ctx) {
    const h = history(ctx);
    if (!h.last) return null;
    return detail(h.last, lastHour(h), today(h, h.last), ctx);
  },
};
