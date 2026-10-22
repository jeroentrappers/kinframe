/**
 * Main-thread side of client-side preparation. All decoding and encoding runs in Web Workers, so the UI never
 * blocks; every failure resolves to `null` and the caller uploads the original (the server then does the work).
 */
import { PHOTO_PROFILE, VIDEO_PROFILE, preparedName, usePrepared, type PrepProfile } from './plan';
import { preparedPhotoName } from './photo';
import type { PhotoRequest, PhotoResponse } from './photo.worker';
import type { VideoRequest, VideoResponse } from './video.worker';

export interface PreparedFile {
	file: File;
	profile: PrepProfile;
	width: number;
	height: number;
	durationMs?: number;
	capturedAt: string | null;
}

let seq = 0;

function workersSupported(): boolean {
	return typeof Worker !== 'undefined';
}

// ---- photos: a small pool of long-lived workers, one job at a time each.

const POOL = Math.max(1, Math.min(2, (typeof navigator !== 'undefined' && navigator.hardwareConcurrency) || 2));
let photoWorkers: { w: Worker; job: number | null }[] | null = null;
const photoQueue: { req: PhotoRequest; resolve: (r: PhotoResponse) => void }[] = [];
const photoWaiting = new Map<number, (r: PhotoResponse) => void>();

function photoPool() {
	if (!photoWorkers) {
		photoWorkers = Array.from({ length: POOL }, () => {
			const w = new Worker(new URL('./photo.worker.ts', import.meta.url), { type: 'module' });
			const slot: { w: Worker; job: number | null } = { w, job: null };
			const finish = (r: PhotoResponse) => {
				photoWaiting.get(r.id)?.(r);
				photoWaiting.delete(r.id);
				slot.job = null;
				pump();
			};
			w.onmessage = (e: MessageEvent<PhotoResponse>) => finish(e.data);
			// A crashed job fails (the original is uploaded); the worker keeps serving.
			w.onerror = () => slot.job !== null && finish({ id: slot.job, ok: false, error: 'worker error', capturedAt: null });
			return slot;
		});
	}
	return photoWorkers;
}

function pump() {
	for (const slot of photoPool()) {
		if (slot.job !== null) continue;
		const job = photoQueue.shift();
		if (!job) return;
		slot.job = job.req.id;
		photoWaiting.set(job.req.id, job.resolve);
		slot.w.postMessage(job.req);
	}
}

/** Prepares a photo master, or resolves null (upload the original). `capturedAt` is returned either way. */
export async function preparePhotoFile(file: File): Promise<{ prepared: PreparedFile | null; capturedAt: string | null }> {
	if (!workersSupported() || typeof OffscreenCanvas === 'undefined' || typeof createImageBitmap === 'undefined') {
		const { captureTime } = await import('./exif');
		return { prepared: null, capturedAt: await captureTime(file) };
	}
	const res = await new Promise<PhotoResponse>((resolve) => {
		photoQueue.push({ req: { id: ++seq, file }, resolve });
		pump();
	});
	if (!res.ok || !usePrepared(file.size, res.blob.size)) return { prepared: null, capturedAt: res.capturedAt };
	const out = new File([res.blob], preparedPhotoName(file.name, res.mime), { type: res.mime, lastModified: file.lastModified });
	return {
		prepared: { file: out, profile: PHOTO_PROFILE, width: res.width, height: res.height, capturedAt: res.capturedAt },
		capturedAt: res.capturedAt
	};
}

// ---- videos: one worker per job (encoders are heavy; the composer runs them one after another).

export function canPrepareVideo(): boolean {
	return workersSupported() && typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined';
}

export interface VideoJob {
	promise: Promise<PreparedFile | null>;
	cancel(): void;
}

export function prepareVideoFile(
	file: File,
	opts: { trim: { start: number; end: number } | null; includeAudio: boolean; onProgress?: (p: number) => void }
): VideoJob {
	if (!canPrepareVideo()) return { promise: Promise.resolve(null), cancel() {} };
	const w = new Worker(new URL('./video.worker.ts', import.meta.url), { type: 'module' });
	let done: (v: PreparedFile | null) => void = () => {};
	const promise = new Promise<PreparedFile | null>((resolve) => {
		done = (v) => {
			w.terminate();
			resolve(v);
		};
		w.onmessage = (e: MessageEvent<VideoResponse>) => {
			const m = e.data;
			if (m.type === 'progress') opts.onProgress?.(m.progress);
			else if (m.type === 'error') {
				console.warn('kinframe: video preparation failed, uploading the original:', m.error);
				done(null);
			}
			else
				done({
					file: new File([m.blob], preparedName(file.name, 'mp4'), { type: 'video/mp4', lastModified: file.lastModified }),
					profile: VIDEO_PROFILE,
					width: m.width,
					height: m.height,
					durationMs: m.durationMs,
					capturedAt: m.capturedAt
				});
		};
		w.onerror = (e) => {
			console.warn('kinframe: video preparation worker failed:', e.message);
			done(null);
		};
		const req: VideoRequest = { id: ++seq, file, trim: opts.trim, includeAudio: opts.includeAudio };
		w.postMessage(req);
	});
	return { promise, cancel: () => done(null) };
}
