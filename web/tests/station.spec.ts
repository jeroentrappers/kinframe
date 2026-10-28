import { expect, test, type Page } from '@playwright/test';
import { emptyState, mockApi } from './mock-api';

/** A station page as GET /v1/stations/{id}/public answers it: 48 hours in 5-minute steps ending now. */
function stationPage() {
	const step = 300;
	const n = 576;
	const now = Date.now();
	const start = Math.floor(now / 1000 / step) * step * 1000 + step * 1000 - n * step * 1000;
	const wave = (base: number, amp: number, d = 1) =>
		Array.from({ length: n }, (_, i) => Math.round((base + amp * Math.sin(i / 48)) * 10 ** d) / 10 ** d);
	const rain = Array.from({ length: n }, (_, i) => (i < 300 ? 0 : Math.min(3.2, (i - 300) * 0.05)));
	return {
		station_id: 'IAFFLI3',
		name: 'Affligem',
		timezone: 'Europe/Brussels',
		received_at: new Date(now - 20_000).toISOString(),
		reading: {
			temp_c: 11.8,
			dew_point_c: 7.5,
			feels_like_c: 10.6,
			humidity_pct: 83,
			pressure_hpa: 1013.5,
			wind_ms: 1.8,
			gust_ms: 3.3,
			wind_dir_deg: 297,
			rain_rate_mm_h: 0,
			rain_today_mm: 3.2,
			solar_wm2: 293,
			uv_index: 2
		},
		today: {
			temp_min_c: 6.4,
			temp_min_at: new Date(now - 4 * 3600_000).toISOString(),
			temp_max_c: 11.9,
			temp_max_at: new Date(now - 3600_000).toISOString(),
			gust_max_ms: 6.8,
			gust_max_at: new Date(now - 2 * 3600_000).toISOString(),
			rain_mm: 3.2,
			uv_max: 2
		},
		series: {
			start: new Date(start).toISOString(),
			step_s: step,
			temp_c: wave(9.5, 3),
			dew_point_c: wave(6, 1.5),
			feels_like_c: wave(8.5, 3),
			humidity_pct: wave(85, 10, 0),
			pressure_hpa: wave(1010, 3),
			wind_ms: wave(2.5, 1.5),
			gust_ms: wave(4.5, 2.5),
			wind_dir_deg: wave(250, 40, 0),
			rain_rate_mm_h: rain.map(() => 0),
			rain_today_mm: rain,
			solar_wm2: wave(150, 150, 0),
			uv_index: wave(1, 1, 0)
		}
	};
}

async function station(page: Page) {
	await mockApi(page, emptyState());
	// Routes registered later win over mockApi's catch-all for /v1/.
	await page.route(/\/v1\/stations\/[^/]+\/public$/, (r) => {
		const id = decodeURIComponent(new URL(r.request().url()).pathname.split('/')[3] ?? '');
		return id.toUpperCase() === 'IAFFLI3'
			? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(stationPage()) })
			: r.fulfill({ status: 404, contentType: 'application/problem+json', body: '{"status":404}' });
	});
}

test.describe('weather station public page', () => {
	test('shows the readings and the charts without signing in', async ({ page }) => {
		await station(page);
		await page.goto('/station/IAFFLI3');
		await expect(page.getByRole('heading', { level: 1, name: 'Affligem' })).toBeVisible();
		await expect(page.getByText('Weather station IAFFLI3')).toBeVisible();
		await expect(page.getByText('11.8°', { exact: true })).toBeVisible();
		for (const name of ['Temperature', 'Wind', 'Humidity', 'Pressure', 'Rain per hour', 'Sunlight'])
			await expect(page.getByRole('img', { name })).toBeVisible();
		// The wind unit switches the tiles and the chart together.
		await page.getByRole('button', { name: 'm/s' }).click();
		await expect(page.getByText('1.8 m/s').first()).toBeVisible();
		// A table for those who can't read the charts.
		await page.getByText('Show the readings as a table').click();
		await expect(page.getByRole('table')).toBeVisible();
		// No sideways scrolling, on a phone too.
		const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
		expect(overflow).toBeLessThanOrEqual(0);
	});

	test('a station without a public page says so', async ({ page }) => {
		await station(page);
		await page.goto('/station/INOPE1');
		await expect(page.getByRole('heading', { name: 'No public page for this station' })).toBeVisible();
	});
});
