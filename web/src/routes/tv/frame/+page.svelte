<script lang="ts">
	// "Use this screen as a frame": this browser enrolls as a virtual frame (device kind
	// `browser`) and speaks the frame's device protocol. It is claimed with the code it shows,
	// like a frame on the wall, and then shows its library with the frame's settings.
	import { onMount } from 'svelte';
	import { apiUrl } from '$lib/api/client';
	import { DeviceClient, type Reaction } from '$lib/device/client';
	import { enrollInfo, heartbeat } from '$lib/device/browserInfo';
	import { browserStorage, CredentialStore, credentialsKey } from '$lib/device/credentials';
	import { noteMessage, SocialStore } from '$lib/device/social';
	import { FrameEngine, type FrameView } from '$lib/device/engine';
	import type { FramePlacement } from '$lib/device/feed';
	import { DeviceSocket } from '$lib/device/socket';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { matchLocale } from '$lib/i18n/locales';
	import { kenBurnsKeys } from '$lib/kenburns';
	import { withVia } from '$lib/sender';
	import type { StageSlide } from '$lib/tvstage';
	import Icon from '$components/Icon.svelte';
	import Logo from '$components/Logo.svelte';
	import TvOverlay from '$components/tv/TvOverlay.svelte';
	import TvStage from '$components/tv/TvStage.svelte';

	const REACTIONS: Reaction[] = ['love', 'laugh', 'wow', 'sad'];
	const EMOJI: Record<Reaction, string> = { love: '❤️', laugh: '😄', wow: '😮', sad: '😢' };

	let view = $state<FrameView | null>(null);
	let engine: FrameEngine | null = null;
	let client: DeviceClient | null = null;
	let muted = $state(true);
	let reactOpen = $state(false);
	let reacted = $state<Reaction | null>(null);
	let chrome = $state(false);
	let chromeTimer: ReturnType<typeof setTimeout> | null = null;
	let reactButtons: HTMLButtonElement[] = $state([]);
	let askButton: HTMLButtonElement | null = $state(null);
	const started = Date.now();

	const settings = $derived(view?.settings ?? null);
	const shown = $derived(view?.slide ?? null);
	const p = $derived(shown?.placement ?? null);

	function stageSlide(v: FrameView): StageSlide | null {
		const s = v.slide;
		if (!s || !client) return null;
		const pl = s.placement;
		const src = client.mediaUrl(pl.id);
		if (!src) return null;
		const fill = v.settings?.slideshow?.fill ?? true;
		return {
			key: `${pl.id}#${s.seq}`,
			tiles: [
				{
					id: pl.id,
					kind: pl.kind === 'video' ? 'video' : 'photo',
					src,
					poster: pl.hasPoster ? client.mediaUrl(pl.id, 'poster') : null,
					width: pl.width,
					height: pl.height,
					fit: pl.fit ?? (fill ? 'fill' : 'fit'),
					focal: pl.focal,
					rotation: pl.rotation
				}
			],
			transition: s.transition,
			transitionMs: s.transitionMs,
			direction: s.direction,
			startedAt: s.startedAt,
			kenBurns: s.kenBurns ? kenBurnsKeys(pl.id, pl.focal?.x ?? null, pl.focal?.y ?? null) : null,
			holdMs: s.holdMs
		};
	}

	// Rebuild the stage slide only when the slide itself changes (not on every view update).
	let stage = $state<StageSlide | null>(null);
	let stageFor = '';
	$effect(() => {
		const v = view;
		const key = v?.slide ? `${v.slide.placement.id}#${v.slide.seq}` : '';
		if (key !== stageFor) {
			stageFor = key;
			stage = v ? stageSlide(v) : null;
			reactOpen = false;
			reacted = null;
		}
	});

	// The page follows the frame's language, like a frame does.
	$effect(() => {
		const lang = settings?.locale?.language;
		const m = lang ? matchLocale(lang) : null;
		if (m && m !== i18n.locale) void i18n.use(m);
	});

	function byline(pl: FramePlacement): string {
		const which = settings?.slideshow?.caption_date ?? 'received';
		const when = which === 'none' ? null : which === 'taken' ? (pl.capturedAt ?? pl.receivedAt) : pl.receivedAt;
		const d = when ? new Date(when) : null;
		const date = d && !Number.isNaN(d.getTime()) ? new Intl.DateTimeFormat(i18n.tag, { day: 'numeric', month: 'long', year: 'numeric' }).format(d) : '';
		const who = withVia(pl.senderName, pl.via && pl.viaService ? { service: pl.viaService, label: pl.via } : null);
		return [who, date].filter(Boolean).join(' · ');
	}

	const weather = $derived.by(() => {
		const w = view?.weather;
		if (!w?.current || !settings?.widgets?.weather) return null;
		// Frames hide weather older than 6 h (README §5.4).
		if (Date.now() - Date.parse(w.fetched_at) > 6 * 3600_000) return null;
		return { tempC: w.current.temp_c, code: w.current.code, isDay: w.current.is_day ?? true, unit: settings?.locale?.temperature_unit ?? 'c' };
	});

	function poke() {
		chrome = true;
		if (chromeTimer) clearTimeout(chromeTimer);
		chromeTimer = setTimeout(() => (chrome = false), 4000);
	}

	async function react(r: Reaction) {
		if (!engine) return;
		const next = p?.reaction === r ? null : r;
		if (await engine.react(next)) {
			reacted = next;
			setTimeout(() => (reactOpen = false), 900);
		}
	}

	function openReactions() {
		reactOpen = true;
		engine?.setPaused(true);
		queueMicrotask(() => reactButtons[0]?.focus());
	}

	function closeReactions() {
		reactOpen = false;
		engine?.setPaused(false);
	}

	/** "Ask for new photos" (family-social.md §8): above the reactions, the browser frame's quick menu. */
	function askForPhotos() {
		if (engine?.askForPhotos()) closeReactions();
	}

	const askLabel = $derived.by(() => {
		const a = view?.ask;
		if (!a) return '';
		if (a.kind === 'answered') return t('browserFrame.askAnswered', { name: a.name });
		return a.kind === 'asked' ? t('browserFrame.askedToday') : t('browserFrame.askPhotos');
	});

	const noteText = $derived.by(() => {
		const m = view?.note ? noteMessage(view.note) : null;
		return m ? t(m.key as Parameters<typeof t>[0], m.values) : '';
	});

	function onKey(e: KeyboardEvent) {
		muted = false;
		poke();
		engine?.activity();
		if (view?.status !== 'running' || !view.slide) return;
		if (reactOpen) {
			const i = reactButtons.indexOf(document.activeElement as HTMLButtonElement);
			if (document.activeElement === askButton && (e.key === 'ArrowDown' || e.key === 'ArrowRight' || e.key === 'ArrowLeft')) reactButtons[0]?.focus();
			else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
				const n = reactButtons.length;
				const dir = (e.key === 'ArrowRight') !== (i18n.dir === 'rtl') ? 1 : -1;
				reactButtons[(Math.max(0, i) + dir + n) % n]?.focus();
			} else if (e.key === 'ArrowUp') {
				if (askButton && !askButton.disabled) askButton.focus();
			} else if (e.key === 'ArrowDown') reactButtons[Math.max(0, i)]?.focus();
			else if (e.key === 'Escape' || e.key === 'Backspace' || e.key === 'GoBack' || e.key === 'BrowserBack') closeReactions();
			else return; // Enter activates the focused button
			e.preventDefault();
			return;
		}
		const digit = Number(e.key);
		if (e.key === 'ArrowRight' || e.key === 'MediaTrackNext') engine?.next();
		else if (e.key === 'ArrowLeft' || e.key === 'MediaTrackPrevious') engine?.previous();
		else if (e.key === 'Enter' || e.key === ' ') openReactions();
		else if (e.key === 'MediaPlayPause') engine?.setPaused(!view.paused);
		else if (digit >= 1 && digit <= 4) void react(REACTIONS[digit - 1]!);
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

	function formatCode(c: string): string {
		return c.length === 8 ? `${c.slice(0, 4)} ${c.slice(4)}` : c;
	}

	onMount(() => {
		const base = apiUrl('');
		client = new DeviceClient({ base, credentials: new CredentialStore(browserStorage(), credentialsKey(base)), locale: () => i18n.locale });
		const c = client;
		engine = new FrameEngine({
			client: c,
			social: new SocialStore(browserStorage(), `${credentialsKey(base)}.social`),
			socket: (events) => new DeviceSocket({ url: () => c.socketUrl(location.origin), events }),
			enrollInfo: () => enrollInfo(i18n.locale),
			heartbeat: () => heartbeat(started, view?.screen === 'on' ? 'on' : view?.screen === 'dimmed' ? 'dimmed' : 'sleeping')
		});
		const off = engine.on('view', (v) => (view = v));
		view = engine.view;
		void engine.start();

		// Preload the next photo for the cross-fade.
		const offSlide = engine.on('slide', () => {
			const n = engine?.view.next;
			if (n && n.kind !== 'video') {
				const u = c.mediaUrl(n.id);
				if (u) new Image().src = u;
			}
		});

		// Keep the TV awake while the frame runs.
		let lock: { release: () => Promise<void> } | null = null;
		const wake = async () => {
			try {
				lock = (await (navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }).wakeLock?.request('screen')) ?? null;
			} catch {
				/* not supported */
			}
		};
		void wake();
		const vis = () => {
			if (document.visibilityState !== 'visible') return;
			void wake();
			engine?.resume();
		};
		const online = () => engine?.resume();
		document.addEventListener('visibilitychange', vis);
		window.addEventListener('online', online);
		return () => {
			off();
			offSlide();
			engine?.stop();
			document.removeEventListener('visibilitychange', vis);
			window.removeEventListener('online', online);
			void lock?.release().catch(() => {});
		};
	});

	const asleep = $derived(view?.screen === 'sleeping' || view?.screen === 'off');
	const showCaption = $derived(settings?.widgets?.caption ?? true);
