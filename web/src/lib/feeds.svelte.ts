import { api, call, type FeedSummary } from './api/client';
import { discovery } from './discovery.svelte';

/** The signed-in user's feeds (any role), shared by the feed list, the send page and the frame pages. */
class FeedsState {
	list = $state<FeedSummary[]>([]);
	loaded = $state(false);
	error = $state<unknown>(null);
	#inflight: Promise<void> | null = null;

	/** Does nothing when the server has no feeds (discovery `feeds`). */
	load(force = false): Promise<void> {
		if (!discovery.feeds) return Promise.resolve();
		if (this.loaded && !force) return Promise.resolve();
		this.#inflight ??= (async () => {
			try {
				this.list = await call(api.GET('/v1/feeds'));
				this.error = null;
				this.loaded = true;
			} catch (e) {
				this.error = e;
			} finally {
				this.#inflight = null;
			}
		})();
		return this.#inflight;
	}

	get(id: string) {
		return this.list.find((f) => f.id === id);
	}

	upsert(feed: FeedSummary) {
		const i = this.list.findIndex((f) => f.id === feed.id);
		if (i >= 0) this.list[i] = { ...this.list[i], ...feed };
		else this.list = [...this.list, feed];
	}

	remove(id: string) {
		this.list = this.list.filter((f) => f.id !== id);
	}

	reset() {
		this.list = [];
		this.loaded = false;
	}
}

export const feeds = new FeedsState();
