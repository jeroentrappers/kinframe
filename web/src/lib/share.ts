// Sharing photos from one frame's library to other frames (POST /v1/frames/{id}/placements:share).

/** How much of a selection a frame already holds. */
export type Coverage = 'none' | 'some' | 'all';

/**
 * Per target frame, how much of the selection it already has, from a dry run's `skipped` list
 * (one entry per source placement × frame that already holds the item).
 */
export function shareCoverage(
	skipped: { placement_id: string; frame_id: string }[],
	frameIds: string[],
	selectionSize: number
): Record<string, Coverage> {
	const have = new Map<string, Set<string>>();
	for (const s of skipped) {
		const set = have.get(s.frame_id) ?? new Set<string>();
		set.add(s.placement_id);
		have.set(s.frame_id, set);
	}
	const out: Record<string, Coverage> = {};
	for (const id of frameIds) {
		const n = have.get(id)?.size ?? 0;
		out[id] = n === 0 ? 'none' : n >= selectionSize ? 'all' : 'some';
	}
	return out;
}

/** The first frame on which every given item of a send still has a live placement, with those placements. */
export function shareSource(
	frames: { frame_id: string; placements: { placement_id: string; item_id: string; status: string }[] }[],
	itemIds: string[]
): { frameId: string; placementIds: string[] } | null {
	for (const f of frames) {
		const ids: string[] = [];
		for (const item of itemIds) {
			const p = f.placements.find((x) => x.item_id === item && x.status !== 'removed' && x.status !== 'failed');
			if (!p) break;
			ids.push(p.placement_id);
		}
		if (ids.length === itemIds.length && ids.length > 0) return { frameId: f.frame_id, placementIds: ids };
	}
	return null;
}
