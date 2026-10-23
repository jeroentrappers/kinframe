import { describe, expect, it } from 'vitest';
import { DEFAULT_SERVER, FRAME_PACKAGE, FRAMEO_PACKAGE, WORK_DIR, planConvert, planRevert, type StepId } from './plan';
import { identifyFrame, isReadOnly, runPlan, type FrameShell, type RunHooks, type StepStatus } from './runner';

const GETPROP = ['[ro.product.brand]: [denver]', '[ro.product.device]: [PFF_1024]', '[ro.product.model]: [Frame]', '[ro.build.version.sdk]: [23]', '[ro.product.cpu.abilist]: [armeabi-v7a,armeabi]'].join('\r\n');

/** A scripted frame: answers commands from a table and records everything that happens to it. */
class FakeFrame implements FrameShell {
	calls: string[] = [];
	pushed: string[] = [];
	reboots = 0;
	constructor(
		private answers: (cmd: string) => string | undefined,
		private enabled: Record<string, boolean> = {}
	) {}
	async shell(cmd: string) {
		this.calls.push(cmd);
		const a = this.answers(cmd);
		if (a !== undefined) return a;
		if (cmd === 'getprop') return GETPROP;
		if (cmd === 'su 0 id') return 'uid=0(root) gid=0(root)\r\n';
		const pkg = /^pm path (\S+)/.exec(cmd)?.[1];
		if (pkg) return pkg in this.enabled ? `package:/data/app/${pkg}-1/base.apk\r\n` : '';
		const info = /^dumpsys package (\S+)/.exec(cmd)?.[1];
		if (info) return `  Package [${info}]\r\n    versionCode=1\r\n    versionName=1.0\r\n    User 0:  installed=true enabled=${this.enabled[info] ? 0 : 3}\r\n`;
		if (cmd.startsWith('pm install')) return 'Success\r\n';
		if (cmd.includes('disable-user')) return `Package ${cmd.split(' ').pop()} new state: disabled-user\r\n`;
		if (cmd.includes('pm enable')) return `Package ${cmd.split(' ').pop()} new state: enabled\r\n`;
		if (cmd.includes('pm uninstall')) return 'Success\r\n';
		if (cmd === 'dumpsys activity activities') return `  mFocusedActivity: ActivityRecord{1 u0 ${FRAME_PACKAGE}/.MainActivity t3}\r\n`;
		return '';
	}
	async push(remote: string) {
		this.pushed.push(remote);
	}
	async pull(remote: string) {
		this.calls.push(`pull ${remote}`);
		return new Uint8Array([1, 2, 3]);
	}
	async reboot() {
		this.reboots++;
	}
}

function hooks(frame: FakeFrame, over: Partial<RunHooks> = {}) {
	const statuses: Partial<Record<StepId, StepStatus>> = {};
	const files: string[] = [];
	const lines: string[] = [];
	const h: RunHooks = {
		dryRun: false,
		log: (l) => lines.push(l),
		step: (id, s) => (statuses[id] = s),
		file: (name) => files.push(name),
		fetchRelease: async () => new Uint8Array([0x50, 0x4b]),
		reconnect: async () => frame,
		...over
	};
	return { h, statuses, files, lines };
}

const stock = () => new FakeFrame(() => undefined, { [FRAMEO_PACKAGE]: true, 'com.adups.fota': true, 'com.adups.fota.sysoper': true });

describe('identifyFrame', () => {
	it('identifies with read-only commands only', async () => {
		const frame = stock();
		const id = await identifyFrame(frame, { log: () => {} });
		expect(id.verdict).toBe('supported');
		expect(id.facts.packages[FRAMEO_PACKAGE]).toMatchObject({ installed: true, enabled: 'enabled' });
		expect(id.facts.packages[FRAME_PACKAGE]).toMatchObject({ installed: false });
		expect(frame.calls.every(isReadOnly)).toBe(true);
	});
});

