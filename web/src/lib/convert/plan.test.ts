import { execFileSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { bmpInfo, brandResource, buildResource } from './rsce';
import {
	APPOPS_ALLOW,
	BOOT_ANIMATION,
	BOOT_ANIMATION_BACKUP,
	BOOT_ASSETS,
	BOOT_BRAND,
	RESOURCE_BACKUP,
	ADUPS_HOSTS,
	ADUPS_PACKAGE,
	EXTRA_PACKAGES,
	HOSTS,
	HOSTS_BEGIN,
	HOSTS_END,
	STATE_FILE,
	addHostsBlock,
	describeUpdater,
	extrasToDisable,
	extrasToEnable,
	hostsBlockLines,
	parseAdupsPrefs,
	parseState,
	removeHostsBlock,
	updaterLeftovers,
	updatePending,
	ROOT_CERT,
	ROOT_CERT_ASSETS,
	ROOT_CERT_BEFORE_SDK,
	ROOT_CERT_FILE,
	ROOT_CERT_SHA256,
	SETTLE_SECONDS,
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
	bootAnimationPossible,
	bootLogoPossible,
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
	parseResourcePath,
	parseSystemDevice,
	parseDisabledPackages,
	parseRootCert,
	parseScreenSize,
	pickBootAnimation,
	pickBootLogo,
	rootCertNeeded,
	planConvert,
	planRevert,
	versionCodeForTag,
	type Action,
	type BootManifest,
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
	'[ro.serialno]: [36339301]',
	'[ro.sf.hwrotation]: [270]'
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

const RESOURCE = '/dev/block/platform/1021c000.rksdmmc/by-name/resource';
const SYSTEM = '/dev/block/platform/1021c000.rksdmmc/by-name/system';
const MOUNTS = ['rootfs / rootfs ro,seclabel,relatime 0 0', `${SYSTEM} /system ext4 ro,seclabel,noatime 0 0`, '/dev/block/mmcblk0p13 /data ext4 rw 0 0'].join('\r\n');

const installed = (over: Partial<PackageState> = {}): PackageState => ({ installed: true, enabled: 'enabled', paths: ['/system/app/x.apk'], ...over });
const missing: PackageState = { installed: false, enabled: 'missing', paths: [] };

function facts(over: Partial<DeviceFacts> = {}, packages: Record<string, PackageState> = {}): DeviceFacts {
	return {
		...deviceFacts(GETPROP, ROOT, {}, { findResource: `${RESOURCE}\r\n`, mounts: MOUNTS, screenSize: 'Physical size: 1280x800\r\n' }),
		packages: {
			[FRAME_PACKAGE]: missing,
			[FRAMEO_PACKAGE]: installed({ paths: ['/data/app/net.frameo.frame-2/base.apk'], versionName: 'v1.32.16', versionCode: 80320 }),
			'com.adups.fota': installed(),
			'com.adups.fota.sysoper': installed(),
			'android.rockchip.update.service': installed(),
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

	it('finds the resource partition and the /system device', () => {
		expect(parseResourcePath(`${RESOURCE}\r\n`)).toBe(RESOURCE);
		expect(parseResourcePath('/dev/block/platform/*/by-name/resource: No such file or directory\r\n')).toBe('');
		expect(parseSystemDevice(MOUNTS)).toBe(SYSTEM);
		expect(parseSystemDevice('/dev/block/dm-0 on /system type ext4 (ro,seclabel)')).toBe('/dev/block/dm-0');
		expect(parseSystemDevice('')).toBe('');
		const f = deviceFacts(GETPROP, ROOT, {}, { findResource: RESOURCE, mounts: MOUNTS });
		expect(f).toMatchObject({ resource: RESOURCE, system: SYSTEM });
		expect(bootLogoPossible(f) && bootAnimationPossible(f)).toBe(true);
		expect(bootLogoPossible({ ...f, root: false })).toBe(false);
		expect(bootLogoPossible({ ...f, resource: '' })).toBe(false);
		// dm-verity: /system can't be remounted writable.
		expect(bootAnimationPossible({ ...f, system: '/dev/block/dm-0' })).toBe(false);
		expect(bootAnimationPossible({ ...f, system: '' })).toBe(false);
	});

	it('reads the screen size, the CA store check and the disabled packages', () => {
		expect(parseScreenSize('Physical size: 1920x1080\r\n')).toBe('1920x1080');
		expect(parseScreenSize('Physical size: 1280x800\r\nOverride size: 1024x640\r\n')).toBe('1280x800');
		expect(parseScreenSize('')).toBe('');
		expect(parseRootCert(`${ROOT_CERT}\r\n`)).toBe('present');
		expect(parseRootCert(`ls: ${ROOT_CERT}: No such file or directory\r\n`)).toBe('missing');
		expect(parseRootCert('')).toBe('unknown');
		expect([...parseDisabledPackages('package:net.frameo.frame\r\npackage:com.adups.fota\r\n')]).toEqual(['net.frameo.frame', 'com.adups.fota']);
		const f = deviceFacts(GETPROP, ROOT, {}, { screenSize: 'Physical size: 1280x800', rootCert: 'No such file or directory' });
		expect(f).toMatchObject({ screen: '1280x800', hwrotation: 270, rootCert: 'missing' });
		expect(deviceFacts(GETPROP, ROOT, {}).rootCert).toBe('unknown');
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
		expect(ids(steps)).toEqual(['backup', 'download', 'hosts', 'install', 'permissions', 'disable', 'home', 'cleanup', 'reboot', 'provision']);
		const cmds = shellCmds(steps);
		const firstDisable = cmds.findIndex((c) => c.includes('disable-user'));
		expect(cmds.findIndex((c) => c.startsWith('pm install'))).toBeLessThan(firstDisable);
		expect(cmds.filter((c) => c.includes('disable-user'))).toEqual(DISABLE_PACKAGES.map((p) => `su 0 pm disable-user ${p}`));
		// Never uninstall system packages when converting.
		expect(cmds.some((c) => c.includes('uninstall'))).toBe(false);
	});

	it('pulls every Frameo APK and backs up its data', () => {
		const steps = planConvert(identify(facts()), opts);
		const pulls = steps[0]!.actions.filter((a) => a.kind === 'pull');
		expect(pulls.map((a) => a.kind === 'pull' && a.name)).toEqual(['net.frameo.frame-base.apk']);
		expect(steps[0]!.actions).toContainEqual({ kind: 'backupData', pkg: FRAMEO_PACKAGE });
		// No archive on the frame (toybox tar can't store long names): a pruned, readable copy is pulled instead.
		expect(Object.values(COMMANDS).some((c) => c.includes(' tar '))).toBe(false);
		expect(fill('copyData', { pkg: FRAMEO_PACKAGE })).toBe(`su 0 cp -a /data/data/net.frameo.frame ${WORK_DIR}/net.frameo.frame`);
		expect(fill('pruneData', { pkg: FRAMEO_PACKAGE })).toContain(`${WORK_DIR}/net.frameo.frame/lib `);
		expect(fill('readableData', { pkg: FRAMEO_PACKAGE })).toBe(`su 0 chmod -R a+rX ${WORK_DIR}/net.frameo.frame`);
		expect(planConvert(identify(facts({ root: false })), opts)[0]?.actions.some((a) => a.kind === 'backupData')).toBeFalsy();
	});

	it('skips the backup when asked, and when Frameo is gone', () => {
		expect(ids(planConvert(identify(facts()), { ...opts, backup: false }))).not.toContain('backup');
		expect(ids(planConvert(identify(facts({}, { [FRAMEO_PACKAGE]: missing })), opts))).not.toContain('backup');
	});

	it('only disables what is installed and still enabled, and checks every installed one', () => {
		const steps = planConvert(identify(facts({}, { 'com.adups.fota': missing, [FRAMEO_PACKAGE]: installed({ enabled: 'disabled' }) })), opts);
		expect(shellCmds(steps).filter((c) => c.includes('disable-user'))).toEqual(['su 0 pm disable-user com.adups.fota.sysoper', 'su 0 pm disable-user android.rockchip.update.service']);
		expect(steps.find((s) => s.id === 'disable')!.actions.at(-1)).toEqual({
			kind: 'verifyDisabled',
			packages: [FRAMEO_PACKAGE, 'com.adups.fota.sysoper', 'android.rockchip.update.service'],
			reapply: false
		});
	});

	it('restarts only after the package states are saved, and checks they survived', () => {
		const reboot = planConvert(identify(facts()), opts).find((s) => s.id === 'reboot')!;
		expect(reboot.actions.map((a) => (a.kind === 'shell' ? a.cmd : a.kind))).toEqual([
			`sleep ${SETTLE_SECONDS}`,
			'su 0 sync',
			'reboot',
			'waitBoot',
			'verifyDisabled'
		]);
		expect(SETTLE_SECONDS).toBeGreaterThan(10); // PackageManager writes package-restrictions.xml 10 s after a change
		expect(reboot.actions.at(-1)).toEqual({ kind: 'verifyDisabled', packages: [...DISABLE_PACKAGES], reapply: true });
		const revert = planRevert(identify(facts()), { keepData: false, restoreFrameo: false }).find((s) => s.id === 'reboot')!;
		expect(shellCmds([revert])).toEqual([`sleep ${SETTLE_SECONDS}`, 'su 0 sync']);
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

describe('start-up branding', () => {
	const opts = { server: DEFAULT_SERVER, backup: true, bootBranding: true };
	const flat = (steps: Step[]) => steps.flatMap((s) => s.actions);

	it('backs up both originals first and writes them before the app changes', () => {
		const steps = planConvert(identify(facts()), opts);
		expect(ids(steps)).toEqual(['backup', 'bootBackup', 'download', 'bootLogo', 'bootAnimation', 'hosts', 'install', 'permissions', 'disable', 'home', 'cleanup', 'reboot', 'provision']);
		const backup = steps.find((s) => s.id === 'bootBackup')!;
		expect(backup.actions.filter((a) => a.kind === 'pull').map((a) => a.kind === 'pull' && [a.remote, a.name])).toEqual([
			[BOOT_ANIMATION, BOOT_ANIMATION_BACKUP],
			[`${WORK_DIR}/${RESOURCE_BACKUP}`, RESOURCE_BACKUP]
		]);
		expect(shellCmds([backup])).toContain(`su 0 dd if=${RESOURCE} of=${WORK_DIR}/${RESOURCE_BACKUP} bs=1048576`);
		// Nothing writes before both backups are pulled.
		const all = flat(steps);
		const lastBackupPull = all.findIndex((a) => a.kind === 'pull' && a.name === RESOURCE_BACKUP);
		const firstWrite = all.findIndex((a) => a.kind === 'flashResource' || (a.kind === 'shell' && /remount|of=\/dev|pm (install|disable)/.test(a.cmd)));
		expect(lastBackupPull).toBeGreaterThan(-1);
		expect(firstWrite).toBeGreaterThan(lastBackupPull);
		expect(steps.find((s) => s.id === 'download')!.actions).toEqual([
			{ kind: 'fetch' },
			{ kind: 'fetchBoot', logo: true, animation: true, screen: '1280x800', hwrotation: 270 }
		]);
		expect(steps.find((s) => s.id === 'bootLogo')!.actions).toEqual([{ kind: 'brandResource' }, { kind: 'flashResource', resource: RESOURCE }]);
	});

	it('remounts /system writable only around the copy, and checks the result', () => {
		const anim = planConvert(identify(facts()), opts).find((s) => s.id === 'bootAnimation')!;
		expect(shellCmds([anim])).toEqual([
			`mkdir -p ${WORK_DIR}`,
			`su 0 mount -o remount,rw ${SYSTEM} /system`,
			`su 0 cp ${WORK_DIR}/bootanimation.zip ${BOOT_ANIMATION}`,
			`su 0 chmod 644 ${BOOT_ANIMATION}`,
			`su 0 mount -o remount,ro ${SYSTEM} /system`,
			`su 0 rm -f ${WORK_DIR}/bootanimation.zip`
		]);
		expect(anim.actions.map((a) => a.kind)).toContain('verifyBootAnimation');
	});

	it('is optional, and leaves out what the frame does not allow', () => {
		expect(ids(planConvert(identify(facts()), { ...opts, bootBranding: false })).filter((i) => i.startsWith('boot'))).toEqual([]);
		expect(ids(planConvert(identify(facts({ resource: '' })), opts)).filter((i) => i.startsWith('boot'))).toEqual(['bootBackup', 'bootAnimation']);
		expect(ids(planConvert(identify(facts({ system: '/dev/block/dm-0' })), opts)).filter((i) => i.startsWith('boot'))).toEqual(['bootBackup', 'bootLogo']);
		expect(planConvert(identify(facts({ resource: '' })), opts).find((s) => s.id === 'download')!.actions[1]).toMatchObject({ logo: false, animation: true });
		// The boot backup happens even without the app backup.
		expect(ids(planConvert(identify(facts()), { ...opts, backup: false }))[0]).toBe('bootBackup');
	});

	it('revert puts back what was chosen', () => {
		const id = identify(facts());
		expect(ids(planRevert(id, { keepData: false, restoreFrameo: false })).filter((i) => i.startsWith('boot'))).toEqual([]);
		const steps = planRevert(id, { keepData: false, restoreFrameo: false, restoreBootLogo: true, restoreBootAnimation: true });
		expect(ids(steps).slice(0, 2)).toEqual(['bootLogo', 'bootAnimation']);
		expect(steps[0]!.actions).toEqual([{ kind: 'flashResource', resource: RESOURCE }]);
	});
});

describe('start-up branding variants', () => {
	const dir = new URL('../../../static/frame-boot/kinframe/', import.meta.url);
	const manifest = JSON.parse(readFileSync(new URL('manifest.json', dir), 'utf8')) as BootManifest;
	const PFF_1024 = { width: 800, height: 1280, bpp: 8, compression: 1 };
	const PFF_1515 = { width: 1920, height: 1080, bpp: 8, compression: 1 };

	it('the committed files match the manifest (tools/brand/boot/build.py)', () => {
		for (const v of [...manifest.logos, ...manifest.bootanimations]) {
			const data = readFileSync(new URL(v.file, dir));
			expect(createHash('sha256').update(data).digest('hex'), v.file).toBe(v.sha256);
		}
		for (const v of manifest.logos) {
			expect(bmpInfo(new Uint8Array(readFileSync(new URL(v.file, dir)))), v.file).toEqual({ width: v.width, height: v.height, bpp: v.bpp, compression: v.compression });
		}
		for (const v of manifest.bootanimations) {
			const zip = readFileSync(new URL(v.file, dir));
			const desc = /(\d+) (\d+) \d+\n/.exec(zip.toString('latin1'))!;
			const [w, h] = v.screen.split('x').map(Number);
			expect(Number(desc[1]) <= w! && Number(desc[2]) <= h!, v.file).toBe(true);
		}
		// One entry per line: the CLI reads the manifest with sed.
		const text = readFileSync(new URL('manifest.json', dir), 'utf8');
		for (const v of [...manifest.logos, ...manifest.bootanimations]) expect(text.split('\n').filter((l) => l.includes(v.sha256))).toHaveLength(1);
	});

	it('picks the logo with exactly the frame logo format and panel rotation', () => {
		expect(pickBootLogo(manifest, PFF_1024, 270)?.file).toBe('logo-800x1280-r270.bmp');
		expect(pickBootLogo(manifest, PFF_1515, 180)?.file).toBe('logo-1920x1080-r180.bmp');
		// Same size but another mounting would show the logo turned: refused.
		expect(pickBootLogo(manifest, PFF_1515, 0)).toBeUndefined();
		expect(pickBootLogo(manifest, { ...PFF_1515, compression: 0 }, 180)).toBeUndefined();
		expect(pickBootLogo(manifest, { ...PFF_1024, width: 720 }, 270)).toBeUndefined();
	});

	it('picks the animation for the screen size', () => {
		expect(pickBootAnimation(manifest, '1280x800')?.file).toBe('bootanimation-1280x800.zip');
		expect(pickBootAnimation(manifest, '1920x1080')?.file).toBe('bootanimation-1920x1080.zip');
		expect(pickBootAnimation(manifest, '800x1280')?.file).toBe('bootanimation-1280x800.zip');
		expect(pickBootAnimation(manifest, '1024x600')).toBeUndefined();
		expect(pickBootAnimation(manifest, '')).toBeUndefined();
	});
});

describe("Let's Encrypt root certificate", () => {
	const opts = { server: DEFAULT_SERVER, backup: false };
	const lacking = (over: Partial<DeviceFacts> = {}) => facts({ rootCert: 'missing', ...over });

	it('the served file is ISRG Root X1 in the CA store format, with the pinned sha256', () => {
		const file = readFileSync(new URL(`../../../static${ROOT_CERT_ASSETS}/${ROOT_CERT_FILE}`, import.meta.url));
		expect(createHash('sha256').update(file).digest('hex')).toBe(ROOT_CERT_SHA256);
		const text = file.toString('latin1');
		expect(text.startsWith('-----BEGIN CERTIFICATE-----\n')).toBe(true);
		expect(text).toMatch(/\nCertificate:\n[\s\S]*\nSHA1 Fingerprint=CA:BD:2A:79:A1:07:6A:31:F2:1D:25:36:35:CB:03:9D:43:29:A5:E8\n$/);
		const cert = new X509Certificate(file);
		expect(cert.fingerprint256).toBe('96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6');
		expect(cert.subject).toContain('CN=ISRG Root X1');
		// The same certificate the app bundles.
		const app = new X509Certificate(readFileSync(new URL('../../../../android/frame/src/main/res/raw/isrg_root_x1.pem', import.meta.url)));
		expect(app.fingerprint256).toBe(cert.fingerprint256);
		expect(ROOT_CERT).toBe(`/system/etc/security/cacerts/${ROOT_CERT_FILE}`);
	});

	it('is added only before Android 7, when missing, with a writable /system', () => {
		expect(rootCertNeeded(lacking())).toBe(true);
		expect(rootCertNeeded(lacking({ sdk: ROOT_CERT_BEFORE_SDK }))).toBe(false);
		expect(rootCertNeeded(lacking({ rootCert: 'present' }))).toBe(false);
		expect(rootCertNeeded(lacking({ rootCert: 'unknown' }))).toBe(false);
		expect(rootCertNeeded(lacking({ system: '/dev/block/dm-0' }))).toBe(false);
		expect(rootCertNeeded(lacking({ root: false }))).toBe(false);
	});

	it('is an optional step before the app changes, with the remount pattern, and can be turned off', () => {
		const steps = planConvert(identify(lacking()), opts);
		expect(ids(steps).slice(0, 4)).toEqual(['download', 'rootCert', 'hosts', 'install']);
		expect(steps[0]!.actions).toContainEqual({ kind: 'fetchRootCert' });
		const cert = steps[1]!;
		expect(cert.optional).toBe(true);
		expect(shellCmds([cert])).toEqual([
			`mkdir -p ${WORK_DIR}`,
			`su 0 mount -o remount,rw ${SYSTEM} /system`,
			`su 0 cp ${WORK_DIR}/${ROOT_CERT_FILE} ${ROOT_CERT}`,
			`su 0 chmod 644 ${ROOT_CERT}`,
			`su 0 chown 0:0 ${ROOT_CERT}`,
			`su 0 mount -o remount,ro ${SYSTEM} /system`,
			`su 0 rm -f ${WORK_DIR}/${ROOT_CERT_FILE}`
		]);
		expect(cert.actions.map((a) => a.kind)).toContain('verifyRootCert');
		expect(ids(planConvert(identify(lacking()), { ...opts, rootCert: false }))).not.toContain('rootCert');
		expect(ids(planConvert(identify(facts()), opts))).not.toContain('rootCert');
		// Revert leaves it: it is a standard public root.
		expect(ids(planRevert(identify(lacking()), { keepData: false, restoreFrameo: false }))).not.toContain('rootCert');
	});
});

describe('firmware updater', () => {
	// A synthetic SharedPreferences file with an update downloaded.
	const PREFS = [
		"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
		'<map>',
		'    <int name="ota_update_status" value="4" />',
		'    <string name="update_package_path">/storage/1234-ABCD/Android/data/com.adups.fota/files/adupsfota/update.zip</string>',
		'    <string name="CustomDtPath">/storage/1234-ABCD/Android/data/com.adups.fota/files</string>',
		'    <string name="other">a &amp; b</string>',
		'</map>'
	].join('\r\n');
	const EXT = '/storage/1234-ABCD/Android/data/com.adups.fota/files/adupsfota';
	const probes = {
		listRecovery: 'command\r\nlast_log\r\n',
		readRecoveryCommand: '--update_package=/data/data/com.adups.fota/files/adupsfota/update.zip\r\n--locale=en_US\r\n',
		readUpdateResult: 'cat: /data/data/com.adups.fota/files/updateResult: No such file or directory\r\n',
		readAdupsPrefs: PREFS,
		listAdupsInternal: 'update.zip\r\n',
		listAdupsExternal: [
			`${EXT}`,
			'ls: /storage/emulated/0/Android/data/com.adups.fota/files/adupsfota: No such file or directory',
			'ls: /storage/*/adupsfota: No such file or directory',
			'/storage/emulated/0/adupsfota',
			'/storage/x y/Android/data/com.adups.fota/files/adupsfota'
		].join('\r\n')
	};

	it('reads the leftovers and sees a pending update', () => {
		expect(parseAdupsPrefs(PREFS)).toEqual({ status: 4, packagePath: `${EXT}/update.zip`, downloadDir: '/storage/1234-ABCD/Android/data/com.adups.fota/files' });
		expect(parseAdupsPrefs('')).toEqual({ status: null, packagePath: '', downloadDir: '' });
		const u = updaterLeftovers(probes);
		expect(u).toMatchObject({
			recovery: ['command', 'last_log'],
			recoveryCommand: '--update_package=/data/data/com.adups.fota/files/adupsfota/update.zip\n--locale=en_US',
			updateResult: '',
			status: 4,
			internal: ['update.zip'],
			downloads: [EXT], // the path with a space is skipped: it can't go on the command line
			otherDownloads: ['/storage/emulated/0/adupsfota']
		});
		expect(updatePending(u)).toBe(true);
		expect(describeUpdater(u).join('\n')).toMatch(/update downloaded, update at .*\n\/cache\/recovery: command, last_log \(command: --update_package=/);
		const idle = updaterLeftovers({ readAdupsPrefs: '<int name="ota_update_status" value="0" />', listRecovery: 'ls: /cache/recovery: No such file or directory' });
		expect(updatePending(idle)).toBe(false);
		expect(idle.recovery).toEqual([]);
	});

	it('clears the updater before it is disabled, and disables RKUpdateService too', () => {
		const f = { ...facts(), updater: updaterLeftovers(probes) };
		const disable = planConvert(identify(f), { server: DEFAULT_SERVER, backup: false }).find((s) => s.id === 'disable')!;
		expect(disable.actions.slice(0, 3)).toEqual([
			{ kind: 'shell', cmd: 'am force-stop com.adups.fota', modifies: true, optional: true },
			{ kind: 'shell', cmd: 'pm clear com.adups.fota', modifies: true, ok: ['Success'], optional: true },
			{ kind: 'clearUpdater', downloads: [EXT], recoveryCommand: true }
		]);
		expect(shellCmds([disable])).toContain('su 0 pm disable-user android.rockchip.update.service');
		expect(DISABLE_PACKAGES).toContain('android.rockchip.update.service');
		// Nothing to clear when there are no leftovers.
		expect(planConvert(identify(facts()), { server: DEFAULT_SERVER, backup: false }).find((s) => s.id === 'disable')!.actions.some((a) => a.kind === 'clearUpdater')).toBe(false);
	});
});

describe('hosts file', () => {
	const BLOCK = `${HOSTS_BEGIN}\n0.0.0.0 fota5p.adups.com\n0.0.0.0 fota5p.adups.cn\n0.0.0.0 fruet.adups.com\n${HOSTS_END}\n`;
	const CASES = [
		'127.0.0.1       localhost\n::1             ip6-localhost\n',
		'127.0.0.1 localhost', // no newline at the end
		'',
		`127.0.0.1 localhost\n${BLOCK}`,
		`${BLOCK}127.0.0.1 localhost\n\n# mine\n10.0.0.1 nas\n`,
		`127.0.0.1 localhost\n${HOSTS_BEGIN}\n0.0.0.0 fota5p.adups.com\n10.0.0.1 nas\n`, // unterminated block
		`127.0.0.1 localhost\n${BLOCK}${BLOCK}`
	];

	it('adds the block once and removes exactly that block', () => {
		expect(hostsBlockLines()).toEqual(['# >>> kinframe: block the Adups firmware update servers (kinframe revert removes this block)', ...ADUPS_HOSTS.map((h) => `0.0.0.0 ${h}`), '# <<< kinframe']);
		const plain = CASES[0]!;
		expect(addHostsBlock(plain)).toBe(plain + BLOCK);
		expect(addHostsBlock(addHostsBlock(plain))).toBe(plain + BLOCK);
		expect(removeHostsBlock(addHostsBlock(plain))).toBe(plain);
		expect(removeHostsBlock(CASES[4]!)).toBe('127.0.0.1 localhost\n\n# mine\n10.0.0.1 nas\n');
		expect(removeHostsBlock(CASES[5]!)).toBe('127.0.0.1 localhost\n10.0.0.1 nas\n');
		expect(removeHostsBlock(plain)).toBe(plain);
		expect(addHostsBlock('')).toBe(BLOCK);
	});

	it('the plan blocks when converting and unblocks when reverting, only where /system can change', () => {
		const convert = planConvert(identify(facts()), { server: DEFAULT_SERVER, backup: false }).find((s) => s.id === 'hosts')!;
		expect(convert).toEqual({ id: 'hosts', optional: true, actions: [{ kind: 'editHosts', mode: 'block', system: SYSTEM }] });
		const verity = planConvert(identify(facts({ system: '/dev/block/dm-0' })), { server: DEFAULT_SERVER, backup: false }).find((s) => s.id === 'hosts')!;
		expect(verity.actions[0]).toMatchObject({ kind: 'note', warn: true });
		expect(planRevert(identify(facts()), { keepData: false, restoreFrameo: false }).find((s) => s.id === 'hosts')!.actions).toEqual([{ kind: 'editHosts', mode: 'unblock', system: SYSTEM }]);
		expect(ids(planRevert(identify(facts({ system: '/dev/block/dm-0' })), { keepData: false, restoreFrameo: false }))).not.toContain('hosts');
	});

	it('the script builds the same files (kinframe-convert.sh --edit-hosts)', () => {
		const script = new URL('../../../../tools/convert/kinframe-convert.sh', import.meta.url).pathname;
		const dir = mkdtempSync(join(tmpdir(), 'kinframe-hosts-'));
		try {
			for (const [i, text] of CASES.entries()) {
				for (const mode of ['block', 'unblock'] as const) {
					writeFileSync(join(dir, 'in'), text);
					execFileSync('sh', [script, '--edit-hosts', mode, join(dir, 'in'), join(dir, 'out')], { stdio: 'pipe', env: { ...process.env, TMPDIR: dir } });
					const want = mode === 'block' ? addHostsBlock(text) : removeHostsBlock(text);
					expect(readFileSync(join(dir, 'out'), 'utf8'), `case ${i} ${mode}`).toBe(want);
				}
			}
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});
});

describe('extra packages', () => {
	// Packages kinframe or Android needs: never on any of our lists.
	const KEEP = [
		FRAME_PACKAGE,
		'android',
		'com.android.settings',
		'com.android.packageinstaller',
		'com.android.inputmethod.latin',
		'jp.co.omronsoft.openwnn',
		'com.android.documentsui',
		'com.android.externalstorage',
		'com.android.providers.media',
		'com.android.providers.downloads',
		'com.android.providers.settings',
		'com.android.providers.telephony',
		'com.android.providers.contacts',
		'com.android.providers.calendar',
		'com.android.providers.userdictionary',
		'com.google.android.webview',
		'com.android.webview',
		'com.android.captiveportallogin',
		'com.android.certinstaller',
		'com.android.keychain',
		'com.android.shell',
		'com.android.defcontainer',
		'com.android.phone',
		'com.android.server.telecom',
		'com.android.mms.service',
		'com.android.systemui',
		'com.android.provision',
		'com.android.location.fused',
		'com.android.proxyhandler',
		'com.android.pacprocessor'
	];
	const ALL = [...EXTRA_PACKAGES];
	const withExtras = (over: Partial<DeviceFacts> = {}) =>
		facts({ installed: [...ALL, 'com.android.systemui', 'com.android.settings'], disabled: ['com.android.wallpaper', 'com.android.systemui'], ...over });

	it('never names a package kinframe or Android needs', () => {
		for (const p of KEEP) {
			expect(ALL, p).not.toContain(p);
			expect(DISABLE_PACKAGES as readonly string[], p).not.toContain(p);
		}
		expect(new Set(ALL).size).toBe(ALL.length);
		expect(ALL.filter((p) => (DISABLE_PACKAGES as readonly string[]).includes(p))).toEqual([]);
		// The README gives a reason for each.
		const readme = readFileSync(new URL('../../../../tools/convert/README.md', import.meta.url), 'utf8');
		for (const p of ALL) expect(readme, p).toContain(`| \`${p}\` |`);
	});

	it('converting disables the installed, enabled ones, records them, and checks them after the restart', () => {
		const f = withExtras({ extrasState: ['com.android.music'] });
		expect(extrasToDisable(f)).toEqual(ALL.filter((p) => p !== 'com.android.wallpaper'));
		const steps = planConvert(identify(f), { server: DEFAULT_SERVER, backup: false });
		const extras = steps.find((s) => s.id === 'extras')!;
		expect(extras.optional).toBe(true);
		expect(ids(steps).indexOf('extras')).toBe(ids(steps).indexOf('disable') + 1);
		expect(shellCmds([extras])).toEqual(extrasToDisable(f).map((p) => `su 0 pm disable-user ${p}`));
		expect(extras.actions.at(-1)).toEqual({ kind: 'saveState', packages: extrasToDisable(f), previous: ['com.android.music'] });
		expect(steps.find((s) => s.id === 'reboot')!.actions.at(-1)).toEqual({ kind: 'verifyDisabled', packages: extrasToDisable(f), reapply: true, warnOnly: true });
		expect(ids(planConvert(identify(f), { server: DEFAULT_SERVER, backup: false, extras: false }))).not.toContain('extras');
		expect(ids(planConvert(identify(facts()), { server: DEFAULT_SERVER, backup: false }))).not.toContain('extras');
	});

	it('reverting enables what the convert recorded, or without a record every disabled extra; never System UI', () => {
		const disabled = ['com.android.music', 'com.android.browser', 'com.android.systemui', 'com.android.settings'];
		const recorded = withExtras({ disabled, extrasState: ['com.android.music', 'com.android.calculator2'] });
		expect(extrasToEnable(recorded)).toEqual(['com.android.music']);
		expect(extrasToEnable(withExtras({ disabled, extrasState: null }))).toEqual(['com.android.browser', 'com.android.music']);
		const steps = planRevert(identify(recorded), { keepData: false, restoreFrameo: false });
		const enable = steps.find((s) => s.id === 'enable')!;
		expect(enable.actions.at(-1)).toEqual({ kind: 'shell', cmd: 'su 0 pm enable com.android.music', modifies: true, ok: ['enabled'], optional: true });
		expect(shellCmds(steps).some((c) => c.includes('systemui') || c.includes('settings'))).toBe(false);
		expect(shellCmds([steps.find((s) => s.id === 'cleanup')!])).toEqual([`rm -rf ${WORK_DIR}`, `rm -f ${STATE_FILE}`]);
		expect(shellCmds([planRevert(identify(withExtras({ disabled })), { keepData: false, restoreFrameo: false }).find((s) => s.id === 'cleanup')!])).toEqual([`rm -rf ${WORK_DIR}`]);
	});

	it('reads the record and the package lists', () => {
		expect(parseState(`# kinframe convert\r\ncom.android.music\r\ncom.android.systemui\r\nbogus\r\n`)).toEqual(['com.android.music']);
		expect(parseState(`cat: ${STATE_FILE}: No such file or directory`)).toBeNull();
		expect(parseState('# header only\n')).toEqual([]);
		const f = deviceFacts(GETPROP, ROOT, {}, { listPackages: 'package:com.android.music\r\npackage:android\r\n', listDisabled: 'package:com.android.music\r\n', readState: 'com.android.music\n' });
		expect(f).toMatchObject({ installed: ['com.android.music', 'android'], disabled: ['com.android.music'], extrasState: ['com.android.music'] });
	});
});

describe('revert plan', () => {
	const converted = () =>
		facts({}, {
			[FRAME_PACKAGE]: installed(),
			[FRAMEO_PACKAGE]: installed({ enabled: 'disabled' }),
			'com.adups.fota': installed({ enabled: 'disabled' }),
			'com.adups.fota.sysoper': installed({ enabled: 'disabled' }),
			'android.rockchip.update.service': installed({ enabled: 'disabled' })
		});

	it('enables, uninstalls kinframe and restarts', () => {
		const steps = planRevert(identify(converted()), { keepData: false, restoreFrameo: false });
		expect(ids(steps)).toEqual(['hosts', 'enable', 'uninstall', 'cleanup', 'reboot']);
		expect(shellCmds(steps)).toEqual([
			...DISABLE_PACKAGES.map((p) => `su 0 pm enable ${p}`),
			`pm uninstall ${FRAME_PACKAGE}`,
			`rm -rf ${WORK_DIR}`,
			`sleep ${SETTLE_SECONDS}`,
			'su 0 sync'
		]);
		const uninstall = steps.find((s) => s.id === 'uninstall')!.actions[0]!;
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
		expect(ids(steps)).toEqual(['hosts', 'enable', 'cleanup', 'reboot']);
		expect(shellCmds(steps)).toEqual([`su 0 pm enable ${FRAMEO_PACKAGE}`, `rm -rf ${WORK_DIR}`, `sleep ${SETTLE_SECONDS}`, 'su 0 sync']);
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
			INSTALL_NEWER_PRESENT,
			BOOT_BRAND,
			BOOT_ASSETS,
			BOOT_ANIMATION,
			RESOURCE_BACKUP,
			BOOT_ANIMATION_BACKUP,
			SETTLE_SECONDS: String(SETTLE_SECONDS),
			ROOT_CERT_FILE,
			ROOT_CERT,
			ROOT_CERT_ASSETS,
			ROOT_CERT_SHA256,
			ROOT_CERT_BEFORE_SDK: String(ROOT_CERT_BEFORE_SDK),
			ADUPS_PACKAGE,
			EXTRA_PACKAGES: EXTRA_PACKAGES.join(' '),
			STATE_FILE,
			ADUPS_HOSTS: ADUPS_HOSTS.join(' '),
			HOSTS_BEGIN,
			HOSTS_END,
			HOSTS
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

// ---------------------------------------------------------------- the CLI's resource patcher must match rsce.ts

describe('CLI resource patcher (kinframe-convert.sh --patch-resource)', () => {
	const script = new URL('../../../../tools/convert/kinframe-convert.sh', import.meta.url).pathname;
	const bmp = (extra: number, width = 800) => {
		const b = new Uint8Array(54 + extra).fill(0x5a);
		const v = new DataView(b.buffer);
		b.set([0x42, 0x4d]);
		v.setUint32(14, 40, true);
		v.setInt32(18, width, true);
		v.setInt32(22, 1280, true);
		v.setUint16(28, 8, true);
		v.setUint32(30, 1, true);
		return b;
	};
	const dtb = Uint8Array.from({ length: 3000 }, (_, i) => (i * 13) & 0xff);
	const image = buildResource(
		[
			{ name: 'rk-kernel.dtb', data: dtb },
			{ name: 'logo.bmp', data: bmp(2000) },
			{ name: 'logo_kernel.bmp', data: bmp(2000) }
		],
		64 * 1024
	);

	function cli(args: string[], files: Record<string, Uint8Array>): { out?: Uint8Array; error?: string } {
		const dir = mkdtempSync(join(tmpdir(), 'kinframe-rsce-'));
		try {
			for (const [n, d] of Object.entries(files)) writeFileSync(join(dir, n), d);
			execFileSync('sh', [script, ...args.map((a) => (a in files || a === 'out.img' ? join(dir, a) : a))], { cwd: dir, stdio: 'pipe', env: { ...process.env, TMPDIR: dir } });
			return { out: args.includes('out.img') ? new Uint8Array(readFileSync(join(dir, 'out.img'))) : undefined };
		} catch (e) {
			return { error: String((e as { stderr?: Buffer }).stderr ?? e) };
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	}

	it('produces the same bytes as brandResource()', () => {
		for (const logo of [bmp(2000), bmp(700), bmp(5000)]) {
			const r = cli(['--patch-resource', 'in.img', 'logo.bmp', 'out.img'], { 'in.img': image, 'logo.bmp': logo });
			expect(r.error).toBeUndefined();
			expect(Buffer.compare(Buffer.from(r.out!), Buffer.from(brandResource(image, logo)))).toBe(0);
		}
	});

	it('refuses what brandResource() refuses', () => {
		expect(cli(['--patch-resource', 'in.img', 'logo.bmp', 'out.img'], { 'in.img': image, 'logo.bmp': bmp(100, 1280) }).error).toMatch(/logo.bmp on the frame is/);
		const noDtb = buildResource([{ name: 'logo.bmp', data: bmp(100) }], 16 * 1024);
		expect(cli(['--patch-resource', 'in.img', 'logo.bmp', 'out.img'], { 'in.img': noDtb, 'logo.bmp': bmp(100) }).error).toMatch(/device tree/);
		expect(cli(['--check-resource', 'in.img'], { 'in.img': noDtb }).error).toMatch(/device tree/);
		expect(cli(['--check-resource', 'in.img'], { 'in.img': new Uint8Array(4096) }).error).toMatch(/RSCE/);
		expect(cli(['--check-resource', 'in.img'], { 'in.img': image }).error).toBeUndefined();
	});
});
