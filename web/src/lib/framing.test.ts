import { describe, expect, it } from 'vitest';
import {
	boxAspectOf,
	clampView,
	cropRect,
	cropStyle,
	framingOrNull,
	looksSoft,
	moveView,
	orientationOf,
	pairable,
	pickView,
	recentre,
	sendFocal,
	tileFocal,
	zoomView
} from './framing';
import { framedKenBurnsKeys, FRAMED_MAX_DRIFT, FRAMED_MAX_SCALE, kenBurnsAt, kenBurnsKeyframes } from './kenburns';
import { coverPosition } from './logic';
import { tileCropStyle, tileObjectPosition, tileView } from './tvstage';
import { sendItemBody } from './prep/sendItem';

const close = (a: number, b: number) => expect(a).toBeCloseTo(b, 6);

describe('framing geometry (docs/spec/framing.md §3)', () => {
	it('is the plain cover crop at zoom 1 (FocalCrop.fillSource)', () => {
		// The Android frame's FocalCropTest: 1920×1280 on 1280×800, centred.
		const r = cropRect(1920, 1280, 1280, 800, { x: 0.5, y: 0.5, zoom: 1 });
		close(r.width, 1920);
		close(r.height, 1200);
		close(r.left, 0);
		close(r.top, 40);
		// Focal at the top: clamped to the edge.
		expect(cropRect(1920, 1280, 1280, 800, { x: 0.5, y: 0, zoom: 1 }).top).toBe(0);
		// Portrait photo on a landscape screen, focal near the bottom.
		const p = cropRect(1280, 1920, 1280, 800, { x: 0.5, y: 0.95, zoom: 1 });
		close(p.top + p.height, 1920);
	});

	it('zoom 2 shows half the width and half the height of the cover crop', () => {
		const r = cropRect(4000, 3000, 1600, 1000, { x: 0.5, y: 0.5, zoom: 2 });
		close(r.width, 2000);
		close(r.height, 1250);
		close(r.left, 1000);
		close(r.top, 875);
	});

	it('never leaves the photo', () => {
		const r = cropRect(4000, 3000, 1600, 1000, { x: 0.99, y: 0.01, zoom: 3 });
		close(r.left + r.width, 4000);
		close(r.top, 0);
		// Out-of-range zoom is clamped to 1..4.
		expect(cropRect(4000, 3000, 1600, 1000, { x: 0.5, y: 0.5, zoom: 9 }).width).toBeCloseTo(1000, 6);
		expect(cropRect(4000, 3000, 1600, 1000, { x: 0.5, y: 0.5, zoom: 0.2 }).width).toBeCloseTo(4000, 6);
	});

	it('works for any screen size of the same shape', () => {
		const a = cropRect(3000, 2000, 1920, 1080, { x: 0.3, y: 0.6, zoom: 2.5 });
		const b = cropRect(3000, 2000, 16 / 9, 1, { x: 0.3, y: 0.6, zoom: 2.5 });
		close(a.left, b.left);
		close(a.width, b.width);
	});

	it('draws the crop with a positioned image that matches object-position at zoom 1', () => {
		const style = cropStyle(3, 2, 16 / 10, { x: 0.2, y: 0.5, zoom: 1 });
		const pos = coverPosition({ x: 0.2, y: 0.5 }, 3 / 2, 16 / 10);
		// object-position p% puts the image's left edge at p% of (box − image).
		const width = Number(/width:([\d.]+)%/.exec(style)![1]);
		const left = Number(/left:(-?[\d.]+)%/.exec(style)![1]);
		expect(left).toBeCloseTo((pos.x / 100) * (100 - width), 2);
		const z = cropStyle(3, 2, 16 / 10, { x: 0.5, y: 0.5, zoom: 2 });
		expect(z).toMatch(/^width:[\d.]+%;height:[\d.]+%;left:-[\d.]+%;top:-[\d.]+%$/);
		expect(Number(/width:([\d.]+)%/.exec(z)![1])).toBeCloseTo(2 * width, 2);
	});

	it('names orientations like the spec: a square screen is landscape', () => {
		expect(orientationOf(1920, 1080)).toBe('landscape');
		expect(orientationOf(800, 800)).toBe('landscape');
		expect(orientationOf(800, 1280)).toBe('portrait');
		expect(boxAspectOf('landscape', 10 / 16)).toBeCloseTo(1.6);
		expect(boxAspectOf('portrait', 16 / 10)).toBeCloseTo(0.625);
	});
});

