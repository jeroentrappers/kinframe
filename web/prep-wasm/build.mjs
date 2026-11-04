// Builds the photo preparation WebAssembly module into src/lib/prep/wasm, where it is committed (so `npm run build`
// and the Docker image need no Rust). Needs rustup (the toolchain is pinned in rust-toolchain.toml) and wasm-pack.
// Paths are remapped so the output doesn't depend on where the checkout or the cargo home is: the same toolchain
// gives the same bytes, which CI checks.
import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const crate = dirname(fileURLToPath(import.meta.url));
const out = resolve(crate, '../src/lib/prep/wasm');
const cargoHome = process.env.CARGO_HOME ?? join(homedir(), '.cargo');
const rustflags = [`--remap-path-prefix=${cargoHome}=/cargo`, `--remap-path-prefix=${crate}=/kinframe-prep`].join(' ');

execFileSync(
	'wasm-pack',
	['build', crate, '--release', '--target', 'web', '--no-pack', '--out-dir', out, '--out-name', 'kinframe_prep'],
	{ stdio: 'inherit', env: { ...process.env, RUSTFLAGS: rustflags } }
);
// wasm-pack writes a .gitignore that ignores everything; the module is committed.
rmSync(join(out, '.gitignore'), { force: true });
