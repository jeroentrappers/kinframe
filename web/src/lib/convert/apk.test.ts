import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { checkApk, formatFingerprint, normalizeFingerprint, pkcs7Certificates, readZip, sha256Hex } from './apk';

// Fixtures: a two-file zip signed by apksigner with a throwaway test key (not the release key), with v1+v2 and with
// v2 only, plus the unsigned zip. Their certificate is TEST_CERT; the release certificate is pinned in plan.ts.
const fixture = (name: string) => new Uint8Array(readFileSync(new URL(`./fixtures/${name}`, import.meta.url)));
const TEST_CERT = '9e86c53b6f21177e7b2635a94dd558cb7cc570ff1f3c079a12e84d8d89c3e3cf';
const V1V2_SHA256 = '5bb9cff37a1d3d2a1c8566e9d523a124f6be7d337d5cf67766871fc42d07e2f0';

describe('fingerprints', () => {
	it('normalizes and formats', () => {
		expect(normalizeFingerprint('99:C5:E3 6c')).toBe('99c5e36c');
		expect(formatFingerprint('99c5e36c')).toBe('99:C5:E3:6C');
	});

	it('hashes', async () => {
		expect(await sha256Hex(new TextEncoder().encode('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
	});
});

describe('checkApk', () => {
	it('reads the v1 and v2 certificates and accepts the pinned one', async () => {
		const r = await checkApk(fixture('test-v1v2.apk'), { pinnedCert: TEST_CERT, sha256: V1V2_SHA256, deviceSdk: 23 });
		expect(r).toMatchObject({ ok: true, sha256: V1V2_SHA256, sha256Ok: true, v1Certs: [TEST_CERT], v2Certs: [TEST_CERT] });
	});

	it('accepts the fingerprint in apksigner/keytool notation', async () => {
		const r = await checkApk(fixture('test-v1v2.apk'), { pinnedCert: formatFingerprint(TEST_CERT), deviceSdk: 23 });
		expect(r.ok).toBe(true);
		expect(r.sha256Ok).toBeNull();
	});

	it('refuses another certificate', async () => {
		const r = await checkApk(fixture('test-v1v2.apk'), { pinnedCert: '99c5e36c1a7fd3740caa5115d9e303c982a18555f7bd29336b55e4481a2a205c', deviceSdk: 23 });
		expect(r).toMatchObject({ ok: false, problem: 'cert' });
	});

	it('refuses a wrong sha256 before anything else', async () => {
		const r = await checkApk(fixture('test-v1v2.apk'), { pinnedCert: TEST_CERT, sha256: '0'.repeat(64), deviceSdk: 23 });
		expect(r).toMatchObject({ ok: false, problem: 'sha256', sha256Ok: false });
	});

	it('needs a v1 signature for Android 6, not for Android 7+', async () => {
		const apk = fixture('test-v2only.apk');
		expect(await checkApk(apk, { pinnedCert: TEST_CERT, deviceSdk: 23 })).toMatchObject({ ok: false, problem: 'v1missing', v1Certs: [], v2Certs: [TEST_CERT] });
		expect(await checkApk(apk, { pinnedCert: TEST_CERT, deviceSdk: 24 })).toMatchObject({ ok: true });
	});

	it('refuses unsigned and broken files', async () => {
		expect(await checkApk(fixture('test-unsigned.apk'), { pinnedCert: TEST_CERT, deviceSdk: 23 })).toMatchObject({ ok: false, problem: 'unsigned' });
		expect(await checkApk(new TextEncoder().encode('<html>not found</html>'), { pinnedCert: TEST_CERT, deviceSdk: 23 })).toMatchObject({ ok: false, problem: 'corrupt' });
		const truncated = fixture('test-v1v2.apk').subarray(0, 3000);
		expect((await checkApk(truncated, { pinnedCert: TEST_CERT, deviceSdk: 23 })).ok).toBe(false);
	});
});

describe('zip and PKCS#7', () => {
	it('lists entries', () => {
		const names = readZip(fixture('test-v1v2.apk')).entries.map((e) => e.name);
		expect(names).toEqual(expect.arrayContaining(['AndroidManifest.xml', 'hello.txt', 'META-INF/MANIFEST.MF']));
	});

	it('returns no certificates for a SignedData without them', () => {
		// ContentInfo { OID, [0] { SignedData { INTEGER 1, SET {}, SEQUENCE {} } } }
		const der = new Uint8Array([0x30, 0x0e, 0x06, 0x01, 0x2a, 0xa0, 0x09, 0x30, 0x07, 0x02, 0x01, 0x01, 0x31, 0x00, 0x30, 0x00]);
		expect(pkcs7Certificates(der)).toEqual([]);
	});
});