describe('runPlan', () => {
	it('converts a stock frame in order, with backup files and a restart', async () => {
		const frame = stock();
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		const { h, statuses, files } = hooks(frame);
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: true, language: 'nl' }), h, { continueOnError: false });
		expect(r).toEqual({ ok: true, failed: [] });
		expect(files).toEqual([`${FRAMEO_PACKAGE}-base.apk`, `${FRAMEO_PACKAGE}-data.tgz`]);
		expect(frame.pushed).toEqual([`${WORK_DIR}/kinframe.apk`]);
		expect(frame.reboots).toBe(1);
		expect(Object.values(statuses).every((s) => s === 'done')).toBe(true);
		const install = frame.calls.findIndex((c) => c.startsWith('pm install'));
		expect(install).toBeGreaterThan(-1);
		expect(frame.calls.findIndex((c) => c.includes('disable-user'))).toBeGreaterThan(install);
		expect(frame.calls.at(-2)).toContain('--es language nl');
	});

	it('a dry run only reads: no pushes, pulls, restarts or modifying commands', async () => {
		const frame = stock();
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		let fetched = false;
		const { h, statuses, lines } = hooks(frame, {
			dryRun: true,
			fetchRelease: async () => {
				fetched = true;
				return new Uint8Array([1]);
			}
		});
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: true }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(frame.calls).toEqual([]);
		expect(frame.pushed).toEqual([]);
		expect(frame.reboots).toBe(0);
		expect(fetched).toBe(true); // the download and its checks still run
		expect(statuses.download).toBe('done');
		expect(statuses.disable).toBe('dry');
		expect(lines).toContain('would run: su 0 pm disable-user net.frameo.frame');
	});

	it('stops before disabling Frameo when the install fails', async () => {
		const frame = new FakeFrame((c) => (c.startsWith('pm install') ? 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\r\n' : undefined), {
			[FRAMEO_PACKAGE]: true
		});
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame);
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r).toEqual({ ok: false, failed: ['install'] });
		expect(statuses.disable).toBe('skipped');
		expect(statuses.reboot).toBe('skipped');
		expect(frame.calls.some((c) => c.includes('disable-user'))).toBe(false);
		expect(frame.reboots).toBe(0);
	});

	it('stops before touching the frame when the download fails verification', async () => {
		const frame = stock();
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		const { h, statuses } = hooks(frame, {
			fetchRelease: async () => {
				throw new Error('certificate mismatch');
			}
		});
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r.failed).toEqual(['download']);
		expect(statuses.install).toBe('skipped');
		expect(frame.calls).toEqual([]);
		expect(frame.pushed).toEqual([]);
	});

	it('a newer kinframe already installed is fine', async () => {
		const frame = new FakeFrame((c) => (c.startsWith('pm install') ? 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\r\n' : undefined), { [FRAMEO_PACKAGE]: true });
		const id = await identifyFrame(frame, { log: () => {} });
		const { h } = hooks(frame);
		expect((await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false })).ok).toBe(true);
	});

	it('revert goes on after a failed step and falls back to a root uninstall', async () => {
		const frame = new FakeFrame(
			(c) => {
				if (c === `su 0 pm enable ${FRAMEO_PACKAGE}`) return 'Error: java.lang.SecurityException\r\n';
				if (c === `pm uninstall ${FRAME_PACKAGE}`) return 'Failure [DELETE_FAILED_INTERNAL_ERROR]\r\n';
				if (c === 'dumpsys activity activities') return `  mFocusedActivity: ActivityRecord{1 u0 ${FRAMEO_PACKAGE}/.Main t3}\r\n`;
				return undefined;
			},
			{ [FRAME_PACKAGE]: true, [FRAMEO_PACKAGE]: false }
		);
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame);
		const r = await runPlan(frame, planRevert(id, { keepData: false, restoreFrameo: false }), h, { continueOnError: true });
		expect(r.failed).toEqual(['enable']);
		expect(statuses.uninstall).toBe('done');
		expect(frame.calls).toContain(`su 0 pm uninstall ${FRAME_PACKAGE}`);
		expect(statuses.reboot).toBe('done');
		expect(frame.reboots).toBe(1);
	});

	it('warns when kinframe is not in front after converting', async () => {
		const frame = new FakeFrame((c) => (c === 'dumpsys activity activities' ? '  mFocusedActivity: ActivityRecord{1 u0 com.android.settings/.Settings t3}' : undefined), {
			[FRAMEO_PACKAGE]: true
		});
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame);
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(statuses.provision).toBe('warn');
	});
});
