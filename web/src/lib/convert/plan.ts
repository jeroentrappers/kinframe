// The convert tool's shared plan (issue #1, tools/convert/README.md): what turns a stock Frameo frame into a
// kinframe frame and back. The website (/convert, over WebUSB) and the CLI (tools/convert/kinframe-convert.sh, over
// platform-tools adb) run the same steps with the same commands. The constants and command templates below are the
// source of truth; the CLI repeats them in its "shared plan" block, and plan.test.ts fails when the two drift.
//
// Pure functions only (no browser APIs), so the planner, device identification and verification are unit-tested.

/** The kinframe frame app. */
export const FRAME_PACKAGE = 'app.kinframe.frame';
/** Its launcher/home activity; also the provisioning entry point (MainActivity.handleProvisioning). */
export const FRAME_ACTIVITY = 'app.kinframe.frame/.MainActivity';
/** SHA-256 of the release signing certificate (apksigner "certificate SHA-256 digest"), 99:C5:E3:…:20:5C. */
export const RELEASE_CERT_SHA256 = '99c5e36c1a7fd3740caa5115d9e303c982a18555f7bd29336b55e4481a2a205c';
/** The frame app's built-in server (android/frame/build.gradle.kts DEFAULT_SERVER). */
export const DEFAULT_SERVER = 'https://kinframe.appmire.be';
/** GitHub repository whose releases carry the signed frame APK (.github/workflows/release-frame.yml). */
export const RELEASES_REPO = 'jeroentrappers/kinframe';
/** The stock photo frame app. */
export const FRAMEO_PACKAGE = 'net.frameo.frame';
/**
 * Disabled with `pm disable-user` (as root) when converting and enabled again when reverting: the stock app and the
 * firmware updaters. Never uninstalled: they are system packages, and disabling is fully reversible.
 */
export const DISABLE_PACKAGES = [FRAMEO_PACKAGE, 'com.adups.fota', 'com.adups.fota.sysoper'] as const;
/** Runtime permissions granted after install (also covered by `pm install -g`; granted again in case it was ignored). */
export const GRANT_PERMISSIONS = [
	'android.permission.ACCESS_COARSE_LOCATION',
	'android.permission.READ_EXTERNAL_STORAGE',
	'android.permission.WRITE_EXTERNAL_STORAGE'
] as const;
/** App-ops allowed after install, each from the API level where it exists. */
export const APPOPS_ALLOW = [
	{ op: 'WRITE_SETTINGS', minSdk: 23 },
	{ op: 'REQUEST_INSTALL_PACKAGES', minSdk: 26 }
] as const;
/** Tested models, as `<ro.product.brand lowercased>/<ro.product.device>` (docs/hardware/supported-devices.md). */
export const SUPPORTED_MODELS = ['denver/PFF_1024'] as const;
/** The frame app's minSdk (Android 6.0). */
export const MIN_SDK = 23;
/** ABIs the frame APK ships native code for. */
export const SUPPORTED_ABIS = ['armeabi-v7a', 'arm64-v8a'] as const;
/** `pm set-home-activity` exists from Android 7.0. On Android 6, kinframe is home because it is the only one left. */
export const SET_HOME_MIN_SDK = 24;
/** `pm install` refuses an older APK: a newer kinframe is already installed, which is fine. */
export const INSTALL_NEWER_PRESENT = 'INSTALL_FAILED_VERSION_DOWNGRADE';
/** Scratch directory on the frame (the adb shell user owns /data/local/tmp). Removed after use. */
export const WORK_DIR = '/data/local/tmp/kinframe-convert';
/** The start-up branding put on the frame (tools/brand/boot): kinframe now; a retail brand can be added later. */
export const BOOT_BRAND = 'kinframe';
/** Where a kinframe server serves the start-up branding: `<server><BOOT_ASSETS>/<brand>/{logo.bmp,bootanimation.zip}`. */
export const BOOT_ASSETS = '/frame-boot';
/** The Android boot animation (Android 6 has no /oem copy). */
export const BOOT_ANIMATION = '/system/media/bootanimation.zip';
/** Backup file names for the start-up branding: the whole resource partition and the stock boot animation. */
export const RESOURCE_BACKUP = 'resource.img';
export const BOOT_ANIMATION_BACKUP = 'bootanimation.zip';

