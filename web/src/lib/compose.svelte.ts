import { api, call, type Schemas, type Send } from './api/client';
import { discovery } from './discovery.svelte';
import { CAPTION_MAX, graphemeLength } from './format';
import {
	acceptFiles,
	itemIssue,
	mediaKindOf,
	newIdempotencyKey,
	overallProgress,
	runPool,
	type Focal,
	type ItemIssue
} from './logic';
import { probeImage, probeVideo, sha256Hex } from './media';
import { defaultTrim, isTrimmed, keptSeconds, trimForApi, type Trim } from './parity';
import { canPrepareVideo, preparePhotoFile, prepareVideoFile, type PreparedFile, type VideoJob } from './prep/client';
import { pairLive } from './prep/live';
import { splitMotionPhotoFile } from './prep/motionPhoto';
import { MAX_MOTION_PHOTO_BYTES, MAX_MOTION_S } from './prep/plan';
import { sendItemBody } from './prep/sendItem';
import { PARALLEL_UPLOADS, uploadFile, type UploadHandle } from './upload';

/** The motion part of a Live Photo (iPhone export pair) or a Motion Photo split in the browser. */
export interface LiveClip {
	file: File;
	/** Split out of an Android Motion Photo (the item's `file` is then the still part). */
	split: boolean;
	prepared: PreparedFile | null;
	uploadId: string | null;
}

export interface DraftItem {
	key: string;
	file: File;
	kind: 'photo' | 'video';
	previewUrl: string;
	/** null: browser can't render this format (e.g. HEIC on Chrome). */
	previewable: boolean;
	width: number | null;
	height: number | null;
	durationS: number | null;
	/** Selected part of a video (seconds); null keeps the whole clip. */
	trim: Trim | null;
	includeAudio: boolean;
	caption: string;
	fit: 'fill' | 'fit';
	focal: Focal;
	issue: ItemIssue | null;
	/** Bytes this item uploads (the prepared files once known, else the originals). */
	bytes: number;
	uploaded: number;
	state: 'idle' | 'preparing' | 'uploading' | 'done' | 'error';
	/** 0..1 while `state` is preparing. */
	prepProgress: number;
	uploadId: string | null;
	error: unknown;
	/** Live Photo clip, if any; `sendLive` false sends the still only. */
	live: LiveClip | null;
	sendLive: boolean;
	/** The client-made master, when preparation worked (photos are prepared right after adding). */
	prepared: PreparedFile | null;
	/** Settings the prepared video was made with; a change re-prepares it. */
	preparedFor: string | null;
	capturedAt: string | null;
}

let seq = 0;

export type Phase = 'compose' | 'uploading' | 'sending';

class Compose {
	items = $state<DraftItem[]>([]);
	frameIds = $state<string[]>([]);
	phase = $state<Phase>('compose');
	error = $state<unknown>(null);

	#handles = new Map<string, UploadHandle>();
	#videoJobs = new Map<string, VideoJob>();
	#photoPrep = new Map<string, Promise<void>>();
	#sendKey: string | null = null;
	#lastBody = '';

	get progress() {
		return overallProgress(this.items.map((i) => ({ bytes: i.bytes, uploaded: i.uploaded })));
	}

	get uploadedCount() {
		return this.items.filter((i) => i.state === 'done').length;
	}

	get issues() {
		return this.items.filter((i) => i.issue !== null);
	}

	get captionsTooLong() {
		return this.items.some((i) => graphemeLength(i.caption) > CAPTION_MAX);
	}

	get canSend() {
		return (
			this.phase === 'compose' &&
			this.items.length > 0 &&
			this.frameIds.length > 0 &&
			this.issues.length === 0 &&
			!this.captionsTooLong
		);
	}

