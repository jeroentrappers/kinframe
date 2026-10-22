import { api, call, type Send } from './api/client';
import { live } from './live.svelte';
import { applyPlacementEvent, type Reaction } from './logic';

/**
 * Keep a set of sends fresh from the user websocket. `get` returns the sends currently shown;
 * `replace` swaps one after a refetch. Reactions and replies patch in place; anything else
 * (send.updated) triggers a debounced GET /v1/sends/{id}.
 */
export function followSends(get: () => Send[], replace: (s: Send) => void): () => void {
	const timers = new Map<string, ReturnType<typeof setTimeout>>();
	const refetch = (id: string) => {
		clearTimeout(timers.get(id));
		timers.set(
			id,
			setTimeout(async () => {
				timers.delete(id);
				try {
					replace(await call(api.GET('/v1/sends/{sendId}', { params: { path: { sendId: id } } })));
				} catch {
					/* next event will retry */
				}
			}, 250)
		);
	};
	const off = live.on((e) => {
		if (e.type === 'send.updated') {
			if (get().some((s) => s.id === e.send.id)) refetch(e.send.id);
		} else if (e.type === 'reaction' || e.type === 'reply') {
			const ev =
				e.type === 'reaction'
					? { placement_id: e.placement_id, reaction: e.reaction as Reaction | null }
					: { placement_id: e.placement_id, preset: e.preset };
			for (const s of get()) if (applyPlacementEvent(s as Parameters<typeof applyPlacementEvent>[0], ev)) break;
		} else if (e.type === 'frame.status') {
			for (const s of get()) for (const f of s.frames) if (f.frame_id === e.frame_id) f.online = e.online;
		}
	});
	return () => {
		off();
		for (const t of timers.values()) clearTimeout(t);
	};
}