/**
 * Every shell command the tools run on the frame, as templates. `{name}` placeholders are filled by `fill()`; the
 * CLI uses the same text with `$name` variables. `su 0 <command>` with plain arguments only: the su on Rockchip
 * frame firmware re-parses its arguments, so no quoting, redirects or pipes behind it.
 */
export const COMMANDS = {
	// read-only
	getprop: 'getprop',
	rootCheck: 'su 0 id',
	packagePath: 'pm path {pkg}',
	packageInfo: 'dumpsys package {pkg}',
	bootCompleted: 'getprop sys.boot_completed',
	activities: 'dumpsys activity activities',
	// modifying (skipped in a dry run)
	mkWork: 'mkdir -p {work}',
	backupData: 'su 0 tar -czf {work}/{pkg}-data.tgz -C /data/data {pkg}',
	backupReadable: 'su 0 chmod 644 {work}/{pkg}-data.tgz',
	install: 'pm install -r -g {work}/kinframe.apk',
	grant: 'pm grant {frame} {perm}',
	appop: 'appops set {frame} {op} allow',
	disable: 'su 0 pm disable-user {pkg}',
	setHome: 'pm set-home-activity {activity}',
	cleanup: 'rm -rf {work}',
	provision: 'am start -n {activity} --es server_url {server}',
	provisionLanguage: 'am start -n {activity} --es server_url {server} --es language {language}',
	restoreFrameo: 'pm install -r -d {work}/restore.apk',
	enable: 'su 0 pm enable {pkg}',
	uninstall: 'pm uninstall {frame}',
	uninstallKeepData: 'pm uninstall -k {frame}',
	uninstallRoot: 'su 0 pm uninstall {frame}',
	// start-up branding (boot logo in the resource partition, boot animation in /system)
	findResource: 'ls /dev/block/platform/*/by-name/resource',
	mounts: 'mount',
	readResource: 'su 0 dd if={resource} of={work}/{file} bs=1048576',
	readable: 'su 0 chmod 644 {work}/{file}',
	writeResource: 'su 0 dd if={work}/{file} of={resource} bs=1048576',
	sync: 'su 0 sync',
	flushResource: 'su 0 blockdev --flushbufs {resource}',
	removeFile: 'su 0 rm -f {work}/{file}',
	remountRw: 'su 0 mount -o remount,rw {system} /system',
	copyBootAnimation: 'su 0 cp {work}/bootanimation.zip /system/media/bootanimation.zip',
	chmodBootAnimation: 'su 0 chmod 644 /system/media/bootanimation.zip',
	remountRo: 'su 0 mount -o remount,ro {system} /system',
	sha1BootAnimation: 'sha1sum /system/media/bootanimation.zip'
} as const;

export type CommandName = keyof typeof COMMANDS;

/** Fills a command template. Unknown placeholders are an error (a planner bug), never sent to the frame. */
export function fill(name: CommandName, vars: Record<string, string> = {}): string {
	const all: Record<string, string> = { work: WORK_DIR, frame: FRAME_PACKAGE, activity: FRAME_ACTIVITY, ...vars };
	return COMMANDS[name].replace(/\{(\w+)\}/g, (_, k: string) => {
		const v = all[k];
		if (v === undefined) throw new Error(`command ${name}: missing {${k}}`);
		if (!SAFE_ARG.test(v)) throw new Error(`command ${name}: unsafe value for {${k}}`);
		return v;
	});
}

/** Values interpolated into shell commands: no spaces, quotes or shell metacharacters. */
const SAFE_ARG = /^[A-Za-z0-9._:/@+=,~-]+$/;

// ---------------------------------------------------------------- server URL

