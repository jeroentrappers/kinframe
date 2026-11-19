<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Feed } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { canInviteToFeed, isFeedAdmin } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from '$components/Icon.svelte';
	import FeedBirthdays from '$components/feed/FeedBirthdays.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';
	import FeedInvites from '$components/feed/FeedInvites.svelte';
	import FeedMembers from '$components/feed/FeedMembers.svelte';
	import FeedPosts from '$components/feed/FeedPosts.svelte';
	import FeedRoleBadge from '$components/feed/FeedRoleBadge.svelte';
	import FeedSettings from '$components/feed/FeedSettings.svelte';
	import FeedSubscriptions from '$components/feed/FeedSubscriptions.svelte';
	import PhotoSources from '$components/sources/PhotoSources.svelte';
	import EmailInbox from '$components/inbox/EmailInbox.svelte';
	import TelegramSection from '$components/telegram/TelegramSection.svelte';
	import EngagementSettings from '$components/social/EngagementSettings.svelte';
	import FeedCircles from '$components/social/FeedCircles.svelte';
	import FeedSocialBar from '$components/social/FeedSocialBar.svelte';
	import FeedTimeline from '$components/social/FeedTimeline.svelte';
	import ReportList from '$components/social/ReportList.svelte';
	import TakenAwayGroups from '$components/leaving/TakenAwayGroups.svelte';

	type Tab = 'posts' | 'birthdays' | 'members' | 'circles' | 'subscriptions' | 'sources' | 'invites' | 'reports' | 'settings';
	/** Family social: the posts tab is a timeline with threads; the classic list keeps the per-frame details. */
	let classicPosts = $state(false);

	let feed = $state<Feed | null>(null);
	let error = $state<unknown>(null);
	const id = $derived(page.params.id!);

	async function load(feedId: string) {
		try {
			feed = (await call(api.GET('/v1/feeds/{feedId}', { params: { path: { feedId } } }))) as Feed;
			feeds.upsert(feed);
			error = null;
		} catch (e) {
			if (feed && isCode(e, 'not_found')) {
				// Removed from the feed, or the feed was deleted, while we were looking at it.
				feeds.remove(feedId);
				toasts.show(t('feed.gone'));
				void goto('/feeds');
				return;
			}
			error = e;
		}
	}

	$effect(() => {
		const feedId = id;
		feed = null;
		error = null;
		void load(feedId);
		return live.on((e) => {
			if (e.type === 'feed.membership_updated' && e.feed_id === feedId) void load(feedId);
			if ((e.type === 'subscription.request' || e.type === 'feed.join_request') && e.feed_id === feedId) void load(feedId);
			// The suggestion badge (pending_birthday_count) and the birthday count.
			if ((e.type === 'birthday.suggested' || e.type === 'birthday.decided' || e.type === 'birthday.removed') && e.feed_id === feedId) void load(feedId);
		});
	});

	const tabs = $derived.by(() => {
		if (!feed) return [];
		const list: { id: Tab; label: MessageKey; icon: IconName; badge?: number }[] = [
			{ id: 'posts', label: 'feed.tabPosts', icon: 'image' },
			...(discovery.birthdays
				? [{ id: 'birthdays' as const, label: 'feed.tabBirthdays' as const, icon: 'cake' as const, badge: (isFeedAdmin(feed.role) && feed.pending_birthday_count) || undefined }]
				: []),
			{ id: 'members', label: 'feed.tabMembers', icon: 'users', badge: feed.pending_join_request_count || undefined },
			...(discovery.social ? [{ id: 'circles' as const, label: 'social.tabCircles' as const, icon: 'users' as const }] : []),
			{ id: 'subscriptions', label: 'feed.tabFrames', icon: 'frame', badge: feed.pending_subscription_count || undefined }
		];
		// Photo sources (photo-sources.md §4.2): the feed's owner and admins.
		if (discovery.photoSources && isFeedAdmin(feed.role)) list.push({ id: 'sources', label: 'feed.tabSources', icon: 'refresh' });
		if (canInviteToFeed(feed)) list.push({ id: 'invites', label: 'feed.tabInvites', icon: 'link' });
		if (discovery.chats && isFeedAdmin(feed.role)) list.push({ id: 'reports', label: 'social.tabReports', icon: 'flag' });
		if (isFeedAdmin(feed.role)) list.push({ id: 'settings', label: 'feed.tabSettings', icon: 'settings' });
		return list;
	});

	const tab = $derived.by((): Tab => {
		const q = page.url.searchParams.get('tab') as Tab | null;
		return tabs.some((x) => x.id === q) ? q! : 'posts';
	});

	function selectTab(next: Tab) {
		const url = new URL(page.url);
		url.searchParams.delete('post');
		if (next === 'posts') url.searchParams.delete('tab');
		else url.searchParams.set('tab', next);
		void goto(url, { replaceState: true, noScroll: true, keepFocus: true });
	}

	function onTabKey(e: KeyboardEvent, index: number) {
		const forward = i18n.dir === 'rtl' ? 'ArrowLeft' : 'ArrowRight';
		const back = i18n.dir === 'rtl' ? 'ArrowRight' : 'ArrowLeft';
		const dir = e.key === forward ? 1 : e.key === back ? -1 : 0;
		if (!dir) return;
		e.preventDefault();
		const next = tabs[(index + dir + tabs.length) % tabs.length]!;
		selectTab(next.id);
		document.getElementById(`ftab-${next.id}`)?.focus();
	}
