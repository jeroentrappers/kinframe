import type { PrepProfile } from './plan';

/** What the composer knows about an uploaded item when it builds the `POST /v1/sends` item. */
export interface SendItemInput {
	kind: 'photo' | 'video';
	uploadId: string;
	caption: string | null;
	fit: 'fill' | 'fit';
	focal: { x: number; y: number };
	/** Original video trim for the server (only when the upload is not a prepared master). */
	trim: { start_ms: number; end_ms: number } | undefined;
	includeAudio: boolean;
	prepared: { profile: PrepProfile; width: number; height: number; durationMs?: number } | null;
	capturedAt: string | null;
	/** Live/Motion Photo: whether it has a clip at all, and the clip's upload when sent as live. */
	hasLive: boolean;
	liveUploadId: string | null;
}

/** Builds one item of a send (openapi `SendCreate.items`). */
export function sendItemBody(i: SendItemInput) {
	const out: Record<string, unknown> = {
		upload_id: i.uploadId,
		caption: i.caption,
		fit: i.fit,
		focal_point: i.focal
	};
	if (i.kind === 'video') {
		out.include_audio = i.includeAudio;
		// A prepared master already has the trim applied (and the server refuses to trim a stream copy).
		if (!i.prepared && i.trim) out.trim = i.trim;
	}
	if (i.prepared) {
		out.width = i.prepared.width;
		out.height = i.prepared.height;
		if (i.prepared.durationMs) out.duration_ms = i.prepared.durationMs;
	}
	if (i.hasLive) {
		if (i.liveUploadId) out.live_video_upload_id = i.liveUploadId;
		else out.live = false;
	}
	out.captured_at = i.capturedAt;
	return out;
}
