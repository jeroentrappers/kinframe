// Example: day-ahead prices per quarter hour (Energy-Charts). A day has 96 quarter hours, but 92 or 100 when the clocks
// change, while a sparkline takes at most 96 points; and a sparkline can't mark "now" or the cheapest hour (§11).

export function view(json, ctx) {
  const t = json.unix_seconds || [], p = json.price || [];
  if (!t.length) return null;
  const now = ctx.now() / 1000;
  let i = t.findIndex((s, k) => s <= now && (k === t.length - 1 || t[k + 1] > now));
  if (i < 0) i = 0;
  const kwh = (mwh) => mwh / 1000;
  let cheap = 0;
  for (let k = i; k < p.length; k++) if (p[k] < p[cheap] || cheap < i) cheap = k;
  const fmt = (mwh) => ctx.format.number(kwh(mwh), 3, '€/kWh');
  const main = {
    type: 'value', value: fmt(p[i]), label: ctx.t('cheapest', { time: ctx.format.time(t[cheap] * 1000) }), icon: 'euro',
    tone: p[i] <= 0 ? 'good' : p[i] > 150 ? 'warn' : undefined,
  };
  // Down to at most 96 points (DST days have 100): average neighbours.
  const n = p.length, out = Math.min(96, n);
  const pts = Array.from({ length: out }, (_, k) => { const a = Math.floor((k * n) / out), b = Math.max(a + 1, Math.floor(((k + 1) * n) / out)); let s = 0; for (let j = a; j < b; j++) s += p[j]; return kwh(s / (b - a)); });
  const detail = { type: 'stack', items: [main, { type: 'sparkline', points: pts, label: ctx.t('today'), show_range: true }] };
  return { main, detail };
}

const current = (ctx) => (ctx.state.prices ? view(ctx.state.prices, ctx) : null);

export default {
  async update(ctx) {
    const r = await ctx.http.get('https://api.energy-charts.info/price?bzn=' + encodeURIComponent(ctx.settings.zone || 'BE'));
    if (!r.ok) throw new Error('Energy-Charts: HTTP ' + r.status);
    ctx.state.prices = r.json();
  },
  render(ctx) { const v = current(ctx); return v && v.main; },
  detail(ctx) { const v = current(ctx); return v && v.detail; },
};
