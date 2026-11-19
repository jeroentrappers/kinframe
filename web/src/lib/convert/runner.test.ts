import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
	BOOT_ANIMATION_BACKUP,
	COMMANDS,
	DEFAULT_SERVER,
	FRAME_PACKAGE,
	FRAMEO_PACKAGE,
	RESOURCE_BACKUP,
	ROOT_CERT,
	ROOT_CERT_FILE,
	HOSTS,
	STATE_FILE,
	addHostsBlock,
	WORK_DIR,
	planConvert,
	planRevert,
	type BootManifest,
	type StepId
} from './plan';
import { bootAssetsProblem, identifyFrame, isReadOnly, runPlan, type BootSource, type FrameShell, type RemoteEntry, type RunHooks, type StepStatus } from './runner';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { brandResource, buildResource } from './rsce';

/** Frameo data as copied to the work directory: a Firebase file whose name alone is ~90 bytes, and the lib symlink. */
const LONG_NAME = 'PersistedInstallation.W0RFRkFVTFRd+MTo2NDA1NzkyMzE0Nzk6YW5kcm9pZDo4MjcxNWY0NjY3M2U0M2Nj.json';
const DATA_TREE: Record<string, Uint8Array | 'link'> = {
	'databases/frameo.db': new TextEncoder().encode('SQLite format 3'),
	'shared_prefs/prefs.xml': new TextEncoder().encode('<map/>'),
	[`files/${LONG_NAME}`]: new TextEncoder().encode('{"fid":"x"}'),
	'files/media/photo-1.jpg': Uint8Array.from({ length: 3000 }, (_, i) => i & 0xff),
	lib: 'link'
};

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
		if (cmd.startsWith('pm clear ')) return 'Success\r\n';
		if (cmd.includes('disable-user')) {
			const p = cmd.split(' ').pop()!;
			this.enabled[p] = false;
			return `Package ${p} new state: disabled-user\r\n`;
		}
		if (cmd.includes('pm enable')) {
			const p = cmd.split(' ').pop()!;
			this.enabled[p] = true;
			return `Package ${p} new state: enabled\r\n`;
		}
		if (cmd === 'pm list packages') return Object.keys(this.enabled).map((p) => `package:${p}\r\n`).join('');
		if (cmd === 'pm list packages -d') return Object.entries(this.enabled).filter(([, on]) => !on).map(([p]) => `package:${p}\r\n`).join('');
		if (cmd.includes('pm uninstall')) return 'Success\r\n';
		if (cmd === 'dumpsys activity activities') return `  mFocusedActivity: ActivityRecord{1 u0 ${FRAME_PACKAGE}/.MainActivity t3}\r\n`;
		return '';
	}
	async push(remote: string) {
		this.pushed.push(remote);
	}
	/** The copy of the app data in the work directory, as the frame would list it: files, and symlinks as 'link'. */
	tree = new Map<string, Uint8Array | 'link'>(Object.entries(DATA_TREE).map(([k, v]) => [`${WORK_DIR}/${FRAMEO_PACKAGE}/${k}`, v]));
	async pull(remote: string) {
		this.calls.push(`pull ${remote}`);
		const f = this.tree.get(remote);
		return f instanceof Uint8Array ? f : new Uint8Array([1, 2, 3]);
	}
	async list(remote: string): Promise<RemoteEntry[]> {
		this.calls.push(`list ${remote}`);
		const out = new Map<string, RemoteEntry>([['.', { name: '.', mode: 0o40771, size: 0, mtime: 1 }]]);
		for (const [path, v] of this.tree) {
			if (!path.startsWith(`${remote}/`)) continue;
			const [name, ...rest] = path.slice(remote.length + 1).split('/');
			if (rest.length) out.set(name!, { name: name!, mode: 0o40771, size: 4096, mtime: 1700000000 });
			else out.set(name!, v === 'link' ? { name: name!, mode: 0o120777, size: 20, mtime: 1 } : { name: name!, mode: 0o100660, size: v.length, mtime: 1700000000 });
		}
		if (out.size === 1) throw new Error(`no such directory ${remote}`);
		return [...out.values()];
	}
	/** The clean restart command of each restart (undefined: adb's reboot). */
	rebootCommands: (string | undefined)[] = [];
	async reboot(cleanCommand?: string): Promise<'clean' | 'adb'> {
		this.reboots++;
		this.rebootCommands.push(cleanCommand);
		this.onReboot?.(this.enabled);
		return cleanCommand ? 'clean' : 'adb';
	}
	/** What a restart does to the package states (a lost write: a package enabled again). */
	onReboot?: (enabled: Record<string, boolean>) => void;
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
		// The fake frame has no /system device, so the hosts file is left alone with a warning.
		expect(statuses.hosts).toBe('warn');
		expect(Object.entries(statuses).every(([id, s]) => id === 'hosts' || s === 'done')).toBe(true);
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
		// Rooted: Android's own restart, so the apps hear about the shutdown.
		expect(frame.rebootCommands).toEqual(['su 0 svc power reboot']);
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

