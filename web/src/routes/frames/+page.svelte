<script lang="ts">
	import { can, frames } from '$lib/frames.svelte';
	import { FrameDrop } from '$lib/framedrop.svelte';
	import { problemMessage } from '$lib/problem';
	import { t, tn } from '$lib/i18n/index.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import FrameCard from '$components/FrameCard.svelte';
	import Icon from '$components/Icon.svelte';

	const online = $derived(frames.list.filter((f) => f.online).length);

	// Drag photos from the computer straight onto a frame's card to start a send to it.
	const drop = new FrameDrop((id) => {
		const f = id ? frames.get(id) : undefined;
		return f && can(f, 'send') ? f.id : null;
	});
	const dropStatus = $derived.by(() => {
		if (!drop.active || !frames.list.some((f) => can(f, 'send'))) return '';
		const f = drop.target ? frames.get(drop.target) : undefined;
		return f ? t('frames.dropTo', { name: f.name }) : t('frames.dropHint');
	});
</script>

<svelte:head><title>{t('frames.title')} · kinframe</title></svelte:head>

<svelte:window ondragenter={drop.enter} ondragleave={drop.leave} ondragover={drop.over} ondrop={drop.drop} />

<p class="visually-hidden" role="status" aria-live="polite">{dropStatus}</p>

<div class="container page">
	<div class="page-header">
		<div>
			<h1>{t('frames.title')}</h1>
			{#if frames.loaded && frames.list.length > 0}
				<p class="lede">{tn('frames.summary', frames.list.length, { online })}</p>
			{/if}
		</div>
		{#if frames.list.length > 0}
			<a href="/frames/add" class="btn btn-soft"><Icon name="plus" size={18} />{t('frames.add')}</a>
		{/if}
	</div>

	{#if !frames.loaded && !frames.error}
		<div class="grid" aria-busy="true" aria-label={t('common.loading')}>
			{#each [0, 1, 2] as i (i)}
				<div class="card skel-card">
					<div class="skeleton" style="aspect-ratio:16/9; border-radius: var(--r) var(--r) 0 0"></div>
					<div class="stack-sm" style="padding: 16px">
						<div class="skeleton" style="height:20px; width:60%"></div>
						<div class="skeleton" style="height:14px; width:40%"></div>
					</div>
				</div>
			{/each}
		</div>
	{:else if frames.error && !frames.loaded}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm">
				<span>{problemMessage(frames.error)}</span>
				<button class="btn btn-sm" onclick={() => frames.load(true)}>{t('common.tryAgain')}</button>
			</div>
		</div>
	{:else if frames.list.length === 0}
		<div class="card">
			<EmptyState title={t('frames.emptyTitle')} body={t('frames.emptyBody')} art="frame">
				{#snippet actions()}
					<a href="/frames/add" class="btn btn-primary btn-lg"><Icon name="plus" size={20} />{t('frames.emptyCta')}</a>
				{/snippet}
			</EmptyState>
		</div>
	{:else}
		<ul class="grid" role="list">
			{#each frames.list as frame (frame.id)}
				<li><FrameCard {frame} dragging={drop.active} dropOver={drop.target === frame.id} /></li>
			{/each}
			<li>
				<a href="/frames/add" class="add-tile">
					<span class="plus"><Icon name="plus" size={26} /></span>
					<span>{t('frames.add')}</span>
				</a>
			</li>
		</ul>
	{/if}
</div>

<style>
	.grid {
		display: grid;
		gap: var(--s-4);
		grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.skel-card {
		overflow: hidden;
	}
	.add-tile {
		height: 100%;
		min-height: 180px;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: var(--s-2);
		border: 2px dashed var(--border-strong);
		border-radius: var(--r-lg);
		color: var(--text-muted);
		font-weight: 600;
		text-decoration: none;
		transition:
			background var(--dur) var(--ease),
			border-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.add-tile:hover {
		background: var(--surface);
		border-color: var(--primary);
		color: var(--primary);
	}
	.plus {
		width: 52px;
		height: 52px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
	}
</style>
