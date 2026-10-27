import { api, call, ApiError, type FrameSettings } from './api/client';
import type { JsonObject } from './logic';
import { applyInstanceOp, instancesPatch, type FramePlugins, type InstanceOp, type PluginInstance } from './plugins';

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

/**
 * Save one plugin instance change (docs/plugins.md §6.1). `instances` is an array, so the patch carries the whole
 * list from the version read; on 412 the change is applied again to the latest list and sent once more ("reload and
 * try again"), so someone else's instances are never overwritten. Never throws: the result says where the instance went in
 * the list that was sent last, so a 422's `plugins.instances.N…` fields can be mapped back to the form.
 */
export async function savePluginInstance(
	frameId: string,
	read: { version: number; instances: PluginInstance[] },
	op: InstanceOp
): Promise<{ ok: true; settings: FrameSettings; index: number } | { ok: false; error: unknown; index: number }> {
	let { version, instances } = read;
	for (let attempt = 0; ; attempt++) {
		const next = applyInstanceOp(instances, op);
		try {
			const settings = await call(
				api.PATCH('/v1/frames/{frameId}/settings', {
					params: { path: { frameId }, header: { 'If-Match': `"${version}"` } },
					body: instancesPatch(next.instances) as never,
					headers: { 'Content-Type': 'application/merge-patch+json' }
				})
			);
			return { ok: true, settings, index: next.index };
		} catch (e) {
			if (attempt > 0 || !(e instanceof ApiError && e.code === 'version_conflict')) return { ok: false, error: e, index: next.index };
			try {
				const latest = await getFrameSettings(frameId);
				version = latest.version;
				instances = latest.plugins?.instances ?? [];
			} catch (e2) {
				return { ok: false, error: e2, index: next.index };
			}
		}
	}
}

export function getFramePlugins(frameId: string): Promise<FramePlugins> {
	return call(api.GET('/v1/frames/{frameId}/plugins', { params: { path: { frameId } } }));
}
