// APK checks for the convert tool: the file's sha256 and the certificates it is signed with, read from the
// v1 (JAR, META-INF/*.RSA|DSA|EC PKCS#7) signature and the v2/v3 APK Signing Block.
//
// This reads which certificate an APK claims; it does not verify the signatures cryptographically. That is enough
// for pinning: Android itself verifies the signature on install (v1 on Android 6, v2+ from 7), so an APK that names
// the kinframe release certificate but was not signed with its key never installs. Requiring every certificate found
// to equal the pinned one, and requiring a v1 signature for Android 6 frames, closes the gap between what we read
// and what the frame checks.

export interface ApkCheck {
	sha256: string;
	/** null when no expected sha256 was given. */
	sha256Ok: boolean | null;
	v1Certs: string[];
	v2Certs: string[];
	ok: boolean;
	problem?: 'corrupt' | 'sha256' | 'unsigned' | 'cert' | 'v1missing';
}

export async function sha256Hex(data: Uint8Array | ArrayBuffer): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', data as BufferSource);
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** `99:C5:E3:…` or `99c5e3…` → `99c5e3…`. */
export function normalizeFingerprint(fp: string): string {
	return fp.replace(/[\s:]/g, '').toLowerCase();
}

/** `99c5e3…` → `99:C5:E3:…`, as apksigner and keytool print it. */
export function formatFingerprint(hex: string): string {
	return (normalizeFingerprint(hex).match(/../g) ?? []).join(':').toUpperCase();
}

/**
 * Checks an APK against the pinned release certificate (and the published sha256, when known).
 * `deviceSdk` < 24 requires a v1 signature, the only scheme Android 6 checks.
 */
export async function checkApk(bytes: Uint8Array, opts: { pinnedCert: string; sha256?: string; deviceSdk: number }): Promise<ApkCheck> {
	const sha256 = await sha256Hex(bytes);
	const sha256Ok = opts.sha256 ? normalizeFingerprint(opts.sha256) === sha256 : null;
	const result: ApkCheck = { sha256, sha256Ok, v1Certs: [], v2Certs: [], ok: false };
	if (sha256Ok === false) return { ...result, problem: 'sha256' };
	let zip: Zip;
	try {
		zip = readZip(bytes);
		result.v1Certs = await v1Certificates(bytes, zip);
		result.v2Certs = await signingBlockCertificates(bytes, zip.cdOffset);
	} catch {
		return { ...result, problem: 'corrupt' };
	}
	const all = [...result.v1Certs, ...result.v2Certs];
	if (!all.length) return { ...result, problem: 'unsigned' };
	const pinned = normalizeFingerprint(opts.pinnedCert);
	if (all.some((c) => c !== pinned)) return { ...result, problem: 'cert' };
	if (opts.deviceSdk < 24 && !result.v1Certs.length) return { ...result, problem: 'v1missing' };
	return { ...result, ok: true };
}

// ---------------------------------------------------------------- ZIP

/** Whether the bytes are a readable ZIP (APK) file at all. */
export function isZip(bytes: Uint8Array): boolean {
	try {
		readZip(bytes);
		return true;
	} catch {
		return false;
	}
}

interface ZipEntry {
	name: string;
	method: number;
	compressedSize: number;
	localOffset: number;
}

interface Zip {
	entries: ZipEntry[];
	cdOffset: number;
}

const u16 = (b: Uint8Array, o: number) => b[o]! | (b[o + 1]! << 8);
const u32 = (b: Uint8Array, o: number) => (b[o]! | (b[o + 1]! << 8) | (b[o + 2]! << 16) | (b[o + 3]! << 24)) >>> 0;
const u64 = (b: Uint8Array, o: number) => u32(b, o) + u32(b, o + 4) * 2 ** 32;

function need(cond: boolean, what: string): asserts cond {
	if (!cond) throw new Error(`malformed APK: ${what}`);
}

export function readZip(b: Uint8Array): Zip {
	let eocd = -1;
	for (let i = b.length - 22; i >= Math.max(0, b.length - 22 - 0xffff); i--) {
		if (u32(b, i) === 0x06054b50) {
			eocd = i;
			break;
		}
	}
	need(eocd >= 0, 'no end of central directory');
	const count = u16(b, eocd + 10);
	const cdSize = u32(b, eocd + 12);
	const cdOffset = u32(b, eocd + 16);
	need(cdOffset + cdSize <= eocd, 'central directory out of range');
	const entries: ZipEntry[] = [];
	let o = cdOffset;
	const dec = new TextDecoder();
	for (let i = 0; i < count; i++) {
		need(o + 46 <= b.length && u32(b, o) === 0x02014b50, 'bad central directory entry');
		const nameLen = u16(b, o + 28);
		entries.push({
			name: dec.decode(b.subarray(o + 46, o + 46 + nameLen)),
			method: u16(b, o + 10),
			compressedSize: u32(b, o + 20),
			localOffset: u32(b, o + 42)
		});
		o += 46 + nameLen + u16(b, o + 30) + u16(b, o + 32);
	}
	return { entries, cdOffset };
}

