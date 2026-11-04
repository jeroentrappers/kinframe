/**
 * Image format and pixel size from a file's first bytes, without decoding it. Used for the strict upload rule (an
 * original may only go up as is when it is already a master-sized JPEG or WebP) and to route HEIF/AVIF to libheif.
 */

export type ImageFormat = 'jpeg' | 'png' | 'webp' | 'gif' | 'bmp' | 'tiff' | 'heif' | 'avif';

export interface ImageInfo {
	format: ImageFormat | null;
	/** Stored size (before any EXIF orientation); null when the header doesn't say or wasn't read far enough. */
	width: number | null;
	height: number | null;
}

/** HEIF brands (HEVC stills and sequences, plus the generic MIAF/HEIF ones). */
const HEIF_BRANDS = new Set(['heic', 'heix', 'heim', 'heis', 'hevc', 'hevx', 'hevm', 'hevs', 'mif1', 'mif2', 'msf1']);
const AVIF_BRANDS = new Set(['avif', 'avis']);

const ascii = (b: Uint8Array, at: number, n: number) => String.fromCharCode(...b.subarray(at, at + n));
const be16 = (b: Uint8Array, at: number) => (b[at]! << 8) | b[at + 1]!;
const le16 = (b: Uint8Array, at: number) => b[at]! | (b[at + 1]! << 8);
const le24 = (b: Uint8Array, at: number) => b[at]! | (b[at + 1]! << 8) | (b[at + 2]! << 16);
const be32 = (b: Uint8Array, at: number) => ((b[at]! << 24) | (b[at + 1]! << 16) | (b[at + 2]! << 8) | b[at + 3]!) >>> 0;

function jpegSize(b: Uint8Array): { width: number; height: number } | null {
	let i = 2;
	while (i + 9 < b.length) {
		if (b[i] !== 0xff) return null;
		const m = b[i + 1]!;
		if (m === 0xff) {
			i++; // fill byte
			continue;
		}
		if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) {
			i += 2;
			continue;
		}
		// SOF0..SOF15, except DHT (C4), JPG (C8) and DAC (CC).
		if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return { height: be16(b, i + 5), width: be16(b, i + 7) };
		if (m === 0xda || m === 0xd9) return null;
		i += 2 + be16(b, i + 2);
	}
	return null;
}

function webpSize(b: Uint8Array): { width: number; height: number } | null {
	const chunk = ascii(b, 12, 4);
	if (chunk === 'VP8 ' && b.length >= 30 && b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a)
		return { width: le16(b, 26) & 0x3fff, height: le16(b, 28) & 0x3fff };
	if (chunk === 'VP8L' && b.length >= 25 && b[20] === 0x2f) {
		const bits = b[21]! | (b[22]! << 8) | (b[23]! << 16) | (b[24]! << 24);
		return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
	}
	if (chunk === 'VP8X' && b.length >= 30) return { width: le24(b, 24) + 1, height: le24(b, 27) + 1 };
	return null;
}

/** Major and compatible brands of an ISO BMFF file (`ftyp` must come first). */
function brands(b: Uint8Array): string[] | null {
	if (b.length < 16 || ascii(b, 4, 4) !== 'ftyp') return null;
	const size = Math.min(be32(b, 0), b.length);
	const out = [ascii(b, 8, 4)];
	for (let i = 16; i + 4 <= size; i += 4) out.push(ascii(b, i, 4));
	return out;
}

export function sniffImage(b: Uint8Array): ImageInfo {
	const none = { width: null, height: null };
	if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return { format: 'jpeg', ...(jpegSize(b) ?? none) };
	if (b.length >= 24 && be32(b, 0) === 0x89504e47 && ascii(b, 12, 4) === 'IHDR') return { format: 'png', width: be32(b, 16), height: be32(b, 20) };
	if (b.length >= 16 && ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') return { format: 'webp', ...(webpSize(b) ?? none) };
	if (b.length >= 10 && ascii(b, 0, 4) === 'GIF8') return { format: 'gif', width: le16(b, 6), height: le16(b, 8) };
	if (b.length >= 26 && b[0] === 0x42 && b[1] === 0x4d) {
		const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
		return { format: 'bmp', width: Math.abs(view.getInt32(18, true)), height: Math.abs(view.getInt32(22, true)) };
	}
	if (b.length >= 4 && ((b[0] === 0x49 && b[1] === 0x49 && b[2] === 0x2a && b[3] === 0) || (b[0] === 0x4d && b[1] === 0x4d && b[2] === 0 && b[3] === 0x2a)))
		return { format: 'tiff', ...none };
	const br = brands(b);
	if (br) {
		// AVIF files list mif1 too, so look for an AVIF brand first.
		if (br.some((x) => AVIF_BRANDS.has(x))) return { format: 'avif', ...none };
		if (br.some((x) => HEIF_BRANDS.has(x))) return { format: 'heif', ...none };
	}
	return { format: null, ...none };
}

/** How much of a file to read first; a JPEG's frame header can sit behind large EXIF/XMP segments. */
export const SNIFF_HEAD_BYTES = 1 << 20;

/** Sniffs a file, reading more than the head only when a JPEG's size lies further in. */
export async function sniffFile(file: Blob, maxBytes = 64 * 1024 * 1024): Promise<ImageInfo> {
	try {
		const head = sniffImage(new Uint8Array(await file.slice(0, SNIFF_HEAD_BYTES).arrayBuffer()));
		if (head.format !== 'jpeg' || head.width !== null || file.size <= SNIFF_HEAD_BYTES || file.size > maxBytes) return head;
		return sniffImage(new Uint8Array(await file.arrayBuffer()));
	} catch {
		return { format: null, width: null, height: null };
	}
}
