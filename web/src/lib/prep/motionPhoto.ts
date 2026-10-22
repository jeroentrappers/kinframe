/**
 * Android Motion Photos: a JPEG with a short MP4 appended, announced in its XMP (`GCamera:MotionPhoto="1"` with a
 * `Container:Directory` whose MotionPhoto item has `Item:Length` bytes from the end of the file, or the older
 * `GCamera:MicroVideo="1"` + `GCamera:MicroVideoOffset`). Offsets are never trusted blindly: the range must start
 * with an MP4 `ftyp` box and contain `moov` and `mdat`.
 */

export interface MotionRange {
	start: number;
	end: number;
}

const XMP_SCAN = 1 << 20;
const MP4_BRANDS = new Set(['isom', 'iso2', 'iso4', 'iso5', 'iso6', 'mp41', 'mp42', 'avc1', 'qt  ', 'M4V ']);

function latin1(b: Uint8Array): string {
	let s = '';
	const CHUNK = 0x8000;
	for (let i = 0; i < b.length; i += CHUNK) s += String.fromCharCode(...b.subarray(i, i + CHUNK));
	return s;
}

function u32(b: Uint8Array, i: number): number {
	return ((b[i]! << 24) | (b[i + 1]! << 16) | (b[i + 2]! << 8) | b[i + 3]!) >>> 0;
}

function fourcc(b: Uint8Array, i: number): string {
	return String.fromCharCode(b[i]!, b[i + 1]!, b[i + 2]!, b[i + 3]!);
}

/** Extent of an MP4 starting at `start` (walking its top-level boxes), or null. */
export function mp4At(b: Uint8Array, start: number): MotionRange | null {
	if (start <= 0 || start + 16 > b.length) return null;
	const ftypSize = u32(b, start);
	if (fourcc(b, start + 4) !== 'ftyp' || ftypSize < 16 || ftypSize > 256 || !MP4_BRANDS.has(fourcc(b, start + 8))) return null;
	let off = start;
	let moov = false;
	let mdat = false;
	while (off + 8 <= b.length) {
		let n = u32(b, off);
		const type = fourcc(b, off + 4);
		if (!/^[\x20-\x7e]{4}$/.test(type)) break;
		if (n === 1) {
			if (off + 16 > b.length) break;
			n = u32(b, off + 8) * 2 ** 32 + u32(b, off + 12);
		} else if (n === 0) n = b.length - off;
		if (n < 8 || off + n > b.length) break;
		if (type === 'moov') moov = true;
		if (type === 'mdat') mdat = true;
		off += n;
	}
	return moov && mdat ? { start, end: off } : null;
}

/** Locates the embedded video of a Motion Photo JPEG, or null for ordinary photos. */
export function findMotionVideo(b: Uint8Array): MotionRange | null {
	if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return null;
	const head = latin1(b.subarray(0, Math.min(b.length, XMP_SCAN)));
	const x0 = head.indexOf('<x:xmpmeta');
	if (x0 < 0) return null;
	const x1 = head.indexOf('</x:xmpmeta>', x0);
	const xmp = head.slice(x0, x1 < 0 ? undefined : x1);
	if (!/(MotionPhoto|MicroVideo)\s*=\s*"1"|<\w+:(MotionPhoto|MicroVideo)>\s*1\s*</.test(xmp)) return null;

	const candidates: number[] = [];
	const micro = xmp.match(/MicroVideoOffset\s*=\s*"(\d+)"|<\w+:MicroVideoOffset>\s*(\d+)\s*</);
	if (micro) candidates.push(b.length - Number(micro[1] ?? micro[2]));
	for (const item of xmp.match(/<\w+:Item\b[^>]*>/g) ?? []) {
		if (!/\w+:Semantic\s*=\s*"MotionPhoto"/.test(item)) continue;
		const len = item.match(/\w+:Length\s*=\s*"(\d+)"/);
		if (len) candidates.push(b.length - Number(len[1]));
	}
	for (const c of candidates) {
		const r = mp4At(b, c);
		if (r) return r;
	}
	// Offsets missing or slightly off (some OEMs count padding): look for the last plausible ftyp box.
	for (let i = b.length - 8; i > 4; i--) {
		if (b[i] === 0x66 && b[i + 1] === 0x74 && b[i + 2] === 0x79 && b[i + 3] === 0x70) {
			const r = mp4At(b, i - 4);
			if (r) return r;
		}
	}
	return null;
}

/** Splits a Motion Photo into the still (the bytes before the video) and the clip. */
export function splitMotionPhoto(b: Uint8Array): { still: Uint8Array; clip: Uint8Array } | null {
	const r = findMotionVideo(b);
	if (!r) return null;
	return { still: b.subarray(0, r.start), clip: b.subarray(r.start, r.end) };
}

/** File-level helper: null unless `file` is a Motion Photo JPEG. */
export async function splitMotionPhotoFile(file: File, maxBytes: number): Promise<{ still: File; clip: File } | null> {
	const jpeg = file.type === 'image/jpeg' || /\.jpe?g$/i.test(file.name);
	if (!jpeg || file.size > maxBytes) return null;
	// Cheap check first: the XMP flag lives in the first megabyte.
	const head = latin1(new Uint8Array(await file.slice(0, XMP_SCAN).arrayBuffer()));
	if (!/MotionPhoto|MicroVideo/.test(head)) return null;
	const parts = splitMotionPhoto(new Uint8Array(await file.arrayBuffer()));
	if (!parts) return null;
	const base = file.name.replace(/\.[^.]+$/, '');
	return {
		still: new File([parts.still as BlobPart], file.name, { type: 'image/jpeg', lastModified: file.lastModified }),
		clip: new File([parts.clip as BlobPart], `${base}.mp4`, { type: 'video/mp4', lastModified: file.lastModified })
	};
}
