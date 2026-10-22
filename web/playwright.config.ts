import { defineConfig, devices } from '@playwright/test';

// The SPA is built once and served by `vite preview`; every API call is intercepted by
// tests/mock-api.ts, so no Go server is needed. Uses the system Chrome when present
// (set PW_CHANNEL= to use Playwright's bundled Chromium instead).
const channel = process.env.PW_CHANNEL ?? 'chrome';

export default defineConfig({
	testDir: 'tests',
	timeout: 30_000,
	fullyParallel: true,
	reporter: [['list']],
	use: {
		baseURL: 'http://localhost:4173',
		trace: 'retain-on-failure',
		...(channel ? { channel } : {})
	},
	projects: [
		{ name: 'desktop', use: { ...devices['Desktop Chrome'], ...(channel ? { channel } : {}) }, testIgnore: /screenshots/ },
		{ name: 'phone', use: { ...devices['Pixel 7'], ...(channel ? { channel } : {}) }, testIgnore: /screenshots/ },
		{ name: 'screenshots', testMatch: /screenshots\.spec\.ts/, use: { ...(channel ? { channel } : {}) } }
	],
	webServer: {
		command: 'npm run build && npx vite preview --port 4173 --strictPort',
		url: 'http://localhost:4173',
		reuseExistingServer: !process.env.CI,
		timeout: 120_000
	}
});
