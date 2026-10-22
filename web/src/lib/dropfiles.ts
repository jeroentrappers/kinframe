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
