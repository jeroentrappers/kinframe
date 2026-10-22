// Pure calendar helpers for the frame's Calendars tab (docs/api/README.md §5.8). No Svelte, no DOM.
import type { components } from './api/schema';

type S = components['schemas'];
export type CalendarColor = S['CalendarColor'];
export type CalendarEvent = S['CalendarEvent'];
export type Calendar = S['Calendar'];
export type Weather = NonNullable<S['Weather']>;
export type WeatherDay = NonNullable<Weather['daily']>[number];
export type FirstDay = 'mon' | 'sun' | 'sat';

// ------------------------------------------------------------------ palette

/** The shared 12-colour palette (README §5.8): light for light backgrounds, dark for dark ones. */
export const PALETTE: Record<CalendarColor, { light: string; dark: string }> = {
	coral: { light: '#E5726A', dark: '#F0958E' },
	tangerine: { light: '#EE8F4F', dark: '#F4AE7E' },
	amber: { light: '#DDA731', dark: '#E9C26A' },
	olive: { light: '#9AA63E', dark: '#BBC575' },
	mint: { light: '#4FB28A', dark: '#82CDAE' },
	sage: { light: '#7F9C77', dark: '#A8C0A1' },
	teal: { light: '#349E9A', dark: '#6EC1BD' },
	sky: { light: '#4F9FDF', dark: '#87BFEB' },
	ocean: { light: '#4767C8', dark: '#8197DD' },
	lavender: { light: '#8E7BD0', dark: '#B3A6E2' },
	plum: { light: '#AC5F9F', dark: '#CA92C1' },
	rose: { light: '#DA6C92', dark: '#E79AB5' }
};

export const COLORS = Object.keys(PALETTE) as CalendarColor[];

/** The first palette colour not used yet (the server's rule for new sources), cycling when all are taken. */
export function nextColor(used: readonly (string | null | undefined)[]): CalendarColor {
	const free = COLORS.find((c) => !used.includes(c));
	if (free) return free;
	return COLORS[used.length % COLORS.length]!;
}

/** CSS custom properties for a palette colour; the app's stylesheet picks light or dark per scheme. */
export function colorVars(color: string | null | undefined): string {
	const p = PALETTE[(color ?? '') as CalendarColor] ?? PALETTE.sky;
	return `--ev-light:${p.light};--ev-dark:${p.dark}`;
}

// ------------------------------------------------------------------ plain dates (YYYY-MM-DD)

const toUTC = (d: string) => {
	const [y, m, day] = d.split('-').map(Number);
	return new Date(Date.UTC(y!, m! - 1, day!));
};
const fromUTC = (d: Date) => d.toISOString().slice(0, 10);

export function addDays(date: string, n: number): string {
	const d = toUTC(date);
	d.setUTCDate(d.getUTCDate() + n);
	return fromUTC(d);
}

/** Whole days from a to b (b - a). */
export function daysBetween(a: string, b: string): number {
	return Math.round((toUTC(b).getTime() - toUTC(a).getTime()) / 86_400_000);
}

/** 0 = Monday … 6 = Sunday. */
export function weekdayIndex(date: string): number {
	return (toUTC(date).getUTCDay() + 6) % 7;
}

const FIRST: Record<FirstDay, number> = { mon: 0, sun: 6, sat: 5 };

export function startOfWeek(date: string, first: FirstDay = 'mon'): string {
	return addDays(date, -((weekdayIndex(date) - FIRST[first] + 7) % 7));
}

/** ISO 8601 week number. */
export function isoWeek(date: string): number {
	const d = toUTC(date);
	d.setUTCDate(d.getUTCDate() + 3 - ((d.getUTCDay() + 6) % 7)); // Thursday of this week
	const jan1 = Date.UTC(d.getUTCFullYear(), 0, 1);
	return Math.ceil(((d.getTime() - jan1) / 86_400_000 + 1) / 7);
}

/** Full weeks covering a month (`month` 1–12): 4 to 6 rows of 7 dates. */
export function monthGrid(year: number, month: number, first: FirstDay = 'mon'): string[][] {
	const firstOfMonth = fromUTC(new Date(Date.UTC(year, month - 1, 1)));
	const last = fromUTC(new Date(Date.UTC(year, month, 0)));
	const weeks: string[][] = [];
	let d = startOfWeek(firstOfMonth, first);
	while (d <= last) {
		weeks.push(Array.from({ length: 7 }, (_, i) => addDays(d, i)));
		d = addDays(d, 7);
	}
	return weeks;
}

