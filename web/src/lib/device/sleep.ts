// Sleep schedules (product spec 4.7) for the browser frame, with the Android frame's
// rules: a rule's day is the day its sleep starts, and a wake time at or before the
// sleep time means the next morning. Times are in the frame's time zone.

export interface SleepRule {
	sleep_at: string;
	wake_at: string;
	days: string[];
	enabled: boolean;
	mode?: 'off' | 'dim';
}

const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

function minutes(hhmm: string): number | null {
	const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm);
	if (!m) return null;
	return (Number(m[1]) % 24) * 60 + Number(m[2]);
}

/** Weekday (0 = Sunday) and minute of the day of `at` in `timeZone` (the browser's own when invalid). */
export function localTime(at: Date, timeZone?: string): { day: number; minute: number } {
	let parts: Intl.DateTimeFormatPart[];
	try {
		parts = new Intl.DateTimeFormat('en-US', { timeZone, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
	} catch {
		parts = new Intl.DateTimeFormat('en-US', { weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
	}
	const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
	const day = DAYS.indexOf(get('weekday').slice(0, 3).toLowerCase());
	return { day: day < 0 ? 0 : day, minute: (Number(get('hour')) % 24) * 60 + Number(get('minute')) };
}

/** 'off' or 'dim' while a sleep rule is active at `at`, else null (dim only when every active rule dims). */
export function sleepState(rules: SleepRule[] | undefined, at: Date, timeZone?: string): 'off' | 'dim' | null {
	if (!rules?.length) return null;
	const { day, minute } = localTime(at, timeZone);
	let state: 'off' | 'dim' | null = null;
	for (const r of rules) {
		if (!r.enabled) continue;
		const s = minutes(r.sleep_at);
		const w = minutes(r.wake_at);
		if (s === null || w === null || s === w) continue;
		const today = r.days.includes(DAYS[day]!);
		const yesterday = r.days.includes(DAYS[(day + 6) % 7]!);
		const active = w > s ? today && minute >= s && minute < w : (today && minute >= s) || (yesterday && minute < w);
		if (!active) continue;
		if (r.mode !== 'dim') return 'off';
		state = 'dim';
	}
	return state;
}
