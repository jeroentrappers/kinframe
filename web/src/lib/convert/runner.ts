// Runs a convert/revert plan against a frame. The frame is behind the `FrameShell` interface (WebUSB ADB in the
// browser, a fake in tests), so the order, the dry run and the error handling are tested without a device.

import { sha256Hex } from './apk';
import { gzip, tarArchive, type TarEntry } from './tar';
import { ResourceError, bmpInfo, brandResource, resourceLogoFormat, resourceProblem } from './rsce';
import {
	COMMANDS,
	DISABLE_PACKAGES,
	EXTRA_PACKAGES,
	HOSTS,
	STATE_FILE,
	addHostsBlock,
	describeUpdater,
	removeHostsBlock,
	updatePending,
	RESOURCE_BACKUP,
	ROOT_CERT_SHA256,
	SETTLE_SECONDS,
	WORK_DIR,
	FRAME_PACKAGE,
	deviceFacts,
	fill,
	focusedPackage,
	identify,
	packageState,
	parseDisabledPackages,
	pickBootAnimation,
	pickBootLogo,
	type Action,
	type BootManifest,
	type CommandName,
	type Identification,
	type PackageState,
	type Step,
	type StepId
} from './plan';

/** What the runner needs from a connected frame. */
export interface FrameShell {
	/** Runs a command with `sh -c` as the adb shell user and returns stdout+stderr. */
	shell(cmd: string): Promise<string>;
	push(remote: string, data: Uint8Array): Promise<void>;
	pull(remote: string): Promise<Uint8Array>;
	/** Lists a directory (adb sync LIST): names with mode, size and mtime, `.` and `..` possibly included. */
	list?(remote: string): Promise<RemoteEntry[]>;
	/** Restarts the frame. The connection drops; `reconnect` returns a new one. */
	reboot(): Promise<void>;
}

/** A directory entry on the frame. `mode` holds the file type bits (S_IFDIR, S_IFREG, S_IFLNK) and permissions. */
export interface RemoteEntry {
	name: string;
	mode: number;
	size: number;
	mtime: number;
}

export type StepStatus = 'pending' | 'running' | 'done' | 'warn' | 'failed' | 'skipped' | 'dry';

export interface RunHooks {
	dryRun: boolean;
	/** One line of the support log. */
	log(line: string): void;
	step(id: StepId, status: StepStatus, detail?: string): void;
	/** A backup file was pulled from the frame. */
	file(name: string, data: Uint8Array): void;
	/** Downloads and verifies the release APK. Throws with a readable message when it can't. */
	fetchRelease(): Promise<Uint8Array>;
	/** The backed-up Frameo APK the person chose (revert). */
	restoreApk?(): Uint8Array | undefined;
	/**
	 * The start-up branding on offer (convert with boot branding): the brand's manifest and a way to download one of
	 * its files. The runner picks the variants for the frame and checks their sha256. Throws with a readable message
	 * when the manifest can't be had.
	 */
	fetchBoot?(): Promise<BootSource>;
	/** Downloads the Let's Encrypt root certificate file; the runner checks its sha256. */
	fetchRootCert?(): Promise<Uint8Array>;
	/** The backed-up resource image and boot animation the person chose (revert). */
	restoreBoot?(): { resource?: Uint8Array; bootanimation?: Uint8Array };
	/** Waits for the frame to come back after a restart and returns the new connection. */
	reconnect(): Promise<FrameShell>;
}

/** Where the start-up branding comes from (tools/brand/boot): its manifest, and its files by name. */
export interface BootSource {
	manifest: BootManifest;
	get(file: string): Promise<Uint8Array>;
}

/** The start-up branding picked for a frame. */
export interface BootAssets {
	logo?: Uint8Array;
	bootanimation?: Uint8Array;
}

/** Checks downloaded start-up branding: a BMP logo and a zip. Returns the problem, or null. */
export function bootAssetsProblem(a: BootAssets): string | null {
	if (a.logo && !bmpInfo(a.logo)) return 'the boot logo is not a BMP file';
	const z = a.bootanimation;
	if (z && !(z[0] === 0x50 && z[1] === 0x4b && z[2] === 3 && z[3] === 4)) return 'the boot animation is not a zip file';
	return null;
}

export class StepError extends Error {
	constructor(
		message: string,
		readonly step: StepId
	) {
		super(message);
	}
}

