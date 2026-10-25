<script lang="ts">
	// TV mirror: shows exactly what the physical frame shows, live, with the frame's own
	// transitions and Ken Burns. A calm notice when the frame sleeps or is offline.
	import { onMount } from 'svelte';
	import { apiUrl } from '$lib/api/client';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { slideByline } from '$lib/tv';
	import { MirrorConnection, mirrorScreen, mirrorSlide, mirrorUrl, type MirrorState } from '$lib/tvMirror';
	import type { StageSlide } from '$lib/tvstage';
	import Logo from '$components/Logo.svelte';
	import TvOverlay from './TvOverlay.svelte';
	import TvStage from './TvStage.svelte';

	let { token, onended }: { token: string; onended: (reason: 'revoked' | 'expired' | 'invalid') => void } = $props();

	let st = $state<MirrorState | null>(null);
	let slide = $state<StageSlide | null>(null);
	let muted = $state(true);
	const screen = $derived(mirrorScreen(st));
	const first = $derived(st?.showing?.slides[0]);
	const name = $derived(st?.frame_name ?? '');

	function preload(url: string | undefined) {
		if (url) new Image().src = url;
	}

	onMount(() => {
		const conn = new MirrorConnection(mirrorUrl(apiUrl(''), location.origin, token), {
			state: (s, at) => {
				st = s;
				slide = mirrorSlide(s, at);
				// The frame tells what comes next: have it ready for the cross-fade.
				if (s.next?.kind !== 'video') preload(s.next?.url);
			},
			ended: (reason) => onended(reason),
			connected: () => {}
		});
		conn.start();
		const vis = () => document.visibilityState === 'visible' && conn.reconnectNow();
		document.addEventListener('visibilitychange', vis);
		const unmute = () => (muted = false);
		window.addEventListener('keydown', unmute, { once: true });
		window.addEventListener('pointerdown', unmute, { once: true });
		return () => {
			conn.stop();
			document.removeEventListener('visibilitychange', vis);
			window.removeEventListener('keydown', unmute);
			window.removeEventListener('pointerdown', unmute);
		};
	});
</script>

<svelte:head><title>{name ? t('tv.mirrorPageTitle', { frame: name }) : 'kinframe'}</title></svelte:head>

{#if screen === 'showing'}
	<TvStage {slide} background={st?.settings?.background ?? 'blur'} {muted} />
	<TvOverlay
		caption={st?.settings?.caption !== false ? (first?.caption ?? null) : null}
		byline={st?.settings?.caption !== false && first ? slideByline(first, i18n.tag) : ''}
		clock={st?.settings?.clock ?? true}
		date={st?.settings?.date ?? false}
		clock24h={st?.settings?.clock_24h ?? true}
		timeZone={st?.settings?.timezone}
		label={t('tv.mirrorLabel', { frame: name })}
		dim
	/>
{:else}
	<div class="center notice" role="status">
		<Logo size={44} />
		{#if screen === 'asleep'}
			<h1>{t('tv.mirrorAsleepTitle', { frame: name })}</h1>
			<p>{t('tv.mirrorAsleepBody')}</p>
		{:else if screen === 'offline'}
			<h1>{t('tv.mirrorOfflineTitle', { frame: name })}</h1>
			<p>{t('tv.mirrorOfflineBody')}</p>
		{:else}
			<h1>{t('tv.mirrorWaiting', { frame: name || 'kinframe' })}</h1>
		{/if}
	</div>
{/if}

<style>
	.center {
		position: absolute;
		inset: 0;
		display: grid;
		place-content: center;
		justify-items: center;
		gap: 12px;
		text-align: center;
		padding: 24px;
		color: #f6f1ea;
	}
	.notice h1 {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.6rem, 3.5vw, 2.6rem);
		margin: 12px 0 0;
		font-weight: 500;
	}
	.notice p {
		max-width: 40ch;
		opacity: 0.8;
		font-size: clamp(1rem, 1.8vw, 1.3rem);
		margin: 0;
	}
</style>
