// Pure helpers for video trimming, albums and memories. Unit-tested in parity.test.ts.

// ------------------------------------------------------------------ video trim

/** The shortest part of a video a sender may keep (product spec 4.3). */
export const MIN_TRIM_S = 3;

/** A selection in seconds. */
export interface Trim {
	start: number;
	end: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Shortest allowed selection: 3 s, or the whole clip when it is shorter. */
export function minTrimLength(duration: number): number {
	return Math.min(MIN_TRIM_S, duration);
}

/** Initial selection: the whole video, or its first `maxS` seconds when it is too long. */
export function defaultTrim(duration: number, maxS: number): Trim {
	return { start: 0, end: Math.min(duration, maxS) };
}

/**
 * Move one handle to `value`, keeping the selection inside the clip and between the minimum and
 * `maxS` long. Dragging a handle past the maximum length pulls the other handle along.
 */
export function moveTrim(t: Trim, handle: 'start' | 'end', value: number, duration: number, maxS: number): Trim {
	const min = minTrimLength(duration);
	const max = Math.max(min, Math.min(maxS, duration));
	if (handle === 'start') {
		const start = clamp(value, 0, Math.max(0, t.end - min));
		return { start, end: Math.min(t.end, start + max) };
	}
	const end = clamp(value, Math.min(duration, t.start + min), duration);
	return { start: Math.max(t.start, end - max), end };
}

/** Slide the whole selection by `delta` seconds without changing its length. */
export function shiftTrim(t: Trim, delta: number, duration: number): Trim {
	const len = t.end - t.start;
	const start = clamp(t.start + delta, 0, Math.max(0, duration - len));
	return { start, end: start + len };
}

/** Whether a selection differs from the whole clip (sub-50 ms slack). */
export function isTrimmed(t: Trim | null, duration: number | null): boolean {
	if (!t || duration == null) return false;
	return t.start > 0.05 || t.end < duration - 0.05;
}

/** The `trim` field for POST /v1/sends, or undefined when the whole clip is kept. */
export function trimForApi(t: Trim | null, duration: number | null): { start_ms: number; end_ms: number } | undefined {
	if (!t || duration == null || !isTrimmed(t, duration)) return undefined;
	const start_ms = Math.max(0, Math.round(t.start * 1000));
	let end_ms = Math.round(t.end * 1000);
	// Rounding must never take a valid selection under the server's 3 s minimum.
	if (end_ms - start_ms < MIN_TRIM_S * 1000 && duration >= MIN_TRIM_S) end_ms = start_ms + MIN_TRIM_S * 1000;
	return { start_ms, end_ms: Math.max(end_ms, start_ms + 1) };
}

/** Seconds kept from a video: the selection, or the whole clip. */
export function keptSeconds(t: Trim | null, duration: number | null): number | null {
	if (duration == null) return null;
	return t ? t.end - t.start : duration;
}

/** Evenly spaced timestamps (s) for a filmstrip of `n` frames, centred in each slot. */
export function filmstripTimes(duration: number, n: number): number[] {
	if (!(duration > 0) || n <= 0) return [];
	return Array.from({ length: n }, (_, i) => Math.min(duration - 0.05, ((i + 0.5) * duration) / n));
}

// ------------------------------------------------------------------ albums

/** Move one element of a list to another index (new array). */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
	if (from === to || from < 0 || from >= list.length) return list;
	const out = [...list];
	const [x] = out.splice(from, 1);
	out.splice(clamp(to, 0, out.length), 0, x as T);
	return out;
}

/** Put `items` in the order of `ids` (an album's placement_ids); unknown items go last. */
export function orderBy<T extends { id: string }>(items: T[], ids: string[]): T[] {
	const pos = new Map(ids.map((id, i) => [id, i]));
	return [...items].sort((a, b) => (pos.get(a.id) ?? Infinity) - (pos.get(b.id) ?? Infinity));
}

/** Days in a month (1–12), counting 29 February so a schedule can include it. */
export function daysInMonth(month: number): number {
	return new Date(Date.UTC(2024, month, 0)).getUTCDate();
}

/** "MM-DD" for a month (1–12) and day. */
export function monthDay(month: number, day: number): string {
	return `${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** Parse "MM-DD" into numbers, or null. */
export function parseMonthDay(md: string | null | undefined): { month: number; day: number } | null {
	const m = /^(\d{2})-(\d{2})$/.exec(md ?? '');
	if (!m) return null;
	const month = Number(m[1]);
	const day = Number(m[2]);
	if (month < 1 || month > 12 || day < 1 || day > daysInMonth(month)) return null;
	return { month, day };
}

/** Today's "MM-DD" in the viewer's time zone (for "On this day"). */
export function todayMonthDay(now: Date = new Date()): string {
	return monthDay(now.getMonth() + 1, now.getDate());
}

/** Whether a yearly schedule covers a date; ranges may wrap the new year (12-20 → 01-06). */
export function scheduleCovers(schedule: { from_month_day?: string; to_month_day?: string } | null | undefined, now: Date = new Date()): boolean {
	if (!schedule?.from_month_day || !schedule.to_month_day) return false;
	const today = todayMonthDay(now);
	const { from_month_day: from, to_month_day: to } = schedule;
	return from <= to ? today >= from && today <= to : today >= from || today <= to;
}

/** "1 December" in the given locale. */
export function formatMonthDay(md: string, locale: string): string {
	const p = parseMonthDay(md);
	if (!p) return md;
	return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2024, p.month - 1, p.day)));
}

/** Whole years between a capture date and now (for "3 years ago"). */
export function yearsAgo(iso: string | null | undefined, now: Date = new Date()): number {
	if (!iso) return 0;
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return 0;
	return now.getFullYear() - d.getUTCFullYear();
}
