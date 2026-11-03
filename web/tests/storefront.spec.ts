// Storefront mode (discovery shop.domains): the same build on another host name is the brand's shop.
// Chrome resolves *.localhost to the loopback address, so storefront.localhost reaches the same preview server.
import { expect, test, type Page } from '@playwright/test';
import { emptyState, mockApi, shopFixture, type MockState } from './mock-api';

const PORT = process.env.PW_PORT ?? 4173;
const APP = `http://localhost:${PORT}`;
const STORE = `http://storefront.localhost:${PORT}`;

function storefrontState(): MockState {
	const state = emptyState();
	state.discovery.web = APP;
	state.discovery.shop = { ...shopFixture(), brand: 'Fotokader', domains: ['storefront.localhost'], default_locale: 'nl' };
	return state;
}

async function open(page: Page, state: MockState, path = '/') {
	await mockApi(page, state);
	await page.goto(STORE + path);
}

test.describe('storefront', () => {
	test.use({ locale: 'en-US' });

	test('a storefront host serves the branded landing page, without the app', async ({ page }) => {
		await open(page, storefrontState());

		await expect(page.getByRole('heading', { level: 1, name: 'The photo frame for the whole family' })).toBeVisible();
		await expect(page).toHaveTitle('Fotokader: the photo frame for the whole family');
		await expect(page.locator('header .logo')).toHaveText('Fotokader');
		await expect(page.getByText('From €79, no subscription')).toBeVisible();

		// How it works, the existing product section, and the FAQ.
		await expect(page.getByRole('heading', { name: 'How it works', level: 2 })).toBeVisible();
		const shop = page.getByRole('region', { name: 'Get a Fotokader' });
		await expect(shop.getByRole('link', { name: 'Reserve the kinframe 10.1"' })).toBeVisible();
		const faq = page.getByRole('region', { name: 'Good to know' });
		await faq.getByText('Where do you deliver?').click();
		await expect(faq.getByText('Delivered to Belgium and Netherlands. Below €100, shipping costs €6.95.')).toBeVisible();
		await faq.getByText('When will my frame arrive?').click();
		await expect(faq.getByText(/Expected delivery: early 2027\. We’ll email you/)).toBeVisible();

		// No sign-in, no app navigation, no server chip; the app is one link away on the main origin.
		await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0);
		await expect(page.getByText(/Connected to/)).toHaveCount(0);
		await expect(page.getByText('Developer sign-in')).toHaveCount(0);
		await expect(page.getByRole('link', { name: 'Frames' })).toHaveCount(0);
		await expect(page.getByTestId('open-app')).toHaveAttribute('href', `${APP}/`);
		await expect(page.getByTestId('seller-footer')).toContainText('Example BV');
		await expect(page.getByText(/Frameo|Denver/)).toHaveCount(0);
		// Nothing sticks out sideways, also on a phone.
		expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
	});

	test('reserving on the storefront calls the API on the storefront origin', async ({ page }) => {
		const state = storefrontState();
		await open(page, state);
		await page.getByRole('region', { name: 'Get a Fotokader' }).getByRole('link', { name: 'Reserve the kinframe 10.1"' }).click();
		await expect(page).toHaveURL(`${STORE}/shop?product=frame-10`);
		await expect(page.getByRole('heading', { name: 'Reserve your Fotokader' })).toBeVisible();
		await expect(page).toHaveTitle('Reserve your Fotokader');

		await page.getByLabel('Name', { exact: true }).fill('An Peeters');
		await page.getByLabel('Email', { exact: true }).fill('an@example.org');
		await page.getByLabel('Country').selectOption('BE');
		await page.getByLabel(/I agree that Example BV emails me/).check();
		const request = page.waitForRequest((r) => r.url().endsWith('/v1/shop/reservations'));
		await page.getByRole('button', { name: 'Reserve, no payment now' }).click();
		expect(new URL((await request).url()).origin).toBe(STORE);
		await expect(page.getByRole('heading', { name: 'Thank you! Your Fotokader is reserved.' })).toBeVisible();
		expect(state.calls.some((c) => c.path === '/v1/me')).toBe(false); // never signs in here

		// Back home stays on the storefront.
		await page.getByRole('link', { name: 'Back to the home page' }).first().click();
		await expect(page).toHaveURL(`${STORE}/`);
		await expect(page.getByRole('heading', { level: 1, name: 'The photo frame for the whole family' })).toBeVisible();
	});

	test('the cancel page works on the storefront', async ({ page }) => {
		const state = storefrontState();
		await open(page, state, '/shop/cancel?token=short');
		await expect(page.getByRole('heading', { name: 'Cancel your reservation?' })).toBeVisible();
		await expect(page.getByText('This cancel link isn’t valid.', { exact: false })).toBeVisible();
		await expect(page.locator('header .logo')).toHaveText('Fotokader');
	});

	test('app pages on the storefront go to the same path on the main origin', async ({ page }) => {
		const state = storefrontState();
		await mockApi(page, state);
		for (const path of ['/callback?code=abc', '/settings', '/f/12345678', '/frames?tab=people#x']) {
			await page.goto(STORE + path);
			await page.waitForURL((u) => u.origin === APP);
			const u = new URL(page.url());
			// The main app keeps the path (a signed-out visitor gets the sign-in page with ?next=).
			expect(u.pathname + u.search + u.hash === path || u.searchParams.get('next') === path.split('#')[0], `${path} → ${page.url()}`).toBe(true);
		}
		await expect(page.getByRole('button', { name: 'Continue' })).toBeVisible(); // the app's own sign-in
	});

	test('the main origin is unchanged when a shop has storefront domains', async ({ page }) => {
		await mockApi(page, storefrontState());
		await page.goto(APP + '/');
		await expect(page.getByText('Connected to Testfamilie')).toBeVisible();
		await expect(page.getByRole('heading', { level: 1, name: 'Your photos, on their frame, in seconds.' })).toBeVisible();
		await expect(page.locator('header .logo')).toHaveText('kinframe');
		await expect(page.getByRole('region', { name: 'Get a Fotokader' })).toBeVisible();
		await expect(page.getByTestId('open-app')).toHaveCount(0);
	});

	test('a host is only a storefront when the shop lists it', async ({ page }) => {
		const state = emptyState(); // no shop at all: the open-source default
		await open(page, state);
		await expect(page.getByText('Connected to Testfamilie')).toBeVisible();
		await expect(page.getByRole('heading', { level: 1, name: 'The photo frame for the whole family' })).toHaveCount(0);
		await expect(page.getByRole('link', { name: /Reserve/ })).toHaveCount(0);
		await expect(page.locator('header .logo')).toHaveText('kinframe');
	});
});

test.describe('storefront language', () => {
	test.describe('a browser language the shop doesn’t speak', () => {
		test.use({ locale: 'ja-JP' });
		test('falls back to the operator’s default', async ({ page }) => {
			await open(page, storefrontState());
			await expect(page.getByRole('heading', { level: 1, name: 'Het fotokader voor de hele familie' })).toBeVisible();
			await expect(page).toHaveTitle('Fotokader: het fotokader voor de hele familie');
			await expect(page.locator('html')).toHaveAttribute('lang', 'nl');
		});
	});
	test.describe('French', () => {
		test.use({ locale: 'fr-BE' });
		test('is used when the browser asks for it', async ({ page }) => {
			await open(page, storefrontState());
			await expect(page.getByRole('heading', { level: 1, name: 'Le cadre photo de toute la famille' })).toBeVisible();
		});
	});
	test.describe('a picked language', () => {
		test.use({ locale: 'fr-BE' });
		test('wins over the default', async ({ page }) => {
			await open(page, storefrontState());
			await page.evaluate(() => localStorage.setItem('kinframe.locale', 'de'));
			await page.reload();
			await expect(page.getByRole('heading', { level: 1, name: 'Der Fotorahmen für die ganze Familie' })).toBeVisible();
		});
	});
});
