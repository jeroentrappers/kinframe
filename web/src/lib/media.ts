import { authFetch } from './api/client';

export type MediaVariant = 'thumb_256' | 'thumb_512' | 'master' | 'poster';

/**
 * <img> can't send a bearer token, so media is fetched with auth and shown via an object URL.
 * The endpoint 302s to a short-lived signed URL; fetch follows that transparently.
 * Cached per item+variant for the lifetime of the page.
 */
const cache = new Map<string, Promise<string>>();

export function mediaObjectUrl(itemId: string, variant: MediaVariant = 'thumb_512'): Promise<string> {
	const key = `${itemId}/${variant}`;
	let p = cache.get(key);
	if (!p) {
		p = authFetch(`/v1/items/${encodeURIComponent(itemId)}/media/${variant}`)
			.then((r) => r.blob())
			.then((b) => URL.createObjectURL(b));
		p.catch(() => cache.delete(key));
		cache.set(key, p);
	}
	return p;
}

export function forgetMedia(itemId: string) {
	for (const key of [...cache.keys()]) {
		if (key.startsWith(`${itemId}/`)) {
			void cache.get(key)?.then((u) => URL.revokeObjectURL(u)).catch(() => {});
			cache.delete(key);
		}
	}
}

/** Duration (s) and natural size of a local video file, or null if the browser can't read it. */
export function probeVideo(file: File): Promise<{ duration: number; width: number; height: number } | null> {
	return new Promise((resolve) => {
		const url = URL.createObjectURL(file);
		const v = document.createElement('video');
		v.preload = 'metadata';
		v.muted = true;
		const done = (r: { duration: number; width: number; height: number } | null) => {
			URL.revokeObjectURL(url);
			resolve(r);
		};
		v.onloadedmetadata = () =>
			done(Number.isFinite(v.duration) ? { duration: v.duration, width: v.videoWidth, height: v.videoHeight } : null);
		v.onerror = () => done(null);
		setTimeout(() => done(null), 10_000);
		v.src = url;
	});
}

/** Natural size of a local image, or null when the browser can't decode it (e.g. HEIC on Chrome). */
export function probeImage(url: string): Promise<{ width: number; height: number } | null> {
	return new Promise((resolve) => {
		const img = new Image();
		img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight });
		img.onerror = () => resolve(null);
		img.src = url;
	});
}

/** Hex SHA-256 of a file, for small files only (duplicate detection is optional). */
export async function sha256Hex(file: Blob, maxBytes = 64 * 1024 * 1024): Promise<string | undefined> {
	if (file.size > maxBytes || !globalThis.crypto?.subtle) return undefined;
	const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
	return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Square-crop and downscale an image for avatar upload (the server crops to 256 as well). */
export async function squareAvatar(file: File, size = 512): Promise<Blob> {
	const url = URL.createObjectURL(file);
	try {
		const img = new Image();
		await new Promise<void>((res, rej) => {
			img.onload = () => res();
			img.onerror = () => rej(new Error('decode'));
			img.src = url;
		});
		const side = Math.min(img.naturalWidth, img.naturalHeight);
		const canvas = document.createElement('canvas');
		canvas.width = canvas.height = Math.min(size, side);
		const ctx = canvas.getContext('2d')!;
		ctx.drawImage(
			img,
			(img.naturalWidth - side) / 2,
			(img.naturalHeight - side) / 2,
			side,
			side,
			0,
			0,
			canvas.width,
			canvas.height
		);
		return await new Promise<Blob>((res, rej) =>
			canvas.toBlob((b) => (b ? res(b) : rej(new Error('encode'))), 'image/jpeg', 0.9)
		);
	} finally {
		URL.revokeObjectURL(url);
	}
}
