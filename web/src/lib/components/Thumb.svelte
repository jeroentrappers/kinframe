<script lang="ts">
	import { mediaObjectUrl, type MediaVariant } from '$lib/media';
	import { tm } from '$lib/i18n/media';
	import Icon from './Icon.svelte';

	let {
		itemId,
		variant = 'thumb_512',
		alt = '',
		kind = 'photo',
		radius = 'var(--r)'
	}: { itemId: string; variant?: MediaVariant; alt?: string; kind?: 'photo' | 'video' | 'live'; radius?: string } = $props();

	// Live Photos play their motion on hover (mouse) or when the badge is tapped.
	let motionSrc = $state<string | null>(null);
	let playing = $state(false);

	async function playMotion() {
		if (kind !== 'live' || playing) return;
		try {
			motionSrc = await mediaObjectUrl(itemId, 'motion');
			playing = true;
		} catch {
			/* motion not ready yet: the still stays */
		}
	}

	let src = $state<string | null>(null);
	let failed = $state(false);
	let loaded = $state(false);

	$effect(() => {
		const id = itemId;
		src = null;
		failed = false;
		loaded = false;
		let cancelled = false;
		mediaObjectUrl(id, variant)
			.then((u) => !cancelled && (src = u))
			.catch(() => !cancelled && (failed = true));
		return () => (cancelled = true);
	});
</script>

<span
	class="thumb"
	style="border-radius:{radius}"
	role="presentation"
	onpointerenter={(e) => e.pointerType === 'mouse' && playMotion()}
	onpointerleave={(e) => e.pointerType === 'mouse' && (playing = false)}
	onpointerup={(e) => e.pointerType !== 'mouse' && playMotion()}
>
	{#if src && !failed}
		<img {src} {alt} class:loaded onload={() => (loaded = true)} onerror={() => (failed = true)} draggable="false" />
	{/if}
	{#if !loaded && !failed}
		<span class="skeleton fill" aria-hidden="true"></span>
	{/if}
	{#if failed}
		<span class="fallback" role="img" aria-label={alt}><Icon name={kind === 'video' ? 'video' : 'image'} size={22} /></span>
	{/if}
	{#if kind === 'live' && playing && motionSrc}
		<!-- svelte-ignore a11y_media_has_caption -->
		<video class="motion" src={motionSrc} autoplay muted playsinline onended={() => (playing = false)}></video>
	{/if}
	{#if kind === 'video'}
		<span class="video-badge" aria-hidden="true"><Icon name="play" size={12} stroke={0} /></span>
	{:else if kind === 'live'}
		<span class="live-badge" role="img" aria-label={tm('media.liveA11y')} title={tm('media.playLive')}
			><Icon name="live" size={12} stroke={2} /><span aria-hidden="true">{tm('media.live')}</span></span
		>
	{/if}
</span>

<style>
	.thumb {
		position: relative;
		display: block;
		width: 100%;
		height: 100%;
		overflow: hidden;
		background: var(--surface-2);
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		opacity: 0;
		transition: opacity 220ms var(--ease);
	}
	img.loaded {
		opacity: 1;
	}
	.fill {
		position: absolute;
		inset: 0;
		border-radius: 0;
	}
	.fallback {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		color: var(--text-faint);
	}
	.video-badge {
		position: absolute;
		inset-inline-start: 6px;
		bottom: 6px;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.55);
		color: #fff;
	}
	.motion {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.live-badge {
		position: absolute;
		inset-inline-start: 6px;
		bottom: 6px;
		display: inline-flex;
		align-items: center;
		gap: 3px;
		padding: 2px 7px 2px 5px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.55);
		color: #fff;
		font-size: 0.68rem;
		font-weight: 700;
		letter-spacing: 0.02em;
		pointer-events: none;
	}
	.video-badge :global(svg) {
		fill: currentColor;
	}
</style>
