// The browser frame's local copy of its state, built from the change feed (README §5.4).
// Kept in memory: a reload syncs again from 0 (a snapshot), which is cheap for a library
// and keeps localStorage for the credential only.
import type { components } from '../api/schema';
import type { Change, ChangesPage, DeviceClient } from './client';

type S = components['schemas'];
export type FrameSettings = S['FrameSettings'];
export type Weather = NonNullable<S['Weather']>;

export interface FramePlacement {
	id: string;
	kind: 'photo' | 'video' | 'live';
	caption: string | null;
	senderName: string;
	senderId: string | null;
	capturedAt: string | null;
	receivedAt: string;
	fit: 'fill' | 'fit' | null;
	focal: { x: number; y: number } | null;
	rotation: 0 | 90 | 180 | 270;
	reaction: S['Reaction'] | null;
	albumIds: string[];
	width: number;
	height: number;
	durationMs: number | null;
	hasPoster: boolean;
	/** In the slideshow: ready, not hidden, trashed or awaiting approval. */
	visible: boolean;
}

export interface FrameInfo {
	name: string;
	location: string | null;
	ownerName: string;
}

type PlacementChange = NonNullable<Change['placement']>;

/** A change-feed placement as the slideshow needs it, or null if it has nothing to show yet. */
export function toFramePlacement(p: PlacementChange): FramePlacement {
	const it = p.item;
	const media = p.media;
	const focal = p.focal_override ?? it.focal_point ?? null;
	return {
		id: p.id,
		kind: it.kind,
		caption: it.caption ?? null,
		senderName: it.imported_sender_name ?? it.sender_name ?? '',
		senderId: it.imported_from ? null : (it.sender_id ?? null),
		capturedAt: it.captured_at ?? null,
		receivedAt: p.received_at ?? it.created_at,
		fit: p.fit_override ?? it.fit ?? null,
		focal: focal ? { x: focal.x, y: focal.y } : null,
		rotation: (p.rotation ?? 0) as FramePlacement['rotation'],
		reaction: p.reaction ?? null,
		albumIds: p.album_ids ?? [],
		width: media?.width ?? it.width ?? 0,
		height: media?.height ?? it.height ?? 0,
		durationMs: it.duration_ms ?? null,
		hasPoster: !!media?.poster_url,
		visible: !!media && !p.hidden && !p.trashed && !p.pending_moderation && p.status !== 'removed' && p.status !== 'failed'
	};
}

export interface ApplyResult {
	/** Placements that arrived after the first snapshot: the new-photo celebration. */
	added: string[];
	settingsChanged: boolean;
	reset: boolean;
}

export class FrameFeed {
	seq = 0;
	placements = new Map<string, FramePlacement>();
	settings: FrameSettings | null = null;
	frame: FrameInfo | null = null;
	weather: Weather | null = null;
	members = new Map<string, { name: string; avatarUrl: string | null }>();
	/** True once the first snapshot (since=0) was applied. */
	synced = false;
	/** Placements that have been visible at some point: un-hiding one is not "new". */
	readonly #shown = new Set<string>();

	clear(): void {
		this.seq = 0;
		this.placements.clear();
		this.members.clear();
		this.settings = null;
		this.frame = null;
		this.weather = null;
		this.synced = false;
		this.#shown.clear();
	}

	/** Applies one page of changes in order. */
	apply(page: ChangesPage): ApplyResult {
		const out: ApplyResult = { added: [], settingsChanged: false, reset: false };
		for (const c of page.changes) {
			switch (c.type) {
				case 'reset':
					this.clear();
					out.reset = true;
					return out;
				case 'placement.upsert': {
					if (!c.placement) break;
					const fp = toFramePlacement(c.placement);
					this.placements.set(fp.id, fp);
					if (fp.visible && !this.#shown.has(fp.id)) {
						this.#shown.add(fp.id);
						if (this.synced) out.added.push(fp.id);
					}
					break;
				}
				case 'placement.remove':
					if (c.id) this.placements.delete(c.id);
					break;
				case 'settings':
					if (c.settings) {
						this.settings = c.settings;
						out.settingsChanged = true;
					}
					break;
				case 'frame':
					if (c.frame) this.frame = { name: c.frame.name ?? '', location: c.frame.location ?? null, ownerName: c.frame.owner_name ?? '' };
					break;
				case 'weather':
					this.weather = (c.weather as Weather | null | undefined) ?? null;
					break;
				case 'member.upsert':
					if (c.member?.user_id) this.members.set(c.member.user_id, { name: c.member.display_name ?? '', avatarUrl: c.member.avatar_url ?? null });
					break;
				case 'member.remove':
					if (c.id) this.members.delete(c.id);
					break;
				default:
					// album.*, calendar: not shown by the browser frame yet (album_id filtering uses placement.album_ids).
					break;
			}
		}
		this.seq = page.next_seq;
		return out;
	}

	/** Pulls every page after `seq`; a reset starts over from 0 once. */
	async pull(client: Pick<DeviceClient, 'changes'>): Promise<ApplyResult> {
		const total: ApplyResult = { added: [], settingsChanged: false, reset: false };
		for (let pages = 0; pages < 200; pages++) {
			const page = await client.changes(this.seq);
			if (!page) break;
			const r = this.apply(page);
			total.added.push(...r.added);
			total.settingsChanged ||= r.settingsChanged;
			if (r.reset) {
				if (total.reset) break;
				total.reset = true;
				continue;
			}
			if (!page.has_more) break;
		}
		this.synced = true;
		return total;
	}

	visible(): FramePlacement[] {
		return [...this.placements.values()].filter((p) => p.visible);
	}
}
