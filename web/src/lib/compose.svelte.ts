import { api, call, type Send } from './api/client';
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
import { defaultTrim, keptSeconds, trimForApi, type Trim } from './parity';
import { PARALLEL_UPLOADS, uploadFile, type UploadHandle } from './upload';

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
	uploaded: number;
	state: 'idle' | 'uploading' | 'done' | 'error';
	uploadId: string | null;
	error: unknown;
}

let seq = 0;

export type Phase = 'compose' | 'uploading' | 'sending';

class Compose {
	items = $state<DraftItem[]>([]);
	frameIds = $state<string[]>([]);
	phase = $state<Phase>('compose');
	error = $state<unknown>(null);

	#handles = new Map<string, UploadHandle>();
	#sendKey: string | null = null;
	#lastBody = '';

	get progress() {
		return overallProgress(this.items.map((i) => ({ bytes: i.file.size, uploaded: i.uploaded })));
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
		const { accepted, unsupported, overLimit } = acceptFiles(files, this.items.length, limits.max_items_per_send);
		const fresh: DraftItem[] = accepted.map((file) => {
			const kind = mediaKindOf(file)!;
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
				uploaded: 0,
				state: 'idle',
				uploadId: null,
				error: null
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
		} else {
			const size = await probeImage(item.previewUrl);
			this.#patch(key, (i) => {
				i.width = size?.width ?? null;
				i.height = size?.height ?? null;
				i.previewable = size !== null;
			});
		}
	}

	/** Re-evaluate an item's issue after its trim changed. */
	recheck(key: string) {
		this.#patch(key, (i) => {
			i.issue = itemIssue({ kind: i.kind, bytes: i.file.size, durationS: keptSeconds(i.trim, i.durationS) }, discovery.limits);
		});
	}

	#patch(key: string, fn: (i: DraftItem) => void) {
		const item = this.items.find((i) => i.key === key);
		if (item) fn(item);
	}

	remove(key: string) {
		this.#handles.get(key)?.abort();
		this.#handles.delete(key);
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

	/** Upload everything not yet uploaded (3 in parallel), then create the send. */
	async send(): Promise<Send | null> {
		if (!this.canSend) return null;
		this.error = null;
		this.phase = 'uploading';
		const todo = this.items.filter((i) => !i.uploadId);
		const results = await runPool(todo, PARALLEL_UPLOADS, async (item) => {
			if (this.phase !== 'uploading' || !this.items.some((i) => i.key === item.key)) return; // removed or cancelled
			this.#patch(item.key, (i) => {
				i.state = 'uploading';
				i.error = null;
			});
			const sha256 = await sha256Hex(item.file).catch(() => undefined);
			const handle = uploadFile(item.file, {
				sha256,
				onProgress: (sent) => this.#patch(item.key, (i) => (i.uploaded = sent))
			});
			this.#handles.set(item.key, handle);
			try {
				const id = await handle.promise;
				this.#patch(item.key, (i) => {
					i.uploadId = id;
					i.state = 'done';
					i.uploaded = i.file.size;
				});
			} catch (e) {
				this.#patch(item.key, (i) => {
					i.state = 'error';
					i.error = e;
				});
				throw e;
			} finally {
				this.#handles.delete(item.key);
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
			items: this.items.map((i) => ({
				upload_id: i.uploadId!,
				caption: i.caption.trim() || null,
				fit: i.fit,
				focal_point: i.focal,
				...(i.kind === 'video' ? { trim: trimForApi(i.trim, i.durationS), include_audio: i.includeAudio } : {}),
				captured_at: i.file.lastModified ? new Date(i.file.lastModified).toISOString() : null
			}))
		};
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
		for (const i of this.items) {
			if (i.state === 'uploading') {
				i.state = 'idle';
				i.uploaded = 0;
			}
		}
	}

	reset() {
		for (const i of this.items) URL.revokeObjectURL(i.previewUrl);
		this.items = [];
		this.phase = 'compose';
		this.error = null;
		this.#sendKey = null;
		this.#lastBody = '';
	}
}

export const compose = new Compose();
