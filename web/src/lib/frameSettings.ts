import { api, call, ApiError, type FrameSettings } from './api/client';
import type { JsonObject } from './logic';

export function getFrameSettings(frameId: string): Promise<FrameSettings> {
	return call(api.GET('/v1/frames/{frameId}/settings', { params: { path: { frameId } } }));
}

/**
 * Apply a small merge patch to a frame's settings outside the settings form (e.g. "play this
 * album"). Uses the given version, and on 412 refetches and retries once: the server merges
 * field-wise, so a retry only fails when someone changed the very same field.
 */
export async function patchFrameSettings(frameId: string, patch: JsonObject, version?: number): Promise<FrameSettings> {
	const send = (v: number) =>
		call(
			api.PATCH('/v1/frames/{frameId}/settings', {
				params: { path: { frameId }, header: { 'If-Match': `"${v}"` } },
				body: patch as never,
				headers: { 'Content-Type': 'application/merge-patch+json' }
			})
		);
	const v = version ?? (await getFrameSettings(frameId)).version;
	try {
		return await send(v);
	} catch (e) {
		if (!(e instanceof ApiError && e.code === 'version_conflict')) throw e;
		return send((await getFrameSettings(frameId)).version);
	}
}
