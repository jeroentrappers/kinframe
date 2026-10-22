import { describe, expect, it } from 'vitest';
import {
	daysInMonth,
	defaultTrim,
	filmstripTimes,
	formatMonthDay,
	isTrimmed,
	keptSeconds,
	monthDay,
	moveItem,
	moveTrim,
	orderBy,
	parseMonthDay,
	scheduleCovers,
	shiftTrim,
	todayMonthDay,
	trimForApi,
	yearsAgo
} from './parity';

describe('video trim', () => {
	it('defaults to the whole clip, or the first max seconds', () => {
		expect(defaultTrim(42, 300)).toEqual({ start: 0, end: 42 });
		expect(defaultTrim(400, 300)).toEqual({ start: 0, end: 300 });
	});

	it('keeps at least 3 s between the handles', () => {
		expect(moveTrim({ start: 0, end: 10 }, 'start', 9, 20, 300)).toEqual({ start: 7, end: 10 });
		expect(moveTrim({ start: 5, end: 10 }, 'end', 6, 20, 300)).toEqual({ start: 5, end: 8 });
		expect(moveTrim({ start: 0, end: 10 }, 'start', -4, 20, 300)).toEqual({ start: 0, end: 10 });
		expect(moveTrim({ start: 0, end: 10 }, 'end', 99, 20, 300)).toEqual({ start: 0, end: 20 });
	});

	it('drags the other handle along at the maximum length', () => {
		expect(moveTrim({ start: 0, end: 60 }, 'end', 90, 400, 60)).toEqual({ start: 30, end: 90 });
		expect(moveTrim({ start: 100, end: 160 }, 'start', 20, 400, 60)).toEqual({ start: 20, end: 80 });
	});

	it('handles clips shorter than the minimum', () => {
		expect(moveTrim({ start: 0, end: 2 }, 'start', 1, 2, 300)).toEqual({ start: 0, end: 2 });
		expect(isTrimmed({ start: 0, end: 2 }, 2)).toBe(false);
	});

	it('slides the selection inside the clip', () => {
		expect(shiftTrim({ start: 2, end: 7 }, 3, 20)).toEqual({ start: 5, end: 10 });
		expect(shiftTrim({ start: 2, end: 7 }, 30, 20)).toEqual({ start: 15, end: 20 });
		expect(shiftTrim({ start: 2, end: 7 }, -30, 20)).toEqual({ start: 0, end: 5 });
	});

	it('only sends trim when something was cut, never under 3 s', () => {
		expect(trimForApi({ start: 0, end: 20 }, 20)).toBeUndefined();
		expect(trimForApi(null, 20)).toBeUndefined();
		expect(trimForApi({ start: 1.2345, end: 4.2 }, 20)).toEqual({ start_ms: 1235, end_ms: 4235 });
		expect(trimForApi({ start: 5, end: 12.5 }, 20)).toEqual({ start_ms: 5000, end_ms: 12500 });
		expect(keptSeconds({ start: 5, end: 12.5 }, 20)).toBe(7.5);
		expect(keptSeconds(null, 20)).toBe(20);
		expect(keptSeconds(null, null)).toBeNull();
	});

	it('spreads filmstrip frames over the clip', () => {
		expect(filmstripTimes(10, 5)).toEqual([1, 3, 5, 7, 9]);
		expect(filmstripTimes(0, 5)).toEqual([]);
	});
});

describe('albums and memories', () => {
	it('reorders lists', () => {
		expect(moveItem(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd']);
		expect(moveItem(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b']);
		expect(moveItem(['a', 'b'], 1, 9)).toEqual(['a', 'b']);
		expect(orderBy([{ id: 'x' }, { id: 'b' }, { id: 'a' }], ['a', 'b'])).toEqual([{ id: 'a' }, { id: 'b' }, { id: 'x' }]);
	});

	it('parses and formats month-days', () => {
		expect(daysInMonth(2)).toBe(29);
		expect(daysInMonth(4)).toBe(30);
		expect(monthDay(12, 1)).toBe('12-01');
		expect(parseMonthDay('02-29')).toEqual({ month: 2, day: 29 });
		expect(parseMonthDay('04-31')).toBeNull();
		expect(parseMonthDay('13-01')).toBeNull();
		expect(formatMonthDay('12-01', 'en-GB')).toBe('1 December');
		expect(formatMonthDay('12-01', 'nl-BE')).toBe('1 december');
	});

	it('knows when a yearly schedule is active, across the new year', () => {
		const dec24 = new Date(2026, 11, 24);
		const jan3 = new Date(2027, 0, 3);
		const jul1 = new Date(2026, 6, 1);
		expect(scheduleCovers({ from_month_day: '12-01', to_month_day: '12-31' }, dec24)).toBe(true);
		expect(scheduleCovers({ from_month_day: '12-20', to_month_day: '01-06' }, jan3)).toBe(true);
		expect(scheduleCovers({ from_month_day: '12-20', to_month_day: '01-06' }, jul1)).toBe(false);
		expect(scheduleCovers(null, jul1)).toBe(false);
		expect(todayMonthDay(jan3)).toBe('01-03');
	});

	it('counts years ago', () => {
		expect(yearsAgo('2023-10-22T10:00:00Z', new Date(2026, 9, 22))).toBe(3);
		expect(yearsAgo(null)).toBe(0);
	});
});
