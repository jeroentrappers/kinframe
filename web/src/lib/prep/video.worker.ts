/// <reference lib="webworker" />
/**
 * Video master preparation (video-master-v1) with WebCodecs via mediabunny: H.264 ≤ 1080p, ≤ 30 fps, bitrate
 * w·h·3.5 capped at 8 Mbps, AAC or no audio, trim applied, rotation baked in, no metadata, faststart MP4.
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
import { AUDIO_BITRATE, videoBitrate, videoFrameRate, videoTarget } from './plan';

export interface VideoRequest {
	id: number;
	file: Blob;
	/** Seconds; null keeps the whole clip. */
	trim: { start: number; end: number } | null;
	includeAudio: boolean;
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
			capturedAt: string | null;
	  }
	| { id: number; type: 'error'; error: string };

const post = (m: VideoResponse) => (self as unknown as Worker).postMessage(m);

async function prepare(req: VideoRequest): Promise<VideoResponse> {
	if (typeof VideoEncoder === 'undefined') throw new Error('no WebCodecs');
	const input = new Input({ source: new BlobSource(req.file), formats: ALL_FORMATS });
	try {
		const track = await input.getPrimaryVideoTrack();
		if (!track) throw new Error('no video track');
		const size = videoTarget(track.displayWidth, track.displayHeight);
		const bitrate = videoBitrate(size);
		let fps: number | null = null;
		try {
			fps = (await track.computePacketStats(120)).averagePacketRate;
		} catch {
			/* unknown */
		}
		const frameRate = videoFrameRate(fps);
		if (!(await canEncodeVideo('avc', { ...size, bitrate, frameRate: frameRate ?? fps ?? 30 })))
			throw new Error('no H.264 encoder');
		const audioTrack = req.includeAudio ? await input.getPrimaryAudioTrack() : null;
		const aacConfig = { numberOfChannels: audioTrack ? Math.min(2, audioTrack.numberOfChannels) : 2, sampleRate: 48000, bitrate: AUDIO_BITRATE };
		if (audioTrack && !(await canEncodeAudio('aac', aacConfig))) {
			// Chrome on Linux has no native AAC encoder: load the WASM one (only then, it is large).
			const { registerAacEncoder } = await import('@mediabunny/aac-encoder');
			registerAacEncoder();
		}
		const keepAudio = audioTrack !== null && (await canEncodeAudio('aac', aacConfig));
		if (audioTrack && !keepAudio) throw new Error('no AAC encoder'); // keep the sound: upload the original instead

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
			capturedAt
		};
	} finally {
		input.dispose();
	}
}

self.onmessage = async (e: MessageEvent<VideoRequest>) => {
	try {
		post(await prepare(e.data));
	} catch (err) {
		post({ id: e.data.id, type: 'error', error: String(err) });
	}
};
