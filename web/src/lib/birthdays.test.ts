import { describe, expect, it } from 'vitest';
import type { BirthdayPerson, FeedBirthdayGreeting } from './api/client';
import {
	addGreeting,
	addMode,
	ageDisplay,
	birthdayView,
	canRemoveBirthday,
	countdown,
	createBody,
	dateProblem,
	daysInMonth,
	editScope,
	formOf,
	greetingsFor,
	groupBirthdays,
	hasOwnEntry,
	isLeapYear,
	nextOccurrence,
	occurrenceIn,
	recreateBody,
	removeBirthday,
	sortByNextBirthday,
	timezoneUpdate,
	todayIn,
	updateBody,
	upsertBirthday,
	yearAudience,
	type BirthdayViewer
} from './birthdays';

const ME = 'u-me';
let n = 0;
function person(p: Partial<BirthdayPerson> = {}): BirthdayPerson {
	return {
		id: `b${++n}`,
		feed_id: 'f1',
		status: 'approved',
		display_name: 'Oma',
		day: 12,
		month: 3,
		birth_year: null,
		show_age: false,
		created_at: '2026-01-01T00:00:00Z',
		...p
	};
}
const d = (y: number, m: number, day: number) => ({ y, m, d: day });

describe('dates', () => {
	it('knows leap years and month lengths (29 February is pickable without a year)', () => {
		expect([2024, 2000, 2026, 1900].map(isLeapYear)).toEqual([true, true, false, false]);
		expect(daysInMonth(2)).toBe(29);
		expect(daysInMonth(2, 2027)).toBe(28);
		expect(daysInMonth(2, 2028)).toBe(29);
		expect(daysInMonth(4)).toBe(30);
		expect(daysInMonth(12)).toBe(31);
	});

	it('moves 29 February to 28 February in non-leap years', () => {
		expect(occurrenceIn({ day: 29, month: 2 }, 2027)).toEqual(d(2027, 2, 28));
		expect(occurrenceIn({ day: 29, month: 2 }, 2028)).toEqual(d(2028, 2, 29));
		expect(nextOccurrence({ day: 29, month: 2, birth_year: null }, d(2026, 11, 19))).toEqual(d(2027, 2, 28));
		// On 28 February of a non-leap year a leap-day birthday is today.
		expect(birthdayView({ day: 29, month: 2, birth_year: 2000 }, d(2027, 2, 28))).toEqual({ next: d(2027, 2, 28), days: 0, turns: 27 });
		// And on 1 March it's next year's.
		expect(nextOccurrence({ day: 29, month: 2, birth_year: null }, d(2027, 3, 1))).toEqual(d(2028, 2, 29));
	});

	it('finds the next occurrence: today counts, past dates roll over', () => {
		const oma = { day: 12, month: 3, birth_year: 1946 };
		expect(birthdayView(oma, d(2026, 3, 12))).toEqual({ next: d(2026, 3, 12), days: 0, turns: 80 });
		expect(birthdayView(oma, d(2026, 3, 9))).toEqual({ next: d(2026, 3, 12), days: 3, turns: 80 });
		expect(birthdayView(oma, d(2026, 3, 13))).toEqual({ next: d(2027, 3, 12), days: 364, turns: 81 });
		// Across New Year.
		expect(birthdayView({ day: 2, month: 1, birth_year: null }, d(2026, 12, 30))).toEqual({ next: d(2027, 1, 2), days: 3, turns: null });
	});

	it('does not count the date of birth itself', () => {
		const lotte = { day: 2, month: 6, birth_year: 2026 };
		expect(nextOccurrence(lotte, d(2026, 6, 2))).toEqual(d(2027, 6, 2));
		expect(birthdayView(lotte, d(2026, 1, 1)).turns).toBe(1);
		// Without a year we can't tell: every occurrence counts.
		expect(nextOccurrence({ ...lotte, birth_year: null }, d(2026, 6, 2))).toEqual(d(2026, 6, 2));
	});

	it('sorts by the next birthday, then by name', () => {
		const today = d(2026, 11, 19);
		const list = [
			person({ display_name: 'Opa', day: 1, month: 1 }),
			person({ display_name: 'Zoë', day: 19, month: 11 }),
			person({ display_name: 'Anna', day: 19, month: 11 }),
			person({ display_name: 'Ben', day: 18, month: 11 }),
			person({ display_name: 'Leap', day: 29, month: 2 })
		];
		expect(sortByNextBirthday(list, today).map((p) => p.display_name)).toEqual(['Anna', 'Zoë', 'Opa', 'Leap', 'Ben']);
	});

	it('words the countdown in days, then months', () => {
		expect(countdown(0)).toEqual({ unit: 'day', value: 0 });
		expect(countdown(3)).toEqual({ unit: 'day', value: 3 });
		expect(countdown(44)).toEqual({ unit: 'day', value: 44 });
		expect(countdown(45)).toEqual({ unit: 'month', value: 2 });
		expect(countdown(364)).toEqual({ unit: 'month', value: 12 });
	});

	it('rejects impossible dates and years', () => {
		const today = d(2026, 11, 19);
		expect(dateProblem(29, 2, null, today)).toBeNull();
		expect(dateProblem(29, 2, 2024, today)).toBeNull();
		expect(dateProblem(29, 2, 2023, today)).toBe('day');
		expect(dateProblem(30, 2, null, today)).toBe('day');
		expect(dateProblem(31, 4, null, today)).toBe('day');
		expect(dateProblem(1, 1, 1899, today)).toBe('birth_year');
		expect(dateProblem(1, 1, 2027, today)).toBe('birth_year');
		expect(dateProblem(1, 1, 1900, today)).toBeNull();
	});

	it('computes today in a time zone', () => {
		const now = new Date('2026-03-11T23:30:00Z');
		expect(todayIn('Europe/Brussels', now)).toEqual(d(2026, 3, 12));
		expect(todayIn('America/Toronto', now)).toEqual(d(2026, 3, 11));
	});
});

