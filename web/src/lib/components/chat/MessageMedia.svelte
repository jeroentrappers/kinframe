<script lang="ts">
	import type { Item } from '$lib/api/client';
	import { mediaObjectUrl } from '$lib/media';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	/** A chat message's photos and videos: a grid of up to four, and a viewer. */
	let { items }: { items: Item[] } = $props();
	const shown = $derived(items.slice(0, 4));
	const more = $derived(items.length - shown.length);

	let viewing = $state<number | null>(null);
	let open = $state(false);
	let src = $state<string | null>(null);
	const current = $derived(viewing === null ? null : (items[viewing] ?? null));

	function show(i: number) {
		viewing = i;
		open = true;
		src = null;
		const it = items[i]!;
		if (it.status !== 'ready') return;
		mediaObjectUrl(it.id, 'master')
			.then((u) => viewing === i && (src = u))
			.catch(() => {});
	}
</script>

<div class="grid n{shown.length}">
	{#each shown as it, i (it.id)}
		<button class="cell" onclick={() => show(i)} aria-label={t('social.photoOf', { n: i + 1, total: items.length })}>
			{#if it.status === 'processing'}
				<span class="processing skeleton" aria-hidden="true"></span><span class="badge-proc small"><span class="spinner" aria-hidden="true"></span></span>
			{:else if it.status === 'failed'}
				<span class="failed" aria-hidden="true"><Icon name="alert" size={20} /></span>
			{:else}
				<Thumb itemId={it.id} variant="thumb_256" kind={it.kind} radius="0" />
			{/if}
			{#if i === shown.length - 1 && more > 0}<span class="more num" aria-hidden="true">+{more}</span>{/if}
		</button>
	{/each}
</div>

{#if current}
	<Dialog bind:open title={t('social.photoOf', { n: (viewing ?? 0) + 1, total: items.length })} size="md">
		<div class="viewer">
			{#if current.status !== 'ready'}
				<p class="muted">{current.status === 'failed' ? t('chat.mediaFailed') : t('chat.processing')}</p>
			{:else if !src}
				<div class="skeleton" style="aspect-ratio:4/3"></div>
			{:else if current.kind === 'video'}
				<!-- svelte-ignore a11y_media_has_caption -->
				<video {src} controls playsinline></video>
			{:else}
				<img {src} alt={current.caption ?? ''} />
			{/if}
		</div>
		{#snippet footer()}
			<button class="btn" disabled={(viewing ?? 0) === 0} onclick={() => show((viewing ?? 0) - 1)} aria-label={t('social.previousPhoto')}><Icon name="chevronLeft" size={18} /></button>
			<button class="btn" disabled={(viewing ?? 0) >= items.length - 1} onclick={() => show((viewing ?? 0) + 1)} aria-label={t('social.nextPhoto')}><Icon name="chevronRight" size={18} /></button>
		{/snippet}
	</Dialog>
{/if}

<style>
	.grid {
		display: grid;
		gap: 3px;
		width: min(320px, 70vw);
		border-radius: 14px;
		overflow: hidden;
	}
	.grid.n1 {
		grid-template-columns: 1fr;
	}
	.grid.n2,
	.grid.n3,
	.grid.n4 {
		grid-template-columns: 1fr 1fr;
	}
	.grid.n3 .cell:first-child {
		grid-column: span 2;
	}
	.cell {
		position: relative;
		aspect-ratio: 1;
		border: 0;
		padding: 0;
		background: var(--surface-2);
		cursor: zoom-in;
		display: block;
	}
	.grid.n1 .cell {
		aspect-ratio: 4 / 3;
	}
	.grid.n3 .cell:first-child {
		aspect-ratio: 2 / 1;
	}
	.processing {
		position: absolute;
		inset: 0;
		border-radius: 0;
	}
	.badge-proc {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
	}
	.failed {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		color: var(--danger);
	}
	.more {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.45);
		color: #fff;
		font-size: 1.4rem;
		font-weight: 800;
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
</style>
