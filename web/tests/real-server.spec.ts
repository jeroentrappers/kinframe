// Opt-in end-to-end run against a real kinframe server in dev-auth mode:
//   REAL_SERVER=http://localhost:8110 npx playwright test tests/real-server.spec.ts --project=desktop
// The server must serve this build (KINFRAME_WEB_DIR=web/build). A fake frame is enrolled via the device API.
import { expect, test } from '@playwright/test';
import { gradientPng } from './mock-api';

const base = process.env.REAL_SERVER;
test.skip(!base, 'set REAL_SERVER to run against a real server');

test('real server: dev sign-in → claim frame → send → history', async ({ page, request }) => {
	const enroll = await request.post(`${base}/v1/devices/enroll`, {
		data: { hardware: { model: 'PFF_1024', brand: 'denver' }, screen: { width: 1280, height: 800 }, app: { version: '0.1.0', version_code: 1 } }
	});
	expect(enroll.status()).toBe(201);
	const dev = await enroll.json();
	const tok = await (await request.post(`${base}/v1/devices/token`, { data: dev })).json();
	const codeRes = await request.post(`${base}/v1/device/code`, { headers: { Authorization: `Bearer ${tok.access_token}` } });
	const { code } = await codeRes.json();

	page.on('response', async (r) => { if (r.status() >= 400) console.log('HTTP', r.status(), r.request().method(), r.url(), r.request().postData()?.slice(0, 400), await r.text().catch(() => '')); });
	await page.goto(`${base}/f/${code}`);
	await page.getByLabel('Email').fill(`e2e-${Date.now()}@example.org`);
	await page.getByLabel('Name').fill('E2E Tester');
	await page.getByRole('button', { name: 'Continue' }).click();
	await expect(page.getByRole('heading', { name: 'It’s yours!' })).toBeVisible();
	await page.getByLabel('Frame name').fill('E2E frame');
	await page.getByRole('button', { name: 'Save and send photos' }).click();

	await expect(page.getByRole('checkbox', { name: /E2E frame/ })).toBeChecked();
	await page.getByTestId('file-input').setInputFiles([{ name: 'e2e.png', mimeType: 'image/png', buffer: await gradientPng(320, 240) }]);
	await page.getByLabel('Caption for item 1').fill('Hello from the e2e test');
	await page.getByRole('button', { name: 'Send it' }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/, { timeout: 20_000 });
	await expect(page.getByText('Hello from the e2e test')).toBeVisible();
	await page.goto(`${base}/history`);
	await expect(page.getByText('E2E frame').first()).toBeVisible();
	await page.goto(`${base}/frames`);
	await expect(page.getByText('E2E frame').first()).toBeVisible();
});
