/**
 * The colour description of a HEIF file's primary image (its `colr` property): an ICC profile, or `nclx` colour
 * primaries. libheif hands out RGB in the image's own primaries, and its JavaScript build doesn't expose the profile,
 * so it is read from the boxes here and the pixels are converted to sRGB by the WebAssembly module.
 */

export interface HeifColour {
	icc: Uint8Array | null;
	/** `nclx` colour primaries 12: Display P3 (iPhones and most Android phones without an ICC profile). */
	p3: boolean;
}

interface Box {
	type: string;
	start: number;
	body: number;
	end: number;
}

const u16 = (b: Uint8Array, at: number) => (b[at]! << 8) | b[at + 1]!;
const u32 = (b: Uint8Array, at: number) => ((b[at]! << 24) | (b[at + 1]! << 16) | (b[at + 2]! << 8) | b[at + 3]!) >>> 0;
const fourcc = (b: Uint8Array, at: number) => String.fromCharCode(b[at]!, b[at + 1]!, b[at + 2]!, b[at + 3]!);

function* boxes(b: Uint8Array, from: number, to: number): Generator<Box> {
	let at = from;
	while (at + 8 <= to) {
		let size = u32(b, at);
		let body = at + 8;
		if (size === 1) {
			if (at + 16 > to) return;
			size = u32(b, at + 8) * 2 ** 32 + u32(b, at + 12);
			body = at + 16;
		} else if (size === 0) size = to - at;
		if (size < body - at || at + size > to) return;
		yield { type: fourcc(b, at + 4), start: at, body, end: at + size };
		at += size;
	}
}

const child = (b: Uint8Array, parent: Box, type: string, skip = 0) => {
	for (const x of boxes(b, parent.body + skip, parent.end)) if (x.type === type) return x;
	return null;
};

function colourOf(b: Uint8Array, colr: Box[]): HeifColour | null {
	const prof = colr.find((c) => ['prof', 'rICC'].includes(fourcc(b, c.body)));
	if (prof) return { icc: b.slice(prof.body + 4, prof.end), p3: false };
	const nclx = colr.find((c) => fourcc(b, c.body) === 'nclx' && c.body + 6 <= c.end);
	if (nclx) return { icc: null, p3: u16(b, nclx.body + 4) === 12 };
	return null;
}

/** The primary image's colour description; sRGB (`{icc: null, p3: false}`) when there is none or on junk. */
export function heifColour(b: Uint8Array): HeifColour {
	const srgb = { icc: null, p3: false };
	try {
		let meta: Box | null = null;
		for (const x of boxes(b, 0, b.length)) if (x.type === 'meta') meta = x;
		if (!meta) return srgb;
		const pitm = child(b, meta, 'pitm', 4);
		const iprp = child(b, meta, 'iprp', 4);
		const ipco = iprp && child(b, iprp, 'ipco');
		if (!ipco) return srgb;
		const props = [...boxes(b, ipco.body, ipco.end)];
		const allColr = props.filter((p) => p.type === 'colr' && p.body + 4 <= p.end);
		const primary = pitm ? (b[pitm.body] === 0 ? u16(b, pitm.body + 4) : u32(b, pitm.body + 4)) : null;
		const ipma = iprp && child(b, iprp, 'ipma');
		if (ipma && primary !== null) {
			const version = b[ipma.body]!;
			const wide = (b[ipma.body + 3]! & 1) === 1;
			let at = ipma.body + 4;
			const count = u32(b, at);
			at += 4;
			for (let e = 0; e < count && at < ipma.end; e++) {
				const item = version < 1 ? u16(b, at) : u32(b, at);
				at += version < 1 ? 2 : 4;
				const n = b[at++]!;
				const mine: Box[] = [];
				for (let k = 0; k < n; k++) {
					const index = wide ? u16(b, at) & 0x7fff : b[at]! & 0x7f;
					at += wide ? 2 : 1;
					const p = props[index - 1];
					if (item === primary && p?.type === 'colr') mine.push(p);
				}
				if (item === primary) {
					const c = colourOf(b, mine);
					if (c) return c;
				}
			}
		}
		// A grid's colour usually sits on its tiles: they all share one, so take the first.
		return colourOf(b, allColr) ?? srgb;
	} catch {
		return srgb;
	}
}
