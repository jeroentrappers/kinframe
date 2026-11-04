/// <reference lib="webworker" />
/**
 * Photo master preparation in a worker. First the browser itself (createImageBitmap + OffscreenCanvas, WebP or
 * JPEG); when that fails or is missing, the WebAssembly fallback (JPEG; HEIF/AVIF through libheif). The original's
 * format and size are reported too, so the client can tell whether it may go up as it is (plan.ts).
 */
import { captureTime } from './exif';
import { browserPhotoDeps, preparePhoto, type PreparedPhoto } from './photo';
import { photoWithinLimits } from './plan';
import { sniffFile } from './sniff';

export interface PhotoRequest {
	id: number;
	file: Blob;
	/** Skip the browser's decoder (debug switch, see client.ts). */
	wasmOnly?: boolean;
}

export type PhotoResponse =
	| {
			id: number;
			ok: true;
			blob: Blob;
			mime: 'image/webp' | 'image/jpeg';
			width: number;
			height: number;
			capturedAt: string | null;
			originalWithinLimits: boolean;
	  }
	| { id: number; ok: false; error: string; capturedAt: string | null; originalWithinLimits: boolean };

const nativeAvailable = () => typeof createImageBitmap !== 'undefined' && typeof OffscreenCanvas !== 'undefined';

self.onmessage = async (e: MessageEvent<PhotoRequest>) => {
	const { id, file, wasmOnly } = e.data;
	const post = (m: PhotoResponse) => (self as unknown as Worker).postMessage(m);
	const [capturedAt, info] = await Promise.all([captureTime(file), sniffFile(file)]);
	const originalWithinLimits = photoWithinLimits(info);
	const errors: string[] = [];
	let out: PreparedPhoto | null = null;
	if (nativeAvailable() && !wasmOnly) {
		try {
			out = await preparePhoto(file, browserPhotoDeps);
		} catch (err) {
			errors.push(`browser: ${String(err)}`);
		}
	}
	if (!out) {
		try {
			const { prepareWithWasm } = await import('./wasmPhoto');
			out = await prepareWithWasm(file, info.format);
		} catch (err) {
			errors.push(`wasm: ${String(err)}`);
		}
	}
	if (out) post({ id, ok: true, ...out, capturedAt, originalWithinLimits });
	else post({ id, ok: false, error: errors.join('; '), capturedAt, originalWithinLimits });
};
