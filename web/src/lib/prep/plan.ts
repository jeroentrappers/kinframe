/**
 * Client-side preparation rules (docs/api/README.md §5.3 "Prepared uploads"). The server verifies whatever we
 * send and falls back to its own pipeline, so these only need to aim at the profile, not be perfect.
 *
 * The web app never uploads a full original: a file goes up as it is only when it is already within the master
 * limits (photoWithinLimits, videoWithinLimits). Anything else is prepared in the browser, or not sent at all.
 */

export const PHOTO_PROFILE = 'photo-master-v1';
export const VIDEO_PROFILE = 'video-master-v1';
export type PrepProfile = typeof PHOTO_PROFILE | typeof VIDEO_PROFILE;

/** Long edge of a photo master. */
export const MASTER_PHOTO_EDGE = 2560;
/** Video masters fit 1920×1080 in either orientation. */
export const MASTER_VIDEO_BOX = { long: 1920, short: 1080 };
export const MAX_VIDEO_FPS = 30;
export const MAX_VIDEO_BITRATE = 8_000_000;
export const AUDIO_BITRATE = 128_000;
/** Live/Motion Photo clips longer than this are cut by the server; we cut them ourselves. */
export const MAX_MOTION_S = 10;
/** Bigger originals are read in full only up to this size (Motion Photo detection). */
export const MAX_MOTION_PHOTO_BYTES = 64 * 1024 * 1024;

export interface Size {
	width: number;
	height: number;
}

/** Scale (never up) so the long edge is at most `maxLong`. */
export function photoTarget(w: number, h: number, maxLong = MASTER_PHOTO_EDGE): Size {
	const scale = Math.min(1, maxLong / Math.max(w, h));
	return { width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale)) };
}

/** Scale (never up) into 1920×1080 in either orientation, with even dimensions (H.264 4:2:0). */
export function videoTarget(w: number, h: number): Size {
	const long = Math.max(w, h);
	const short = Math.min(w, h);
	const scale = Math.min(1, MASTER_VIDEO_BOX.long / long, MASTER_VIDEO_BOX.short / short);
	const even = (v: number) => Math.max(2, Math.round(v * scale) - (Math.round(v * scale) % 2));
	return { width: even(w), height: even(h) };
}

/** w·h·3.5 bits per second, capped at 8 Mbps: under the server's frame-rendition budget (w·h·4), so frames reuse it. */
export function videoBitrate(size: Size): number {
	return Math.min(Math.round(size.width * size.height * 3.5), MAX_VIDEO_BITRATE);
}

/** Output frame rate: keep ≤30 fps sources as they are, halve anything faster to 30. */
export function videoFrameRate(sourceFps: number | null): number | undefined {
	return sourceFps && sourceFps > MAX_VIDEO_FPS + 0.5 ? MAX_VIDEO_FPS : undefined;
}

/**
 * Whether a prepared photo is worth uploading instead of the original: it must be smaller (a small JPEG can grow
 * when re-encoded; the server then does the work itself). Only asked for originals within the limits.
 */
export function usePrepared(originalBytes: number, preparedBytes: number): boolean {
	return preparedBytes > 0 && preparedBytes < originalBytes;
}

/** Formats the server accepts as a photo master (by magic bytes, §5.3). */
export const MASTER_PHOTO_FORMATS: readonly string[] = ['jpeg', 'webp'];

/** An original photo may go up as it is: a JPEG or WebP whose long edge is at most 2560 px. */
export function photoWithinLimits(info: { format: string | null; width: number | null; height: number | null } | null): boolean {
	if (!info?.format || !MASTER_PHOTO_FORMATS.includes(info.format) || !info.width || !info.height) return false;
	return Math.max(info.width, info.height) <= MASTER_PHOTO_EDGE;
}

/** An original video may go up as it is: at most 1080p (either orientation) and 30 fps. */
export function videoWithinLimits(info: { width: number; height: number; fps: number | null } | null): boolean {
	if (!info || !info.width || !info.height || info.fps === null) return false;
	const long = Math.max(info.width, info.height);
	const short = Math.min(info.width, info.height);
	return long <= MASTER_VIDEO_BOX.long && short <= MASTER_VIDEO_BOX.short && info.fps <= MAX_VIDEO_FPS + 0.5;
}

/** What to upload for a photo: the prepared master, the original (only within the limits), or nothing (an error). */
export function choosePhotoUpload(originalBytes: number, preparedBytes: number | null, originalWithinLimits: boolean): 'prepared' | 'original' | 'error' {
	if (preparedBytes === null) return originalWithinLimits ? 'original' : 'error';
	return originalWithinLimits && !usePrepared(originalBytes, preparedBytes) ? 'original' : 'prepared';
}

/** What to upload for a video (or a Live/Motion clip): the same rule without the size comparison. */
export function chooseVideoUpload(prepared: boolean, originalWithinLimits: boolean): 'prepared' | 'original' | 'error' {
	if (prepared) return 'prepared';
	return originalWithinLimits ? 'original' : 'error';
}

/** File name for a prepared file: same base name, new extension. */
export function preparedName(name: string, ext: string): string {
	const dot = name.lastIndexOf('.');
	return `${dot > 0 ? name.slice(0, dot) : name}.${ext}`;
}
