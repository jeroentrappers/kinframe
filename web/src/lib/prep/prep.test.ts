import { describe, expect, it } from 'vitest';
import { captureTimeFromBytes, exifTimeToIso } from './exif';
import { pairLive } from './live';
import { findMotionVideo, mp4At, splitMotionPhoto } from './motionPhoto';
import { preparePhoto, preparedPhotoName, type PhotoPrepDeps } from './photo';
import { photoTarget, preparedName, usePrepared, videoBitrate, videoFrameRate, videoTarget } from './plan';
import { sendItemBody, type SendItemInput } from './sendItem';

// ---------------------------------------------------------------- fixtures

const enc = new TextEncoder();

function concat(...parts: (Uint8Array | number[])[]): Uint8Array {
	const arrs = parts.map((p) => (p instanceof Uint8Array ? p : Uint8Array.from(p)));
	const out = new Uint8Array(arrs.reduce((n, a) => n + a.length, 0));
	let o = 0;
	for (const a of arrs) {
		out.set(a, o);
		o += a.length;
	}
	return out;
}

function u16le(v: number) {
	return [v & 0xff, v >> 8];
}
function u32le(v: number) {
	return [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >>> 24];
}
function u32be(v: number) {
	return [v >>> 24, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff];
}

/** A JPEG whose APP1 holds EXIF DateTimeOriginal (+ optional OffsetTimeOriginal). */
function exifJpeg(dto: string, offset?: string): Uint8Array {
	// TIFF: header(8) IFD0(2+12+4=18) ExifIFD(2+n*12+4) strings
	const ifd0 = 8;
	const exifIfd = ifd0 + 18;
	const nExif = offset ? 2 : 1;
	const strs = exifIfd + 2 + nExif * 12 + 4;
	const dtoBytes = concat(enc.encode(dto), [0]);
	const offBytes = offset ? concat(enc.encode(offset), [0]) : new Uint8Array();
	const entry = (tag: number, type: number, count: number, value: number[]) => [...u16le(tag), ...u16le(type), ...u32le(count), ...value];
	const tiff = concat(
		enc.encode('II'),
		[0x2a, 0],
		u32le(ifd0),
		u16le(1),
		entry(0x8769, 4, 1, u32le(exifIfd)),
		u32le(0),
		u16le(nExif),
		entry(0x9003, 2, dtoBytes.length, u32le(strs)),
		offset ? entry(0x9011, 2, offBytes.length, u32le(strs + dtoBytes.length)) : [],
		u32le(0),
		dtoBytes,
		offBytes
	);
	const payload = concat(enc.encode('Exif\0\0'), tiff);
	return concat([0xff, 0xd8, 0xff, 0xe1], [(payload.length + 2) >> 8, (payload.length + 2) & 0xff], payload, [0xff, 0xd9]);
}

function box(type: string, body: Uint8Array | number[] = []): Uint8Array {
	const b = body instanceof Uint8Array ? body : Uint8Array.from(body);
	return concat(u32be(8 + b.length), enc.encode(type), b);
}

const MP4 = concat(box('ftyp', concat(enc.encode('isom'), u32be(512), enc.encode('isommp41'))), box('moov', [1, 2, 3, 4]), box('mdat', new Uint8Array(64).fill(7)));

/** A Motion Photo JPEG: XMP in APP1, the image, then the MP4 appended. */
function motionPhoto(xmpAttrs: (len: number) => string): Uint8Array {
	const xmp = `<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description ${xmpAttrs(MP4.length)}/></rdf:RDF></x:xmpmeta>`;
	const payload = concat(enc.encode('http://ns.adobe.com/xap/1.0/\0'), enc.encode(xmp));
	const jpeg = concat([0xff, 0xd8, 0xff, 0xe1], [(payload.length + 2) >> 8, (payload.length + 2) & 0xff], payload, [0xff, 0xda, 0, 2, 1, 2, 3], [0xff, 0xd9]);
	return concat(jpeg, MP4);
}

// ---------------------------------------------------------------- tests

