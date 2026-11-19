// Leaving a family (docs/spec/leaving.md, README §5.21): pure helpers for the leave sheet, keep requests and memories.
import type { Schemas } from './api/client';

export type LeavePreview = Schemas['LeavePreview'];
export type TakeAwayResult = Schemas['TakeAwayResult'];
export type FormerPlaces = Schemas['FormerPlaces'];
export type TakenAwayGroup = Schemas['TakenAwayGroup'];
export type KeepRequest = Schemas['KeepRequest'];
export type KeepRequestStatus = Schemas['KeepRequestStatus'];
export type MemoryPreferences = Schemas['MemoryPreferences'];
export type PhotosChoice = Schemas['PhotosChoice'];
export type CommentsChoice = Schemas['CommentsChoice'];

export interface LeaveChoice {
	photos: PhotosChoice;
	comments: CommentsChoice;
	/** Frames to leave in the same request ("Leave this frame too"). */
	frames: string[];
}

/**
 * The sheet's defaults (decision A): photos and comments stay; frames that get this family's photos and that the
 * person doesn't own are checked (`suggested`), frames they own can't be left here.
 */
export function defaultChoice(preview: Pick<LeavePreview, 'frames'> | null): LeaveChoice {
	const frames = (preview?.frames ?? []).filter((f) => f.suggested && f.can_leave !== false).map((f) => f.frame_id);
	return { photos: 'keep', comments: 'keep', frames };
}

/** Body of `leaveFeed`: always with `photos` (new servers then answer 200 with a TakeAwayResult). */
export function leaveFeedBody(c: LeaveChoice, preview: Pick<LeavePreview, 'frames'> | null): Schemas['LeaveFeedRequest'] {
	const allowed = new Set((preview?.frames ?? []).filter((f) => f.can_leave !== false).map((f) => f.frame_id));
	const frames = c.frames.filter((id) => allowed.has(id));
	return { photos: c.photos, comments: c.comments, ...(frames.length ? { leave_frame_ids: frames } : {}) };
}

/** "Your photos" section: only when there is something to choose about. */
export function hasPhotoChoice(p: Pick<LeavePreview, 'photos' | 'frames'>): boolean {
	return p.photos > 0 || (p.frames ?? []).some((f) => (f.direct_photos ?? 0) > 0);
}

/** The line after leaving or taking away, as message parts the component renders (keys and params). */
export function resultParts(r: TakeAwayResult | null, frameNames: (ids: string[]) => string[]) {
	const out: { key: 'taken' | 'remote' | 'comments' | 'frames' | 'nothing'; n?: number; until?: string | null; names?: string[] }[] = [];
	if (!r) return out;
	if (r.photos_taken_away > 0) out.push({ key: 'taken', n: r.photos_taken_away, until: r.until ?? null });
	if ((r.remote_frames ?? 0) > 0) out.push({ key: 'remote', n: r.remote_frames! });
	if (r.comments_deleted > 0) out.push({ key: 'comments', n: r.comments_deleted });
	const left = frameNames(r.left_frame_ids ?? []);
	if (left.length) out.push({ key: 'frames', names: left });
	return out;
}

/** Former places as one list, families first, then frames, each by name. */
export function formerList(fp: FormerPlaces | null): { kind: 'feed' | 'frame'; id: string; name: string; photos: number; left_at: string | null }[] {
	if (!fp) return [];
	const feeds = fp.feeds.map((f) => ({ kind: 'feed' as const, id: f.feed_id, name: f.name, photos: f.photos, left_at: f.left_at ?? null }));
	const frames = fp.frames.map((f) => ({ kind: 'frame' as const, id: f.frame_id, name: f.name, photos: f.photos, left_at: f.left_at ?? null }));
	const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name);
	return [...feeds.sort(byName), ...frames.sort(byName)].filter((x) => x.photos > 0);
}

/** Where a keep request is: the frame's or the family's name. */
export function requestPlace(r: Pick<KeepRequest, 'scope' | 'frame_name' | 'feed_name'>): string {
	return (r.scope === 'frame' ? r.frame_name : r.feed_name) ?? '';
}

export type KeepAnswer = 'all' | 'some' | 'none';

/** The answer body; `some` needs at least one picked item of the request (else null: not sendable). */
export function answerBody(allow: KeepAnswer, picked: Iterable<string>, requested: string[]): Schemas['KeepRequestAnswer'] | null {
	if (allow !== 'some') return { allow };
	const want = new Set(requested);
	const ids = [...new Set(picked)].filter((id) => want.has(id));
	if (ids.length === 0) return null;
	// Picking every photo is "allow all".
	if (requested.length > 0 && ids.length === want.size) return { allow: 'all' };
	return { allow: 'some', item_ids: ids };
}

/** The ask body: `item_ids` only when some (not all) were picked. */
export function askBody(
	scope: { frameId?: string; feedId?: string },
	takingId: string,
	picked: string[] | null,
	all: number,
	note: string
): Schemas['KeepRequestCreate'] {
	const n = note.trim().slice(0, 300);
	return {
		taking_id: takingId,
		...(scope.frameId ? { frame_id: scope.frameId } : { feed_id: scope.feedId }),
		...(picked && picked.length > 0 && picked.length < all ? { item_ids: picked } : {}),
		...(n ? { note: n } : {})
	};
}

/** Open requests first (soonest to expire first), then the rest newest first. */
export function sortRequests(list: KeepRequest[]): KeepRequest[] {
	return [...list].sort((a, b) => {
		const ao = a.status === 'open' ? 0 : 1;
		const bo = b.status === 'open' ? 0 : 1;
		if (ao !== bo) return ao - bo;
		if (ao === 0) return a.expires_at.localeCompare(b.expires_at);
		return b.asked_at.localeCompare(a.asked_at);
	});
}

/** Replace or add a request (websocket `keep_request.updated`). */
export function upsertRequest(list: KeepRequest[], r: KeepRequest): KeepRequest[] {
	return list.some((x) => x.id === r.id) ? list.map((x) => (x.id === r.id ? r : x)) : [r, ...list];
}

/** Memories: the update body for adding or removing a person or memories (PUT replaces the lists it carries). */
export function withPerson(prefs: MemoryPreferences, userId: string, hidden: boolean): Schemas['MemoryPreferencesUpdate'] {
	const ids = prefs.hidden_people.map((p) => p.user_id).filter((id) => id !== userId);
	return { hidden_people: hidden ? [...ids, userId] : ids };
}

export function withMemories(prefs: Pick<MemoryPreferences, 'hidden_memories'>, postIds: string[], hidden: boolean): Schemas['MemoryPreferencesUpdate'] {
	const drop = new Set(postIds);
	const rest = prefs.hidden_memories.filter((id) => !drop.has(id));
	return { hidden_memories: hidden ? [...rest, ...postIds.filter((id, i) => postIds.indexOf(id) === i)] : rest };
}

/** The other person of a direct chat, for "You can no longer message Lies". */
export function readOnlyName(conv: Pick<Schemas['Conversation'], 'other' | 'members' | 'name'>, meId: string | undefined): string {
	if (conv.other?.display_name) return conv.other.display_name;
	const other = conv.members?.find((m) => m.person.user_id !== meId);
	return other?.person.display_name ?? conv.name ?? '';
}