const READ_ONLY_PREFIXES = [
	COMMANDS.getprop,
	COMMANDS.rootCheck,
	COMMANDS.findResource,
	COMMANDS.mounts,
	COMMANDS.screenSize,
	COMMANDS.checkRootCert,
	COMMANDS.listDisabled,
	COMMANDS.sha1BootAnimation,
	COMMANDS.sha1RootCert,
	COMMANDS.sha1Hosts,
	COMMANDS.listPackages,
	COMMANDS.readState,
	COMMANDS.listRecovery,
	COMMANDS.readRecoveryCommand,
	COMMANDS.readUpdateResult,
	COMMANDS.readAdupsPrefs,
	COMMANDS.listAdupsInternal,
	COMMANDS.listAdupsExternal,
	'ls -d ',
	'pm path ',
	'dumpsys '
];

/** Whether a command only reads (allowed in a dry run). */
export function isReadOnly(cmd: string): boolean {
	return READ_ONLY_PREFIXES.some((p) => cmd === p || cmd.startsWith(p));
}

async function run(frame: FrameShell, cmd: string, hooks: Pick<RunHooks, 'log'>): Promise<string> {
	hooks.log(`$ ${cmd}`);
	const out = (await frame.shell(cmd)).replace(/\r\n/g, '\n').trimEnd();
	if (out) hooks.log(out.length > 2000 ? `${out.slice(0, 2000)}\n… (${out.length} characters)` : out);
	return out;
}

/** Runs the read-only identification commands and classifies the frame. */
export async function identifyFrame(frame: FrameShell, hooks: Pick<RunHooks, 'log'>): Promise<Identification> {
	const getprop = await run(frame, fill('getprop'), { log: () => {} });
	hooks.log(`$ ${fill('getprop')}\n(${getprop.split('\n').length} properties)`);
	const root = await run(frame, fill('rootCheck'), hooks);
	const findResource = await run(frame, fill('findResource'), hooks);
	const mounts = await run(frame, fill('mounts'), { log: () => {} });
	hooks.log(`$ ${fill('mounts')}\n${mounts.split('\n').filter((l) => /\s\/system\s/.test(l)).join('\n') || '(no /system line)'}`);
	const screenSize = await run(frame, fill('screenSize'), hooks);
	const rootCert = await run(frame, fill('checkRootCert'), hooks);
	const quiet = async (name: CommandName) => {
		const out = await frame.shell(fill(name));
		hooks.log(`$ ${fill(name)}\n(${out.trim() ? `${out.trim().split('\n').length} lines` : 'nothing'})`);
		return out;
	};
	// Package lists, the extras record, and the updater's leftovers (summarised below; its preferences hold a
	// device id, so they are not logged as they are).
	const listPackages = await quiet('listPackages');
	const listDisabled = await run(frame, fill('listDisabled'), hooks);
	const readState = await run(frame, fill('readState'), hooks);
	const listRecovery = await run(frame, fill('listRecovery'), hooks);
	const readRecoveryCommand = await run(frame, fill('readRecoveryCommand'), hooks);
	const readUpdateResult = await run(frame, fill('readUpdateResult'), hooks);
	const readAdupsPrefs = await quiet('readAdupsPrefs');
	const listAdupsInternal = await run(frame, fill('listAdupsInternal'), hooks);
	const listAdupsExternal = await run(frame, fill('listAdupsExternal'), hooks);
	const packages: Record<string, PackageState> = {};
	for (const pkg of [FRAME_PACKAGE, ...DISABLE_PACKAGES]) {
		const path = await run(frame, fill('packagePath', { pkg }), hooks);
		let info = '';
		if (/^package:/m.test(path)) {
			info = await frame.shell(fill('packageInfo', { pkg }));
			hooks.log(`$ ${fill('packageInfo', { pkg })}\n(${info.length} characters)`);
		}
		packages[pkg] = packageState(path, info);
	}
	const id = identify(
		deviceFacts(getprop, root, packages, {
			findResource,
			mounts,
			screenSize,
			rootCert,
			listPackages,
			listDisabled,
			readState,
			listRecovery,
			readRecoveryCommand,
			readUpdateResult,
			readAdupsPrefs,
			listAdupsInternal,
			listAdupsExternal
		})
	);
	hooks.log(describe(id));
	return id;
}

