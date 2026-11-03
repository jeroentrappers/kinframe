import { readFileSync, writeFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { emptyState, gradientPng, makeFrame, mockApi, uid, type MockState } from './mock-api';

type S = components['schemas'];

// Client-side preparation (README §5.3 "Prepared uploads") and Live/Motion Photos in the send flow.

function state(): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: new Date().toISOString() };
	st.frames = [makeFrame({ name: 'Oma’s frame', role: 'owner' })];
	return st;
}

async function openSend(page: Page, st: MockState) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
	await page.goto('/send');
	await expect(page.getByRole('checkbox', { name: /Oma’s frame/ })).toBeChecked();
}

async function sent(page: Page, st: MockState): Promise<S['SendCreate']> {
	await page.getByRole('button', { name: /^Send (all|it)/ }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	return st.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST')!.body as S['SendCreate'];
}

/** A tiny MP4 (ftyp, moov, mdat): structurally valid, not playable, so preparing it fails and it goes up as is. */
function fakeMp4(): Buffer {
	const box = (type: string, body: Buffer) => {
		const h = Buffer.alloc(8);
		h.writeUInt32BE(8 + body.length);
		h.write(type, 4, 'latin1');
		return Buffer.concat([h, body]);
	};
	return Buffer.concat([box('ftyp', Buffer.from('isom\0\0\x02\0isommp41', 'latin1')), box('moov', Buffer.alloc(16)), box('mdat', Buffer.alloc(256, 7))]);
}

test('photos are prepared in the browser: WebP ≤ 2560 px, declared size, metadata-free', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles({ name: 'big.png', mimeType: 'image/png', buffer: await gradientPng(3000, 2000, 30) });
	const body = await sent(page, st);
	const item = body.items[0]!;
	const up = st.uploads[item.upload_id]!;
	expect(up.prepared).toBe('photo-master-v1');
	expect(up.filename).toMatch(/^big\.(webp|jpg)$/);
	expect(item).toMatchObject({ width: 2560, height: 1707 });
	expect(up.offset).toBe(up.length);
	const bytes = Buffer.concat(up.data!);
	const webp = bytes.subarray(0, 4).toString('latin1') === 'RIFF' && bytes.subarray(8, 12).toString('latin1') === 'WEBP';
	expect(webp || (bytes[0] === 0xff && bytes[1] === 0xd8)).toBe(true);
	expect(bytes.includes('Exif')).toBe(false);
});

test('Live Photo pair: still + same-name clip become one item; toggle sends the still only', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles([
		{ name: 'IMG_0042.png', mimeType: 'image/png', buffer: await gradientPng(400, 300, 50) },
		{ name: 'IMG_0042.MOV', mimeType: 'video/quicktime', buffer: fakeMp4() },
		{ name: 'IMG_0043.png', mimeType: 'image/png', buffer: await gradientPng(300, 400, 90) },
		{ name: 'IMG_0043.MOV', mimeType: 'video/quicktime', buffer: fakeMp4() }
	]);
	await expect(page.getByLabel('Caption for item 2')).toBeVisible();
	await expect(page.getByLabel('Caption for item 3')).toHaveCount(0);
	const toggles = page.getByRole('button', { name: /Live Photo/ });
	await expect(toggles).toHaveCount(2);
	await toggles.nth(1).click();
	await expect(toggles.nth(1)).toHaveAttribute('aria-pressed', 'false');

	const body = await sent(page, st);
	const [live, still] = body.items;
	expect(live!.live_video_upload_id).toBeTruthy();
	expect(st.uploads[live!.live_video_upload_id!]!.filename).toMatch(/^IMG_0042\.(MOV|mp4)$/);
	expect(still!.live).toBe(false);
	expect(still!.live_video_upload_id).toBeUndefined();
	expect(Object.keys(st.uploads)).toHaveLength(3);
});

test('Android Motion Photo: the embedded clip is split off and sent as Live', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	// A real JPEG from the browser, with Motion Photo XMP inserted and an MP4 appended.
	const jpeg = Buffer.from(
		await page.evaluate(() => {
			const c = document.createElement('canvas');
			c.width = 320;
			c.height = 240;
			const ctx = c.getContext('2d')!;
			ctx.fillStyle = '#c84';
			ctx.fillRect(0, 0, 320, 240);
			return c.toDataURL('image/jpeg', 0.9).split(',')[1]!;
		}),
		'base64'
	);
	const mp4 = fakeMp4();
	const xmp = Buffer.from(
		'http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description GCamera:MotionPhoto="1"><Container:Directory><rdf:Seq>' +
			'<rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>' +
			`<rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${mp4.length}"/></rdf:li>` +
			'</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>',
		'latin1'
	);
	const app1 = Buffer.alloc(4);
	app1.writeUInt16BE(0xffe1);
	app1.writeUInt16BE(xmp.length + 2, 2);
	const motion = Buffer.concat([jpeg.subarray(0, 2), app1, xmp, jpeg.subarray(2), mp4]);
	await page.getByTestId('file-input').setInputFiles({ name: 'PXL_20261020.MP.jpg', mimeType: 'image/jpeg', buffer: motion });
	await expect(page.getByRole('button', { name: /Live Photo/ })).toBeVisible();

	const body = await sent(page, st);
	const item = body.items[0]!;
	expect(item.live_video_upload_id).toBeTruthy();
	const clip = st.uploads[item.live_video_upload_id!]!;
	expect(clip.filename).toBe('PXL_20261020.MP.mp4');
	expect(clip.length).toBe(mp4.length);
	// The still never carries the video bytes.
	expect(st.uploads[item.upload_id]!.length).toBeLessThan(motion.length - mp4.length + 1);
});

test('videos are converted with WebCodecs where the browser can encode H.264', async ({ page }, info) => {
	const st = state();
	await openSend(page, st);
	const h264 =await page.evaluate(async () =>
		typeof VideoEncoder === 'undefined'
			? false
			: ((await VideoEncoder.isConfigSupported({ codec: 'avc1.42001f', width: 192, height: 108 })).supported ?? false)
	);
	await page.getByTestId('file-input').setInputFiles({ name: 'clip.webm', mimeType: 'video/webm', buffer: readFileSync('tests/fixtures/clip.webm') });
	await expect(page.locator('.duration')).toContainText('0:08');
	const item = (await sent(page, st)).items[0]!;
	const up = st.uploads[item.upload_id]!;
	if (!h264) {
		expect(up.prepared).toBeUndefined();
		expect(up.filename).toBe('clip.webm');
		return;
	}
	expect(up.prepared).toBe('video-master-v1');
	expect(up.filename).toBe('clip.mp4');
	expect(item).toMatchObject({ width: 192, height: 108, include_audio: true });
	expect(item.duration_ms).toBeGreaterThan(7800);
	expect(item.trim).toBeUndefined();
	// An MP4 with the index first (faststart), ready for the server's stream-copy fast path.
	const bytes = Buffer.concat(up.data!);
	writeFileSync(info.outputPath('prepared.mp4'), bytes);
	expect(bytes.subarray(4, 8).toString('latin1')).toBe('ftyp');
	expect(bytes.indexOf('moov')).toBeLessThan(bytes.indexOf('mdat'));
});