</script>

<svelte:head><title>{view?.frameName ? `${view.frameName} · kinframe` : t('browserFrame.pageTitle')}</title></svelte:head>
<svelte:window onkeydown={onKey} onpointermove={poke} onpointerdown={() => ((muted = false), engine?.activity())} />

<main class="tv" class:hide-cursor={!chrome}>
	{#if !view || view.status === 'starting' || view.status === 'enrolling'}
		<div class="center"><Logo size={56} /></div>
	{:else if view.status === 'code'}
		<section class="center setup" aria-labelledby="bf-title">
			<Logo size={44} />
			<h1 id="bf-title">{t('browserFrame.setupTitle')}</h1>
			<p>{t('browserFrame.codeIntro')}</p>
			<p class="code num" aria-label={view.code?.code}>{formatCode(view.code?.code ?? '')}</p>
			{#if view.code?.join_url}<p class="small">{t('browserFrame.joinUrl', { url: view.code.join_url.replace(/^https?:\/\//, '') })}</p>{/if}
			{#if !view.online}<p class="small dimmed">{t('browserFrame.connecting')}</p>{/if}
		</section>
	{:else if view.status === 'revoked'}
		<section class="center setup" role="alert">
			<Logo size={44} />
			<h1>{t('browserFrame.removedTitle')}</h1>
			<p>{t('browserFrame.removedBody')}</p>
			<button class="again" onclick={() => engine?.reset()}>{t('browserFrame.setUpAgain')}</button>
		</section>
	{:else if view.status === 'error'}
		<section class="center setup" role="status">
			<Logo size={44} />
			<h1>{t('browserFrame.offlineTitle')}</h1>
			<p>{t('browserFrame.offlineBody')}</p>
		</section>
	{:else}
		{#if stage}
			<TvStage
				slide={stage}
				background={settings?.slideshow?.background ?? 'blur'}
				{muted}
				volume={(settings?.video?.volume ?? 60) / 100}
				loop={settings?.video?.playback === 'loop'}
				onvideoended={() => engine?.videoEnded()}
			/>
		{:else}
			<div class="center setup">
				<Logo size={44} />
				<h1>{t('browserFrame.emptyTitle')}</h1>
				<p>{t('browserFrame.emptyBody', { frame: view.frameName })}</p>
			</div>
		{/if}
		<TvOverlay
			caption={p && showCaption ? p.caption : null}
			byline={p && showCaption ? byline(p) + (p.reaction ? `   ${EMOJI[p.reaction]}` : '') : ''}
			clock={settings?.widgets?.clock ?? true}
			date={settings?.widgets?.date ?? true}
			clock24h={settings?.widgets?.clock_24h ?? true}
			timeZone={settings?.locale?.timezone}
			{weather}
			label={chrome ? view.frameName : ''}
		>
			{#if view.celebrate}
				<p class="pill celebrate" role="status"><Icon name="sparkle" size={20} />{view.celebrate.count > 1 ? tn('browserFrame.newPhotos', view.celebrate.count) : t('browserFrame.newPhoto', { name: view.celebrate.senderName })}</p>
			{:else if chrome && !reactOpen && stage}
				<p class="pill keys">{t('browserFrame.keysHint')}</p>
			{/if}
		</TvOverlay>

		{#if (view.note || view.cards) && !reactOpen && !view.identify}
			<!-- Family social: "New photos from Ben", the weekly prompt's title, the newest comment. Plain text. -->
			<div class="social">
				{#if view.note}
					<p class="note" role="status"><Icon name="image" size={22} />{noteText}</p>
				{/if}
				{#if view.cards?.prompt}
					{@const pr = view.cards.prompt}
					<div class="card prompt" style:animation-delay="{shown?.transitionMs ?? 0}ms">
						<span class="prompt-label">{pr.feed_name.trim() ? t('browserFrame.promptThisWeekIn', { family: pr.feed_name }) : t('browserFrame.promptThisWeek')}</span>
						<span class="prompt-title">{pr.title}</span>
					</div>
				{/if}
				{#if view.cards?.comment}
					{@const cm = view.cards.comment}
					<p class="card comment" style:animation-delay="{shown?.transitionMs ?? 0}ms">
						<Icon name="message" size={22} />
						<span class="comment-text">{#if cm.author_name.trim()}<strong>{cm.author_name.trim()}</strong>{/if}{cm.body.trim().replace(/\s*\n\s*/g, ' ')}</span>
					</p>
				{/if}
			</div>
		{/if}

		{#if reactOpen && p}
			<div class="menu">
				{#if view.ask}
					<button bind:this={askButton} class="ask" disabled={view.ask.kind !== 'ready'} onclick={askForPhotos}>
						{#if view.ask.kind === 'ready'}<Icon name="image" size={22} />{/if}<span>{askLabel}</span>
					</button>
				{/if}
				<div class="reactions" role="toolbar" aria-label={t('browserFrame.react')}>
					{#each REACTIONS as r, i (r)}
						<button bind:this={reactButtons[i]} class:on={(reacted ?? p.reaction) === r} aria-pressed={(reacted ?? p.reaction) === r} onclick={() => react(r)}>
							<span class="emoji" aria-hidden="true">{EMOJI[r]}</span><span>{t(`reaction.${r}`)}</span>
						</button>
					{/each}
					<button class="close" onclick={closeReactions} aria-label={t('common.close')}><Icon name="x" size={22} /></button>
				</div>
			</div>
		{/if}
		{#if view.askConfirmation && !reactOpen}
			<div class="confirm" role="status">
				<button onclick={() => engine?.dismissAskConfirmation()}>
					{view.askConfirmation === 'asked' ? t('browserFrame.askSent') : t('browserFrame.askOffline')}
				</button>
			</div>
		{/if}
		{#if reacted}
			<p class="toast" role="status">{t('browserFrame.reacted', { name: p?.senderName ?? '' })}</p>
		{/if}
		{#if view.identify}
			<div class="identify" role="status"><span>{view.identify}</span></div>
		{/if}
		<div class="sleep" class:on={asleep} class:dimmed={view.screen === 'dimmed'} aria-hidden={!asleep}></div>
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
	.setup h1 {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.6rem, 3.5vw, 2.6rem);
		font-weight: 500;
		margin: 12px 0 0;
	}
	.setup p {
		max-width: 44ch;
		opacity: 0.85;
		font-size: clamp(1rem, 1.8vw, 1.35rem);
		margin: 0;
	}
	.setup .code {
		font-size: clamp(2.6rem, 8vw, 5.5rem);
		letter-spacing: 0.08em;
		font-weight: 700;
		opacity: 1;
		margin: 12px 0;
		max-width: none;
	}
	.small {
		font-size: clamp(0.9rem, 1.4vw, 1.1rem) !important;
	}
	.dimmed {
		opacity: 0.6 !important;
	}
	.again {
		margin-top: 12px;
		padding: 14px 26px;
		border-radius: 999px;
		border: 0;
		background: var(--primary, #c8553d);
		color: #fff;
		font: inherit;
		font-weight: 650;
		font-size: 1.1rem;
		cursor: pointer;
	}
	.again:focus-visible {
		outline: 3px solid #fff;
		outline-offset: 3px;
	}
	.pill {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		margin: 0;
		padding: 10px 18px;
		border-radius: 999px;
		background: rgb(20 18 16 / 0.72);
		backdrop-filter: blur(10px);
		font-weight: 600;
		white-space: nowrap;
	}
	.celebrate {
		background: var(--primary, #c8553d);
		font-size: clamp(1rem, 1.8vw, 1.4rem);
		animation: pop 600ms cubic-bezier(0.2, 0.8, 0.2, 1.2);
	}
	.keys {
		opacity: 0.85;
		font-size: 0.95rem;
	}
	@keyframes pop {
		from {
			transform: scale(0.6);
			opacity: 0;
		}
	}
	.menu {
		position: absolute;
		z-index: 5;
		left: 50%;
		bottom: clamp(24px, 8vh, 96px);
		transform: translateX(-50%);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
	}
	.ask {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		max-width: min(90vw, 520px);
		padding: 14px 24px;
		border-radius: 999px;
		border: 2px solid transparent;
		background: rgb(20 18 16 / 0.8);
		backdrop-filter: blur(12px);
		color: inherit;
		font: inherit;
		font-weight: 600;
		font-size: clamp(1rem, 1.6vw, 1.2rem);
		cursor: pointer;
	}
	.ask span {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.ask:disabled {
		cursor: default;
		opacity: 0.85;
	}
	.ask:not(:disabled):focus-visible,
	.ask:not(:disabled):hover {
		background: rgb(255 255 255 / 0.14);
		border-color: #fff;
		outline: none;
	}
	.confirm {
		position: absolute;
		z-index: 6;
		left: 50%;
		bottom: clamp(24px, 6vh, 64px);
		transform: translateX(-50%);
		max-width: min(92vw, 760px);
	}
	.confirm button {
		margin: 0;
		padding: 14px 26px;
		border: 0;
		border-radius: 999px;
		background: rgb(46 38 33 / 0.94);
		color: inherit;
		font: inherit;
		font-weight: 600;
		font-size: clamp(1rem, 1.6vw, 1.25rem);
		cursor: pointer;
	}
	.social {
		position: absolute;
		z-index: 4;
		top: clamp(16px, 4vh, 48px);
		left: 50%;
		transform: translateX(-50%);
		width: min(92vw, 640px);
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 14px;
		pointer-events: none;
	}
	.note,
	.card {
		margin: 0;
		color: #fff;
		background: rgb(28 23 20 / 0.85);
		backdrop-filter: blur(10px);
		animation: fade-in 500ms ease both;
	}
	.note {
		display: inline-flex;
		align-items: center;
		gap: 12px;
		max-width: 100%;
		padding: 12px 24px;
		border-radius: 999px;
		font-weight: 600;
		font-size: clamp(1rem, 1.6vw, 1.25rem);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.card {
		max-width: 100%;
		border-radius: 24px;
		padding: 16px 28px;
	}
	.prompt {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		text-align: center;
	}
	.prompt-label {
		font-weight: 600;
		font-size: clamp(0.9rem, 1.3vw, 1.05rem);
		color: #f3c6a8;
	}
	.prompt-title {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.3rem, 2.4vw, 1.9rem);
		line-height: 1.25;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.comment {
		display: flex;
		align-items: center;
		gap: 14px;
		font-size: clamp(1rem, 1.6vw, 1.25rem);
	}
	.comment-text {
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.comment strong {
		color: #f3c6a8;
		margin-inline-end: 0.6em;
	}
	@keyframes fade-in {
		from {
			opacity: 0;
		}
	}
	.reactions {
		display: flex;
		gap: 10px;
		padding: 10px;
		border-radius: 28px;
		background: rgb(20 18 16 / 0.8);
		backdrop-filter: blur(12px);
	}
	.reactions button {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		min-width: 92px;
		padding: 12px 10px;
		border-radius: 20px;
		border: 2px solid transparent;
		background: transparent;
		color: inherit;
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.reactions .emoji {
		font-size: 2.2rem;
		line-height: 1;
	}
	.reactions button:focus-visible,
	.reactions button:hover {
		background: rgb(255 255 255 / 0.14);
		border-color: #fff;
		outline: none;
	}
	.reactions button.on {
		background: rgb(255 255 255 / 0.22);
	}
	.reactions .close {
		min-width: 56px;
		justify-content: center;
	}
	.toast {
		position: absolute;
		z-index: 6;
		left: 50%;
		top: 10%;
		transform: translateX(-50%);
		margin: 0;
		padding: 10px 18px;
		border-radius: 999px;
		background: rgb(20 18 16 / 0.8);
		font-weight: 600;
	}
	.identify {
		position: absolute;
		z-index: 7;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.55);
	}
	.identify span {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(2rem, 6vw, 4.5rem);
		padding: 24px 40px;
		border-radius: 28px;
		background: var(--primary, #c8553d);
	}
	.sleep {
		position: absolute;
		inset: 0;
		z-index: 10;
		background: #000;
		opacity: 0;
		pointer-events: none;
		transition: opacity 1500ms ease;
	}
	.sleep.dimmed {
		opacity: 0.6;
	}
	.sleep.on {
		opacity: 1;
	}
</style>
