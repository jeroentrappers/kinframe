<script lang="ts">
	// Full-screen "TV mode": a slideshow for a smart-TV browser, a laptop on the TV, or the
	// receiving end of a Presentation API session. No sign-in: the link's token is the credential.
	import { onMount } from 'svelte';
	import { fade } from 'svelte/transition';
	import { page } from '$app/state';
	import { ApiError } from '$lib/api/client';
	import { reducedMotion } from '$lib/motion';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { dwellMs, keepPlace, needsRefresh, parseCommand, slideByline, step, type TvCommand, type TvSlide, type TvSlideshow, type TvState } from '$lib/tv';
	import { fetchSlideshow } from '$lib/tvCast.svelte';
	import Icon from '$components/Icon.svelte';
	import Logo from '$components/Logo.svelte';

	const token = $derived(page.url.searchParams.get('t') ?? '');
	const frameId = $derived(page.params.frameId ?? '');

	let show = $state<TvSlideshow | null>(null);
	let slides = $state<TvSlide[]>([]);
	let index = $state(0);
	let playing = $state(true);
	let interval = $state(10);
	let failure = $state<'expired' | 'invalid' | null>(null);
	let loading = $state(true);
	let muted = $state(true);
	let chrome = $state(true);
	let now = $state(new Date());

	const cur = $derived(slides[index]);
	let timer: ReturnType<typeof setTimeout> | null = null;
	let chromeTimer: ReturnType<typeof setTimeout> | null = null;
	let refreshTimer: ReturnType<typeof setTimeout> | null = null;
	const connections: PresentationConnection[] = [];

	async function load(first = false) {
		try {
			const s = await fetchSlideshow(token);
			if (s.frame_id !== frameId) throw new ApiError(404, 'not_found', 'Wrong frame');
			const prev = slides[index];
			show = s;
			slides = s.slides;
			index = keepPlace(prev, s.slides, index);
			if (first) {
				const i = Number(page.url.searchParams.get('i'));
				interval = Number.isFinite(i) && i >= 3 && i <= 600 ? i : s.interval_seconds;
			}
			failure = null;
			scheduleRefresh();
			if (first) schedule();
		} catch (e) {
			if (e instanceof ApiError && (e.status === 404 || e.status === 410)) {
				failure = e.status === 410 ? 'expired' : 'invalid';
				stopTimer();
			} else if (first) {
				failure = 'invalid';
			} else {
				// A blip: keep showing what we have and try again soon.
				refreshTimer = setTimeout(() => load(), 60_000);
			}
		} finally {
			loading = false;
		}
	}

	function scheduleRefresh() {
		if (refreshTimer) clearTimeout(refreshTimer);
		if (!show) return;
		const wait = Math.max(30_000, Date.parse(show.refresh_after) - Date.now());
		refreshTimer = setTimeout(() => load(), Number.isFinite(wait) ? wait : 45 * 60_000);
	}

	function stopTimer() {
		if (timer) clearTimeout(timer);
		timer = null;
	}

	function schedule() {
		stopTimer();
		if (!playing || slides.length < 2) return;
		// Videos move on when they end; the timer is a safety net for ones that stall.
		const ms = cur?.kind === 'video' ? dwellMs(cur, interval) + 10_000 : dwellMs(cur, interval);
		timer = setTimeout(() => go(1), ms);
	}

	function go(dir: 1 | -1) {
		if (slides.length === 0) return;
		index = step(index, slides.length, dir);
		if (show && needsRefresh(show)) void load();
		schedule();
		preload();
		broadcast();
	}

	function preload() {
		const n = slides[step(index, slides.length, 1)];
		if (n?.kind === 'photo') new Image().src = n.url;
	}

	function toggle() {
		playing = !playing;
		const v = document.querySelector<HTMLVideoElement>('video.slide');
		if (v) void (playing ? v.play().catch(() => {}) : v.pause());
		schedule();
		broadcast();
	}

	function apply(c: TvCommand) {
		if (c.cmd === 'next') go(1);
		else if (c.cmd === 'prev') go(-1);
		else if (c.cmd === 'play' && !playing) toggle();
		else if (c.cmd === 'pause' && playing) toggle();
		else if (c.cmd === 'interval') {
			interval = c.seconds;
			schedule();
			broadcast();
		} else if (c.cmd === 'stop') window.close();
	}

	function broadcast() {
		const st: TvState = { type: 'state', index, total: slides.length, playing, interval, caption: cur?.caption ?? null, placement_id: cur?.placement_id ?? null };
		for (const c of connections) if (c.state === 'connected') c.send(JSON.stringify(st));
	}

	function poke() {
		chrome = true;
		if (chromeTimer) clearTimeout(chromeTimer);
		chromeTimer = setTimeout(() => (chrome = false), 4000);
	}

	function onKey(e: KeyboardEvent) {
		poke();
		if (e.key === 'ArrowRight' || e.key === 'MediaTrackNext') go(1);
		else if (e.key === 'ArrowLeft' || e.key === 'MediaTrackPrevious') go(-1);
		else if (e.key === 'Enter' || e.key === ' ' || e.key === 'MediaPlayPause') toggle();
		else if (e.key === 'f') void fullscreen();
		else return;
		e.preventDefault();
	}

	async function fullscreen() {
		try {
			if (document.fullscreenElement) await document.exitFullscreen();
			else await document.documentElement.requestFullscreen();
		} catch {
			/* not allowed here */
		}
	}

	function videoEnded() {
		if (playing) go(1);
	}

	onMount(() => {
		void load(true);
		poke();
		const clock = setInterval(() => (now = new Date()), 15_000);
		// Keep the TV awake while the slideshow runs.
		let lock: { release: () => Promise<void> } | null = null;
		const wake = async () => {
			try {
				lock = await (navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }).wakeLock?.request('screen') ?? null;
			} catch {
				/* not supported */
			}
		};
		void wake();
		const vis = () => document.visibilityState === 'visible' && void wake();
		document.addEventListener('visibilitychange', vis);
		// Presentation API receiver: commands from the phone or laptop that started the show.
		const receiver = (navigator as Navigator & { presentation?: { receiver?: { connectionList: Promise<PresentationConnectionList> } } }).presentation?.receiver;
		const add = (c: PresentationConnection) => {
			connections.push(c);
			c.addEventListener('message', (e: Event) => {
				const cmd = parseCommand((e as MessageEvent).data);
				if (cmd) apply(cmd);
			});
			broadcast();
		};
		receiver?.connectionList.then((list) => {
			list.connections.forEach(add);
			list.addEventListener('connectionavailable', (e: Event) => add((e as PresentationConnectionAvailableEvent).connection));
		});
		return () => {
			stopTimer();
			if (refreshTimer) clearTimeout(refreshTimer);
			if (chromeTimer) clearTimeout(chromeTimer);
			clearInterval(clock);
			document.removeEventListener('visibilitychange', vis);
			void lock?.release().catch(() => {});
		};
	});

	const time = $derived(new Intl.DateTimeFormat(i18n.tag, { hour: '2-digit', minute: '2-digit' }).format(now));
	const dur = reducedMotion() ? 0 : 900;
