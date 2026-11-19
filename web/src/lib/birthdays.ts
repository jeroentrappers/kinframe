// Birthdays in feeds (docs/spec/birthdays.md): date maths, sorting, permissions and request bodies. Pure:
// unit-tested in birthdays.test.ts.

import type {
	BirthdayPerson,
	BirthdayPersonCreate,
	BirthdayPersonUpdate,
	FeedBirthdayGreeting,
	FeedRole,
	User
} from './api/client';
import { isFeedAdmin } from './feeds';

// ---------------------------------------------------------------- dates (§2, §6.2)

/** A calendar date without a time zone. `m` is 1–12. */
export interface LocalDate {
	y: number;
	m: number;
	d: number;
}

export const MIN_BIRTH_YEAR = 1900;
export const NAME_MAX = 40;

export function isLeapYear(y: number): boolean {
	return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

/** Days in a month; without a year February has 29 (a birthday may be on 29 February). */
export function daysInMonth(month: number, year?: number | null): number {
	if (month === 2) return year == null || isLeapYear(year) ? 29 : 28;
	return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/** Today in a time zone (the browser's when omitted), as a calendar date. */
export function todayIn(timeZone?: string, now: Date = new Date()): LocalDate {
	try {
		const parts = new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: 'numeric', day: 'numeric' }).formatToParts(now);
		const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
		const y = get('year');
		const m = get('month');
		const d = get('day');
		if (y && m && d) return { y, m, d };
	} catch {
		/* unknown zone: fall through */
	}
	return { y: now.getFullYear(), m: now.getMonth() + 1, d: now.getDate() };
}

export function parseLocalDate(s: string): LocalDate | null {
	const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
	return m ? { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) } : null;
}

export function formatLocalDate(d: LocalDate): string {
	return `${String(d.y).padStart(4, '0')}-${String(d.m).padStart(2, '0')}-${String(d.d).padStart(2, '0')}`;
}

const dayNumber = (d: LocalDate) => Date.UTC(d.y, d.m - 1, d.d) / 86_400_000;

export function daysBetween(from: LocalDate, to: LocalDate): number {
	return Math.round(dayNumber(to) - dayNumber(from));
}

/** The birthday in a given year: 29 February falls on 28 February in non-leap years. */
export function occurrenceIn(p: Pick<BirthdayPerson, 'day' | 'month'>, year: number): LocalDate {
	const d = p.month === 2 && p.day === 29 && !isLeapYear(year) ? 28 : p.day;
	return { y: year, m: p.month, d };
}

/**
 * The next occurrence on or after `today`. With a known year, the date of birth itself is not a birthday: the first
 * one is a year later (§10).
 */
export function nextOccurrence(p: Pick<BirthdayPerson, 'day' | 'month' | 'birth_year'>, today: LocalDate): LocalDate {
	let year = today.y;
	if (p.birth_year != null && year <= p.birth_year) year = p.birth_year + 1;
	let occ = occurrenceIn(p, year);
	if (daysBetween(today, occ) < 0) occ = occurrenceIn(p, year + 1);
	return occ;
}

/** Age on an occurrence, or null without a year. */
export function ageOn(p: Pick<BirthdayPerson, 'birth_year'>, occ: LocalDate): number | null {
	return p.birth_year == null ? null : occ.y - p.birth_year;
}

export interface BirthdayView {
	next: LocalDate;
	/** 0 = today. */
	days: number;
	/** Age on `next` when the viewer knows the year (null otherwise). */
	turns: number | null;
}

export function birthdayView(p: Pick<BirthdayPerson, 'day' | 'month' | 'birth_year'>, today: LocalDate): BirthdayView {
	const next = nextOccurrence(p, today);
	return { next, days: daysBetween(today, next), turns: ageOn(p, next) };
}

/** By the next birthday, nearest first (today first), then by name. */
export function sortByNextBirthday<T extends Pick<BirthdayPerson, 'day' | 'month' | 'birth_year' | 'display_name'>>(list: T[], today: LocalDate): T[] {
	return list
		.map((p) => ({ p, days: birthdayView(p, today).days }))
		.sort((a, b) => a.days - b.days || a.p.display_name.localeCompare(b.p.display_name))
		.map((x) => x.p);
}

