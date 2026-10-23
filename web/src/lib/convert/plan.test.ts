import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
	APPOPS_ALLOW,
	COMMANDS,
	DEFAULT_SERVER,
	DISABLE_PACKAGES,
	FRAME_ACTIVITY,
	FRAME_PACKAGE,
	FRAMEO_PACKAGE,
	GRANT_PERMISSIONS,
	INSTALL_NEWER_PRESENT,
	MIN_SDK,
	RELEASE_CERT_SHA256,
	RELEASES_REPO,
	SET_HOME_MIN_SDK,
	SUPPORTED_ABIS,
	SUPPORTED_MODELS,
	WORK_DIR,
	backupName,
	canConvert,
	canRevert,
	deviceFacts,
	fill,
	focusedPackage,
	identify,
	normalizeServerUrl,
	packageState,
	parseDumpsysPackage,
	parseGetprop,
	planConvert,
	planRevert,
	versionCodeForTag,
	type Action,
	type DeviceFacts,
	type PackageState,
	type Step
} from './plan';

// Real output from a Denver PFF-1024 (Android 6.0.1), trimmed. adb on Android 6 ends lines with CRLF.
const GETPROP = [
	'[ro.board.platform]: [rk312x]',
	'[ro.build.fingerprint]: [rockchip/rk312x/rk312x:6.0.1/MXC89K/user.wanghy.20260520.085907:user/release-keys]',
	'[ro.build.version.release]: [6.0.1]',
	'[ro.build.version.sdk]: [23]',
	'[ro.product.brand]: [denver]',
	'[ro.product.cpu.abi]: [armeabi-v7a]',
	'[ro.product.cpu.abi2]: [armeabi]',
	'[ro.product.cpu.abilist]: [armeabi-v7a,armeabi]',
	'[ro.product.device]: [PFF_1024]',
	'[ro.product.manufacturer]: [Harmony]',
	'[ro.product.model]: [Frame]',
	'[ro.serialno]: [36339301]'
].join('\r\n');
const ROOT = 'uid=0(root) gid=0(root) groups=0(root) context=u:r:toolbox:s0\r\n';
const NO_ROOT = '/system/bin/sh: su: not found\r\n';
// An updated system app is listed twice: the update first.
const FRAMEO_DUMPSYS = [
	'Packages:',
	'  Package [net.frameo.frame] (6c1d2a0):',
	'    codePath=/data/app/net.frameo.frame-2',
	'    versionCode=80320 targetSdk=34',
	'    versionName=v1.32.16',
	'    User 0:  installed=true hidden=false stopped=false notLaunched=false enabled=0',
	'Hidden system packages:',
	'  Package [net.frameo.frame] (2e7f5b1):',
	'    codePath=/system/priv-app/Frameo.apk',
	'    versionCode=69600 targetSdk=34',
	'    versionName=v1.30.13',
	'    User 0:  installed=true hidden=false stopped=false notLaunched=false enabled=3'
].join('\r\n');

const installed = (over: Partial<PackageState> = {}): PackageState => ({ installed: true, enabled: 'enabled', paths: ['/system/app/x.apk'], ...over });
const missing: PackageState = { installed: false, enabled: 'missing', paths: [] };

function facts(over: Partial<DeviceFacts> = {}, packages: Record<string, PackageState> = {}): DeviceFacts {
	return {
		...deviceFacts(GETPROP, ROOT, {}),
		packages: {
			[FRAME_PACKAGE]: missing,
			[FRAMEO_PACKAGE]: installed({ paths: ['/data/app/net.frameo.frame-2/base.apk'], versionName: 'v1.32.16', versionCode: 80320 }),
			'com.adups.fota': installed(),
			'com.adups.fota.sysoper': installed(),
			...packages
		},
		...over
	};
}

const shellCmds = (steps: Step[]) => steps.flatMap((s) => s.actions).filter((a): a is Extract<Action, { kind: 'shell' }> => a.kind === 'shell').map((a) => a.cmd);
const ids = (steps: Step[]) => steps.map((s) => s.id);

