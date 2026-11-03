// WebUSB ADB for the convert tool, using Tango (ya-webadb). Browser-only; loaded lazily by /convert so the rest
// of the app doesn't carry it. Wraps a connection as the runner's FrameShell.

import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import AdbWebCredentialStore from '@yume-chan/adb-credential-web';
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { ReadableStream } from '@yume-chan/stream-extra';
import type { FrameShell } from './runner';

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

/**
 * After a restart the frame drops off USB and comes back as a new device the browser already has permission for,
 * so no dialog is needed. Waits until it is back and has finished booting.
 */
export async function reconnect(serial: string, opts: { timeoutMs?: number; signal?: AbortSignal } = {}): Promise<WebUsbFrame> {
	const deadline = Date.now() + (opts.timeoutMs ?? 240_000);
	await sleep(8000);
	while (Date.now() < deadline) {
		if (opts.signal?.aborted) throw new Error('cancelled');
		const device = (await manager().getDevices()).find((d) => d.serial === serial);
		if (device) {
			try {
				const frame = await connect(device);
				for (let i = 0; i < 60 && Date.now() < deadline; i++) {
					if ((await frame.shell('getprop sys.boot_completed')).trim() === '1') {
						await sleep(4000); // let the home app come up
						return frame;
					}
					await sleep(2000);
				}
				await frame.close();
			} catch {
				// adbd is still starting; try again
			}
		}
		await sleep(2000);
	}
	throw new Error('the frame did not come back within 4 minutes');
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

	async reboot(): Promise<void> {
		try {
			await this.adb.power.reboot();
		} catch {
			// the connection drops while the command is answered
		}
		await this.close();
	}

	async close(): Promise<void> {
		try {
			await this.adb.close();
		} catch {
			/* already gone */
		}
	}
}
