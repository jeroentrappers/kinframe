import { api, call, type FrameSummary } from './api/client';

/** The signed-in user's frames, shared by the frame list, the send page and the nav. */
class FramesState {
	list = $state<FrameSummary[]>([]);
	loaded = $state(false);
	error = $state<unknown>(null);
	#inflight: Promise<void> | null = null;

	load(force = false): Promise<void> {
		if (this.loaded && !force) return Promise.resolve();
		this.#inflight ??= (async () => {
			try {
				this.list = await call(api.GET('/v1/frames'));
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

	upsert(frame: FrameSummary) {
		const i = this.list.findIndex((f) => f.id === frame.id);
		if (i >= 0) this.list[i] = { ...this.list[i], ...frame };
		else this.list = [...this.list, frame];
	}

	remove(id: string) {
		this.list = this.list.filter((f) => f.id !== id);
	}

	setStatus(id: string, online: boolean, lastSeen: string | null) {
		const f = this.list.find((x) => x.id === id);
		if (f) {
			f.online = online;
			f.last_seen_at = lastSeen ?? f.last_seen_at;
		}
	}

	reset() {
		this.list = [];
		this.loaded = false;
	}
}

export const frames = new FramesState();

export function canManage(f: Pick<FrameSummary, 'role'>) {
	return f.role === 'owner' || f.role === 'admin';
}

export function can(f: Pick<FrameSummary, 'role' | 'permissions'>, perm: keyof FrameSummary['permissions']) {
	return canManage(f) || f.permissions?.[perm] === true;
}