describe('parsing', () => {
	it('reads getprop output', () => {
		const p = parseGetprop(GETPROP);
		expect(p['ro.product.device']).toBe('PFF_1024');
		expect(p['ro.build.version.sdk']).toBe('23');
	});

	it('reads the running copy of an updated system app', () => {
		expect(parseDumpsysPackage(FRAMEO_DUMPSYS)).toEqual({ versionName: 'v1.32.16', versionCode: 80320, enabled: 'enabled' });
		expect(parseDumpsysPackage('    User 0:  installed=true hidden=false enabled=3').enabled).toBe('disabled');
		expect(parseDumpsysPackage('    User 0:  installed=true hidden=false enabled=2').enabled).toBe('disabled');
		expect(parseDumpsysPackage('    User 0:  installed=true hidden=false enabled=1').enabled).toBe('enabled');
	});

	it('package state comes from pm path first', () => {
		const s = packageState('package:/data/app/net.frameo.frame-2/base.apk\r\n', FRAMEO_DUMPSYS);
		expect(s).toEqual({ installed: true, enabled: 'enabled', paths: ['/data/app/net.frameo.frame-2/base.apk'], versionName: 'v1.32.16', versionCode: 80320 });
		expect(packageState('', '')).toEqual(missing);
	});

	it('collects device facts', () => {
		const f = deviceFacts(GETPROP, ROOT, {});
		expect(f).toMatchObject({ brand: 'denver', device: 'PFF_1024', sdk: 23, abis: ['armeabi-v7a', 'armeabi'], root: true, serial: '36339301' });
		expect(deviceFacts(GETPROP, NO_ROOT, {}).root).toBe(false);
		expect(deviceFacts('[ro.product.cpu.abi]: [arm64-v8a]', ROOT, {}).abis).toEqual(['arm64-v8a']);
	});

	it('finds the activity in front', () => {
		expect(focusedPackage('  mFocusedActivity: ActivityRecord{d19d313 u0 app.kinframe.frame/.MainActivity t31}\r\n')).toBe(FRAME_PACKAGE);
		expect(focusedPackage('    mResumedActivity: ActivityRecord{1 u0 net.frameo.frame/.ui.Main t2}')).toBe(FRAMEO_PACKAGE);
		expect(focusedPackage('nothing here')).toBeNull();
	});
});

describe('identification', () => {
	it('the Denver PFF-1024 is supported', () => {
		const id = identify(facts());
		expect(id).toMatchObject({ verdict: 'supported', issues: [], modelKey: 'denver/PFF_1024' });
		expect(canConvert(id, false)).toBe(true);
	});

	it('other models with root and Android 6+ are untested and need force', () => {
		const id = identify(facts({ brand: 'Acme', device: 'PF10' }));
		expect(id).toMatchObject({ verdict: 'untested', issues: ['model'], modelKey: 'acme/PF10' });
		expect(canConvert(id, false)).toBe(false);
		expect(canConvert(id, true)).toBe(true);
	});

	it('no root, old Android or another CPU are unsupported, even with force', () => {
		const cases: [Partial<DeviceFacts>, string][] = [
			[{ root: false }, 'root'],
			[{ sdk: 22 }, 'sdk'],
			[{ abis: ['x86'] }, 'abi']
		];
		for (const [over, issue] of cases) {
			const id = identify(facts(over));
			expect(id.verdict).toBe('unsupported');
			expect(id.issues).toContain(issue);
			expect(canConvert(id, true)).toBe(false);
		}
	});

	it('revert only needs root', () => {
		expect(canRevert(identify(facts({ brand: 'Acme', sdk: 22 })))).toBe(true);
		expect(canRevert(identify(facts({ root: false })))).toBe(false);
	});
});

describe('server URL', () => {
	it('normalizes what people type', () => {
		expect(normalizeServerUrl('frames.example.org')).toBe('https://frames.example.org');
		expect(normalizeServerUrl(' https://Frames.Example.org/ ')).toBe('https://frames.example.org');
		expect(normalizeServerUrl('http://192.168.1.20:8110')).toBe('http://192.168.1.20:8110');
		expect(normalizeServerUrl('https://example.org/kinframe/')).toBe('https://example.org/kinframe');
		expect(normalizeServerUrl(DEFAULT_SERVER)).toBe(DEFAULT_SERVER);
	});

	it('refuses anything that is not a plain http(s) address', () => {
		for (const bad of ['', 'ftp://x.org', 'https://x.org/?a=1', 'https://u:p@x.org', 'https://x.org/a b', 'https://x.org/;reboot', 'javascript:alert(1)']) {
			expect(normalizeServerUrl(bad), bad).toBeNull();
		}
	});
});

