// kinframe/series — a short history of a live value, for trend arrows and "last hour" sparklines
// (docs/plugins.md §13.1). However often the device is read, the series keeps one averaged sample per step (5 s by
// default), so its meaning doesn't change with the refresh interval, and its size stays fixed (an hour by default).
//
//   import { series } from 'kinframe/series';
//
//   update(ctx) { series(ctx, 'power').add(watts, ctx.now()); }
//   render(ctx) { const s = series(ctx, 'power'); s.trend();  s.buckets(); s.last(); }
//
// Lives in ctx.state (lost on restart, like any live history).

const HOUR_MS = 3600000;

/** The series [name] of this instance; `stepMs` and `samples` apply when it is first created. */
export function series(ctx, name, { stepMs = 5000, samples = 720 } = {}) {
  const slot = 'series:' + name;
  if (!ctx.state[slot]) {
    ctx.state[slot] = { values: new Array(samples).fill(NaN), times: new Array(samples).fill(0), head: 0, count: 0, slotStart: 0, n: 0, stepMs, size: samples };
  }
  const h = ctx.state[slot];
  const N = h.size;
  const at = (k) => (h.head - 1 - k + N * 2) % N; // k samples back from the newest

  return {
    get count() { return h.count; },

    /** Adds a reading taken at [time] (epoch ms); readings within one step are averaged into one sample. */
    add(value, time) {
      if (typeof value !== 'number' || Number.isNaN(value)) return;
      if (h.count > 0 && time - h.slotStart < h.stepMs) {
        const i = at(0);
        h.n++;
        h.values[i] += (value - h.values[i]) / h.n;
        h.times[i] = time;
        return;
      }
      h.values[h.head] = value;
      h.times[h.head] = time;
      h.slotStart = time; h.n = 1;
      h.head = (h.head + 1) % N;
      if (h.count < N) h.count++;
    },

    /** The newest sample, or null. */
    last() { return h.count ? h.values[at(0)] : null; },

    /**
     * 'up' or 'down' when the newest sample differs from the average of the [window] samples before it by more than
     * [ratio] of that average and at least [min]; otherwise null. Needs 4 samples.
     */
    trend({ window = 6, ratio = 0.1, min = 50 } = {}) {
      if (h.count < 4) return null;
      const now = h.values[at(0)];
      let sum = 0, n = 0;
      for (let k = 1; k <= window && k < h.count; k++) {
        const v = h.values[at(k)];
        if (!Number.isNaN(v)) { sum += v; n++; }
      }
      if (n === 0) return null;
      const avg = sum / n;
      const delta = now - avg;
      if (Math.abs(delta) < Math.max(min, Math.abs(avg) * ratio)) return null;
      return delta > 0 ? 'up' : 'down';
    },

    /** The last [spanMs] (an hour) in [n] averaged buckets, null where there was no reading: a sparkline's points. */
    buckets(spanMs = HOUR_MS, n = 96) {
      if (h.count === 0) return [];
      const end = h.times[at(0)];
      const start = end - spanMs;
      const sums = new Array(n).fill(0), ns = new Array(n).fill(0);
      for (let k = 0; k < h.count; k++) {
        const i = at(k);
        const t = h.times[i];
        if (t < start) break;
        const b = Math.min(n - 1, Math.max(0, Math.floor(((t - start) / spanMs) * (n - 1))));
        sums[b] += h.values[i]; ns[b]++;
      }
      return sums.map((s, i) => (ns[i] === 0 ? null : s / ns[i]));
    },
  };
}
