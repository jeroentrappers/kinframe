// A small tar writer for the convert tool's data backup (tools/convert/README.md, "The data backup"): the website
// pulls the app data from the frame file by file and archives it in the browser, like the script does with the
// computer's tar. POSIX ustar, with a pax extended header ('x') for names that don't fit ustar (over 100 bytes, or
// not ASCII), which GNU tar, bsdtar and 7-Zip all read. Pure functions apart from gzip (CompressionStream).

export interface TarEntry {
	/** Path inside the archive, `/`-separated, without a leading `/` (a directory without a trailing one). */
	path: string;
	type: 'file' | 'dir';
	/** Permission bits (e.g. 0o644). */
	mode: number;
	/** Seconds since the epoch. */
	mtime: number;
	data?: Uint8Array;
}

const BLOCK = 512;
const enc = new TextEncoder();

function octal(n: number, width: number): string {
	return n.toString(8).padStart(width - 1, '0') + '\0';
}

function header(name: Uint8Array, size: number, mode: number, mtime: number, type: string): Uint8Array {
	const h = new Uint8Array(BLOCK);
	const put = (s: string | Uint8Array, at: number) => h.set(typeof s === 'string' ? enc.encode(s) : s, at);
	put(name.subarray(0, 100), 0);
	put(octal(mode & 0o7777, 8), 100);
	put(octal(0, 8), 108); // uid
	put(octal(0, 8), 116); // gid
	put(octal(size, 12), 124);
	put(octal(Math.max(0, Math.floor(mtime)), 12), 136);
	put('        ', 148); // checksum placeholder
	put(type, 156);
	put('ustar\0', 257);
	put('00', 263);
	let sum = 0;
	for (const b of h) sum += b;
	put(sum.toString(8).padStart(6, '0') + '\0 ', 148);
	return h;
}

/** A pax record `<len> path=<value>\n`, whose length counts its own digits. */
function paxRecord(key: string, value: string): Uint8Array {
	const body = enc.encode(` ${key}=${value}\n`);
	let len = body.length + 1;
	while (String(len).length + body.length !== len) len = String(len).length + body.length;
	return enc.encode(`${len} ${key}=${value}\n`);
}

const pad = (n: number) => (BLOCK - (n % BLOCK)) % BLOCK;

/** The tar archive of `entries`, in order, ending with two zero blocks. */
export function tarArchive(entries: TarEntry[]): Uint8Array {
	const parts: Uint8Array[] = [];
	for (const e of entries) {
		const path = e.type === 'dir' ? `${e.path.replace(/\/+$/, '')}/` : e.path;
		const name = enc.encode(path);
		const data = e.type === 'file' ? (e.data ?? new Uint8Array()) : new Uint8Array();
		// eslint-disable-next-line no-control-regex
		if (name.length > 100 || /[^\x20-\x7e]/.test(path)) {
			const rec = paxRecord('path', path);
			parts.push(header(enc.encode('PaxHeader'), rec.length, 0o644, e.mtime, 'x'), rec, new Uint8Array(pad(rec.length)));
		}
		parts.push(header(name, data.length, e.mode, e.mtime, e.type === 'dir' ? '5' : '0'), data, new Uint8Array(pad(data.length)));
	}
	parts.push(new Uint8Array(BLOCK * 2));
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let o = 0;
	for (const p of parts) {
		out.set(p, o);
		o += p.length;
	}
	return out;
}

/** gzip, with the platform's CompressionStream (browsers, Node 18+). */
export async function gzip(data: Uint8Array): Promise<Uint8Array> {
	const stream = new Blob([data as BlobPart]).stream().pipeThrough(new CompressionStream('gzip'));
	return new Uint8Array(await new Response(stream).arrayBuffer());
}
