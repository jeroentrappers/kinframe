import { expect, test } from '@playwright/test';
import { emptyState, mockApi, shopFixture } from './mock-api';

test.describe('shop', () => {
	test('nothing shop-related without a shop in discovery', async ({ page }) => {
		await mockApi(page, emptyState());
		await page.goto('/');
		await expect(page.getByText('Connected to Testfamilie')).toBeVisible();
		await expect(page.getByRole('heading', { name: /Get a/ })).toHaveCount(0);
		await expect(page.getByRole('link', { name: /Reserve/ })).toHaveCount(0);
		await expect(page.getByTestId('seller-footer')).toHaveCount(0);
		await page.goto('/shop');
		await expect(page.getByRole('heading', { name: 'Frames aren’t sold on this server.' })).toBeVisible();
		await expect(page.getByLabel('Email', { exact: true })).toHaveCount(0);
	});

	test('landing section → reserve one frame → free-shipping hint → success', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = shopFixture();
		await mockApi(page, state);
		await page.goto('/');

		const section = page.getByRole('region', { name: 'Get a kinframe' });
		await expect(section).toBeVisible();
		await expect(section.getByRole('heading', { name: 'kinframe 10.1"' })).toBeVisible();
		await expect(section.getByText('€79', { exact: true })).toBeVisible();
		await expect(section.getByText('€159', { exact: true })).toBeVisible();
		await expect(section.getByText('incl. 21% VAT').first()).toBeVisible();
		await expect(section.getByText('No subscription, everything included')).toBeVisible();
		await expect(section.getByText('Free shipping from €100')).toBeVisible();
		await expect(section.getByText('Expected delivery: early 2027')).toBeVisible();
		await expect(page.getByTestId('seller-footer')).toContainText('Example BV · VAT BE0123.456.789');
		await expect(page.getByText(/Frameo|Denver/)).toHaveCount(0);

		await section.getByRole('link', { name: 'Reserve the kinframe 10.1"' }).click();
		await expect(page).toHaveURL(/\/shop\?product=frame-10$/);
		await expect(page.getByRole('heading', { name: 'Reserve your kinframe' })).toBeVisible();

		// One small frame is preselected: shipping applies and the hint says how much is missing.
		await expect(page.getByLabel('Number of kinframe 10.1"')).toHaveValue('1');
		await expect(page.getByTestId('shop-total')).toHaveText('€85.95');
		await expect(page.getByTestId('free-hint')).toHaveText('Add €21.00 more for free shipping.');

		// Submitting empty details shows an error summary and marks the fields.
		await page.getByRole('button', { name: 'Reserve, no payment now' }).click();
		const alert = page.getByRole('alert').filter({ hasText: 'Please check the highlighted fields.' });
		await expect(alert).toBeVisible();
		await expect(alert).toBeFocused();
		await expect(page.getByLabel('Name', { exact: true })).toHaveAttribute('aria-invalid', 'true');
		await expect(page.getByText('We need your permission to email you about the reservation.').first()).toBeVisible();
		expect(state.calls.some((c) => c.path === '/v1/shop/reservations')).toBe(false);

		// A second small frame makes shipping free.
		await page.getByRole('button', { name: 'One kinframe 10.1" more' }).click();
		await expect(page.getByTestId('shop-total')).toHaveText('€158.00');
		await expect(page.getByText('You get free shipping.')).toBeVisible();

		await page.getByLabel('Name', { exact: true }).fill('An Peeters');
		await page.getByLabel('Email', { exact: true }).fill('an@example.org');
		await page.getByLabel('Country').selectOption('BE');
		await page.getByLabel(/Postal code/).fill('9000');
		await page.getByLabel(/I agree that Example BV emails me/).check();
		await expect(alert).toHaveCount(0);
		await page.getByRole('button', { name: 'Reserve, no payment now' }).click();

		await expect(page.getByRole('heading', { name: 'Thank you! Your kinframe is reserved.' })).toBeVisible();
		await expect(page.getByTestId('reservation-reference')).toHaveText('KF-01QM');
		await expect(page.getByText('We sent a confirmation to an@example.org.', { exact: false })).toBeVisible();

		const call = state.calls.find((c) => c.path === '/v1/shop/reservations');
		expect(call?.body).toEqual({
			name: 'An Peeters',
			email: 'an@example.org',
			country: 'BE',
			postal_code: '9000',
			items: [{ product_id: 'frame-10', quantity: 2 }],
			locale: 'en',
			consent: true
		});
		expect(call?.headers?.['authorization']).toBeUndefined();
	});

	test('server field errors are shown on the form', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = shopFixture();
		await mockApi(page, state);
		await page.goto('/shop?product=frame-15');
		await expect(page.getByTestId('shop-total')).toHaveText('€159.00');
		await page.getByLabel('Name', { exact: true }).fill('Piet');
		await page.getByLabel('Email', { exact: true }).fill('piet@example.nl');
		await page.getByLabel('Country').selectOption('NL');
		await page.getByLabel(/I agree that/).check();
		await page.route('**/v1/shop/reservations', (r) =>
			r.fulfill({
				status: 422,
				contentType: 'application/problem+json',
				body: JSON.stringify({ type: 'x', title: 'Validation failed', status: 422, code: 'validation_failed', errors: [{ field: 'email', code: 'format' }] })
			})
		);
		await page.getByRole('button', { name: 'Reserve, no payment now' }).click();
		await expect(page.getByLabel('Email', { exact: true })).toHaveAttribute('aria-invalid', 'true');
		await expect(page.getByText('This doesn’t look like an email address.').first()).toBeVisible();
	});

	test('the cancel link asks first, then cancels', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = shopFixture();
		await mockApi(page, state);
		await page.goto('/shop?product=frame-10');
		await page.getByLabel('Name', { exact: true }).fill('An');
		await page.getByLabel('Email', { exact: true }).fill('an@example.org');
		await page.getByLabel('Country').selectOption('BE');
		await page.getByLabel(/I agree that/).check();
		await page.getByRole('button', { name: 'Reserve, no payment now' }).click();
		await expect(page.getByTestId('reservation-reference')).toHaveText('KF-01QM');

		await page.goto('/shop/cancel?token=tok-KF-01QM');
		await expect(page.getByRole('heading', { name: 'Cancel your reservation?' })).toBeVisible();
		expect(state.reservations[0]!.status).toBe('new');
		await page.getByRole('button', { name: 'Yes, cancel my reservation' }).click();
		await expect(page.getByRole('heading', { name: 'Your reservation is cancelled' })).toBeVisible();
		await expect(page.getByText('Reservation KF-01QM is cancelled.', { exact: false })).toBeVisible();
		expect(state.reservations[0]!.status).toBe('cancelled');

		await page.goto('/shop/cancel?token=tok-forged-token');
		await page.getByRole('button', { name: 'Yes, cancel my reservation' }).click();
		await expect(page.getByText('This cancel link isn’t valid.', { exact: false })).toBeVisible();
	});

	test('Dutch: the shop speaks nl and uses the configured specs', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = shopFixture();
		await mockApi(page, state);
		await page.addInitScript(() => localStorage.setItem('kinframe.locale', 'nl'));
		await page.goto('/');
		const section = page.getByRole('region', { name: 'Bestel je eigen kinframe' });
		await expect(section).toBeVisible();
		await expect(section.getByText('kinframe vooraf geïnstalleerd')).toBeVisible();
		await expect(section.getByText('Verwachte levering: begin 2027')).toBeVisible();
	});

	test('operators see and manage reservations; others do not', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = shopFixture();
		state.reservations = [
			{
				id: '0192f0a0-0000-7000-8000-00000000r001',
				reference: 'KF-7Q3M',
				status: 'new',
				name: 'An Peeters',
				email: 'an@example.org',
				country: 'BE',
				postal_code: '9000',
				note: 'Graag voor Kerstmis',
				locale: 'nl',
				items: [{ product_id: 'frame-10', name: 'kinframe 10.1"', quantity: 2, unit_cents: 7900, line_cents: 15800 }],
				subtotal_cents: 15800,
				shipping_cents: 0,
				total_cents: 15800,
				currency: 'EUR',
				internal_note: null,
				created_at: new Date().toISOString(),
				updated_at: new Date().toISOString(),
				cancelled_at: null
			}
		];
		await mockApi(page, state);
		await page.goto('/');
		await page.getByLabel('Email', { exact: true }).fill('op@example.org');
		await page.getByLabel('Name', { exact: true }).fill('Op');
		await page.getByRole('button', { name: 'Continue' }).click();
		await expect(page).toHaveURL(/\/frames$/);

		// Not an operator: no link, and the page says so.
		await page.goto('/settings');
		await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
		await expect(page.getByRole('link', { name: /Shop reservations/ })).toHaveCount(0);
		await page.goto('/settings/reservations');
		await expect(page.getByText('Only server operators can see reservations.')).toBeVisible();

		state.me!.is_operator = true;
		await page.goto('/settings');
		await page.getByRole('link', { name: /Shop reservations/ }).click();
		await expect(page.getByRole('heading', { name: 'Reservations' })).toBeVisible();
		const card = page.getByTestId('reservation');
		await expect(card).toContainText('KF-7Q3M');
		await expect(card).toContainText('Graag voor Kerstmis');
		await expect(card).toContainText('€158.00');

		await card.getByLabel('Status').selectOption('contacted');
		await expect(page.getByText('Reservation updated')).toBeVisible();
		expect(state.reservations[0]!.status).toBe('contacted');

		await card.getByLabel('Internal note').fill('Called on Monday');
		await card.getByRole('button', { name: 'Save' }).click();
		await expect.poll(() => state.reservations[0]!.internal_note).toBe('Called on Monday');

		const download = page.waitForEvent('download');
		await page.getByRole('button', { name: 'Download CSV' }).click();
		expect((await download).suggestedFilename()).toMatch(/^reservations-\d{4}-\d{2}-\d{2}\.csv$/);
	});
});
