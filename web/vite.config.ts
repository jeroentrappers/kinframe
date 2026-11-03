import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

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
	// Media preparation workers (src/lib/prep) are ES modules.
	worker: { format: 'es' },
	server: { proxy },
	preview: { proxy },
	test: {
		include: ['src/**/*.test.ts'],
		environment: 'node'
	}
});
