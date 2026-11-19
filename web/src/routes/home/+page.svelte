<script lang="ts">
	import { page } from '$app/state';
	import { api, call, type FeedPrompt, type FrameSummary, type Milestone, type OnThisDay, type TimelinePost, type WeeklySummary } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { currentPrompt, lastWeekOf } from '$lib/engagement';
	import { canPostToFeed } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { frames } from '$lib/frames.svelte';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { appendPosts, applyPostReactions, applyThread, groupTimeline } from '$lib/social';
	import { t, tn } from '$lib/i18n/index.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import HomeStrip from '$components/social/HomeStrip.svelte';
	import MilestoneCards from '$components/social/MilestoneCards.svelte';
	import OnThisDayCard from '$components/social/OnThisDayCard.svelte';
	import PostCard from '$components/social/PostCard.svelte';
	import PromptCard from '$components/social/PromptCard.svelte';
	import SuggestDialog from '$components/social/SuggestDialog.svelte';
	import WeeklySummaryCard from '$components/social/WeeklySummaryCard.svelte';

	const PAGE = 20;
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
					params: { query: { limit: PAGE, ...(promptFilter ? { prompt_id: promptFilter } : {}), ...(!reset && next ? { before: next } : {}) } }
				})
			);
			posts = reset ? res.posts : appendPosts(posts, res.posts);
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
			const res = await call(api.GET('/v1/timeline', { params: { query: { limit: PAGE, ...(promptFilter ? { prompt_id: promptFilter } : {}) } } }));
			const top = posts[0]!.id;
			fresh = res.posts.filter((p) => p.id > top && !posts.some((x) => x.id === p.id));
		} catch {
			/* try again later */
		}
	}

	function showFresh() {
		posts = [...fresh, ...posts];
		fresh = [];
		window.scrollTo({ top: 0, behavior: 'smooth' });
	}

	$effect(() => {
		if (!discovery.social) return;
		void promptFilter;
		void load();
		const timer = setInterval(checkNew, 120_000);
		const onVis = () => void checkNew();
		document.addEventListener('visibilitychange', onVis);
		const off = live.on((e) => {
			if (e.type === 'post.updated') applyThread(posts, e.post_id, e.thread);
			else if (e.type === 'post.removed') posts = posts.filter((p) => p.id !== e.post_id);
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
		for (const f of feeds.list) {
			if (promptsByFeed[f.id]) continue;
			call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: f.id } } }))
				.then((list) => (promptsByFeed = { ...promptsByFeed, [f.id]: list }))
				.catch(() => (promptsByFeed = { ...promptsByFeed, [f.id]: [] }));
		}
	});

	const currentPrompts = $derived(
		feeds.list
			.map((f) => ({ family: f.name, canPost: canPostToFeed(f.role), prompt: currentPrompt(promptsByFeed[f.id] ?? []) }))
			.filter((x): x is { family: string; canPost: boolean; prompt: FeedPrompt } => x.prompt !== null)
	);
	const filterPrompt = $derived(promptFilter ? Object.values(promptsByFeed).flat().find((p) => p.id === promptFilter) : null);

	// ---- the strip: frames, then families
	let suggestFor = $state<FrameSummary | null>(null);
	let suggestOpen = $state(false);
	const rows = $derived(groupTimeline(posts));
</script>

<svelte:head><title>{t('social.homeTitle')} · kinframe</title></svelte:head>

<div class="container page home">
	{#if !discovery.social}
		<p class="callout"><Icon name="info" size={18} />{t('social.offBody')}</p>
	{:else}
		<div class="page-header">
			<h1>{promptFilter ? t('engagement.answersTitle') : t('social.homeTitle')}</h1>
			{#if promptFilter}<a class="btn btn-ghost btn-sm" href="/home"><Icon name="chevronLeft" size={16} />{t('social.homeTitle')}</a>{/if}
		</div>

		{#if !promptFilter}
			<HomeStrip
				frames={frames.list}
				families={feeds.list}
				onframe={(f) => {
					suggestFor = f;
					suggestOpen = true;
				}}
			/>
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
					<EmptyState compact art="send" title={t('social.emptyTitle')} body={feeds.list.length ? t('social.emptyBody') : t('social.emptyNoFamily')}>
						{#snippet actions()}
							{#if feeds.list.length}
								<a class="btn btn-primary" href="/send"><Icon name="send" size={18} />{t('nav.sendPhotos')}</a>
							{:else}
								<a class="btn btn-primary" href="/feeds"><Icon name="users" size={18} />{t('feeds.title')}</a>
							{/if}
						{/snippet}
					</EmptyState>
				</div>
			{:else}
				<ul class="timeline" role="list" aria-label={t('social.timeline')}>
					{#each rows as row (row.kind === 'post' ? row.post.id : `prompt:${row.prompt.id}:${row.posts[0]!.id}`)}
						<li>
							{#if row.kind === 'post'}
								<PostCard post={row.post} />
							{:else}
								<section class="prompt-group" aria-label={tn('engagement.answersTo', row.posts.length, { title: row.prompt.title })}>
									<a class="pg-head" href="/home?prompt={row.prompt.id}"><span aria-hidden="true">💡</span>{tn('engagement.answersTo', row.posts.length, { title: row.prompt.title })}</a>
									<div class="stack">
										{#each row.posts as p (p.id)}<PostCard post={p} showPrompt={false} />{/each}
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
	{/if}
</div>

{#if suggestFor}
	<SuggestDialog bind:open={suggestOpen} frame={suggestFor} {onThisDay} prompts={currentPrompts} />
{/if}

<style>
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