describe('which view a screen uses (§3.1)', () => {
	const item = { framing: { landscape: { x: 0.2, y: 0.3, zoom: 2 } }, focal: { x: 0.6, y: 0.4 } };

	it('prefers the override, then the focal override, then the item framing, then its focal point', () => {
		const over = { ...item, framingOverride: { landscape: { x: 0.9, y: 0.9, zoom: 3 } }, focalOverride: { x: 0.1, y: 0.1 } };
		expect(pickView(over, 'landscape')).toEqual({ view: { x: 0.9, y: 0.9, zoom: 3 }, chosen: true });
		expect(pickView(over, 'portrait')).toEqual({ view: { x: 0.1, y: 0.1, zoom: 1 }, chosen: false });
		expect(pickView(item, 'landscape')).toEqual({ view: { x: 0.2, y: 0.3, zoom: 2 }, chosen: true });
		expect(pickView(item, 'portrait')).toEqual({ view: { x: 0.6, y: 0.4, zoom: 1 }, chosen: false });
		expect(pickView({}, 'portrait')).toEqual({ view: { x: 0.5, y: 0.5, zoom: 1 }, chosen: false });
	});

	it('pairs and collages use the centre and keep zoomed photos alone (§3.5)', () => {
		const l = pickView(item, 'landscape');
		expect(pairable(l)).toBe(false);
		expect(tileFocal(l)).toEqual({ x: 0.2, y: 0.3 });
		expect(pairable(pickView(item, 'portrait'))).toBe(true);
		expect(pairable({ view: { x: 0.5, y: 0.5, zoom: 1 }, chosen: true })).toBe(true);
	});
});

describe('the editor (§4)', () => {
	it('clamps the centre so the window stays on the photo, and the zoom to 1..4', () => {
		expect(clampView({ x: 0, y: 0, zoom: 2 }, 4 / 3, 16 / 10)).toEqual({ x: 0.25, y: 0.208, zoom: 2 });
		expect(clampView({ x: 0.5, y: 0.5, zoom: 7 }, 4 / 3, 16 / 10).zoom).toBe(4);
		expect(clampView({ x: 0.5, y: 0.5, zoom: 0 }, 4 / 3, 16 / 10).zoom).toBe(1);
		// At zoom 1 a 4:3 photo on 16:10 fills the width: x can't move.
		expect(clampView({ x: 0.1, y: 0.5, zoom: 1 }, 4 / 3, 16 / 10).x).toBe(0.5);
	});

	it('moves by a share of the visible part and zooms around the centre', () => {
		const v = moveView({ x: 0.5, y: 0.5, zoom: 2 }, 0.1, 0, 4 / 3, 16 / 10);
		expect(v.x).toBeCloseTo(0.55, 3);
		expect(v.y).toBe(0.5);
		expect(zoomView({ x: 0.5, y: 0.5, zoom: 1 }, 2.5, 4 / 3, 16 / 10)).toEqual({ x: 0.5, y: 0.5, zoom: 2.5 });
	});

	it('moves both views to a new focal point and keeps their zoom', () => {
		const f = { landscape: { x: 0.2, y: 0.2, zoom: 2 }, portrait: { x: 0.8, y: 0.8, zoom: 3 } };
		const r = recentre(f, { x: 0.5, y: 0.5 }, 1, 16 / 10)!;
		expect(r.landscape).toEqual({ x: 0.5, y: 0.5, zoom: 2 });
		expect(r.portrait).toEqual({ x: 0.5, y: 0.5, zoom: 3 });
		expect(recentre(null, { x: 0.5, y: 0.5 }, 1)).toBeNull();
		expect(recentre({ portrait: f.portrait }, { x: 0.5, y: 0.5 }, 1)).not.toHaveProperty('landscape');
	});

	it('sends the landscape centre, else the portrait one, else the tapped point', () => {
		const tapped = { x: 0.1, y: 0.9 };
		expect(sendFocal({ landscape: { x: 0.3, y: 0.4, zoom: 2 }, portrait: { x: 0.7, y: 0.6, zoom: 2 } }, tapped)).toEqual({ x: 0.3, y: 0.4 });
		expect(sendFocal({ portrait: { x: 0.7, y: 0.6, zoom: 2 } }, tapped)).toEqual({ x: 0.7, y: 0.6 });
		expect(sendFocal(null, tapped)).toEqual(tapped);
		expect(framingOrNull({})).toBeNull();
	});

	it('sends only the changed orientations, for photos', () => {
		const base = {
			kind: 'photo' as const,
			uploadId: 'u',
			caption: null,
			fit: 'fill' as const,
			focal: { x: 0.3, y: 0.4 },
			trim: undefined,
			includeAudio: true,
			prepared: null,
			capturedAt: null,
			hasLive: false,
			liveUploadId: null
		};
		const framing = { landscape: { x: 0.3, y: 0.4, zoom: 2 } };
		expect(sendItemBody({ ...base, framing })).toMatchObject({ framing, focal_point: { x: 0.3, y: 0.4 } });
		expect(sendItemBody({ ...base, framing: null })).not.toHaveProperty('framing');
		expect(sendItemBody({ ...base, framing: {} })).not.toHaveProperty('framing');
		expect(sendItemBody({ ...base, kind: 'video', framing })).not.toHaveProperty('framing');
	});

	it('warns softly below 960 master pixels across the long side', () => {
		// 4000×3000 → master 2560×1920; 16:10 cover crop is 2560 wide: 2× → 1280, 3× → 853.
		expect(looksSoft({ x: 0.5, y: 0.5, zoom: 2 }, 4000, 3000, 16 / 10)).toBe(false);
		expect(looksSoft({ x: 0.5, y: 0.5, zoom: 3 }, 4000, 3000, 16 / 10)).toBe(true);
		// Portrait screen: the long side is the height (1920 at zoom 1).
		expect(looksSoft({ x: 0.5, y: 0.5, zoom: 2 }, 4000, 3000, 10 / 16)).toBe(false);
		expect(looksSoft({ x: 0.5, y: 0.5, zoom: 2.1 }, 4000, 3000, 10 / 16)).toBe(true);
		expect(looksSoft({ x: 0.5, y: 0.5, zoom: 4 }, null, null, 16 / 10)).toBe(false);
	});
});