describe('EXIF capture time', () => {
	it('reads DateTimeOriginal with its offset', () => {
		expect(captureTimeFromBytes(exifJpeg('2021:06:15 10:30:00', '+02:00'))).toBe('2021-06-15T08:30:00Z');
	});
	it('treats a time without offset as wall clock (UTC), like the server', () => {
		expect(captureTimeFromBytes(exifJpeg('2019:12:31 23:59:58'))).toBe('2019-12-31T23:59:58Z');
	});
	it('returns null without EXIF or for junk', () => {
		expect(captureTimeFromBytes(Uint8Array.from([0xff, 0xd8, 0xff, 0xd9]))).toBeNull();
		expect(exifTimeToIso('0000:00:00 00:00:00', null)).toBeNull();
		expect(exifTimeToIso('    :  :     :  :  ', null)).toBeNull();
		expect(exifTimeToIso('2020:01:02 03:04:05', '-05:30')).toBe('2020-01-02T08:34:05Z');
	});
});

describe('Motion Photos', () => {
	it('finds the clip from Container:Directory Item:Length', () => {
		const file = motionPhoto(
			(len) =>
				`GCamera:MotionPhoto="1"><Container:Directory><rdf:Seq><rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li><rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${len}"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description><rdf:Description a="b"`
		);
		const r = findMotionVideo(file);
		expect(r).toEqual({ start: file.length - MP4.length, end: file.length });
		const parts = splitMotionPhoto(file)!;
		expect(parts.clip).toEqual(MP4);
		expect([...parts.still.slice(-2)]).toEqual([0xff, 0xd9]);
	});
	it('finds the clip from the legacy MicroVideoOffset', () => {
		const file = motionPhoto((len) => `GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${len}"`);
		expect(findMotionVideo(file)?.start).toBe(file.length - MP4.length);
	});
	it('recovers when the offset is wrong by scanning for ftyp', () => {
		const file = motionPhoto((len) => `GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${len + 5}"`);
		expect(findMotionVideo(file)?.start).toBe(file.length - MP4.length);
	});
	it('ignores ordinary photos and unflagged appended data', () => {
		expect(findMotionVideo(exifJpeg('2021:06:15 10:30:00'))).toBeNull();
		const unflagged = concat(exifJpeg('2021:06:15 10:30:00'), MP4);
		expect(findMotionVideo(unflagged)).toBeNull();
	});
	it('validates the MP4 structure', () => {
		const file = concat(new Uint8Array(10), MP4);
		expect(mp4At(file, 10)).toEqual({ start: 10, end: file.length });
		expect(mp4At(file, 11)).toBeNull();
		expect(mp4At(concat(new Uint8Array(10), box('ftyp', concat(enc.encode('isom'), u32be(0))), box('free')), 10)).toBeNull();
	});
});

describe('Live Photo pairing', () => {
	it('pairs a still and a clip with the same base name', () => {
		const files = [{ name: 'IMG_1.HEIC' }, { name: 'IMG_2.jpg' }, { name: 'img_1.MOV' }, { name: 'clip.mp4' }];
		const out = pairLive(files);
		expect(out.map((e) => [e.file.name, e.clip?.name ?? null])).toEqual([
			['IMG_1.HEIC', 'img_1.MOV'],
			['IMG_2.jpg', null],
			['clip.mp4', null]
		]);
	});
	it('pairs at most one clip per still and leaves videos without a still alone', () => {
		const out = pairLive([{ name: 'a.mov' }, { name: 'a.mp4' }, { name: 'a.jpg' }]);
		expect(out.map((e) => [e.file.name, e.clip?.name ?? null])).toEqual([
			['a.mp4', null],
			['a.jpg', 'a.mov']
		]);
	});
});

describe('preparation plan', () => {
	it('scales photos to ≤ 2560 px, never up', () => {
		expect(photoTarget(4032, 3024)).toEqual({ width: 2560, height: 1920 });
		expect(photoTarget(3024, 4032)).toEqual({ width: 1920, height: 2560 });
		expect(photoTarget(800, 600)).toEqual({ width: 800, height: 600 });
	});
	it('fits videos in 1080p either way, with even sizes', () => {
		expect(videoTarget(3840, 2160)).toEqual({ width: 1920, height: 1080 });
		expect(videoTarget(2160, 3840)).toEqual({ width: 1080, height: 1920 });
		expect(videoTarget(1281, 721)).toEqual({ width: 1280, height: 720 });
		expect(videoTarget(1440, 1440)).toEqual({ width: 1080, height: 1080 });
	});
	it('keeps the bitrate under the frame rendition budget', () => {
		expect(videoBitrate({ width: 1920, height: 1080 })).toBe(7_257_600);
		expect(videoBitrate({ width: 1280, height: 720 })).toBe(3_225_600);
		expect(videoBitrate({ width: 1920, height: 1080 })).toBeLessThanOrEqual(1920 * 1080 * 4);
	});
	it('drops high frame rates to 30', () => {
		expect(videoFrameRate(59.94)).toBe(30);
		expect(videoFrameRate(30)).toBeUndefined();
		expect(videoFrameRate(null)).toBeUndefined();
	});
	it('uploads a prepared photo only when it is smaller', () => {
		expect(usePrepared(5_000_000, 900_000)).toBe(true);
		expect(usePrepared(90_000, 120_000)).toBe(false);
		expect(preparedName('IMG_1.HEIC', 'webp')).toBe('IMG_1.webp');
		expect(preparedPhotoName('x.png', 'image/jpeg')).toBe('x.jpg');
	});
});