/** A short, support-friendly summary of an identification. */
export function describe(id: Identification): string {
	const f = id.facts;
	const where = (s: PackageState) => (s.paths.some((x) => x.startsWith('/system/')) ? 'system copy' : 'updated, in /data/app');
	const pkgs = Object.entries(f.packages)
		.map(([p, s]) => `  ${p}: ${s.installed ? `${s.versionName ?? '?'} (${s.versionCode ?? '?'}), ${s.enabled}, ${where(s)}` : 'not installed'}`)
		.join('\n');
	const extras = EXTRA_PACKAGES.filter((p) => f.installed.includes(p));
	return [
		`device: ${f.brand} ${f.model} (${id.modelKey}), manufacturer ${f.manufacturer}, platform ${f.platform}`,
		`android: ${f.release} (API ${f.sdk}), abis ${f.abis.join(',') || '?'}, root ${f.root ? 'yes' : 'no'}`,
		`boot: resource ${f.resource || 'none'}, /system on ${f.system || '?'}, screen ${f.screen || '?'}, hwrotation ${f.hwrotation}`,
		`Let's Encrypt root in the system CA store: ${f.rootCert}`,
		`fingerprint: ${f.fingerprint}`,
		`firmware: ${f.displayId || '?'}, updater version ${f.fotaVersion || '?'}`,
		...describeUpdater(f.updater),
		...(updatePending(f.updater) ? ['WARNING: the firmware updater has a pending or downloaded update; converting clears it and turns the updater off'] : []),
		`verdict: ${id.verdict}${id.issues.length ? ` (${id.issues.join(', ')})` : ''}`,
		'packages:',
		pkgs,
		`extra packages installed: ${extras.length ? extras.map((p) => (f.disabled.includes(p) ? `${p} (disabled)` : p)).join(', ') : 'none'}`,
		`extras record (${STATE_FILE}): ${f.extrasState === null ? 'none' : f.extrasState.join(', ') || 'empty'}`
	].join('\n');
}

function describeAction(a: Action): string {
	switch (a.kind) {
		case 'shell':
			return a.cmd;
		case 'pull':
			return `pull ${a.remote} → ${a.name}`;
		case 'push':
			return `push ${PUSH_NAMES[a.source]} → ${a.remote}`;
		case 'fetch':
			return 'download and verify the kinframe release APK';
		case 'fetchBoot':
			return `download the ${[a.logo && "boot logo matching the frame's own", a.animation && `boot animation for ${a.screen || 'the screen'}`].filter(Boolean).join(' and ')}`;
		case 'fetchRootCert':
			return "download and verify the Let's Encrypt root certificate";
		case 'backupData':
			return `${fill('listData', { pkg: a.pkg })}, ${fill('copyData', { pkg: a.pkg })}, ${fill('pruneData', { pkg: a.pkg })}, ${fill('readableData', { pkg: a.pkg })}, pull the copy and archive it as ${a.pkg}-data.tgz, ${fill('removeDataCopy', { pkg: a.pkg })}`;
		case 'verifyDisabled':
			return `check with ${fill('listDisabled')} that ${a.packages.join(', ')} ${a.reapply ? 'stayed' : 'are'} disabled`;
		case 'verifyRootCert':
			return `check ${fill('sha1RootCert')}`;
		case 'clearUpdater':
			return [...a.downloads.map((path) => fill('removePath', { path })), ...(a.recoveryCommand ? [fill('removeRecoveryCommand')] : [])].join(', ') + ' (checked)';
		case 'editHosts':
			return a.mode === 'block'
				? `add the kinframe block to ${HOSTS} (built here from the pulled file, copied in with remount rw / ro, sha1 checked)`
				: `remove the kinframe block from ${HOSTS}`;
		case 'saveState':
			return `record the disabled extra packages in ${STATE_FILE}`;
		case 'note':
			return a.message;
		case 'brandResource':
			return `put the boot logo into a copy of ${RESOURCE_BACKUP}`;
		case 'flashResource':
			return `write the resource image to ${a.resource}, read it back and compare (write the original back on a mismatch)`;
		case 'verifyBootAnimation':
			return `check ${fill('sha1BootAnimation')}`;
		case 'reboot':
			return 'reboot';
		case 'waitBoot':
			return 'wait for the frame to restart';
		case 'checkHome':
			return `check that ${a.expect === 'kinframe' ? 'kinframe' : 'the stock app'} is in front`;
	}
}

/**
 * Runs the steps in order. A failed required action stops the run (convert) or marks the step failed and goes
 * on (when `continueOnError`, used for revert). In a dry run, only reading actions and the download run; every
 * other action is logged as "would run".
 */