describe('backing up the app data', () => {
	const pkg = FRAMEO_PACKAGE;
	const d = `${WORK_DIR}/${pkg}`;

	it('copies the data on the frame, pulls it as a tree and archives it here, with long names', async () => {
		const frame = new FakeFrame((c) => (c === `su 0 ls -lnRa /data/data/${pkg}` ? `/data/data/${pkg}:\r\ndrwxr-x--x 6 10052 10052 4096 databases\r\n` : undefined), { [pkg]: true });
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		const saved: Record<string, Uint8Array> = {};
		const { h, statuses, files } = hooks(frame);
		h.file = (name, data) => ((saved[name] = data), files.push(name));
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: true }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(statuses.backup).toBe('done');
		expect(files).toEqual([`${pkg}-base.apk`, `${pkg}-data-files.txt`, `${pkg}-data.tgz`]);
		expect(new TextDecoder().decode(saved[`${pkg}-data-files.txt`])).toContain('10052 10052');
		// No tar on the frame; the copy is removed after the pull.
		expect(frame.calls.filter((c) => c.startsWith('su 0'))).toEqual([
			`su 0 ls -lnRa /data/data/${pkg}`,
			`su 0 cp -a /data/data/${pkg} ${d}`,
			`su 0 rm -rf ${d}/cache ${d}/code_cache ${d}/app_webview ${d}/lib ${d}/files/.crashlytics.v3`,
			`su 0 chmod -R a+rX ${d}`,
			`su 0 rm -rf ${d}`,
			...frame.calls.filter((c) => c.startsWith('su 0') && frame.calls.indexOf(c) > frame.calls.indexOf(`su 0 rm -rf ${d}`))
		]);
		expect(frame.calls.indexOf(`su 0 rm -rf ${d}`)).toBeGreaterThan(frame.calls.lastIndexOf(`pull ${d}/files/media/photo-1.jpg`));
		// The archive extracts with the system tar, long name and all; the symlink is left out.
		const dir = mkdtempSync(join(tmpdir(), 'kinframe-data-'));
		try {
			writeFileSync(join(dir, 'data.tgz'), saved[`${pkg}-data.tgz`]!);
			execFileSync('tar', ['-xzf', 'data.tgz'], { cwd: dir });
			for (const [path, v] of Object.entries(DATA_TREE)) {
				if (v === 'link') continue;
				expect(new Uint8Array(readFileSync(join(dir, pkg, path))), path).toEqual(v);
			}
			const names = execFileSync('tar', ['-tzf', 'data.tgz'], { cwd: dir, encoding: 'utf8' }).split('\n');
			expect(names).toContain(`${pkg}/files/${LONG_NAME}`);
			expect(`${pkg}/files/${LONG_NAME}`.length).toBeGreaterThan(100);
			expect(names.some((n) => n.endsWith('/lib'))).toBe(false);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	it('a data backup that fails is a warning, the copy is removed and the convert goes on', async () => {
		const frame = new FakeFrame(() => undefined, { [pkg]: true });
		frame.tree.clear(); // the copy failed: nothing to pull
		const id = await identifyFrame(frame, { log: () => {} });
		const details: Partial<Record<StepId, string>> = {};
		const { h, statuses, files } = hooks(frame);
		h.step = (sid, st, detail) => ((statuses[sid] = st), detail && (details[sid] = detail));
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: true }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(statuses.backup).toBe('warn');
		expect(details.backup).toMatch(/could not back up the data of net.frameo.frame .*stays on the frame/);
		expect(files).toEqual([`${pkg}-base.apk`]);
		expect(statuses.disable).toBe('done');
		expect(frame.calls).toContain(`su 0 rm -rf ${d}`);
	});

	it('files cp could not copy are a warning, the rest is kept', async () => {
		const frame = new FakeFrame((c) => (c.startsWith('su 0 cp -a') ? "cp: /data/data/net.frameo.frame/x: Permission denied\r\n" : undefined), { [pkg]: true });
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses, files } = hooks(frame);
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: true }), h, { continueOnError: false });
		expect(statuses.backup).toBe('warn');
		expect(files).toContain(`${pkg}-data.tgz`);
	});
});

