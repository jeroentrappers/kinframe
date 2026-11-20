// The photo a frame card shows (simple-app.md §3.3): the last one placed on the frame. The server has no
// "now showing" for apps, so the newest visible placement stands in. One request per frame per visit.

import { api, call } from './api/client';

export interface FramePhoto {
	itemId: string;
	kind: 'photo' | 'video' | 'live';
}

const cache = new Map<string, Promise<FramePhoto | null>>();

export function lastPhoto(frameId: string): Promise<FramePhoto | null> {
	let p = cache.get(frameId);
	if (!p) {
		p = call(api.GET('/v1/frames/{frameId}/placements', { params: { path: { frameId }, query: { limit: 1, visibility: 'visible', sort: 'received_desc' } } }))
			.then((res) => {
				const it = res.placements[0]?.item;
				return it && it.status === 'ready' ? { itemId: it.id, kind: it.kind } : null;
			})
			.catch(() => null);
		cache.set(frameId, p);
	}
	return p;
}

/** A send just went out: look again next time. */
export function forgetLastPhoto(frameId?: string) {
	if (frameId) cache.delete(frameId);
	else cache.clear();
}
