import { Upload } from 'tus-js-client';
import { ApiError, apiUrl, authHeaders } from './api/client';
import { uploadIdFromUrl } from './logic';

export const CHUNK_SIZE = 4 * 1024 * 1024;
export const PARALLEL_UPLOADS = 3;

export interface UploadHandle {
	/** Resolves with the tus upload id, or an existing item id when the server reports a duplicate. */
	promise: Promise<string>;
	abort(): void;
}

/** Resumable tus 1.0 upload to /v1/uploads (README §5.3). */
export function uploadFile(
	file: File,
	opts: { sha256?: string; onProgress?: (sent: number, total: number) => void }
): UploadHandle {
	let upload: Upload;
	let settled = false;
	let rejectOuter: (e: unknown) => void = () => {};
	const promise = new Promise<string>((resolve, reject) => {
		rejectOuter = reject;
		const metadata: Record<string, string> = {
			filename: file.name,
			filetype: file.type || 'application/octet-stream'
		};
		if (opts.sha256) metadata.sha256 = opts.sha256;

		upload = new Upload(file, {
			endpoint: new URL(apiUrl('/v1/uploads'), location.href).href,
			chunkSize: CHUNK_SIZE,
			retryDelays: [0, 1000, 3000, 5000, 10000],
			metadata,
			removeFingerprintOnSuccess: true,
			async onBeforeRequest(req) {
				for (const [k, v] of Object.entries(await authHeaders())) req.setHeader(k, v);
			},
			onAfterResponse(req, res) {
				// Same file already sent by this user: skip the bytes, reuse the item.
				if (req.getMethod() === 'POST') {
					const existing = res.getHeader('Kinframe-Existing-Item');
					if (existing && !settled) {
						settled = true;
						opts.onProgress?.(file.size, file.size);
						resolve(existing);
						void upload.abort(false);
					}
				}
			},
			onProgress(sent, total) {
				opts.onProgress?.(sent, total);
			},
			onSuccess() {
				if (settled) return;
				settled = true;
				resolve(uploadIdFromUrl(upload.url ?? ''));
			},
			onError(err) {
				if (settled) return;
				settled = true;
				const status = (err as { originalResponse?: { getStatus(): number } }).originalResponse?.getStatus?.() ?? 0;
				if (status === 413) reject(new ApiError(413, 'upload_too_large', 'Upload too large'));
				else if (status === 401) reject(new ApiError(401, 'unauthorized', 'Unauthorized'));
				else reject(new ApiError(status, status ? 'server_error' : 'network', err.message));
			},
			onShouldRetry(err) {
				const status = (err as { originalResponse?: { getStatus(): number } }).originalResponse?.getStatus?.() ?? 0;
				return status === 0 || status === 409 || status === 423 || status >= 500;
			}
		});

		upload
			.findPreviousUploads()
			.then((previous) => {
				if (previous[0]) upload.resumeFromPreviousUpload(previous[0]);
				upload.start();
			})
			.catch(() => upload.start());
	});
	return {
		promise,
		abort() {
			if (settled) return;
			settled = true;
			void upload?.abort(true);
			rejectOuter(new DOMException('Upload cancelled', 'AbortError'));
		}
	};
}
