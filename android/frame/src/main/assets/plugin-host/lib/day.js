// kinframe/day — "how much today" from cumulative meters (docs/plugins.md §13.1). A meter only reports its running
// total (kWh imported, m³ of water or gas); today's use is that total minus where it stood when the frame's day began.
//
//   import { dayTotals } from 'kinframe/day';
//
//   const day = dayTotals(ctx, ['imp', 'exp', 'gas']);   // stored under ctx.store 'day'
//   update(ctx) { day.record({ imp: 13779.3, exp: 5114.1, gas: 3204.5 }); }
//   render(ctx) { const t = day.since(latest); /* { imp: 1.5, exp: 0, gas: 0.2 }, null where unknown */ }
//
// The day is the frame's own (ctx.localTime: its time zone, not the device's). The baseline lives in ctx.store, so it
// survives a restart. A counter that appears later in the day starts from its first value; a counter that goes down
// (meter replaced or reset) starts again from there instead of showing 0 for the rest of the day.

function num(s) {
  if (s === '' || s === undefined || s === null) return null;
  const v = Number(s);
  return Number.isNaN(v) ? null : v;
}

/**
 * Today's totals for the counters [names] (in this order; the order is the stored format, so keep it), kept under
 * ctx.store [key] as `date|v1|v2|…`.
 */
export function dayTotals(ctx, names, key = 'day') {
  const slot = 'day:' + key;
  const save = (st) => {
    const s = (v) => (v === null || v === undefined ? '' : String(v));
    ctx.store.set(key, [st.date, ...names.map((n) => s(st.base[n]))].join('|'));
  };
  const load = () => {
    if (ctx.state[slot]) return ctx.state[slot];
    const p = (ctx.store.get(key) || '').split('|');
    if (p.length !== names.length + 1) return null;
    const base = {};
    names.forEach((n, i) => { base[n] = num(p[i + 1]); });
    return (ctx.state[slot] = { date: p[0], base });
  };
  return {
    /** Records the meters' current totals: starts a new day when the date changed. */
    record(values) {
      const today = ctx.localTime(ctx.now()).date;
      let st = load();
      if (!st || st.date !== today) {
        st = ctx.state[slot] = { date: today, base: {} };
        names.forEach((n) => { st.base[n] = values[n] === undefined ? null : values[n]; });
        save(st);
        return;
      }
      let changed = false;
      for (const n of names) {
        const v = values[n], b = st.base[n];
        if (v === null || v === undefined) continue;
        if (b === null || b === undefined || v < b) { st.base[n] = v; changed = true; }
      }
      if (changed) save(st);
    },
    /** How much each counter grew today, from [values] (the latest totals); null where unknown. */
    since(values) {
      const st = load();
      const out = {};
      for (const n of names) {
        const v = values[n], b = st ? st.base[n] : null;
        out[n] = v === null || v === undefined || b === null || b === undefined ? null : Math.max(0, v - b);
      }
      return out;
    },
    /** The day the baseline belongs to ("2026-10-25"), or null before the first reading. */
    get date() { const st = load(); return st ? st.date : null; },
  };
}
