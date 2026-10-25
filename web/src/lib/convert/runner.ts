// Runs a convert/revert plan against a frame. The frame is behind the `FrameShell` interface (WebUSB ADB in the
// browser, a fake in tests), so the order, the dry run and the error handling are tested without a device.

import { sha256Hex } from './apk';
import { ResourceError, bmpInfo, brandResource, resourceProblem } from './rsce';
import {
	COMMANDS,
	DISABLE_PACKAGES,
	RESOURCE_BACKUP,
	WORK_DIR,
	FRAME_PACKAGE,
	deviceFacts,
	fill,
	focusedPackage,
	identify,
	packageState,
	type Action,
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
	/** Restarts the frame. The connection drops; `reconnect` returns a new one. */
	reboot(): Promise<void>;
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
	/** Downloads the start-up branding (convert with boot branding). Throws with a readable message when it can't. */
	fetchBoot?(): Promise<BootAssets>;
	/** The backed-up resource image and boot animation the person chose (revert). */
	restoreBoot?(): { resource?: Uint8Array; bootanimation?: Uint8Array };
	/** Waits for the frame to come back after a restart and returns the new connection. */
	reconnect(): Promise<FrameShell>;
}

/** The start-up branding put on a frame (tools/brand/boot). */
export interface BootAssets {
	logo: Uint8Array;
	bootanimation: Uint8Array;
}

/** Checks downloaded start-up branding: a BMP logo and a zip. Returns the problem, or null. */
export function bootAssetsProblem(a: BootAssets): string | null {
	if (!bmpInfo(a.logo)) return 'the boot logo is not a BMP file';
	if (!(a.bootanimation[0] === 0x50 && a.bootanimation[1] === 0x4b && a.bootanimation[2] === 3 && a.bootanimation[3] === 4)) return 'the boot animation is not a zip file';
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

const READ_ONLY_PREFIXES = [COMMANDS.getprop, COMMANDS.rootCheck, COMMANDS.findResource, COMMANDS.mounts, COMMANDS.sha1BootAnimation, 'pm path ', 'dumpsys '];

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
	const id = identify(deviceFacts(getprop, root, packages, { findResource, mounts }));
	hooks.log(describe(id));
	return id;
}

/** A short, support-friendly summary of an identification. */
export function describe(id: Identification): string {
	const f = id.facts;
	const pkgs = Object.entries(f.packages)
		.map(([p, s]) => `  ${p}: ${s.installed ? `${s.versionName ?? '?'} (${s.versionCode ?? '?'}), ${s.enabled}` : 'not installed'}`)
		.join('\n');
	return [
		`device: ${f.brand} ${f.model} (${id.modelKey}), manufacturer ${f.manufacturer}, platform ${f.platform}`,
		`android: ${f.release} (API ${f.sdk}), abis ${f.abis.join(',') || '?'}, root ${f.root ? 'yes' : 'no'}`,
		`boot: resource ${f.resource || 'none'}, /system on ${f.system || '?'}`,
		`fingerprint: ${f.fingerprint}`,
		`verdict: ${id.verdict}${id.issues.length ? ` (${id.issues.join(', ')})` : ''}`,
		'packages:',
		pkgs
	].join('\n');
}

function describeAction(a: Action): string {
	switch (a.kind) {
		case 'shell':
			return a.cmd;
		case 'pull':
			return `pull ${a.remote} → ${a.name}`;
		case 'push':
			return `push ${a.source === 'release' ? 'kinframe APK' : a.source === 'restore' ? 'Frameo APK' : 'boot animation'} → ${a.remote}`;
		case 'fetch':
			return 'download and verify the kinframe release APK';
		case 'fetchBoot':
			return 'download the boot logo and boot animation';
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
		let dry = false;
		try {
			if (!step.actions.length) hooks.log('(nothing to do on this frame)');
			for (const a of step.actions) {
				const modifies = a.kind === 'shell' ? a.modifies : !['pull', 'fetch', 'fetchBoot', 'checkHome'].includes(a.kind);
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
						const assets = await hooks.fetchBoot();
						const problem = bootAssetsProblem(assets);
						if (problem) throw new StepError(problem, step.id);
						hooks.log(`boot logo ${assets.logo.length} bytes, sha256 ${await sha256Hex(assets.logo)}`);
						hooks.log(`boot animation ${assets.bootanimation.length} bytes, sha256 ${await sha256Hex(assets.bootanimation)}`);
						blobs.set('logo', assets.logo);
						blobs.set('bootanimation', assets.bootanimation);
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
					case 'verifyBootAnimation': {
						const want = blobs.get('bootanimation');
						const out = await run(frame, fill('sha1BootAnimation'), hooks);
						const got = /^([0-9a-f]{40})\b/.exec(out)?.[1];
						const expected = want ? await sha1Hex(want) : undefined;
						if (!got || got !== expected) throw new StepError(`the boot animation on the frame is not the one copied (sha1 ${got ?? '?'}, expected ${expected ?? '?'})`, step.id);
						break;
					}
					case 'push': {
						const data = a.source === 'release' ? release : a.source === 'restore' ? hooks.restoreApk?.() : blobs.get('bootanimation');
						if (!data) {
							if (hooks.dryRun) break;
							throw new StepError(a.source === 'release' ? 'no verified APK' : a.source === 'restore' ? 'no Frameo APK chosen' : 'no boot animation', step.id);
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
			hooks.step(step.id, dry ? 'dry' : warned ? 'warn' : 'done');
		} catch (e) {
			const msg = e instanceof Error ? e.message : String(e);
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
