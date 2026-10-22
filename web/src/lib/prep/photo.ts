import { photoTarget, preparedName, type Size } from './plan';

/**
 * Photo master preparation (photo-master-v1): decode with EXIF orientation applied, scale to ≤ 2560 px, encode
 * WebP q90 (JPEG q90 where the browser can't encode WebP). A canvas encode carries no metadata at all.
 *
 * The platform pieces are injected so this runs in a Web Worker (OffscreenCanvas) and in unit tests (fakes).
 */

export interface DecodedImage {
	width: number;
	height: number;
	source: unknown;
	close(): void;
}

export interface Surface {
	draw(source: unknown, width: number, height: number): void;
	encode(type: string, quality: number): Promise<Blob>;
}

export interface PhotoPrepDeps {
	decode(blob: Blob): Promise<DecodedImage>;
	surface(width: number, height: number): Surface;
}

export interface PreparedPhoto extends Size {
	blob: Blob;
	mime: 'image/webp' | 'image/jpeg';
}

export const PHOTO_QUALITY = 0.9;

export async function preparePhoto(blob: Blob, deps: PhotoPrepDeps): Promise<PreparedPhoto> {
	const img = await deps.decode(blob);
	try {
		if (!img.width || !img.height) throw new Error('empty image');
		const size = photoTarget(img.width, img.height);
		const surface = deps.surface(size.width, size.height);
		surface.draw(img.source, size.width, size.height);
		let out = await surface.encode('image/webp', PHOTO_QUALITY);
		let mime: PreparedPhoto['mime'] = 'image/webp';
		if (out.type !== 'image/webp') {
			// No WebP encoder (Safari falls back to PNG): JPEG is the other accepted master format.
			out = await surface.encode('image/jpeg', PHOTO_QUALITY);
			mime = 'image/jpeg';
			if (out.type !== 'image/jpeg') throw new Error(`cannot encode ${out.type}`);
		}
		return { blob: out, mime, ...size };
	} finally {
		img.close();
	}
}

export function preparedPhotoName(name: string, mime: PreparedPhoto['mime']): string {
	return preparedName(name, mime === 'image/webp' ? 'webp' : 'jpg');
}

/** The browser implementation: createImageBitmap + OffscreenCanvas (works in workers). */
export const browserPhotoDeps: PhotoPrepDeps = {
	async decode(blob) {
		const bmp = await createImageBitmap(blob, { imageOrientation: 'from-image' });
		return { width: bmp.width, height: bmp.height, source: bmp, close: () => bmp.close() };
	},
	surface(width, height) {
		const canvas = new OffscreenCanvas(width, height);
		const ctx = canvas.getContext('2d', { alpha: false });
		if (!ctx) throw new Error('no 2d context');
		return {
			draw(source, w, h) {
				ctx.imageSmoothingEnabled = true;
				ctx.imageSmoothingQuality = 'high';
				ctx.drawImage(source as CanvasImageSource, 0, 0, w, h);
			},
			encode: (type, quality) => canvas.convertToBlob({ type, quality })
		};
	}
};
