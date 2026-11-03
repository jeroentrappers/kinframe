// Example: day-ahead prices per quarter hour (Energy-Charts), as bars with "now" marked and the cheapest quarter hour
// still to come highlighted. A day has 96 quarter hours, 92 or 100 when the clocks change; bars take up to 100.

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
  const pts = p.slice(0, 100).map(kwh);
  const chart = { type: 'sparkline', style: 'bars', points: pts, label: ctx.t('today'), show_range: true, tone: 'info', marker: i };
  if (cheap < pts.length) Object.assign(chart, { highlight: cheap, highlight_tone: 'good' });
  const detail = { type: 'stack', items: [main, chart] };
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
