// Example: the European Air Quality Index (EAQI) from Open-Meteo, where the frame is (ctx.location, which needs
// `permissions.location`), or at the latitude and longitude the owner typed in instead.

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

/** Where to measure: the place the owner typed in, else the frame's own (coarse) place, else null. */
export function place(ctx) {
  const s = ctx.settings;
  if (typeof s.latitude === 'number' && typeof s.longitude === 'number') return { latitude: s.latitude, longitude: s.longitude };
  return ctx.location;
}

export default {
  async update(ctx) {
    const p = place(ctx);
    if (!p) return;
    const { latitude, longitude } = p;
    const r = await ctx.http.get(`https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${latitude}&longitude=${longitude}&current=european_aqi,pm2_5,pm10`);
    if (!r.ok) throw new Error('Open-Meteo: HTTP ' + r.status);
    ctx.state.data = r.json();
  },
  render(ctx) {
    return ctx.state.data ? view(ctx.state.data, ctx) : null;
  },
};
