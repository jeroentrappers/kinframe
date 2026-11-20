<script lang="ts">
	import { page } from '$app/state';
	import { api, call, type FeedPrompt, type Milestone, type OnThisDay, type TimelinePost, type WeeklySummary } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { currentPrompt, lastWeekOf } from '$lib/engagement';
	import { canPostToFeed } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { frames } from '$lib/frames.svelte';
	import { dedupePosts, families, view } from '$lib/family.svelte';
	import { badge } from '$lib/social.svelte';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { appendPosts, applyPostReactions, applyThread, groupTimeline } from '$lib/social';
	import { t, tn } from '$lib/i18n/index.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import ActivitySheet from '$components/family/ActivitySheet.svelte';
	import FamilyRow from '$components/family/FamilyRow.svelte';
	import InviteSheet from '$components/family/InviteSheet.svelte';
	import SimpleView from '$components/family/SimpleView.svelte';
	import MilestoneCards from '$components/social/MilestoneCards.svelte';
	import OnThisDayCard from '$components/social/OnThisDayCard.svelte';
	import PostCard from '$components/social/PostCard.svelte';
	import PromptCard from '$components/social/PromptCard.svelte';
	import WeeklySummaryCard from '$components/social/WeeklySummaryCard.svelte';

	const PAGE = 20;
	/**
	 * Home is one timeline of all the person's families (simple-app.md §3.1); with several, chips filter it to one
	 * family. The row of people and frames is the filtered family's, with All the home family's.
	 */
	const filtered = $derived(families.filter);
	const feedId = $derived(filtered?.id ?? null);
	const current = $derived(filtered ?? families.home);
	const scope = $derived(feedId ? { feed_id: feedId } : {});
	const promptFilter = $derived(page.url.searchParams.get('prompt'));
	const weekParam = $derived(page.url.searchParams.get('week'));

	let posts = $state<TimelinePost[]>([]);
	let next = $state<string | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<unknown>(null);
	let fresh = $state<TimelinePost[]>([]);

	async function load(reset = true) {
		if (reset) loading = true;
		else loadingMore = true;
		error = null;
		try {
			const res = await call(
				api.GET('/v1/timeline', {
					params: { query: { limit: PAGE, ...scope, ...(promptFilter ? { prompt_id: promptFilter } : {}), ...(!reset && next ? { before: next } : {}) } }
				})
			);
			posts = dedupePosts(reset ? res.posts : appendPosts(posts, res.posts));
			next = res.next_before;
			if (reset) fresh = [];
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	/** There is no "new post" event (posts arrive with their sends): look for new ones when the tab comes back. */
	async function checkNew() {
		if (loading || document.visibilityState !== 'visible' || posts.length === 0) return;
		try {
			const res = await call(api.GET('/v1/timeline', { params: { query: { limit: PAGE, ...scope, ...(promptFilter ? { prompt_id: promptFilter } : {}) } } }));
			const top = posts[0]!.id;
			const candidates = res.posts.filter((p) => p.id > top && !posts.some((x) => x.id === p.id));
			fresh = dedupePosts([...posts, ...candidates]).filter((p) => candidates.includes(p));
		} catch {
			/* try again later */
		}
	}

	function showFresh() {
		posts = dedupePosts([...fresh, ...posts]);
		fresh = [];
		window.scrollTo({ top: 0, behavior: 'smooth' });
	}

	$effect(() => {
		if (!feeds.loaded || !feeds.list.length || view.simple) return;
		void feedId;
		void promptFilter;
		void load();
		const timer = setInterval(checkNew, 120_000);
		const onVis = () => void checkNew();
		document.addEventListener('visibilitychange', onVis);
		const off = live.on((e) => {
			if (e.type === 'post.updated') applyThread(posts, e.post_id, e.thread);
			else if (e.type === 'post.removed') posts = posts.filter((p) => p.id !== e.post_id);
			else if (e.type === 'feed_post.updated' && (!feedId || e.feed_id === feedId) && !posts.some((p) => p.id === e.post_id)) void checkNew();
			else if (e.type === 'reaction.updated' && e.target_kind === 'post') applyPostReactions(posts, e.target_id, e.summary);
			else if (e.type === 'thread.read') {
				const p = posts.find((x) => x.id === e.post_id);
				if (p) p.thread = { ...p.thread, unread_count: 0, unread_mentions: 0, read_up_to: e.read_up_to };
			} else if (e.type === 'prompt.updated') {
				const list = promptsByFeed[e.feed_id] ?? [];
				promptsByFeed = { ...promptsByFeed, [e.feed_id]: [e.prompt, ...list.filter((p) => p.id !== e.prompt.id)] };
			} else if (e.type === 'milestone.achieved') milestones = [e.milestone, ...milestones.filter((m) => m.key !== e.milestone.key)];
			else if (e.type === 'photo_request.created' || e.type === 'photo_request.updated' || e.type === 'streak.updated') {
				if (e.type === 'streak.updated') {
					const f = feeds.get(e.feed_id);
					if (f) feeds.upsert({ ...f, streak: e.streak });
				} else void frames.load(true);
			}
		});
		return () => {
			off();
			clearInterval(timer);
			document.removeEventListener('visibilitychange', onVis);
		};
	});

	// ---- engagement: On this day, milestones, last week's summary, this week's prompts
	let onThisDay = $state<OnThisDay | null>(null);
	let milestones = $state<Milestone[]>([]);
	let weekly = $state<WeeklySummary | null>(null);
	let promptsByFeed = $state<Record<string, FeedPrompt[]>>({});
	const DISMISS_KEY = 'kinframe.weeklyDismissed';

	$effect(() => {
		if (!discovery.engagement) return;
		void frames.load();
		call(api.GET('/v1/me/on-this-day'))
			.then((d) => (onThisDay = d.groups.length ? d : null))
			.catch(() => (onThisDay = null));
		call(api.GET('/v1/me/milestones'))
			.then((m) => (milestones = m))
			.catch(() => (milestones = []));
	});

	$effect(() => {
		if (!discovery.engagement) return;
		const week = weekParam ?? lastWeekOf(new Date());
		let dismissed: string | null = null;
		try {
			dismissed = localStorage.getItem(DISMISS_KEY);
		} catch {
			/* storage unavailable */
		}
		if (!weekParam && dismissed === week) return;
		call(api.GET('/v1/me/weekly-summaries/{weekOf}', { params: { path: { weekOf: week } } }))
			.then((s) => (weekly = s.seen_count > 0 ? s : null))
			.catch(() => (weekly = null));
	});

	function dismissWeekly() {
		try {
			if (weekly) localStorage.setItem(DISMISS_KEY, weekly.week_of);
		} catch {
			/* storage unavailable */
		}
		weekly = null;
	}

	$effect(() => {
		if (!discovery.engagement || !feeds.loaded) return;
		for (const f of feeds.list.filter((x) => !feedId || x.id === feedId)) {
			if (promptsByFeed[f.id]) continue;
			call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: f.id } } }))
				.then((list) => (promptsByFeed = { ...promptsByFeed, [f.id]: list }))
				.catch(() => (promptsByFeed = { ...promptsByFeed, [f.id]: [] }));
		}
	});

	const currentPrompts = $derived(
		feeds.list
			.filter((f) => !feedId || f.id === feedId)
			.map((f) => ({ family: f.name, canPost: canPostToFeed(f.role), prompt: currentPrompt(promptsByFeed[f.id] ?? []) }))
			.filter((x): x is { family: string; canPost: boolean; prompt: FeedPrompt } => x.prompt !== null)
	);
	const filterPrompt = $derived(promptFilter ? Object.values(promptsByFeed).flat().find((p) => p.id === promptFilter) : null);

	const rows = $derived(groupTimeline(posts));

	// ---- the top bar ("Home" and the activity button), then the filter chips with several families
	let activityOpen = $state(false);
	let inviteOpen = $state(false);
	const several = $derived(feeds.list.length > 1);
	const alone = $derived((current?.member_count ?? 0) <= 1);
