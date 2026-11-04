/**
 * Main-thread side of client-side preparation. All decoding and encoding runs in Web Workers, so the UI never
 * blocks. The web app never uploads a full original: when preparation fails, the original may only go up as it is
 * when it is already within the master limits (plan.ts); otherwise the item gets an error and isn't sent.
 */
import { PHOTO_PROFILE, VIDEO_PROFILE, choosePhotoUpload, photoWithinLimits, preparedName, type PrepProfile } from './plan';
import { preparedPhotoName } from './photo';
import type { PhotoRequest, PhotoResponse } from './photo.worker';
import type { VideoInfo, VideoProbeRequest, VideoRequest, VideoResponse } from './video.worker';

export type { VideoInfo } from './video.worker';

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
			// A crashed job fails; the worker keeps serving.
			w.onerror = () =>
				slot.job !== null && finish({ id: slot.job, ok: false, error: 'worker error', capturedAt: null, originalWithinLimits: false });
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

export interface PhotoResult {
	/** The master to upload; null uploads the original (only ever when it is within the limits). */
	prepared: PreparedFile | null;
	capturedAt: string | null;
	/** Neither preparation worked and the original is over the limits: the item can't be sent. */
	failed: boolean;
}

/**
 * Debug switch: `localStorage['kinframe.prep'] = 'wasm'` skips the browser's own decoder, so the WebAssembly path
 * can be tried (and tested) in any browser.
 */
function wasmOnly(): boolean {
	try {
		return localStorage.getItem('kinframe.prep') === 'wasm';
	} catch {
		return false;
	}
}

/** Prepares a photo master, or decides that the original may go as it is, or that the item can't be sent. */
export async function preparePhotoFile(file: File): Promise<PhotoResult> {
	if (!workersSupported()) {
		const [{ captureTime }, { sniffFile }] = await Promise.all([import('./exif'), import('./sniff')]);
		const within = photoWithinLimits(await sniffFile(file));
		return { prepared: null, capturedAt: await captureTime(file), failed: !within };
	}
	const res = await new Promise<PhotoResponse>((resolve) => {
		photoQueue.push({ req: { id: ++seq, file, wasmOnly: wasmOnly() }, resolve });
		pump();
	});
	if (!res.ok) console.warn('kinframe: photo preparation failed:', res.error);
	const choice = choosePhotoUpload(file.size, res.ok ? res.blob.size : null, res.originalWithinLimits);
	if (!res.ok || choice !== 'prepared') return { prepared: null, capturedAt: res.capturedAt, failed: choice === 'error' };
	const out = new File([res.blob], preparedPhotoName(file.name, res.mime), { type: res.mime, lastModified: file.lastModified });
	return {
		prepared: { file: out, profile: PHOTO_PROFILE, width: res.width, height: res.height, capturedAt: res.capturedAt },
		capturedAt: res.capturedAt,
		failed: false
	};
}

// ---- videos: one worker per job (encoders are heavy; the composer runs them one after another).

export function canPrepareVideo(): boolean {
	return workersSupported() && typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined';
}

export interface PreparedVideo extends PreparedFile {
	/** The sound was asked for, but this browser can't encode it: the master is muted. */
	audioDropped: boolean;
}

export interface VideoJob {
	/** The master, or null when preparation failed or was cancelled (the caller applies the strict rule). */
	promise: Promise<PreparedVideo | null>;
	cancel(): void;
}

/** Reads a video's size and frame rate and what this browser can do with it; null when the file can't be read. */
export function probeVideoFile(file: File): Promise<VideoInfo | null> {
	if (!workersSupported()) return Promise.resolve(null);
	const w = new Worker(new URL('./video.worker.ts', import.meta.url), { type: 'module' });
	return new Promise<VideoInfo | null>((resolve) => {
		const done = (v: VideoInfo | null) => {
			w.terminate();
			resolve(v);
		};
		w.onmessage = (e: MessageEvent<VideoResponse>) => {
			if (e.data.type === 'probe') done(e.data.info);
			else if (e.data.type === 'error') {
				console.warn('kinframe: could not read video:', e.data.error);
				done(null);
			}
		};
		w.onerror = () => done(null);
		const req: VideoProbeRequest = { id: ++seq, type: 'probe', file };
		w.postMessage(req);
	});
}

export function prepareVideoFile(
	file: File,
	opts: { trim: { start: number; end: number } | null; includeAudio: boolean; onProgress?: (p: number) => void }
): VideoJob {
	if (!canPrepareVideo()) return { promise: Promise.resolve(null), cancel() {} };
	const w = new Worker(new URL('./video.worker.ts', import.meta.url), { type: 'module' });
	let done: (v: PreparedVideo | null) => void = () => {};
	const promise = new Promise<PreparedVideo | null>((resolve) => {
		done = (v) => {
			w.terminate();
			resolve(v);
		};
		w.onmessage = (e: MessageEvent<VideoResponse>) => {
			const m = e.data;
			if (m.type === 'progress') opts.onProgress?.(m.progress);
			else if (m.type === 'error') {
				console.warn('kinframe: video preparation failed:', m.error);
				done(null);
			} else if (m.type === 'done')
				done({
					file: new File([m.blob], preparedName(file.name, 'mp4'), { type: 'video/mp4', lastModified: file.lastModified }),
					profile: VIDEO_PROFILE,
					width: m.width,
					height: m.height,
					durationMs: m.durationMs,
					capturedAt: m.capturedAt,
					audioDropped: m.audioDropped
				});
		};
		w.onerror = (e) => {
			console.warn('kinframe: video preparation worker failed:', e.message);
			done(null);
		};
		const req: VideoRequest = { id: ++seq, type: 'prepare', file, trim: opts.trim, includeAudio: opts.includeAudio };
		w.postMessage(req);
	});
	return { promise, cancel: () => done(null) };
}
