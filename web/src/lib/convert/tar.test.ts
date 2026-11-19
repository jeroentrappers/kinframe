import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { gzip, tarArchive, type TarEntry } from './tar';

const enc = new TextEncoder();

/** Extracts a .tgz with the system tar into a fresh directory and hands it to `check`. */
function extract(tgz: Uint8Array, check: (dir: string, names: string[]) => void) {
	const dir = mkdtempSync(join(tmpdir(), 'kinframe-tar-'));
	try {
		writeFileSync(join(dir, 'a.tgz'), tgz);
		execFileSync('tar', ['-xzf', 'a.tgz'], { cwd: dir });
		check(dir, execFileSync('tar', ['-tzf', 'a.tgz'], { cwd: dir, encoding: 'utf8' }).split('\n').filter(Boolean));
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
}

describe('tar writer', () => {
	it('stores names of any length (pax), directories, modes and contents', async () => {
		const long = `files/${'x'.repeat(120)}.json`; // a 120-byte file name
		const deep = `${'d'.repeat(60)}/${'e'.repeat(60)}/f.txt`;
		// pax record lengths around a digit boundary (the record counts its own length)
		const edge = Array.from({ length: 6 }, (_, i) => `files/${'y'.repeat(85 + i)}`);
		const entries: TarEntry[] = [
			{ path: 'pkg', type: 'dir', mode: 0o751, mtime: 1700000000 },
			{ path: 'pkg/files', type: 'dir', mode: 0o771, mtime: 1700000000 },
			{ path: `pkg/${long}`, type: 'file', mode: 0o600, mtime: 1700000000, data: enc.encode('long') },
			{ path: `pkg/${deep}`, type: 'file', mode: 0o644, mtime: 1700000000, data: new Uint8Array(1025).fill(7) },
			{ path: 'pkg/files/foto-ä.jpg', type: 'file', mode: 0o644, mtime: 1700000000, data: enc.encode('ä') },
			{ path: 'pkg/empty', type: 'file', mode: 0o644, mtime: 1700000000, data: new Uint8Array() },
			...edge.map((p): TarEntry => ({ path: `pkg/${p}`, type: 'file', mode: 0o644, mtime: 1, data: enc.encode(p) }))
		];
		const tar = tarArchive(entries);
		expect(tar.length % 512).toBe(0);
		extract(await gzip(tar), (dir, names) => {
			expect(names).toContain(`pkg/${long}`);
			expect(readFileSync(join(dir, 'pkg', long), 'utf8')).toBe('long');
			expect(readFileSync(join(dir, 'pkg', deep))).toEqual(Buffer.alloc(1025, 7));
			expect(readFileSync(join(dir, 'pkg/files/foto-ä.jpg'), 'utf8')).toBe('ä');
			expect(readFileSync(join(dir, 'pkg/empty')).length).toBe(0);
			for (const p of edge) expect(readFileSync(join(dir, 'pkg', p), 'utf8')).toBe(p);
			expect(statSync(join(dir, 'pkg', long)).mode & 0o777).toBe(0o600);
			expect(statSync(join(dir, 'pkg', long)).mtimeMs).toBe(1700000000 * 1000);
			expect(names.some((n) => n.includes('PaxHeader'))).toBe(false);
		});
	});
});
