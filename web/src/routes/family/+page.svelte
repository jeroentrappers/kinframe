<script lang="ts">
	import { goto } from '$app/navigation';
	import { families, startFlow } from '$lib/family.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';

	/** The Family tab with several families (simple-app.md §3.4): home family first, each opens its profile. */
	$effect(() => {
		if (feeds.loaded && feeds.list.length === 1) void goto(`/feeds/${feeds.list[0]!.id}`, { replaceState: true });
	});
	const list = $derived(families.ranked);
</script>

<svelte:head><title>{t('family.switchTitle')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<header class="top">
		<h1>{t('family.switchTitle')}</h1>
		<a class="btn btn-ghost btn-icon" href="/settings" aria-label={t('nav.settings')}><Icon name="settings" size={22} /></a>
	</header>

	{#if !feeds.loaded}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>{#each [0, 1] as i (i)}<div class="skeleton" style="height:76px"></div>{/each}</div>
	{:else}
		<ul class="list" role="list">
			{#each list as f (f.id)}
				<li>
					<a class="card row-link" href="/feeds/{f.id}">
						<span class="round"><FeedCover name={f.name} url={f.cover_url} size={52} /></span>
						<span class="body">
							<span class="name">{f.name}</span>
							<span class="muted small">{tn('family.people', f.member_count)} · {tn('family.frames', f.subscription_count)}</span>
						</span>
						<Icon name="chevronRight" size={18} />
					</a>
				</li>
			{/each}
		</ul>
		<button class="btn btn-block start" onclick={() => startFlow.begin('name')}><Icon name="plus" size={18} />{t('start.start')}</button>
	{/if}
</div>

<style>
	.top {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: var(--s-4);
	}
	.top h1 {
		margin: 0;
		font-size: 1.6rem;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.row-link {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
		color: inherit;
		text-decoration: none;
	}
	.row-link:hover {
		box-shadow: var(--shadow);
	}
	.round :global(*) {
		border-radius: 50% !important;
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.name {
		font-weight: 650;
		font-size: 1.05rem;
	}
	.start {
		margin-top: var(--s-4);
	}
</style>
