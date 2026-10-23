import { expect, test } from '@playwright/test';
import { emptyState, mockApi } from './mock-api';

test.describe('privacy policy and terms', () => {
	test('linked from the landing page, readable without signing in', async ({ page }) => {
		await mockApi(page, emptyState());
		await page.goto('/');
		await page.getByRole('link', { name: 'Privacy', exact: true }).click();
		await expect(page).toHaveURL(/\/privacy$/);
		await expect(page.getByRole('heading', { level: 1, name: 'Privacy policy' })).toBeVisible();
		await expect(page.getByText('Last updated: 23 October 2026')).toBeVisible();
		await expect(page.getByRole('heading', { name: /Deleting your data/ })).toBeVisible();
		await expect(page.getByRole('link', { name: 'jeroen@appmire.be' }).first()).toHaveAttribute('href', 'mailto:jeroen@appmire.be');
		// This test server is not kinframe.appmire.be, so the page says whose service the text describes.
		await expect(page.getByTestId('legal-other-server')).toContainText('kinframe.appmire.be');
		// Readable on a phone: no sideways scrolling.
		const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
		expect(overflow).toBeLessThanOrEqual(0);

		await page.getByRole('link', { name: 'Terms', exact: true }).click();
		await expect(page).toHaveURL(/\/terms$/);
		await expect(page.getByRole('heading', { level: 1, name: 'Terms of use' })).toBeVisible();
	});

	test('#delete opens at the deletion instructions', async ({ page }) => {
		await mockApi(page, emptyState());
		await page.goto('/privacy#delete');
		await expect(page.locator('#delete')).toBeInViewport();
	});

	test('Dutch and French texts; other languages fall back to English with a note', async ({ page }) => {
		await mockApi(page, emptyState());
		await page.goto('/terms');
		// The picker's label follows the language, so find it by place.
		const lang = page.locator('header.top select');
		await lang.selectOption('nl');
		await expect(page.getByRole('heading', { level: 1, name: 'Gebruiksvoorwaarden' })).toBeVisible();
		await lang.selectOption('fr');
		await expect(page.getByRole('heading', { level: 1, name: 'Conditions d’utilisation' })).toBeVisible();
		await lang.selectOption('de');
		await expect(page.getByRole('heading', { level: 1, name: 'Terms of use' })).toBeVisible();
		await expect(page.getByText('Dieser Text liegt auf Englisch, Niederländisch und Französisch vor.', { exact: false })).toBeVisible();
	});
});
