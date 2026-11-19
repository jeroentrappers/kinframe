import { expect, test } from '@playwright/test';
import { call, signIn, socialState } from './social-fixtures';

// Voice messages (README §5.19) with Chrome's fake microphone.
test.use({ launchOptions: { args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] }, permissions: ['microphone'] });

test('record, review and send a voice message', async ({ page }) => {
	const s = socialState();
	await signIn(page, s.st);
	await page.goto(`/chats/${s.direct}`);
	await page.getByRole('button', { name: 'Record a voice message' }).click();
	await expect(page.getByRole('timer')).toBeVisible();
	await page.waitForTimeout(1600);
	await page.getByRole('button', { name: 'Stop recording' }).click();
	await page.getByRole('button', { name: 'Send voice message' }).click();
	await expect.poll(() => (call(s.st, 'POST', `/v1/conversations/${s.direct}/messages`)?.body as { voice?: { upload_id: string } })?.voice?.upload_id, { timeout: 10_000 }).toBeTruthy();
	await expect(page.getByRole('button', { name: 'Play voice message' })).toBeVisible();
});