describe('age display', () => {
	const occ = d(2026, 3, 12);
	it('shows the age only with a known year; marks it private when the frames hide it', () => {
		expect(ageDisplay({ birth_year: null, show_age: true }, occ)).toEqual({ kind: 'none' });
		expect(ageDisplay({ birth_year: 1946, show_age: true }, occ)).toEqual({ kind: 'shown', age: 80 });
		expect(ageDisplay({ birth_year: 1946, show_age: false }, occ)).toEqual({ kind: 'private', age: 80 });
	});

	it('says who sees the year', () => {
		expect(yearAudience(true)).toBe('everyone');
		expect(yearAudience(false)).toBe('limited');
	});
});

describe('permissions', () => {
	const viewer = (v: Partial<BirthdayViewer> = {}): BirthdayViewer => ({ role: 'poster', userId: ME, postersCanAdd: false, ...v });

	it('adds directly as owner/admin or with posters_can_add_birthdays, else suggests', () => {
		expect(addMode({ role: 'owner' })).toBe('add');
		expect(addMode({ role: 'admin' })).toBe('add');
		expect(addMode({ role: 'poster' })).toBe('suggest');
		expect(addMode({ role: 'poster', posters_can_add_birthdays: false })).toBe('suggest');
		expect(addMode({ role: 'poster', posters_can_add_birthdays: true })).toBe('add');
	});

	it('limits the linked member to the privacy fields of their own entry', () => {
		const own = person({ user_id: ME, can_edit: true, created_by_id: 'u-anna', created_by_name: 'Anna' });
		expect(editScope(own, viewer())).toBe('privacy');
		expect(editScope(own, viewer({ role: 'admin' }))).toBe('all');
		// Added by me with the setting on: everything.
		expect(editScope({ ...own, created_by_id: ME }, viewer({ postersCanAdd: true }))).toBe('all');
		// Added by another account: still only the privacy fields.
		expect(editScope({ ...own, created_by_id: 'u-other-ben' }, viewer({ postersCanAdd: true }))).toBe('privacy');
		// Creator account gone (null): not "added by me".
		expect(editScope({ ...own, created_by_id: null }, viewer({ postersCanAdd: true }))).toBe('privacy');
		// Own pending suggestion: everything.
		expect(editScope({ ...own, status: 'pending' }, viewer())).toBe('all');
		// Someone else's entry the server says we can't edit.
		expect(editScope(person({ can_edit: false }), viewer())).toBe('none');
		expect(editScope(person({ can_edit: true, created_by_id: ME }), viewer({ postersCanAdd: true }))).toBe('all');
	});

	it('lets the linked member, admins and editors remove', () => {
		expect(canRemoveBirthday(person({ user_id: ME, can_edit: false }), viewer())).toBe(true);
		expect(canRemoveBirthday(person({ can_edit: false }), viewer())).toBe(false);
		expect(canRemoveBirthday(person({ can_edit: false }), viewer({ role: 'owner' }))).toBe(true);
	});

	it('groups suggestions, the list and declined ones', () => {
		const today = d(2026, 3, 1);
		const g = groupBirthdays(
			[person({ status: 'pending', display_name: 'Lotte' }), person({ display_name: 'Oma' }), person({ status: 'rejected', display_name: 'X' })],
			today
		);
		expect(g.pending.map((p) => p.display_name)).toEqual(['Lotte']);
		expect(g.approved.map((p) => p.display_name)).toEqual(['Oma']);
		expect(g.rejected.map((p) => p.display_name)).toEqual(['X']);
	});

	it('hides "Add my birthday" once the member has a live entry', () => {
		expect(hasOwnEntry([person({ user_id: ME })], ME)).toBe(true);
		expect(hasOwnEntry([person({ user_id: ME, status: 'rejected' })], ME)).toBe(false);
		expect(hasOwnEntry([person()], ME)).toBe(false);
		expect(hasOwnEntry([person({ user_id: ME })], undefined)).toBe(false);
	});
});

