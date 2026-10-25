import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
	BOOT_ANIMATION_BACKUP,
	COMMANDS,
	DEFAULT_SERVER,
	FRAME_PACKAGE,
	FRAMEO_PACKAGE,
	RESOURCE_BACKUP,
	WORK_DIR,
	planConvert,
	planRevert,
	type StepId
} from './plan';
import { bootAssetsProblem, identifyFrame, isReadOnly, runPlan, type FrameShell, type RunHooks, type StepStatus } from './runner';
import { brandResource, buildResource } from './rsce';

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

// ---------------------------------------------------------------- start-up branding

const RESOURCE = '/dev/block/platform/1021c000.rksdmmc/by-name/resource';
const SYSTEM = '/dev/block/platform/1021c000.rksdmmc/by-name/system';

function bmp(extra: number): Uint8Array {
	const b = new Uint8Array(54 + extra).fill(0x33);
	const v = new DataView(b.buffer);
	b.set([0x42, 0x4d]);
	v.setUint32(14, 40, true);
	v.setInt32(18, 800, true);
	v.setInt32(22, 1280, true);
	v.setUint16(28, 8, true);
	v.setUint32(30, 1, true);
	return b;
}
const DTB = Uint8Array.from({ length: 5000 }, (_, i) => (i * 7) & 0xff);
const STOCK = buildResource(
	[
		{ name: 'rk-kernel.dtb', data: DTB },
		{ name: 'logo.bmp', data: bmp(3000) },
		{ name: 'logo_kernel.bmp', data: bmp(3000) }
	],
	256 * 1024
);
const LOGO = bmp(1200);
const ANIM = new Uint8Array([0x50, 0x4b, 3, 4, 9, 9, 9]);
const STOCK_ANIM = new Uint8Array([0x50, 0x4b, 3, 4, 1, 1, 1]);
const sha1 = (b: Uint8Array) => createHash('sha1').update(b).digest('hex');

/** A frame with a resource partition, /system and a work directory, as files. */
class BootFrame extends FakeFrame {
	partition: Uint8Array = STOCK.slice();
	files = new Map<string, Uint8Array>([['/system/media/bootanimation.zip', STOCK_ANIM]]);
	systemRw = false;
	writes = 0;
	/** Corrupts the n-th partition write (1-based), 0 for never; or every write. */
	corrupt = 0;
	corruptAll = false;
	constructor() {
		super(() => undefined, { [FRAMEO_PACKAGE]: true });
	}
	override async shell(cmd: string) {
		this.calls.push(cmd);
		if (cmd === COMMANDS.findResource) return `${RESOURCE}\r\n`;
		if (cmd === 'mount') return `${SYSTEM} /system ext4 ro,seclabel 0 0\r\n`;
		let m = /^su 0 dd if=(\S+) of=(\S+) bs=1048576$/.exec(cmd);
		if (m) {
			if (m[1] === RESOURCE) this.files.set(m[2]!, this.partition.slice());
			else {
				const data = this.files.get(m[1]!)!.slice();
				if (++this.writes === this.corrupt || this.corruptAll) data[1000] = data[1000]! ^ 0xff;
				this.partition = data;
			}
			return '256+0 records in\r\n256+0 records out\r\n';
		}
		if (cmd.startsWith('su 0 mount -o remount,')) {
			this.systemRw = cmd.includes('remount,rw');
			return '';
		}
		m = /^su 0 cp (\S+) (\S+)$/.exec(cmd);
		if (m) {
			if (!this.systemRw) return 'cp: /system/media/bootanimation.zip: Read-only file system\r\n';
			this.files.set(m[2]!, this.files.get(m[1]!)!);
			return '';
		}
		if (cmd === COMMANDS.sha1BootAnimation) return `${sha1(this.files.get('/system/media/bootanimation.zip')!)}  /system/media/bootanimation.zip\r\n`;
		return super.shell(cmd).then((out) => (this.calls.pop(), out));
	}
	override async push(remote: string, data?: Uint8Array) {
		this.pushed.push(remote);
		if (data) this.files.set(remote, data.slice());
	}
	override async pull(remote: string) {
		this.calls.push(`pull ${remote}`);
		const f = this.files.get(remote);
		if (!f) throw new Error(`no ${remote}`);
		return f.slice();
	}
}

