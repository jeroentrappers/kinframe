/**
 * The WebAssembly fallback for photo preparation (web/prep-wasm, a Rust crate built with wasm-pack into ./wasm).
 * The photo worker loads it only when the browser path (createImageBitmap + OffscreenCanvas) fails or is missing;
 * HEIF/AVIF files are first decoded by libheif (./heif, loaded only for those). Output: a JPEG q90 master.
 */
import { MASTER_PHOTO_EDGE } from './plan';
import { PHOTO_QUALITY, type PreparedPhoto } from './photo';
import type { ImageFormat } from './sniff';

type PrepModule = typeof import('./wasm/kinframe_prep.js');

let mod: Promise<PrepModule> | null = null;

function load(): Promise<PrepModule> {
	mod ??= import('./wasm/kinframe_prep.js').then(async (m) => {
		await m.default();
		return m;
	});
	mod.catch(() => (mod = null));
	return mod;
}

export async function prepareWithWasm(file: Blob, format: ImageFormat | null): Promise<PreparedPhoto> {
	const bytes = new Uint8Array(await file.arrayBuffer());
	const quality = Math.round(PHOTO_QUALITY * 100);
	const prep = await load();
	let out;
	if (format === 'heif' || format === 'avif') {
		const { decodeHeif } = await import('./heif');
		const px = await decodeHeif(bytes);
		out = prep.preparePixels(px.rgba, px.width, px.height, px.icc ?? undefined, px.p3, MASTER_PHOTO_EDGE, quality);
	} else {
		out = prep.prepareImage(bytes, MASTER_PHOTO_EDGE, quality);
	}
	try {
		const jpeg = out.takeJpeg();
		return { blob: new Blob([jpeg as BlobPart], { type: 'image/jpeg' }), mime: 'image/jpeg', width: out.width, height: out.height };
	} finally {
		out.free();
	}
}
