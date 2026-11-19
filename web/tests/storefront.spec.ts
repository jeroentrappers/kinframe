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

// Like the live shop: converted Denver frames, shipping to Belgium only, and retailer links to buy and convert one.
function resaleState(): MockState {
	const state = storefrontState();
	state.discovery.shop = {
		...state.discovery.shop!,
		shipping: { countries: ['BE'], flat_cents: 695, free_from_cents: 10000 },
		notice: {
			en: 'We resell Denver frames that we converted to Fotokader.\n\nPrefer to do it yourself? Buy one and convert it with our free tool.',
			nl: 'Wij verkopen Denver-kaders die we omgezet hebben naar Fotokader.'
		},
		diy: { title: { en: 'Convert one yourself', nl: 'Zet er zelf een om' } },
		elsewhere: [
			{ countries: ['BE'], retailer: 'Amazon', product: 'Denver PFF-1024', url: 'https://www.amazon.com.be/dp/B0TEST1', affiliate: true },
			{ countries: ['BE'], retailer: 'bol.com', product: 'Denver PFF-1024', url: 'https://www.bol.com/be/p/1', secondary: true },
			{ countries: ['NL'], retailer: 'Amazon.nl', product: 'Denver PFF-1024', url: 'https://www.amazon.nl/dp/B0TEST2', affiliate: true },
			{ countries: ['NL'], retailer: 'bol.com', product: 'Denver PFF-1024', url: 'https://www.bol.com/nl/p/1', secondary: true }
		]
	};
	return state;
}

test.describe('storefront: resale notice and DIY links, Belgian visitor', () => {
	test.use({ locale: 'en-BE' });

	test('sees the notice first, then the DIY section with the Amazon link and a small bol.com link', async ({ page }) => {
		await open(page, resaleState());

		const notice = page.getByTestId('shop-notice');
		await expect(notice).toBeVisible();
		await expect(notice.locator('p')).toHaveText([
			'We resell Denver frames that we converted to Fotokader.',
			'Prefer to do it yourself? Buy one and convert it with our free tool.'
		]);

		const diy = page.getByRole('region', { name: 'Convert one yourself' });
		await expect(diy).toBeVisible();
		const amazon = diy.getByRole('link', { name: 'Denver PFF-1024 · Amazon' });
		await expect(amazon).toHaveAttribute('href', 'https://www.amazon.com.be/dp/B0TEST1');
		await expect(amazon).toHaveAttribute('rel', 'sponsored noopener');
		const bol = diy.getByRole('link', { name: 'Also at bol.com' });
		await expect(bol).toHaveAttribute('href', 'https://www.bol.com/be/p/1');
		await expect(bol).toHaveAttribute('rel', 'noopener');
		await expect(bol).not.toHaveClass(/btn/);
		await expect(diy.getByRole('link', { name: 'How to convert a frame' })).toHaveAttribute('href', `${APP}/convert`);
		await expect(diy.getByText('These are affiliate links: we may earn a small commission, at no extra cost to you.')).toBeVisible();
		// The bol.com link is plain small text, not one of the buttons.
		expect(await bol.evaluate((a) => parseFloat(getComputedStyle(a).fontSize))).toBeLessThan(
			await amazon.evaluate((a) => parseFloat(getComputedStyle(a).fontSize))
		);
		await expect(diy.getByText('Amazon.nl')).toHaveCount(0);

		// Notice and DIY come before the products; "Outside Belgium?" stays for links to other countries.
		const products = page.getByRole('region', { name: 'Get a Fotokader' });
		const y = async (l: ReturnType<typeof page.getByTestId>) => (await l.boundingBox())!.y;
		expect(await y(notice)).toBeLessThan(await y(diy));
		expect(await y(diy)).toBeLessThan(await y(products.getByRole('heading', { name: 'kinframe 10.1"' })));
		await expect(page.getByTestId('shop-elsewhere')).toHaveCount(1);
		await expect(page.getByTestId('shop-notice')).toHaveCount(1);

		// The shop page says the same above the order form.
		await products.getByRole('link', { name: 'Reserve the kinframe 10.1"' }).click();
		await expect(page.getByRole('heading', { name: 'Reserve your Fotokader' })).toBeVisible();
		await expect(page.getByTestId('shop-notice')).toContainText('We resell Denver frames');
		await expect(page.getByRole('region', { name: 'Convert one yourself' }).getByRole('link', { name: 'Also at bol.com' })).toBeVisible();
		expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
	});

	test('Dutch: the notice and title in Dutch, the default body', async ({ page }) => {
		await page.addInitScript(() => localStorage.setItem('kinframe.locale', 'nl'));
		await open(page, resaleState());
		await expect(page.getByTestId('shop-notice')).toHaveText('Wij verkopen Denver-kaders die we omgezet hebben naar Fotokader.');
		const diy = page.getByRole('region', { name: 'Zet er zelf een om' });
		await expect(diy.getByText('Doe je het liever zelf? Koop een geschikt fotokader en maak er met onze gratis tool zelf een Fotokader van.')).toBeVisible();
		await expect(diy.getByRole('link', { name: 'Ook bij bol.com' })).toBeVisible();
	});
});

test.describe('storefront: resale notice and DIY links, Dutch visitor', () => {
	test.use({ locale: 'en-NL' });

	test('sees "Outside Belgium?" with bol.com as a small secondary link', async ({ page }) => {
		await open(page, resaleState());
		await expect(page.getByTestId('shop-notice')).toBeVisible();
		await expect(page.getByTestId('shop-diy')).toHaveCount(0);

		const outside = page.getByRole('region', { name: 'Outside Belgium?' });
		await expect(outside).toBeVisible();
		const amazon = outside.getByRole('link', { name: /Netherlands\s*Denver PFF-1024 · Amazon\.nl/ });
		await expect(amazon).toHaveAttribute('rel', 'sponsored noopener');
		const bol = outside.getByRole('link', { name: 'Also at bol.com' });
		await expect(bol).toHaveAttribute('href', 'https://www.bol.com/nl/p/1');
		await expect(bol).toHaveAttribute('rel', 'noopener');
		await expect(outside.getByRole('link', { name: /amazon\.com\.be|Denver PFF-1024 · Amazon$/ })).toHaveCount(0);
		await expect(outside.getByText('These are affiliate links', { exact: false })).toBeVisible();
	});
});
