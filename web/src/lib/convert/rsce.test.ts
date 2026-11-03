import { describe, expect, it } from 'vitest';
import {
	RSCE_BLOCK,
	ResourceError,
	bmpInfo,
	brandResource,
	buildResource,
	parseResource,
	replaceResourceEntries,
	resourceDtb,
	resourceEntry,
	resourceProblem
} from './rsce';

// A synthetic resource image shaped like a Rockchip frame's (a device tree and two logos), built here so no
// third-party firmware is needed: the device tree is random bytes, the logos are tiny BMP headers.
const bytes = (n: number, seed: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed * 7 + ((i * i) >> 3)) & 0xff);

function bmp(width: number, height: number, bpp = 8, compression = 1, extra = 100): Uint8Array {
	const b = new Uint8Array(54 + extra);
	const v = new DataView(b.buffer);
	b.set([0x42, 0x4d]);
	v.setUint32(2, b.length, true);
	v.setUint32(10, 54, true);
	v.setUint32(14, 40, true);
	v.setInt32(18, width, true);
	v.setInt32(22, height, true);
	v.setUint16(26, 1, true);
	v.setUint16(28, bpp, true);
	v.setUint32(30, compression, true);
	b.fill(0xab, 54);
	return b;
}

const DTB = bytes(105148, 1);
const STOCK_LOGO = bmp(800, 1280, 8, 1, 30276 - 54);
const PARTITION = 1024 * 1024;
/** Byte comparison (toEqual on megabyte arrays is slow). */
const same = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i]);
const stock = () =>
	buildResource(
		[
			{ name: 'rk-kernel.dtb', data: DTB },
			{ name: 'logo.bmp', data: STOCK_LOGO },
			{ name: 'logo_kernel.bmp', data: STOCK_LOGO }
		],
		PARTITION
	);

describe('parseResource', () => {
	it('reads the header and the entries', () => {
		const img = stock();
		const p = parseResource(img);
		expect(p).toMatchObject({ headerBlocks: 1, tableBlock: 1, entryBlocks: 1, contentBlock: 4 });
		expect(p.entries).toEqual([
			{ name: 'rk-kernel.dtb', block: 4, size: DTB.length, hashSize: 0 },
			{ name: 'logo.bmp', block: 4 + Math.ceil(DTB.length / RSCE_BLOCK), size: STOCK_LOGO.length, hashSize: 0 },
			{ name: 'logo_kernel.bmp', block: 4 + Math.ceil(DTB.length / RSCE_BLOCK) + Math.ceil(STOCK_LOGO.length / RSCE_BLOCK), size: STOCK_LOGO.length, hashSize: 0 }
		]);
		expect(resourceEntry(img, p, 'rk-kernel.dtb')).toEqual(DTB);
		expect(resourceDtb(p)?.name).toBe('rk-kernel.dtb');
		expect(resourceProblem(img)).toBeNull();
	});

	it('refuses what is not a resource image', () => {
		expect(() => parseResource(new Uint8Array(4096))).toThrow(ResourceError);
		const img = stock();
		img[0x200] = 0x58; // entry tag
		expect(() => parseResource(img)).toThrow(/ENTR/);
		const short = stock().slice(0, 50 * RSCE_BLOCK); // contents cut off
		expect(() => parseResource(short)).toThrow(/out of bounds/);
		const zero = stock();
		zero.fill(0, 12, 16); // no entries
		expect(() => parseResource(zero)).toThrow(/entry count/);
	});

	it('flags an image without a device tree', () => {
		const img = buildResource([{ name: 'logo.bmp', data: STOCK_LOGO }], PARTITION);
		expect(resourceProblem(img)).toMatch(/device tree/);
		expect(resourceProblem(new Uint8Array(10))).toMatch(/RSCE/);
	});
});

describe('replaceResourceEntries', () => {
	it('round-trips: replacing every entry with itself gives the same bytes', () => {
		const img = stock();
		const p = parseResource(img);
		const itself = Object.fromEntries(p.entries.map((e) => [e.name, resourceEntry(img, p, e.name)!.slice()]));
		expect(same(replaceResourceEntries(img, itself), img)).toBe(true);
		expect(same(replaceResourceEntries(img, {}), img)).toBe(true);
	});

	it('moves later entries when a logo changes size and keeps the device tree identical', () => {
		const img = stock();
		const big = bmp(800, 1280, 8, 1, 70_000);
		const out = replaceResourceEntries(img, { 'logo.bmp': big });
		expect(out.length).toBe(img.length);
		const p = parseResource(out);
		expect(resourceEntry(out, p, 'rk-kernel.dtb')).toEqual(DTB);
		expect(resourceEntry(out, p, 'logo.bmp')).toEqual(big);
		expect(resourceEntry(out, p, 'logo_kernel.bmp')).toEqual(STOCK_LOGO);
		const logo = p.entries[1]!;
		expect(p.entries[2]!.block).toBe(logo.block + Math.ceil(big.length / RSCE_BLOCK));
		// Everything after the last entry is zero again.
		const last = p.entries[2]!;
		expect(out.subarray(last.block * RSCE_BLOCK + last.size).every((b) => b === 0)).toBe(true);
	});

	it('refuses entries it would have to re-hash, missing entries and images that would not fit', () => {
		const img = stock();
		img[0x400 + 4 + 256 + 8] = 32; // logo.bmp: hash size 32
		expect(() => replaceResourceEntries(img, { 'logo.bmp': STOCK_LOGO })).toThrow(/hash/);
		expect(() => replaceResourceEntries(stock(), { 'boot.bmp': STOCK_LOGO })).toThrow(/no boot.bmp/);
		expect(() => replaceResourceEntries(stock(), { 'logo.bmp': new Uint8Array(PARTITION) })).toThrow(/fit/);
	});
});

describe('brandResource', () => {
	it('replaces both logos when ours has the same format', () => {
		const ours = bmp(800, 1280, 8, 1, 20_000);
		const out = brandResource(stock(), ours);
		const p = parseResource(out);
		expect(resourceEntry(out, p, 'logo.bmp')).toEqual(ours);
		expect(resourceEntry(out, p, 'logo_kernel.bmp')).toEqual(ours);
		expect(resourceEntry(out, p, 'rk-kernel.dtb')).toEqual(DTB);
	});

	it('is a no-op with the frame’s own logo', () => {
		expect(same(brandResource(stock(), STOCK_LOGO), stock())).toBe(true);
	});

	it('refuses a logo of another size, depth or compression', () => {
		expect(() => brandResource(stock(), bmp(1280, 800))).toThrow(/800×1280/);
		expect(() => brandResource(stock(), bmp(800, 1280, 24, 0))).toThrow(ResourceError);
		expect(() => brandResource(stock(), new Uint8Array(100))).toThrow(/not a BMP/);
	});

	it('refuses an image without a device tree or without logos', () => {
		expect(() => brandResource(buildResource([{ name: 'logo.bmp', data: STOCK_LOGO }], PARTITION), STOCK_LOGO)).toThrow(/device tree/);
		expect(() => brandResource(buildResource([{ name: 'rk-kernel.dtb', data: DTB }], PARTITION), STOCK_LOGO)).toThrow(/no boot logo/);
	});
});

describe('bmpInfo', () => {
	it('reads the header', () => {
		expect(bmpInfo(bmp(800, 1280))).toEqual({ width: 800, height: 1280, bpp: 8, compression: 1 });
		expect(bmpInfo(bmp(10, -10, 24, 0))).toEqual({ width: 10, height: -10, bpp: 24, compression: 0 });
		expect(bmpInfo(new Uint8Array(60))).toBeNull();
	});
});
