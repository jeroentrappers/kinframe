import { describe, expect, it } from 'vitest';
import {
	addDays,
	buildInvitation,
	COLORS,
	compareEvents,
	daysUntil,
	eventsByDay,
	eventSpan,
	isoWeek,
	isPast,
	localDate,
	monthGrid,
	newTimedView,
	nextColor,
	PALETTE,
	startOfWeek,
	weatherByDate,
	weatherKind,
	weekdayOrder,
	type CalendarEvent
} from './calendar';

const SRC = '0192f0a0-0000-7000-8000-000000000001';
const ev = (p: Partial<CalendarEvent> & { title: string }): CalendarEvent => ({ id: `ev_${p.title.replace(/\W/g, '')}xxxx`, source_id: SRC, all_day: false, ...p });

describe('palette', () => {
	it('has 12 colours with light and dark hex variants', () => {
		expect(COLORS).toHaveLength(12);
		for (const c of COLORS) {
			expect(PALETTE[c].light).toMatch(/^#[0-9A-F]{6}$/);
			expect(PALETTE[c].dark).toMatch(/^#[0-9A-F]{6}$/);
		}
	});
	it('picks the first unused colour, then cycles', () => {
		expect(nextColor([])).toBe('coral');
		expect(nextColor(['coral', 'amber'])).toBe('tangerine');
		expect(nextColor([...COLORS])).toBe('coral');
	});
});

describe('dates', () => {
	it('adds days across months and years', () => {
		expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
		expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
	});
	it('finds the start of the week for each first day', () => {
		// 2026-10-22 is a Thursday.
		expect(startOfWeek('2026-10-22', 'mon')).toBe('2026-10-19');
		expect(startOfWeek('2026-10-22', 'sun')).toBe('2026-10-18');
		expect(startOfWeek('2026-10-22', 'sat')).toBe('2026-10-17');
		expect(weekdayOrder('sun')[0]).toBe('sun');
	});
	it('computes ISO week numbers', () => {
		expect(isoWeek('2026-01-01')).toBe(1);
		expect(isoWeek('2026-10-22')).toBe(43);
		expect(isoWeek('2027-01-01')).toBe(53);
		expect(isoWeek('2024-12-30')).toBe(1);
	});
	it('builds month grids of full weeks', () => {
		const g = monthGrid(2026, 2, 'mon');
		// 1 February 2026 is a Sunday.
		expect(g).toHaveLength(5);
		expect(g[0]![0]).toBe('2026-01-26');
		expect(g.at(-1)![6]).toBe('2026-03-01');
		expect(monthGrid(2027, 2, 'mon')).toHaveLength(4); // 1 February 2027 is a Monday
		const oct = monthGrid(2026, 10, 'mon');
		expect(oct[0]![0]).toBe('2026-09-28');
		expect(oct.at(-1)![6]).toBe('2026-11-01');
		expect(monthGrid(2026, 10, 'sun')[0]![0]).toBe('2026-09-27');
	});
});

describe('events', () => {
	const tz = 'Europe/Brussels';
	it('maps instants to the frame’s local date', () => {
		expect(localDate('2026-10-21T22:30:00Z', tz)).toBe('2026-10-22');
		expect(localDate('2026-10-21T22:30:00Z', 'America/New_York')).toBe('2026-10-21');
	});
	it('spans timed events over days; midnight ends do not touch the next day', () => {
		expect(eventSpan(ev({ title: 'a', start_at: '2026-10-22T20:00:00Z', end_at: '2026-10-22T23:00:00Z' }), tz)).toEqual(['2026-10-22', '2026-10-23']);
		expect(eventSpan(ev({ title: 'b', start_at: '2026-10-22T08:00:00Z', end_at: '2026-10-22T22:00:00Z' }), tz)).toEqual(['2026-10-22', '2026-10-22']);
		expect(eventSpan(ev({ title: 'c', all_day: true, start_date: '2026-10-22', end_date: '2026-10-24' }), tz)).toEqual(['2026-10-22', '2026-10-24']);
	});
	it('puts all-day and multi-day events on top, then timed by start', () => {
		const timed = ev({ title: 'Dentist', start_at: '2026-10-22T07:00:00Z', end_at: '2026-10-22T08:00:00Z' });
		const early = ev({ title: 'Breakfast', start_at: '2026-10-22T05:00:00Z', end_at: '2026-10-22T06:00:00Z' });
		const allDay = ev({ title: 'Birthday', all_day: true, start_date: '2026-10-22', end_date: '2026-10-22' });
		const trip = ev({ title: 'Trip', all_day: true, start_date: '2026-10-20', end_date: '2026-10-25' });
		const map = eventsByDay([timed, allDay, early, trip], tz, '2026-10-19', '2026-10-25');
		expect(map.get('2026-10-22')!.map((e) => e.title)).toEqual(['Trip', 'Birthday', 'Breakfast', 'Dentist']);
		expect(map.get('2026-10-20')!.map((e) => e.title)).toEqual(['Trip']);
		expect(map.has('2026-10-26')).toBe(false);
		expect(compareEvents(allDay, timed, tz)).toBeLessThan(0);
	});
	it('clips events to the requested range', () => {
		const long = ev({ title: 'Long', all_day: true, start_date: '2026-09-01', end_date: '2026-12-31' });
		const map = eventsByDay([long], tz, '2026-10-01', '2026-10-03');
		expect([...map.keys()]).toEqual(['2026-10-01', '2026-10-02', '2026-10-03']);
	});
	it('knows past events and countdown days', () => {
		const now = new Date('2026-10-22T10:00:00Z');
		expect(isPast(ev({ title: 'x', start_at: '2026-10-22T07:00:00Z', end_at: '2026-10-22T08:00:00Z' }), tz, now)).toBe(true);
		expect(isPast(ev({ title: 'y', all_day: true, start_date: '2026-10-22' }), tz, now)).toBe(false);
		expect(daysUntil({ all_day: true, start_date: '2026-12-25' }, tz, now)).toBe(64);
		expect(daysUntil({ all_day: false, start_at: '2026-10-22T23:30:00Z' }, tz, now)).toBe(1);
	});
});

describe('weather', () => {
	it('maps WMO codes to icons', () => {
		expect(weatherKind(0)).toBe('clear');
		expect(weatherKind(2)).toBe('partly');
		expect(weatherKind(45)).toBe('fog');
		expect(weatherKind(63)).toBe('rain');
		expect(weatherKind(81)).toBe('showers');
		expect(weatherKind(73)).toBe('snow');
		expect(weatherKind(95)).toBe('storm');
	});
	it('indexes the daily forecast by date', () => {
		const m = weatherByDate({ fetched_at: '', current: { temp_c: 1, code: 0, is_day: true }, daily: [{ date: '2026-10-22', min_c: 4, max_c: 12, code: 3 }] });
		expect(m.get('2026-10-22')?.max_c).toBe(12);
		expect(weatherByDate(null).size).toBe(0);
	});
});

describe('send event form', () => {
	const base = { title: 'Lunch', allDay: false, start: '2026-10-25T12:00', end: '2026-10-25T13:30', startDate: '', endDate: '', location: ' Café ', notes: '' };
	it('builds a timed invitation', () => {
		const r = buildInvitation(base);
		expect('body' in r && r.body.all_day).toBe(false);
		expect('body' in r && r.body.location).toBe('Café');
		expect('body' in r && 'notes' in r.body).toBe(false);
	});
	it('builds an all-day invitation with the end defaulting to the start', () => {
		const r = buildInvitation({ ...base, allDay: true, startDate: '2026-12-24' });
		expect(r).toEqual({ body: { title: 'Lunch', all_day: true, start_date: '2026-12-24', end_date: '2026-12-24', location: 'Café' } });
	});
	it('rejects missing titles and reversed times', () => {
		expect(buildInvitation({ ...base, title: '  ' })).toEqual({ error: 'title' });
		expect(buildInvitation({ ...base, end: '2026-10-25T11:00' })).toEqual({ error: 'order' });
		expect(buildInvitation({ ...base, allDay: true, startDate: '2026-12-24', endDate: '2026-12-23' })).toEqual({ error: 'order' });
	});
	it('creates timed-view schedules for 07:00–09:00 every day', () => {
		expect(newTimedView('x')).toEqual({ id: 'x', from: '07:00', to: '09:00', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], enabled: true });
	});
});
