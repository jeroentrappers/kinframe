/** Pairs iPhone Live Photo exports: a still and a short clip with the same base name (IMG_1234.HEIC + IMG_1234.MOV). */

const STILL_EXT = ['heic', 'heif', 'jpg', 'jpeg', 'png', 'webp', 'avif'];
const CLIP_EXT = ['mov', 'mp4'];

function split(name: string): { base: string; ext: string } {
	const dot = name.lastIndexOf('.');
	return dot > 0 ? { base: name.slice(0, dot).toLowerCase(), ext: name.slice(dot + 1).toLowerCase() } : { base: name.toLowerCase(), ext: '' };
}

export interface LiveEntry<F> {
	file: F;
	/** The paired motion clip, when this is the still of a Live Photo. */
	clip: F | null;
}

/** Keeps the order of `files`; a paired clip is attached to its still and not listed on its own. */
export function pairLive<F extends { name: string }>(files: F[]): LiveEntry<F>[] {
	const stills = new Map<string, number>();
	files.forEach((f, i) => {
		const { base, ext } = split(f.name);
		if (STILL_EXT.includes(ext) && !stills.has(base)) stills.set(base, i);
	});
	const clipFor = new Map<number, F>();
	const used = new Set<number>();
	files.forEach((f, i) => {
		const { base, ext } = split(f.name);
		const s = stills.get(base);
		if (CLIP_EXT.includes(ext) && s !== undefined && !clipFor.has(s)) {
			clipFor.set(s, f);
			used.add(i);
		}
	});
	return files.flatMap((f, i) => (used.has(i) ? [] : [{ file: f, clip: clipFor.get(i) ?? null }]));
}
