import { expect, test, type Page } from '@playwright/test';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, push, sampleCalendar, sampleSources, uid, type MockState } from './mock-api';

const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function calState(opts: { sources?: boolean; role?: 'owner' | 'member' } = {}): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const frame =
		opts.role === 'member'
			? makeFrame({ name: 'Oma’s frame', role: 'member', permissions: { send: true, invite: false, see_library: true, manage_library: false, change_settings: false } })
			: makeFrame({ name: 'Oma’s frame' });
	st.frames = [frame];
	st.settings[frame.id] = defaultSettings(2);
	if (opts.sources) {
		st.calendars[frame.id] = sampleSources();
		st.calendarViews[frame.id] = sampleCalendar(st.calendars[frame.id]!);
	}
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('add an ICS link: test the connection, pick a colour, it syncs', async ({ page }) => {
	const st = calState();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}`);
	await page.getByRole('tab', { name: 'Calendar' }).click();
	await expect(page.getByText('No calendars yet.')).toBeVisible();

	await page.getByRole('button', { name: 'Add calendar' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Calendar address').fill('not a url');
	await dialog.getByLabel('Name on the frame').click();
	await expect(dialog.getByText('That doesn’t look like a web address.', { exact: false })).toBeVisible();

	await dialog.getByLabel('Calendar address').fill('webcal://calendar.google.com/calendar/ical/secret/basic.ics');
	await dialog.getByRole('button', { name: 'Test connection' }).click();
	await expect(dialog.getByText('Connected to “Family”. 12 events in the next 30 days.')).toBeVisible();
	await expect(dialog.getByLabel('Name on the frame')).toHaveValue('Family');
	await dialog.getByRole('radio', { name: 'Colour ocean' }).check();
	await dialog.getByRole('button', { name: 'Add to frame' }).click();

	await expect(page.getByText('Calendar added.', { exact: false })).toBeVisible();
	const post = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/calendars'))!;
	expect(post.body).toMatchObject({ kind: 'ics', url: 'webcal://calendar.google.com/calendar/ical/secret/basic.ics', name: 'Family', color: 'ocean' });
	// "Syncing…" turns into "Up to date" on the next poll.
	await expect(page.getByText(/Synced .*6 events/)).toBeVisible({ timeout: 10_000 });
});

test('add a CalDAV account with two calendars; a failed test explains itself', async ({ page }) => {
	const st = calState();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);
	await page.getByRole('button', { name: 'Add calendar' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('radio', { name: /CalDAV account/ }).check();
	await dialog.getByLabel('Server address').fill('https://cloud.example.org');
	await dialog.getByLabel('Username').fill('oma');
	await dialog.getByLabel('App password', { exact: true }).fill('wrong');
	await dialog.getByRole('button', { name: 'Test connection' }).click();
	await expect(dialog.getByText('The server didn’t accept the username or password.')).toBeVisible();

	await dialog.getByLabel('App password', { exact: true }).fill('abcd-efgh-ijkl');
	await dialog.getByRole('button', { name: 'Test connection' }).click();
	await expect(dialog.getByText('Which calendars?')).toBeVisible();
	await dialog.getByRole('checkbox', { name: 'Birthdays' }).check();
	await dialog.getByRole('button', { name: 'Add to frame' }).click();
	await expect(page.getByText('2 calendars added.', { exact: false })).toBeVisible();

	const posts = st.calls.filter((c) => c.method === 'POST' && c.path.endsWith('/calendars'));
	expect(posts).toHaveLength(2);
	expect(posts.map((p) => (p.body as { name: string }).name)).toEqual(['Personal', 'Birthdays']);
	expect(posts.map((p) => (p.body as { color: string }).color)).toEqual(['coral', 'tangerine']);
	expect(posts[0]!.body).toMatchObject({ kind: 'caldav', username: 'oma', password: 'abcd-efgh-ijkl' });
	expect((posts[1]!.body as { calendar_url: string }).calendar_url).toContain('/birthdays/');
});

test('manage sources: status, sync now, turn off, rename, remove', async ({ page }) => {
	const st = calState({ sources: true });
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);

	await expect(page.getByText('Received events', { exact: true })).toBeVisible();
	await expect(page.getByText('HTTP 404 Not Found')).toBeVisible();
	await page.getByRole('button', { name: 'Sync now: Football club' }).click();
	await expect(page.getByText('Syncing Football club…')).toBeVisible();

	await page.getByRole('checkbox', { name: 'Show on the frame: Family' }).uncheck();
	expect(st.calls.some((c) => c.method === 'PATCH' && (c.body as { enabled?: boolean })?.enabled === false)).toBe(true);

	await page.getByRole('button', { name: 'Edit Oma’s appointments' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByLabel('App password', { exact: true })).toHaveAttribute('placeholder', 'Leave empty to keep the saved password');
	await dialog.getByLabel('Name on the frame').fill('Oma');
	await dialog.getByRole('button', { name: 'Save' }).click();
	await expect(page.getByRole('heading', { name: 'Calendars' }).locator('..').locator('..').getByText('Oma', { exact: true })).toBeVisible();
	const patch = st.calls.filter((c) => c.method === 'PATCH').at(-1)!;
	expect(patch.body).toEqual({ name: 'Oma', color: 'amber', enabled: true });

	await page.getByRole('button', { name: 'Remove: Received events' }).click();
	await expect(page.getByText('All events that were sent to this frame and accepted are deleted.', { exact: false })).toBeVisible();
	await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
	await expect(page.getByText('Received events removed')).toBeVisible();
});

test('preview: merged events with weather per day, month view, countdowns', async ({ page }) => {
	const st = calState({ sources: true });
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);

	const preview = page.locator('.preview-card');
	await expect(preview.getByRole('button', { name: /Doctor/ })).toBeVisible();
	await expect(preview.getByRole('button', { name: /Swimming lesson/ }).first()).toBeVisible();
	await expect(preview.getByText('80% rain')).toBeVisible();
	await expect(preview.getByRole('img', { name: 'Rain' }).first()).toBeVisible();
	// Countdowns strip.
	await expect(preview.getByRole('list', { name: 'Countdowns' }).getByText('Lisa’s birthday')).toBeVisible();

	// Mark a countdown from the event details.
	await preview.getByRole('button', { name: /Hairdresser/ }).click();
	const dialog = page.getByRole('dialog', { name: 'Hairdresser' });
	await dialog.getByLabel('Count down on the frame').check();
	await expect(page.getByText('Countdown added')).toBeVisible();
	expect(st.calls.some((c) => c.method === 'PUT' && c.path.includes('/countdowns/'))).toBe(true);
	await page.keyboard.press('Escape');
	await expect(preview.getByRole('list', { name: 'Countdowns' }).getByText('Hairdresser')).toBeVisible();

	// Month view.
	await preview.getByRole('button', { name: 'Month' }).click();
	await expect(preview.getByRole('grid')).toBeVisible();
	await expect(preview.getByRole('columnheader').first()).toHaveText('Mon');
});

test('send an event with a photo; the frame accepts it live; withdraw another', async ({ page }) => {
	const st = calState({ sources: true });
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);
	await expect(page.getByText('No events sent to this frame yet.')).toBeVisible();

	await page.getByRole('button', { name: 'Send an event' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('button', { name: 'Send' }).click();
	await expect(dialog.getByText('Give the event a name.')).toBeVisible();
	await dialog.getByLabel('What').fill('Sunday lunch');
	await dialog.getByLabel('All day').check();
	await dialog.getByLabel('From').fill('2026-12-24');
	await dialog.getByLabel('Until').fill('2026-12-26');
	await dialog.getByLabel('Where').fill('At ours');
	await dialog.locator('input[type=file]').setInputFiles({ name: 'table.png', mimeType: 'image/png', buffer: await gradientPng(320, 200, 20) });
	await dialog.getByText('Behind').click();
	await expect(dialog.getByRole('complementary', { name: 'On the frame' }).getByText('Sunday lunch')).toBeVisible();
	await dialog.getByRole('button', { name: 'Send' }).click();
	await expect(page.getByText('Sent to Oma’s frame.', { exact: false })).toBeVisible();

	const post = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/events'))!;
	expect(post.body).toMatchObject({ title: 'Sunday lunch', all_day: true, start_date: '2026-12-24', end_date: '2026-12-26', location: 'At ours', photo_placement: 'background' });
	expect((post.body as { photo_upload_id?: string }).photo_upload_id).toMatch(/^up_/);
	await expect(page.getByText('Waiting')).toBeVisible();

	// The frame accepts: live update over the user websocket.
	const inv = st.eventInvitations[frame.id]![0]!;
	push(st, { type: 'event_invitation.decided', frame_id: frame.id, invitation: { ...inv, status: 'accepted', decided_at: new Date().toISOString() } });
	await expect(page.getByText('Oma’s frame accepted “Sunday lunch”')).toBeVisible();
	await expect(page.locator('.invs').getByText('Accepted')).toBeVisible();

	// A second one, withdrawn while pending.
	await page.getByRole('button', { name: 'Send an event' }).click();
	await page.getByRole('dialog').getByLabel('What').fill('Coffee');
	await page.getByRole('dialog').getByRole('button', { name: 'Send' }).click();
	await page.getByRole('button', { name: 'Withdraw' }).click();
	await expect(page.getByText('Invitation withdrawn')).toBeVisible();
	await expect(page.locator('.invs').getByText('Withdrawn')).toBeVisible();
});

test('calendar settings and timed view schedules', async ({ page }) => {
	const st = calState({ sources: true });
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);

	await page.getByRole('radio', { name: 'Month' }).check({ force: true });
	await page.getByLabel('Calendar beside the photos').check();
	await page.getByLabel('Show week numbers').check();
	await page.getByRole('button', { name: 'Add time' }).click();
	await expect(page.getByLabel('From', { exact: true })).toHaveValue('07:00');
	await page.getByLabel('Until', { exact: true }).fill('09:30');
	await page.getByRole('group', { name: 'Days' }).getByRole('button', { name: 'Sun' }).click();
	await page.getByRole('button', { name: 'Save', exact: true }).click();
	await expect(page.getByText('Calendar settings saved')).toBeVisible();

	const patch = st.calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/settings'))!;
	const body = patch.body as { calendar: Record<string, unknown>; widgets: Record<string, unknown> };
	expect(body.widgets).toEqual({ calendar_sidebar: true });
	expect(body.calendar.view).toBe('month');
	expect(body.calendar.week_numbers).toBe(true);
	expect(body.calendar.timed_views).toEqual([
		expect.objectContaining({ from: '07:00', to: '09:30', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat'], enabled: true })
	]);
});

test('members who can only send see just the send section', async ({ page }) => {
	const st = calState({ role: 'member' });
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=calendar`);
	await expect(page.getByRole('button', { name: 'Send an event' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Add calendar' })).toHaveCount(0);
	await expect(page.getByRole('heading', { name: 'What the frame shows' })).toHaveCount(0);
});

test('the tab is hidden when the server has calendars off', async ({ page }) => {
	const st = calState();
	st.discovery.calendar = false;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${st.frames[0]!.id}`);
	await expect(page.getByRole('tab', { name: 'Photos' })).toBeVisible();
	await expect(page.getByRole('tab', { name: 'Calendar' })).toHaveCount(0);
});