describe('Ken Burns inside a chosen view (§3.4)', () => {
	it('starts and ends at the view, scaled 1.00..1.08 with at most 3% drift', () => {
		for (const id of ['a', 'b', '0192f0a0-0001-7000-8000-000000000001']) {
			const keys = framedKenBurnsKeys(id);
			expect(keys).toEqual(framedKenBurnsKeys(id));
			for (let t = 0; t <= 2; t += 0.05) {
				const k = kenBurnsAt(keys, t);
				expect(k.scale).toBeGreaterThanOrEqual(1);
				expect(k.scale).toBeLessThanOrEqual(FRAMED_MAX_SCALE + 1e-6);
				expect(Math.hypot(k.x, k.y)).toBeLessThanOrEqual(FRAMED_MAX_DRIFT + 1e-6);
			}
			expect(kenBurnsAt(keys, 0)).toEqual({ scale: 1, x: 0, y: 0 });
			const end = kenBurnsAt(keys, 2);
			expect(end.scale).toBeCloseTo(1, 6);
			expect(Math.abs(end.x)).toBeLessThan(1e-9);
		}
		// Played with half the hold as a segment: back at the view when the slide ends.
		const frames = kenBurnsKeyframes(framedKenBurnsKeys('a'), 30_000, 15_000);
		expect(frames[0]!.transform).toBe('translate(0.000%, 0.000%) scale(1.0000)');
		expect(frames[frames.length - 1]!.transform).toBe('translate(0.000%, 0.000%) scale(1.0000)');
	});
});

describe('stage tiles with framing', () => {
	const views = {
		landscape: { view: { x: 0.25, y: 0.5, zoom: 2 }, chosen: true },
		portrait: { view: { x: 0.7, y: 0.5, zoom: 1 }, chosen: false }
	};
	const tile = { kind: 'photo' as const, width: 3000, height: 2000, focal: { x: 0.5, y: 0.5 }, rotation: 0, views };

	it('picks the view for the stage orientation', () => {
		expect(tileView(tile, 16 / 9)?.view.zoom).toBe(2);
		expect(tileView(tile, 9 / 16)?.view.x).toBe(0.7);
		expect(tileView({ views: null }, 1)).toBeNull();
	});

	it('zooms a single photo with a crop and centres collage tiles on the view', () => {
		expect(tileCropStyle(tile, 16 / 9)).toBe(cropStyle(3000, 2000, 16 / 9, views.landscape.view));
		expect(tileCropStyle(tile, 9 / 16)).toBeNull(); // zoom 1: plain cover
		expect(tileCropStyle({ ...tile, rotation: 90 }, 16 / 9)).toBeNull();
		expect(tileCropStyle({ ...tile, kind: 'video' }, 16 / 9)).toBeNull();
		const pos = coverPosition({ x: 0.7, y: 0.5 }, 1.5, 9 / 16);
		expect(tileObjectPosition(tile, 9 / 16)).toBe(`${pos.x.toFixed(2)}% ${pos.y.toFixed(2)}%`);
		// A collage tile on a landscape stage uses the landscape centre, not the zoom.
		const half = coverPosition({ x: 0.25, y: 0.5 }, 1.5, 16 / 18);
		expect(tileObjectPosition(tile, 16 / 18, 16 / 9)).toBe(`${half.x.toFixed(2)}% ${half.y.toFixed(2)}%`);
	});
});
