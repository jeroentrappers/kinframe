import { describe, expect, it } from 'vitest';
import {
	acceptFiles,
	applyMergePatch,
	applyPlacementEvent,
	backoffDelay,
	clampFocal,
	countStages,
	coverPosition,
	deliveryProgress,
	frameDeliveries,
	itemIssue,
	kindCounts,
	mediaKindOf,
	mergePatchDiff,
	overallProgress,
	overallStage,
	patchesOverlap,
	rebaseSettings,
	runPool,
	stageOf,
	tallyReactions,
	uploadIdFromUrl
} from './logic';

describe('focal point framing', () => {
	it('centres when the image already matches the box', () => {
		expect(coverPosition({ x: 0.2, y: 0.8 }, 16 / 10, 16 / 10)).toEqual({ x: 50, y: 50 });
	});
	it('slides a wide photo horizontally toward the focal point, clamped to the edges', () => {
		// 3:1 panorama in a square: image is 3 box-widths wide.
		expect(coverPosition({ x: 0.5, y: 0.5 }, 3, 1).x).toBeCloseTo(50);
		expect(coverPosition({ x: 0, y: 0.5 }, 3, 1).x).toBeCloseTo(0);
		expect(coverPosition({ x: 1, y: 0.5 }, 3, 1).x).toBeCloseTo(100);
		// focal at 1/3 → left edge at 0.5 - 1 = -0.5 of 2 overflow → 25%
		expect(coverPosition({ x: 1 / 3, y: 0.5 }, 3, 1).x).toBeCloseTo(25);
		expect(coverPosition({ x: 1 / 3, y: 0.5 }, 3, 1).y).toBe(50);
	});
	it('slides a tall photo vertically in a landscape box', () => {
		const p = coverPosition({ x: 0.5, y: 0.1 }, 3 / 4, 16 / 10);
		expect(p.x).toBe(50);
		expect(p.y).toBeCloseTo(0);
	});
	it('clamps and rounds focal points', () => {
		expect(clampFocal(-0.2, 1.4)).toEqual({ x: 0, y: 1 });
		expect(clampFocal(0.12345, 0.5)).toEqual({ x: 0.123, y: 0.5 });
	});
});

describe('JSON merge patch', () => {
	const base = { version: 3, slideshow: { interval_s: 30, order: 'received' }, display: { brightness: 204, sleep_schedules: [{ id: 'a' }] }, extra: { keep: true } };

	it('diffs only changed leaves, replaces arrays whole', () => {
		const next = structuredClone(base);
		next.slideshow.interval_s = 60;
		next.display.sleep_schedules.push({ id: 'b' });
		expect(mergePatchDiff(base, next)).toEqual({
			slideshow: { interval_s: 60 },
			display: { sleep_schedules: [{ id: 'a' }, { id: 'b' }] }
		});
	});

	it('returns an empty patch when nothing changed', () => {
		expect(mergePatchDiff(base, structuredClone(base))).toEqual({});
	});

	it('nulls removed keys and round-trips through applyMergePatch', () => {
		const next = structuredClone(base) as Record<string, unknown>;
		delete next.extra;
		const patch = mergePatchDiff(base, next as typeof base);
		expect(patch).toEqual({ extra: null });
		expect(applyMergePatch(base, patch)).toEqual(next);
	});

	it('detects overlapping patches', () => {
		expect(patchesOverlap({ slideshow: { interval_s: 5 } }, { slideshow: { order: 'taken' } })).toBe(false);
		expect(patchesOverlap({ slideshow: { interval_s: 5 } }, { slideshow: { interval_s: 10 } })).toBe(true);
		expect(patchesOverlap({ display: null }, { display: { brightness: 20 } })).toBe(true);
	});

	it('rebases after a 412: retries when fields differ, asks for review when they collide', () => {
		const theirs = structuredClone(base);
		theirs.version = 4;
		theirs.display.brightness = 100;
		expect(rebaseSettings(base, theirs, { slideshow: { interval_s: 60 } })).toEqual({
			kind: 'retry',
			patch: { slideshow: { interval_s: 60 } }
		});
		const review = rebaseSettings(base, theirs, { display: { brightness: 255 } });
		expect(review.kind).toBe('review');
		if (review.kind === 'review') {
			expect((review.merged.display as { brightness: number }).brightness).toBe(255);
			expect(review.merged.version).toBe(4);
		}
	});
});

describe('websocket back-off', () => {
	it('doubles from 1 s and caps at 60 s', () => {
		expect(backoffDelay(0, 0.5)).toBe(1000);
		expect(backoffDelay(1, 0.5)).toBe(2000);
		expect(backoffDelay(5, 0.5)).toBe(32000);
		expect(backoffDelay(6, 0.5)).toBe(60000);
		expect(backoffDelay(30, 0.5)).toBe(60000);
	});
	it('jitters by ±20 %', () => {
		expect(backoffDelay(0, 0)).toBe(800);
		expect(backoffDelay(0, 0.99999)).toBe(1200);
	});
});

