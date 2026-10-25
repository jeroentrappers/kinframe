// Rockchip resource images ("RSCE"): the resource partition of Rockchip frames holds the kernel device tree and the
// boot logos the bootloader and kernel show (docs/hardware/denver-pff-1024.md, "Boot chain"). The convert tool
// replaces the two logos and keeps everything else, the device tree byte for byte.
//
// Layout, in 512-byte blocks, all numbers little-endian:
//   block 0                header: "RSCE", u16 partition version, u16 table version, u8 header size (blocks),
//                          u8 table offset (blocks), u8 entry size (blocks), u8 padding, u32 entry count
//   table offset + i·size  entry i: "ENTR", name (256 bytes, NUL-terminated), u32 content offset (blocks),
//                          u32 content size (bytes), and in newer images u32 hash size + hash (20 or 32 bytes)
//   content                each entry's file, starting on a block boundary
// The rest of the partition is zero.
//
// Pure functions only, shared by the website and the unit tests.

export const RSCE_BLOCK = 512;
const MAGIC = 'RSCE';
const ENTRY_TAG = 'ENTR';
const NAME_LEN = 256;
/** Offsets inside an entry. */
const E_NAME = 4;
const E_OFFSET = E_NAME + NAME_LEN;
const E_SIZE = E_OFFSET + 4;
const E_HASH_SIZE = E_SIZE + 4;

export interface ResourceEntry {
	name: string;
	/** Content offset in blocks. */
	block: number;
	/** Content size in bytes. */
	size: number;
	/** Size of the entry's own hash (0: none). */
	hashSize: number;
}

export interface ResourceImage {
	headerBlocks: number;
	tableBlock: number;
	entryBlocks: number;
	entries: ResourceEntry[];
	/** First block after the entry table. */
	contentBlock: number;
}

export class ResourceError extends Error {}

const u32 = (b: Uint8Array, o: number) => (b[o]! | (b[o + 1]! << 8) | (b[o + 2]! << 16) | (b[o + 3]! << 24)) >>> 0;
const ascii = (b: Uint8Array, o: number, n: number) => String.fromCharCode(...b.subarray(o, o + n));

function putU32(b: Uint8Array, o: number, v: number) {
	b[o] = v & 0xff;
	b[o + 1] = (v >>> 8) & 0xff;
	b[o + 2] = (v >>> 16) & 0xff;
	b[o + 3] = (v >>> 24) & 0xff;
}

/** Parses and checks a resource image. Throws ResourceError when it isn't one, or when an entry is out of bounds. */
export function parseResource(img: Uint8Array): ResourceImage {
	if (img.length < RSCE_BLOCK || ascii(img, 0, 4) !== MAGIC) throw new ResourceError('not a Rockchip resource image (no RSCE header)');
	const headerBlocks = img[8]!;
	const tableBlock = img[9]!;
	const entryBlocks = img[10]!;
	const count = u32(img, 12);
	if (headerBlocks < 1 || entryBlocks < 1 || tableBlock < headerBlocks) throw new ResourceError('bad RSCE header');
	if (count < 1 || count > 256) throw new ResourceError(`bad RSCE entry count ${count}`);
	const contentBlock = tableBlock + count * entryBlocks;
	if (contentBlock * RSCE_BLOCK > img.length) throw new ResourceError('RSCE entry table is cut off');
	const entries: ResourceEntry[] = [];
	for (let i = 0; i < count; i++) {
		const o = (tableBlock + i * entryBlocks) * RSCE_BLOCK;
		if (ascii(img, o, 4) !== ENTRY_TAG) throw new ResourceError(`RSCE entry ${i} has no ENTR tag`);
		const raw = img.subarray(o + E_NAME, o + E_NAME + NAME_LEN);
		const end = raw.indexOf(0);
		if (end <= 0) throw new ResourceError(`RSCE entry ${i} has no name`);
		const name = String.fromCharCode(...raw.subarray(0, end));
		if (!/^[\x21-\x7e]+$/.test(name)) throw new ResourceError(`RSCE entry ${i} has a bad name`);
		const block = u32(img, o + E_OFFSET);
		const size = u32(img, o + E_SIZE);
		const hashSize = entryBlocks * RSCE_BLOCK >= E_HASH_SIZE + 4 ? u32(img, o + E_HASH_SIZE) : 0;
		if (block < contentBlock || block * RSCE_BLOCK + size > img.length) throw new ResourceError(`RSCE entry ${name} is out of bounds`);
		if (entries.some((e) => e.name === name)) throw new ResourceError(`RSCE entry ${name} appears twice`);
		entries.push({ name, block, size, hashSize });
	}
	return { headerBlocks, tableBlock, entryBlocks, entries, contentBlock };
}

/** An entry's content. */
export function resourceEntry(img: Uint8Array, parsed: ResourceImage, name: string): Uint8Array | undefined {
	const e = parsed.entries.find((x) => x.name === name);
	return e ? img.subarray(e.block * RSCE_BLOCK, e.block * RSCE_BLOCK + e.size) : undefined;
}

/** The kernel device tree entry (`*.dtb`); a resource image without one would not boot. */
export function resourceDtb(parsed: ResourceImage): ResourceEntry | undefined {
	return parsed.entries.find((e) => e.name.endsWith('.dtb'));
}

/**
 * Whether a resource image is safe to write to a frame: it parses, every entry fits, and it carries a device tree.
 * Returns the problem, or null.
 */