/**
 * Normalizes the server address entered for a self-hosted server: `https://` is added when missing, a trailing
 * slash is dropped. Returns null for anything that is not a plain http(s) origin plus optional path, so the value
 * is always safe to pass on the frame's command line.
 */
export function normalizeServerUrl(input: string): string | null {
	let s = input.trim();
	if (!s) return null;
	if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = `https://${s}`;
	let u: URL;
	try {
		u = new URL(s);
	} catch {
		return null;
	}
	if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
	if (u.username || u.password || u.search || u.hash || !u.hostname) return null;
	const out = `${u.protocol}//${u.host}${u.pathname}`.replace(/\/+$/, '');
	return SAFE_ARG.test(out) ? out : null;
}

// ---------------------------------------------------------------- device identification

export type EnabledState = 'enabled' | 'disabled' | 'missing';

export interface PackageState {
	installed: boolean;
	enabled: EnabledState;
	versionName?: string;
	versionCode?: number;
	/** APK paths from `pm path` (the base APK plus any splits). */
	paths: string[];
}

export interface DeviceFacts {
	brand: string;
	manufacturer: string;
	model: string;
	device: string;
	platform: string;
	sdk: number;
	release: string;
	abis: string[];
	fingerprint: string;
	serial: string;
	root: boolean;
	packages: Record<string, PackageState>;
	/** The resource partition (boot logos) by name, or empty when the frame has none. */
	resource: string;
	/** The block device /system is mounted from (empty when unknown). */
	system: string;
}

/** Why a frame is not (or not known to be) supported. */
export type Issue = 'sdk' | 'abi' | 'root' | 'model';

export interface Identification {
	facts: DeviceFacts;
	/** `supported`: tested model. `untested`: meets the requirements but needs `--force`. `unsupported`: can't work. */
	verdict: 'supported' | 'untested' | 'unsupported';
	issues: Issue[];
	modelKey: string;
}

/** Parses `getprop` output (`[key]: [value]` lines). */
export function parseGetprop(out: string): Record<string, string> {
	const props: Record<string, string> = {};
	for (const line of out.split(/\r?\n/)) {
		const m = /^\[([^\]]+)\]: \[(.*)\]\s*$/.exec(line);
		if (m) props[m[1]!] = m[2]!;
	}
	return props;
}

/** Parses `pm path` output into APK paths. */
export function parsePmPath(out: string): string[] {
	return out
		.split(/\r?\n/)
		.map((l) => /^package:(\S+)/.exec(l.trim())?.[1])
		.filter((p): p is string => Boolean(p));
}

/**
 * Parses `dumpsys package <pkg>`. An updated system app is listed twice (the update first, then the system
 * copy); the first entry is the one that runs. `enabled=` is the user-0 state: 0 default, 1 enabled, 2 disabled,
 * 3 disabled-user, 4 disabled-until-used.
 */
export function parseDumpsysPackage(out: string): { versionName?: string; versionCode?: number; enabled: EnabledState } {
	const versionName = /versionName=(\S+)/.exec(out)?.[1];
	const code = /versionCode=(\d+)/.exec(out)?.[1];
	const enabled = /User 0:.*?\benabled=(\d)/.exec(out)?.[1];
	return {
		versionName,
		versionCode: code ? Number(code) : undefined,
		enabled: enabled === undefined ? 'missing' : ['2', '3', '4'].includes(enabled) ? 'disabled' : 'enabled'
	};
}

/** The package state from `pm path` and `dumpsys package` output. */
export function packageState(pmPath: string, dumpsys: string): PackageState {
	const paths = parsePmPath(pmPath);
	if (!paths.length) return { installed: false, enabled: 'missing', paths };
	const info = parseDumpsysPackage(dumpsys);
	return { installed: true, paths, versionName: info.versionName, versionCode: info.versionCode, enabled: info.enabled === 'missing' ? 'enabled' : info.enabled };
}

