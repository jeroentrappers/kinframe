// Family social on the browser frame (/tv/frame): "Ask for new photos", "New photos from Ben", the comment card and
// the weekly prompt's title card (docs/spec/family-social.md §8, README §5.20).
import { expect, test, type Page } from '@playwright/test';
import { devicePlacement, deviceState, emptyState, mockApi, mockDevice, nudgeDevice, pushChange, type DeviceMock } from './mock-api';

const later = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString();

async function frame(page: Page, settings: Record<string, unknown> = {}, prepare: (dev: DeviceMock) => void = () => {}): Promise<DeviceMock> {
	await mockApi(page, emptyState());
	const dev = deviceState();
	pushChange(dev, { type: 'settings', settings: { version: 1, slideshow: { interval_s: 600 }, widgets: { caption: true }, ...settings } });
	pushChange(dev, { type: 'frame', frame: { name: 'Living room TV', owner_name: 'Jeroen' } });
	prepare(dev);
	await mockDevice(page, dev);
	await page.goto('/tv/frame');
	await expect(page.locator('img.media').first()).toBeVisible();
	await expect.poll(() => dev.ws !== null).toBe(true);
	return dev;
}

const askCalls = (dev: DeviceMock) => dev.calls.filter((c) => c.method === 'POST' && c.path === '/v1/device/photo-requests').length;

