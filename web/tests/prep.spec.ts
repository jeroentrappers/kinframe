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

/** A real 1 s, 192×108, 30 fps H.264 clip: within the master limits, and convertible where WebCodecs exists. */
const motionMp4 = () => readFileSync('tests/fixtures/motion.mp4');

/** A tiny MP4 (ftyp, moov, mdat): structurally valid but unreadable, so it can't be prepared nor checked. */
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
		{ name: 'IMG_0042.MOV', mimeType: 'video/quicktime', buffer: motionMp4() },
		{ name: 'IMG_0043.png', mimeType: 'image/png', buffer: await gradientPng(300, 400, 90) },
		{ name: 'IMG_0043.MOV', mimeType: 'video/quicktime', buffer: motionMp4() }
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
	const mp4 = motionMp4();
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
	// Converted (muted) where WebCodecs can, else the split-off clip as is: it is within the limits.
	if (!clip.prepared) expect(clip.length).toBe(mp4.length);
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

// ---------------------------------------------------------------- WebAssembly fallback, HEIC, strict mode

/** Decodes an uploaded JPEG in the page and samples pixels (x, y as fractions of the size). */
async function samplePixels(page: Page, bytes: Buffer, points: [number, number][]): Promise<number[][]> {
	return page.evaluate(
		async ({ b64, points }) => {
			const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
			const bmp = await createImageBitmap(new Blob([bin], { type: 'image/jpeg' }));
			const c = new OffscreenCanvas(bmp.width, bmp.height);
			const ctx = c.getContext('2d')!;
			ctx.drawImage(bmp, 0, 0);
			return points.map(([x, y]) => [...ctx.getImageData(Math.floor(x * bmp.width), Math.floor(y * bmp.height), 1, 1).data.slice(0, 3)]);
		},
		{ b64: bytes.toString('base64'), points }
	);
}

const near = (px: number[], want: number[]) => px.every((v, i) => Math.abs(v - want[i]!) < 40);

function expectPlainJpeg(bytes: Buffer) {
	expect([bytes[0], bytes[1], bytes[2]]).toEqual([0xff, 0xd8, 0xff]);
	expect(bytes.includes('Exif')).toBe(false);
	expect(bytes.includes('ICC_PROFILE')).toBe(false);
}

test('HEIC is decoded with libheif (rotation applied) where the browser can’t', async ({ page, browserName }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles({ name: 'IMG_0001.HEIC', mimeType: 'image/heic', buffer: readFileSync('tests/fixtures/heic-rotated.heic') });
	// The browser can't show HEIC, but the prepared master becomes the preview.
	await expect(page.locator('.draft img')).toBeVisible({ timeout: 20_000 });
	const item = (await sent(page, st)).items[0]!;
	const up = st.uploads[item.upload_id]!;
	expect(up.prepared).toBe('photo-master-v1');
	// 4000×3000 stored, rotated 90° by `irot`: 3000×4000 shown, so 1920×2560.
	expect(item).toMatchObject({ width: 1920, height: 2560 });
	const bytes = Buffer.concat(up.data!);
	// Safari decodes HEIC itself (WebP or JPEG out); Chromium and Firefox go through libheif (JPEG).
	if (browserName !== 'webkit') {
		expect(up.filename).toBe('IMG_0001.jpg');
		expectPlainJpeg(bytes);
		const [top, bottom] = await samplePixels(page, bytes, [[0.5, 0.1], [0.5, 0.9]]);
		expect(near(top!, [32, 32, 208]), `top ${top}`).toBe(true);
		expect(near(bottom!, [208, 32, 32]), `bottom ${bottom}`).toBe(true);
	}
});

test('multi-image HEIC: the primary image is sent', async ({ page, browserName }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles({ name: 'burst.heic', mimeType: 'image/heic', buffer: readFileSync('tests/fixtures/heic-multi.heic') });
	const item = (await sent(page, st)).items[0]!;
	expect(item).toMatchObject({ width: 400, height: 600 });
	const up = st.uploads[item.upload_id]!;
	if (browserName !== 'webkit') {
		expect(up.filename).toBe('burst.jpg');
		const [px] = await samplePixels(page, Buffer.concat(up.data!), [[0.5, 0.5]]);
		expect(near(px!, [32, 32, 208]), `${px}`).toBe(true);
	}
});

