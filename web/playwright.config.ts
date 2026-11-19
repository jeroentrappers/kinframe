import { defineConfig, devices } from '@playwright/test';

// The SPA is built once and served by `vite preview`; every API call is intercepted by
// tests/mock-api.ts, so no Go server is needed. Uses the system Chrome when present
// (set PW_CHANNEL= to use Playwright's bundled Chromium instead). tests/integration/ runs against a real server
// with its own config (see tests/integration/README.md).
const channel = process.env.PW_CHANNEL ?? 'chrome';
// PW_PORT lets several checkouts run their suites side by side.
const port = Number(process.env.PW_PORT ?? 4173);

export default defineConfig({
	testDir: 'tests',
	timeout: 30_000,
	fullyParallel: true,
	reporter: [['list']],
	use: {
		baseURL: `http://localhost:${port}`,
		trace: 'retain-on-failure',
		...(channel ? { channel } : {})
	},
	projects: [
		{ name: 'desktop', use: { ...devices['Desktop Chrome'], ...(channel ? { channel } : {}) }, testIgnore: [/screenshots/, /integration\//] },
		{ name: 'phone', use: { ...devices['Pixel 7'], ...(channel ? { channel } : {}) }, testIgnore: [/screenshots/, /integration\//] },
		{ name: 'screenshots', testMatch: /screenshots\.spec\.ts/, use: { ...(channel ? { channel } : {}) } }
	],
	webServer: {
		command: `npm run build && npx vite preview --port ${port} --strictPort`,
		url: `http://localhost:${port}`,
		reuseExistingServer: !process.env.CI,
		timeout: 120_000
	}
});
