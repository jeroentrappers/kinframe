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
/** The Adups firmware updater: checks the vendor's servers and downloads updates (com.adups.fota.sysoper installs them). */
export const ADUPS_PACKAGE = 'com.adups.fota';
/**
 * Disabled with `pm disable-user` (as root) when converting and enabled again when reverting: the stock app and the
 * firmware updaters (Adups, and Rockchip's RKUpdateService, which offers update.img/update.zip files it finds on a
 * card or USB stick). Never uninstalled: they are system packages, and disabling is fully reversible.
 */
export const DISABLE_PACKAGES = [FRAMEO_PACKAGE, ADUPS_PACKAGE, 'com.adups.fota.sysoper', 'android.rockchip.update.service'] as const;
/**
 * Preinstalled apps and services a kiosk photo frame never uses, disabled when converting if installed and enabled
 * (optional, on by default; the reason for each is in tools/convert/README.md). Never anything kinframe or Android
 * needs (Settings, the package installer, keyboards, providers, WebView, System UI, ...): plan.test.ts checks that.
 */
export const EXTRA_PACKAGES = [
	'com.android.browser',
	'com.android.calculator2',
	'com.android.music',
	'com.android.musicfx',
	'com.android.soundrecorder',
	'com.android.gallery3d',
	'android.rk.RockVideoPlayer',
	'com.android.rk',
	'com.android.rk.mediafloat',
	'com.android.dreams.basic',
	'com.android.dreams.phototable',
	'com.android.galaxy4',
	'com.android.noisefield',
	'com.android.phasebeam',
	'com.android.wallpaper',
	'com.android.wallpaper.holospiral',
	'com.android.wallpaper.livepicker',
	'com.android.wallpapercropper',
	'com.android.htmlviewer',
	'com.android.bluetoothmidiservice',
	'com.android.printspooler',
	'com.android.providers.downloads.ui',
	'com.cghs.stresstest',
	'com.DeviceTest',
	'com.svox.pico'
] as const;
/**
 * Which extra packages a convert disabled, kept on the frame for the revert (one package per line). In the adb shell
 * user's own /data/local/tmp: no app can read or delete it, it is not on the user-visible storage, and it survives
 * restarts and app updates. Only a factory reset removes it, and that enables every package again anyway. A copy goes
 * into the backup on the computer.
 */
export const STATE_FILE = '/data/local/tmp/kinframe-state.txt';
/** The Adups update servers, sent to 0.0.0.0 in /system/etc/hosts inside a block between these marker lines. */
export const ADUPS_HOSTS = ['fota5p.adups.com', 'fota5p.adups.cn', 'fruet.adups.com'] as const;
export const HOSTS_BEGIN = '# >>> kinframe: block the Adups firmware update servers (kinframe revert removes this block)';
export const HOSTS_END = '# <<< kinframe';
export const HOSTS = '/system/etc/hosts';
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
export const SUPPORTED_MODELS = ['denver/PFF_1024', 'denver/PFF-1515'] as const;
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
 * PackageManager saves package states (`pm disable-user`, the home activity) to package-restrictions.xml about 10 s
 * after a change, not at once. A restart within those seconds loses the change (seen on a Denver PFF-1515: the last
 * package disabled was enabled again after the restart), so the tools wait this long, sync, and only then restart.
 */
export const SETTLE_SECONDS = 15;
/**
 * ISRG Root X1, the Let's Encrypt root, in Android's CA store format (PEM followed by its text form), named after its
 * OpenSSL subject_hash_old. Frames before Android 7 with older firmware lack it, so kinframe can't reach a server with
 * a Let's Encrypt certificate; the convert tools add it to /system as a fallback. Served by the website at
 * `<server><ROOT_CERT_ASSETS>/<ROOT_CERT_FILE>` (web/static/frame-certs); the tools only accept the file with this
 * sha256, so no server can plant another certificate. Certificate SHA-256 96:BC:EC:06:…:BD:DF:08:C6.
 */
