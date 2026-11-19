import { defineConfig, devices } from '@playwright/test';

// End-to-end runs of the web app against a real kinframe server in dev-auth mode (see README.md next to this file).
// The server serves this build (KINFRAME_WEB_DIR=web/build), so the app and the API share one origin.
const channel = process.env.PW_CHANNEL ?? 'chrome';
const base = process.env.REAL_SERVER ?? 'http://localhost:18110';

export default defineConfig({
	testDir: '.',
	testMatch: /\.spec\.ts$/,
	timeout: 90_000,
	expect: { timeout: 10_000 },
	fullyParallel: false,
	workers: 1,
	reporter: [['list']],
	use: {
		baseURL: base,
		trace: 'retain-on-failure',
		...devices['Desktop Chrome'],
		...(channel ? { channel } : {}),
		permissions: ['microphone'],
		launchOptions: { args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] }
	}
});