/** The resource partition from the `findResource` listing (`/dev/block/platform/<controller>/by-name/resource`), or empty. */
export function parseResourcePath(out: string): string {
	return out.split(/\r?\n/).map((l) => l.trim()).find((l) => /^\/dev\/block\/platform\/[^/\s]+\/by-name\/resource$/.test(l)) ?? '';
}

/** The block device /system is mounted from, from `mount` output (`<dev> /system ext4 ro,…` or `<dev> on /system type …`). */
export function parseSystemDevice(out: string): string {
	for (const line of out.split(/\r?\n/)) {
		const m = /^(\S+)\s+(?:on\s+)?\/system\s/.exec(line.trim());
		if (m) return m[1]!;
	}
	return '';
}

/** Device facts from getprop output, the root check, package states and (optionally) the boot partition probes. */
export function deviceFacts(
	getprop: string,
	rootCheck: string,
	packages: Record<string, PackageState>,
	boot: { findResource?: string; mounts?: string } = {}
): DeviceFacts {
	const p = parseGetprop(getprop);
	const abilist = (p['ro.product.cpu.abilist'] ?? '').split(',').filter(Boolean);
	const abis = abilist.length ? abilist : [p['ro.product.cpu.abi'], p['ro.product.cpu.abi2']].filter((a): a is string => Boolean(a));
	return {
		brand: p['ro.product.brand'] ?? '',
		manufacturer: p['ro.product.manufacturer'] ?? '',
		model: p['ro.product.model'] ?? '',
		device: p['ro.product.device'] ?? '',
		platform: p['ro.board.platform'] ?? '',
		sdk: Number(p['ro.build.version.sdk'] ?? 0) || 0,
		release: p['ro.build.version.release'] ?? '',
		abis,
		fingerprint: p['ro.build.fingerprint'] ?? '',
		serial: p['ro.serialno'] ?? '',
		root: /uid=0\b/.test(rootCheck),
		packages,
		resource: parseResourcePath(boot.findResource ?? ''),
		system: parseSystemDevice(boot.mounts ?? '')
	};
}

export function modelKey(f: Pick<DeviceFacts, 'brand' | 'device'>): string {
	return `${f.brand.toLowerCase()}/${f.device}`;
}

/** Classifies a frame: tested model, untested but plausible, or impossible. */
export function identify(facts: DeviceFacts): Identification {
	const issues: Issue[] = [];
	if (facts.sdk < MIN_SDK) issues.push('sdk');
	if (!facts.abis.some((a) => (SUPPORTED_ABIS as readonly string[]).includes(a))) issues.push('abi');
	if (!facts.root) issues.push('root');
	const key = modelKey(facts);
	const known = (SUPPORTED_MODELS as readonly string[]).includes(key);
	if (!known) issues.push('model');
	const hard = issues.some((i) => i !== 'model');
	return { facts, verdict: hard ? 'unsupported' : known ? 'supported' : 'untested', issues, modelKey: key };
}

/** Whether converting may start: supported, or untested and the person chose to continue anyway. */
export function canConvert(id: Identification, force: boolean): boolean {
	return id.verdict === 'supported' || (id.verdict === 'untested' && force);
}

/**
 * Whether the boot logo can be replaced: root and a resource partition. Whether the boot animation can be: root and a
 * plain (not dm-verity) /system, which may be remounted writable.
 */
export function bootLogoPossible(f: DeviceFacts): boolean {
	return f.root && f.resource !== '';
}
export function bootAnimationPossible(f: DeviceFacts): boolean {
	return f.root && /^\/dev\/block\//.test(f.system) && !/\/dm-\d+$/.test(f.system);
}

/** Reverting needs root (to enable the disabled packages) and nothing else: it must work on any half-converted frame. */
export function canRevert(id: Identification): boolean {
	return id.facts.root;
}

// ---------------------------------------------------------------- plans

