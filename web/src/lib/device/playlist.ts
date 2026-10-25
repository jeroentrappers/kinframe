// Slideshow order for the browser frame (product spec 4.4), the same rules as the
// Android frame in a smaller form: received / taken newest first (`reverse` = oldest
// first), or a seeded shuffle weighted toward recent photos; new arrivals play first.

export type PlayOrder = 'received' | 'taken' | 'shuffle';

export interface PlaylistItem {
	id: string;
	receivedAt: string;
	capturedAt: string | null;
}

const ms = (s: string | null | undefined) => {
	const t = s ? Date.parse(s) : NaN;
	return Number.isFinite(t) ? t : 0;
};

/** Deterministic value in (0, 1) for a seed and an id (FNV-1a + xorshift). */
export function uniform(seed: number, id: string): number {
	let h = (2166136261 ^ seed) >>> 0;
	for (let i = 0; i < id.length; i++) {
		h ^= id.charCodeAt(i);
		h = Math.imul(h, 16777619) >>> 0;
	}
	h ^= h << 13;
	h >>>= 0;
	h ^= h >>> 17;
	h ^= h << 5;
	h >>>= 0;
	return (h + 0.5) / 4294967296;
}

export function orderIds(items: PlaylistItem[], order: PlayOrder, reverse: boolean, seed: number, now = Date.now()): string[] {
	let sorted: PlaylistItem[];
	if (order === 'shuffle') {
		// Weighted random permutation (key = -ln(u) / w): recent photos come up more often early in a cycle.
		const key = (it: PlaylistItem) => {
			const ageDays = Math.max(0, now - ms(it.receivedAt)) / 86_400_000;
			const w = 1 + 3 * Math.exp(-ageDays / 30);
			return -Math.log(uniform(seed, it.id)) / w;
		};
		const keys = new Map(items.map((it) => [it.id, key(it)]));
		sorted = [...items].sort((a, b) => keys.get(a.id)! - keys.get(b.id)!);
	} else {
		const at = (it: PlaylistItem) => (order === 'taken' ? ms(it.capturedAt) || ms(it.receivedAt) : ms(it.receivedAt));
		sorted = [...items].sort((a, b) => at(b) - at(a) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	}
	const ids = sorted.map((it) => it.id);
	return order !== 'shuffle' && reverse ? ids.reverse() : ids;
}

export class Playlist {
	current: string | null = null;
	#ordered: string[] = [];
	#pendingNew: string[] = [];
	#history: string[] = [];
	#forward: string[] = [];
	#anchor: string | null = null;
	#seed: number;
	#items: PlaylistItem[] = [];
	#order: PlayOrder = 'received';
	#reverse = false;

	constructor(seed = Math.floor(Math.random() * 2 ** 31)) {
		this.#seed = seed;
	}

	get size(): number {
		return this.#ordered.length;
	}

	get ordered(): readonly string[] {
		return this.#ordered;
	}

	/** New photos waiting to be shown, oldest arrival first. */
	get pendingNew(): readonly string[] {
		return this.#pendingNew;
	}

	update(items: PlaylistItem[], order: PlayOrder, reverse: boolean, now = Date.now()): void {
		this.#items = items;
		this.#order = order;
		this.#reverse = reverse;
		this.#ordered = orderIds(items, order, reverse, this.#seed, now);
		const present = new Set(this.#ordered);
		this.#pendingNew = this.#pendingNew.filter((id) => present.has(id));
		this.#history = this.#history.filter((id) => present.has(id));
		this.#forward = this.#forward.filter((id) => present.has(id));
		if (this.current && !present.has(this.current)) this.current = null;
	}

	/** Photos that arrived while running: they play next, in arrival order. */
	addNew(ids: string[]): void {
		for (const id of ids) if (!this.#pendingNew.includes(id)) this.#pendingNew.push(id);
	}

	peekNext(): string | null {
		if (this.#forward.length) return this.#forward[this.#forward.length - 1]!;
		const fresh = this.#pendingNew.find((id) => id !== this.current && this.#ordered.includes(id));
		if (fresh) return fresh;
		if (!this.#ordered.length) return null;
		const start = this.#anchor ? this.#ordered.indexOf(this.#anchor) : -1;
		const n = this.#ordered.length;
		for (let step = 1; step <= n; step++) {
			const id = this.#ordered[(start + step) % n]!;
			// Never the photo on screen again (a new arrival that also sits next in the order).
			if (id !== this.current || n === 1) return id;
		}
		return null;
	}

	next(): string | null {
		const id = this.peekNext();
		if (!id) return null;
		if (this.#forward.length && this.#forward[this.#forward.length - 1] === id) this.#forward.pop();
		else if (this.#pendingNew.includes(id)) this.#pendingNew = this.#pendingNew.filter((x) => x !== id);
		else {
			this.#anchor = id;
			// A full cycle of a shuffle starts a new permutation (from the start of the new order).
			if (this.#order === 'shuffle' && this.#ordered.length > 2 && this.#ordered.indexOf(id) === this.#ordered.length - 1) {
				this.#seed = (Math.imul(this.#seed, 1103515245) + 12345) >>> 0;
				this.#ordered = orderIds(this.#items, this.#order, this.#reverse, this.#seed);
				// Do not repeat the photo that ends one cycle as the first of the next.
				this.#anchor = this.#ordered[0] === id ? id : null;
			}
		}
		this.#go(id);
		return id;
	}

	previous(): string | null {
		while (this.#history.length) {
			const id = this.#history.pop()!;
			if (id !== this.current && this.#ordered.includes(id)) {
				if (this.current) this.#forward.push(this.current);
				this.current = id;
				return id;
			}
		}
		if (!this.#ordered.length) return null;
		const i = this.current ? this.#ordered.indexOf(this.current) : 0;
		const id = this.#ordered[(i - 1 + this.#ordered.length) % this.#ordered.length]!;
		if (this.current) this.#forward.push(this.current);
		this.current = id;
		this.#anchor = id;
		return id;
	}

	/** Shows a specific item now (remote `show_placement`, the "new photo" card). */
	jumpTo(id: string): boolean {
		if (!this.#ordered.includes(id)) return false;
		this.#forward = [];
		this.#pendingNew = this.#pendingNew.filter((x) => x !== id);
		this.#anchor = id;
		this.#go(id);
		return true;
	}

	#go(id: string): void {
		if (this.current && this.current !== id) {
			this.#history.push(this.current);
			if (this.#history.length > 50) this.#history.shift();
		}
		this.current = id;
	}
}
