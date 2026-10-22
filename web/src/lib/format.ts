// Pure formatting helpers. No DOM, no state: unit-tested in format.test.ts.

export const CODE_LENGTH = 8;
export const CAPTION_MAX = 280;

/** Keep only digits, at most 8. Accepts pasted text like "Code: 4821-3907". */
export function normalizeCode(input: string): string {
	return input.replace(/\D/g, '').slice(0, CODE_LENGTH);
}

/** "48213907" → "4821 3907" (partial input stays readable: "482" → "482", "48213" → "4821 3"). */
export function formatCode(input: string): string {
	const digits = normalizeCode(input);
	return digits.length > 4 ? `${digits.slice(0, 4)} ${digits.slice(4)}` : digits;
}

export function isCompleteCode(input: string): boolean {
	return normalizeCode(input).length === CODE_LENGTH;
}

/**
 * Where the caret should land after reformatting: count the digits before the caret in the
 * raw value and find the position after that many digits in the formatted value.
 */
export function caretAfterFormat(raw: string, caret: number, formatted: string): number {
	const digitsBefore = raw.slice(0, caret).replace(/\D/g, '').length;
	if (digitsBefore === 0) return 0;
	let seen = 0;
	for (let i = 0; i < formatted.length; i++) {
		if (/\d/.test(formatted[i]!)) seen++;
		if (seen === digitsBefore) return i + 1;
	}
	return formatted.length;
}

export function formatBytes(bytes: number, locale = 'en'): string {
	if (!Number.isFinite(bytes) || bytes < 0) return '–';
	const units = ['B', 'KB', 'MB', 'GB', 'TB'];
	let i = 0;
	let v = bytes;
	while (v >= 1024 && i < units.length - 1) {
		v /= 1024;
		i++;
	}
	const digits = i === 0 || v >= 100 ? 0 : 1;
	return `${new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(v)} ${units[i]}`;
}

/** 75.4 → "1:15", 3725 → "1:02:05". */
export function formatDuration(seconds: number): string {
	if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
	const s = Math.round(seconds);
	const h = Math.floor(s / 3600);
	const m = Math.floor((s % 3600) / 60);
	const sec = s % 60;
	const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
	return `${h > 0 ? `${h}:` : ''}${mm}:${String(sec).padStart(2, '0')}`;
}

/** Interval in seconds → short label: 30 → "30 s", 300 → "5 min", 3600 → "1 h", 86400 → "24 h". */
export function formatInterval(seconds: number): string {
	if (seconds < 60) return `${seconds} s`;
	if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
	return `${Math.round(seconds / 3600)} h`;
}

const RELATIVE_STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
	['second', 60],
	['minute', 60],
	['hour', 24],
	['day', 7],
	['week', 4.345],
	['month', 12],
	['year', Infinity]
];

/** "just now", "5 minutes ago", "yesterday", … in the given locale. */
export function relativeTime(iso: string | null | undefined, locale = 'en', now: Date = new Date()): string {
	if (!iso) return '';
	const then = new Date(iso);
	if (Number.isNaN(then.getTime())) return '';
	let delta = (then.getTime() - now.getTime()) / 1000;
	if (Math.abs(delta) < 45) {
		return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(0, 'second');
	}
	const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
	for (const [unit, size] of RELATIVE_STEPS) {
		if (Math.abs(delta) < size) return rtf.format(Math.round(delta), unit);
		delta /= size;
	}
	return rtf.format(Math.round(delta), 'year');
}

export function formatDateTime(iso: string | null | undefined, locale = 'en'): string {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return '';
	return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(d);
}

/** Group label for history: "Today", "Yesterday" or a date. Returns a key or formatted date. */
export function dayBucket(iso: string, now: Date = new Date()): 'today' | 'yesterday' | string {
	const d = new Date(iso);
	const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
	const diffDays = Math.round((startOf(now) - startOf(d)) / 86_400_000);
	if (diffDays === 0) return 'today';
	if (diffDays === 1) return 'yesterday';
	return d.toISOString().slice(0, 10);
}

/** Characters left in a caption, counting user-perceived characters (emoji count as one). */
export function captionRemaining(caption: string, max = CAPTION_MAX): number {
	return max - graphemeLength(caption);
}

export function graphemeLength(text: string): number {
	if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
		let n = 0;
		for (const _ of new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) n++;
		return n;
	}
	return Array.from(text).length;
}

export function initials(name: string | null | undefined): string {
	const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
	if (parts.length === 0) return '?';
	const first = Array.from(parts[0]!)[0] ?? '';
	const last = parts.length > 1 ? (Array.from(parts[parts.length - 1]!)[0] ?? '') : '';
	return (first + last).toUpperCase();
}

/** Wi-Fi RSSI (dBm) → 0..4 bars. */
export function signalBars(rssi: number | null | undefined): number {
	if (rssi == null) return 0;
	if (rssi >= -55) return 4;
	if (rssi >= -67) return 3;
	if (rssi >= -75) return 2;
	if (rssi >= -85) return 1;
	return 0;
}

export function formatUptime(seconds: number): string {
	const d = Math.floor(seconds / 86400);
	const h = Math.floor((seconds % 86400) / 3600);
	const m = Math.floor((seconds % 3600) / 60);
	if (d > 0) return `${d}d ${h}h`;
	if (h > 0) return `${h}h ${m}m`;
	return `${m}m`;
}
