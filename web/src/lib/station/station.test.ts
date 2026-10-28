import { describe, expect, it } from 'vitest';
import {
	beaufort,
	compass,
	domain,
	hourlyRain,
	lastAt,
	niceTicks,
	pressureTrend,
	timeTicks,
	uvLevel,
	windValue,
	type StationSeries
} from './station';

const DIRS = 'N,NNE,NE,ENE,E,ESE,SE,SSE,S,SSW,SW,WSW,W,WNW,NW,NNW';

describe('station page logic', () => {
	it('names wind directions and Beaufort forces', () => {
		expect(compass(225, DIRS)).toBe('SW');
		expect(compass(359, DIRS)).toBe('N');
		expect(compass(-90, DIRS)).toBe('W');
		expect(beaufort(0.2)).toBe(0);
		expect(beaufort(5)).toBe(3);
		expect(beaufort(40)).toBe(12);
		expect(windValue(10, 'kmh')).toBe(36);
		expect(windValue(0.836, 'bft')).toBeCloseTo(1);
	});

	it('puts the UV index in its WHO category', () => {
		expect([0, 3, 6, 8, 11].map(uvLevel)).toEqual(['uvLow', 'uvModerate', 'uvHigh', 'uvVeryHigh', 'uvExtreme']);
	});

	it('reads the pressure trend over three hours, across short gaps', () => {
		const p: (number | null)[] = Array(48).fill(1010);
		p[47] = null; // the newest step had no upload
		p[46] = 1012.4;
		const s = { step_s: 300, pressure_hpa: p } as unknown as StationSeries;
		expect(pressureTrend(s)).toEqual({ change: 2.4, kind: 'rising' });
		expect(
			pressureTrend({
				step_s: 300,
				pressure_hpa: [1010, 1011]
			} as unknown as StationSeries)
		).toBeNull();
		expect(lastAt([1, null, null, null, null], 4)).toBeNull();
		expect(lastAt([1, null, null, null], 3)).toBe(1);
	});

	it('picks round axis ticks around the data', () => {
		expect(niceTicks(6.3, 11.8)).toEqual([6, 8, 10, 12]);
		expect(niceTicks(1003.2, 1011.9)).toEqual([1002.5, 1005, 1007.5, 1010, 1012.5]);
		expect(niceTicks(0, 0)[0]).toBeLessThanOrEqual(-1);
		expect(domain([[null, 2, 3]], { zero: true })).toEqual([0, 3]);
		expect(domain([[1010, 1010.5]], { minSpan: 4 })).toEqual([1008.25, 1012.25]);
		expect(domain([[null]])).toBeNull();
	});

	it('labels the time axis on the hour in the station’s zone, midnight as the day', () => {
		// 2026-10-27 22:00 UTC is 23:00 in Brussels: the next step on the hour is midnight.
		const start = Date.UTC(2026, 9, 27, 22, 0);
		const ticks = timeTicks(start, 300, 0, 12 * 7, 'Europe/Brussels', 3);
		expect(ticks[0]).toEqual({ i: 12, midnight: true });
		expect(ticks[1]).toEqual({ i: 48, midnight: false });
	});

	it('turns the running total into rain per hour, across the midnight reset', () => {
		// Starts at 22:50 Brussels (21:50 UTC): two steps before 23:00, then twelve per hour.
		const start = Date.UTC(2026, 10, 2, 21, 50);
		const today: (number | null)[] = Array(2 + 24).fill(null);
		today[0] = 1.0;
		today[1] = 1.2; // before the first full hour: not shown
		today[2] = 1.4;
		today[13] = 2.0; // 23:55
		today[14] = 0.3; // 00:00, after the reset
		today[20] = 0.5;
		const h = hourlyRain(today, start, 300, 'Europe/Brussels');
		expect(h[2]).toBe(0.8);
		expect(h[14]).toBe(0.5);
		expect(h.filter((v) => v != null)).toHaveLength(2);
	});
});
