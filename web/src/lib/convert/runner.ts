// Runs a convert/revert plan against a frame. The frame is behind the `FrameShell` interface (WebUSB ADB in the
// browser, a fake in tests), so the order, the dry run and the error handling are tested without a device.

import {
	COMMANDS,
	DISABLE_PACKAGES,
	FRAME_PACKAGE,
	deviceFacts,
	fill,
	focusedPackage,
	identify,
	packageState,
	type Action,
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
	/** Waits for the frame to come back after a restart and returns the new connection. */
	reconnect(): Promise<FrameShell>;
}

export class StepError extends Error {
	constructor(
		message: string,
		readonly step: StepId
	) {
		super(message);
	}
}

const READ_ONLY_PREFIXES = [COMMANDS.getprop, COMMANDS.rootCheck, 'pm path ', 'dumpsys '];

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
	const id = identify(deviceFacts(getprop, root, packages));
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
			return `push ${a.source === 'release' ? 'kinframe APK' : 'Frameo APK'} → ${a.remote}`;
		case 'fetch':
			return 'download and verify the kinframe release APK';
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
				const modifies = a.kind === 'shell' ? a.modifies : a.kind !== 'pull' && a.kind !== 'fetch' && a.kind !== 'checkHome';
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
						} else if (!a.ok && /\b(Error|Exception|not found|denied|Failure)\b/i.test(out)) {
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
						hooks.file(a.name, data);
						break;
					}
					case 'fetch':
						release = await hooks.fetchRelease();
						break;
					case 'push': {
						const data = a.source === 'release' ? release : hooks.restoreApk?.();
						if (!data) {
							if (hooks.dryRun) break;
							throw new StepError(a.source === 'release' ? 'no verified APK' : 'no Frameo APK chosen', step.id);
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

function firstLine(s: string): string {
	return s.split('\n').find((l) => l.trim())?.trim() ?? '';
}
