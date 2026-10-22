// Pure state logic shared by pages. Unit-tested in logic.test.ts.

// ------------------------------------------------------------------ focal point / framing

export interface Focal {
	x: number;
	y: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/**
 * CSS `object-position` (in %) that centres the focal point inside a box when the image is
 * scaled to cover it, clamped so no empty space shows. This is how the frame crops in fill mode.
 */
export function coverPosition(focal: Focal, imageAspect: number, boxAspect: number): { x: number; y: number } {
	// Scale so the image covers the box (box normalised to 1×1 in its own units).
	const scaleW = imageAspect >= boxAspect ? imageAspect / boxAspect : 1; // image width in box widths
	const scaleH = imageAspect >= boxAspect ? 1 : boxAspect / imageAspect; // image height in box heights
	const axis = (f: number, size: number) => {
		if (size <= 1.0001) return 50;
		const t = clamp(0.5 - f * size, 1 - size, 0); // translation of the image's left/top edge
		return (t / (1 - size)) * 100;
	};
	return { x: axis(focal.x, scaleW), y: axis(focal.y, scaleH) };
}

export function clampFocal(x: number, y: number): Focal {
	const round = (v: number) => Math.round(clamp(v, 0, 1) * 1000) / 1000;
	return { x: round(x), y: round(y) };
}

// ------------------------------------------------------------------ JSON merge patch (RFC 7396)

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export type JsonObject = { [k: string]: Json };

const isObject = (v: unknown): v is JsonObject => typeof v === 'object' && v !== null && !Array.isArray(v);

/** The merge patch that turns `from` into `to`. Arrays are replaced whole, removed keys become null. */
export function mergePatchDiff(from: JsonObject, to: JsonObject): JsonObject {
	const patch: JsonObject = {};
	for (const key of Object.keys(to)) {
		const a = from[key];
		const b = to[key] as Json;
		if (isObject(a) && isObject(b)) {
			const sub = mergePatchDiff(a, b);
			if (Object.keys(sub).length > 0) patch[key] = sub;
		} else if (JSON.stringify(a) !== JSON.stringify(b)) {
			patch[key] = b === undefined ? null : b;
		}
	}
	for (const key of Object.keys(from)) {
		if (!(key in to)) patch[key] = null;
	}
	return patch;
}

/** Apply a merge patch (RFC 7396). Returns a new object. */
export function applyMergePatch(target: JsonObject, patch: JsonObject): JsonObject {
	const out: JsonObject = { ...target };
	for (const [key, value] of Object.entries(patch)) {
		if (value === null) delete out[key];
		else if (isObject(value)) out[key] = applyMergePatch(isObject(out[key]) ? (out[key] as JsonObject) : {}, value);
		else out[key] = value;
	}
	return out;
}

/** Leaf paths touched by a patch, e.g. ["slideshow.interval_s", "display.sleep_schedules"]. */
export function patchPaths(patch: JsonObject, prefix = ''): string[] {
	const out: string[] = [];
	for (const [key, value] of Object.entries(patch)) {
		const path = prefix ? `${prefix}.${key}` : key;
		if (isObject(value)) out.push(...patchPaths(value, path));
		else out.push(path);
	}
	return out;
}

/** Whether two patches touch the same field (or one touches a parent of the other). */
export function patchesOverlap(a: JsonObject, b: JsonObject): boolean {
	const pa = patchPaths(a);
	const pb = patchPaths(b);
	return pa.some((x) => pb.some((y) => x === y || x.startsWith(`${y}.`) || y.startsWith(`${x}.`)));
}

/**
 * What to do after a 412 version_conflict: our patch was computed against `base`, the server now
 * holds `latest`. If the remote change doesn't touch our fields, retry with our patch on the new
 * version; otherwise show the merged result for review.
 */
export function rebaseSettings(
	base: JsonObject,
	latest: JsonObject,
	ours: JsonObject
): { kind: 'retry'; patch: JsonObject } | { kind: 'review'; merged: JsonObject } {
	const theirs = mergePatchDiff(stripVersion(base), stripVersion(latest));
	if (!patchesOverlap(theirs, ours)) return { kind: 'retry', patch: ours };
	return { kind: 'review', merged: applyMergePatch(latest, ours) };
}

function stripVersion(o: JsonObject): JsonObject {
	const { version: _v, ...rest } = o;
	return rest;
}

// ------------------------------------------------------------------ websocket back-off

/** README §6: exponential back-off 1 s → 60 s with ±20 % jitter. `random` in [0,1). */
export function backoffDelay(attempt: number, random: number = Math.random()): number {
	const base = Math.min(60_000, 1000 * 2 ** Math.max(0, attempt));
	const jitter = 1 + (random * 2 - 1) * 0.2;
	return Math.round(base * jitter);
}

// ------------------------------------------------------------------ delivery state

export type PlacementStatus = 'pending' | 'ready' | 'delivered' | 'seen' | 'failed' | 'removed';
export type Stage = 'processing' | 'on_its_way' | 'delivered' | 'seen' | 'failed' | 'removed';

const STAGE_RANK: Record<Stage, number> = { failed: 0, processing: 1, on_its_way: 2, delivered: 3, seen: 4, removed: 5 };

export function stageOf(status: PlacementStatus, itemStatus?: 'processing' | 'ready' | 'failed'): Stage {
	if (itemStatus === 'failed' || status === 'failed') return 'failed';
	if (status === 'removed') return 'removed';
	if (itemStatus === 'processing') return 'processing';
	if (status === 'pending') return 'processing';
	if (status === 'ready') return 'on_its_way';
	return status;
}

export interface StageCounts {
	processing: number;
	on_its_way: number;
	delivered: number;
	seen: number;
	failed: number;
	removed: number;
	total: number;
}

export function countStages(stages: Stage[]): StageCounts {
	const c: StageCounts = { processing: 0, on_its_way: 0, delivered: 0, seen: 0, failed: 0, removed: 0, total: 0 };
	for (const s of stages) {
		c[s]++;
		c.total++;
	}
	return c;
}

/** The headline stage for a group: the least-advanced live stage (failures win so they are noticed). */
export function overallStage(stages: Stage[]): Stage {
	const live = stages.filter((s) => s !== 'removed');
	if (live.length === 0) return 'removed';
	return live.reduce((a, b) => (STAGE_RANK[b] < STAGE_RANK[a] ? b : a));
}

/** 0..1 progress of a group of placements (processing 0, on its way 1/3, delivered 2/3, seen 1). */
export function deliveryProgress(stages: Stage[]): number {
	const live = stages.filter((s) => s !== 'removed' && s !== 'failed');
	if (live.length === 0) return 0;
	const value: Record<Stage, number> = { processing: 0, on_its_way: 1 / 3, delivered: 2 / 3, seen: 1, failed: 0, removed: 0 };
	return live.reduce((sum, s) => sum + value[s], 0) / live.length;
}

export type Reaction = 'love' | 'laugh' | 'wow' | 'sad';
export const REACTION_EMOJI: Record<Reaction, string> = { love: '❤️', laugh: '😂', wow: '😮', sad: '😢' };

/** [{reaction:'love', count:2}, …] in a stable order. */
export function tallyReactions(reactions: (Reaction | null | undefined)[]): { reaction: Reaction; count: number }[] {
	const order: Reaction[] = ['love', 'laugh', 'wow', 'sad'];
	const counts = new Map<Reaction, number>();
	for (const r of reactions) if (r) counts.set(r, (counts.get(r) ?? 0) + 1);
	return order.filter((r) => counts.has(r)).map((reaction) => ({ reaction, count: counts.get(reaction)! }));
}

// ------------------------------------------------------------------ uploads

/** Run async tasks with at most `limit` in flight; results keep input order; never rejects. */
export async function runPool<T, R>(
	inputs: T[],
	limit: number,
	task: (input: T, index: number) => Promise<R>
): Promise<PromiseSettledResult<R>[]> {
	const results: PromiseSettledResult<R>[] = new Array(inputs.length);
	let next = 0;
	const worker = async () => {
		while (next < inputs.length) {
			const i = next++;
			try {
				results[i] = { status: 'fulfilled', value: await task(inputs[i]!, i) };
			} catch (reason) {
				results[i] = { status: 'rejected', reason };
			}
		}
	};
	await Promise.all(Array.from({ length: Math.min(limit, inputs.length) }, worker));
	return results;
}

/** Overall upload progress weighted by bytes. */
export function overallProgress(items: { bytes: number; uploaded: number }[]): number {
	const total = items.reduce((s, i) => s + i.bytes, 0);
	if (total === 0) return 0;
	return Math.min(1, items.reduce((s, i) => s + Math.min(i.uploaded, i.bytes), 0) / total);
}

/** tus upload URL → upload id (last path segment). */
export function uploadIdFromUrl(url: string): string {
	const path = url.split('?')[0]!.replace(/\/+$/, '');
	return decodeURIComponent(path.slice(path.lastIndexOf('/') + 1));
}

// ------------------------------------------------------------------ compose validation

export interface Limits {
	max_items_per_send: number;
	max_video_seconds: number;
	max_upload_bytes: number;
}

export type ItemIssue = 'too_large' | 'video_too_long' | 'unsupported';

const PHOTO_EXT = ['jpg', 'jpeg', 'png', 'webp', 'heic', 'heif', 'avif', 'gif'];
const VIDEO_EXT = ['mp4', 'mov', 'm4v', '3gp', 'mkv', 'webm'];

/** Classify a file by MIME type, falling back to extension (HEIC often has an empty type). */
export function mediaKindOf(file: { name: string; type: string }): 'photo' | 'video' | null {
	if (file.type.startsWith('image/')) return 'photo';
	if (file.type.startsWith('video/')) return 'video';
	const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
	if (PHOTO_EXT.includes(ext)) return 'photo';
	if (VIDEO_EXT.includes(ext)) return 'video';
	return null;
}

export function itemIssue(
	item: { kind: 'photo' | 'video'; bytes: number; durationS?: number | null },
	limits: Limits
): ItemIssue | null {
	if (item.bytes > limits.max_upload_bytes) return 'too_large';
	if (item.kind === 'video' && item.durationS != null && item.durationS > limits.max_video_seconds) return 'video_too_long';
	return null;
}

/** Split incoming files into accepted and rejected so the drop zone can explain what happened. */
export function acceptFiles<F extends { name: string; type: string }>(
	files: F[],
	alreadyQueued: number,
	maxItems: number
): { accepted: F[]; unsupported: F[]; overLimit: F[] } {
	const media = files.filter((f) => mediaKindOf(f) !== null);
	const unsupported = files.filter((f) => mediaKindOf(f) === null);
	const room = Math.max(0, maxItems - alreadyQueued);
	return { accepted: media.slice(0, room), unsupported, overLimit: media.slice(room) };
}

/** A fresh, unguessable idempotency key (UUID v4 from the platform). */
export function newIdempotencyKey(): string {
	return globalThis.crypto.randomUUID();
}

// ------------------------------------------------------------------ send view model

interface SendLike {
	items: { id: string; status: 'processing' | 'ready' | 'failed'; kind: 'photo' | 'video' }[];
	frames: {
		frame_id: string;
		frame_name: string;
		online?: boolean;
		placements: {
			placement_id: string;
			item_id: string;
			status: PlacementStatus;
			reaction?: Reaction | null;
			replies?: string[];
		}[];
	}[];
}

export interface FrameDelivery {
	frameId: string;
	frameName: string;
	online: boolean;
	stage: Stage;
	counts: StageCounts;
	progress: number;
	reactions: (Reaction | null | undefined)[];
	replies: string[];
}

/** Per-frame delivery summary of a send, for the history cards. */
export function frameDeliveries(send: SendLike): FrameDelivery[] {
	const itemStatus = new Map(send.items.map((i) => [i.id, i.status]));
	return send.frames.map((f) => {
		const stages = f.placements.map((p) => stageOf(p.status, itemStatus.get(p.item_id)));
		return {
			frameId: f.frame_id,
			frameName: f.frame_name,
			online: f.online ?? false,
			stage: overallStage(stages),
			counts: countStages(stages),
			progress: deliveryProgress(stages),
			reactions: f.placements.map((p) => p.reaction),
			replies: f.placements.flatMap((p) => p.replies ?? [])
		};
	});
}

/** Apply a live reaction/reply event to a send in place. Returns true if something changed. */
export function applyPlacementEvent(
	send: SendLike,
	e: { placement_id: string } & ({ reaction: Reaction | null } | { preset: string })
): boolean {
	for (const f of send.frames) {
		const p = f.placements.find((x) => x.placement_id === e.placement_id);
		if (!p) continue;
		if ('reaction' in e) p.reaction = e.reaction;
		else p.replies = [...(p.replies ?? []), e.preset];
		return true;
	}
	return false;
}

export function kindCounts(items: { kind: 'photo' | 'video' }[]): { photos: number; videos: number } {
	let photos = 0;
	let videos = 0;
	for (const i of items) i.kind === 'video' ? videos++ : photos++;
	return { photos, videos };
}