export async function runPlan(
	start: FrameShell,
	steps: Step[],
	hooks: RunHooks,
	opts: { continueOnError: boolean }
): Promise<{ ok: boolean; failed: StepId[] }> {
	let frame = start;
	let release: Uint8Array | undefined;
	// Files the steps hand to each other: pulled backups by name, plus 'logo', 'bootanimation' and 'resource'.
	const blobs = new Map<string, Uint8Array>();
	const restore = hooks.restoreBoot?.() ?? {};
	if (restore.resource) blobs.set('resource', restore.resource);
	if (restore.bootanimation) blobs.set('bootanimation', restore.bootanimation);
	const failed: StepId[] = [];
	let stopped = false;

	for (const step of steps) {
		if (stopped) {
			hooks.step(step.id, 'skipped');
			continue;
		}
		hooks.step(step.id, 'running');
		hooks.log(`\n## ${step.id}`);
		let warned = false;
		let warning: string | undefined;
		const warn = (msg: string) => {
			warned = true;
			warning = warning ? `${warning} ${msg}` : msg;
			hooks.log(`warning: ${msg}`);
		};
		let dry = false;
		try {
			if (!step.actions.length) hooks.log('(nothing to do on this frame)');
			for (const a of step.actions) {
				const modifies = a.kind === 'shell' ? a.modifies : !['pull', 'fetch', 'fetchBoot', 'fetchRootCert', 'checkHome', 'note'].includes(a.kind);
				const skipInDry = modifies || a.kind === 'pull' || a.kind === 'checkHome';
				if (hooks.dryRun && skipInDry) {
					hooks.log(`would run: ${describeAction(a)}`);
					dry = true;
					continue;
				}
				switch (a.kind) {
					case 'shell': {
						const good = (out: string) => a.ok!.some((k) => out.includes(k));
						let out = await run(frame, a.cmd, hooks);
						if (a.ok && !good(out) && a.fallback) out = await run(frame, a.fallback, hooks);
						if (a.ok && !good(out)) {
							if (a.optional) {
								warned = true;
								hooks.log(`warning: expected "${a.ok.join('" or "')}"`);
							} else {
								throw new StepError(firstLine(out) || `expected "${a.ok.join('" or "')}"`, step.id);
							}
						} else if (!a.ok && FAILURE.test(out)) {
							if (!a.optional) throw new StepError(firstLine(out), step.id);
							warned = true;
						}
						break;
					}
					case 'pull': {
						hooks.log(`pull ${a.remote}`);
						const data = await frame.pull(a.remote);
						if (!data.length) throw new StepError(`empty file ${a.remote}`, step.id);
						hooks.log(`  ${data.length} bytes → ${a.name}`);
						blobs.set(a.name, data);
						hooks.file(a.name, data);
						break;
					}
					case 'fetch':
						release = await hooks.fetchRelease();
						break;
					case 'fetchBoot': {
						if (!hooks.fetchBoot) throw new StepError('no boot branding available', step.id);
						const source = await hooks.fetchBoot();
						const get = async (file: string, sha256: string) => {
							const data = await source.get(file);
							const got = await sha256Hex(data);
							if (got !== sha256) throw new StepError(`boot branding: ${file} does not match its sha256`, step.id);
							hooks.log(`${file}: ${data.length} bytes, sha256 ${got}`);
							return data;
						};
						const assets: BootAssets = {};
						if (a.logo) {
							const backup = blobs.get(RESOURCE_BACKUP);
							const theirs = backup && resourceLogoFormat(backup);
							if (!backup) {
								hooks.log("the boot logo is picked once the frame's own logo has been read");
							} else if (!theirs) {
								throw new StepError('no boot logo in the resource image', step.id);
							} else {
								const v = pickBootLogo(source.manifest, theirs, a.hwrotation);
								const fmt = `${theirs.width}×${theirs.height}, ${theirs.bpp} bits, compression ${theirs.compression}, panel rotation ${a.hwrotation}`;
								if (!v) throw new StepError(`no kinframe boot logo for this frame (its logo is ${fmt}); turn the start-up screen option off`, step.id);
								hooks.log(`the frame's logo is ${fmt}: using ${v.file}`);
								assets.logo = await get(v.file, v.sha256);
							}
						}
						if (a.animation) {
							const v = pickBootAnimation(source.manifest, a.screen);
							if (!v) throw new StepError(`no kinframe boot animation for this frame's screen (${a.screen || 'unknown size'}); turn the start-up screen option off`, step.id);
							hooks.log(`screen ${a.screen}: using ${v.file}`);
							assets.bootanimation = await get(v.file, v.sha256);
						}
						const problem = bootAssetsProblem(assets);
						if (problem) throw new StepError(problem, step.id);
						if (assets.logo) blobs.set('logo', assets.logo);
						if (assets.bootanimation) blobs.set('bootanimation', assets.bootanimation);
						break;
					}
					case 'fetchRootCert': {
						try {
							if (!hooks.fetchRootCert) throw new Error('not available');
							const cert = await hooks.fetchRootCert();
							const got = await sha256Hex(cert);
							if (got !== ROOT_CERT_SHA256) throw new Error(`sha256 ${got}, expected ${ROOT_CERT_SHA256}`);
							hooks.log(`Let's Encrypt root certificate: ${cert.length} bytes, sha256 ${got}`);
							blobs.set('rootCert', cert);
						} catch (e) {
							warn(`could not download the Let's Encrypt root certificate (${e instanceof Error ? e.message : String(e)}); it will not be added`);
						}
						break;
					}
					case 'backupData': {
						const r = await backupData(frame, a.pkg, hooks);
						if (r.files) hooks.file(`${a.pkg}-data-files.txt`, r.files);
						if (r.note) warn(r.note);
						if (r.data) {
							blobs.set(`${a.pkg}-data.tgz`, r.data);
							hooks.file(`${a.pkg}-data.tgz`, r.data);
						} else {
							warn(`could not back up the data of ${a.pkg} (${r.problem}). It stays on the frame: converting only turns the app off, and reverting turns it on again with its data.`);
						}
						break;
					}
					case 'verifyDisabled': {
						const enabled = async () => {
							const disabled = parseDisabledPackages(await run(frame, fill('listDisabled'), hooks));
							return a.packages.filter((p) => !disabled.has(p));
						};
						const fail = (msg: string) => {
							if (a.warnOnly) warn(msg);
							else throw new StepError(msg, step.id);
						};
						const back = await enabled();
						if (!back.length) break;
						if (!a.reapply) {
							fail(`still enabled after pm disable-user: ${back.join(', ')}`);
							break;
						}
						for (const pkg of back) await run(frame, fill('disable', { pkg }), hooks);
						for (const c of settleActions()) await run(frame, c, hooks);
						const still = await enabled();
						if (still.length) fail(`${still.join(', ')} came back after the restart and could not be turned off again`);
						else warn(`${back.join(', ')} came back after the restart; turned off again`);
						break;
					}
					case 'clearUpdater': {
						for (const path of a.downloads) {
							const exists = async () => parseNamesOut(await run(frame, fill('checkPath', { path }), hooks)).includes(path);
							await run(frame, fill('removePath', { path }), hooks);
							if (await exists()) {
								await run(frame, fill('removePathRoot', { path }), hooks);
								if (await exists()) warn(`could not remove the updater's download folder ${path}`);
							}
						}
						if (a.recoveryCommand) {
							await run(frame, fill('removeRecoveryCommand'), hooks);
							if (parseNamesOut(await run(frame, fill('listRecovery'), hooks)).includes('command')) warn('could not remove /cache/recovery/command');
						}
						break;
					}
					case 'editHosts': {
						await editHosts(frame, a.mode, a.system, hooks, (name, data) => hooks.file(name, data), step.id);
						break;
					}
					case 'saveState': {
						const disabled = parseDisabledPackages(await run(frame, fill('listDisabled'), hooks));
						const record = EXTRA_PACKAGES.filter((p) => a.previous.includes(p) || (a.packages.includes(p) && disabled.has(p)));
						const notOff = a.packages.filter((p) => !disabled.has(p));
						if (notOff.length) warn(`still enabled: ${notOff.join(', ')}`);
						const data = new TextEncoder().encode(stateFile(record));
						hooks.log(`push ${data.length} bytes → ${STATE_FILE} (${record.join(', ') || 'nothing'})`);
						await frame.push(STATE_FILE, data);
						hooks.file('kinframe-state.txt', data);
						break;
					}
					case 'note': {
						if (a.warn) warn(a.message);
						else hooks.log(a.message);
						break;
					}
					case 'brandResource': {
						const original = blobs.get(RESOURCE_BACKUP);
						const logo = blobs.get('logo');
						if (!original || !logo) throw new StepError('no resource backup or boot logo', step.id);
						try {
							blobs.set('resource', brandResource(original, logo));
						} catch (e) {
							if (e instanceof ResourceError) throw new StepError(`boot logo not changed: ${e.message}`, step.id);
							throw e;
						}
						hooks.log('boot logo put into the resource image; the device tree is unchanged');
						break;
					}
					case 'flashResource': {
						const image = blobs.get('resource');
						if (!image) throw new StepError('no resource image', step.id);
						await flashResource(frame, a.resource, image, hooks, step.id);
						break;
					}
					case 'verifyRootCert': {
						const want = blobs.get('rootCert');
						const out = await run(frame, fill('sha1RootCert'), hooks);
						const got = /^([0-9a-f]{40})\b/.exec(out)?.[1];
						const expected = want ? await sha1Hex(want) : undefined;
						if (!got || got !== expected) throw new StepError(`the root certificate on the frame is not the one copied (sha1 ${got ?? '?'}, expected ${expected ?? '?'})`, step.id);
						break;
					}
					case 'verifyBootAnimation': {
						const want = blobs.get('bootanimation');
						const out = await run(frame, fill('sha1BootAnimation'), hooks);
						const got = /^([0-9a-f]{40})\b/.exec(out)?.[1];
						const expected = want ? await sha1Hex(want) : undefined;
						if (!got || got !== expected) throw new StepError(`the boot animation on the frame is not the one copied (sha1 ${got ?? '?'}, expected ${expected ?? '?'})`, step.id);
						break;
					}
					case 'push': {
						const data = a.source === 'release' ? release : a.source === 'restore' ? hooks.restoreApk?.() : blobs.get(a.source);
						if (!data) {
							if (hooks.dryRun) break;
							throw new StepError(`no ${a.source === 'release' ? 'verified APK' : a.source === 'restore' ? 'Frameo APK chosen' : PUSH_NAMES[a.source]}`, step.id);
						}
						hooks.log(`push ${data.length} bytes → ${a.remote}`);
						await frame.push(a.remote, data);
						break;
					}
					case 'reboot':
						hooks.log('$ reboot');
						await frame.reboot();
						break;
					case 'waitBoot':
						hooks.log('waiting for the frame to restart…');
						frame = await hooks.reconnect();
						hooks.log('frame is back');
						break;
					case 'checkHome': {
						const front = focusedPackage(await frame.shell(fill('activities')));
						hooks.log(`in front: ${front ?? 'unknown'}`);
						const good = a.expect === 'kinframe' ? front === FRAME_PACKAGE : front !== FRAME_PACKAGE;
						if (!good) warned = true;
						break;
					}
				}
			}
			hooks.step(step.id, dry ? 'dry' : warned ? 'warn' : 'done', warned ? warning : undefined);
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
			if (step.optional) {
				hooks.log(`warning: ${msg} (this step is optional; going on)`);
				hooks.step(step.id, 'warn', msg);
				continue;
			}
			hooks.log(`error: ${msg}`);
			hooks.step(step.id, 'failed', msg);
			failed.push(step.id);
			if (!opts.continueOnError) stopped = true;
		}
	}
	return { ok: failed.length === 0, failed };
}