/**
 * What the list shows about the age: `shown` (the frame shows it too), `private` (the viewer knows the year but the
 * frame won't show the age) or `none` (no year known to the viewer).
 */
export type AgeDisplay = { kind: 'shown' | 'private'; age: number } | { kind: 'none' };

export function ageDisplay(p: Pick<BirthdayPerson, 'birth_year' | 'show_age'>, occ: LocalDate): AgeDisplay {
	const age = ageOn(p, occ);
	if (age == null || age < 1) return { kind: 'none' };
	return { kind: p.show_age ? 'shown' : 'private', age };
}

/** Countdown wording: today, tomorrow, in N days, or in N months beyond about six weeks. */
export function countdown(days: number): { unit: 'day' | 'month'; value: number } {
	if (days < 45) return { unit: 'day', value: days };
	return { unit: 'month', value: Math.max(2, Math.round(days / 30.44)) };
}

/** Why a date is impossible: `day` (31 April, 29 February with a non-leap year) or `birth_year`, else null. */
export function dateProblem(day: number, month: number, year: number | null, today: LocalDate): 'day' | 'birth_year' | null {
	if (year != null && (!Number.isInteger(year) || year < MIN_BIRTH_YEAR || year > today.y)) return 'birth_year';
	if (!Number.isInteger(month) || month < 1 || month > 12) return 'day';
	if (!Number.isInteger(day) || day < 1 || day > daysInMonth(month, year)) return 'day';
	return null;
}

// ---------------------------------------------------------------- permissions (§4.1)

export interface BirthdayViewer {
	role: FeedRole;
	userId: string | undefined;
	postersCanAdd: boolean;
}

/** Owner, admins and (with `posters_can_add_birthdays`) posters add directly; other posters suggest. */
export function addMode(feed: { role: FeedRole; posters_can_add_birthdays?: boolean }): 'add' | 'suggest' {
	return isFeedAdmin(feed.role) || feed.posters_can_add_birthdays === true ? 'add' : 'suggest';
}

/**
 * Which fields the viewer may change. The linked member of an approved entry (who neither manages the feed nor
 * added it) only controls the privacy fields: year, show age, photo, unlinking. The server has the last word.
 */
export function editScope(p: Pick<BirthdayPerson, 'can_edit' | 'user_id' | 'status' | 'created_by_id'>, v: BirthdayViewer): 'all' | 'privacy' | 'none' {
	if (isFeedAdmin(v.role)) return 'all';
	if (!p.can_edit) return 'none';
	if (p.status === 'pending') return 'all';
	const mine = v.userId !== undefined && p.user_id === v.userId;
	const addedByMe = v.postersCanAdd && v.userId !== undefined && p.created_by_id === v.userId;
	return mine && !addedByMe ? 'privacy' : 'all';
}

export function canRemoveBirthday(p: Pick<BirthdayPerson, 'can_edit' | 'user_id'>, v: BirthdayViewer): boolean {
	return isFeedAdmin(v.role) || p.can_edit === true || (v.userId !== undefined && p.user_id === v.userId);
}

/**
 * Who sees the year in the apps (§9): everyone in the feed with `show_age`, otherwise only admins, whoever added the
 * entry and the linked member. The editor says so next to the year.
 */
export function yearAudience(showAge: boolean): 'everyone' | 'limited' {
	return showAge ? 'everyone' : 'limited';
}

/** Split for the screen: suggestions waiting, the list, and declined suggestions. */
export function groupBirthdays<T extends Pick<BirthdayPerson, 'status' | 'day' | 'month' | 'birth_year' | 'display_name'>>(
	list: T[],
	today: LocalDate
): { pending: T[]; approved: T[]; rejected: T[] } {
	return {
		pending: sortByNextBirthday(
			list.filter((p) => p.status === 'pending'),
			today
		),
		approved: sortByNextBirthday(
			list.filter((p) => p.status === 'approved'),
			today
		),
		rejected: list.filter((p) => p.status === 'rejected')
	};
}

/** Whether the viewer has an entry already (hides "Add my birthday"). */
export function hasOwnEntry(list: Pick<BirthdayPerson, 'user_id' | 'status'>[], userId: string | undefined): boolean {
	return userId !== undefined && list.some((p) => p.user_id === userId && p.status !== 'rejected');
}

