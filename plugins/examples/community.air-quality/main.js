// Example: the European Air Quality Index (EAQI) from Open-Meteo. Needs the frame's location typed in as numbers: a
// plugin can't ask the frame where it is (§11).

const LEVELS = [[20, 'good', 'good'], [40, 'fair', 'good'], [60, 'moderate', 'warn'], [80, 'poor', 'warn'], [100, 'very_poor', 'bad'], [Infinity, 'extreme', 'bad']];

export function view(json, ctx) {
  const c = json.current || {};
  const aqi = c.european_aqi;
  if (typeof aqi !== 'number') return null;
  const [, key, tone] = LEVELS.find(([max]) => aqi <= max);
  return {
    type: 'gauge', value: Math.min(100, aqi), label: String(Math.round(aqi)), icon: 'wind', tone,
    caption: ctx.t(key) + ' · PM2.5 ' + ctx.format.number(c.pm2_5, 0, 'µg/m³'),
  };
}

export default {
  async update(ctx) {
    const { latitude, longitude } = ctx.settings;
    if (typeof latitude !== 'number' || typeof longitude !== 'number') return;
    const r = await ctx.http.get(`https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${latitude}&longitude=${longitude}&current=european_aqi,pm2_5,pm10`);
    if (!r.ok) throw new Error('Open-Meteo: HTTP ' + r.status);
    ctx.state.data = r.json();
  },
  render(ctx) {
    return ctx.state.data ? view(ctx.state.data, ctx) : null;
  },
};
