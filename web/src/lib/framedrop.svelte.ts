import { goto } from '$app/navigation';
import { compose } from './compose.svelte';
import { discovery } from './discovery.svelte';
import { carriesFiles, DragDepth, dropFrameAt, filesFromDataTransfer } from './dropfiles';
import { t, tn } from './i18n/index.svelte';
import { toasts } from './toast.svelte';

/**
 * Start a send to one frame: the files join the draft, only that frame is picked, and the
 * Send page opens so the sender can still look things over, add captions and confirm.
 */
export async function sendToFrame(frameId: string, files: File[]) {
	if (files.length === 0) return;
	// While a send is uploading the draft is locked; the Send page shows its progress.
	if (compose.phase === 'compose') {
		compose.frameIds = [frameId];
		const { unsupported, overLimit } = compose.add(files);
		if (overLimit > 0) toasts.error(t('send.overLimit', { max: discovery.limits.max_items_per_send, n: overLimit }));
		if (unsupported > 0) toasts.show(tn('send.skipped', unsupported));
	}
	await goto('/send');
}

/**
 * Window-level drag and drop of files onto frames. `resolve` maps the frame under the pointer
 * (an element marked `data-drop-frame`, or null) to the frame a drop there sends to, or null.
 * Drops anywhere else are swallowed so the browser doesn't open the file instead of the app.
 */
export class FrameDrop {
	/** Files are being dragged over the page. */
	active = $state(false);
	/** The frame a drop right now would send to. */
	target = $state<string | null>(null);

	#depth = new DragDepth();
	#resolve: (hovered: string | null) => string | null;

	constructor(resolve: (hovered: string | null) => string | null) {
		this.#resolve = resolve;
	}

	enter = (e: DragEvent) => {
		if (!carriesFiles(e.dataTransfer)) return;
		this.active = this.#depth.enter();
	};

	leave = (e: DragEvent) => {
		if (!carriesFiles(e.dataTransfer)) return;
		this.active = this.#depth.leave();
		if (!this.active) this.target = null;
	};

	over = (e: DragEvent) => {
		if (!carriesFiles(e.dataTransfer)) return;
		e.preventDefault();
		this.active = true;
		this.target = this.#resolve(dropFrameAt(e.target));
		if (e.dataTransfer) e.dataTransfer.dropEffect = this.target ? 'copy' : 'none';
	};

	drop = async (e: DragEvent) => {
		if (!carriesFiles(e.dataTransfer)) return;
		e.preventDefault();
		const frameId = this.#resolve(dropFrameAt(e.target));
		this.#depth.reset();
		this.active = false;
		this.target = null;
		if (!frameId || !e.dataTransfer) return;
		await sendToFrame(frameId, await filesFromDataTransfer(e.dataTransfer));
	};
}
