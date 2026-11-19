<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Feed, type Schemas } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { relativeTime } from '$lib/format';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '$components/Dialog.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';
	import FeedRoleBadge from '$components/feed/FeedRoleBadge.svelte';

	$effect(() => {
		if (discovery.feeds) void feeds.load(true);
	});

	const sorted = $derived(
		[...feeds.list].sort((a, b) => (b.last_post_at ?? b.created_at ?? '').localeCompare(a.last_post_at ?? a.created_at ?? '') || a.name.localeCompare(b.name))
	);

	let createOpen = $state(false);
	let name = $state('');
	let creating = $state(false);
	let createError = $state<string | null>(null);
	let key = newIdempotencyKey();

	function openCreate() {
		name = '';
		createError = null;
		key = newIdempotencyKey();
		createOpen = true;
	}

	async function create(e: SubmitEvent) {
		e.preventDefault();
		const n = name.trim();
		if (!n) return;
		creating = true;
		createError = null;
		try {
			const feed = (await call(
				api.POST('/v1/feeds', { params: { header: { 'Idempotency-Key': key } }, body: { name: n } as Schemas['FeedCreate'] })
			)) as Feed;
			feeds.upsert(feed);
			createOpen = false;
			toasts.success(t('feeds.created', { feed: feed.name }));
			await goto(`/feeds/${feed.id}?tab=invites`);
		} catch (err) {
			createError = isCode(err, 'limit_reached') ? t('feeds.limitReached', { max: discovery.limits.max_feeds_per_user }) : problemMessage(err);
			key = newIdempotencyKey();
		} finally {
			creating = false;
		}
	}
</script>

<svelte:head><title>{t('feeds.title')} · kinframe</title></svelte:head>

<div class="container page">
	{#if !discovery.feeds}
		<div class="card"><EmptyState title={t('feeds.offTitle')} body={t('feeds.offBody')} art="none" /></div>
	{:else}
		<div class="page-header">
			<div>
				<h1>{t('feeds.title')}</h1>
				<p class="lede">{t('feeds.lede')}</p>
			</div>
			<div class="row-wrap">
				<a href="/frames/add" class="btn btn-ghost"><Icon name="link" size={18} />{t('feeds.enterCode')}</a>
				{#if feeds.list.length > 0}<button class="btn btn-soft" onclick={openCreate}><Icon name="plus" size={18} />{t('feeds.new')}</button>{/if}
			</div>
		</div>

		{#if !feeds.loaded && !feeds.error}
			<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>{#each [0, 1] as i (i)}<div class="skeleton" style="height:84px"></div>{/each}</div>
		{:else if feeds.error && !feeds.loaded}
			<div class="callout callout-error" role="alert">
				<Icon name="alert" />
				<div class="stack-sm"><span>{problemMessage(feeds.error)}</span><button class="btn btn-sm" onclick={() => feeds.load(true)}>{t('common.tryAgain')}</button></div>
			</div>
		{:else if feeds.list.length === 0}
			<div class="card">
				<EmptyState title={t('feeds.emptyTitle')} body={t('feeds.emptyBody')} art="people">
					{#snippet actions()}
						<button class="btn btn-primary btn-lg" onclick={openCreate}><Icon name="plus" size={20} />{t('feeds.new')}</button>
					{/snippet}
				</EmptyState>
			</div>
		{:else}
			<ul class="feeds" role="list">
				{#each sorted as f (f.id)}
					<li>
						<a class="card feed-row" href="/feeds/{f.id}">
							<FeedCover name={f.name} url={f.cover_url} size={56} />
							<span class="body">
								<span class="name">{f.name}</span>
								<span class="muted small">
									{tn('feeds.frames', f.subscription_count)} · {tn('feeds.members', f.member_count)}{#if f.last_post_at} · {t('feeds.lastPost', { when: relativeTime(f.last_post_at, i18n.tag) })}{/if}
								</span>
							</span>
							<FeedRoleBadge role={f.role} />
							<Icon name="chevronRight" size={18} />
						</a>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</div>

<Dialog bind:open={createOpen} title={t('feeds.newTitle')}>
	<form id="feed-create" class="stack" onsubmit={create}>
		<p class="muted small">{t('feeds.newBody')}</p>
		<div class="field">
			<label for="feed-name">{t('feeds.name')}</label>
			<!-- svelte-ignore a11y_autofocus -->
			<input id="feed-name" class="input" required maxlength="40" bind:value={name} placeholder={t('feeds.namePlaceholder')} autofocus />
		</div>
		{#if createError}<p class="error-text" role="alert">{createError}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (createOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="feed-create" disabled={!name.trim() || creating}>
			{#if creating}<span class="spinner" aria-hidden="true"></span>{/if}{t('feeds.create')}
		</button>
	{/snippet}
</Dialog>

<style>
	.feeds {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		max-width: 760px;
	}
	.feed-row {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
		color: inherit;
		text-decoration: none;
		transition: box-shadow var(--dur) var(--ease);
	}
	.feed-row:hover {
		box-shadow: var(--shadow);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.name {
		font-weight: 650;
		font-size: 1.05rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
