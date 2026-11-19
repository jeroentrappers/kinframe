import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, push, uid, type MockState } from './mock-api';

// Birthdays in feeds (docs/spec/birthdays.md): the family web app's side, against the in-memory mock API.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const inDays = (n: number) => {
	const d = new Date();
	d.setDate(d.getDate() + n);
	return { day: d.getDate(), month: d.getMonth() + 1 };
};

function bdayState(opts: { birthdays?: boolean; role?: S['FeedRole']; postersCanAdd?: boolean } = {}) {
	const st = emptyState();
	st.discovery.feeds = true;
	st.discovery.birthdays = opts.birthdays ?? true;
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', timezone: 'Mars/Olympus', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);
	const role = opts.role ?? 'owner';
	const feed: S['Feed'] = {
		id: uid('fd'),
		name: 'Peeters kids',
		cover_url: null,
		role,
		member_count: 2,
		subscription_count: 1,
		created_at: ago(9000),
		owner_name: 'Anna',
		posters_can_add_birthdays: opts.postersCanAdd ?? false,
		birthday_notifications: true,
		birthday_count: 2,
		pending_birthday_count: role === 'poster' ? 0 : 1
	};
	st.feeds = [feed];
	const benId = uid('u');
	st.feedMembers[feed.id] = [
		{ user_id: st.me.id, display_name: 'Anna', role, joined_at: ago(9000) },
		{ user_id: benId, display_name: 'Ben', role: 'poster', joined_at: ago(5000) }
	];
	const admin = role !== 'poster';
	const base = { feed_id: feed.id, created_at: ago(900), can_edit: admin, can_decide: false, has_own_photo: false, photo_url: null, user_id: null };
	st.birthdays[feed.id] = [
		{ ...base, id: uid('bd'), status: 'approved', display_name: 'Opa', ...inDays(3), birth_year: admin ? 1950 : null, show_age: false, created_by_name: 'Anna' },
		{ ...base, id: uid('bd'), status: 'approved', display_name: 'Oma', ...inDays(0), birth_year: 1946, show_age: true, created_by_name: 'Anna' },
		...(admin
			? [{ ...base, id: uid('bd'), status: 'pending' as const, display_name: 'Lotte', ...inDays(40), birth_year: null, show_age: false, created_by_name: 'Carla', can_decide: true }]
			: [])
	];
	st.subscriptions = [
		{
			id: uid('sb'),
			status: 'active',
			history: 'none',
			feed: { id: feed.id, name: feed.name, admin_names: ['Anna'], member_count: 2 },
			frame: { id: oma.id, name: oma.name, birthdays_supported: false },
			birthdays: true,
			birthday_count: 2,
			created_at: ago(900),
			activated_at: ago(900),
			can_manage: true
		}
	];
	return { st, oma, feed, benId };
}

async function signIn(page: Page, st: MockState) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('birthdays stay hidden when the server has none', async ({ page }) => {
	const { st, feed } = bdayState({ birthdays: false });
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}`);
	await expect(page.getByRole('tab', { name: 'Posts' })).toBeVisible();
	await expect(page.getByRole('tab', { name: /Birthdays/ })).toHaveCount(0);
	expect(st.calls.some((c) => c.method === 'PATCH' && c.path === '/v1/me' && (c.body as { timezone?: string })?.timezone)).toBe(false);
});

test('owner: list by next birthday, add, duplicate, approve a suggestion', async ({ page }) => {
	const { st, feed } = bdayState();
	await signIn(page, st);
	// The profile's zone is set from the browser.
	await expect.poll(() => st.calls.some((c) => c.method === 'PATCH' && c.path === '/v1/me' && typeof (c.body as { timezone?: string })?.timezone === 'string')).toBe(true);

	await page.goto(`/feeds/${feed.id}`);
	const tab = page.getByRole('tab', { name: /Birthdays/ });
	await expect(tab).toContainText('1');
	await tab.click();
	await expect(page).toHaveURL(/tab=birthdays/);

	const rows = page.getByTestId('birthday-row');
	await expect(rows).toHaveCount(2);
	await expect(rows.nth(0)).toContainText('Oma');
	await expect(rows.nth(0)).toContainText('today 🎈');
	await expect(rows.nth(0)).toContainText('turns 80');
	await expect(rows.nth(1)).toContainText('Opa');
	await expect(rows.nth(1)).toContainText('in 3 days');
	// The admin sees Opa's year, the frames don't show his age.
	await expect(rows.nth(1)).toContainText('1950');
	await expect(rows.nth(1).getByText('The frames don’t show the age')).toBeAttached();

	// Pending suggestion: approve.
	await expect(page.getByText('suggested by Carla')).toBeVisible();
	await page.getByRole('button', { name: 'Add to the list' }).click();
	await expect(page.getByText('Lotte is on the list now')).toBeVisible();
	await expect(rows).toHaveCount(3);

	// Add a person.
	await page.getByRole('button', { name: 'Add a birthday' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Name').fill('Zoë');
	await dialog.getByLabel('Day').selectOption('29');
	await dialog.getByLabel('Month').selectOption('2');
	await dialog.getByLabel(/Year of birth/).fill('2023');
	await expect(dialog.getByText('That date doesn’t exist.')).toBeVisible();
	await dialog.getByLabel(/Year of birth/).fill('2024');
	await dialog.getByRole('checkbox', { name: /Show age/ }).check();
	await dialog.getByRole('button', { name: 'Add a birthday' }).click();
	await expect(page.getByText('Zoë is on the list')).toBeVisible();
	const create = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/birthdays`);
	expect(create?.body).toEqual({ display_name: 'Zoë', day: 29, month: 2, birth_year: 2024, show_age: true, user_id: null });

	// Same name and date again: duplicate_birthday.
	await page.getByRole('button', { name: 'Add a birthday' }).click();
	await dialog.getByLabel('Name').fill('zoë');
	await dialog.getByLabel('Day').selectOption('29');
	await dialog.getByLabel('Month').selectOption('2');
	await dialog.getByRole('button', { name: 'Add a birthday' }).click();
	await expect(dialog.getByText('Zoë is already on the list for that day.')).toBeVisible();
});