/** One thing the runner does. `modifies` actions are only reported in a dry run. */
export type Action =
	| {
			kind: 'shell';
			cmd: string;
			modifies: boolean;
			/** Output that means success (any of them). Without it, error-looking output fails the action. */
			ok?: string[];
			/** A failure is a warning, not an error. */
			optional?: boolean;
			/** Tried when the output has none of `ok`. */
			fallback?: string;
	  }
	| { kind: 'pull'; remote: string; name: string }
	| { kind: 'push'; remote: string; source: PushSource }
	| { kind: 'fetch' }
	/** Downloads the start-up branding (boot logo, boot animation) and checks its format. */
	| { kind: 'fetchBoot' }
	/** Builds the branded resource image from the backed-up one (`RESOURCE_BACKUP`) and the downloaded logo. */
	| { kind: 'brandResource' }
	/**
	 * Writes the resource image (the branded one, or the backup chosen when reverting) to the resource partition:
	 * refuses one that isn't a valid resource image with a device tree or doesn't match the partition size, reads the
	 * partition first, writes, reads it back and compares sha256. On a mismatch it writes the previous content back.
	 */
	| { kind: 'flashResource'; resource: string }
	/** Checks the boot animation on the frame is the one pushed (sha1sum: the frame has no sha256sum). */
	| { kind: 'verifyBootAnimation' }
	| { kind: 'reboot' }
	| { kind: 'waitBoot' }
	| { kind: 'checkHome'; expect: 'kinframe' | 'other' };

/** Data the runner pushes: the kinframe APK, a Frameo APK chosen for revert, or a boot animation. */
export type PushSource = 'release' | 'restore' | 'bootanimation';

export type StepId =
	| 'backup'
	| 'bootBackup'
	| 'download'
	| 'bootLogo'
	| 'bootAnimation'
	| 'install'
	| 'permissions'
	| 'disable'
	| 'home'
	| 'cleanup'
	| 'reboot'
	| 'provision'
	| 'restore'
	| 'enable'
	| 'uninstall';

export interface Step {
	id: StepId;
	actions: Action[];
}

export interface ConvertOptions {
	/** Normalized server URL (DEFAULT_SERVER for the hosted one). */
	server: string;
	/** Frame language code (web locale ids match the frame's), or empty to let the frame ask. */
	language?: string;
	/** Pull the Frameo APK(s) and its data before changing anything. */
	backup: boolean;
	/**
	 * Replace the boot logo (resource partition) and the boot animation with kinframe's, where the frame allows it.
	 * The originals are always backed up first.
	 */
	bootBranding?: boolean;
}

export interface RevertOptions {
	/** Keep kinframe's data (`pm uninstall -k`), so reinstalling picks up the same enrolment. */
	keepData: boolean;
	/** A backed-up Frameo APK is pushed and installed over the current one (if Frameo updated itself meanwhile). */
	restoreFrameo: boolean;
	/** A backed-up resource image (`RESOURCE_BACKUP`) is written back to the resource partition. */
	restoreBootLogo?: boolean;
	/** A backed-up boot animation (`BOOT_ANIMATION_BACKUP`) is copied back to /system. */
	restoreBootAnimation?: boolean;
}

const sh = (cmd: string, extra: Partial<Extract<Action, { kind: 'shell' }>> = {}): Action => ({ kind: 'shell', cmd, modifies: true, ...extra });

/** Backup file name for a pulled APK path: `/data/app/net.frameo.frame-2/base.apk` → `net.frameo.frame-base.apk`. */
export function backupName(pkg: string, path: string): string {
	return `${pkg}-${path.split('/').pop() ?? 'base.apk'}`;
}

/** Copies a partition to the work directory and pulls it as `name` (binary data through su is not clean). */
function readPartition(resource: string, name: string): Action[] {
	return [
		sh(fill('mkWork')),
		sh(fill('readResource', { resource, file: name }), { ok: ['records out'] }),
		sh(fill('readable', { file: name })),
		{ kind: 'pull', remote: `${WORK_DIR}/${name}`, name },
		sh(fill('removeFile', { file: name }), { optional: true })
	];
}

/**
 * Puts the boot animation in place: push, remount /system writable, copy, make it readable, remount read-only, check.
 * The copy may fail without stopping the step, so /system is always remounted read-only; the check then fails it.
 */
