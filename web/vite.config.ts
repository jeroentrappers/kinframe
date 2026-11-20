import { readFileSync } from 'node:fs';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

// Crash reporting release (docs/spec/crash-reporting.md): KINFRAME_WEB_RELEASE (the Dockerfile sets
// kinframe-web@<version>+<revision>), else package.json's version. scripts/upload-sourcemaps.sh uploads under it.
const pkgVersion = (JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }).version;
const release = process.env.KINFRAME_WEB_RELEASE || `kinframe-web@${pkgVersion}`;

// In development the API lives on the Go server (default :8110). Everything the SPA
// talks to is proxied so the browser sees a single origin, exactly like production.
const target = process.env.KINFRAME_API ?? 'http://localhost:8110';
const proxy = {
	'/v1': { target, changeOrigin: true, ws: true },
	'/.well-known/kinframe': { target, changeOrigin: true },
	'/dev': { target, changeOrigin: true }
};

export default defineConfig({
	plugins: [sveltekit()],
	define: { __KINFRAME_WEB_RELEASE__: JSON.stringify(release) },
	// Maps for the crash reporting server only: no sourceMappingURL comments, and the Docker build deletes them.
	build: { sourcemap: 'hidden' },
	// Media preparation workers (src/lib/prep) are ES modules.
	worker: { format: 'es' },
	// Only imported lazily from the photo worker: pre-bundle it so `vite dev` doesn't reload when a HEIC is picked.
	optimizeDeps: { include: ['libheif-js/libheif-wasm/libheif.js'] },
	server: { proxy },
	preview: { proxy },
	test: {
		include: ['src/**/*.test.ts'],
		environment: 'node'
	}
});