export function resourceProblem(img: Uint8Array): string | null {
	try {
		const p = parseResource(img);
		if (!resourceDtb(p)) return 'no device tree (.dtb) in the resource image';
		if (img.length % RSCE_BLOCK) return 'the resource image is not a whole number of 512-byte blocks';
		return null;
	} catch (e) {
		return e instanceof Error ? e.message : String(e);
	}
}

/**
 * Rebuilds the image with some entries' content replaced. The header and entry blocks are kept (only offsets and
 * sizes change), contents are laid out in table order from the first block after the table, each on a block
 * boundary, and the image keeps its length (the partition size), zero-filled. Entries that carry their own hash
 * can't be replaced (we would have to recompute it; no supported frame uses them).
 */
export function replaceResourceEntries(img: Uint8Array, replace: Record<string, Uint8Array>): Uint8Array {
	const p = parseResource(img);
	for (const name of Object.keys(replace)) {
		const e = p.entries.find((x) => x.name === name);
		if (!e) throw new ResourceError(`no ${name} in the resource image`);
		if (e.hashSize) throw new ResourceError(`${name} carries a hash; replacing it is not supported`);
	}
	const out = new Uint8Array(img.length);
	out.set(img.subarray(0, p.contentBlock * RSCE_BLOCK));
	let block = p.contentBlock;
	p.entries.forEach((e, i) => {
		const data = replace[e.name] ?? img.subarray(e.block * RSCE_BLOCK, e.block * RSCE_BLOCK + e.size);
		const at = block * RSCE_BLOCK;
		if (at + data.length > out.length) throw new ResourceError('the new resource image does not fit in the partition');
		out.set(data, at);
		const o = (p.tableBlock + i * p.entryBlocks) * RSCE_BLOCK;
		putU32(out, o + E_OFFSET, block);
		putU32(out, o + E_SIZE, data.length);
		block += Math.ceil(data.length / RSCE_BLOCK);
	});
	return out;
}

/** Builds a resource image from scratch (tests, and documentation of the format). */
export function buildResource(files: { name: string; data: Uint8Array }[], length: number): Uint8Array {
	const contentBlock = 1 + files.length;
	const out = new Uint8Array(length);
	out.set([...MAGIC].map((c) => c.charCodeAt(0)), 0);
	out[8] = 1; // header size
	out[9] = 1; // table offset
	out[10] = 1; // entry size
	putU32(out, 12, files.length);
	let block = contentBlock;
	files.forEach((f, i) => {
		const o = (1 + i) * RSCE_BLOCK;
		out.set([...ENTRY_TAG].map((c) => c.charCodeAt(0)), o);
		out.set([...f.name].map((c) => c.charCodeAt(0)), o + E_NAME);
		putU32(out, o + E_OFFSET, block);
		putU32(out, o + E_SIZE, f.data.length);
		if (block * RSCE_BLOCK + f.data.length > length) throw new ResourceError('files do not fit');
		out.set(f.data, block * RSCE_BLOCK);
		block += Math.ceil(f.data.length / RSCE_BLOCK);
	});
	return out;
}

// ---------------------------------------------------------------- boot logos (BMP)

export interface BmpInfo {
	width: number;
	/** Negative for top-down rows. */
	height: number;
	bpp: number;
	/** 0 none, 1 RLE8, 2 RLE4, 3 bitfields. */
	compression: number;
}

/** The format of a Windows BMP, or null when it isn't one. */
export function bmpInfo(b: Uint8Array): BmpInfo | null {
	if (b.length < 54 || b[0] !== 0x42 || b[1] !== 0x4d) return null;
	const headerSize = u32(b, 14);
	if (headerSize < 40) return null;
	const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
	return { width: v.getInt32(18, true), height: v.getInt32(22, true), bpp: v.getUint16(28, true), compression: u32(b, 30) };
}

/** The logo entries the convert tool replaces, when the image has them. */
export const LOGO_ENTRIES = ['logo.bmp', 'logo_kernel.bmp'] as const;

/**
 * Puts our logo in place of the frame's boot logos. Refuses (ResourceError) when the image has no device tree or no
 * logo, or when our logo's size, depth or compression differs from the frame's: the bootloader only shows what it
 * was built for, and the panel has one resolution.
 */
export function brandResource(img: Uint8Array, logo: Uint8Array): Uint8Array {
	const problem = resourceProblem(img);
	if (problem) throw new ResourceError(problem);
	const p = parseResource(img);
	const ours = bmpInfo(logo);
	if (!ours) throw new ResourceError('the new logo is not a BMP file');
	const replace: Record<string, Uint8Array> = {};
	for (const name of LOGO_ENTRIES) {
		const theirs = resourceEntry(img, p, name);
		if (!theirs) continue;
		const t = bmpInfo(theirs);
		if (!t) throw new ResourceError(`${name} on the frame is not a BMP file`);
		if (t.width !== ours.width || t.height !== ours.height || t.bpp !== ours.bpp || t.compression !== ours.compression) {
			throw new ResourceError(
				`${name} on the frame is ${t.width}×${t.height}, ${t.bpp} bits, compression ${t.compression}; the new logo is ${ours.width}×${ours.height}, ${ours.bpp} bits, compression ${ours.compression}`
			);
		}
		replace[name] = logo;
	}
	if (!Object.keys(replace).length) throw new ResourceError('no boot logo in the resource image');
	return replaceResourceEntries(img, replace);
}