</script>

<svelte:head><title>{t('social.homeTitle')} · kinframe</title></svelte:head>

{#if current && view.simple}
	<SimpleView />
{:else if current}
	<div class="container page home">
		<header class="topbar">
			{#if promptFilter}
				<a class="btn btn-ghost btn-sm" href="/home"><Icon name="chevronLeft" size={16} />{t('social.homeTitle')}</a>
				<h1 class="h-prompt">{t('engagement.answersTitle')}</h1>
			{:else}
				<h1>{t('social.homeTitle')}</h1>
			{/if}
			<span class="spacer"></span>
			<button class="btn btn-ghost btn-icon activity" onclick={() => (activityOpen = true)} aria-label={t('family.activity')}>
				<Icon name="bell" size={22} />
				{#if badge.badge.threads > 0}<span class="dot-badge num">{badge.badge.threads > 99 ? '99+' : badge.badge.threads}</span>{/if}
			</button>
		</header>

		{#if several && !promptFilter}
			<div class="chips" role="group" aria-label={t('home.filterLabel')}>
				<button class="chip" aria-pressed={!filtered} onclick={() => families.setFilter(null)}>{t('home.all')}</button>
				{#each families.ranked as f (f.id)}
					<button class="chip" aria-pressed={filtered?.id === f.id} onclick={() => families.setFilter(f.id)}>{f.name}</button>
				{/each}
			</div>
		{/if}

		{#if !promptFilter}
			<FamilyRow feed={current} oninvite={() => (inviteOpen = true)} />
		{/if}

		<div class="feed-col stack">
			{#if promptFilter && filterPrompt}
				<p class="prompt-head"><span aria-hidden="true">💡</span>{tn('engagement.answersTo', filterPrompt.answer_count ?? posts.length, { title: filterPrompt.title })}</p>
			{/if}
			{#if !promptFilter}
				<MilestoneCards bind:milestones />
				{#if weekly}<WeeklySummaryCard summary={weekly} ondismiss={weekParam ? undefined : dismissWeekly} />{/if}
				{#each currentPrompts as p (p.prompt.id)}
					<PromptCard prompt={p.prompt} familyName={p.family} canPost={p.canPost} />
				{/each}
				{#if onThisDay}<OnThisDayCard day={onThisDay} />{/if}
			{/if}

			{#if fresh.length > 0}
				<button class="fresh-pill btn btn-primary btn-sm" onclick={showFresh}><Icon name="refresh" size={16} />{tn('social.newPosts', fresh.length)}</button>
			{/if}

			{#if loading}
				<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
					{#each [0, 1] as i (i)}<div class="skeleton" style="height:420px"></div>{/each}
				</div>
			{:else if error}
				<div class="callout callout-error" role="alert">
					<Icon name="alert" />
					<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div>
				</div>
			{:else if posts.length === 0}
				<div class="card">
					<EmptyState compact art="send" title={t('family.emptyTitle')} body={alone ? t('family.emptyAlone') : t('family.emptyBody')}>
						{#snippet actions()}
							{#if canPostToFeed(current.role)}<a class="btn btn-primary" href="/send"><Icon name="plus" size={18} />{t('family.shareFirst')}</a>{/if}
							{#if alone}<button class="btn" onclick={() => (inviteOpen = true)}><Icon name="users" size={18} />{t('family.invite')}</button>{/if}
						{/snippet}
					</EmptyState>
				</div>
			{:else}
				<ul class="timeline" role="list" aria-label={t('social.timeline')}>
					{#each rows as row (row.kind === 'post' ? row.post.id : `prompt:${row.prompt.id}:${row.posts[0]!.id}`)}
						<li>
							{#if row.kind === 'post'}
								<PostCard post={row.post} showFamily={several} />
							{:else}
								<section class="prompt-group" aria-label={tn('engagement.answersTo', row.posts.length, { title: row.prompt.title })}>
									<a class="pg-head" href="/home?prompt={row.prompt.id}"><span aria-hidden="true">💡</span>{tn('engagement.answersTo', row.posts.length, { title: row.prompt.title })}</a>
									<div class="stack">
										{#each row.posts as p (p.id)}<PostCard post={p} showPrompt={false} showFamily={several} />{/each}
									</div>
								</section>
							{/if}
						</li>
					{/each}
				</ul>
				{#if next}
					<button class="btn btn-block" onclick={() => load(false)} disabled={loadingMore}>
						{#if loadingMore}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.loadMore')}
					</button>
				{:else}
					<p class="end faint small">{t('social.endOfTimeline')}</p>
				{/if}
			{/if}
		</div>
	</div>

	<ActivitySheet bind:open={activityOpen} feedIds={filtered ? [filtered.id] : feeds.list.map((f) => f.id)} />
	<InviteSheet bind:open={inviteOpen} feed={current} />
{:else}
	<div class="container page" aria-busy="true" aria-label={t('common.loading')}><div class="skeleton" style="height:420px"></div></div>
{/if}

<style>
	.topbar {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		margin-bottom: var(--s-3);
	}
	.topbar h1 {
		font-size: 1.6rem;
		margin: 0;
		overflow-wrap: anywhere;
	}
	.chips {
		display: flex;
		gap: var(--s-2);
		overflow-x: auto;
		margin: 0 calc(-1 * var(--s-4)) var(--s-3);
		padding: 2px var(--s-4);
		scrollbar-width: none;
	}
	.chip {
		flex: none;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		font-size: 0.9rem;
		padding: 6px 14px;
		cursor: pointer;
		white-space: nowrap;
	}
	.chip[aria-pressed='true'] {
		background: var(--text);
		border-color: var(--text);
		color: var(--bg);
	}
	.h-prompt {
		font-size: 1.2rem !important;
	}
	.activity {
		position: relative;
	}
	.dot-badge {
		position: absolute;
		top: 2px;
		inset-inline-end: 2px;
		min-width: 18px;
		height: 18px;
		padding: 0 5px;
		border-radius: var(--r-full);
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.68rem;
		font-weight: 800;
		display: inline-grid;
		place-items: center;
		line-height: 1;
	}
	.feed-col {
		max-width: 620px;
		margin-inline: auto;
		margin-top: var(--s-4);
	}
	.timeline {
		list-style: none;
		margin: 0;
		padding: 0;
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
	.pg-head,
	.prompt-head {
		display: inline-flex;
		gap: var(--s-2);
		font-weight: 700;
		color: inherit;
		text-decoration: none;
		margin: 0;
	}
	.pg-head:hover {
		text-decoration: underline;
	}
	.fresh-pill {
		position: sticky;
		top: calc(var(--nav-h) + 8px);
		z-index: 10;
		align-self: center;
		box-shadow: var(--shadow);
	}
	.end {
		text-align: center;
	}
	@media (max-width: 480px) {
		.timeline {
			gap: var(--s-4);
		}
		.feed-col :global(.post) {
			margin-inline: calc(-1 * var(--s-2));
		}
	}
</style>