/** Weekday order for a first day of the week, as `day.*` keys. */
export function weekdayOrder(first: FirstDay = 'mon'): ('mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun')[] {
	const all = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
	return Array.from({ length: 7 }, (_, i) => all[(FIRST[first] + i) % 7]!);
}

// ------------------------------------------------------------------ time zones

const dateFmt = new Map<string, Intl.DateTimeFormat>();

/** The calendar date of an instant in a time zone. */
export function localDate(iso: string, timeZone: string): string {
	let f = dateFmt.get(timeZone);
	if (!f) {
		try {
			f = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' });
		} catch {
			f = new Intl.DateTimeFormat('en-CA', { timeZone: 'UTC', year: 'numeric', month: '2-digit', day: '2-digit' });
		}
		dateFmt.set(timeZone, f);
	}
	return f.format(new Date(iso));
}

/** "09:30" (or "9:30 AM") in the frame's time zone. */
export function localTime(iso: string, timeZone: string, locale = 'en-GB', hour12 = false): string {
	try {
		return new Intl.DateTimeFormat(locale, { timeZone, hour: '2-digit', minute: '2-digit', hour12 }).format(new Date(iso));
	} catch {
		return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12 }).format(new Date(iso));
	}
}

/** Today in a time zone. */
export function today(timeZone: string, now: Date = new Date()): string {
	return localDate(now.toISOString(), timeZone);
}

// ------------------------------------------------------------------ events

/** First and last calendar day (inclusive) an event covers in a time zone. */
export function eventSpan(ev: CalendarEvent, timeZone: string): [string, string] {
	if (ev.all_day) {
		const start = ev.start_date ?? (ev.start_at ? localDate(ev.start_at, timeZone) : '1970-01-01');
		const end = ev.end_date ?? start;
		return [start, end < start ? start : end];
	}
	const start = localDate(ev.start_at!, timeZone);
	if (!ev.end_at || ev.end_at <= ev.start_at!) return [start, start];
	// An event ending exactly at midnight does not touch the next day.
	const end = localDate(new Date(new Date(ev.end_at).getTime() - 1).toISOString(), timeZone);
	return [start, end < start ? start : end];
}

/** Spans more than one day (shown with all-day events, on top). */
export function isMultiDay(ev: CalendarEvent, timeZone: string): boolean {
	const [a, b] = eventSpan(ev, timeZone);
	return a !== b;
}

/** All-day and multi-day events first (earlier start, then longer), then timed events by start, then title. */
export function compareEvents(a: CalendarEvent, b: CalendarEvent, timeZone: string): number {
	const topA = a.all_day || isMultiDay(a, timeZone);
	const topB = b.all_day || isMultiDay(b, timeZone);
	if (topA !== topB) return topA ? -1 : 1;
	const [sa, ea] = eventSpan(a, timeZone);
	const [sb, eb] = eventSpan(b, timeZone);
	if (topA) {
		if (sa !== sb) return sa < sb ? -1 : 1;
		if (ea !== eb) return ea > eb ? -1 : 1;
	} else if (a.start_at !== b.start_at) {
		return (a.start_at ?? '') < (b.start_at ?? '') ? -1 : 1;
	}
	return a.title.localeCompare(b.title);
}

/** Events per local day between from and to (inclusive), each day sorted with compareEvents. */
export function eventsByDay(events: readonly CalendarEvent[], timeZone: string, from: string, to: string): Map<string, CalendarEvent[]> {
	const out = new Map<string, CalendarEvent[]>();
	for (const ev of events) {
		const [s, e] = eventSpan(ev, timeZone);
		let d = s < from ? from : s;
		const end = e > to ? to : e;
		for (let guard = 0; d <= end && guard < 400; guard++, d = addDays(d, 1)) {
			const list = out.get(d);
			if (list) list.push(ev);
			else out.set(d, [ev]);
		}
	}
	for (const list of out.values()) list.sort((a, b) => compareEvents(a, b, timeZone));
	return out;
}

/** Whether an event is over at `now`. */
export function isPast(ev: CalendarEvent, timeZone: string, now: Date = new Date()): boolean {
	if (ev.all_day) return eventSpan(ev, timeZone)[1] < today(timeZone, now);
	return new Date(ev.end_at ?? ev.start_at!).getTime() <= now.getTime();
}