// ---------------------------------------------------------------- forms and bodies

export interface BirthdayForm {
	display_name: string;
	day: number;
	month: number;
	/** '' = unknown. */
	year: string;
	show_age: boolean;
	user_id: string | null;
}

export function emptyBirthdayForm(): BirthdayForm {
	return { display_name: '', day: 1, month: 1, year: '', show_age: false, user_id: null };
}

export function formOf(p: BirthdayPerson): BirthdayForm {
	return {
		display_name: p.display_name,
		day: p.day,
		month: p.month,
		year: p.birth_year == null ? '' : String(p.birth_year),
		show_age: p.show_age,
		user_id: p.user_id ?? null
	};
}

export function formYear(f: Pick<BirthdayForm, 'year'>): number | null {
	const s = f.year.trim();
	if (!s) return null;
	const n = Number(s);
	return Number.isFinite(n) ? Math.trunc(n) : NaN;
}

export function createBody(f: BirthdayForm): BirthdayPersonCreate {
	const year = formYear(f);
	return {
		display_name: f.display_name.trim().slice(0, NAME_MAX),
		day: f.day,
		month: f.month,
		birth_year: year,
		// The age needs a year.
		show_age: year != null && f.show_age,
		user_id: f.user_id
	};
}

/** Only what changed (and only the fields the scope allows), so a PATCH never undoes someone else's edit. */
export function updateBody(f: BirthdayForm, base: BirthdayForm, scope: 'all' | 'privacy'): BirthdayPersonUpdate {
	const next = createBody(f);
	const prev = createBody(base);
	const out: BirthdayPersonUpdate = {};
	if (scope === 'all') {
		if (next.display_name !== prev.display_name) out.display_name = next.display_name;
		if (next.day !== prev.day) out.day = next.day;
		if (next.month !== prev.month) out.month = next.month;
		if (next.user_id !== prev.user_id) out.user_id = next.user_id;
	} else if (next.user_id === null && prev.user_id !== null) {
		out.user_id = null; // the linked member may only unlink
	}
	if (next.birth_year !== prev.birth_year) out.birth_year = next.birth_year;
	if (next.show_age !== prev.show_age) out.show_age = next.show_age;
	return out;
}

/** Undo after a removal: the same person again (the photo is re-uploaded separately). */
export function recreateBody(p: BirthdayPerson): BirthdayPersonCreate {
	return createBody(formOf(p));
}

// ---------------------------------------------------------------- live updates (README §6.2)

/** Apply `birthday.updated` / `.suggested` / `.decided` to a list. Returns a new array. */
export function upsertBirthday(list: BirthdayPerson[], b: BirthdayPerson): BirthdayPerson[] {
	return list.some((x) => x.id === b.id) ? list.map((x) => (x.id === b.id ? b : x)) : [...list, b];
}

export function removeBirthday(list: BirthdayPerson[], id: string): BirthdayPerson[] {
	return list.filter((x) => x.id !== id);
}

/** Greetings for one person on one date, oldest first ("Opa's frame 10:14 · Kitchen 11:02"). */
export function greetingsFor(greetings: FeedBirthdayGreeting[], birthdayId: string, date: string): FeedBirthdayGreeting[] {
	return greetings.filter((g) => g.birthday_id === birthdayId && g.date === date).sort((a, b) => a.created_at.localeCompare(b.created_at));
}

/** Add a greeting from the websocket, newest first, without duplicates. */
export function addGreeting(greetings: FeedBirthdayGreeting[], g: FeedBirthdayGreeting): FeedBirthdayGreeting[] {
	if (greetings.some((x) => x.id === g.id)) return greetings;
	return [g, ...greetings].sort((a, b) => b.created_at.localeCompare(a.created_at));
}

// ---------------------------------------------------------------- User.timezone (§8.1)

/** The browser's IANA time zone, or null when it can't tell. */
export function browserTimeZone(): string | null {
	try {
		return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
	} catch {
		return null;
	}
}

/** The `timezone` to send with PATCH /v1/me, or null when the profile already has it. */
export function timezoneUpdate(me: Pick<User, 'timezone'> | null, zone: string | null): string | null {
	if (!me || !zone) return null;
	return me.timezone === zone ? null : zone;
}
