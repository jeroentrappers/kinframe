<script lang="ts">
	// A product's photos (from the shop config): the leading photo, thumbnails to switch, and a full-screen view with
	// previous/next. Without photos, the drawn frame. `compact` shows only the leading photo (the order form's list).
	import { t } from '$lib/i18n/index.svelte';
	import type { ShopProduct } from '$lib/shop';
	import Icon from './Icon.svelte';
	import ShopFrameArt from './ShopFrameArt.svelte';

	let { product, fallback = 'frame-10', compact = false }: { product: ShopProduct; fallback?: string; compact?: boolean } = $props();
	const photos = $derived(product.photos ?? []);
	let index = $state(0);
	let dialog: HTMLDialogElement | undefined = $state();
	const alt = (i: number) => t('shop.photoAlt', { name: product.name, n: i + 1, count: photos.length });
	const go = (d: number) => (index = (index + d + photos.length) % photos.length);

	function keys(e: KeyboardEvent) {
		if (e.key === 'ArrowRight') go(1);
		else if (e.key === 'ArrowLeft') go(-1);
	}
</script>

{#if photos.length === 0}
	<ShopFrameArt image={product.image ?? fallback} />
{:else if compact}
	<img class="photo compact" src={photos[0]} alt={alt(0)} loading="lazy" decoding="async" />
{:else}
	<div class="gallery" data-testid="product-photos">
		<button type="button" class="main" onclick={() => dialog?.showModal()} aria-label={t('shop.photoOpen', { name: product.name })}>
			<img class="photo" src={photos[index]} alt={alt(index)} loading="lazy" decoding="async" />
		</button>
		{#if photos.length > 1}
			<div class="thumbs">
				{#each photos as src, i (src)}
					<button type="button" class="thumb" aria-pressed={i === index} aria-label={alt(i)} onclick={() => (index = i)}>
						<img {src} alt="" loading="lazy" decoding="async" />
					</button>
				{/each}
			</div>
		{/if}
	</div>

	<dialog bind:this={dialog} class="viewer" aria-label={product.name} onkeydown={keys} onclick={(e) => e.target === dialog && dialog?.close()}>
		<img src={photos[index]} alt={alt(index)} />
		<button type="button" class="btn btn-icon close" onclick={() => dialog?.close()} aria-label={t('common.close')}><Icon name="x" size={20} /></button>
		{#if photos.length > 1}
			<button type="button" class="btn btn-icon nav prev" onclick={() => go(-1)} aria-label={t('shop.photoPrev')}><Icon name="chevronLeft" size={22} /></button>
			<button type="button" class="btn btn-icon nav next" onclick={() => go(1)} aria-label={t('shop.photoNext')}><Icon name="chevronRight" size={22} /></button>
			<p class="count num" aria-live="polite">{index + 1} / {photos.length}</p>
		{/if}
	</dialog>
{/if}

<style>
	.gallery {
		display: grid;
		gap: var(--s-2);
	}
	.main {
		display: block;
		padding: 0;
		border: 0;
		background: #fff;
		border-radius: var(--r-md);
		overflow: hidden;
		cursor: zoom-in;
	}
	.photo {
		display: block;
		width: 100%;
		aspect-ratio: 1;
		object-fit: contain;
		background: #fff;
	}
	.photo.compact {
		border-radius: var(--r-sm);
	}
	.thumbs {
		display: flex;
		gap: var(--s-2);
		overflow-x: auto;
		padding-bottom: 2px;
	}
	.thumb {
		flex: 0 0 auto;
		width: 56px;
		height: 56px;
		padding: 0;
		border: 2px solid transparent;
		border-radius: var(--r-sm);
		background: #fff;
		overflow: hidden;
		cursor: pointer;
	}
	.thumb[aria-pressed='true'] {
		border-color: var(--primary);
	}
	.thumb img {
		width: 100%;
		height: 100%;
		object-fit: contain;
	}
	.viewer {
		width: min(96vw, 1100px);
		max-height: 94vh;
		padding: 0;
		border: 0;
		border-radius: var(--r-lg);
		background: #fff;
		overflow: hidden;
	}
	.viewer::backdrop {
		background: rgb(0 0 0 / 0.75);
	}
	.viewer img {
		display: block;
		width: 100%;
		max-height: 94vh;
		object-fit: contain;
	}
	.viewer .close {
		position: absolute;
		top: 10px;
		inset-inline-end: 10px;
	}
	.viewer .nav {
		position: absolute;
		top: 50%;
		transform: translateY(-50%);
	}
	.viewer .prev {
		inset-inline-start: 10px;
	}
	.viewer .next {
		inset-inline-end: 10px;
	}
	.viewer .count {
		position: absolute;
		bottom: 10px;
		left: 50%;
		transform: translateX(-50%);
		margin: 0;
		padding: 2px 10px;
		border-radius: 999px;
		background: rgb(0 0 0 / 0.55);
		color: #fff;
		font-size: 0.85rem;
	}
</style>