test('the WebAssembly fallback prepares large PNG and WebP photos', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	await page.evaluate(() => localStorage.setItem('kinframe.prep', 'wasm'));
	const webp = Buffer.from(
		await page.evaluate(async () => {
			const c = new OffscreenCanvas(4000, 3000);
			const ctx = c.getContext('2d')!;
			const g = ctx.createLinearGradient(0, 0, 4000, 0);
			g.addColorStop(0, '#d02020');
			g.addColorStop(1, '#2020d0');
			ctx.fillStyle = g;
			ctx.fillRect(0, 0, 4000, 3000);
			const blob = await c.convertToBlob({ type: 'image/webp', quality: 0.8 });
			return btoa(String.fromCharCode(...new Uint8Array(await blob.arrayBuffer())));
		}),
		'base64'
	);
	await page.getByTestId('file-input').setInputFiles([
		{ name: 'wide.webp', mimeType: 'image/webp', buffer: webp },
		{ name: 'big.png', mimeType: 'image/png', buffer: await gradientPng(3000, 2000, 30) }
	]);
	const [w, p] = (await sent(page, st)).items;
	expect(w).toMatchObject({ width: 2560, height: 1920 });
	expect(p).toMatchObject({ width: 2560, height: 1707 });
	for (const item of [w!, p!]) {
		const up = st.uploads[item.upload_id]!;
		expect(up.prepared).toBe('photo-master-v1');
		expect(up.filename).toMatch(/\.jpg$/);
		expectPlainJpeg(Buffer.concat(up.data!));
	}
	const [left, right] = await samplePixels(page, Buffer.concat(st.uploads[w!.upload_id]!.data!), [[0.02, 0.5], [0.98, 0.5]]);
	expect(near(left!, [208, 32, 32]) && near(right!, [32, 32, 208]), `${left} ${right}`).toBe(true);
});

/** A JPEG whose header says w×h but whose data is garbage: no decoder can prepare it. */
function brokenJpeg(w: number, h: number): Buffer {
	const sof = Buffer.from([0xff, 0xc0, 0, 17, 8, h >> 8, h & 0xff, w >> 8, w & 0xff, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]);
	return Buffer.concat([Buffer.from([0xff, 0xd8]), sof, Buffer.from([0xff, 0xda, 0, 2]), Buffer.alloc(4096, 0x5a), Buffer.from([0xff, 0xd9])]);
}

test('strict: a photo that can’t be prepared goes up as is only within the limits', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles([
		{ name: 'small.jpg', mimeType: 'image/jpeg', buffer: brokenJpeg(800, 600) },
		{ name: 'huge.jpg', mimeType: 'image/jpeg', buffer: brokenJpeg(4000, 3000) }
	]);
	await expect(page.getByRole('alert').filter({ hasText: 'This browser can’t prepare this photo' })).toHaveCount(1, { timeout: 20_000 });
	await expect(page.getByRole('button', { name: /^Send (all|it)/ }).first()).toBeDisabled();
	await page.getByRole('button', { name: 'Remove item 2' }).click();
	const item = (await sent(page, st)).items[0]!;
	const up = st.uploads[item.upload_id]!;
	expect(up.prepared).toBeUndefined();
	expect(up.filename).toBe('small.jpg');
});

test('strict: a Live Photo clip that can’t be converted blocks the item until Live is off', async ({ page }) => {
	const st = state();
	await openSend(page, st);
	await page.getByTestId('file-input').setInputFiles([
		{ name: 'IMG_0050.png', mimeType: 'image/png', buffer: await gradientPng(400, 300, 50) },
		{ name: 'IMG_0050.MOV', mimeType: 'video/quicktime', buffer: fakeMp4() }
	]);
	await expect(page.getByRole('alert').filter({ hasText: 'Turn Live off' })).toBeVisible({ timeout: 20_000 });
	await page.getByRole('button', { name: /Live Photo/ }).click();
	await expect(page.getByRole('alert')).toHaveCount(0);
	const item = (await sent(page, st)).items[0]!;
	expect(item.live).toBe(false);
	expect(Object.keys(st.uploads)).toHaveLength(1);
});
