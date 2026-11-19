<script lang="ts">
	import { api, call, type Feed, type TimelinePost } from '$lib/api/client';
	import { canPostToFeed } from '$lib/feeds';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { appendPosts, applyPostReactions, applyThread, groupTimeline } from '$lib/social';
	import { t, tn } from '$lib/i18n/index.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';
	import PostCard from './PostCard.svelte';

	/** One family's posts as a timeline with threads (`getTimeline?feed_id=`). */
	let { feed, ondetails }: { feed: Feed; ondetails?: () => void } = $props();

	let posts = $state<TimelinePost[]>([]);
	let next = $state<string | null>(null);
	let loading = $state(true);
	let more = $state(false);
	let error = $state<unknown>(null);

	async function load(reset = true) {
		if (reset) loading = true;
		else more = true;
		try {
			const res = await call(api.GET('/v1/timeline', { params: { query: { feed_id: feed.id, limit: 20, ...(!reset && next ? { before: next } : {}) } } }));
			posts = reset ? res.posts : appendPosts(posts, res.posts);
			next = res.next_before;
			error = null;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			more = false;
		}
	}

	$effect(() => {
		void feed.id;
		void load();
		return live.on((e) => {
			if (e.type === 'post.updated') applyThread(posts, e.post_id, e.thread);
			else if (e.type === 'post.removed') posts = posts.filter((p) => p.id !== e.post_id);
			else if (e.type === 'reaction.updated' && e.target_kind === 'post') applyPostReactions(posts, e.target_id, e.summary);
			// New posts of this family come with their send: the receipts event is our hint.
			else if (e.type === 'feed_post.updated' && e.feed_id === feed.id && !posts.some((p) => p.id === e.post_id)) void load();
		});
	});

	const rows = $derived(groupTimeline(posts));
</script>

<div class="toolbar">
	{#if canPostToFeed(feed.role)}<a class="btn btn-primary" href="/send?feed={feed.id}"><Icon name="send" size={18} />{t('feedPosts.post')}</a>{/if}
	<span class="spacer"></span>
	{#if ondetails}<button class="btn btn-ghost btn-sm" onclick={ondetails}><Icon name="eye" size={16} />{t('social.deliveryDetails')}</button>{/if}
</div>

{#if loading}
	<div class="skeleton" style="height:360px"></div>
{:else if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" /><div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div></div>
{:else if posts.length === 0}
	<div class="card"><EmptyState compact art="send" title={t('feedPosts.emptyTitle')} body={t('feedPosts.emptyBody')} /></div>
{:else}
	<ul class="timeline" role="list">
		{#each rows as row (row.kind === 'post' ? row.post.id : `p:${row.prompt.id}:${row.posts[0]!.id}`)}
			<li>
				{#if row.kind === 'post'}
					<PostCard post={row.post} />
				{:else}
					<section class="prompt-group">
						<a class="pg-head" href="/home?prompt={row.prompt.id}"><span aria-hidden="true">💡</span>{tn('engagement.answersTo', row.posts.length, { title: row.prompt.title })}</a>
						{#each row.posts as p (p.id)}<PostCard post={p} showPrompt={false} />{/each}
					</section>
				{/if}
			</li>
		{/each}
	</ul>
	{#if next}<button class="btn btn-block more" onclick={() => load(false)} disabled={more}>{t('common.loadMore')}</button>{/if}
{/if}

<style>
	.toolbar {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		margin-bottom: var(--s-4);
	}
	.timeline {
		list-style: none;
		margin: 0 auto;
		padding: 0;
		max-width: 620px;
		display: flex;
		flex-direction: column;
		gap: var(--s-5);
	}
	.prompt-group {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: var(--s-3);
		border-radius: var(--r-lg);
		background: var(--warn-soft);
	}
	.pg-head {
		font-weight: 700;
		color: inherit;
		text-decoration: none;
		display: inline-flex;
		gap: var(--s-2);
	}
	.more {
		max-width: 620px;
		margin: var(--s-4) auto 0;
	}
</style>
