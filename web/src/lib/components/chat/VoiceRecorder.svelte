<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { clock, pickAudioMime, recordingFile, rmsLevel, waveformBars } from '$lib/chat';
	import { discovery } from '$lib/discovery.svelte';
	import { uploadFile } from '$lib/upload';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/**
	 * Record a voice message with MediaRecorder (Opus in WebM on Chrome and Firefox, AAC in MP4 on Safari; the
	 * server stores AAC, README §5.19), show the live waveform, then upload it with tus.
	 */
	let {
		onsend,
		oncancel,
		onactivity
	}: {
		onsend: (v: { upload_id: string; duration_ms: number }) => Promise<void>;
		oncancel: () => void;
		onactivity?: () => void;
	} = $props();

	type Phase = 'starting' | 'recording' | 'review' | 'uploading' | 'error';
	let phase = $state<Phase>('starting');
	let errorKey = $state<'denied' | 'unsupported' | 'failed' | 'tooShort'>('failed');
	let elapsed = $state(0);
	let levels = $state<number[]>([]);
	let blob: Blob | null = null;
	let mime = '';
	let previewUrl = $state<string | null>(null);
	let progress = $state(0);

	let stream: MediaStream | null = null;
	let recorder: MediaRecorder | null = null;
	let ctx: AudioContext | null = null;
	let timer: ReturnType<typeof setInterval> | null = null;
	let started = 0;
	let abortUpload: (() => void) | null = null;

	const max = $derived(discovery.limits.max_voice_seconds);
	const bars = $derived(waveformBars(levels.slice(-120), 40));

	onMount(() => void start());
	onDestroy(cleanup);

	async function start() {
		const picked = typeof MediaRecorder !== 'undefined' ? pickAudioMime((m) => MediaRecorder.isTypeSupported(m)) : null;
		if (!picked || !navigator.mediaDevices?.getUserMedia) {
			errorKey = 'unsupported';
			phase = 'error';
			return;
		}
		mime = picked;
		try {
			stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
		} catch {
			errorKey = 'denied';
			phase = 'error';
			return;
		}
		const chunks: Blob[] = [];
		try {
			recorder = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 64_000 });
		} catch {
			recorder = new MediaRecorder(stream);
			mime = recorder.mimeType || mime;
		}
		recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
		recorder.onstop = () => {
			blob = new Blob(chunks, { type: recordingFile(mime).type });
			previewUrl = URL.createObjectURL(blob);
			stopStream();
			if (elapsed < 1000) {
				errorKey = 'tooShort';
				phase = 'error';
			} else phase = 'review';
		};
		// Live levels for the waveform.
		try {
			ctx = new AudioContext();
			const analyser = ctx.createAnalyser();
			analyser.fftSize = 1024;
			ctx.createMediaStreamSource(stream).connect(analyser);
			const buf = new Uint8Array(analyser.fftSize);
			timer = setInterval(() => {
				analyser.getByteTimeDomainData(buf);
				levels = [...levels, rmsLevel(buf)];
				tick();
			}, 100);
		} catch {
			timer = setInterval(tick, 100);
		}
		started = performance.now();
		recorder.start(1000);
		phase = 'recording';
		onactivity?.();
	}

	let lastActivity = 0;
	function tick() {
		elapsed = performance.now() - started;
		if (elapsed - lastActivity > 3000) {
			lastActivity = elapsed;
			onactivity?.();
		}
		if (elapsed >= max * 1000) stop();
	}

	function stop() {
		if (timer) clearInterval(timer);
		timer = null;
		if (recorder?.state === 'recording') recorder.stop();
	}

	function stopStream() {
		for (const tr of stream?.getTracks() ?? []) tr.stop();
		stream = null;
		void ctx?.close().catch(() => {});
		ctx = null;
	}

	function cleanup() {
		if (timer) clearInterval(timer);
		if (recorder?.state === 'recording') {
			recorder.onstop = null;
			recorder.stop();
		}
		stopStream();
		abortUpload?.();
		if (previewUrl) URL.revokeObjectURL(previewUrl);
	}

	function cancel() {
		cleanup();
		oncancel();
	}

	async function send() {
		if (!blob) return;
		phase = 'uploading';
		const f = recordingFile(mime);
		const file = new File([blob], f.name, { type: f.type });
		const handle = uploadFile(file, { onProgress: (s, total) => (progress = total ? s / total : 0) });
		abortUpload = handle.abort;
		try {
			const id = await handle.promise;
			abortUpload = null;
			await onsend({ upload_id: id, duration_ms: Math.round(elapsed) });
			cleanup();
		} catch {
			errorKey = 'failed';
			phase = 'error';
		}
	}
</script>

<div class="recorder" role="group" aria-label={t('chat.voiceMessage')}>
	<button class="btn btn-ghost btn-icon" onclick={cancel} aria-label={t('chat.discardVoice')}><Icon name="trash" size={18} /></button>
	{#if phase === 'error'}
		<p class="err small" role="alert">{t(`chat.voiceError.${errorKey}`)}</p>
	{:else if phase === 'review' && previewUrl}
		<!-- svelte-ignore a11y_media_has_caption -->
		<audio class="preview" src={previewUrl} controls></audio>
		<span class="time num">{clock(elapsed)}</span>
	{:else}
		<span class="rec-dot" class:on={phase === 'recording'} aria-hidden="true"></span>
		<div class="live" aria-hidden="true">
			{#each bars as b, i (i)}<span style="height:{Math.max(8, b)}%"></span>{/each}
		</div>
		<span class="time num" role="timer" aria-live="off">{clock(elapsed)} / {clock(max * 1000)}</span>
	{/if}
	{#if phase === 'recording'}
		<button class="btn btn-primary btn-icon" onclick={stop} aria-label={t('chat.stopRecording')}><Icon name="pause" size={18} /></button>
	{:else if phase === 'review'}
		<button class="btn btn-primary btn-icon" onclick={send} aria-label={t('chat.sendVoice')}><Icon name="send" size={18} /></button>
	{:else if phase === 'uploading'}
		<span class="up small num" role="status"><span class="spinner" aria-hidden="true"></span>{Math.round(progress * 100)}%</span>
	{/if}
</div>

<style>
	.recorder {
		flex: 1;
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-height: 44px;
		min-width: 0;
	}
	.rec-dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--text-faint);
		flex: none;
	}
	.rec-dot.on {
		background: var(--danger);
		animation: blink 1.2s ease-in-out infinite;
	}
	@keyframes blink {
		50% {
			opacity: 0.35;
		}
	}
	.live {
		flex: 1;
		height: 30px;
		display: flex;
		align-items: center;
		gap: 2px;
		color: var(--primary);
		min-width: 0;
	}
	.live span {
		flex: 1;
		min-width: 2px;
		border-radius: 2px;
		background: currentColor;
		opacity: 0.75;
	}
	.time {
		flex: none;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.preview {
		flex: 1;
		min-width: 0;
		height: 36px;
	}
	.err {
		flex: 1;
		margin: 0;
		color: var(--danger);
	}
	.up {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
</style>