export const ROOT_CERT_FILE = '6187b673.0';
export const ROOT_CERT = '/system/etc/security/cacerts/6187b673.0';
export const ROOT_CERT_ASSETS = '/frame-certs';
export const ROOT_CERT_SHA256 = 'b3dce5684d06bf72f8d998ee2245c933df91443a7348eef6d57477796f13da14';
/** The root certificate is only added below this API level (the app trusts its own copy from Android 7 on). */
export const ROOT_CERT_BEFORE_SDK = 24;

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
	screenSize: 'wm size',
	checkRootCert: 'ls /system/etc/security/cacerts/6187b673.0',
	listDisabled: 'pm list packages -d',
	listPackages: 'pm list packages',
	readState: 'cat /data/local/tmp/kinframe-state.txt',
	// leftovers of the firmware updater (reported, and cleared before it is disabled)
	listRecovery: 'su 0 ls /cache/recovery',
	readRecoveryCommand: 'su 0 cat /cache/recovery/command',
	readUpdateResult: 'su 0 cat /data/data/com.adups.fota/files/updateResult',
	readAdupsPrefs: 'su 0 cat /data/data/com.adups.fota/shared_prefs/adupsfota.xml',
	listAdupsInternal: 'su 0 ls /data/data/com.adups.fota/files/adupsfota',
	listAdupsExternal:
		'ls -d /storage/*/Android/data/com.adups.fota/files/adupsfota /storage/emulated/0/Android/data/com.adups.fota/files/adupsfota /storage/*/adupsfota /storage/emulated/0/adupsfota',
	checkPath: 'ls -d {path}',
	// modifying (skipped in a dry run)
	mkWork: 'mkdir -p {work}',
	// The app data: copied as root into the work directory (without caches, WebView data, crash reports and the lib
	// symlink), made readable for the adb shell user, pulled as a directory and archived on the computer. Toybox tar
	// on some frames can't store names over 100 bytes at all (Frameo has Firebase files with ~90-byte names), so no
	// archive is made on the frame. The listing keeps the original modes and owners (numeric) for a restore.
	listData: 'su 0 ls -lnRa /data/data/{pkg}',
	copyData: 'su 0 cp -a /data/data/{pkg} {work}/{pkg}',
	pruneData: 'su 0 rm -rf {work}/{pkg}/cache {work}/{pkg}/code_cache {work}/{pkg}/app_webview {work}/{pkg}/lib {work}/{pkg}/files/.crashlytics.v3',
	readableData: 'su 0 chmod -R a+rX {work}/{pkg}',
	removeDataCopy: 'su 0 rm -rf {work}/{pkg}',
	install: 'pm install -r -g {work}/kinframe.apk',
	grant: 'pm grant {frame} {perm}',
	appop: 'appops set {frame} {op} allow',
	disable: 'su 0 pm disable-user {pkg}',
	forceStop: 'am force-stop {pkg}',
	clearData: 'pm clear {pkg}',
	removePath: 'rm -rf {path}',
	removePathRoot: 'su 0 rm -rf {path}',
	removeRecoveryCommand: 'su 0 rm -f /cache/recovery/command',
	removeState: 'rm -f /data/local/tmp/kinframe-state.txt',
	settle: 'sleep {seconds}',
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
	cleanReboot: 'su 0 svc power reboot',
	flushResource: 'su 0 blockdev --flushbufs {resource}',
	removeFile: 'su 0 rm -f {work}/{file}',
	remountRw: 'su 0 mount -o remount,rw {system} /system',
	copyBootAnimation: 'su 0 cp {work}/bootanimation.zip /system/media/bootanimation.zip',
	chmodBootAnimation: 'su 0 chmod 644 /system/media/bootanimation.zip',
	remountRo: 'su 0 mount -o remount,ro {system} /system',
	sha1BootAnimation: 'sha1sum /system/media/bootanimation.zip',
	// the Let's Encrypt root in /system (same remount pattern as the boot animation)
	copyRootCert: 'su 0 cp {work}/6187b673.0 /system/etc/security/cacerts/6187b673.0',
	chmodRootCert: 'su 0 chmod 644 /system/etc/security/cacerts/6187b673.0',
	chownRootCert: 'su 0 chown 0:0 /system/etc/security/cacerts/6187b673.0',
	sha1RootCert: 'sha1sum /system/etc/security/cacerts/6187b673.0',
	// /system/etc/hosts, built on the computer and copied in (same remount pattern)
	copyHosts: 'su 0 cp {work}/hosts /system/etc/hosts',
	chmodHosts: 'su 0 chmod 644 /system/etc/hosts',
	chownHosts: 'su 0 chown 0:0 /system/etc/hosts',
	sha1Hosts: 'sha1sum /system/etc/hosts'
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
	/** The screen as Android draws it, `<width>x<height>` from `wm size` (empty when unknown). */
	screen: string;
	/** How the panel is mounted (`ro.sf.hwrotation`, 0 when unset): the boot logo is stored turned back by it. */
	hwrotation: number;
	/** Whether the Let's Encrypt root (`ROOT_CERT`) is in the system CA store; `unknown` when the check was unclear. */
	rootCert: 'present' | 'missing' | 'unknown';
	/** `ro.build.display.id` and `ro.fota.version` (the firmware as the updater reports it). */
	displayId: string;
	fotaVersion: string;
	/** Every installed package (`pm list packages`) and the disabled ones (`pm list packages -d`); empty when unknown. */
	installed: string[];
	disabled: string[];
	/** The extra packages an earlier convert disabled (`STATE_FILE`), or null when there is no record. */
	extrasState: string[] | null;
	/** What the firmware updater left behind. */
	updater: UpdaterLeftovers;
}