describe('commands', () => {
	it('fills templates and refuses unsafe values', () => {
		expect(fill('disable', { pkg: FRAMEO_PACKAGE })).toBe('su 0 pm disable-user net.frameo.frame');
		expect(fill('install')).toBe(`pm install -r -g ${WORK_DIR}/kinframe.apk`);
		expect(() => fill('disable', { pkg: 'x; reboot' })).toThrow();
		expect(() => fill('disable')).toThrow(/missing/);
	});

	it('version codes follow the release workflow', () => {
		expect(versionCodeForTag('v0.4.1')).toBe(40199);
		expect(versionCodeForTag('v0.3.0-beta.2')).toBe(30002);
		expect(versionCodeForTag('v1.2.3')).toBe(1020399);
		expect(versionCodeForTag('0.4.1')).toBeNull();
		expect(versionCodeForTag('v0.100.0')).toBeNull();
	});

	it('names backups after the package', () => {
		expect(backupName(FRAMEO_PACKAGE, '/data/app/net.frameo.frame-2/base.apk')).toBe('net.frameo.frame-base.apk');
	});
});

describe('convert plan', () => {
	const opts = { server: DEFAULT_SERVER, backup: true };

	it('backs up, verifies and installs before it disables anything', () => {
		const steps = planConvert(identify(facts()), opts);
		expect(ids(steps)).toEqual(['backup', 'download', 'install', 'permissions', 'disable', 'home', 'cleanup', 'reboot', 'provision']);
		const cmds = shellCmds(steps);
		const firstDisable = cmds.findIndex((c) => c.includes('disable-user'));
		expect(cmds.findIndex((c) => c.startsWith('pm install'))).toBeLessThan(firstDisable);
		expect(cmds.filter((c) => c.includes('disable-user'))).toEqual(DISABLE_PACKAGES.map((p) => `su 0 pm disable-user ${p}`));
		// Never uninstall system packages when converting.
		expect(cmds.some((c) => c.includes('uninstall'))).toBe(false);
	});

	it('pulls every Frameo APK and its data', () => {
		const steps = planConvert(identify(facts()), opts);
		const pulls = steps[0]!.actions.filter((a) => a.kind === 'pull');
		expect(pulls.map((a) => a.kind === 'pull' && a.name)).toEqual(['net.frameo.frame-base.apk', 'net.frameo.frame-data.tgz']);
		expect(shellCmds([steps[0]!])).toContain(`su 0 tar -czf ${WORK_DIR}/net.frameo.frame-data.tgz -C /data/data net.frameo.frame`);
	});

	it('skips the backup when asked, and when Frameo is gone', () => {
		expect(ids(planConvert(identify(facts()), { ...opts, backup: false }))).not.toContain('backup');
		expect(ids(planConvert(identify(facts({}, { [FRAMEO_PACKAGE]: missing })), opts))).not.toContain('backup');
	});

	it('only disables what is installed and still enabled', () => {
		const steps = planConvert(identify(facts({}, { 'com.adups.fota': missing, [FRAMEO_PACKAGE]: installed({ enabled: 'disabled' }) })), opts);
		expect(shellCmds(steps).filter((c) => c.includes('disable-user'))).toEqual(['su 0 pm disable-user com.adups.fota.sysoper']);
	});

	it('sets the home app only where pm can (Android 7+)', () => {
		const home = (sdk: number) => planConvert(identify(facts({ sdk })), opts).find((s) => s.id === 'home')!;
		expect(home(23).actions).toEqual([]);
		expect(shellCmds([home(SET_HOME_MIN_SDK)])).toEqual([`pm set-home-activity ${FRAME_ACTIVITY}`]);
	});

	it('allows only the app-ops that exist on the frame', () => {
		const ops = (sdk: number) => shellCmds(planConvert(identify(facts({ sdk })), opts)).filter((c) => c.startsWith('appops'));
		expect(ops(23)).toEqual([`appops set ${FRAME_PACKAGE} WRITE_SETTINGS allow`]);
		expect(ops(26)).toHaveLength(2);
	});

	it('provisions the server after the restart', () => {
		const steps = planConvert(identify(facts()), { ...opts, server: 'https://frames.example.org', language: 'nl' });
		const reboot = ids(steps).indexOf('reboot');
		expect(ids(steps).indexOf('provision')).toBeGreaterThan(reboot);
		expect(shellCmds(steps)).toContain(`am start -n ${FRAME_ACTIVITY} --es server_url https://frames.example.org --es language nl`);
		expect(shellCmds(planConvert(identify(facts()), opts))).toContain(`am start -n ${FRAME_ACTIVITY} --es server_url ${DEFAULT_SERVER}`);
	});

	it('accepts a newer kinframe that is already installed', () => {
		const install = planConvert(identify(facts()), opts).find((s) => s.id === 'install')!.actions.at(-1);
		expect(install).toMatchObject({ kind: 'shell', ok: ['Success', INSTALL_NEWER_PRESENT] });
	});
});