</script>

<svelte:head><title>{feed?.name ?? t('feeds.title')} · kinframe</title></svelte:head>

<div class="container page">
	<a href="/feeds" class="back"><Icon name="chevronLeft" size={18} />{t('feeds.title')}</a>

	{#if !discovery.feeds}
		<p class="callout"><Icon name="info" size={18} />{t('feeds.offBody')}</p>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm">
				<span>{isCode(error, 'not_found') ? t('feed.notFound') : problemMessage(error)}</span>
				<a class="btn btn-sm" href="/feeds">{t('feeds.title')}</a>
			</div>
		</div>
	{:else if !feed}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:56px;width:45%"></div>
			<div class="skeleton" style="height:44px;width:100%;max-width:520px"></div>
			<div class="skeleton" style="height:220px"></div>
		</div>
	{:else}
		<header class="head">
			<FeedCover name={feed.name} url={feed.cover_url} size={64} />
			<div class="title">
				<h1>{feed.name}</h1>
				<div class="row-wrap meta">
					<FeedRoleBadge role={feed.role} />
					<span class="muted small">{tn('feeds.frames', feed.subscription_count)} · {tn('feeds.members', feed.member_count)}{#if feed.owner_name && feed.role !== 'owner'} · {t('feed.ownedBy', { name: feed.owner_name })}{/if}</span>
				</div>
			</div>
		</header>

		{#if discovery.social || discovery.engagement || discovery.chats}
			<FeedSocialBar {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
		{/if}

		<div class="tabs segmented" role="tablist" aria-label={t('feed.sections')}>
			{#each tabs as tb, i (tb.id)}
				<button
					id="ftab-{tb.id}"
					role="tab"
					aria-selected={tab === tb.id}
					aria-controls="fpanel-{tb.id}"
					tabindex={tab === tb.id ? 0 : -1}
					onclick={() => selectTab(tb.id)}
					onkeydown={(e) => onTabKey(e, i)}
				>
					<Icon name={tb.icon} size={16} />{t(tb.label)}{#if tb.badge}<span class="count num" aria-label={tn('feed.pending', tb.badge)}>{tb.badge}</span>{/if}
				</button>
			{/each}
		</div>

		<div id="fpanel-{tab}" role="tabpanel" aria-labelledby="ftab-{tab}" class="panel" tabindex="-1">
			{#if tab === 'posts'}
				{#if discovery.leaving && isFeedAdmin(feed.role)}<TakenAwayGroups scope="feed" id={feed.id} />{/if}
				{#if discovery.social && !classicPosts}
					<FeedTimeline {feed} ondetails={() => (classicPosts = true)} />
				{:else}
					{#if discovery.social}<button class="btn btn-ghost btn-sm back-timeline" onclick={() => (classicPosts = false)}><Icon name="chevronLeft" size={16} />{t('social.backToTimeline')}</button>{/if}
					<FeedPosts {feed} />
				{/if}
			{:else if tab === 'circles'}
				<FeedCircles {feed} />
			{:else if tab === 'reports'}
				<ReportList feedId={feed.id} load={() => call(api.GET('/v1/feeds/{feedId}/reports', { params: { path: { feedId: feed!.id } } }))} />
			{:else if tab === 'birthdays'}
				<FeedBirthdays {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
			{:else if tab === 'members'}
				<FeedMembers {feed} onchanged={() => void load(feed!.id)} />
			{:else if tab === 'subscriptions'}
				<FeedSubscriptions {feed} onchanged={() => void load(feed!.id)} />
			{:else if tab === 'sources'}
				<PhotoSources target={{ kind: 'feed', feedId: feed.id, name: feed.name }} />
			{:else if tab === 'invites'}
				<FeedInvites {feed} />
			{:else if tab === 'settings'}
				<FeedSettings {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
				{#if discovery.engagement}<EngagementSettings {feed} />{/if}
				<!-- Email and Telegram post to the feed (email-inbox.md §4.2, telegram.md §4.2): the owner and admins. -->
				{#if discovery.emailInbox}
					<EmailInbox target={{ kind: 'feed', id: feed.id, name: feed.name }} focus={page.url.searchParams.get('email')} />
				{/if}
				{#if discovery.telegram}
					<TelegramSection target={{ kind: 'feed', id: feed.id, name: feed.name }} focus={page.url.searchParams.get('telegram')} />
				{/if}
			{/if}
		</div>
	{/if}
</div>

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.head {
		display: flex;
		align-items: center;
		gap: var(--s-4);
		margin-bottom: var(--s-5);
	}
	.title {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	h1 {
		overflow-wrap: anywhere;
	}
	.meta {
		gap: var(--s-3);
	}
	.tabs {
		margin-bottom: var(--s-5);
	}
	.count {
		min-width: 20px;
		padding: 0 6px;
		border-radius: var(--r-full);
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.72rem;
		line-height: 20px;
		text-align: center;
	}
	@media (max-width: 480px) {
		.tabs :global(svg) {
			display: none;
		}
		.tabs button {
			padding: 0 9px;
			font-size: 0.88rem;
		}
	}
	.panel {
		outline: none;
	}
	.back-timeline {
		margin-bottom: var(--s-3);
	}
</style>