/** Output that means a command failed (Android 6 adb shell has no exit status). */
const FAILURE = /\b(Error|Exception|not found|denied|Failure|not permitted|Read-only file system|No such file|Usage:)/i;

/** The content of STATE_FILE for these extra packages. */
export function stateFile(packages: readonly string[]): string {
	return `# kinframe convert: the extra packages it disabled; kinframe revert enables these again\n${packages.map((p) => `${p}\n`).join('')}`;
}

function parseNamesOut(out: string): string[] {
	return out
		.split('\n')
		.map((l) => l.trim())
		.filter((l) => l && !/^ls:|No such file|Permission denied/.test(l));
}

/**
 * The `editHosts` action: pulls /system/etc/hosts, builds the new file here, and when it differs pushes it and copies
 * it in (remount rw, cp, chmod 644, chown 0:0, remount ro always), then compares sha1.
 */
async function editHosts(frame: FrameShell, mode: 'block' | 'unblock', system: string, hooks: Pick<RunHooks, 'log'>, save: (name: string, data: Uint8Array) => void, step: StepId): Promise<void> {
	const f = (name: CommandName, vars: Record<string, string> = {}) => run(frame, fill(name, vars), hooks);
	hooks.log(`pull ${HOSTS}`);
	const original = new TextDecoder().decode(await frame.pull(HOSTS));
	const next = mode === 'block' ? addHostsBlock(original) : removeHostsBlock(original);
	if (next === original) {
		hooks.log(mode === 'block' ? 'the update servers are already blocked' : 'no kinframe block in the hosts file');
		return;
	}
	if (mode === 'block') save('hosts', new TextEncoder().encode(original));
	const data = new TextEncoder().encode(next);
	await f('mkWork');
	hooks.log(`push ${data.length} bytes → ${WORK_DIR}/hosts`);
	await frame.push(`${WORK_DIR}/hosts`, data);
	const rw = await f('remountRw', { system });
	if (FAILURE.test(rw)) throw new StepError(`could not make /system writable: ${firstLine(rw)}`, step);
	await f('copyHosts');
	await f('chmodHosts');
	await f('chownHosts');
	await f('remountRo', { system });
	await f('removeFile', { file: 'hosts' });
	const got = /^([0-9a-f]{40})\b/.exec(await f('sha1Hosts'))?.[1];
	const want = await sha1Hex(data);
	if (got !== want) throw new StepError(`${HOSTS} on the frame is not the one copied (sha1 ${got ?? '?'}, expected ${want})`, step);
	hooks.log(mode === 'block' ? 'update servers blocked in the hosts file' : 'kinframe block removed from the hosts file');
}