describe('revert plan', () => {
	const converted = () =>
		facts({}, {
			[FRAME_PACKAGE]: installed(),
			[FRAMEO_PACKAGE]: installed({ enabled: 'disabled' }),
			'com.adups.fota': installed({ enabled: 'disabled' }),
			'com.adups.fota.sysoper': installed({ enabled: 'disabled' })
		});

	it('enables, uninstalls kinframe and restarts', () => {
		const steps = planRevert(identify(converted()), { keepData: false, restoreFrameo: false });
		expect(ids(steps)).toEqual(['enable', 'uninstall', 'cleanup', 'reboot']);
		expect(shellCmds(steps)).toEqual([
			...DISABLE_PACKAGES.map((p) => `su 0 pm enable ${p}`),
			`pm uninstall ${FRAME_PACKAGE}`,
			`rm -rf ${WORK_DIR}`
		]);
		const uninstall = steps[1]!.actions[0]!;
		expect(uninstall).toMatchObject({ fallback: `su 0 pm uninstall ${FRAME_PACKAGE}` });
	});

	it('keeps kinframe data and restores a Frameo APK when asked', () => {
		const steps = planRevert(identify(converted()), { keepData: true, restoreFrameo: true });
		expect(ids(steps)[0]).toBe('restore');
		expect(shellCmds(steps)).toContain(`pm uninstall -k ${FRAME_PACKAGE}`);
		expect(shellCmds(steps)).toContain(`pm install -r -d ${WORK_DIR}/restore.apk`);
	});

	it('repairs a half-converted frame: kinframe already gone, only Frameo disabled', () => {
		const steps = planRevert(identify(facts({}, { [FRAMEO_PACKAGE]: installed({ enabled: 'disabled' }) })), { keepData: false, restoreFrameo: false });
		expect(ids(steps)).toEqual(['enable', 'cleanup', 'reboot']);
		expect(shellCmds(steps)).toEqual([`su 0 pm enable ${FRAMEO_PACKAGE}`, `rm -rf ${WORK_DIR}`]);
	});

	it('never uninstalls system packages', () => {
		const cmds = shellCmds(planRevert(identify(converted()), { keepData: false, restoreFrameo: true }));
		for (const p of DISABLE_PACKAGES) expect(cmds.some((c) => c.includes('uninstall') && c.includes(p))).toBe(false);
	});
});

// ---------------------------------------------------------------- the CLI must not drift from this plan

describe('CLI shared plan (tools/convert/kinframe-convert.sh)', () => {
	const script = readFileSync(new URL('../../../../tools/convert/kinframe-convert.sh', import.meta.url), 'utf8');
	const block = /# >>> shared plan[^\n]*\n([\s\S]*?)# <<< shared plan/.exec(script)?.[1] ?? '';
	const vars: Record<string, string> = {};
	for (const line of block.split('\n')) {
		const m = /^([A-Za-z_][A-Za-z0-9_]*)=(?:'([^']*)'|(\S*))$/.exec(line);
		if (m) vars[m[1]!] = m[2] ?? m[3] ?? '';
	}

	it('has the shared block', () => {
		expect(Object.keys(vars).length).toBeGreaterThan(20);
	});

	it('has the same constants', () => {
		expect(vars).toMatchObject({
			FRAME_PACKAGE,
			FRAME_ACTIVITY,
			RELEASE_CERT_SHA256,
			DEFAULT_SERVER,
			RELEASES_REPO,
			FRAMEO_PACKAGE,
			DISABLE_PACKAGES: DISABLE_PACKAGES.join(' '),
			GRANT_PERMISSIONS: GRANT_PERMISSIONS.join(' '),
			APPOPS_ALLOW: APPOPS_ALLOW.map((a) => `${a.op}:${a.minSdk}`).join(' '),
			SUPPORTED_MODELS: SUPPORTED_MODELS.join(' '),
			MIN_SDK: String(MIN_SDK),
			SUPPORTED_ABIS: SUPPORTED_ABIS.join(' '),
			SET_HOME_MIN_SDK: String(SET_HOME_MIN_SDK),
			WORK_DIR,
			INSTALL_NEWER_PRESENT
		});
	});

	it('has the same command templates', () => {
		const cli = Object.fromEntries(Object.entries(vars).filter(([k]) => k.startsWith('CMD_')).map(([k, v]) => [k.slice(4), v]));
		const web = Object.fromEntries(Object.entries(COMMANDS).map(([k, v]) => [k, v.replace(/\{(\w+)\}/g, '$$$1')]));
		expect(cli).toEqual(web);
	});

	it('uses every command template', () => {
		for (const name of Object.keys(COMMANDS)) expect(script, name).toMatch(new RegExp(`\\$CMD_${name}\\b`));
	});
});
