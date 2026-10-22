<script lang="ts" module>
	import { filmstripTimes } from '$lib/parity';

	// Filmstrips are extracted once per file and kept for the page's lifetime.
	const strips = new Map<string, Promise<string[]>>();

	/** Grab `n` small JPEG frames from a local video with <video> + <canvas>. */
	function filmstrip(src: string, duration: number, n: number): Promise<string[]> {
		const key = `${src}#${n}`;
		let p = strips.get(key);
		if (!p) {
			p = extract(src, duration, n);
			strips.set(key, p);
		}
		return p;
	}

	async function extract(src: string, duration: number, n: number): Promise<string[]> {
		const v = document.createElement('video');
		v.muted = true;
		v.playsInline = true;
		v.preload = 'auto';
		v.src = src;
		await new Promise<void>((resolve, reject) => {
			v.onloadeddata = () => resolve();
			v.onerror = () => reject(new Error('decode'));
			setTimeout(() => reject(new Error('timeout')), 15_000);
		});
		const h = 72;
		const w = Math.max(1, Math.round((v.videoWidth / Math.max(1, v.videoHeight)) * h));
		const canvas = document.createElement('canvas');
		canvas.width = w;
		canvas.height = h;
		const ctx = canvas.getContext('2d');
		const out: string[] = [];
		for (const t of filmstripTimes(duration, n)) {
			await new Promise<void>((resolve) => {
				const done = () => resolve();
				v.addEventListener('seeked', done, { once: true });
				setTimeout(done, 3000);
				v.currentTime = t;
			});
			ctx?.drawImage(v, 0, 0, w, h);
			out.push(canvas.toDataURL('image/jpeg', 0.6));
		}
		v.removeAttribute('src');
		v.load();
		return out;
	}
</script>

