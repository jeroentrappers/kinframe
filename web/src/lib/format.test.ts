import { describe, expect, it } from 'vitest';
import {
	captionRemaining,
	caretAfterFormat,
	dayBucket,
	formatBytes,
	formatCode,
	formatDuration,
	formatInterval,
	initials,
	isCompleteCode,
	normalizeCode,
	relativeTime,
	signalBars
} from './format';

describe('frame codes', () => {
	it('keeps only digits, max 8', () => {
		expect(normalizeCode('Code: 4821-3907 ')).toBe('48213907');
		expect(normalizeCode('123456789012')).toBe('12345678');
		expect(normalizeCode('abc')).toBe('');
	});

	it('formats as "1234 5678", including partial input', () => {
		expect(formatCode('48213907')).toBe('4821 3907');
		expect(formatCode('482')).toBe('482');
		expect(formatCode('4821')).toBe('4821');
		expect(formatCode('48213')).toBe('4821 3');
		expect(formatCode('4821 3907')).toBe('4821 3907');
	});

	it('knows when a code is complete', () => {
		expect(isCompleteCode('4821 3907')).toBe(true);
		expect(isCompleteCode('4821 390')).toBe(false);
	});

	it('keeps the caret after the same digit when reformatting', () => {
		// typed the 5th digit at the end: "48213" → "4821 3", caret after "3"
		expect(caretAfterFormat('48213', 5, '4821 3')).toBe(6);
		// caret in the middle, after 2 digits
		expect(caretAfterFormat('48x213', 3, '4821 3')).toBe(2);
		expect(caretAfterFormat('', 0, '')).toBe(0);
	});
});

describe('numbers and durations', () => {
	it('formats bytes', () => {
		expect(formatBytes(0)).toBe('0 B');
		expect(formatBytes(1536)).toBe('1.5 KB');
		expect(formatBytes(2 * 1024 ** 3)).toBe('2 GB');
		expect(formatBytes(150 * 1024 ** 2)).toBe('150 MB');
		expect(formatBytes(-1)).toBe('–');
	});

	it('formats durations', () => {
		expect(formatDuration(0)).toBe('0:00');
		expect(formatDuration(75.4)).toBe('1:15');
		expect(formatDuration(300)).toBe('5:00');
		expect(formatDuration(3725)).toBe('1:02:05');
	});

	it('formats slideshow intervals', () => {
		expect(formatInterval(30)).toBe('30 s');
		expect(formatInterval(300)).toBe('5 min');
		expect(formatInterval(86400)).toBe('24 h');
		expect(formatInterval(300, 'de')).toBe('5 Min.');
		expect(formatInterval(30, 'ar')).not.toContain('s');
	});
});

describe('relative time', () => {
	const now = new Date('2026-10-22T12:00:00Z');
	it('says "now" for very recent times', () => {
		expect(relativeTime('2026-10-22T11:59:40Z', 'en', now)).toBe('now');
	});
	it('uses minutes, hours and days', () => {
		expect(relativeTime('2026-10-22T11:55:00Z', 'en', now)).toBe('5 minutes ago');
		expect(relativeTime('2026-10-22T09:00:00Z', 'en', now)).toBe('3 hours ago');
		expect(relativeTime('2026-10-21T12:00:00Z', 'en', now)).toBe('yesterday');
		expect(relativeTime('2026-10-23T12:00:00Z', 'en', now)).toBe('tomorrow');
	});
	it('localizes', () => {
		expect(relativeTime('2026-10-22T11:55:00Z', 'nl', now)).toBe('5 minuten geleden');
	});
	it('handles missing and invalid input', () => {
		expect(relativeTime(null, 'en', now)).toBe('');
		expect(relativeTime('nope', 'en', now)).toBe('');
	});
	it('buckets days for history grouping', () => {
		const local = new Date(2026, 9, 22, 15, 0);
		expect(dayBucket(new Date(2026, 9, 22, 8, 0).toISOString(), local)).toBe('today');
		expect(dayBucket(new Date(2026, 9, 21, 23, 0).toISOString(), local)).toBe('yesterday');
		expect(dayBucket(new Date(2026, 9, 10, 12, 0).toISOString(), local)).toMatch(/^2026-10-1\d$/);
	});
});

describe('captions and names', () => {
	it('counts emoji as one character', () => {
		expect(captionRemaining('hi', 280)).toBe(278);
		expect(captionRemaining('👨‍👩‍👧', 280)).toBe(279);
		expect(captionRemaining('x'.repeat(281), 280)).toBe(-1);
	});
	it('makes initials', () => {
		expect(initials('Jeroen Trappers')).toBe('JT');
		expect(initials('oma')).toBe('O');
		expect(initials('  ')).toBe('?');
		expect(initials(undefined)).toBe('?');
	});
	it('maps RSSI to signal bars', () => {
		expect(signalBars(-50)).toBe(4);
		expect(signalBars(-70)).toBe(2);
		expect(signalBars(-90)).toBe(0);
		expect(signalBars(null)).toBe(0);
	});
});
