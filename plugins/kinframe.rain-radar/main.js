// Rain radar: the next two hours of rain where the frame is (ctx.location), as bars, with "Rain from 11:20" or
// "Dry from 12:05" above them. Buienradar's radar nowcast (5-minute steps) in and around Belgium and the Netherlands,
// Open-Meteo's 15-minute forecast elsewhere. While it stays dry the widget hides itself (a view of type "none").
// A template for a plugin that reads an internet API for the frame's own place.

/** Below this many mm/h it is dry (Buienradar's lowest steps are radar noise). */
export const WET_MMH = 0.1;
/** A light shower doesn't fill the chart: the bars' scale goes at least this high. */
const SCALE_MMH = 2;
const STEP_BR = 5 * 60000;

/** Roughly the area Buienradar's radar covers well. */
export function inBuienradarArea(lat, lon) {
  return lat >= 49.3 && lat <= 53.8 && lon >= 2.3 && lon <= 7.4;
}

export function sourceFor(setting, loc) {
  if (setting === 'buienradar' || setting === 'open_meteo') return setting;
  return inBuienradarArea(loc.latitude, loc.longitude) ? 'buienradar' : 'open_meteo';
}

/**
 * Buienradar's raintext: 24 lines of "value|HH:MM" in Dutch time, value 0–255 with mm/h = 10^((value − 109) / 32).
 * The first time is the current five minutes; [nowMs] and [here] (ctx.localTime in Europe/Amsterdam) place it.
 */
export function parseBuienradar(text, nowMs, here) {
  const rows = String(text).split(/\r?\n/).map((l) => /^\s*(\d{1,3})\|(\d{1,2}):(\d{2})\s*$/.exec(l)).filter(Boolean);
  if (!rows.length) throw new Error('buienradar: no data');
  const first = +rows[0][2] * 60 + +rows[0][3];
  let delta = first - (here.hour * 60 + here.minute);
  if (delta > 720) delta -= 1440;
  if (delta < -720) delta += 1440;
  const start = Math.floor(nowMs / 60000) * 60000 + delta * 60000;
  return rows.map((m, i) => {
    const v = +m[1];
    return { t: start + i * STEP_BR, mmh: v <= 0 ? 0 : Math.round(Math.pow(10, (v - 109) / 32) * 100) / 100 };
  });
}

/** Open-Meteo's minutely_15 precipitation (mm per quarter hour, UTC times): from the current quarter, two hours. */
export function parseOpenMeteo(json, nowMs) {
  const m = json && json.minutely_15;
  if (!m || !Array.isArray(m.time) || !Array.isArray(m.precipitation)) throw new Error('open-meteo: no data');
  const out = [];
  m.time.forEach((t, i) => {
    const ms = Date.parse(t + 'Z');
    const mm = m.precipitation[i];
    if (Number.isFinite(ms) && typeof mm === 'number' && ms + 15 * 60000 > nowMs) out.push({ t: ms, mmh: Math.round(mm * 4 * 100) / 100 });
  });
  return out.slice(0, 8);
}

/** What the next two hours hold: "rain_from", "dry_from" (with the time), "rain_2h" or "dry_2h". */
export function summary(points) {
  const wet = (p) => p.mmh >= WET_MMH;
  if (!points.length) return { kind: 'dry_2h' };
  if (wet(points[0])) {
    const dry = points.findIndex((p) => !wet(p));
    return dry < 0 ? { kind: 'rain_2h' } : { kind: 'dry_from', at: points[dry].t };
  }
  const rain = points.findIndex(wet);
  return rain < 0 ? { kind: 'dry_2h' } : { kind: 'rain_from', at: points[rain].t };
}

/**
 * The words above the bars, and the big value: the time ("Rain from" · 11:20) when it starts or stops within the two
 * hours, the time being what matters and fitting even the band's narrow column; else how hard it rains at most.
 */
function headline(s, peak, ctx) {
  if (s.at !== undefined) return { label: ctx.t(s.kind + '_label'), value: ctx.format.time(s.at) };
  return { label: ctx.t(s.kind), value: ctx.format.number(peak, peak < 10 ? 1 : 0, 'mm/h') };
}

/** The corner and band view; null keeps the last one, a "none" view hides the widget while it stays dry. */
export function view(state, ctx) {
  const points = state.points;
  if (!points) return null;
  const s = summary(points);
  if (s.kind === 'dry_2h') {
    return ctx.settings.show_dry ? { type: 'value', value: ctx.t('dry'), label: ctx.t('next_2h'), icon: 'sun' } : { type: 'none' };
  }
  const peak = Math.max(...points.map((p) => p.mmh));
  const h = headline(s, peak, ctx);
  return {
    type: 'sparkline', style: 'bars', tone: 'water',
    points: points.map((p) => p.mmh), min: 0, max: Math.max(SCALE_MMH, peak),
    label: h.label.slice(0, 32), value: h.value,
  };
}

/** The detail card: the same chart, the times at either end and where the data comes from. */
export function detail(state, ctx) {
  const v = view(state, ctx);
  if (!v || v.type === 'none') {
    return { type: 'stack', items: [{ type: 'value', value: ctx.t('dry'), label: ctx.t('next_2h'), icon: 'sun' }, source(state, ctx)] };
  }
  const p = state.points;
  const s = summary(p);
  const peak = Math.max(...p.map((x) => x.mmh));
  // The whole sentence ("Rain from 11:20"), at most how hard, and the span the bars cover.
  const what = s.at === undefined ? ctx.t(s.kind) : ctx.t(s.kind, { time: ctx.format.time(s.at) });
  const span = { type: 'text', lines: [what, `${ctx.t('peak')} ${ctx.format.number(peak, peak < 10 ? 1 : 0, 'mm/h')} · ${ctx.format.time(p[0].t)} – ${ctx.format.time(p[p.length - 1].t)}`] };
  return { type: 'stack', items: [v, span, source(state, ctx)] };
}

function source(state, ctx) {
  return { type: 'text', lines: [ctx.t('source', { name: state.source === 'buienradar' ? 'Buienradar' : 'Open-Meteo' })] };
}

export default {
  async update(ctx) {
    const loc = ctx.location;
    if (!loc) throw new Error(ctx.t('no_location'));
    const src = sourceFor(ctx.settings.source, loc);
    const now = ctx.now();
    if (src === 'buienradar') {
      const r = await ctx.http.get(`https://gpsgadget.buienradar.nl/data/raintext?lat=${loc.latitude}&lon=${loc.longitude}`);
      if (!r.ok) throw new Error('buienradar: HTTP ' + r.status);
      ctx.state.points = parseBuienradar(r.text, now, ctx.localTime(now, 'Europe/Amsterdam'));
    } else {
      const r = await ctx.http.get(`https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&minutely_15=precipitation&forecast_minutely_15=12&past_minutely_15=1&timezone=UTC`);
      if (!r.ok) throw new Error('open-meteo: HTTP ' + r.status);
      ctx.state.points = parseOpenMeteo(r.json(), now);
    }
    ctx.state.source = src;
    ctx.dataTime = now;
  },
  render(ctx) {
    return view(ctx.state, ctx);
  },
  detail(ctx) {
    return ctx.state.points ? detail(ctx.state, ctx) : null;
  },
};