async function entryData(b: Uint8Array, e: ZipEntry): Promise<Uint8Array> {
	const o = e.localOffset;
	need(u32(b, o) === 0x04034b50, 'bad local header');
	const start = o + 30 + u16(b, o + 26) + u16(b, o + 28);
	need(start + e.compressedSize <= b.length, 'entry out of range');
	const raw = b.subarray(start, start + e.compressedSize);
	if (e.method === 0) return raw;
	need(e.method === 8, 'unsupported compression');
	const stream = new Blob([raw as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
	return new Uint8Array(await new Response(stream).arrayBuffer());
}

// ---------------------------------------------------------------- v1 (PKCS#7 in META-INF)

const V1_SIGNATURE = /^META-INF\/[^/]+\.(RSA|DSA|EC)$/i;

async function v1Certificates(b: Uint8Array, zip: Zip): Promise<string[]> {
	const out: string[] = [];
	for (const e of zip.entries.filter((x) => V1_SIGNATURE.test(x.name))) {
		for (const cert of pkcs7Certificates(await entryData(b, e))) out.push(await sha256Hex(cert));
	}
	return out;
}

interface Tlv {
	tag: number;
	start: number; // content start
	end: number; // content end
	next: number; // offset after this TLV
	offset: number; // TLV start
}

function tlv(b: Uint8Array, o: number, limit = b.length): Tlv {
	need(o + 2 <= limit, 'DER truncated');
	const tag = b[o]!;
	let len = b[o + 1]!;
	let p = o + 2;
	if (len & 0x80) {
		const n = len & 0x7f;
		need(n >= 1 && n <= 4 && p + n <= limit, 'DER length');
		len = 0;
		for (let i = 0; i < n; i++) len = len * 256 + b[p + i]!;
		p += n;
	}
	need(p + len <= limit, 'DER content out of range');
	return { tag, start: p, end: p + len, next: p + len, offset: o };
}

function children(b: Uint8Array, parent: Tlv): Tlv[] {
	const out: Tlv[] = [];
	for (let o = parent.start; o < parent.end; ) {
		const t = tlv(b, o, parent.end);
		out.push(t);
		o = t.next;
	}
	return out;
}

/** The DER certificates in a PKCS#7 SignedData (ContentInfo → [0] SignedData → [0] certificates). */
export function pkcs7Certificates(der: Uint8Array): Uint8Array[] {
	const contentInfo = tlv(der, 0);
	need(contentInfo.tag === 0x30, 'ContentInfo');
	const [, explicit] = children(der, contentInfo);
	need(explicit?.tag === 0xa0, 'SignedData wrapper');
	const signedData = children(der, explicit)[0];
	need(signedData?.tag === 0x30, 'SignedData');
	const certs = children(der, signedData).find((c) => c.tag === 0xa0);
	if (!certs) return [];
	return children(der, certs)
		.filter((c) => c.tag === 0x30)
		.map((c) => der.subarray(c.offset, c.next));
}

// ---------------------------------------------------------------- v2/v3 (APK Signing Block)

const SIG_BLOCK_MAGIC = 'APK Sig Block 42';
const V2_ID = 0x7109871a;
const V3_IDS = [0xf05368c0, 0x1b93ad61];

/** Certificates of every signer in the v2 and v3 blocks (empty when the APK has no signing block). */
async function signingBlockCertificates(b: Uint8Array, cdOffset: number): Promise<string[]> {
	if (cdOffset < 32) return [];
	const magic = new TextDecoder().decode(b.subarray(cdOffset - 16, cdOffset));
	if (magic !== SIG_BLOCK_MAGIC) return [];
	const size = u64(b, cdOffset - 24);
	const start = cdOffset - size - 8;
	need(start >= 0 && u64(b, start) === size, 'signing block size');
	const out: string[] = [];
	for (let o = start + 8; o < cdOffset - 24; ) {
		const len = u64(b, o);
		need(len >= 4 && o + 8 + len <= cdOffset - 24, 'signing block pair');
		const id = u32(b, o + 8);
		if (id === V2_ID || V3_IDS.includes(id)) {
			for (const cert of signerCertificates(b.subarray(o + 12, o + 8 + len))) out.push(await sha256Hex(cert));
		}
		o += 8 + len;
	}
	return out;
}

/** Splits a sequence of uint32-length-prefixed values. */
function lpSequence(b: Uint8Array): Uint8Array[] {
	const out: Uint8Array[] = [];
	for (let o = 0; o < b.length; ) {
		need(o + 4 <= b.length, 'length prefix');
		const len = u32(b, o);
		need(o + 4 + len <= b.length, 'length-prefixed value');
		out.push(b.subarray(o + 4, o + 4 + len));
		o += 4 + len;
	}
	return out;
}

/** v2/v3 value: signers → signer (signed data → digests, certificates, …) → certificates. */
function signerCertificates(value: Uint8Array): Uint8Array[] {
	const [signers] = lpSequence(value);
	need(Boolean(signers), 'signers');
	const out: Uint8Array[] = [];
	for (const signer of lpSequence(signers!)) {
		need(signer.length >= 4, 'signer');
		const signedLen = u32(signer, 0);
		need(4 + signedLen <= signer.length, 'signed data');
		const signed = signer.subarray(4, 4 + signedLen);
		// signed data: digests (length-prefixed), certificates (length-prefixed), …
		need(signed.length >= 4, 'digests');
		const digestsLen = u32(signed, 0);
		const certsAt = 4 + digestsLen;
		need(certsAt + 4 <= signed.length, 'certificates');
		const certsLen = u32(signed, certsAt);
		need(certsAt + 4 + certsLen <= signed.length, 'certificates length');
		out.push(...lpSequence(signed.subarray(certsAt + 4, certsAt + 4 + certsLen)));
	}
	return out;
}