describe('start-up branding', () => {
	const fetchBoot = async () => ({ logo: LOGO, bootanimation: ANIM });

	it('backs up, writes and verifies the boot logo and animation', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		expect(id.facts).toMatchObject({ resource: RESOURCE, system: SYSTEM });
		const { h, statuses, files } = hooks(frame, { fetchBoot });
		const saved: Record<string, Uint8Array> = {};
		h.file = (name, data) => ((saved[name] = data), files.push(name));
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r).toEqual({ ok: true, failed: [] });
		expect(files).toEqual([BOOT_ANIMATION_BACKUP, RESOURCE_BACKUP]);
		expect(saved[RESOURCE_BACKUP]).toEqual(STOCK);
		expect(saved[BOOT_ANIMATION_BACKUP]).toEqual(STOCK_ANIM);
		expect(frame.partition).toEqual(brandResource(STOCK, LOGO));
		expect(frame.files.get('/system/media/bootanimation.zip')).toEqual(ANIM);
		expect(frame.systemRw).toBe(false);
		expect(statuses.bootLogo).toBe('done');
		expect(statuses.bootAnimation).toBe('done');
		// The partition is written once, after the backup was pulled.
		const write = frame.calls.findIndex((c) => c.includes(`of=${RESOURCE}`));
		expect(frame.calls.findIndex((c) => c === `pull ${WORK_DIR}/${RESOURCE_BACKUP}`)).toBeLessThan(write);
		expect(frame.calls.filter((c) => c.includes(`of=${RESOURCE}`))).toHaveLength(1);
		expect(frame.calls).toContain(`su 0 blockdev --flushbufs ${RESOURCE}`);
	});

	it('writes the original back when the read-back does not match, and stops before the app changes', async () => {
		const frame = new BootFrame();
		frame.corrupt = 1;
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame, { fetchBoot });
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r.failed).toEqual(['bootLogo']);
		expect(frame.partition).toEqual(STOCK);
		expect(frame.calls.filter((c) => c.includes(`of=${RESOURCE}`))).toHaveLength(2);
		expect(statuses.install).toBe('skipped');
		expect(frame.calls.some((c) => c.startsWith('pm install'))).toBe(false);
	});

	it('reports when even the original could not be written back', async () => {
		const frame = new BootFrame();
		frame.corruptAll = true; // the way back fails too
		const id = await identifyFrame(frame, { log: () => {} });
		const details: string[] = [];
		const { h } = hooks(frame, { fetchBoot, step: (_id, _s, d) => d && details.push(d) });
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(details.join(' ')).toMatch(/Do not restart the frame/);
	});

	it('refuses a logo the frame cannot show, without writing anything', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		const wrong = bmp(100);
		new DataView(wrong.buffer).setInt32(18, 1280, true);
		const { h, statuses } = hooks(frame, { fetchBoot: async () => ({ logo: wrong, bootanimation: ANIM }) });
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r.failed).toEqual(['bootLogo']);
		expect(statuses.bootAnimation).toBe('skipped');
		expect(frame.calls.some((c) => c.includes(`of=${RESOURCE}`))).toBe(false);
		expect(frame.partition).toEqual(STOCK);
	});

	it('fails the animation step when the copy did not land, and leaves /system read-only', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		frame.shell = ((orig) => async (cmd: string) => (cmd.startsWith('su 0 cp') ? (frame.calls.push(cmd), 'cp: write error: No space left on device\r\n') : orig(cmd)))(frame.shell.bind(frame));
		const { h, statuses } = hooks(frame, { fetchBoot });
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r.failed).toEqual(['bootAnimation']);
		expect(statuses.bootLogo).toBe('done');
		expect(frame.systemRw).toBe(false);
	});

	it('revert writes back a valid backup and refuses anything else', async () => {
		const frame = new BootFrame();
		frame.partition = brandResource(STOCK, LOGO);
		frame.files.set('/system/media/bootanimation.zip', ANIM);
		const id = await identifyFrame(frame, { log: () => {} });
		const opts = { keepData: false, restoreFrameo: false, restoreBootLogo: true, restoreBootAnimation: true };
		const ok = hooks(frame, { restoreBoot: () => ({ resource: STOCK, bootanimation: STOCK_ANIM }) });
		await runPlan(frame, planRevert(id, opts), ok.h, { continueOnError: true });
		expect(ok.statuses.bootLogo).toBe('done');
		expect(ok.statuses.bootAnimation).toBe('done');
		expect(frame.partition).toEqual(STOCK);
		expect(frame.files.get('/system/media/bootanimation.zip')).toEqual(STOCK_ANIM);

		const bad = new BootFrame();
		const before = bad.partition.slice();
		const noDtb = buildResource([{ name: 'logo.bmp', data: bmp(10) }], STOCK.length);
		const b = hooks(bad, { restoreBoot: () => ({ resource: noDtb }) });
		await runPlan(bad, planRevert(await identifyFrame(bad, { log: () => {} }), { ...opts, restoreBootAnimation: false }), b.h, { continueOnError: true });
		expect(b.statuses.bootLogo).toBe('failed');
		expect(bad.calls.some((c) => c.includes(`of=${RESOURCE}`))).toBe(false);
		expect(bad.partition).toEqual(before);
	});

	it('a dry run neither reads the partition nor writes anything', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		const { h, statuses } = hooks(frame, { dryRun: true, fetchBoot });
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(frame.calls).toEqual([]);
		expect(statuses.bootLogo).toBe('dry');
	});

	it('only accepts a BMP logo and a zip animation', () => {
		expect(bootAssetsProblem({ logo: LOGO, bootanimation: ANIM })).toBeNull();
		expect(bootAssetsProblem({ logo: ANIM, bootanimation: ANIM })).toMatch(/BMP/);
		expect(bootAssetsProblem({ logo: LOGO, bootanimation: LOGO })).toMatch(/zip/);
	});
});