	/** Add files; returns what was skipped so the page can explain it. */
	add(files: File[]): { unsupported: number; overLimit: number } {
		const limits = discovery.limits;
		// iPhone exports: IMG_1234.HEIC + IMG_1234.MOV become one Live Photo item.
		const entries = pairLive(files);
		const clips = new Map(entries.filter((e) => e.clip).map((e) => [e.file, e.clip!]));
		const { accepted, unsupported, overLimit } = acceptFiles(
			entries.map((e) => e.file),
			this.items.length,
			limits.max_items_per_send
		);
		const fresh: DraftItem[] = accepted.map((file) => {
			const kind = mediaKindOf(file)!;
			const clip = kind === 'photo' ? clips.get(file) : undefined;
			return {
				key: `d${++seq}`,
				file,
				kind,
				previewUrl: URL.createObjectURL(file),
				previewable: true,
				width: null,
				height: null,
				durationS: null,
				trim: null,
				includeAudio: true,
				caption: '',
				fit: 'fill',
				focal: { x: 0.5, y: 0.5 },
				issue: itemIssue({ kind, bytes: file.size }, limits),
				bytes: file.size + (clip?.size ?? 0),
				uploaded: 0,
				state: 'idle',
				prepProgress: 0,
				uploadId: null,
				error: null,
				live: clip ? { file: clip, split: false, prepared: null, uploadId: null } : null,
				sendLive: true,
				prepared: null,
				preparedFor: null,
				capturedAt: null
			};
		});
		this.items = [...this.items, ...fresh];
		for (const item of fresh) void this.#probe(item.key);
		return { unsupported: unsupported.length, overLimit: overLimit.length };
	}

