/**
 * HEIC/HEIF (and AVIF) decoding with libheif + libde265 (+ dav1d) compiled to WebAssembly, from the `libheif-js`
 * package (LGPL-3.0; see static/third-party-notices.txt). Loaded only by the photo worker, only for HEIF/AVIF files
 * the browser can't decode itself (Safari can). The `.wasm` is a separate file fetched on demand, so it stays a
 * replaceable library. Decoding HEVC may need a patent licence in some countries; kinframe only decodes, and only
 * on the user's own device when they pick such a file.
 */
import createLibheif from 'libheif-js/libheif-wasm/libheif.js';
import wasmUrl from 'libheif-js/libheif-wasm/libheif.wasm?url';
import { heifColour, type HeifColour } from './heifColour';

type LibHeif = ReturnType<typeof createLibheif>;

export interface HeifPixels extends HeifColour {
	rgba: Uint8Array;
	width: number;
	height: number;
}

let lib: Promise<LibHeif> | null = null;

function load(): Promise<LibHeif> {
	lib ??= (async () => {
		const res = await fetch(wasmUrl);
		if (!res.ok) throw new Error(`libheif: HTTP ${res.status}`);
		const wasmBinary = await res.arrayBuffer();
		return await new Promise<LibHeif>((resolve, reject) => {
			let mod: LibHeif | undefined;
			let ready = false;
			mod = createLibheif({
				wasmBinary,
				onRuntimeInitialized: () => {
					ready = true;
					if (mod) resolve(mod);
				},
				onAbort: (why: unknown) => reject(new Error(`libheif: ${String(why)}`))
			});
			if (ready) resolve(mod);
		});
	})();
	lib.catch(() => (lib = null));
	return lib;
}

interface Channel {
	id: unknown;
	width: number;
	height: number;
	stride: number;
	data: Uint8Array;
}

/**
 * Decodes the primary image (a multi-image file's other images are ignored) to RGBA8, with the HEIF transformations
 * (`irot` rotation, `imir` mirroring, `clap` crop) applied by libheif. EXIF orientation is not applied: in HEIF the
 * transformation properties are the authority.
 */
export async function decodeHeif(bytes: Uint8Array): Promise<HeifPixels> {
	const h = await load();
	const ctx = h.heif_context_alloc();
	if (!ctx) throw new Error('libheif: no context');
	try {
		const err = h.heif_context_read_from_memory(ctx, bytes);
		if (err.code !== h.heif_error_code.heif_error_Ok) throw new Error(`libheif: ${err.message}`);
		const handle = h.heif_js_context_get_primary_image_handle(ctx);
		if (!handle || handle.code) throw new Error(`libheif: no primary image (${handle?.message ?? 'unknown'})`);
		try {
			const img = await h.heif_js_decode_image2(handle, h.heif_colorspace.heif_colorspace_RGB, h.heif_chroma.heif_chroma_interleaved_RGBA);
			if (!img || img.code) throw new Error(`libheif: decoding failed (${img?.message ?? 'unknown'})`);
			try {
				const ch = (img.channels as Channel[]).find((c) => c.id === h.heif_channel.heif_channel_interleaved);
				if (!ch) throw new Error('libheif: no interleaved channel');
				const { width, height, stride, data } = ch;
				const row = width * 4;
				let rgba: Uint8Array;
				if (stride === row) rgba = data.slice(0, row * height);
				else {
					rgba = new Uint8Array(row * height);
					for (let y = 0; y < height; y++) rgba.set(data.subarray(y * stride, y * stride + row), y * row);
				}
				return { rgba, width, height, ...heifColour(bytes) };
			} finally {
				h.heif_image_release(img.image);
			}
		} finally {
			h.heif_image_handle_release(handle);
		}
	} finally {
		h.heif_context_free(ctx);
	}
}