<script lang="ts">
	import { onDestroy } from 'svelte';
	import type { DraftItem } from '$lib/compose.svelte';
	import { compose } from '$lib/compose.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDuration } from '$lib/format';
	import { isTrimmed, minTrimLength, moveTrim, shiftTrim, type Trim } from '$lib/parity';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';

	let { item }: { item: DraftItem } = $props();

	const FRAMES = 10;
	const duration = $derived(item.durationS ?? 0);
	const maxS = $derived(discovery.limits.max_video_seconds);
	const sel = $derived<Trim>(item.trim ?? { start: 0, end: duration });
	const length = $derived(sel.end - sel.start);
	const trimmed = $derived(isTrimmed(item.trim, item.durationS));
	const pct = (s: number) => (duration > 0 ? (s / duration) * 100 : 0);

	let frames = $state<string[]>([]);
	let stripFailed = $state(false);
	$effect(() => {
		const src = item.previewUrl;
		const d = duration;
		if (!(d > 0)) return;
		let alive = true;
		filmstrip(src, d, FRAMES)
			.then((f) => alive && (frames = f))
			.catch(() => alive && (stripFailed = true));
		return () => {
			alive = false;
		};
	});

	// ---- preview playback inside the selection
	let video: HTMLVideoElement | undefined = $state();
	let playing = $state(false);
	let current = $state(0);

	function togglePlay() {
		if (!video) return;
		if (playing) {
			video.pause();
			return;
		}
		if (video.currentTime < sel.start || video.currentTime >= sel.end - 0.1) video.currentTime = sel.start;
		void video.play().catch(() => (playing = false));
	}

	function onTime() {
		if (!video) return;
		current = video.currentTime;
		if (playing && video.currentTime >= sel.end) {
			video.pause();
			video.currentTime = sel.start;
		}
	}

	function seek(s: number) {
		if (!video) return;
		video.pause();
		video.currentTime = s;
		current = s;
	}

	onDestroy(() => video?.pause());

	// ---- handles
	let track: HTMLDivElement | undefined = $state();
	let drag: { kind: 'start' | 'end' | 'window'; startX: number; from: Trim } | null = $state(null);

	function apply(next: Trim) {
		item.trim = isTrimmed(next, duration) ? next : null;
		compose.recheck(item.key);
	}

	function timeAt(clientX: number) {
		if (!track) return 0;
		const r = track.getBoundingClientRect();
		return ((clientX - r.left) / r.width) * duration;
	}

	function down(e: PointerEvent, kind: 'start' | 'end' | 'window') {
		e.preventDefault();
		(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		(e.currentTarget as HTMLElement).focus();
		drag = { kind, startX: e.clientX, from: { ...sel } };
	}

	function move(e: PointerEvent) {
		if (!drag || !track) return;
		if (drag.kind === 'window') {
			const delta = ((e.clientX - drag.startX) / track.getBoundingClientRect().width) * duration;
			const next = shiftTrim(drag.from, delta, duration);
			apply(next);
			seek(next.start);
		} else {
			const next = moveTrim(sel, drag.kind, timeAt(e.clientX), duration, maxS);
			apply(next);
			seek(drag.kind === 'start' ? next.start : Math.max(next.start, next.end - 0.05));
		}
	}

	function up() {
		drag = null;
	}

	function key(e: KeyboardEvent, handle: 'start' | 'end') {
		const step = e.shiftKey ? 1 : 0.1;
		const value = handle === 'start' ? sel.start : sel.end;
		let next: number | null = null;
		if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') next = value - step;
		else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') next = value + step;
		else if (e.key === 'PageDown') next = value - 5;
		else if (e.key === 'PageUp') next = value + 5;
		else if (e.key === 'Home') next = 0;
		else if (e.key === 'End') next = duration;
		if (next === null) return;
		e.preventDefault();
		const t2 = moveTrim(sel, handle, next, duration, maxS);
		apply(t2);
		seek(handle === 'start' ? t2.start : Math.max(t2.start, t2.end - 0.05));
	}

	const fmt = (s: number) => formatDuration(s);
</script>

<section class="trim" aria-labelledby="trim-title-{item.key}">
	<div class="head">
		<h3 id="trim-title-{item.key}"><Icon name="scissors" size={18} />{t('trim.title')}</h3>
		{#if trimmed}
			<button type="button" class="btn btn-ghost btn-sm" onclick={() => apply({ start: 0, end: duration })} disabled={duration > maxS}>
				{t('trim.reset')}
			</button>
		{/if}
	</div>

	<div class="stage">
		<!-- svelte-ignore a11y_media_has_caption -->
		<video
			bind:this={video}
			src={item.previewUrl}
			playsinline
			preload="metadata"
			muted={!item.includeAudio}
			onplay={() => (playing = true)}
			onpause={() => (playing = false)}
			ontimeupdate={onTime}
			onloadedmetadata={() => video && sel.start > 0 && (video.currentTime = sel.start)}
		></video>
		<button type="button" class="play" onclick={togglePlay} aria-label={playing ? t('trim.pause') : t('trim.play')}>
			<Icon name={playing ? 'pause' : 'play'} size={22} stroke={playing ? 1.9 : 0} />
		</button>
	</div>

	<div class="track-wrap" dir="ltr">
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="track" bind:this={track} onpointermove={move} onpointerup={up} onpointercancel={up}>
			<div class="strip" aria-hidden="true">
				{#if frames.length}
					{#each frames as f, i (i)}<img src={f} alt="" draggable="false" />{/each}
				{:else if !stripFailed}
					{#each Array(FRAMES) as _, i (i)}<span class="skeleton strip-skel"></span>{/each}
				{/if}
			</div>
			<span class="shade" style="left:0;width:{pct(sel.start)}%" aria-hidden="true"></span>
			<span class="shade" style="left:{pct(sel.end)}%;right:0" aria-hidden="true"></span>
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<span
				class="window"
				class:dragging={drag?.kind === 'window'}
				style="left:{pct(sel.start)}%;width:{pct(length)}%"
				onpointerdown={(e) => down(e, 'window')}
			></span>
			{#if current >= sel.start && current <= sel.end && (playing || drag)}
				<span class="playhead" style="left:{pct(current)}%" aria-hidden="true"></span>
			{/if}
			<button
				type="button"
				class="handle start"
				style="left:{pct(sel.start)}%"
				role="slider"
				aria-label={t('trim.startLabel')}
				aria-valuemin={0}
				aria-valuemax={Math.round(duration * 10) / 10}
				aria-valuenow={Math.round(sel.start * 10) / 10}
				aria-valuetext={fmt(sel.start)}
				onpointerdown={(e) => down(e, 'start')}
				onkeydown={(e) => key(e, 'start')}
			><span></span></button>
			<button
				type="button"
				class="handle end"
				style="left:{pct(sel.end)}%"
				role="slider"
				aria-label={t('trim.endLabel')}
				aria-valuemin={0}
				aria-valuemax={Math.round(duration * 10) / 10}
				aria-valuenow={Math.round(sel.end * 10) / 10}
				aria-valuetext={fmt(sel.end)}
				onpointerdown={(e) => down(e, 'end')}
				onkeydown={(e) => key(e, 'end')}
			><span></span></button>
		</div>
		<div class="times num" aria-live="polite">
			<span>{fmt(sel.start)}</span>
			<strong>{t('trim.length', { length: fmt(length), total: fmt(duration) })}</strong>
			<span>{fmt(sel.end)}</span>
		</div>
	</div>

	<p class="hint">
		{duration > maxS
			? t('trim.hintTooLong', { max: fmt(maxS), min: Math.round(minTrimLength(duration)) })
			: t('trim.hint', { min: Math.round(minTrimLength(duration)) })}
	</p>

	<label class="switch">
		<input type="checkbox" bind:checked={item.includeAudio} />
		<span>
			<span class="s-label"><Icon name={item.includeAudio ? 'volume' : 'volumeOff'} size={16} />{t('trim.includeAudio')}</span>
			<span class="hint">{item.includeAudio ? t('trim.audioOnHint') : t('trim.audioOffHint')}</span>
		</span>
	</label>
</section>

<style>
	.trim {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		min-width: 0;
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-2);
		min-height: 36px;
	}
	h3 {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		font-size: 1rem;
	}
	.stage {
		position: relative;
		aspect-ratio: 16 / 9;
		border-radius: var(--r);
		overflow: hidden;
		background: #000;
	}
	video {
		width: 100%;
		height: 100%;
		object-fit: contain;
		display: block;
	}
	.play {
		position: absolute;
		left: 12px;
		bottom: 12px;
		width: 48px;
		height: 48px;
		border-radius: 50%;
		border: 0;
		display: grid;
		place-items: center;
		background: rgb(255 255 255 / 0.92);
		color: #2a221d;
		cursor: pointer;
		box-shadow: var(--shadow-sm);
	}
	.play :global(svg) {
		fill: currentColor;
	}
	.play:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.track-wrap {
		/* Room for the handles at both ends without overflowing narrow dialogs. */
		padding: 0 12px;
	}
	.track {
		position: relative;
		height: 56px;
		touch-action: none;
		user-select: none;
	}
	.strip {
		position: absolute;
		inset: 0;
		display: flex;
		border-radius: 8px;
		overflow: hidden;
		background: var(--surface-3);
	}
	.strip img,
	.strip-skel {
		flex: 1;
		min-width: 0;
		height: 100%;
		object-fit: cover;
		border-radius: 0;
	}
	.shade {
		position: absolute;
		top: 0;
		bottom: 0;
		background: rgb(20 14 10 / 0.62);
		pointer-events: none;
	}
	.shade:first-of-type {
		border-radius: 8px 0 0 8px;
	}
	.window {
		position: absolute;
		top: -3px;
		bottom: -3px;
		border: 3px solid var(--primary);
		border-radius: 8px;
		cursor: grab;
		box-shadow: 0 0 0 1px rgb(0 0 0 / 0.15);
	}
	.window.dragging {
		cursor: grabbing;
	}
	.playhead {
		position: absolute;
		top: -6px;
		bottom: -6px;
		width: 2px;
		margin-left: -1px;
		background: #fff;
		box-shadow: 0 0 0 1px rgb(0 0 0 / 0.4);
		pointer-events: none;
	}
	.handle {
		position: absolute;
		top: -6px;
		bottom: -6px;
		width: 24px;
		margin-left: -12px;
		padding: 0;
		border: 0;
		background: transparent;
		cursor: ew-resize;
		touch-action: none;
		display: grid;
		place-items: center;
		z-index: 1;
	}
	.handle span {
		width: 14px;
		height: 100%;
		border-radius: 6px;
		background: var(--primary);
		box-shadow: var(--shadow-sm);
		position: relative;
	}
	.handle span::after {
		content: '';
		position: absolute;
		left: 50%;
		top: 50%;
		width: 2px;
		height: 18px;
		transform: translate(-50%, -50%);
		border-radius: 1px;
		background: var(--on-primary);
		opacity: 0.85;
	}
	.handle:focus-visible span {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.times {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		gap: var(--s-2);
		margin-top: 10px;
		font-size: 0.82rem;
		color: var(--text-muted);
	}
	.times strong {
		color: var(--text);
		font-weight: 650;
		text-align: center;
	}
	.switch .s-label {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-weight: 600;
	}
	.switch > span {
		display: flex;
		flex-direction: column;
	}
</style>