describe('photo preparation (injected platform)', () => {
	function fakes(webp: boolean) {
		const calls: { draw?: [number, number]; encodes: string[]; closed: boolean } = { encodes: [], closed: false };
		const deps: PhotoPrepDeps = {
			decode: async () => ({ width: 4000, height: 3000, source: 'bitmap', close: () => (calls.closed = true) }),
			surface: (w, h) => ({
				draw: (src, dw, dh) => {
					expect(src).toBe('bitmap');
					expect([dw, dh]).toEqual([w, h]);
					calls.draw = [dw, dh];
				},
				encode: async (type) => {
					calls.encodes.push(type);
					const out = type === 'image/webp' && !webp ? 'image/png' : type;
					return new Blob([new Uint8Array(10)], { type: out });
				}
			})
		};
		return { deps, calls };
	}
	it('encodes WebP at ≤ 2560 px and releases the bitmap', async () => {
		const { deps, calls } = fakes(true);
		const p = await preparePhoto(new Blob([]), deps);
		expect(p).toMatchObject({ mime: 'image/webp', width: 2560, height: 1920 });
		expect(calls).toEqual({ draw: [2560, 1920], encodes: ['image/webp'], closed: true });
	});
	it('falls back to JPEG when the browser cannot encode WebP', async () => {
		const { deps, calls } = fakes(false);
		const p = await preparePhoto(new Blob([]), deps);
		expect(p.mime).toBe('image/jpeg');
		expect(calls.encodes).toEqual(['image/webp', 'image/jpeg']);
	});
	it('rejects (upload the original) when decoding fails', async () => {
		const { deps } = fakes(true);
		await expect(preparePhoto(new Blob([]), { ...deps, decode: () => Promise.reject(new Error('HEIC')) })).rejects.toThrow('HEIC');
	});
});

describe('send item body', () => {
	const base: SendItemInput = {
		kind: 'photo',
		uploadId: 'up1',
		caption: null,
		fit: 'fill',
		focal: { x: 0.5, y: 0.5 },
		trim: undefined,
		includeAudio: true,
		prepared: null,
		capturedAt: '2021-06-15T08:30:00Z',
		hasLive: false,
		liveUploadId: null
	};
	it('declares a prepared photo’s size', () => {
		expect(sendItemBody({ ...base, prepared: { profile: 'photo-master-v1', width: 2560, height: 1920 } })).toEqual({
			upload_id: 'up1',
			caption: null,
			fit: 'fill',
			focal_point: { x: 0.5, y: 0.5 },
			width: 2560,
			height: 1920,
			captured_at: '2021-06-15T08:30:00Z'
		});
	});
	it('never sends a trim with a prepared video (it is already applied)', () => {
		const trim = { start_ms: 1000, end_ms: 5000 };
		const prepared = { profile: 'video-master-v1' as const, width: 1920, height: 1080, durationMs: 4000 };
		expect(sendItemBody({ ...base, kind: 'video', trim, prepared, includeAudio: false })).toMatchObject({ include_audio: false, duration_ms: 4000 });
		expect(sendItemBody({ ...base, kind: 'video', trim, prepared })).not.toHaveProperty('trim');
		expect(sendItemBody({ ...base, kind: 'video', trim })).toMatchObject({ trim });
	});
	it('links the motion clip, or sends the still only', () => {
		expect(sendItemBody({ ...base, hasLive: true, liveUploadId: 'up2' })).toMatchObject({ live_video_upload_id: 'up2' });
		const still = sendItemBody({ ...base, hasLive: true, liveUploadId: null });
		expect(still).toMatchObject({ live: false });
		expect(still).not.toHaveProperty('live_video_upload_id');
		expect(sendItemBody(base)).not.toHaveProperty('live');
	});
});