/** Leftovers of the Adups updater: files in /cache/recovery, its result file, its saved state and downloads. */
export interface UpdaterLeftovers {
	/** File names in /cache/recovery. */
	recovery: string[];
	/** The content of /cache/recovery/command (what recovery runs on its next start), or empty. */
	recoveryCommand: string;
	/** /data/data/com.adups.fota/files/updateResult (written by recovery after an update), or empty. */
	updateResult: string;
	/** The updater's state: 0 idle, 1 new version found, 2 downloading, 3 paused, 4 downloaded; null when unknown. */
	status: number | null;
	/** Where it keeps a downloaded update, or empty. */
	packagePath: string;
	/** Its external download folder setting, or empty. */
	downloadDir: string;
	/** Files in its internal download folder (/data/data/com.adups.fota/files/adupsfota). */
	internal: string[];
	/** Its download folders on the mounted volumes (`Android/data/com.adups.fota/files/adupsfota`), removed when converting. */
	downloads: string[];
	/** `adupsfota` folders at the top of a volume (where a user can put an update for it): reported, not removed. */
	otherDownloads: string[];
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

/** The screen size from `wm size` output (`Physical size: 1920x1080`), or empty. */
export function parseScreenSize(out: string): string {
	const m = /Physical size:\s*(\d+)x(\d+)/.exec(out);
	return m ? `${m[1]}x${m[2]}` : '';
}

/** Whether the `checkRootCert` listing shows the certificate, says it is missing, or neither. */
export function parseRootCert(out: string): DeviceFacts['rootCert'] {
	if (out.split(/\r?\n/).some((l) => l.trim() === ROOT_CERT)) return 'present';
	return /No such file/i.test(out) ? 'missing' : 'unknown';
}

/** The packages a `pm list packages` listing names. */
export function parsePackageList(out: string): Set<string> {
	return parseDisabledPackages(out);
}

/** The packages `pm list packages -d` lists (disabled ones). */
export function parseDisabledPackages(out: string): Set<string> {
	return new Set(out.split(/\r?\n/).map((l) => /^package:(\S+)/.exec(l.trim())?.[1]).filter((p): p is string => Boolean(p)));
}

/** Names from an `ls` listing (one per line), without its error lines. */
export function parseNames(out: string): string[] {
	return out
		.split(/\r?\n/)
		.map((l) => l.trim())
		.filter((l) => l && !/^ls:|No such file|Permission denied|not found/i.test(l));
}

/** A file's content from `cat`, or empty when it is missing. */
export function parseFile(out: string): string {
	return /No such file|Permission denied|^cat:/m.test(out) ? '' : out.replace(/\r\n/g, '\n').trim();
}

const unescapeXml = (s: string) =>
	s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

/** The updater state that matters from its shared preferences XML (an Android SharedPreferences file). */
export function parseAdupsPrefs(xml: string): Pick<UpdaterLeftovers, 'status' | 'packagePath' | 'downloadDir'> {
	const str = (name: string) => {
		const m = new RegExp(`<string name="${name}">([^<]*)</string>`).exec(xml);
		return m ? unescapeXml(m[1]!).trim() : '';
	};
	const status = /<(?:int|long) name="ota_update_status" value="(-?\d+)"/.exec(xml)?.[1];
	return { status: status === undefined ? null : Number(status), packagePath: str('update_package_path'), downloadDir: str('CustomDtPath') };
}

/** The packages recorded in `STATE_FILE`, or null when there is none. Only names on EXTRA_PACKAGES count. */
export function parseState(out: string): string[] | null {
	if (!out.trim() || /No such file|^cat:/m.test(out)) return null;
	const extras = EXTRA_PACKAGES as readonly string[];
	return out
		.split(/\r?\n/)
		.map((l) => l.trim())
		.filter((l) => extras.includes(l));
}

/** The probes the identification reads besides getprop and the packages (each the raw output of its command). */
export interface Probes {
	findResource?: string;
	mounts?: string;
	screenSize?: string;
	rootCert?: string;
	listPackages?: string;
	listDisabled?: string;
	readState?: string;
	listRecovery?: string;
	readRecoveryCommand?: string;
	readUpdateResult?: string;
	readAdupsPrefs?: string;
	listAdupsInternal?: string;
	listAdupsExternal?: string;
}

/** The updater's leftovers from the probes. */
export function updaterLeftovers(probes: Probes): UpdaterLeftovers {
	// Only paths that can go on the command line as they are (the script skips the others the same way).
	const external = parseNames(probes.listAdupsExternal ?? '').filter((l) => /^\/storage\//.test(l) && SAFE_ARG.test(l));
	const unique = (a: string[]) => [...new Set(a)];
	return {
		recovery: parseNames(probes.listRecovery ?? ''),
		recoveryCommand: parseFile(probes.readRecoveryCommand ?? ''),
		updateResult: parseFile(probes.readUpdateResult ?? ''),
		...parseAdupsPrefs(probes.readAdupsPrefs ?? ''),
		internal: parseNames(probes.listAdupsInternal ?? ''),
		downloads: unique(external.filter((l) => l.includes('/Android/data/com.adups.fota/'))),
		otherDownloads: unique(external.filter((l) => !l.includes('/Android/data/com.adups.fota/')))
	};
}

/** Whether the updater has an update found, downloading, downloaded or queued for recovery. */
export function updatePending(u: UpdaterLeftovers): boolean {
	return (
		(u.status !== null && u.status >= 1 && u.status <= 4) ||
		u.packagePath !== '' ||
		u.internal.some((n) => /\.zip$/.test(n)) ||
		u.recovery.includes('command')
	);
}

/** A short, plain description of the updater leftovers (for the identification summary). */
export function describeUpdater(u: UpdaterLeftovers): string[] {
	const states = ['idle', 'new version found', 'downloading', 'paused', 'update downloaded'];
	const out: string[] = [];
	out.push(`updater state: ${u.status === null ? 'unknown' : (states[u.status] ?? `state ${u.status}`)}${u.packagePath ? `, update at ${u.packagePath}` : ''}`);
	out.push(`/cache/recovery: ${u.recovery.length ? u.recovery.join(', ') : 'empty'}${u.recoveryCommand ? ` (command: ${u.recoveryCommand.replace(/\n/g, ' ')})` : ''}`);
	if (u.updateResult) out.push(`last update result: ${u.updateResult.replace(/\n/g, ' ')}`);
	if (u.internal.length) out.push(`internal downloads: ${u.internal.join(', ')}`);
	if (u.downloads.length) out.push(`download folders: ${u.downloads.join(', ')}`);
	if (u.otherDownloads.length) out.push(`update folders on storage: ${u.otherDownloads.join(', ')}`);
	return out;
}

/** Device facts from getprop output, the root check, package states and (optionally) the other probes. */
export function deviceFacts(getprop: string, rootCheck: string, packages: Record<string, PackageState>, boot: Probes = {}): DeviceFacts {
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
		system: parseSystemDevice(boot.mounts ?? ''),
		screen: parseScreenSize(boot.screenSize ?? ''),
		hwrotation: Number(p['ro.sf.hwrotation'] ?? 0) || 0,
		rootCert: boot.rootCert === undefined ? 'unknown' : parseRootCert(boot.rootCert),
		displayId: p['ro.build.display.id'] ?? '',
		fotaVersion: p['ro.fota.version'] ?? '',
		installed: [...parsePackageList(boot.listPackages ?? '')],
		disabled: [...parseDisabledPackages(boot.listDisabled ?? '')],
		extrasState: parseState(boot.readState ?? ''),
		updater: updaterLeftovers(boot)
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

/**
 * Whether the Let's Encrypt root should be added: Android before 7, the certificate is known to be missing, and /system
 * can be changed (root, not dm-verity).
 */
export function rootCertNeeded(f: DeviceFacts): boolean {
	return f.sdk < ROOT_CERT_BEFORE_SDK && f.rootCert === 'missing' && bootAnimationPossible(f);
}

// ---------------------------------------------------------------- start-up branding variants

/** A boot logo the brand offers (tools/brand/boot/build.py): its BMP format and the panel rotation it is made for. */
export interface BootLogoVariant {
	file: string;
	width: number;
	height: number;
	bpp: number;
	compression: number;
	hwrotation: number;
	sha256: string;
}
/** A boot animation the brand offers, for one screen size (`<width>x<height>`). */
export interface BootAnimationVariant {
	file: string;
	screen: string;
	sha256: string;
}
/** `<server><BOOT_ASSETS>/<brand>/manifest.json`. */
export interface BootManifest {
	logos: BootLogoVariant[];
	bootanimations: BootAnimationVariant[];
}

/** The logo with exactly the frame's logo format, made for the frame's panel rotation, if there is one. */
export function pickBootLogo(
	m: BootManifest,
	frameLogo: { width: number; height: number; bpp: number; compression: number },
	hwrotation: number
): BootLogoVariant | undefined {
	return (m.logos ?? []).find(
		(v) => v.width === frameLogo.width && v.height === frameLogo.height && v.bpp === frameLogo.bpp && v.compression === frameLogo.compression && v.hwrotation === hwrotation
	);
}

/** The boot animation made for the frame's screen size (either orientation), if there is one. */
export function pickBootAnimation(m: BootManifest, screen: string): BootAnimationVariant | undefined {
	if (!/^\d+x\d+$/.test(screen)) return undefined;
	const swapped = screen.split('x').reverse().join('x');
	const list = m.bootanimations ?? [];
	return list.find((v) => v.screen === screen) ?? list.find((v) => v.screen === swapped);
}

// ---------------------------------------------------------------- /system/etc/hosts

/** The kinframe block for /system/etc/hosts, as lines. */
export function hostsBlockLines(): string[] {
	return [HOSTS_BEGIN, ...ADUPS_HOSTS.map((h) => `0.0.0.0 ${h}`), HOSTS_END];
}

/**
 * The hosts file without kinframe's block: the lines from HOSTS_BEGIN to HOSTS_END are dropped (an unterminated
 * block ends at the first line that isn't one of ours). Everything else is kept as it was; lines end with a newline.
 * The script's `hosts_edit` does the same (plan.test.ts compares them).
 */
export function removeHostsBlock(text: string): string {
	const lines = text.split('\n');
	if (lines.at(-1) === '') lines.pop();
	const ours = new Set(hostsBlockLines());
	const out: string[] = [];
	let inBlock = false;
	for (const l of lines) {
		if (l === HOSTS_BEGIN) {
			inBlock = true;
			continue;
		}
		if (inBlock) {
			if (l === HOSTS_END) {
				inBlock = false;
				continue;
			}
			if (ours.has(l)) continue;
			inBlock = false;
		}
		out.push(l);
	}
	return out.map((l) => `${l}\n`).join('');
}

/** The hosts file with kinframe's block at the end (once, whatever was there before). */
export function addHostsBlock(text: string): string {
	return removeHostsBlock(text) + hostsBlockLines().map((l) => `${l}\n`).join('');
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
	/**
	 * Downloads the start-up branding and checks it against the manifest's sha256: the logo whose format matches the
	 * frame's own (read from the backed-up resource image) for `hwrotation`, and the animation for `screen`. Refuses
	 * when the brand has none that matches.
	 */
	| { kind: 'fetchBoot'; logo: boolean; animation: boolean; screen: string; hwrotation: number }
	/** Downloads the Let's Encrypt root and checks its sha256 (`ROOT_CERT_SHA256`). A failure is a warning. */
	| { kind: 'fetchRootCert' }
	/**
	 * Backs up the app data of `pkg` as `<pkg>-data.tgz`, built on the computer from a copy pulled as a directory (see
	 * `copyData` in COMMANDS), plus `<pkg>-data-files.txt` (the original modes and owners). A failure is a warning:
	 * converting only turns the app off, its data stays on the frame.
	 */
	| { kind: 'backupData'; pkg: string }
	/**
	 * Checks with `pm list packages -d` that every one of `packages` is disabled. Without `reapply` a package still
	 * enabled fails the step. With it (after the restart), such a package is disabled again, the change is given time
	 * to be saved, and the step warns (or fails when it still doesn't stick).
	 */
	| { kind: 'verifyDisabled'; packages: string[]; reapply: boolean; warnOnly?: boolean }
	/**
	 * Clears what the firmware updater left behind, before it is disabled: removes its download folders on the
	 * volumes (as the shell user, then as root if still there), and a pending /cache/recovery/command. Checks each
	 * removal; what is still there is a warning.
	 */
	| { kind: 'clearUpdater'; downloads: string[]; recoveryCommand: boolean }
	/**
	 * Adds (`block`) or removes (`unblock`) kinframe's block in /system/etc/hosts: pulls the file, builds the new one
	 * here (`addHostsBlock`, `removeHostsBlock`), and only when it changes pushes it and copies it in with the remount
	 * pattern, then compares sha1. The original is saved as a backup file `hosts` when blocking.
	 */
	| { kind: 'editHosts'; mode: 'block' | 'unblock'; system: string }
	/**
	 * Records the extra packages this convert disabled (those of `packages` that `pm list packages -d` now lists),
	 * together with the `previous` record, in STATE_FILE on the frame and as a backup file.
	 */
	| { kind: 'saveState'; packages: string[]; previous: string[] }
	/** A note in the log; with `warn`, the step ends with a warning. */
	| { kind: 'note'; message: string; warn?: boolean }
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
	/** Checks the root certificate on the frame is the one pushed (sha1sum). */
	| { kind: 'verifyRootCert' }
	/**
	 * Restarts the frame. With `clean` (root): Android's own restart (`svc power reboot`), which tells the apps first,
	 * so kinframe saves its clean-shutdown marker; adb's reboot skips that and the frame reports a power loss.
	 */
	| { kind: 'reboot'; clean?: boolean }
	| { kind: 'waitBoot' }
	| { kind: 'checkHome'; expect: 'kinframe' | 'other' };

/** Data the runner pushes: the kinframe APK, a Frameo APK chosen for revert, a boot animation or the root certificate. */
export type PushSource = 'release' | 'restore' | 'bootanimation' | 'rootCert';

export type StepId =
	| 'backup'
	| 'bootBackup'
	| 'download'
	| 'bootLogo'
	| 'bootAnimation'
	| 'rootCert'
	| 'hosts'
	| 'extras'
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
	/** A failure is a warning: the run goes on. */
	optional?: boolean;
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
	/** Add the Let's Encrypt root to /system where it is missing (`rootCertNeeded`). Default on. */
	rootCert?: boolean;
	/** Disable the EXTRA_PACKAGES that are installed and enabled. Default on. */
	extras?: boolean;
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
 * Adds the Let's Encrypt root: push, remount /system writable, copy, mode 644 root:root, remount read-only, check. As
 * with the boot animation, /system is always remounted read-only again.
 */
function writeRootCert(system: string): Action[] {
	return [
		sh(fill('mkWork')),
		{ kind: 'push', remote: `${WORK_DIR}/${ROOT_CERT_FILE}`, source: 'rootCert' },
		sh(fill('remountRw', { system })),
		sh(fill('copyRootCert'), { optional: true }),
		sh(fill('chmodRootCert'), { optional: true }),
		sh(fill('chownRootCert'), { optional: true }),
		sh(fill('remountRo', { system })),
		{ kind: 'verifyRootCert' },
		sh(fill('removeFile', { file: ROOT_CERT_FILE }), { optional: true })
	];
}

/** Gives PackageManager time to save package states, and flushes them to storage, before a restart. */
function settle(): Action[] {
	return [sh(fill('settle', { seconds: String(SETTLE_SECONDS) })), sh(fill('sync'), { optional: true })];
}

/** The EXTRA_PACKAGES that are installed and enabled (what a convert disables). */
export function extrasToDisable(f: DeviceFacts): string[] {
	return EXTRA_PACKAGES.filter((p) => f.installed.includes(p) && !f.disabled.includes(p));
}

/**
 * The extra packages a revert enables: those the convert recorded (STATE_FILE) that are disabled now; without a
 * record, every EXTRA_PACKAGES entry that is disabled. Never anything outside EXTRA_PACKAGES.
 */
export function extrasToEnable(f: DeviceFacts): string[] {
	const recorded = f.extrasState ?? [...EXTRA_PACKAGES];
	return EXTRA_PACKAGES.filter((p) => recorded.includes(p) && f.disabled.includes(p));
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
		if (f.root) actions.push({ kind: 'backupData', pkg: FRAMEO_PACKAGE }, sh(fill('cleanup'), { optional: true }));
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
	const cert = opts.rootCert !== false && rootCertNeeded(f);
	const download: Action[] = [{ kind: 'fetch' }];
	if (logo || anim) download.push({ kind: 'fetchBoot', logo, animation: anim, screen: f.screen, hwrotation: f.hwrotation });
	if (cert) download.push({ kind: 'fetchRootCert' });
	steps.push({ id: 'download', actions: download });
	if (logo) steps.push({ id: 'bootLogo', actions: [{ kind: 'brandResource' }, { kind: 'flashResource', resource: f.resource }] });
	if (anim) steps.push({ id: 'bootAnimation', actions: writeBootAnimation(f.system) });
	if (cert) steps.push({ id: 'rootCert', actions: writeRootCert(f.system), optional: true });
	steps.push({
		id: 'hosts',
		optional: true,
		actions: bootAnimationPossible(f)
			? [{ kind: 'editHosts', mode: 'block', system: f.system }]
			: [{ kind: 'note', warn: true, message: '/system cannot be changed on this frame (no root, or dm-verity): the update servers are not blocked in /system/etc/hosts' }]
	});
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
	const installed = DISABLE_PACKAGES.filter((p) => f.packages[p]?.installed);
	const toDisable = installed.filter((p) => f.packages[p]?.enabled !== 'disabled');
	// First stop the updater and drop what it has queued (its data: a downloaded update and its state; its download
	// folders; a pending recovery command), so nothing is half-way when it is turned off.
	const clear: Action[] = [];
	if (f.packages[ADUPS_PACKAGE]?.installed) {
		clear.push(sh(fill('forceStop', { pkg: ADUPS_PACKAGE }), { optional: true }), sh(fill('clearData', { pkg: ADUPS_PACKAGE }), { ok: ['Success'], optional: true }));
	}
	const u = f.updater;
	const recoveryCommand = u.recovery.includes('command');
	if (u.downloads.length || recoveryCommand) clear.push({ kind: 'clearUpdater', downloads: [...u.downloads], recoveryCommand });
	if (toDisable.length || clear.length) {
		steps.push({
			id: 'disable',
			actions: [
				...clear,
				...toDisable.map((pkg) => sh(fill('disable', { pkg }), { ok: ['disabled-user'] })),
				...(toDisable.length ? [{ kind: 'verifyDisabled', packages: [...installed], reapply: false } satisfies Action] : [])
			]
		});
	}
	const extras = opts.extras === false ? [] : extrasToDisable(f);
	if (extras.length) {
		steps.push({
			id: 'extras',
			optional: true,
			actions: [
				...extras.map((pkg) => sh(fill('disable', { pkg }), { ok: ['disabled-user'], optional: true })),
				{ kind: 'saveState', packages: extras, previous: f.extrasState ?? [] }
			]
		});
	}
	steps.push({ id: 'home', actions: f.sdk >= SET_HOME_MIN_SDK ? [sh(fill('setHome'), { optional: true })] : [] });
	steps.push({ id: 'cleanup', actions: [sh(fill('cleanup'), { optional: true })] });
	// Restart only once the package states are saved, then check they survived (the updaters must stay off).
	const verify: Action[] = installed.length ? [{ kind: 'verifyDisabled', packages: [...installed], reapply: true }] : [];
	if (extras.length) verify.push({ kind: 'verifyDisabled', packages: extras, reapply: true, warnOnly: true });
	steps.push({ id: 'reboot', actions: [...settle(), { kind: 'reboot', clean: f.root }, { kind: 'waitBoot' }, ...verify] });
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
	if (bootAnimationPossible(f)) steps.push({ id: 'hosts', optional: true, actions: [{ kind: 'editHosts', mode: 'unblock', system: f.system }] });
	const toEnable = DISABLE_PACKAGES.filter((p) => f.packages[p]?.installed && f.packages[p]?.enabled === 'disabled');
	const extras = extrasToEnable(f);
	if (toEnable.length || extras.length) {
		steps.push({
			id: 'enable',
			actions: [
				...toEnable.map((pkg) => sh(fill('enable', { pkg }), { ok: ['enabled'] })),
				...extras.map((pkg) => sh(fill('enable', { pkg }), { ok: ['enabled'], optional: true }))
			]
		});
	}
	if (f.packages[FRAME_PACKAGE]?.installed) {
		steps.push({
			id: 'uninstall',
			actions: [sh(fill(opts.keepData ? 'uninstallKeepData' : 'uninstall'), { ok: ['Success'], fallback: fill('uninstallRoot') })]
		});
	}
	steps.push({ id: 'cleanup', actions: [sh(fill('cleanup'), { optional: true }), ...(f.extrasState ? [sh(fill('removeState'), { optional: true })] : [])] });
	steps.push({ id: 'reboot', actions: [...settle(), { kind: 'reboot', clean: f.root }, { kind: 'waitBoot' }, { kind: 'checkHome', expect: 'other' }] });
	return steps;
}

/** The read-only commands that identify a frame, in order. */
export function identifyCommands(): string[] {
	return [
		fill('getprop'),
		fill('rootCheck'),
		fill('findResource'),
		fill('mounts'),
		fill('screenSize'),
		fill('checkRootCert'),
		fill('listPackages'),
		fill('listDisabled'),
		fill('readState'),
		fill('listRecovery'),
		fill('readRecoveryCommand'),
		fill('readUpdateResult'),
		fill('readAdupsPrefs'),
		fill('listAdupsInternal'),
		fill('listAdupsExternal'),
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
