<script lang="ts">
	import { page } from '$app/state';
	import { api, call, type Feed, type FeedPost, type FeedPostReceipts, type Item } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { applyPostSummary, canRemovePost, receiptsVisible } from '$lib/feeds';
	import { formatDateTime, formatDuration, relativeTime } from '$lib/format';
	import { kindCounts, REACTION_EMOJI, type Reaction } from '$lib/logic';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, kindSummary, t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';
	import OnlineStatus from '../OnlineStatus.svelte';
	import Thumb from '../Thumb.svelte';
	import FeedReactions from './FeedReactions.svelte';
	import ReceiptSummary from './ReceiptSummary.svelte';
	import GoogleImport from '../sources/GoogleImport.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { summaryVia } from '$lib/sender';

	let { feed }: { feed: Feed } = $props();

	let posts = $state<FeedPost[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<unknown>(null);
	let layout = $state<'list' | 'grid'>('list');
	/** Posts being removed (undo window): hidden from the list until the toast commits or is undone. */
	let removing = $state<Set<string>>(new Set());

	const meId = $derived(auth.me?.id);
	const shown = $derived(posts.filter((p) => !removing.has(p.id)));

	async function load(reset = true) {
		if (reset) loading = true;
		else loadingMore = true;
		error = null;
		try {
			const res = await call(
				api.GET('/v1/feeds/{feedId}/posts', {
					params: { path: { feedId: feed.id }, query: { limit: 30, ...(reset || !cursor ? {} : { cursor }) } }
				})
			);
			posts = reset ? res.posts : [...posts, ...res.posts];
			cursor = res.next_cursor;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	async function refetchPost(postId: string) {
		try {
			const p = await call(api.GET('/v1/feeds/{feedId}/posts/{postId}', { params: { path: { feedId: feed.id, postId } } }));
			const i = posts.findIndex((x) => x.id === p.id);
			if (i >= 0) posts[i] = p;
			else posts = [p, ...posts];
			if (detail?.id === p.id) {
				detail = p;
				void loadReceipts(p);
			}
		} catch {
			/* gone or not visible: leave the list as it is */
		}
	}

	$effect(() => {
		void feed.id;
		void load();
		const timers = new Map<string, ReturnType<typeof setTimeout>>();
		const soon = (postId: string) => {
			clearTimeout(timers.get(postId));
			timers.set(postId, setTimeout(() => void refetchPost(postId), 300));
		};
		const off = live.on((e) => {
			if (e.type === 'feed_post.updated' && e.feed_id === feed.id) {
				if (!applyPostSummary(posts, e)) return;
				if (detail?.id === e.post_id) void loadReceipts(detail);
			} else if ((e.type === 'reaction' || e.type === 'reply') && e.feed_id === feed.id && e.post_id) soon(e.post_id);
		});
		return () => {
			off();
			for (const tm of timers.values()) clearTimeout(tm);
		};
	});

	// A push or link with ?post=<id> opens that post.
	$effect(() => {
		const wanted = page.url.searchParams.get('post');
		if (!wanted || loading) return;
		const p = posts.find((x) => x.id === wanted);
		if (p && detail?.id !== p.id) openDetail(p);
	});

	// ---------------------------------------------------------------- detail and receipts
	let detail = $state<FeedPost | null>(null);
	let detailOpen = $state(false);
	let receipts = $state<FeedPostReceipts | null>(null);
	let receiptsError = $state<unknown>(null);

	function openDetail(p: FeedPost) {
		detail = p;
		receipts = null;
		receiptsError = null;
		detailOpen = true;
		void loadReceipts(p);
	}

	async function loadReceipts(p: FeedPost) {
		const own = p.poster.user_id === meId;
		if (p.receipts == null && !receiptsVisible(feed.role, feed.members_see_reactions === true, own)) return;
		try {
			receipts = await call(
				api.GET('/v1/feeds/{feedId}/posts/{postId}/receipts', { params: { path: { feedId: feed.id, postId: p.id } } })
			);
		} catch (e) {
			receiptsError = e;
		}
	}

	const itemIndex = $derived(new Map((detail?.items ?? []).map((it, i) => [it.id, i])));

	// ---------------------------------------------------------------- unsend / remove (undo instead of "are you sure")
	function removePost(p: FeedPost) {
		const own = p.poster.user_id === meId;
		detailOpen = false;
		toasts.deferred({
			message: own ? t('feedPosts.unsent') : t('feedPosts.removed'),
			undoLabel: t('common.undo'),
			apply: () => (removing = new Set([...removing, p.id])),
			rollback: () => {
				const next = new Set(removing);
				next.delete(p.id);
				removing = next;
			},
			commit: async () => {
				await call(api.DELETE('/v1/feeds/{feedId}/posts/{postId}', { params: { path: { feedId: feed.id, postId: p.id } } }));
				posts = posts.filter((x) => x.id !== p.id);
			},
			onCommitError: (e) => {
				const next = new Set(removing);
				next.delete(p.id);
				removing = next;
				toasts.error(problemMessage(e));
			}
		});
	}

	async function removeItem(p: FeedPost, item: Item) {
		if (p.items.length <= 1) return removePost(p);
		const before = p.items;
		p.items = p.items.filter((i) => i.id !== item.id);
		try {
			await call(
				api.DELETE('/v1/feeds/{feedId}/posts/{postId}/items/{itemId}', {
					params: { path: { feedId: feed.id, postId: p.id, itemId: item.id } }
				})
			);
			toasts.success(t('feedPosts.itemRemoved'));
		} catch (e) {
			p.items = before;
			toasts.error(problemMessage(e));
		}
	}

	const STATUS_KEY = {
		pending: 'feedPosts.st.pending',
		ready: 'feedPosts.st.pending',
		delivered: 'feedPosts.st.delivered',
		seen: 'feedPosts.st.seen',
		failed: 'feedPosts.st.failed',
		awaiting_approval: 'feedPosts.st.awaiting',
		suspended: 'feedPosts.st.paused',
		removed: 'feedPosts.st.removed'
	} as const;
</script>

<div class="toolbar">
	<a class="btn btn-primary" href="/send?feed={feed.id}"><Icon name="send" size={18} />{t('feedPosts.post')}</a>
	{#if discovery.googlePhotosImport}
		<GoogleImport target={{ kind: 'feed', feedId: feed.id, name: feed.name }} backPath="/feeds/{feed.id}" onfinished={(i) => i.status === 'done' && i.progress.imported > 0 && load()} />
	{/if}
	<span class="spacer"></span>
	{#if posts.length > 0}
		<div class="segmented" role="group" aria-label={t('feedPosts.layout')}>
			<button aria-pressed={layout === 'list'} onclick={() => (layout = 'list')}>{t('feedPosts.list')}</button>
			<button aria-pressed={layout === 'grid'} onclick={() => (layout = 'grid')}>{t('feedPosts.grid')}</button>
		</div>
	{/if}
</div>

{#if loading}
	<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
		{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:140px"></div>{/each}
	</div>
{:else if error}
	<div class="callout callout-error" role="alert">
		<Icon name="alert" />
		<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div>
	</div>
{:else if shown.length === 0}
	<div class="card">
		<EmptyState compact art="send" title={t('feedPosts.emptyTitle')} body={t('feedPosts.emptyBody')}>
			{#snippet actions()}
				<a class="btn btn-primary" href="/send?feed={feed.id}"><Icon name="send" size={18} />{t('feedPosts.post')}</a>
			{/snippet}
		</EmptyState>
	</div>
{:else if layout === 'grid'}
	<ul class="grid" role="list">
		{#each shown as p (p.id)}
			{#each p.items as item, i (item.id)}
				<li>
					<button class="tile" onclick={() => openDetail(p)} aria-label={t('feedPosts.openPost', { name: p.poster.display_name, when: formatDateTime(p.created_at, i18n.tag) })}>
						<Thumb itemId={item.id} variant="thumb_256" kind={item.kind} radius="0" />
						{#if i === 0 && p.receipts}
							<span class="tile-badge num">{p.receipts.seen}/{p.receipts.frames}</span>
						{/if}
					</button>
				</li>
			{/each}
		{/each}
	</ul>
{:else}
	<ul class="posts" role="list">
		{#each shown as p (p.id)}
			{@const own = p.poster.user_id === meId}
			{@const caption = p.items.find((i) => i.caption)?.caption}
			<li class="card post" in:softFly>
				<header class="p-head">
					<Avatar name={p.poster.display_name} url={p.poster.avatar_url} size={36} />
					<div class="who">
						<strong>{p.poster.display_name}{#if own}<span class="faint you">({t('people.you')})</span>{/if}</strong>
						<time class="muted small" datetime={p.created_at} title={formatDateTime(p.created_at, i18n.tag)}>
							{summaryVia(kindSummary(kindCounts(p.items)), p.via)} · {relativeTime(p.created_at, i18n.tag)}
						</time>
					</div>
					<button class="btn btn-ghost btn-sm" onclick={() => openDetail(p)}>{t('feedPosts.details')}</button>
				</header>
				<button class="thumbs n{Math.min(p.items.length, 4)}" onclick={() => openDetail(p)} aria-label={t('feedPosts.openPost', { name: p.poster.display_name, when: formatDateTime(p.created_at, i18n.tag) })}>
					{#each p.items.slice(0, 4) as item, i (item.id)}
						<span class="t">
							<Thumb itemId={item.id} variant="thumb_256" kind={item.kind} radius="0" />
							{#if i === 3 && p.items.length > 4}<span class="more num">+{p.items.length - 4}</span>{/if}
						</span>
					{/each}
				</button>
				{#if caption}<p class="caption">“{caption}”</p>{/if}
				{#if p.receipts}
					<ReceiptSummary summary={p.receipts} />
					<FeedReactions reactions={p.reactions} />
				{:else}
					<p class="faint tiny">{t('feedPosts.receiptsHidden')}</p>
				{/if}
			</li>
		{/each}
	</ul>
{/if}

{#if cursor && !loading}
	<div class="more-row">
		<button class="btn" onclick={() => load(false)} disabled={loadingMore}>
			{#if loadingMore}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.loadMore')}
		</button>
	</div>
{/if}

<Dialog bind:open={detailOpen} title={detail ? t('feedPosts.postBy', { name: detail.poster.display_name }) : ''} size="md">
	{#if detail}
		{@const canRemove = canRemovePost(feed.role, detail, meId)}
		<div class="stack">
			<p class="muted small">{t('detail.sentAt', { when: formatDateTime(detail.created_at, i18n.tag) })}</p>
			<ul class="d-items" role="list">
				{#each detail.items as item, i (item.id)}
					<li class="d-item">
						<span class="d-thumb">
							<Thumb itemId={item.id} variant="thumb_256" kind={item.kind} alt={item.caption ?? t('detail.itemAlt', { n: i + 1 })} />
							{#if item.kind === 'video' && item.duration_ms}<span class="dur num">{formatDuration(item.duration_ms / 1000)}</span>{/if}
						</span>
						<span class="d-cap small">{item.caption ?? ''}</span>
						{#if canRemove && detail.items.length > 1}
							<button class="btn btn-danger-ghost btn-icon btn-sm" aria-label={t('feedPosts.removeItemN', { n: i + 1 })} title={t('feedPosts.removeItem')} onclick={() => detail && removeItem(detail, item)}>
								<Icon name="trash" size={16} />
							</button>
						{/if}
					</li>
				{/each}
			</ul>

			{#if detail.receipts}
				<section class="stack-sm" aria-label={t('detail.deliveryLabel')}>
					<ReceiptSummary summary={detail.receipts} />
					{#if receipts}
						<ul class="r-frames" role="list">
							{#each receipts.frames as f (f.subscription_id)}
								<li class="r-frame">
									<div class="row">
										<strong class="fname">{f.frame_name}</strong>
										{#if f.frame_location}<span class="muted small">{f.frame_location}</span>{/if}
										<span class="spacer"></span>
										{#if f.subscription_status === 'paused'}
											<span class="badge">{t('subs.status.paused')}</span>
										{:else}
											<OnlineStatus online={f.online ?? false} lastSeen={f.last_seen_at ?? null} />
										{/if}
									</div>
									<ul class="r-items" role="list">
										{#each f.items as it (it.item_id)}
											<li class="small">
												<span class="muted num">#{(itemIndex.get(it.item_id) ?? 0) + 1}</span>
												<span class="st st-{it.status}">{t(STATUS_KEY[it.status])}</span>
												{#if it.reaction}<span title={t(`reaction.${it.reaction}`)}>{REACTION_EMOJI[it.reaction as Reaction]}</span>{/if}
												{#each it.replies ?? [] as r, ri (ri)}{#if r.preset}<span class="reply">💬 {t(`reply.${r.preset}`)}</span>{/if}{/each}
											</li>
										{/each}
									</ul>
								</li>
							{:else}
								<li class="muted small">{t('feedPosts.noFrames')}</li>
							{/each}
						</ul>
					{:else if receiptsError}
						<p class="error-text small">{problemMessage(receiptsError)}</p>
					{:else}
						<div class="skeleton" style="height:60px"></div>
					{/if}
				</section>
				<FeedReactions reactions={detail.reactions} />
			{:else}
				<p class="callout small"><Icon name="eyeOff" size={18} />{t('feedPosts.receiptsHiddenLong')}</p>
			{/if}
		</div>
	{/if}
	{#snippet footer()}
		{#if detail && canRemovePost(feed.role, detail, meId)}
			<button class="btn btn-danger-ghost" onclick={() => detail && removePost(detail)}>
				<Icon name="trash" size={16} />{detail.poster.user_id === meId ? t('feedPosts.unsend') : t('feedPosts.remove')}
			</button>
		{/if}
		<span class="spacer"></span>
		<button class="btn btn-primary" onclick={() => (detailOpen = false)}>{t('common.done')}</button>
	{/snippet}
</Dialog>

<style>
	.toolbar {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.posts {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-4);
		max-width: 680px;
	}
	.post {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: var(--s-4);
	}
	.p-head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.who strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.you {
		margin-inline-start: 0.35em;
		font-weight: 500;
	}
	.thumbs {
		display: grid;
		gap: 2px;
		aspect-ratio: 16 / 10;
		border: 0;
		padding: 0;
		border-radius: var(--r);
		overflow: hidden;
		background: var(--surface-2);
		cursor: pointer;
	}
	.thumbs.n2,
	.thumbs.n4 {
		grid-template-columns: 1fr 1fr;
	}
	.thumbs.n3 {
		grid-template-columns: 2fr 1fr;
		grid-template-rows: 1fr 1fr;
	}
	.thumbs.n3 .t:first-child {
		grid-row: 1 / 3;
	}
	.thumbs.n4 {
		grid-template-rows: 1fr 1fr;
	}
	.t {
		position: relative;
		overflow: hidden;
		display: block;
		min-height: 0;
	}
	.more {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.45);
		color: #fff;
		font-weight: 700;
		font-size: 1.3rem;
	}
	.caption {
		margin: 0;
		font-style: italic;
		overflow-wrap: anywhere;
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 4px;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
	}
	.tile {
		position: relative;
		display: block;
		width: 100%;
		aspect-ratio: 1;
		border: 0;
		padding: 0;
		border-radius: var(--r-sm);
		overflow: hidden;
		cursor: pointer;
		background: var(--surface-2);
	}
	.tile-badge {
		position: absolute;
		inset-inline-end: 6px;
		bottom: 6px;
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.55);
		color: #fff;
		font-size: 0.75rem;
		font-weight: 700;
	}
	.more-row {
		display: flex;
		justify-content: center;
		margin-top: var(--s-4);
	}
	.d-items,
	.r-frames,
	.r-items {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.d-items {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
		gap: var(--s-2);
	}
	.d-item {
		display: flex;
		flex-direction: column;
		gap: 4px;
		position: relative;
	}
	.d-item .btn {
		position: absolute;
		top: 4px;
		inset-inline-end: 4px;
		background: var(--surface);
	}
	.d-thumb {
		position: relative;
		display: block;
		aspect-ratio: 1;
		border-radius: var(--r-sm);
		overflow: hidden;
	}
	.d-cap {
		overflow-wrap: anywhere;
	}
	.dur {
		position: absolute;
		inset-inline-end: 6px;
		bottom: 6px;
		padding: 1px 6px;
		border-radius: 6px;
		background: rgb(0 0 0 / 0.6);
		color: #fff;
		font-size: 0.75rem;
	}
	.r-frames {
		display: flex;
		flex-direction: column;
	}
	.r-frame {
		padding: 8px 0;
	}
	.r-frame + .r-frame {
		border-top: 1px solid var(--border);
	}
	.fname {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.r-items {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 14px;
		margin-top: 4px;
	}
	.r-items li {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.st {
		font-weight: 600;
	}
	.st-seen {
		color: var(--accent-text);
	}
	.st-failed,
	.st-removed {
		color: var(--danger);
	}
	.st-awaiting_approval,
	.st-suspended {
		color: var(--warn);
	}
</style>
