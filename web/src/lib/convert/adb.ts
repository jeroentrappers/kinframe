// WebUSB ADB for the convert tool, using Tango (ya-webadb). Browser-only; loaded lazily by /convert so the rest
// of the app doesn't carry it. Wraps a connection as the runner's FrameShell.

import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import AdbWebCredentialStore from '@yume-chan/adb-credential-web';
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { ReadableStream } from '@yume-chan/stream-extra';
import type { FrameShell, RemoteEntry } from './runner';

/** Whether this browser can talk to USB devices (Chrome, Edge, Opera; not Firefox or Safari). */
export function webUsbSupported(): boolean {
	return typeof navigator !== 'undefined' && 'usb' in navigator && AdbDaemonWebUsbDeviceManager.BROWSER !== undefined;
}

const manager = () => {
	const m = AdbDaemonWebUsbDeviceManager.BROWSER;
	if (!m) throw new Error('WebUSB is not available in this browser');
	return m;
};

/** The key this browser uses to authenticate; the frame asks once to allow it ("Always allow from this computer"). */
const credentials = new AdbWebCredentialStore('kinframe convert');

export type ConnectError = 'busy' | 'denied' | 'other';

/** Classifies a connection failure for the person: another adb holds the device, or the frame didn't allow us. */
export function connectError(e: unknown): ConnectError {
	if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) return 'busy';
	const msg = e instanceof Error ? `${e.name} ${e.message}` : String(e);
	if (/busy|claim|in use|Unable to claim/i.test(msg)) return 'busy';
	if (/auth|denied|reject/i.test(msg)) return 'denied';
	return 'other';
}

/** Asks the person to pick the frame in the browser's USB dialog. Undefined when they cancel. */
export async function chooseDevice(): Promise<AdbDaemonWebUsbDevice | undefined> {
	return manager().requestDevice();
}

/** Opens the device and authenticates (the frame shows "Allow USB debugging?" the first time). */
export async function connect(device: AdbDaemonWebUsbDevice): Promise<WebUsbFrame> {
	const connection = await device.connect();
	const transport = await AdbDaemonTransport.authenticate({ serial: device.serial, connection, credentialStore: credentials });
	return new WebUsbFrame(new Adb(transport), device.serial);
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Rejects after `ms`; `onLate` cleans up whatever the promise yields if it still arrives afterwards. */
function withTimeout<T>(p: Promise<T>, ms: number, what: string, onLate?: (v: T) => void): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		let late = false;
		const t = setTimeout(() => {
			late = true;
			reject(new Error(`${what} took longer than ${Math.round(ms / 1000)} s`));
		}, ms);
		p.then(
			(v) => {
				clearTimeout(t);
				if (late) onLate?.(v);
				else resolve(v);
			},
			(e) => {
				clearTimeout(t);
				if (!late) reject(e);
			}
		);
	});
}

/**
 * After a restart the frame drops off USB and comes back as a new device the browser already has permission for,
 * so no dialog is needed. Waits until it is back and has finished booting. Every step has its own time limit: a
 * USB connection or adb handshake that hangs while the frame is still starting is dropped and tried again, so the
 * wait always ends (`log` says what it is doing).
 */
export async function reconnect(
	serial: string,
	opts: { timeoutMs?: number; signal?: AbortSignal; log?: (line: string) => void } = {}
): Promise<WebUsbFrame> {
	const deadline = Date.now() + (opts.timeoutMs ?? 240_000);
	const log = opts.log ?? (() => {});
	await sleep(8000);
	let seen = false;
	let lastError = '';
	while (Date.now() < deadline) {
		if (opts.signal?.aborted) throw new Error('cancelled');
		const device = (await manager().getDevices()).find((d) => d.serial === serial);
		if (device) {
			if (!seen) log('the frame is back on USB; connecting…');
			seen = true;
			let frame: WebUsbFrame | undefined;
			try {
				frame = await withTimeout(connect(device), 20_000, 'connecting', (f) => void f.close());
				for (let i = 0; i < 60 && Date.now() < deadline; i++) {
					const done = await withTimeout(frame.shell('getprop sys.boot_completed'), 10_000, 'asking whether it has started');
					if (done.trim() === '1') {
						await sleep(4000); // let the home app come up
						return frame;
					}
					if (i === 0) log('connected; waiting until Android has finished starting…');
					await sleep(2000);
				}
			} catch (e) {
				// adbd is still starting, or the connection hung; drop it and try again
				const msg = e instanceof Error ? e.message : String(e);
				if (msg !== lastError) log(`not yet (${msg}); trying again`);
				lastError = msg;
			}
			await frame?.close();
		}
		await sleep(2000);
	}
	throw new Error(
		seen
			? 'the frame came back but did not answer within 4 minutes. Unplug the USB cable, plug it in again and start over: the steps already done are skipped or repeated safely.'
			: 'the frame did not come back within 4 minutes. Check the USB cable, then start over.'
	);
}

export class WebUsbFrame implements FrameShell {
	constructor(
		readonly adb: Adb,
		readonly serial: string
	) {}

	get disconnected(): Promise<void> {
		return this.adb.disconnected;
	}

	shell(cmd: string): Promise<string> {
		return this.adb.subprocess.noneProtocol.spawnWaitText(cmd);
	}

	async push(remote: string, data: Uint8Array): Promise<void> {
		const sync = await this.adb.sync();
		try {
			await sync.write({
				filename: remote,
				file: new ReadableStream<Uint8Array>({
					start(c) {
						c.enqueue(data);
						c.close();
					}
				})
			});
		} finally {
			await sync.dispose();
		}
	}

	async pull(remote: string): Promise<Uint8Array> {
		const sync = await this.adb.sync();
		try {
			const chunks: Uint8Array[] = [];
			let size = 0;
			const reader = sync.read(remote).getReader();
			for (;;) {
				const { done, value } = await reader.read();
				if (done) break;
				chunks.push(value);
				size += value.length;
			}
			const out = new Uint8Array(size);
			let o = 0;
			for (const c of chunks) {
				out.set(c, o);
				o += c.length;
			}
			return out;
		} finally {
			await sync.dispose();
		}
	}

	async list(remote: string): Promise<RemoteEntry[]> {
		const sync = await this.adb.sync();
		try {
			return (await sync.readdir(remote)).map((e) => ({ name: e.name, mode: e.mode, size: Number(e.size), mtime: Number(e.mtime) }));
		} finally {
			await sync.dispose();
		}
	}

	async reboot(cleanCommand?: string): Promise<'clean' | 'adb'> {
		if (cleanCommand) {
			// The command doesn't return before Android shuts down, so don't wait for it: wait for the frame to go.
			const gone = this.adb.disconnected.then(() => true);
			this.shell(cleanCommand).catch(() => {});
			if (await Promise.race([gone, sleep(30_000).then(() => false)])) {
				await this.close();
				return 'clean';
			}
		}
		try {
			await this.adb.power.reboot();
		} catch {
			// the connection drops while the command is answered
		}
		await this.close();
		return 'adb';
	}

	async close(): Promise<void> {
		try {
			await this.adb.close();
		} catch {
			/* already gone */
		}
	}
}