test('poster suggests instead of adding, and doesn’t see private years', async ({ page }) => {
	const { st, feed } = bdayState({ role: 'poster' });
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=birthdays`);
	await expect(page.getByRole('button', { name: 'Add a birthday' })).toHaveCount(0);
	const rows = page.getByTestId('birthday-row');
	await expect(rows.nth(1)).toContainText('Opa');
	await expect(rows.nth(1)).not.toContainText('1950');
	await expect(rows.nth(1).getByRole('button', { name: /Edit/ })).toHaveCount(0);

	await page.getByRole('button', { name: 'Add my birthday' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByLabel('Name')).toHaveValue('Anna');
	await dialog.getByLabel('Day').selectOption('7');
	await dialog.getByRole('button', { name: 'Suggest a birthday' }).click();
	await expect(page.getByText('Suggested. An admin of the feed will check it.')).toBeVisible();
	await expect(page.getByText('Waiting for an admin')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Add my birthday' })).toHaveCount(0);
	const create = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/birthdays`);
	expect(create?.body).toMatchObject({ display_name: 'Anna', day: 7, user_id: st.me!.id });
});

test('poster with posters_can_add_birthdays adds directly', async ({ page }) => {
	const { st, feed } = bdayState({ role: 'poster', postersCanAdd: true });
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=birthdays`);
	await expect(page.getByRole('button', { name: 'Add a birthday' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Suggest a birthday' })).toHaveCount(0);
});

test('greetings arrive live; the member mutes birthday notifications', async ({ page }) => {
	const { st, feed, oma } = bdayState();
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=birthdays`);
	const omaRow = page.getByTestId('birthday-row').filter({ hasText: 'Oma' });
	await expect(omaRow).toBeVisible();
	await expect.poll(() => st.ws !== null).toBe(true);
	const d = new Date();
	const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
	const omaId = st.birthdays[feed.id]!.find((b) => b.display_name === 'Oma')!.id;
	push(st, {
		type: 'birthday.greeting',
		feed_id: feed.id,
		greeting: { id: uid('g'), feed_id: feed.id, birthday_id: omaId, birthday_name: 'Oma', frame_id: oma.id, frame_name: 'Opa’s frame', date, created_at: new Date().toISOString() }
	});
	await expect(page.getByText('🎉 Opa’s frame said happy birthday to Oma').first()).toBeVisible();
	await expect(omaRow).toContainText('Greetings today: Opa’s frame');

	await page.getByRole('checkbox', { name: /Birthday notifications/ }).click();
	await expect.poll(() => st.calls.find((c) => c.method === 'PATCH' && c.path === `/v1/feeds/${feed.id}/preferences`)?.body).toEqual({ birthday_notifications: false });
});

test('frame side: turn a subscription’s birthdays off; old frame app hint', async ({ page }) => {
	const { st, oma } = bdayState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=feeds`);
	await expect(page.getByText('Update the frame app to show birthdays.')).toBeVisible();
	await expect(page.getByText('2 birthdays')).toBeVisible();
	await page.getByRole('checkbox', { name: /Show birthdays/ }).click();
	await expect(page.getByText('Birthdays of Peeters kids no longer show on this frame')).toBeVisible();
	const patch = st.calls.find((c) => c.method === 'PATCH' && c.path.startsWith('/v1/subscriptions/'));
	expect(patch?.body).toEqual({ birthdays: false });
});
