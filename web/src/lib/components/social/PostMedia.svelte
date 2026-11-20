<script lang="ts">
	import { api, call, type Item, type ReactionSummary } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { mediaObjectUrl } from '$lib/media';
	import { postImageVariant, summaryFromList } from '$lib/social';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';
	import ReactionRow from './ReactionRow.svelte';

	let {
		items,
		postId,
		alt,
		itemReactions = false,
		aspect = '4 / 3'
	}: {
		items: Item[];
		postId: string;
		alt: string;
		/** The post page: open a photo to react to it on its own (`post_item`). */
		itemReactions?: boolean;
		aspect?: string;
	} = $props();

	let track: HTMLDivElement | undefined = $state();
	let index = $state(0);
	const many = $derived(items.length > 1);

	function onScroll() {
		if (!track) return;
		const w = track.clientWidth || 1;
		index = Math.max(0, Math.min(items.length - 1, Math.round(Math.abs(track.scrollLeft) / w)));
	}

	function go(delta: number) {
		if (!track) return;
		const next = Math.max(0, Math.min(items.length - 1, index + delta));
		const sign = i18n.dir === 'rtl' ? -1 : 1;
		track.scrollTo({ left: sign * next * track.clientWidth, behavior: 'smooth' });
	}

	// ---- viewer: the full photo or video, and its own reactions
	let viewer = $state<Item | null>(null);
	let viewerOpen = $state(false);
	let src = $state<string | null>(null);
	let itemSummary = $state<ReactionSummary | null>(null);

	async function open(it: Item) {
		viewer = it;
		viewerOpen = true;
		src = null;
		itemSummary = null;
		mediaObjectUrl(it.id, 'master')
			.then((u) => viewer?.id === it.id && (src = u))
			.catch(() => {});
		if (itemReactions)
			call(api.GET('/v1/reactions/{targetKind}/{targetId}', { params: { path: { targetKind: 'post_item', targetId: it.id }, query: { post_id: postId } } }))
				.then((l) => viewer?.id === it.id && (itemSummary = summaryFromList(l, auth.me?.id)))
				.catch(() => (itemSummary = null));
	}
</script>

<div class="media" style="--aspect:{aspect}">
	<div class="track" bind:this={track} onscroll={onScroll} role="group" aria-roledescription={t('social.carousel')} aria-label={alt}>
		{#each items as it, i (it.id)}
			<button class="slide" onclick={() => open(it)} aria-label={many ? t('social.photoOf', { n: i + 1, total: items.length }) : t('social.openPhoto')}>
				<Thumb itemId={it.id} variant={postImageVariant(it)} kind={it.kind} radius="0" alt={it.caption ?? ''} />
			</button>
		{/each}
	</div>
	{#if many}
		<span class="counter num" aria-hidden="true">{index + 1}/{items.length}</span>
		<button class="nav prev" onclick={() => go(-1)} disabled={index === 0} aria-label={t('social.previousPhoto')}><Icon name="chevronLeft" size={20} /></button>
		<button class="nav next" onclick={() => go(1)} disabled={index === items.length - 1} aria-label={t('social.nextPhoto')}><Icon name="chevronRight" size={20} /></button>
		<div class="dots" aria-hidden="true">
			{#each items.slice(0, 12) as it, i (it.id)}<span class:on={i === index}></span>{/each}
		</div>
	{/if}
</div>

{#if viewer}
	<Dialog bind:open={viewerOpen} title={viewer.caption || alt} size="md">
		<div class="viewer">
			{#if !src}
				<div class="skeleton" style="aspect-ratio:4/3"></div>
			{:else if viewer.kind === 'video'}
				<!-- svelte-ignore a11y_media_has_caption -->
				<video {src} controls playsinline></video>
			{:else}
				<img {src} alt={viewer.caption ?? ''} />
			{/if}
		</div>
		{#if itemReactions}
			<div class="viewer-react">
				{#key viewer.id}
					<ReactionRow summary={itemSummary} kind="post_item" targetId={viewer.id} {postId} />
				{/key}
			</div>
		{/if}
	</Dialog>
{/if}

<style>
	.media {
		position: relative;
		background: var(--surface-2);
		overflow: hidden;
	}
	.track {
		display: flex;
		overflow-x: auto;
		scroll-snap-type: x mandatory;
		scrollbar-width: none;
		overscroll-behavior-x: contain;
	}
	.track::-webkit-scrollbar {
		display: none;
	}
	.slide {
		flex: 0 0 100%;
		aspect-ratio: var(--aspect);
		scroll-snap-align: center;
		border: 0;
		padding: 0;
		background: none;
		cursor: zoom-in;
		display: block;
	}
	.counter {
		position: absolute;
		top: 10px;
		inset-inline-end: 10px;
		padding: 2px 9px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.5);
		color: #fff;
		font-size: 0.75rem;
		font-weight: 700;
	}
	.nav {
		position: absolute;
		top: 50%;
		transform: translateY(-50%);
		width: 36px;
		height: 36px;
		border-radius: 50%;
		border: 0;
		background: rgb(255 255 255 / 0.85);
		color: #222;
		display: none;
		place-items: center;
		cursor: pointer;
		box-shadow: var(--shadow-sm);
	}
	.nav:disabled {
		opacity: 0;
		pointer-events: none;
	}
	.prev {
		inset-inline-start: 10px;
	}
	.next {
		inset-inline-end: 10px;
	}
	@media (pointer: fine) {
		.media:hover .nav {
			display: grid;
		}
		.nav:focus-visible {
			display: grid;
		}
	}
	.dots {
		position: absolute;
		bottom: 8px;
		left: 0;
		right: 0;
		display: flex;
		justify-content: center;
		gap: 5px;
		pointer-events: none;
	}
	.dots span {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: rgb(255 255 255 / 0.55);
		box-shadow: 0 0 2px rgb(0 0 0 / 0.4);
	}
	.dots span.on {
		background: #fff;
	}
	.viewer img,
	.viewer video {
		display: block;
		width: 100%;
		max-height: 70dvh;
		object-fit: contain;
		background: #111;
		border-radius: var(--r);
	}
	.viewer-react {
		margin-top: var(--s-3);
	}
</style>