const PUSH_NAMES: Record<'release' | 'restore' | 'bootanimation' | 'rootCert', string> = {
	release: 'kinframe APK',
	restore: 'Frameo APK',
	bootanimation: 'boot animation',
	rootCert: 'root certificate'
};

/** The commands that give PackageManager time to save package states, then flush them (as in plan.ts settle()). */
function settleActions(): string[] {
	return [fill('settle', { seconds: String(SETTLE_SECONDS) }), fill('sync')];
}

const S_IFMT = 0o170000;
const S_IFDIR = 0o040000;
const S_IFREG = 0o100000;

/**
 * Pulls a directory tree from the frame as tar entries under `prefix` (directories first, then their contents).
 * Regular files and directories only: symlinks and special files are skipped (and counted).
 */
export async function pullTree(frame: FrameShell, remote: string, prefix: string, hooks: Pick<RunHooks, 'log'>): Promise<{ entries: TarEntry[]; skipped: string[] }> {
	if (!frame.list) throw new Error('this connection cannot list directories');
	const entries: TarEntry[] = [];
	const skipped: string[] = [];
	async function walk(dir: string, path: string, mode: number, mtime: number) {
		entries.push({ path, type: 'dir', mode: mode & 0o7777, mtime });
		const list = (await frame.list!(dir)).filter((e) => e.name !== '.' && e.name !== '..').sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
		for (const e of list) {
			const type = e.mode & S_IFMT;
			if (type === S_IFDIR) await walk(`${dir}/${e.name}`, `${path}/${e.name}`, e.mode, e.mtime);
			else if (type === S_IFREG) {
				const data = await frame.pull(`${dir}/${e.name}`);
				if (data.length !== e.size) throw new Error(`${path}/${e.name}: pulled ${data.length} bytes, expected ${e.size}`);
				entries.push({ path: `${path}/${e.name}`, type: 'file', mode: e.mode & 0o7777, mtime: e.mtime, data });
			} else skipped.push(`${path}/${e.name}`);
		}
	}
	await walk(remote, prefix, 0o755, Math.floor(Date.now() / 1000));
	if (skipped.length) hooks.log(`skipped (symlinks or special files): ${skipped.join(', ')}`);
	return { entries, skipped };
}

