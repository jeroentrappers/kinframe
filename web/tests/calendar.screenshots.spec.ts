// Visual review of the Calendars tab: `npx playwright test --project=screenshots calendar`.
// Writes PNGs to $SCREENSHOT_DIR (default ./screenshots).
import { test, type Page } from '@playwright/test';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, sampleCalendar, sampleSources, uid, type MockState } from './mock-api';

const DIR = process.env.SCREENSHOT_DIR ?? 'screenshots';
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function state(): MockState {
	const st = emptyState();
	st.discovery.name = 'Trappers family';
	st.me = { id: uid('u'), display_name: 'Jeroen Trappers', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(99999) };
	const oma = makeFrame({ name: 'Oma’s frame', location: 'Living room', member_count: 4, placement_count: 24 });
	st.frames = [oma];
	st.settings[oma.id] = { ...defaultSettings(4), calendar: { view: 'week', week_numbers: true, timed_views: [{ id: uid('t'), from: '07:00', to: '09:00', days: ['mon', 'tue', 'wed', 'thu', 'fri'], enabled: true }] } };
	st.calendars[oma.id] = sampleSources();
	st.calendarViews[oma.id] = sampleCalendar(st.calendars[oma.id]!);
	const lisa = uid('u');
	const day = (n: number) => {
		const d = new Date();
		d.setDate(d.getDate() + n);
		return d.toISOString().slice(0, 10);
	};
	st.eventInvitations[oma.id] = [
		{ id: uid('v'), frame_id: oma.id, sender_id: st.me.id, sender_name: 'Jeroen', status: 'pending', title: 'Grandparents’ day at school', all_day: true, start_date: day(6), end_date: day(6), has_photo: false, created_at: ago(30) },
		{ id: uid('v'), frame_id: oma.id, sender_id: lisa, sender_name: 'Lisa', status: 'accepted', title: 'Video call with Lisa', all_day: false, start_at: ago(-60 * 31), end_at: ago(-60 * 31.5), has_photo: false, created_at: ago(600), decided_at: ago(500) },
		{ id: uid('v'), frame_id: oma.id, sender_id: st.me.id, sender_name: 'Jeroen', status: 'declined', title: 'Bowling night', all_day: false, start_at: ago(-60 * 80), end_at: ago(-60 * 82), has_photo: false, created_at: ago(3000), decided_at: ago(2900) }
	];
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

async function shot(page: Page, name: string, fullPage = true) {
	await page.waitForTimeout(450);
	await page.screenshot({ path: `${DIR}/${name}.png`, fullPage });
}

const VIEWPORTS = [
	{ tag: 'phone', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
	{ tag: 'desktop', viewport: { width: 1280, height: 900 }, isMobile: false, hasTouch: false, deviceScaleFactor: 1 }
] as const;

for (const vp of VIEWPORTS) {
	for (const scheme of ['light', 'dark'] as const) {
		test.describe(`calendar ${vp.tag} ${scheme}`, () => {
			test.use({ viewport: vp.viewport, isMobile: vp.isMobile, hasTouch: vp.hasTouch, deviceScaleFactor: vp.deviceScaleFactor, colorScheme: scheme });

			test(`calendar ${vp.tag} ${scheme}`, async ({ page }) => {
				const st = state();
				await mockApi(page, st);
				const n = (s: string) => `${vp.tag}-${scheme}-cal${s}`;
				const oma = st.frames[0]!;
				await signIn(page);

				await page.goto(`/frames/${oma.id}?tab=calendar`);
				await page.getByRole('heading', { name: 'What the frame shows' }).waitFor();
				await page.getByRole('button', { name: /Doctor/ }).waitFor();
				await shot(page, n('01-tab'));

				const preview = page.locator('.preview-card');
				await preview.getByRole('button', { name: 'Month' }).click();
				await preview.getByRole('grid').waitFor();
				await preview.scrollIntoViewIfNeeded();
				await shot(page, n('02-month'), false);

				await preview.getByRole('button', { name: 'List' }).click();
				await preview.getByRole('button', { name: /Sunday lunch at ours/ }).click();
				await page.getByRole('dialog').waitFor();
				await shot(page, n('03-event'), false);
				await page.keyboard.press('Escape');

				// Add an ICS link, tested.
				await page.getByRole('button', { name: 'Add calendar' }).click();
				const dialog = page.getByRole('dialog');
				await dialog.getByLabel('Calendar address').fill('webcal://calendar.google.com/calendar/ical/abc/basic.ics');
				await dialog.getByRole('button', { name: 'Test connection' }).click();
				await dialog.getByText('Connected to', { exact: false }).waitFor();
				await shot(page, n('04-add-ics'), false);

				// CalDAV with a calendar picker.
				await dialog.getByRole('radio', { name: /CalDAV account/ }).check();
				await dialog.getByLabel('Server address').fill('https://cloud.example.org');
				await dialog.getByLabel('Username').fill('oma');
				await dialog.getByLabel('App password', { exact: true }).fill('abcd-efgh');
				await dialog.getByRole('button', { name: 'Test connection' }).click();
				await dialog.getByText('Which calendars?').waitFor();
				await dialog.getByRole('checkbox', { name: 'Birthdays' }).check();
				await shot(page, n('05-add-caldav'), false);
				await page.keyboard.press('Escape');

				// Send an event with a photo.
				await page.getByRole('button', { name: 'Send an event' }).click();
				const send = page.getByRole('dialog');
				await send.getByLabel('What').fill('Sunday lunch at ours');
				await send.getByLabel('Where').fill('Gent');
				await send.locator('input[type=file]').setInputFiles({ name: 'table.png', mimeType: 'image/png', buffer: await gradientPng(480, 300, 30) });
				await shot(page, n('06-send'), false);
				await send.getByText('Behind').click();
				await shot(page, n('07-send-background'), false);
				await page.keyboard.press('Escape');

				// Settings and timed views, and a member who can only send.
				await page.getByRole('heading', { name: 'Calendar on the frame' }).scrollIntoViewIfNeeded();
				await shot(page, n('08-settings'), false);
			});
		});
	}
}