test('ask for new photos from the menu: asked, "Asked today", then who answered', async ({ page }) => {
	const dev = await frame(page, {}, (d) => pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-1', { caption: 'Beach day' }) }));

	// The heartbeat says what this frame can show.
	dev.ws!.send(JSON.stringify({ type: 'command', id: 'k1', command_type: 'sync_now', args: {} }));
	await expect.poll(() => dev.calls.find((c) => c.path === '/v1/device/heartbeat')?.body).toMatchObject({ features: ['comments', 'photo_requests', 'prompts'] });

	// OK opens the menu: the reactions, and "Ask for new photos" above them (↑).
	await page.keyboard.press('Enter');
	await expect(page.getByRole('toolbar', { name: 'React' })).toBeVisible();
	const ask = page.getByRole('button', { name: 'Ask for new photos' });
	await expect(ask).toBeVisible();
	await page.keyboard.press('ArrowUp');
	await expect(ask).toBeFocused();
	await page.keyboard.press('Enter');
	await expect(page.getByText('Your family has been asked for new photos 💛')).toBeVisible();
	await expect.poll(() => askCalls(dev)).toBe(1);
	await expect(page.getByRole('toolbar', { name: 'React' })).toBeHidden();

	await page.keyboard.press('Enter');
	await expect(page.getByRole('button', { name: 'Asked today 💛' })).toBeDisabled();
	await page.keyboard.press('Escape');

	// Ben answers with photos: the photo_request change, and his new photo.
	pushChange(dev, { type: 'photo_request', id: 'f-1', photo_request: { id: 'req-1', state: 'answered', created_at: new Date().toISOString(), next_allowed_at: later(23), answered_by_name: 'Ben' } });
	pushChange(dev, { type: 'placement.upsert', placement: devicePlacement('pl-2', { sender: 'Ben', origin: 'send', minutesAgo: 0 }) });
	nudgeDevice(dev);
	await expect(page.getByText('New photos from Ben')).toBeVisible();
	await page.keyboard.press('Enter');
	await expect(page.getByRole('button', { name: 'Ben sent new photos 💛' })).toBeDisabled();
	expect(askCalls(dev)).toBe(1);
});

test('the owner can hide the button', async ({ page }) => {
	await frame(page, { social: { ask_for_photos: false } }, (d) => pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-1') }));
	await page.keyboard.press('Enter');
	await expect(page.getByRole('toolbar', { name: 'React' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Ask for new photos' })).toHaveCount(0);
});

test('offline, the request waits and goes out when the frame is back', async ({ page }) => {
	const dev = await frame(page, {}, (d) => pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-1') }));
	dev.socketDown = true;
	dev.photoRequestStatus = 0;
	const tries = dev.sockets;
	dev.ws!.close({ code: 4000, reason: 'down' });
	// The frame noticed: its reconnect was refused.
	await expect.poll(() => dev.sockets, { timeout: 10_000 }).toBeGreaterThan(tries);

	await page.keyboard.press('Enter');
	await page.getByRole('button', { name: 'Ask for new photos' }).click();
	await expect(page.getByText('Your family will be asked as soon as the frame is back online 💛')).toBeVisible();
	await page.keyboard.press('Enter');
	await expect(page.getByRole('button', { name: 'Asked today 💛' })).toBeDisabled();
	await page.keyboard.press('Escape');

	dev.photoRequestStatus = 201;
	dev.socketDown = false;
	// The first try failed like the network; the second goes out once the websocket is back.
	await expect.poll(() => askCalls(dev), { timeout: 20_000 }).toBe(2);
});

test('the prompt’s title card over the first answer, and the newest comment on a photo', async ({ page }) => {
	await frame(page, { social: { show_comments: true } }, (d) => {
		pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-old', { sender: 'Lies', minutesAgo: 60, caption: 'Garden' }) });
		pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-answer', { sender: 'Ben', minutesAgo: 5, origin: 'feed', promptId: 'pr-1', caption: 'From my window' }) });
		pushChange(d, { type: 'prompts', id: 'f-1', prompts: { generated_at: new Date().toISOString(), prompts: [{ prompt_id: 'pr-1', feed_id: 'fd-1', feed_name: 'Peeters', title: 'Your view right now', week_of: '2026-11-16' }] } });
		pushChange(d, {
			type: 'comments', id: 'pl-old',
			comments: { placement_id: 'pl-old', comments: [{ id: 'c-2', author_name: 'Anna', body: 'Those tulips!', created_at: new Date().toISOString() }, { id: 'c-1', author_name: 'Ben', body: 'Nice', created_at: new Date().toISOString() }] }
		});
	});
	await expect(page.locator('.cap')).toHaveText('From my window');
	await expect(page.getByText('This week · Peeters')).toBeVisible();
	await expect(page.getByText('Your view right now')).toBeVisible();

	await page.keyboard.press('ArrowRight');
	await expect(page.locator('.cap')).toHaveText('Garden');
	await expect(page.getByText('This week · Peeters')).toBeHidden();
	const comment = page.locator('.comment');
	await expect(comment).toContainText('Anna');
	await expect(comment).toContainText('Those tulips!');
	await expect(comment).not.toContainText('Nice');

	// Once per photo per day: not again when it comes round.
	await page.keyboard.press('ArrowRight');
	await expect(page.locator('.cap')).toHaveText('From my window');
	await page.keyboard.press('ArrowRight');
	await expect(page.locator('.cap')).toHaveText('Garden');
	await expect(comment).toBeHidden();
});

test('"New photos from Ben" after the frame wakes up', async ({ page }) => {
	const dev = await frame(page, {}, (d) => pushChange(d, { type: 'placement.upsert', placement: devicePlacement('pl-1', { minutesAgo: 60 }) }));
	dev.ws!.send(JSON.stringify({ type: 'command', id: 'k1', command_type: 'sleep', args: {} }));
	await expect(page.locator('.sleep.on')).toBeAttached();
	pushChange(dev, { type: 'placement.upsert', placement: devicePlacement('pl-2', { sender: 'Ben', origin: 'feed', minutesAgo: 0 }) });
	pushChange(dev, { type: 'placement.upsert', placement: devicePlacement('pl-3', { sender: 'Anna', origin: 'send', minutesAgo: 0 }) });
	nudgeDevice(dev);
	// New photos wake a frame put to sleep by hand; then it says who sent them.
	await expect(page.locator('.sleep.on')).not.toBeAttached();
	await expect(page.getByText('New photos from Ben and Anna')).toBeVisible();
});