/** Days left until a countdown's day (0 = today), in the frame's time zone. */
export function daysUntil(c: { all_day: boolean; start_at?: string | null; start_date?: string | null }, timeZone: string, now: Date = new Date()): number {
	const day = c.all_day ? (c.start_date ?? today(timeZone, now)) : localDate(c.start_at!, timeZone);
	return daysBetween(today(timeZone, now), day);
}

// ------------------------------------------------------------------ weather

/** The daily forecast keyed by date. */
export function weatherByDate(w: Weather | null | undefined): Map<string, WeatherDay> {
	return new Map((w?.daily ?? []).map((d) => [d.date, d]));
}

export type WeatherKind = 'clear' | 'partly' | 'cloudy' | 'fog' | 'drizzle' | 'rain' | 'snow' | 'showers' | 'storm';

/** A coarse icon for a WMO weather code. */
export function weatherKind(code: number): WeatherKind {
	if (code <= 1) return 'clear';
	if (code === 2) return 'partly';
	if (code === 3) return 'cloudy';
	if (code === 45 || code === 48) return 'fog';
	if (code >= 51 && code <= 57) return 'drizzle';
	if ((code >= 61 && code <= 67) || code === 80 || code === 81 || code === 82) return code >= 80 ? 'showers' : 'rain';
	if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snow';
	if (code >= 95) return 'storm';
	return 'cloudy';
}

export function formatTemp(c: number, unit: 'c' | 'f' = 'c'): string {
	return `${Math.round(unit === 'f' ? c * 1.8 + 32 : c)}°`;
}

// ------------------------------------------------------------------ sending an event

export interface EventForm {
	title: string;
	allDay: boolean;
	/** `datetime-local` values (the sender's own time zone). */
	start: string;
	end: string;
	/** `date` values. */
	startDate: string;
	endDate: string;
	location: string;
	notes: string;
}

export type EventFormError = 'title' | 'start' | 'end' | 'order';

/** Validate the send-event form; returns the first problem, or the request body. */
export function buildInvitation(f: EventForm): { error: EventFormError } | { body: Omit<S['EventInvitationCreate'], 'photo_upload_id' | 'photo_placement'> } {
	const title = f.title.trim();
	if (!title) return { error: 'title' };
	const extra = {
		...(f.location.trim() ? { location: f.location.trim() } : {}),
		...(f.notes.trim() ? { notes: f.notes.trim() } : {})
	};
	if (f.allDay) {
		if (!/^\d{4}-\d{2}-\d{2}$/.test(f.startDate)) return { error: 'start' };
		const end = f.endDate || f.startDate;
		if (!/^\d{4}-\d{2}-\d{2}$/.test(end)) return { error: 'end' };
		if (end < f.startDate) return { error: 'order' };
		return { body: { title, all_day: true, start_date: f.startDate, end_date: end, ...extra } };
	}
	const start = new Date(f.start);
	const end = new Date(f.end);
	if (!f.start || Number.isNaN(start.getTime())) return { error: 'start' };
	if (!f.end || Number.isNaN(end.getTime())) return { error: 'end' };
	if (end <= start) return { error: 'order' };
	return { body: { title, all_day: false, start_at: start.toISOString(), end_at: end.toISOString(), ...extra } };
}

/** `datetime-local` value for a Date in the browser's time zone. */
export function toLocalInput(d: Date): string {
	const p = (n: number) => String(n).padStart(2, '0');
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** A sensible default: next full hour, one hour long. */
export function defaultEventTimes(now: Date = new Date()): { start: string; end: string; date: string } {
	const s = new Date(now);
	s.setMinutes(0, 0, 0);
	s.setHours(s.getHours() + 1);
	const e = new Date(s.getTime() + 3_600_000);
	return { start: toLocalInput(s), end: toLocalInput(e), date: toLocalInput(s).slice(0, 10) };
}

// ------------------------------------------------------------------ timed view schedules

export type Day = 'mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun';
export const ALL_DAYS: Day[] = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

/** A new timed-view schedule: 07:00–09:00 on every day (Frameo's default). */
export function newTimedView(id: string): S['CalendarSchedule'] {
	return { id, from: '07:00', to: '09:00', days: [...ALL_DAYS], enabled: true };
}