function writeBootAnimation(system: string): Action[] {
	return [
		sh(fill('mkWork')),
		{ kind: 'push', remote: `${WORK_DIR}/bootanimation.zip`, source: 'bootanimation' },
		sh(fill('remountRw', { system })),
		sh(fill('copyBootAnimation'), { optional: true }),
		sh(fill('chmodBootAnimation'), { optional: true }),
		sh(fill('remountRo', { system })),
		{ kind: 'verifyBootAnimation' },
		sh(fill('removeFile', { file: 'bootanimation.zip' }), { optional: true })
	];
}

/**
 * The convert plan. Order matters: nothing on the frame changes before the backup and the verified download, and
 * Frameo is only disabled once kinframe is installed. The server URL is sent after the restart, because the frame
 * keeps a provisioned address only until it enrols.
 */
export function planConvert(id: Identification, opts: ConvertOptions): Step[] {
	const f = id.facts;
	const steps: Step[] = [];
	const frameo = f.packages[FRAMEO_PACKAGE];

	if (opts.backup && frameo?.installed) {
		const actions: Action[] = frameo.paths.map((p) => ({ kind: 'pull', remote: p, name: backupName(FRAMEO_PACKAGE, p) }));
		if (f.root) {
			const data = `${WORK_DIR}/${FRAMEO_PACKAGE}-data.tgz`;
			actions.push(
				sh(fill('mkWork')),
				sh(fill('backupData', { pkg: FRAMEO_PACKAGE })),
				sh(fill('backupReadable', { pkg: FRAMEO_PACKAGE })),
				{ kind: 'pull', remote: data, name: `${FRAMEO_PACKAGE}-data.tgz` },
				sh(fill('cleanup'), { optional: true })
			);
		}
		steps.push({ id: 'backup', actions });
	}

	// Start-up branding: back up both originals before anything changes, then write them right after the downloads,
	// before the app changes, so a failure leaves the frame as it was.
	const logo = Boolean(opts.bootBranding) && bootLogoPossible(f);
	const anim = Boolean(opts.bootBranding) && bootAnimationPossible(f);
	if (logo || anim) {
		const actions: Action[] = [];
		if (anim) actions.push({ kind: 'pull', remote: BOOT_ANIMATION, name: BOOT_ANIMATION_BACKUP });
		if (logo) actions.push(...readPartition(f.resource, RESOURCE_BACKUP));
		steps.push({ id: 'bootBackup', actions });
	}
	steps.push({ id: 'download', actions: [{ kind: 'fetch' }, ...(logo || anim ? [{ kind: 'fetchBoot' } as const] : [])] });
	if (logo) steps.push({ id: 'bootLogo', actions: [{ kind: 'brandResource' }, { kind: 'flashResource', resource: f.resource }] });
	if (anim) steps.push({ id: 'bootAnimation', actions: writeBootAnimation(f.system) });
	steps.push({
		id: 'install',
		actions: [sh(fill('mkWork')), { kind: 'push', remote: `${WORK_DIR}/kinframe.apk`, source: 'release' }, sh(fill('install'), { ok: ['Success', INSTALL_NEWER_PRESENT] })]
	});
	steps.push({
		id: 'permissions',
		actions: [
			...GRANT_PERMISSIONS.map((perm) => sh(fill('grant', { perm }), { optional: true })),
			...APPOPS_ALLOW.filter((a) => f.sdk >= a.minSdk).map((a) => sh(fill('appop', { op: a.op }), { optional: true }))
		]
	});
	const toDisable = DISABLE_PACKAGES.filter((p) => f.packages[p]?.installed && f.packages[p]?.enabled !== 'disabled');
	if (toDisable.length) {
		steps.push({ id: 'disable', actions: toDisable.map((pkg) => sh(fill('disable', { pkg }), { ok: ['disabled-user'] })) });
	}
	steps.push({ id: 'home', actions: f.sdk >= SET_HOME_MIN_SDK ? [sh(fill('setHome'), { optional: true })] : [] });
	steps.push({ id: 'cleanup', actions: [sh(fill('cleanup'), { optional: true })] });
	steps.push({ id: 'reboot', actions: [{ kind: 'reboot' }, { kind: 'waitBoot' }] });
	const language = opts.language && SAFE_ARG.test(opts.language) ? opts.language : '';
	steps.push({
		id: 'provision',
		actions: [
			sh(language ? fill('provisionLanguage', { server: opts.server, language }) : fill('provision', { server: opts.server })),
			{ kind: 'checkHome', expect: 'kinframe' }
		]
	});
	return steps;
}