describe('firmware updater leftovers', () => {
	const EXT = '/storage/1234-ABCD/Android/data/com.adups.fota/files/adupsfota';

	it('identifies a pending update, and clears the download folder (as root when needed) and the recovery command', async () => {
		const present = new Set([EXT, 'command']);
		const frame = new FakeFrame(
			(c) => {
				if (c === COMMANDS.listAdupsExternal) return `${EXT}\r\nls: /storage/emulated/0/adupsfota: No such file or directory\r\n`;
				if (c === COMMANDS.readAdupsPrefs) return '<map><int name="ota_update_status" value="4" /></map>';
				if (c === COMMANDS.listRecovery) return present.has('command') ? 'command\r\nlast_log\r\n' : 'last_log\r\n';
				if (c === COMMANDS.readRecoveryCommand) return '--update_package=/cache/update.zip\r\n';
				if (c === `ls -d ${EXT}`) return present.has(EXT) ? `${EXT}\r\n` : `ls: ${EXT}: No such file or directory\r\n`;
				if (c === `rm -rf ${EXT}`) return `rm: ${EXT}: Permission denied\r\n`; // the shell user may not
				if (c === `su 0 rm -rf ${EXT}`) return (present.delete(EXT), '');
				if (c === COMMANDS.removeRecoveryCommand) return (present.delete('command'), '');
				return undefined;
			},
			{ [FRAMEO_PACKAGE]: true, 'com.adups.fota': true, 'com.adups.fota.sysoper': true, 'android.rockchip.update.service': true }
		);
		const lines: string[] = [];
		const id = await identifyFrame(frame, { log: (l) => lines.push(l) });
		expect(id.facts.updater).toMatchObject({ status: 4, downloads: [EXT], recovery: ['command', 'last_log'] });
		expect(lines.join('\n')).toMatch(/WARNING: the firmware updater has a pending or downloaded update/);
		expect(frame.calls.every(isReadOnly)).toBe(true);
		frame.calls = [];
		const { h, statuses } = hooks(frame);
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(statuses.disable).toBe('done');
		expect(present.size).toBe(0);
		const order = ['am force-stop com.adups.fota', 'pm clear com.adups.fota', `su 0 rm -rf ${EXT}`, COMMANDS.removeRecoveryCommand, 'su 0 pm disable-user com.adups.fota'];
		const at = order.map((c) => frame.calls.indexOf(c));
		expect(at.every((i, k) => i > -1 && (k === 0 || i > at[k - 1]!))).toBe(true);
		expect(frame.calls).toContain('su 0 pm disable-user android.rockchip.update.service');
	});
});

