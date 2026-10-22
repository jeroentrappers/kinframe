import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		// Pure SPA: the Go server serves build/ and falls back to index.html for every
		// non-API path (including /f/{code} and /i/{token}).
		adapter: adapter({ pages: 'build', assets: 'build', fallback: 'index.html', precompress: false, strict: true }),
		alias: { $components: 'src/lib/components' }
	}
};

export default config;
