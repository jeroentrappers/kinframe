/// <reference lib="webworker" />
/**
 * Video master preparation (video-master-v1) with WebCodecs via mediabunny: H.264 ≤ 1080p, ≤ 30 fps, bitrate
 * w·h·3.5 capped at 8 Mbps, AAC or no audio, trim applied, rotation baked in, no metadata, faststart MP4.
 *
 * A `probe` request only reads the container (no WebCodecs needed): the displayed size, the frame rate and whether
 * this browser can convert the file, with or without its sound. The composer uses it to decide early whether the
 * original may go up as it is (plan.ts videoWithinLimits) and to warn when the sound will be dropped.
 */
import {
	ALL_FORMATS,
	BlobSource,
	BufferTarget,
	Conversion,
	Input,
	Mp4OutputFormat,
	Output,
	canEncodeAudio,
	canEncodeVideo
} from 'mediabunny';
import type { InputAudioTrack } from 'mediabunny';
import { AUDIO_BITRATE, videoBitrate, videoFrameRate, videoTarget } from './plan';

export interface VideoRequest {
	id: number;
	type?: 'prepare';
	file: Blob;
	/** Seconds; null keeps the whole clip. */
	trim: { start: number; end: number } | null;
	includeAudio: boolean;
}

export interface VideoProbeRequest {
	id: number;
	type: 'probe';
	file: Blob;
}

export interface VideoInfo {
	/** Displayed size (rotation applied). */
	width: number;
	height: number;
	/** Average frame rate, null when unknown. */
	fps: number | null;
	hasAudio: boolean;
	/** WebCodecs can decode the video and encode H.264 at the target size. */
	canPrepare: boolean;
	/** The sound can be kept (decodable, and an AAC encoder exists); false means it would be sent muted. */
	canKeepAudio: boolean;
}

export type VideoResponse =
	| { id: number; type: 'progress'; progress: number }
	| {
			id: number;
			type: 'done';
			blob: Blob;
			width: number;
			height: number;
			durationMs: number;
			hasAudio: boolean;
			/** The sound was asked for but this browser can't encode it: the master is muted. */
			audioDropped: boolean;
			capturedAt: string | null;
	  }
	| { id: number; type: 'probe'; info: VideoInfo }
	| { id: number; type: 'error'; error: string };

const post = (m: VideoResponse) => (self as unknown as Worker).postMessage(m);

/** Whether the sound of `track` can go into the master: decodable, and AAC can be encoded. */
async function audioKeepable(track: InputAudioTrack): Promise<boolean> {
	if (typeof AudioDecoder === 'undefined' || !(await track.canDecode().catch(() => false))) return false;
	const aac = { numberOfChannels: Math.min(2, track.numberOfChannels), sampleRate: 48000, bitrate: AUDIO_BITRATE };
	if (await canEncodeAudio('aac', aac)) return true;
	try {
		// Chrome on Linux has no native AAC encoder: load the WASM one (only then, it is large).
		const { registerAacEncoder } = await import('@mediabunny/aac-encoder');
		registerAacEncoder();
		return await canEncodeAudio('aac', aac);
	} catch {
		return false;
	}
}

async function averageFps(track: { computePacketStats(n: number): Promise<{ averagePacketRate: number }> }): Promise<number | null> {
	try {
		const fps = (await track.computePacketStats(120)).averagePacketRate;
		return Number.isFinite(fps) && fps > 0 ? fps : null;
	} catch {
		return null;
	}
}

async function probe(req: VideoProbeRequest): Promise<VideoResponse> {
	const input = new Input({ source: new BlobSource(req.file), formats: ALL_FORMATS });
	try {
		const track = await input.getPrimaryVideoTrack();
		if (!track) throw new Error('no video track');
		const fps = await averageFps(track);
		const size = videoTarget(track.displayWidth, track.displayHeight);
		const frameRateOut = videoFrameRate(fps);
		const webcodecs = typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined';
		const canPrepare =
			webcodecs &&
			(await track.canDecode().catch(() => false)) &&
			(await canEncodeVideo('avc', { ...size, bitrate: videoBitrate(size), frameRate: frameRateOut ?? fps ?? 30 }).catch(() => false));
		const audio = await input.getPrimaryAudioTrack();
		return {
			id: req.id,
			type: 'probe',
			info: {
				width: track.displayWidth,
				height: track.displayHeight,
				fps,
				hasAudio: audio !== null,
				canPrepare,
				canKeepAudio: audio !== null && canPrepare && (await audioKeepable(audio))
			}
		};
	} finally {
		input.dispose();
	}
}

async function prepare(req: VideoRequest): Promise<VideoResponse> {
	if (typeof VideoEncoder === 'undefined') throw new Error('no WebCodecs');
	const input = new Input({ source: new BlobSource(req.file), formats: ALL_FORMATS });
	try {
		const track = await input.getPrimaryVideoTrack();
		if (!track) throw new Error('no video track');
		const size = videoTarget(track.displayWidth, track.displayHeight);
		const bitrate = videoBitrate(size);
		const fps = await averageFps(track);
		const frameRate = videoFrameRate(fps);
		if (!(await canEncodeVideo('avc', { ...size, bitrate, frameRate: frameRate ?? fps ?? 30 })))
			throw new Error('no H.264 encoder');
		const audioTrack = req.includeAudio ? await input.getPrimaryAudioTrack() : null;
		// No way to keep the sound (no AAC encoder, or a codec this browser can't decode): the master is muted, and
		// the composer has already told the user (probe).
		const keepAudio = audioTrack !== null && (await audioKeepable(audioTrack));

		let capturedAt: string | null = null;
		try {
			capturedAt = (await input.getMetadataTags()).date?.toISOString() ?? null;
		} catch {
			/* none */
		}
		const duration = await input.computeDuration();
		const start = req.trim ? Math.max(0, req.trim.start) : 0;
		const end = req.trim ? Math.min(duration, req.trim.end) : duration;

		const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() });
		const conversion = await Conversion.init({
			input,
			output,
			tracks: 'primary',
			trim: req.trim ? { start, end } : undefined,
			video: {
				codec: 'avc',
				width: size.width,
				height: size.height,
				fit: 'fill',
				bitrate,
				frameRate,
				keyFrameInterval: 2,
				allowTransformationMetadata: false,
				forceTranscode: true
			},
			audio: keepAudio
				? { codec: 'aac', numberOfChannels: Math.min(2, audioTrack!.numberOfChannels), bitrate: AUDIO_BITRATE, forceTranscode: true }
				: { discard: true },
			tags: {},
			showWarnings: false
		});
		if (!conversion.isValid) throw new Error('conversion not possible');
		conversion.onProgress = (progress) => post({ id: req.id, type: 'progress', progress });
		await conversion.execute();
		const buf = (output.target as BufferTarget).buffer;
		if (!buf) throw new Error('empty output');
		return {
			id: req.id,
			type: 'done',
			blob: new Blob([buf], { type: 'video/mp4' }),
			width: size.width,
			height: size.height,
			durationMs: Math.round((end - start) * 1000),
			hasAudio: keepAudio,
			audioDropped: audioTrack !== null && !keepAudio,
			capturedAt
		};
	} finally {
		input.dispose();
	}
}

self.onmessage = async (e: MessageEvent<VideoRequest | VideoProbeRequest>) => {
	try {
		post(e.data.type === 'probe' ? await probe(e.data) : await prepare(e.data));
	} catch (err) {
		post({ id: e.data.id, type: 'error', error: String(err) });
	}
};
