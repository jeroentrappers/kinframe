// The public weather station page's logic, apart from its views: units, the pressure trend, chart scales and
// local times. The data comes from GET /v1/stations/{id}/public (docs/plugins.md §9.4).
import type { components } from '$lib/api/schema';

export type StationPage = components['schemas']['StationPublic'];
export type StationSeries = components['schemas']['StationSeries'];
export type Values = (number | null)[];

export type WindUnit = 'kmh' | 'ms' | 'bft';
export const WIND_UNITS: WindUnit[] = ['kmh', 'ms', 'bft'];

// Upper bounds (m/s) of Beaufort 0–11; above is 12.
const BEAUFORT = [0.3, 1.6, 3.4, 5.5, 8, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7];

export function beaufort(ms: number): number {
	const i = BEAUFORT.findIndex((b) => ms < b);
	return i < 0 ? 12 : i;
}

/** A wind speed in the chosen unit, as a number for charts (Beaufort as its fractional scale for smooth lines). */
export function windValue(ms: number, unit: WindUnit): number {
	if (unit === 'kmh') return ms * 3.6;
	if (unit === 'bft') return Math.cbrt((ms / 0.836) ** 2); // the Beaufort scale's own formula, v = 0.836·B^1.5
	return ms;
}

export function windUnitLabel(unit: WindUnit, bft: string): string {
	return unit === 'kmh' ? 'km/h' : unit === 'ms' ? 'm/s' : bft;
}

/** "SW" for 225°, from the page's 16 localized directions. */
export function compass(deg: number, directions: string): string {
	const names = directions.split(',');
	return names[Math.round((((deg % 360) + 360) % 360) / 22.5) % 16] ?? '';
}

export type UvLevel = 'uvLow' | 'uvModerate' | 'uvHigh' | 'uvVeryHigh' | 'uvExtreme';

/** The WHO UV index categories. */
export function uvLevel(uv: number): UvLevel {
	return uv < 3 ? 'uvLow' : uv < 6 ? 'uvModerate' : uv < 8 ? 'uvHigh' : uv < 11 ? 'uvVeryHigh' : 'uvExtreme';
}

/** The last non-null value at or before index `i`, within `within` steps. */
export function lastAt(values: Values, i: number, within = 3): number | null {
	for (let k = Math.min(i, values.length - 1); k >= 0 && i - k <= within; k--) {
		const v = values[k];
		if (v != null) return v;
	}
	return null;
}

/**
 * The change over the last three hours (hPa) and how meteorologists name it: steady within ±1 hPa, else rising or
 * falling. Null when there is no value three hours back.
 */
export function pressureTrend(series: StationSeries): { change: number; kind: 'rising' | 'falling' | 'steady' } | null {
	const p = series.pressure_hpa;
	const steps = Math.round((3 * 3600) / series.step_s);
	const end = p.length - 1;
	const now = lastAt(p, end, 6);
	const then = lastAt(p, end - steps, 6);
	if (now == null || then == null) return null;
	const change = Math.round((now - then) * 10) / 10;
	return {
		change,
		kind: change >= 1 ? 'rising' : change <= -1 ? 'falling' : 'steady'
	};
}

/** Round numbers between lo and hi for an axis: about `count` of them, steps of 1, 2, 2.5 or 5 × 10ⁿ. */
export function niceTicks(lo: number, hi: number, count = 4): number[] {
	if (!isFinite(lo) || !isFinite(hi)) return [];
	if (hi - lo < 1e-9) {
		lo -= 1;
		hi += 1;
	}
	const raw = (hi - lo) / count;
	const mag = 10 ** Math.floor(Math.log10(raw));
	const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? 10 * mag;
	const out: number[] = [];
	for (let v = Math.floor(lo / step) * step; v <= hi + step * 1e-9; v += step) out.push(Math.round(v / step) * step);
	const first = out[0] ?? lo;
	const last = out[out.length - 1] ?? hi;
	if (first > lo) out.unshift(first - step);
	if (last < hi) out.push(last + step);
	return out;
}

/** The domain of one or more series' values (nulls skipped), widened to at least `minSpan` and to zero if asked. */
export function domain(series: Values[], opts: { zero?: boolean; minSpan?: number } = {}): [number, number] | null {
	let lo = Infinity;
	let hi = -Infinity;
	for (const s of series)
		for (const v of s)
			if (v != null) {
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
	if (lo === Infinity) return null;
	if (opts.zero) lo = Math.min(0, lo);
	const span = opts.minSpan ?? 0;
	if (hi - lo < span) {
		const mid = (hi + lo) / 2;
		lo = opts.zero ? Math.max(0, Math.min(lo, mid - span / 2)) : mid - span / 2;
		hi = Math.max(hi, lo + span);
	}
	return [lo, hi];
}

/** Wall-clock parts of an instant in the station's zone. */
export function zonedParts(ms: number, timeZone: string): { hour: number; minute: number; day: string } {
	const parts = new Intl.DateTimeFormat('en-GB', {
		timeZone,
		hour: '2-digit',
		minute: '2-digit',
		hourCycle: 'h23',
		year: 'numeric',
		month: '2-digit',
		day: '2-digit'
	}).formatToParts(ms);
	const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
	return {
		hour: Number(get('hour')),
		minute: Number(get('minute')),
		day: `${get('year')}-${get('month')}-${get('day')}`
	};
}

/**
 * Where the time axis gets a label: every `everyHours` on the hour, in the station's zone. A label at midnight is the
 * day's name rather than "00:00".
 */
export function timeTicks(
	startMs: number,
	stepS: number,
	from: number,
	to: number,
	timeZone: string,
	everyHours: number
): { i: number; midnight: boolean }[] {
	const out: { i: number; midnight: boolean }[] = [];
	const perHour = 3600 / stepS;
	for (let i = from; i < to; i++) {
		const { hour, minute } = zonedParts(startMs + i * stepS * 1000, timeZone);
		if (minute < stepS / 60 && hour % everyHours === 0) {
			out.push({ i, midnight: hour === 0 });
			i += Math.max(0, Math.floor(perHour) - 1);
		}
	}
	return out;
}

/**
 * Rain per hour (mm), from the station's running "rain today" total: placed at each hour's first step (null in
 * between), for bars that span the hour. The total resets at midnight, so a drop counts from zero.
 */
export function hourlyRain(today: Values, startMs: number, stepS: number, timeZone: string): Values {
	const out: Values = today.map(() => null);
	const perHour = Math.round(3600 / stepS);
	const first = zonedParts(startMs, timeZone).minute;
	const offset = ((60 - first) % 60) / (stepS / 60); // steps until the first full hour
	let prev: number | null = null;
	let bucket = -1;
	for (let i = 0; i < today.length; i++) {
		const v = today[i];
		if (v == null) continue;
		const inc = prev == null ? 0 : v >= prev ? v - prev : v;
		prev = v;
		const h = i < offset ? -1 : offset + Math.floor((i - offset) / perHour) * perHour;
		if (h < 0) continue;
		if (h !== bucket) {
			bucket = h;
			out[h] = 0;
		}
		out[h] = Math.round(((out[h] ?? 0) + inc) * 10) / 10;
	}
	return out;
}