/**
 * Backs up the app data of `pkg` (the `backupData` action). Toybox tar on some frames can't store names over 100
 * bytes, so nothing is archived on the frame: the data is copied as root into the work directory (without caches,
 * WebView data, crash reports and the lib symlink), made readable, pulled file by file and archived here as
 * `<pkg>/…` in a .tgz; then the copy is removed. `files` is `ls -lnRa` of the original (modes and numeric owners, for
 * a restore). Returns the archive, or why there is none; `note` when some files could not be copied.
 */
export async function backupData(
	frame: FrameShell,
	pkg: string,
	hooks: Pick<RunHooks, 'log'>
): Promise<{ data?: Uint8Array; files?: Uint8Array; problem?: string; note?: string }> {
	const cmd = (n: CommandName) => run(frame, fill(n, { pkg }), hooks);
	await cmd('mkWork');
	const listing = await frame.shell(fill('listData', { pkg }));
	hooks.log(`$ ${fill('listData', { pkg })}\n(${listing.split('\n').length} lines)`);
	const files = listing.trim() && !/No such file/.test(listing) ? new TextEncoder().encode(listing.replace(/\r\n/g, '\n')) : undefined;
	const copy = await cmd('copyData');
	await cmd('pruneData');
	await cmd('readableData');
	let entries: TarEntry[] = [];
	let problem: string | undefined;
	try {
		entries = (await pullTree(frame, `${WORK_DIR}/${pkg}`, pkg, hooks)).entries;
	} catch (e) {
		problem = `could not copy the data from the frame: ${e instanceof Error ? e.message : String(e)}`;
	}
	await cmd('removeDataCopy');
	const count = entries.filter((e) => e.type === 'file').length;
	if (!problem && !count) problem = `no files were copied${copy ? ` (${firstLine(copy)})` : ''}`;
	if (problem) return { files, problem };
	const data = await gzip(tarArchive(entries));
	hooks.log(`${count} files, ${entries.length - count} directories → ${pkg}-data.tgz (${data.length} bytes)`);
	const note = FAILURE.test(copy) ? `some files of ${pkg} could not be copied (${firstLine(copy)}); the backup has the rest` : undefined;
	return { data, files, note };
}

