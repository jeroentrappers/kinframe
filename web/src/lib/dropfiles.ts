/** Collect files from a drop, descending into dropped folders (Chrome, Edge, Firefox, Safari). */
export async function filesFromDataTransfer(dt: DataTransfer): Promise<File[]> {
	const entries: FileSystemEntry[] = [];
	for (const item of Array.from(dt.items ?? [])) {
		if (item.kind !== 'file') continue;
		const entry = item.webkitGetAsEntry?.();
		if (entry) entries.push(entry);
	}
	if (entries.length === 0) return Array.from(dt.files ?? []);
	const out: File[] = [];
	await Promise.all(entries.map((e) => walk(e, out)));
	return out.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
}

async function walk(entry: FileSystemEntry, out: File[], depth = 0): Promise<void> {
	if (depth > 8) return;
	if (entry.isFile) {
		const file = await new Promise<File | null>((res) => (entry as FileSystemFileEntry).file(res, () => res(null)));
		if (file && !file.name.startsWith('.')) out.push(file);
		return;
	}
	if (entry.isDirectory) {
		const reader = (entry as FileSystemDirectoryEntry).createReader();
		// readEntries returns batches; keep reading until empty.
		for (;;) {
			const batch = await new Promise<FileSystemEntry[]>((res) => reader.readEntries(res, () => res([])));
			if (batch.length === 0) break;
			await Promise.all(batch.map((e) => walk(e, out, depth + 1)));
		}
	}
}

/** Whether a drag carries files from the computer (not text, a link or an image dragged within the page). */
export function carriesFiles(dt: Pick<DataTransfer, 'types'> | null | undefined): boolean {
	return !!dt && Array.from(dt.types ?? []).includes('Files');
}

/** Attribute that marks an element as the drop target for one frame. */
export const DROP_FRAME_ATTR = 'data-drop-frame';

type Closest = { closest?: (selector: string) => { getAttribute(name: string): string | null } | null };

/** The frame a drag or drop at `target` is aimed at: the nearest element marked with `data-drop-frame`. */
export function dropFrameAt(target: EventTarget | null): string | null {
	const el = target as Closest | null;
	if (!el || typeof el.closest !== 'function') return null;
	return el.closest(`[${DROP_FRAME_ATTR}]`)?.getAttribute(DROP_FRAME_ATTR) || null;
}

/**
 * Counts dragenter/dragleave pairs: entering a child fires enter on it before leave on its parent,
 * so files are being dragged over the page while the count is above zero.
 */
export class DragDepth {
	#n = 0;

	get active() {
		return this.#n > 0;
	}

	enter() {
		this.#n++;
		return this.active;
	}

	leave() {
		this.#n = Math.max(0, this.#n - 1);
		return this.active;
	}

	reset() {
		this.#n = 0;
	}
}