</script>

<svelte:head><title>{show ? t('tv.pageTitle', { frame: show.frame_name }) : 'kinframe'}</title></svelte:head>
<svelte:window onkeydown={onKey} onpointermove={poke} />

<!-- Taps only reveal the controls; keys are handled on the window. -->
<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
<main class="tv" class:hide-cursor={!chrome} onclick={poke}>
	{#if loading}
		<div class="center"><Logo size={56} /></div>
	{:else if failure}
		<div class="center notice">
			<Logo size={44} />
			<h1>{failure === 'expired' ? t('tv.expiredTitle') : t('tv.invalidTitle')}</h1>
			<p>{failure === 'expired' ? t('tv.expiredBody') : t('tv.invalidBody')}</p>
		</div>
	{:else if !cur}
		<div class="center notice">
			<Logo size={44} />
			<h1>{t('tv.emptyTitle')}</h1>
			<p>{t('tv.emptyBody', { frame: show?.frame_name ?? '' })}</p>
		</div>
	{:else}
		{#key cur.placement_id + index}
			<div class="layer" in:fade={{ duration: dur }} out:fade={{ duration: dur }}>
				{#if cur.kind === 'video'}
					<!-- svelte-ignore a11y_media_has_caption -->
					<video class="slide" src={cur.url} poster={cur.poster_url ?? undefined} autoplay playsinline {muted} onended={videoEnded}></video>
				{:else}
					<img class="backdrop" src={cur.url} alt="" aria-hidden="true" />
					<img class="slide" src={cur.url} alt={cur.caption ?? ''} />
				{/if}
			</div>
		{/key}

		<div class="overlay" class:dim={!chrome}>
			<div class="top">
				<span class="frame-name">{show?.frame_name}</span>
				<span class="clock num">{time}</span>
			</div>
			<div class="bottom">
				<div class="caption">
					{#if cur.caption}<p class="cap">{cur.caption}</p>{/if}
					<p class="by">{slideByline(cur, i18n.tag)}</p>
				</div>
				{#if !playing}<span class="paused"><Icon name="pause" size={16} />{t('tv.paused')}</span>{/if}
			</div>
		</div>

		<div class="controls" class:shown={chrome} role="toolbar" aria-label={show?.frame_name}>
			<button onclick={() => go(-1)} aria-label={t('tv.prev')}><Icon name="prev" size={22} /></button>
			<button onclick={toggle} aria-label={playing ? t('tv.pause') : t('tv.play')}><Icon name={playing ? 'pause' : 'play'} size={24} stroke={playing ? 2 : 0} /></button>
			<button onclick={() => go(1)} aria-label={t('tv.next')}><Icon name="next" size={22} /></button>
			{#if cur.kind === 'video'}
				<button onclick={() => (muted = !muted)} aria-label={t('tv.tapForSound')} aria-pressed={!muted}><Icon name={muted ? 'volumeOff' : 'volume'} size={22} /></button>
			{/if}
			<button onclick={fullscreen} aria-label={t('tv.fullscreen')}><Icon name="maximize" size={22} /></button>
			<span class="keys">{t('tv.keysHint')}</span>
		</div>
	{/if}
</main>

<style>
	.tv {
		position: fixed;
		inset: 0;
		background: #0b0a09;
		color: #f6f1ea;
		overflow: hidden;
		user-select: none;
	}
	.hide-cursor {
		cursor: none;
	}
	.center {
		position: absolute;
		inset: 0;
		display: grid;
		place-content: center;
		justify-items: center;
		gap: 12px;
		text-align: center;
		padding: 24px;
	}
	.notice h1 {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.6rem, 3.5vw, 2.6rem);
		margin: 12px 0 0;
	}
	.notice p {
		max-width: 40ch;
		opacity: 0.8;
		font-size: clamp(1rem, 1.8vw, 1.3rem);
	}
	.layer {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
	}
	.backdrop {
		position: absolute;
		inset: -40px;
		width: calc(100% + 80px);
		height: calc(100% + 80px);
		object-fit: cover;
		filter: blur(36px) brightness(0.55) saturate(1.1);
	}
	.slide {
		position: relative;
		max-width: 100%;
		max-height: 100%;
		width: 100%;
		height: 100%;
		object-fit: contain;
	}
	.overlay {
		position: absolute;
		inset: 0;
		display: flex;
		flex-direction: column;
		justify-content: space-between;
		padding: clamp(16px, 3vw, 40px) clamp(20px, 4vw, 56px);
		pointer-events: none;
		transition: opacity 600ms ease;
	}
	.top {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		text-shadow: 0 1px 8px rgb(0 0 0 / 0.6);
		font-weight: 600;
		opacity: 0.85;
		font-size: clamp(0.9rem, 1.4vw, 1.2rem);
	}
	.clock {
		font-size: clamp(1.2rem, 2.2vw, 1.9rem);
	}
	.overlay.dim .top {
		opacity: 0.55;
	}
	.bottom {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 16px;
	}
	.caption {
		max-width: min(70ch, 70%);
		padding: 14px 18px;
		border-radius: 16px;
		background: linear-gradient(to top, rgb(0 0 0 / 0.55), rgb(0 0 0 / 0.25));
		backdrop-filter: blur(6px);
	}
	.caption p {
		margin: 0;
	}
	.cap {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.2rem, 2.4vw, 2rem);
		line-height: 1.25;
	}
	.by {
		opacity: 0.8;
		font-size: clamp(0.85rem, 1.3vw, 1.1rem);
		margin-top: 4px !important;
	}
	.paused {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 8px 14px;
		border-radius: 999px;
		background: rgb(0 0 0 / 0.5);
		font-weight: 600;
	}
	.controls {
		position: absolute;
		left: 50%;
		top: clamp(12px, 2.5vw, 32px);
		transform: translate(-50%, -12px);
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 6px;
		border-radius: 999px;
		background: rgb(20 18 16 / 0.72);
		backdrop-filter: blur(10px);
		opacity: 0;
		pointer-events: none;
		transition:
			opacity 300ms ease,
			transform 300ms ease;
	}
	.controls.shown {
		opacity: 1;
		transform: translate(-50%, 0);
		pointer-events: auto;
	}
	.controls button {
		display: grid;
		place-items: center;
		width: 52px;
		height: 52px;
		border-radius: 999px;
		border: 0;
		background: transparent;
		color: inherit;
		cursor: pointer;
	}
	.controls button:hover,
	.controls button:focus-visible {
		background: rgb(255 255 255 / 0.14);
		outline: none;
	}
	.keys {
		padding: 0 12px 0 6px;
		font-size: 0.85rem;
		opacity: 0.7;
		white-space: nowrap;
	}
	@media (max-width: 640px) {
		.keys {
			display: none;
		}
		.caption {
			max-width: 100%;
		}
		.top {
			padding-top: 64px;
		}
	}
</style>