	async #probe(key: string) {
		const item = this.items.find((i) => i.key === key);
		if (!item) return;
		if (item.kind === 'video') {
			const meta = await probeVideo(item.file);
			this.#patch(key, (i) => {
				i.durationS = meta?.duration ?? null;
				i.width = meta?.width ?? null;
				i.height = meta?.height ?? null;
				i.previewable = meta !== null;
				// Too long for the server: start with its first allowed stretch selected.
				if (i.durationS != null && i.durationS > discovery.limits.max_video_seconds)
					i.trim = defaultTrim(i.durationS, discovery.limits.max_video_seconds);
				this.recheck(key);
			});
			return;
		}
		const size = await probeImage(item.previewUrl);
		this.#patch(key, (i) => {
			i.width = size?.width ?? null;
			i.height = size?.height ?? null;
			i.previewable = size !== null;
		});
		if (!item.live) {
			// An Android Motion Photo: the clip is appended to the JPEG; split it here.
			const parts = await splitMotionPhotoFile(item.file, MAX_MOTION_PHOTO_BYTES).catch(() => null);
			if (parts)
				this.#patch(key, (i) => {
					i.file = parts.still;
					i.live = { file: parts.clip, split: true, prepared: null, uploadId: null };
					i.bytes = parts.still.size + parts.clip.size;
				});
		} else {
			// A same-name video that is too long to be a Live Photo motion is a video of its own.
			const meta = await probeVideo(item.live.file);
			if (meta && meta.duration > MAX_MOTION_S + 0.5) {
				const clip = item.live.file;
				this.#patch(key, (i) => {
					i.live = null;
					i.bytes = i.file.size;
				});
				this.add([clip]);
			}
		}
		this.#preparePhoto(key);
	}

	/** Photos are prepared in the background right away (in a worker), so sending is quick. */
	#preparePhoto(key: string): Promise<void> {
		let p = this.#photoPrep.get(key);
		if (!p) {
			const item = this.items.find((i) => i.key === key);
			if (!item) return Promise.resolve();
			p = preparePhotoFile(item.file).then(({ prepared, capturedAt }) =>
				this.#patch(key, (i) => {
					i.prepared = prepared;
					i.capturedAt = capturedAt;
					i.bytes = (prepared?.file.size ?? i.file.size) + (i.live && i.sendLive ? (i.live.prepared?.file.size ?? i.live.file.size) : 0);
				})
			);
			this.#photoPrep.set(key, p);
		}
		return p;
	}

	/** Re-evaluate an item's issue after its trim changed. */
	recheck(key: string) {
		this.#patch(key, (i) => {
			i.issue = itemIssue({ kind: i.kind, bytes: i.file.size, durationS: keptSeconds(i.trim, i.durationS) }, discovery.limits);
		});
	}

	setSendLive(key: string, on: boolean) {
		this.#patch(key, (i) => {
			if (!i.live) return;
			i.sendLive = on;
			const clip = i.live.prepared?.file.size ?? i.live.file.size;
			i.bytes = (i.prepared?.file.size ?? i.file.size) + (on ? clip : 0);
		});
	}

	#patch(key: string, fn: (i: DraftItem) => void) {
		const item = this.items.find((i) => i.key === key);
		if (item) fn(item);
	}

	remove(key: string) {
		this.#handles.get(key)?.abort();
		this.#handles.delete(key);
		this.#videoJobs.get(key)?.cancel();
		this.#photoPrep.delete(key);
		const item = this.items.find((i) => i.key === key);
		if (item) URL.revokeObjectURL(item.previewUrl);
		this.items = this.items.filter((i) => i.key !== key);
	}

	applyCaptionToAll(caption: string) {
		for (const i of this.items) i.caption = caption;
	}

	toggleFrame(id: string) {
		this.frameIds = this.frameIds.includes(id) ? this.frameIds.filter((f) => f !== id) : [...this.frameIds, id];
	}

	/** Makes the item's video master in a worker (WebCodecs), or leaves `prepared` null to upload the original. */
	async #prepareVideo(key: string) {
		const item = this.items.find((i) => i.key === key);
		if (!item || !canPrepareVideo()) return;
		const trim = item.trim && item.durationS != null && isTrimmed(item.trim, item.durationS) ? { start: item.trim.start, end: item.trim.end } : null;
		const settings = JSON.stringify({ trim, audio: item.includeAudio });
		if (item.preparedFor === settings) return;
		this.#patch(key, (i) => {
			i.state = 'preparing';
			i.prepProgress = 0;
		});
		const job = prepareVideoFile(item.file, {
			trim,
			includeAudio: item.includeAudio,
			onProgress: (p) => this.#patch(key, (i) => (i.prepProgress = p))
		});
		this.#videoJobs.set(key, job);
		const prepared = await job.promise;
		this.#videoJobs.delete(key);
		this.#patch(key, (i) => {
			i.prepared = prepared;
			i.preparedFor = prepared ? settings : null;
			if (prepared?.capturedAt) i.capturedAt = prepared.capturedAt;
			i.bytes = prepared?.file.size ?? i.file.size;
		});
	}

	/** Prepares the motion clip of a Live Photo (muted, ≤ 10 s). */
	async #prepareClip(key: string) {
		const item = this.items.find((i) => i.key === key);
		if (!item?.live || item.live.prepared || !canPrepareVideo()) return;
		const meta = await probeVideo(item.live.file);
		const trim = meta && meta.duration > MAX_MOTION_S ? { start: 0, end: MAX_MOTION_S } : null;
		const job = prepareVideoFile(item.live.file, { trim, includeAudio: false });
		this.#videoJobs.set(key, job);
		const prepared = await job.promise;
		this.#videoJobs.delete(key);
		this.#patch(key, (i) => {
			if (!i.live) return;
			i.live.prepared = prepared;
			i.bytes = (i.prepared?.file.size ?? i.file.size) + (prepared?.file.size ?? i.live.file.size);
		});
	}

	async #upload(key: string, file: File, opts: { sha256: boolean; prepared?: string; offset: number }): Promise<string> {
		const sha256 = opts.sha256 ? await sha256Hex(file).catch(() => undefined) : undefined;
		const handle = uploadFile(file, {
			sha256,
			prepared: opts.prepared,
			onProgress: (sent) => this.#patch(key, (i) => (i.uploaded = opts.offset + sent))
		});
		this.#handles.set(key, handle);
		try {
			return await handle.promise;
		} finally {
			this.#handles.delete(key);
		}
	}

	/** Prepare and upload everything not yet uploaded (3 in parallel), then create the send. */
	async send(): Promise<Send | null> {
		if (!this.canSend) return null;
		this.error = null;
		this.phase = 'uploading';
		const todo = this.items.filter((i) => !i.uploadId || (i.live && i.sendLive && !i.live.uploadId));
		const results = await runPool(todo, PARALLEL_UPLOADS, async (item) => {
			const alive = () => this.phase === 'uploading' && this.items.some((i) => i.key === item.key);
			if (!alive()) return; // removed or cancelled
			this.#patch(item.key, (i) => {
				i.state = 'preparing';
				i.error = null;
			});
			if (item.kind === 'video') await this.#prepareVideo(item.key);
			else await this.#preparePhoto(item.key);
			if (item.live && item.sendLive) await this.#prepareClip(item.key);
			if (!alive()) return;
			this.#patch(item.key, (i) => (i.state = 'uploading'));
			try {
				const cur = this.items.find((i) => i.key === item.key)!;
				const main = cur.prepared?.file ?? cur.file;
				if (!cur.uploadId) {
					const id = await this.#upload(item.key, main, { sha256: true, prepared: cur.prepared?.profile, offset: 0 });
					this.#patch(item.key, (i) => (i.uploadId = id));
				}
				if (cur.live && cur.sendLive && !cur.live.uploadId) {
					const clip = cur.live.prepared?.file ?? cur.live.file;
					const id = await this.#upload(item.key, clip, { sha256: false, prepared: cur.live.prepared?.profile, offset: main.size });
					this.#patch(item.key, (i) => i.live && (i.live.uploadId = id));
				}
				this.#patch(item.key, (i) => {
					i.state = 'done';
					i.uploaded = i.bytes;
				});
			} catch (e) {
				this.#patch(item.key, (i) => {
					i.state = 'error';
					i.error = e;
				});
				throw e;
			}
		});

		if (this.phase !== 'uploading') return null; // cancelled
		const failed = results.find((r) => r.status === 'rejected');
		if (failed && failed.status === 'rejected') {
			this.error = failed.reason;
			this.phase = 'compose';
			return null;
		}

		this.phase = 'sending';
		const body = {
			frame_ids: [...this.frameIds],
			items: this.items.map(
				(i) =>
					sendItemBody({
					kind: i.kind,
					uploadId: i.uploadId!,
					caption: i.caption.trim() || null,
					fit: i.fit,
					focal: i.focal,
					trim: trimForApi(i.trim, i.durationS),
					includeAudio: i.includeAudio,
					prepared: i.prepared,
					capturedAt: i.capturedAt ?? (i.file.lastModified ? new Date(i.file.lastModified).toISOString() : null),
					hasLive: i.live !== null,
					liveUploadId: i.live && i.sendLive ? i.live.uploadId : null
				}) as Schemas['SendCreate']['items'][number]
			)
		} as Schemas['SendCreate'];
		const serialized = JSON.stringify(body);
		if (!this.#sendKey || serialized !== this.#lastBody) this.#sendKey = newIdempotencyKey();
		this.#lastBody = serialized;
		try {
			const send = await call(
				api.POST('/v1/sends', { body, params: { header: { 'Idempotency-Key': this.#sendKey } } })
			);
			this.reset();
			return send;
		} catch (e) {
			this.error = e;
			this.phase = 'compose';
			return null;
		}
	}

	cancel() {
		this.phase = 'compose';
		for (const h of this.#handles.values()) h.abort();
		this.#handles.clear();
		for (const j of this.#videoJobs.values()) j.cancel();
		this.#videoJobs.clear();
		for (const i of this.items) {
			if (i.state === 'uploading' || i.state === 'preparing') {
				i.state = 'idle';
				i.uploaded = 0;
			}
		}
	}

	reset() {
		for (const j of this.#videoJobs.values()) j.cancel();
		this.#videoJobs.clear();
		this.#photoPrep.clear();
		for (const i of this.items) URL.revokeObjectURL(i.previewUrl);
		this.items = [];
		this.phase = 'compose';
		this.error = null;
		this.#sendKey = null;
		this.#lastBody = '';
	}
}

export const compose = new Compose();