describe('extra packages', () => {
	const pkgs = { [FRAMEO_PACKAGE]: true, 'com.adups.fota': true, 'com.adups.fota.sysoper': true, 'com.android.music': true, 'com.android.browser': true, 'com.android.systemui': false };

	it('converting disables and records them; reverting enables exactly those', async () => {
		const frame = new FakeFrame(() => undefined, { ...pkgs });
		const id = await identifyFrame(frame, { log: () => {} });
		expect(id.facts.extrasState).toBeNull();
		const saved: Record<string, Uint8Array> = {};
		const { h, statuses } = hooks(frame);
		h.file = (name, data) => (saved[name] = data);
		const pushed: Record<string, Uint8Array> = {};
		frame.push = async (remote: string, data?: Uint8Array) => {
			frame.pushed.push(remote);
			if (data) pushed[remote] = data;
		};
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(statuses.extras).toBe('done');
		const record = new TextDecoder().decode(pushed[STATE_FILE]);
		expect(record.split('\n').filter((l) => l && !l.startsWith('#'))).toEqual(['com.android.browser', 'com.android.music']);
		expect(new TextDecoder().decode(saved['kinframe-state.txt'])).toBe(record);
		expect(frame.calls).not.toContain('su 0 pm disable-user com.android.systemui');

		// Revert with that record: only those two come back, System UI stays as the vendor left it.
		const after = new FakeFrame((c) => (c === `cat ${STATE_FILE}` ? record.replace(/\n/g, '\r\n') : undefined), {
			...pkgs,
			'com.android.music': false,
			'com.android.browser': false,
			'com.android.calculator2': false
		});
		const rid = await identifyFrame(after, { log: () => {} });
		await runPlan(after, planRevert(rid, { keepData: false, restoreFrameo: false }), hooks(after).h, { continueOnError: true });
		expect(after.calls).toContain('su 0 pm enable com.android.music');
		expect(after.calls).toContain('su 0 pm enable com.android.browser');
		expect(after.calls).not.toContain('su 0 pm enable com.android.calculator2'); // disabled, but not by us
		expect(after.calls.some((c) => c.includes('enable com.android.systemui'))).toBe(false);
		expect(after.calls).toContain(`rm -f ${STATE_FILE}`);
	});

	it('a package that does not stay off is a warning, not a failure', async () => {
		const frame = new FakeFrame((c) => (c === 'su 0 pm disable-user com.android.music' ? 'Package com.android.music new state: disabled-user\r\n' : undefined), { ...pkgs });
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame);
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(statuses.extras).toBe('warn');
		expect(statuses.reboot).toBe('warn');
	});
});