/**
 * The revert plan: every step is independent and best effort, so it also repairs a half-converted frame (kinframe
 * missing, crashing, or Frameo disabled by hand).
 */
export function planRevert(id: Identification, opts: RevertOptions): Step[] {
	const f = id.facts;
	const steps: Step[] = [];
	if (opts.restoreFrameo) {
		steps.push({
			id: 'restore',
			actions: [
				sh(fill('mkWork')),
				{ kind: 'push', remote: `${WORK_DIR}/restore.apk`, source: 'restore' },
				sh(fill('restoreFrameo'), { ok: ['Success'], optional: true }),
				sh(fill('cleanup'), { optional: true })
			]
		});
	}
	if (opts.restoreBootLogo && bootLogoPossible(f)) {
		steps.push({ id: 'bootLogo', actions: [{ kind: 'flashResource', resource: f.resource }] });
	}
	if (opts.restoreBootAnimation && bootAnimationPossible(f)) {
		steps.push({ id: 'bootAnimation', actions: writeBootAnimation(f.system) });
	}
	const toEnable = DISABLE_PACKAGES.filter((p) => f.packages[p]?.installed && f.packages[p]?.enabled === 'disabled');
	if (toEnable.length) {
		steps.push({ id: 'enable', actions: toEnable.map((pkg) => sh(fill('enable', { pkg }), { ok: ['enabled'] })) });
	}
	if (f.packages[FRAME_PACKAGE]?.installed) {
		steps.push({
			id: 'uninstall',
			actions: [sh(fill(opts.keepData ? 'uninstallKeepData' : 'uninstall'), { ok: ['Success'], fallback: fill('uninstallRoot') })]
		});
	}
	steps.push({ id: 'cleanup', actions: [sh(fill('cleanup'), { optional: true })] });
	steps.push({ id: 'reboot', actions: [{ kind: 'reboot' }, { kind: 'waitBoot' }, { kind: 'checkHome', expect: 'other' }] });
	return steps;
}

/** The read-only commands that identify a frame, in order. */
export function identifyCommands(): string[] {
	return [
		fill('getprop'),
		fill('rootCheck'),
		fill('findResource'),
		fill('mounts'),
		...[FRAME_PACKAGE, ...DISABLE_PACKAGES].flatMap((pkg) => [fill('packagePath', { pkg }), fill('packageInfo', { pkg })])
	];
}

/** Whether `dumpsys activity activities` shows kinframe in front. */
export function focusedPackage(dumpsys: string): string | null {
	const m = /(?:mFocusedActivity|mResumedActivity|topResumedActivity)[^\n]*?\s([A-Za-z0-9_.]+)\/[^\s}]+/.exec(dumpsys);
	return m?.[1] ?? null;
}

/**
 * Version code for a release tag, as the release workflow derives it: major·1 000 000 + minor·10 000 + patch·100 + n,
 * with n = 99 for a release and N for -beta.N.
 */
export function versionCodeForTag(tag: string): number | null {
	const m = /^v(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$/.exec(tag);
	if (!m) return null;
	const [major, minor, patch] = [Number(m[1]), Number(m[2]), Number(m[3])];
	const n = m[4] ? Number(m[4]) : 99;
	if (minor > 99 || patch > 99 || n < 1 || n > 99) return null;
	return major * 1_000_000 + minor * 10_000 + patch * 100 + n;
}
