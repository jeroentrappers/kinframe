<script lang="ts">
	import type { VoiceClip } from '$lib/api/client';
	import { clock, waveformBars } from '$lib/chat';
	import { mediaObjectUrl } from '$lib/media';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/** A voice message: play/pause, the waveform with progress, and seeking (README §5.19). */
	let { clip, mine = false }: { clip: VoiceClip; mine?: boolean } = $props();

	let audio: HTMLAudioElement | undefined = $state();
	let src = $state<string | null>(null);
	let playing = $state(false);
	let position = $state(0);
	let failed = $state(false);
	let loading = $state(false);
	const duration = $derived((clip.duration_ms ?? 0) / 1000);
	const bars = $derived(clip.waveform?.length ? waveformBars(clip.waveform.map((v) => v / 100), 40) : Array.from({ length: 40 }, () => 18));
	const progress = $derived(duration > 0 ? Math.min(1, position / duration) : 0);

	async function toggle() {
		if (clip.status !== 'ready') return;
		if (!src) {
			loading = true;
			try {
				src = await mediaObjectUrl(clip.item_id, 'master');
			} catch {
				failed = true;
				return;
			} finally {
				loading = false;
			}
			await new Promise((r) => setTimeout(r));
		}
		if (!audio) return;
		if (audio.paused) {
			// One voice message at a time.
			for (const a of document.querySelectorAll<HTMLAudioElement>('audio[data-voice]')) if (a !== audio) a.pause();
			await audio.play().catch(() => (failed = true));
		} else audio.pause();
	}

	function seekTo(fraction: number) {
		if (!audio || !duration) return;
		audio.currentTime = Math.max(0, Math.min(duration, fraction * duration));
		position = audio.currentTime;
	}

	function onKey(e: KeyboardEvent) {
		if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
			e.preventDefault();
			seekTo((position + (e.key === 'ArrowRight' ? 5 : -5)) / (duration || 1));
		}
	}
</script>

<div class="voice" class:mine>
	<button class="play" onclick={toggle} disabled={clip.status !== 'ready'} aria-label={playing ? t('chat.pause') : t('chat.playVoice')}>
		{#if loading}<span class="spinner" aria-hidden="true"></span>{:else if clip.status === 'processing'}<Icon name="clock" size={18} />{:else}<Icon name={playing ? 'pause' : 'play'} size={18} stroke={playing ? 1.9 : 0} />{/if}
	</button>
	<div
		class="wave"
		role="slider"
		tabindex={clip.status === 'ready' && src ? 0 : -1}
		aria-label={t('chat.voicePosition')}
		aria-valuemin={0}
		aria-valuemax={Math.round(duration)}
		aria-valuenow={Math.round(position)}
		aria-valuetext={`${clock(position * 1000)} / ${clock(duration * 1000)}`}
		onkeydown={onKey}
		onpointerdown={(e) => {
			const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
			const x = getComputedStyle(e.currentTarget as HTMLElement).direction === 'rtl' ? r.right - e.clientX : e.clientX - r.left;
			if (src) seekTo(x / r.width);
		}}
	>
		{#each bars as b, i (i)}<span class="bar" class:done={i / bars.length < progress} style="height:{Math.max(12, b)}%"></span>{/each}
	</div>
	<span class="time num">{clip.status === 'processing' ? t('chat.processing') : failed || clip.status === 'failed' ? t('chat.voiceFailed') : clock((playing || position ? position : duration) * 1000)}</span>
	{#if src}
		<audio
			bind:this={audio}
			data-voice
			{src}
			preload="auto"
			onplay={() => (playing = true)}
			onpause={() => (playing = false)}
			onended={() => {
				playing = false;
				position = 0;
			}}
			ontimeupdate={() => audio && (position = audio.currentTime)}
		></audio>
	{/if}
</div>

<style>
	.voice {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-width: 220px;
		max-width: 320px;
		padding: 4px 0;
	}
	.play {
		flex: none;
		width: 38px;
		height: 38px;
		border-radius: 50%;
		border: 0;
		display: grid;
		place-items: center;
		background: var(--primary);
		color: var(--on-primary);
		cursor: pointer;
	}
	.play :global(svg) {
		fill: currentColor;
	}
	.play:disabled {
		opacity: 0.6;
		cursor: default;
	}
	.mine .play {
		background: var(--surface);
		color: var(--primary);
	}
	.wave {
		flex: 1;
		height: 32px;
		display: flex;
		align-items: center;
		gap: 2px;
		cursor: pointer;
		border-radius: 4px;
	}
	.wave:focus-visible {
		outline: 2px solid var(--focus);
	}
	.bar {
		flex: 1;
		min-width: 2px;
		border-radius: 2px;
		background: color-mix(in srgb, currentColor 30%, transparent);
	}
	.bar.done {
		background: currentColor;
	}
	.time {
		flex: none;
		font-size: 0.78rem;
		min-width: 34px;
		opacity: 0.8;
	}
</style>