describe('keeping the packages disabled', () => {
	const all = { [FRAMEO_PACKAGE]: true, 'com.adups.fota': true, 'com.adups.fota.sysoper': true };

	it('waits before the restart, and turns a package that came back off again (warning)', async () => {
		const frame = new FakeFrame(() => undefined, { ...all });
		frame.onReboot = (en) => (en['com.adups.fota.sysoper'] = true); // the last write was lost
		const id = await identifyFrame(frame, { log: () => {} });
		frame.calls = [];
		const details: Partial<Record<StepId, string>> = {};
		const { h, statuses } = hooks(frame);
		h.step = (sid, st, detail) => ((statuses[sid] = st), detail && (details[sid] = detail));
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(statuses.reboot).toBe('warn');
		expect(details.reboot).toMatch(/com.adups.fota.sysoper came back after the restart; turned off again/);
		// Disabled, checked, waited for before the restart; after it, disabled again and waited for again.
		const disables = frame.calls.flatMap((c, i) => (c === 'su 0 pm disable-user com.adups.fota.sysoper' ? [i] : []));
		const sleeps = frame.calls.flatMap((c, i) => (c === 'sleep 15' ? [i] : []));
		expect(disables).toHaveLength(2);
		expect(disables[0]! < sleeps[0]! && sleeps[0]! < disables[1]! && disables[1]! < sleeps[1]!).toBe(true);
		expect(frame.calls.filter((c) => c === 'sleep 15')).toHaveLength(2);
		expect(statuses.provision).toBe('done');
	});

	it('fails when a package does not stay disabled at all', async () => {
		const frame = new FakeFrame((c) => (c === 'su 0 pm disable-user com.adups.fota.sysoper' ? 'Package com.adups.fota.sysoper new state: disabled-user\r\n' : undefined), { ...all });
		const id = await identifyFrame(frame, { log: () => {} });
		const { h, statuses } = hooks(frame);
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(r.failed).toEqual(['disable']);
		expect(statuses.reboot).toBe('skipped');
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
const HOSTS_STOCK = '127.0.0.1       localhost\n::1             ip6-localhost\n';

class BootFrame extends FakeFrame {
	partition: Uint8Array = STOCK.slice();
	files = new Map<string, Uint8Array>([
		['/system/media/bootanimation.zip', STOCK_ANIM],
		[HOSTS, new TextEncoder().encode(HOSTS_STOCK)]
	]);
	systemRw = false;
	writes = 0;
	/** Corrupts the n-th partition write (1-based), 0 for never; or every write. */
	corrupt = 0;
	corruptAll = false;
	constructor(certPresent = true) {
		super(() => undefined, { [FRAMEO_PACKAGE]: true });
		if (certPresent) this.files.set(ROOT_CERT, new Uint8Array([1]));
	}
	override async shell(cmd: string) {
		this.calls.push(cmd);
		if (cmd === COMMANDS.findResource) return `${RESOURCE}\r\n`;
		if (cmd === 'mount') return `${SYSTEM} /system ext4 ro,seclabel 0 0\r\n`;
		if (cmd === COMMANDS.screenSize) return 'Physical size: 1280x800\r\n';
		if (cmd === COMMANDS.checkRootCert) return this.files.has(ROOT_CERT) ? `${ROOT_CERT}\r\n` : `${ROOT_CERT}: No such file or directory\r\n`;
		if (cmd === COMMANDS.sha1Hosts) return `${sha1(this.files.get(HOSTS)!)}  ${HOSTS}\r\n`;
		if (cmd === COMMANDS.sha1RootCert) return this.files.has(ROOT_CERT) ? `${sha1(this.files.get(ROOT_CERT)!)}  ${ROOT_CERT}\r\n` : 'No such file\r\n';
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

/** A brand offering one logo (800×1280, rotation 0 like the fake frame) and one 1280×800 animation, plus others. */
function source(logo = LOGO, anim = ANIM, over: Partial<BootManifest> = {}): BootSource {
	const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');
	const files: Record<string, Uint8Array> = { 'logo-a.bmp': logo, 'anim-a.zip': anim, 'logo-b.bmp': bmp(10), 'anim-b.zip': ANIM };
	return {
		manifest: {
			logos: [
				{ file: 'logo-b.bmp', width: 800, height: 1280, bpp: 8, compression: 1, hwrotation: 270, sha256: sha(files['logo-b.bmp']!) },
				{ file: 'logo-a.bmp', width: 800, height: 1280, bpp: 8, compression: 1, hwrotation: 0, sha256: sha(logo) }
			],
			bootanimations: [
				{ file: 'anim-b.zip', screen: '1920x1080', sha256: sha(ANIM) },
				{ file: 'anim-a.zip', screen: '1280x800', sha256: sha(anim) }
			],
			...over
		},
		get: async (f) => files[f]!
	};
}

describe('start-up branding', () => {
	const fetchBoot = async () => source();

	it('backs up, writes and verifies the boot logo and animation', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		expect(id.facts).toMatchObject({ resource: RESOURCE, system: SYSTEM });
		const { h, statuses, files } = hooks(frame, { fetchBoot });
		const saved: Record<string, Uint8Array> = {};
		h.file = (name, data) => ((saved[name] = data), files.push(name));
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r).toEqual({ ok: true, failed: [] });
		expect(files).toEqual([BOOT_ANIMATION_BACKUP, RESOURCE_BACKUP, 'hosts']);
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

	it('picks the logo for the frame logo format and the animation for its screen', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		const lines: string[] = [];
		const { h } = hooks(frame, { fetchBoot, log: (l) => lines.push(l) });
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(lines.some((l) => /logo is 800×1280, 8 bits, compression 1, panel rotation 0: using logo-a.bmp/.test(l))).toBe(true);
		expect(lines).toContain('screen 1280x800: using anim-a.zip');
	});

	it('refuses when the brand has no logo for the frame, without writing anything', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		const details: string[] = [];
		const { h, statuses } = hooks(frame, { fetchBoot: async () => source(LOGO, ANIM, { logos: [] }), step: (s, st, d) => ((statuses[s] = st), d && details.push(d)) });
		const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
		expect(r.failed).toEqual(['download']);
		expect(details.join(' ')).toMatch(/no kinframe boot logo for this frame \(its logo is 800×1280, 8 bits, compression 1, panel rotation 0\)/);
		expect(statuses.bootLogo).toBe('skipped');
		expect(frame.calls.some((c) => c.includes(`of=${RESOURCE}`))).toBe(false);
		expect(frame.partition).toEqual(STOCK);
	});

	it('refuses when the brand has no animation for the screen, or a file does not match its sha256', async () => {
		for (const src of [source(LOGO, ANIM, { bootanimations: [] }), { ...source(), get: async () => bmp(5) }]) {
			const frame = new BootFrame();
			const id = await identifyFrame(frame, { log: () => {} });
			const { h } = hooks(frame, { fetchBoot: async () => src });
			const r = await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false, bootBranding: true }), h, { continueOnError: false });
			expect(r.failed).toEqual(['download']);
			expect(frame.partition).toEqual(STOCK);
		}
	});

	it('refuses a logo the frame cannot show, without writing anything', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		const wrong = bmp(100);
		new DataView(wrong.buffer).setInt32(18, 1280, true);
		// A manifest that claims the wrong file fits: brandResource() still compares the real formats.
		const { h, statuses } = hooks(frame, { fetchBoot: async () => source(wrong) });
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

	it("adds the Let's Encrypt root where it is missing, and a failure there is only a warning", async () => {
		const CERT = new TextEncoder().encode('-----BEGIN CERTIFICATE-----\n');
		const { ROOT_CERT_SHA256 } = await import('./plan');
		// The pinned sha256 is the real file's: a fake certificate is refused, the step warns and the convert goes on.
		const frame = new BootFrame(false);
		const id = await identifyFrame(frame, { log: () => {} });
		expect(id.facts.rootCert).toBe('missing');
		const plan = planConvert(id, { server: DEFAULT_SERVER, backup: false });
		expect(plan.map((s) => s.id)).toContain('rootCert');
		const bad = hooks(frame, { fetchRootCert: async () => CERT });
		const r = await runPlan(frame, plan, bad.h, { continueOnError: false });
		expect(r.ok).toBe(true);
		expect(bad.statuses.download).toBe('warn');
		expect(bad.statuses.rootCert).toBe('warn');
		expect(bad.statuses.install).toBe('done');
		expect(frame.files.has(ROOT_CERT)).toBe(false);
		expect(frame.systemRw).toBe(false);

		// The real file: copied, checked, /system read-only again.
		const { readFileSync } = await import('node:fs');
		const real = new Uint8Array(readFileSync(new URL(`../../../static/frame-certs/${ROOT_CERT_FILE}`, import.meta.url)));
		expect(createHash('sha256').update(real).digest('hex')).toBe(ROOT_CERT_SHA256);
		const good = new BootFrame(false);
		const gid = await identifyFrame(good, { log: () => {} });
		const ok = hooks(good, { fetchRootCert: async () => real });
		expect((await runPlan(good, planConvert(gid, { server: DEFAULT_SERVER, backup: false }), ok.h, { continueOnError: false })).ok).toBe(true);
		expect(ok.statuses.rootCert).toBe('done');
		expect(good.files.get(ROOT_CERT)).toEqual(real);
		expect(good.systemRw).toBe(false);
		expect(good.calls).toContain(`su 0 chown 0:0 ${ROOT_CERT}`);
	});

	it('blocks the update servers in the hosts file once, and the revert removes only that block', async () => {
		const frame = new BootFrame();
		const id = await identifyFrame(frame, { log: () => {} });
		const saved: Record<string, Uint8Array> = {};
		const { h, statuses } = hooks(frame);
		h.file = (name, data) => (saved[name] = data);
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), h, { continueOnError: false });
		expect(statuses.hosts).toBe('done');
		const text = new TextDecoder().decode(frame.files.get(HOSTS));
		expect(text).toBe(addHostsBlock(HOSTS_STOCK));
		expect(new TextDecoder().decode(saved.hosts)).toBe(HOSTS_STOCK);
		expect(frame.systemRw).toBe(false);
		expect(frame.calls).toContain('su 0 chown 0:0 /system/etc/hosts');
		// A second convert finds the block and writes nothing.
		frame.calls = [];
		await runPlan(frame, planConvert(id, { server: DEFAULT_SERVER, backup: false }), hooks(frame).h, { continueOnError: false });
		expect(frame.calls.some((c) => c.includes('remount,rw'))).toBe(false);
		// The revert removes exactly the block, also after the user added a line.
		frame.files.set(HOSTS, new TextEncoder().encode(`${text}10.0.0.1 nas\n`));
		const r = hooks(frame);
		await runPlan(frame, planRevert(id, { keepData: false, restoreFrameo: false }), r.h, { continueOnError: true });
		expect(r.statuses.hosts).toBe('done');
		expect(new TextDecoder().decode(frame.files.get(HOSTS))).toBe(`${HOSTS_STOCK}10.0.0.1 nas\n`);
	});

	it('only accepts a BMP logo and a zip animation', () => {
		expect(bootAssetsProblem({ logo: LOGO, bootanimation: ANIM })).toBeNull();
		expect(bootAssetsProblem({ logo: ANIM, bootanimation: ANIM })).toMatch(/BMP/);
		expect(bootAssetsProblem({ logo: LOGO, bootanimation: LOGO })).toMatch(/zip/);
	});
});
