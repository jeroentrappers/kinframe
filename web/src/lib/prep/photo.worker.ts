/// <reference lib="webworker" />
import { captureTime } from './exif';
import { browserPhotoDeps, preparePhoto } from './photo';

export interface PhotoRequest {
	id: number;
	file: Blob;
}

export type PhotoResponse =
	| { id: number; ok: true; blob: Blob; mime: 'image/webp' | 'image/jpeg'; width: number; height: number; capturedAt: string | null }
	| { id: number; ok: false; error: string; capturedAt: string | null };

self.onmessage = async (e: MessageEvent<PhotoRequest>) => {
	const { id, file } = e.data;
	const capturedAt = await captureTime(file);
	try {
		const p = await preparePhoto(file, browserPhotoDeps);
		(self as unknown as Worker).postMessage({ id, ok: true, capturedAt, ...p } satisfies PhotoResponse);
	} catch (err) {
		(self as unknown as Worker).postMessage({ id, ok: false, capturedAt, error: String(err) } satisfies PhotoResponse);
	}
};
