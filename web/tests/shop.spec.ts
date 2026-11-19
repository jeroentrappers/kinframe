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

	test('operator: tracking link on a paid order', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = { ...shopFixture(), checkout: true };
		const now = new Date().toISOString();
		state.reservations = [
			{
				id: '0192f0a0-0000-7000-8000-00000000r002',
				reference: 'FL-ND7K',
				status: 'shipped',
				name: 'An Peeters',
				email: 'an@example.org',
				country: 'BE',
				postal_code: '9000',
				street: 'Veldstraat 10',
				city: 'Gent',
				note: null,
				locale: 'nl',
				items: [{ product_id: 'frame-10', name: 'kinframe 10.1"', quantity: 1, unit_cents: 9900, line_cents: 9900 }],
				subtotal_cents: 9900,
				shipping_cents: 695,
				total_cents: 10595,
				currency: 'EUR',
				internal_note: null,
				payment_status: 'paid',
				paid_at: now,
				shipped_at: now,
				created_at: now,
				updated_at: now,
				cancelled_at: null
			}
		];
		await mockApi(page, state);
		await page.goto('/');
		await page.getByLabel('Email', { exact: true }).fill('op@example.org');
		await page.getByLabel('Name', { exact: true }).fill('Op');
		await page.getByRole('button', { name: 'Continue' }).click();
		await expect(page).toHaveURL(/\/frames$/);
		state.me!.is_operator = true;
		await page.goto('/settings/reservations');
		const card = page.getByTestId('reservation');
		const input = card.getByLabel('Tracking link');
		const save = card.getByRole('button', { name: 'Save' }).last();
		await input.fill('not a link');
		await expect(save).toBeDisabled();
		await input.fill('https://track.bpost.cloud/btr/web/#/search?itemCode=323212345678901234');
		await save.click();
		await expect.poll(() => state.reservations[0]!.tracking_url).toBe('https://track.bpost.cloud/btr/web/#/search?itemCode=323212345678901234');
		await expect(card.getByRole('link', { name: 'Tracking link' })).toHaveAttribute('href', /itemCode=323212345678901234/);
		await expect(save).toBeDisabled();
	});

	test('stock: availability per frame, dispatch day in the summary, operator count', async ({ page }) => {
		const state = emptyState();
		const base = shopFixture();
		state.discovery.shop = {
			...base,
			checkout: true,
			products: base.products.map((p, i) => ({ ...p, in_stock: i === 0 ? 2 : 0 })),
			stock: { ships_in_stock: '2026-11-17', ships_out_of_stock: '2026-11-20' }
		};
		await mockApi(page, state);
		await page.goto('/');
		const section = page.getByRole('region', { name: 'Get a kinframe' });
		const avail = section.getByTestId('availability');
		await expect(avail.first()).toHaveText(/In stock · shipped Tuesday,? (17 November|November 17)/);
		await expect(avail.nth(1)).toHaveText(/Shipped Friday,? (20 November|November 20)/);

		await section.getByRole('link', { name: /^Buy the / }).first().click();
		await expect(page.getByTestId('dispatch')).toHaveText(/Expected dispatch: Tuesday,? (17 November|November 17)/);
		await page.getByRole('button', { name: /^One .* more$/ }).first().click();
		await page.getByRole('button', { name: /^One .* more$/ }).first().click();
		await expect(page.getByTestId('dispatch')).toHaveText(/Expected dispatch: Friday,? (20 November|November 20)/);

		await page.goto('/');
		await page.getByLabel('Email', { exact: true }).fill('op@example.org');
		await page.getByLabel('Name', { exact: true }).fill('Op');
		await page.getByRole('button', { name: 'Continue' }).click();
		await expect(page).toHaveURL(/\/frames$/);
		state.me!.is_operator = true;
		await page.goto('/settings/reservations');
		const stock = page.getByTestId('stock');
		const input = stock.getByLabel(`${base.products[0]!.name} in stock`);
		await expect(input).toHaveValue('0');
		await input.fill('4');
		await stock.getByRole('button', { name: 'Save' }).first().click();
		await expect.poll(() => state.stock[base.products[0]!.id]).toBe(4);
		await expect(page.getByText('Stock updated')).toBeVisible();
	});

	test('product photos: gallery, thumbnails and full-screen view', async ({ page }) => {
		const state = emptyState();
		const base = shopFixture();
		const photos = ['/v1/shop/media/a.png', '/v1/shop/media/b.png', '/v1/shop/media/c.png'];
		state.discovery.shop = { ...base, products: base.products.map((p, i) => (i === 0 ? { ...p, photos } : p)) };
		await mockApi(page, state);
		// A 1×1 PNG for every photo.
		const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');
		await page.route('**/v1/shop/media/*', (r) => r.fulfill({ status: 200, contentType: 'image/png', body: png }));
		await page.goto('/');
		const section = page.getByRole('region', { name: 'Get a kinframe' });
		const gallery = section.getByTestId('product-photos');
		await expect(gallery).toHaveCount(1); // the second frame keeps its drawing
		const name = base.products[0]!.name;
		await expect(gallery.getByRole('img', { name: `${name}, photo 1 of 3` })).toHaveAttribute('src', photos[0]!);
		await gallery.getByRole('button', { name: `${name}, photo 3 of 3` }).click();
		await expect(gallery.getByRole('img', { name: `${name}, photo 3 of 3` })).toBeVisible();

		await gallery.getByRole('button', { name: `Enlarge the photo of ${name}` }).click();
		const viewer = page.getByRole('dialog', { name });
		await expect(viewer).toBeVisible();
		await viewer.getByRole('button', { name: 'Next photo' }).click();
		await expect(viewer.getByRole('img')).toHaveAttribute('src', photos[0]!);
		await page.keyboard.press('ArrowLeft');
		await expect(viewer.getByRole('img')).toHaveAttribute('src', photos[2]!);
		await page.keyboard.press('Escape');
		await expect(viewer).toBeHidden();

		await section.getByRole('link', { name: `Reserve the ${name}` }).click();
		await expect(page.getByRole('img', { name: `${name}, photo 1 of 3` })).toBeVisible();
	});

	test('checkout: buy, delivery address, pay, paid order page', async ({ page }) => {
		const state = emptyState();
		state.discovery.shop = { ...shopFixture(), checkout: true };
		await mockApi(page, state);
		await page.goto('/');

		const section = page.getByRole('region', { name: 'Get a kinframe' });
		await expect(section.getByText('Pay securely online')).toBeVisible();
		await expect(section.getByRole('link', { name: /Reserve/ })).toHaveCount(0);
		await expect(page.getByTestId('seller-footer').getByRole('link', { name: 'Terms of sale' })).toHaveAttribute('href', '/shop/terms');
		await section.getByRole('link', { name: 'Buy the kinframe 10.1"' }).click();
		await expect(page.getByRole('heading', { name: 'Order your kinframe' })).toBeVisible();

		await page.getByLabel('Name').fill('An Peeters');
		await page.getByLabel('Email', { exact: true }).fill('an@example.org');
		await page.getByLabel('Country').selectOption('BE');
		await page.getByLabel('I accept the terms of sale of Example BV.').check();
		await page.getByRole('button', { name: /^Order and pay/ }).click();
		await expect(page.getByRole('alert')).toContainText('Enter your street and number.');
		await expect(page.getByRole('alert')).toContainText('Enter your postal code.');
		await expect(page.getByRole('alert')).toContainText('Enter your city.');

		await page.getByLabel('Street and number').fill('Veldstraat 10');
		await page.getByLabel('Postal code').fill('9000');
		await page.getByLabel('City').fill('Gent');
		await page.getByRole('button', { name: /^Order and pay/ }).click();

		await expect(page).toHaveURL(/\/shop\/order\?token=tok-KF-01QM$/);
		await expect(page.getByTestId('order-title')).toHaveText('Thank you! Your order is paid.');
		await expect(page.getByTestId('order-reference')).toHaveText('KF-01QM');
		await expect(page.getByText('We sent a confirmation to an@example.org', { exact: false })).toBeVisible();
		const created = state.calls.find((c) => c.path === '/v1/shop/reservations')!.body as Record<string, unknown>;
		expect(created).toMatchObject({ street: 'Veldstraat 10', postal_code: '9000', city: 'Gent' });
		expect(state.reservations[0]!.payment_status).toBe('paid');

		await page.goto('/shop/terms');
		await expect(page.getByRole('heading', { name: 'Terms of sale', level: 1 })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Right of withdrawal (14 days)' })).toBeVisible();
		await expect(page.getByTestId('legal-other-server')).toHaveCount(0);
	});
});