async function sha1Hex(data: Uint8Array): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-1', data as BufferSource);
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Writes a resource image to the resource partition and proves it landed: refuses an image that isn't a valid
 * resource image with a device tree, reads the partition first (kept on the frame as the way back), writes, flushes,
 * reads it back and compares sha256. On a mismatch it writes the previous content back at once and fails the step.
 */
async function flashResource(frame: FrameShell, resource: string, image: Uint8Array, hooks: Pick<RunHooks, 'log'>, step: StepId): Promise<void> {
	const problem = resourceProblem(image);
	if (problem) throw new StepError(`refusing to write the resource image: ${problem}`, step);
	const cmd = (name: CommandName, file?: string) => run(frame, fill(name, file ? { resource, file } : { resource }), hooks);
	const CURRENT = 'resource-current.img';
	const CHECK = 'resource-check.img';
	const NEW = 'resource-new.img';
	async function read(file: string): Promise<Uint8Array> {
		await cmd('mkWork');
		const out = await cmd('readResource', file);
		if (!out.includes('records out')) throw new StepError(`could not read ${resource}: ${firstLine(out)}`, step);
		await cmd('readable', file);
		hooks.log(`pull ${WORK_DIR}/${file}`);
		return frame.pull(`${WORK_DIR}/${file}`);
	}
	async function write(file: string): Promise<string> {
		await cmd('writeResource', file);
		await cmd('sync');
		await cmd('flushResource');
		return sha256Hex(await read(CHECK));
	}

	const current = await read(CURRENT);
	if (current.length !== image.length) {
		await cmd('removeFile', CURRENT);
		throw new StepError(`the resource partition is ${current.length} bytes, the image ${image.length}: not written`, step);
	}
	const before = await sha256Hex(current);
	const want = await sha256Hex(image);
	hooks.log(`resource partition sha256 ${before}, new image sha256 ${want}`);
	if (before === want) {
		hooks.log('the resource partition already has this image');
		await cmd('removeFile', CURRENT);
		return;
	}
	hooks.log(`push ${image.length} bytes → ${WORK_DIR}/${NEW}`);
	await frame.push(`${WORK_DIR}/${NEW}`, image);
	const got = await write(NEW);
	hooks.log(`read back sha256 ${got}`);
	if (got === want) {
		hooks.log('resource partition written and verified');
		for (const f of [NEW, CHECK, CURRENT]) await cmd('removeFile', f);
		return;
	}
	hooks.log('MISMATCH: writing the previous resource image back');
	const again = await write(CURRENT);
	hooks.log(`read back sha256 ${again}`);
	if (again === before) {
		for (const f of [NEW, CHECK, CURRENT]) await cmd('removeFile', f);
		throw new StepError('the new boot logo did not read back correctly; the previous one was written back and checked', step);
	}
	throw new StepError(
		`the resource partition could not be restored (sha256 ${again}, expected ${before}). Do not restart the frame: write the backup ${RESOURCE_BACKUP} back first (docs/hardware/denver-pff-1024.md); a copy is on the frame at ${WORK_DIR}/${CURRENT}`,
		step
	);
}

function firstLine(s: string): string {
	return s.split('\n').find((l) => l.trim())?.trim() ?? '';
}