describe('delivery state', () => {
	it('maps placement + item status to a stage', () => {
		expect(stageOf('pending', 'processing')).toBe('processing');
		expect(stageOf('ready', 'ready')).toBe('on_its_way');
		expect(stageOf('delivered')).toBe('delivered');
		expect(stageOf('seen')).toBe('seen');
		expect(stageOf('ready', 'failed')).toBe('failed');
		expect(stageOf('removed')).toBe('removed');
	});
	it('summarizes a group: least advanced wins, failures surface', () => {
		expect(overallStage(['seen', 'delivered', 'seen'])).toBe('delivered');
		expect(overallStage(['seen', 'seen'])).toBe('seen');
		expect(overallStage(['seen', 'failed'])).toBe('failed');
		expect(overallStage(['removed'])).toBe('removed');
		expect(countStages(['seen', 'seen', 'on_its_way'])).toMatchObject({ seen: 2, on_its_way: 1, total: 3 });
		expect(deliveryProgress(['seen', 'processing'])).toBeCloseTo(0.5);
		expect(deliveryProgress([])).toBe(0);
	});
	it('tallies reactions in a stable order', () => {
		expect(tallyReactions(['sad', 'love', null, 'love', undefined])).toEqual([
			{ reaction: 'love', count: 2 },
			{ reaction: 'sad', count: 1 }
		]);
	});

	const send = {
		items: [
			{ id: 'i1', status: 'ready' as const, kind: 'photo' as const },
			{ id: 'i2', status: 'processing' as const, kind: 'video' as const }
		],
		frames: [
			{
				frame_id: 'f1',
				frame_name: 'Oma',
				online: true,
				placements: [
					{ placement_id: 'p1', item_id: 'i1', status: 'seen' as const, reaction: 'love' as const },
					{ placement_id: 'p2', item_id: 'i2', status: 'pending' as const }
				]
			}
		]
	};

	it('builds per-frame delivery for history cards', () => {
		const [d] = frameDeliveries(send);
		expect(d).toMatchObject({ frameId: 'f1', frameName: 'Oma', online: true, stage: 'processing' });
		expect(d!.counts.seen).toBe(1);
		expect(d!.reactions).toEqual(['love', undefined]);
		expect(kindCounts(send.items)).toEqual({ photos: 1, videos: 1 });
	});

	it('applies live reactions and replies in place', () => {
		const s = structuredClone(send);
		expect(applyPlacementEvent(s, { placement_id: 'p2', reaction: 'wow' })).toBe(true);
		expect(s.frames[0]!.placements[1]!.reaction).toBe('wow');
		expect(applyPlacementEvent(s, { placement_id: 'p1', preset: 'thanks' })).toBe(true);
		expect((s.frames[0]!.placements[0] as { replies?: string[] }).replies).toEqual(['thanks']);
		expect(applyPlacementEvent(s, { placement_id: 'nope', reaction: null })).toBe(false);
	});
});

describe('uploads', () => {
	it('runs at most N tasks at once and keeps result order', async () => {
		let active = 0;
		let peak = 0;
		const results = await runPool([30, 10, 20, 5, 15], 3, async (ms, i) => {
			active++;
			peak = Math.max(peak, active);
			await new Promise((r) => setTimeout(r, ms));
			active--;
			if (i === 3) throw new Error('boom');
			return i;
		});
		expect(peak).toBe(3);
		expect(results.map((r) => r.status)).toEqual(['fulfilled', 'fulfilled', 'fulfilled', 'rejected', 'fulfilled']);
		expect(results[4]).toEqual({ status: 'fulfilled', value: 4 });
	});

	it('weights overall progress by bytes', () => {
		expect(overallProgress([{ bytes: 100, uploaded: 100 }, { bytes: 300, uploaded: 0 }])).toBe(0.25);
		expect(overallProgress([])).toBe(0);
	});

	it('extracts the tus upload id', () => {
		expect(uploadIdFromUrl('https://x.org/v1/uploads/abc123')).toBe('abc123');
		expect(uploadIdFromUrl('/v1/uploads/abc123/?x=1')).toBe('abc123');
	});
});

describe('compose validation', () => {
	const limits = { max_items_per_send: 3, max_video_seconds: 300, max_upload_bytes: 1000 };

	it('classifies media by type, then by extension', () => {
		expect(mediaKindOf({ name: 'a.jpg', type: 'image/jpeg' })).toBe('photo');
		expect(mediaKindOf({ name: 'IMG_1.HEIC', type: '' })).toBe('photo');
		expect(mediaKindOf({ name: 'clip.mov', type: '' })).toBe('video');
		expect(mediaKindOf({ name: 'notes.pdf', type: 'application/pdf' })).toBe(null);
	});

	it('accepts up to the per-send limit and reports the rest', () => {
		const files = ['a.jpg', 'b.pdf', 'c.png', 'd.mp4', 'e.jpg'].map((name) => ({ name, type: '' }));
		const r = acceptFiles(files, 1, limits.max_items_per_send);
		expect(r.accepted.map((f) => f.name)).toEqual(['a.jpg', 'c.png']);
		expect(r.unsupported.map((f) => f.name)).toEqual(['b.pdf']);
		expect(r.overLimit.map((f) => f.name)).toEqual(['d.mp4', 'e.jpg']);
	});

	it('flags items over the server limits', () => {
		expect(itemIssue({ kind: 'photo', bytes: 2000 }, limits)).toBe('too_large');
		expect(itemIssue({ kind: 'video', bytes: 10, durationS: 301 }, limits)).toBe('video_too_long');
		expect(itemIssue({ kind: 'video', bytes: 10, durationS: null }, limits)).toBe(null);
		expect(itemIssue({ kind: 'photo', bytes: 10 }, limits)).toBe(null);
	});
});
