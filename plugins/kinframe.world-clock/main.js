// World clock: up to four places, each with its time, the day when it isn't the frame's day, and how far ahead or
// behind it is. No network: the frame does the time zone arithmetic (ctx.localTime and ctx.format.time take an IANA
// zone), because QuickJS's Date only knows the device's own zone. A template for any plugin that only computes.

const DAY_MS = 86400000;

/** "America/New_York" → "New York", "America/Argentina/Buenos_Aires" → "Buenos Aires". */
export function cityOf(zone) {
  const last = String(zone).split('/').pop() || String(zone);
  return last.replace(/_/g, ' ');
}

/** The places the owner filled in, in order: [{ zone, name }]. */
export function places(settings) {
  const out = [];
  for (let i = 1; i <= 4; i++) {
    const zone = settings['city_' + i];
    if (typeof zone !== 'string' || !zone.trim()) continue;
    const name = typeof settings['name_' + i] === 'string' && settings['name_' + i].trim() ? settings['name_' + i].trim() : cityOf(zone.trim());
    out.push({ zone: zone.trim(), name: name.slice(0, 40) });
  }
  return out;
}

/** "+6 h", "−4:30", or null when it is the frame's own time. Short: a list row's detail holds 16 characters. */
export function difference(minutes) {
  if (minutes === 0) return null;
  const m = Math.abs(minutes);
  const sign = minutes > 0 ? '+' : '−';
  return m % 60 === 0 ? `${sign}${m / 60} h` : `${sign}${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`;
}

/** The row's detail: the day when it isn't the frame's, then the difference while both fit. */
export function detailOf(dayWord, diff) {
  const both = [dayWord, diff].filter(Boolean).join(' · ');
  return both.length <= 16 ? both : dayWord || diff;
}

/** One place now: its time, its day relative to the frame's ("Tomorrow"), the difference, day or night. */
export function row(place, ctx, now) {
  const here = ctx.localTime(now);
  const there = ctx.localTime(now, place.zone);
  const dayWord = there.date > here.date ? ctx.format.day(now + DAY_MS) : there.date < here.date ? ctx.format.day(now - DAY_MS) : null;
  const diff = ctx.settings.show_difference === false ? null : difference(there.offset_minutes - here.offset_minutes);
  const detail = detailOf(dayWord, diff);
  const r = { time: ctx.format.time(now, place.zone), title: place.name, icon: there.hour >= 7 && there.hour < 19 ? 'sun' : 'moon' };
  if (detail) r.detail = detail;
  return r;
}

export function view(ctx) {
  const list = places(ctx.settings);
  if (!list.length) return null;
  const now = ctx.now();
  return { type: 'list', rows: list.map((p) => row(p, ctx, now)) };
}

export default {
  render(ctx) {
    return view(ctx);
  },
  detail(ctx) {
    return view(ctx);
  },
};