describe('request bodies', () => {
	it('creates with the year optional and show_age only with a year', () => {
		expect(createBody({ display_name: '  Oma ', day: 12, month: 3, year: '1946', show_age: true, user_id: null })).toEqual({
			display_name: 'Oma',
			day: 12,
			month: 3,
			birth_year: 1946,
			show_age: true,
			user_id: null
		});
		expect(createBody({ display_name: 'Lotte', day: 2, month: 6, year: '', show_age: true, user_id: 'u2' })).toMatchObject({ birth_year: null, show_age: false, user_id: 'u2' });
	});

	it('patches only what changed, and only privacy fields for the linked member', () => {
		const p = person({ birth_year: 1990, show_age: true, user_id: ME });
		const base = formOf(p);
		expect(updateBody({ ...base }, base, 'all')).toEqual({});
		expect(updateBody({ ...base, display_name: 'Ben', year: '' }, base, 'all')).toEqual({ display_name: 'Ben', birth_year: null, show_age: false });
		// The linked member can't rename, but can drop the year and unlink.
		expect(updateBody({ ...base, display_name: 'Other', year: '', user_id: null }, base, 'privacy')).toEqual({ user_id: null, birth_year: null, show_age: false });
		expect(updateBody({ ...base, day: 1 }, base, 'privacy')).toEqual({});
	});

	it('recreates a removed person for Undo', () => {
		const p = person({ display_name: 'Oma', birth_year: 1946, show_age: true, user_id: 'u9', photo_url: 'https://x/y.webp' });
		expect(recreateBody(p)).toEqual({ display_name: 'Oma', day: 12, month: 3, birth_year: 1946, show_age: true, user_id: 'u9' });
	});
});

describe('live updates and greetings', () => {
	it('upserts and removes people', () => {
		const a = person();
		const list = upsertBirthday([], a);
		expect(list).toHaveLength(1);
		expect(upsertBirthday(list, { ...a, display_name: 'Omi' })[0]!.display_name).toBe('Omi');
		expect(removeBirthday(list, a.id)).toEqual([]);
	});

	it('lists today’s greetings per person, oldest first, without duplicates', () => {
		const g = (id: string, frame: string, at: string, birthday_id = 'b1', date = '2026-03-12'): FeedBirthdayGreeting => ({
			id,
			feed_id: 'f1',
			birthday_id,
			birthday_name: 'Oma',
			frame_id: frame,
			frame_name: frame,
			date,
			created_at: at
		});
		let list = addGreeting([], g('g2', 'Kitchen', '2026-03-12T11:02:00Z'));
		list = addGreeting(list, g('g1', 'Opa’s frame', '2026-03-12T10:14:00Z'));
		list = addGreeting(list, g('g1', 'Opa’s frame', '2026-03-12T10:14:00Z'));
		list = addGreeting(list, g('g3', 'Hall', '2025-03-12T10:00:00Z', 'b1', '2025-03-12'));
		expect(list.map((x) => x.id)).toEqual(['g2', 'g1', 'g3']);
		expect(greetingsFor(list, 'b1', '2026-03-12').map((x) => x.frame_name)).toEqual(['Opa’s frame', 'Kitchen']);
		expect(greetingsFor(list, 'b2', '2026-03-12')).toEqual([]);
	});
});

describe('User.timezone', () => {
	it('sends the browser zone only when it differs', () => {
		expect(timezoneUpdate({ timezone: null }, 'Europe/Brussels')).toBe('Europe/Brussels');
		expect(timezoneUpdate({ timezone: 'Europe/Brussels' }, 'Europe/Brussels')).toBeNull();
		expect(timezoneUpdate({ timezone: 'Europe/Brussels' }, 'America/Toronto')).toBe('America/Toronto');
		expect(timezoneUpdate(null, 'Europe/Brussels')).toBeNull();
		expect(timezoneUpdate({ timezone: null }, null)).toBeNull();
	});
});
