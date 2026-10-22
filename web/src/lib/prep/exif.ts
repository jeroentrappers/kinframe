/**
 * Minimal EXIF reader for the capture time. Prepared uploads carry no metadata, so the capture time is read from
 * the original and sent as the send item's `captured_at`. Works on JPEG (APP1) and on any file that embeds an
 * `Exif\0\0` + TIFF block early on (HEIC usually does).
 */

const TAG_EXIF_IFD = 0x8769;
const TAG_DATETIME = 0x0132;
const TAG_DATETIME_ORIGINAL = 0x9003;
const TAG_DATETIME_DIGITIZED = 0x9004;
const TAG_OFFSET_ORIGINAL = 0x9011;
const TAG_OFFSET_DIGITIZED = 0x9012;

/** How much of a file to read for EXIF. */
export const EXIF_SCAN_BYTES = 256 * 1024;

function findTiff(b: Uint8Array): number {
	// "Exif\0\0" followed by a TIFF header ("II*\0" or "MM\0*").
	for (let i = 0; i + 10 <= b.length; i++) {
		if (b[i] === 0x45 && b[i + 1] === 0x78 && b[i + 2] === 0x69 && b[i + 3] === 0x66 && b[i + 4] === 0 && b[i + 5] === 0) {
			const t = i + 6;
			const ii = b[t] === 0x49 && b[t + 1] === 0x49 && b[t + 2] === 0x2a && b[t + 3] === 0;
			const mm = b[t] === 0x4d && b[t + 1] === 0x4d && b[t + 2] === 0 && b[t + 3] === 0x2a;
			if (ii || mm) return t;
		}
	}
	return -1;
}

function readIfd(view: DataView, base: number, off: number, le: boolean): Map<number, { type: number; count: number; valueOff: number }> {
	const out = new Map<number, { type: number; count: number; valueOff: number }>();
	if (base + off + 2 > view.byteLength) return out;
	const n = view.getUint16(base + off, le);
	for (let i = 0; i < n; i++) {
		const e = base + off + 2 + i * 12;
		if (e + 12 > view.byteLength) break;
		const tag = view.getUint16(e, le);
		const type = view.getUint16(e + 2, le);
		const count = view.getUint32(e + 4, le);
		// ASCII values longer than 4 bytes live at an offset; shorter ones inline.
		const valueOff = type === 2 && count <= 4 ? e + 8 : type === 4 && count === 1 ? e + 8 : base + view.getUint32(e + 8, le);
		out.set(tag, { type, count, valueOff });
	}
	return out;
}

function ascii(view: DataView, ent: { count: number; valueOff: number } | undefined): string | null {
	if (!ent || ent.valueOff + ent.count > view.byteLength) return null;
	let s = '';
	for (let i = 0; i < ent.count; i++) {
		const c = view.getUint8(ent.valueOff + i);
		if (c === 0) break;
		s += String.fromCharCode(c);
	}
	return s.trim() || null;
}

/**
 * Converts EXIF "YYYY:MM:DD HH:MM:SS" (+ optional "+02:00") to RFC 3339. Without an offset the wall-clock time is
 * returned as if UTC, as the server does, so frames show the time the photo was taken.
 */
export function exifTimeToIso(value: string | null, offset: string | null): string | null {
	const m = value?.match(/^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
	if (!m) return null;
	const [, y, mo, d, h, mi, s] = m;
	if (Number(y) < 1900 || Number(mo) < 1 || Number(mo) > 12 || Number(d) < 1) return null;
	const off = offset?.match(/^([+-])(\d{2}):(\d{2})$/);
	const base = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s));
	if (Number.isNaN(base)) return null;
	const shift = off ? (off[1] === '-' ? -1 : 1) * (Number(off[2]) * 60 + Number(off[3])) * 60_000 : 0;
	return new Date(base - shift).toISOString().replace('.000Z', 'Z');
}

/** Capture time from the first bytes of a photo, or null. */
export function captureTimeFromBytes(bytes: Uint8Array): string | null {
	const t = findTiff(bytes);
	if (t < 0) return null;
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	const le = bytes[t] === 0x49;
	const ifd0 = readIfd(view, t, view.getUint32(t + 4, le), le);
	const exifPtr = ifd0.get(TAG_EXIF_IFD);
	const exif = exifPtr ? readIfd(view, t, view.getUint32(exifPtr.valueOff, le), le) : new Map();
	return (
		exifTimeToIso(ascii(view, exif.get(TAG_DATETIME_ORIGINAL)), ascii(view, exif.get(TAG_OFFSET_ORIGINAL))) ??
		exifTimeToIso(ascii(view, exif.get(TAG_DATETIME_DIGITIZED)), ascii(view, exif.get(TAG_OFFSET_DIGITIZED))) ??
		exifTimeToIso(ascii(view, ifd0.get(TAG_DATETIME)), null)
	);
}

/** Capture time of a photo file (reads only its first 256 KiB). */
export async function captureTime(file: Blob): Promise<string | null> {
	try {
		return captureTimeFromBytes(new Uint8Array(await file.slice(0, EXIF_SCAN_BYTES).arrayBuffer()));
	} catch {
		return null;
	}
}
