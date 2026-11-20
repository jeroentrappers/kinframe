<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Feed } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { canInviteToFeed, canPostToFeed, isFeedAdmin } from '$lib/feeds';
	import GoogleImport from '$components/sources/GoogleImport.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { auth } from '$lib/auth.svelte';
	import { directory } from '$lib/family.svelte';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from '$components/Icon.svelte';
	import FeedBirthdays from '$components/feed/FeedBirthdays.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';
	import FeedInvites from '$components/feed/FeedInvites.svelte';
	import FeedMembers from '$components/feed/FeedMembers.svelte';
	import FeedSettings from '$components/feed/FeedSettings.svelte';
	import FeedSubscriptions from '$components/feed/FeedSubscriptions.svelte';
	import PhotoSources from '$components/sources/PhotoSources.svelte';
	import EmailInbox from '$components/inbox/EmailInbox.svelte';
	import TelegramSection from '$components/telegram/TelegramSection.svelte';
	import EngagementSettings from '$components/social/EngagementSettings.svelte';
	import FeedCircles from '$components/social/FeedCircles.svelte';
	import FeedSocialBar from '$components/social/FeedSocialBar.svelte';
	import AddFrameSheet from '$components/family/AddFrameSheet.svelte';
	import FamilyPhotos from '$components/family/FamilyPhotos.svelte';
	import InviteSheet from '$components/family/InviteSheet.svelte';
	import ReportList from '$components/social/ReportList.svelte';
	import TakenAwayGroups from '$components/leaving/TakenAwayGroups.svelte';

	// The family as a profile (simple-app.md §3.4): segments Photos, People and Frames. The other ids are the family's
	// settings sections, opened from Settings › This family (one section per page, back to Settings).
	type Segment = 'posts' | 'members' | 'subscriptions';
	type Section = 'birthdays' | 'circles' | 'sources' | 'invites' | 'reports' | 'settings';

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
				// Removed from the family, or it was deleted, while we were looking at it.
				feeds.remove(feedId);
				toasts.show(t('feed.gone'));
				void goto('/home');
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
		void directory.load(feedId, true);
		return live.on((e) => {
			if (e.type === 'feed.membership_updated' && e.feed_id === feedId) (void load(feedId), void directory.load(feedId, true));
			if ((e.type === 'subscription.request' || e.type === 'subscription.updated') && e.feed_id === feedId) (void load(feedId), void directory.load(feedId, true));
			if (e.type === 'feed.join_request' && e.feed_id === feedId) void load(feedId);
			// The suggestion badge (pending_birthday_count) and the birthday count.
			if ((e.type === 'birthday.suggested' || e.type === 'birthday.decided' || e.type === 'birthday.removed') && e.feed_id === feedId) void load(feedId);
		});
	});

	const sections = $derived.by(() => {
		if (!feed) return [] as Section[];
		const list: Section[] = ['circles'];
		if (discovery.birthdays) list.push('birthdays');
		if (isFeedAdmin(feed.role) || (discovery.googlePhotosImport && canPostToFeed(feed.role))) list.push('sources');
		if (canInviteToFeed(feed)) list.push('invites');
		if (discovery.chats && isFeedAdmin(feed.role)) list.push('reports');
		if (isFeedAdmin(feed.role)) list.push('settings');
		return list;
	});
	const SECTION_TITLE: Record<Section, MessageKey> = {
		birthdays: 'feed.tabBirthdays',
		circles: 'social.tabCircles',
		sources: 'family.sectionWays',
		invites: 'family.sectionInvites',
		reports: 'social.tabReports',
		settings: 'family.sectionGeneral'
	};

	const q = $derived(page.url.searchParams.get('tab'));
	const section = $derived(sections.includes(q as Section) ? (q as Section) : null);
	const segment = $derived<Segment>(q === 'members' || q === 'subscriptions' ? q : 'posts');
	const segments = $derived.by(() => {
		if (!feed) return [];
		const list: { id: Segment; label: MessageKey; icon: IconName; badge?: number }[] = [
			{ id: 'posts', label: 'family.photos', icon: 'image' },
			{ id: 'members', label: 'family.peopleTab', icon: 'users', badge: feed.pending_join_request_count || undefined },
			{ id: 'subscriptions', label: 'family.framesTab', icon: 'frame', badge: (isFeedAdmin(feed.role) && feed.pending_subscription_count) || undefined }
		];
		return list;
	});

	function selectSegment(next: Segment) {
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
		const next = segments[(index + dir + segments.length) % segments.length]!;
		selectSegment(next.id);
		document.getElementById(`ftab-${next.id}`)?.focus();
	}

	// ---- the header: counts, member names, Invite family and Add a frame
	const members = $derived(directory.members[id] ?? []);
	const memberLine = $derived.by(() => {
		const names = members.map((m) => (m.user_id === auth.me?.id ? t('family.you') : m.display_name));
		if (names.length <= 4) return new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(names);
		return tn('family.namesAndMore', names.length - 3, { names: names.slice(0, 3).join(', ') });
	});
	let inviteOpen = $state(false);
	let addOpen = $state(false);
	// "?invite=1": straight from getting started after pairing a frame, open the invite sheet.
	$effect(() => {
		if (page.url.searchParams.get('invite') === '1' && feed) untrack(() => (inviteOpen = true));
	});
	let subsVersion = $state(0);
	$effect(() => {
		// A frame was added from the sheet: refresh the Frames segment.
		void directory.frames[id];
		untrack(() => subsVersion++);
	});
</script>

<svelte:head><title>{feed?.name ?? t('nav.family')} · kinframe</title></svelte:head>

<div class="container page">
	{#if section}<a href="/settings?family={id}" class="back"><Icon name="chevronLeft" size={18} />{t('nav.settings')}</a>{/if}

	{#if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm">
				<span>{isCode(error, 'not_found') ? t('feed.notFound') : problemMessage(error)}</span>
				<a class="btn btn-sm" href="/home">{t('nav.home')}</a>
			</div>
		</div>
	{:else if !feed}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:96px;width:60%"></div>
			<div class="skeleton" style="height:44px;width:100%;max-width:520px"></div>
			<div class="skeleton" style="height:220px"></div>
		</div>
	{:else if section}
		<h1 class="section-h">{t(SECTION_TITLE[section])}</h1>
		<p class="muted section-of">{feed.name}</p>
		<div class="panel">
			{#if section === 'circles'}
				<FeedCircles {feed} />
			{:else if section === 'reports'}
				<ReportList feedId={feed.id} load={() => call(api.GET('/v1/feeds/{feedId}/reports', { params: { path: { feedId: feed!.id } } }))} />
			{:else if section === 'birthdays'}
				<FeedBirthdays {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
			{:else if section === 'sources'}
				<div class="stack">
					{#if discovery.googlePhotosImport && canPostToFeed(feed.role)}
						<div><GoogleImport target={{ kind: 'feed', feedId: feed.id, name: feed.name }} backPath="/feeds/{feed.id}?tab=sources" /></div>
					{/if}
					{#if discovery.photoSources && isFeedAdmin(feed.role)}<PhotoSources target={{ kind: 'feed', feedId: feed.id, name: feed.name }} />{/if}
					<!-- Email and Telegram post to the family (email-inbox.md §4.2, telegram.md §4.2): the owner and admins. -->
					{#if discovery.emailInbox && isFeedAdmin(feed.role)}
						<EmailInbox target={{ kind: 'feed', id: feed.id, name: feed.name }} focus={page.url.searchParams.get('email')} />
					{/if}
					{#if discovery.telegram && isFeedAdmin(feed.role)}
						<TelegramSection target={{ kind: 'feed', id: feed.id, name: feed.name }} focus={page.url.searchParams.get('telegram')} />
					{/if}
				</div>
			{:else if section === 'invites'}
				<FeedInvites {feed} />
			{:else if section === 'settings'}
				<FeedSettings {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
				{#if discovery.engagement}<EngagementSettings {feed} />{/if}
			{/if}
		</div>
	{:else}
		<header class="profile">
			<div class="top-row">
				{#if feeds.list.length > 1}<a class="btn btn-ghost btn-icon" href="/family" aria-label={t('family.switchTitle')}><Icon name="chevronLeft" size={22} /></a>{/if}
				<h1>{feed.name}</h1>
				<a class="btn btn-ghost btn-icon gear" href="/settings?family={feed.id}" aria-label={t('nav.settings')}><Icon name="settings" size={22} /></a>
			</div>
			<div class="id-row">
				<span class="round"><FeedCover name={feed.name} url={feed.cover_url} size={84} /></span>
				<dl class="counts">
					<div><dt>{tn('family.postsLabel', feed.post_count ?? 0)}</dt><dd class="num">{feed.post_count ?? 0}</dd></div>
					<div><dt>{tn('family.peopleLabel', feed.member_count)}</dt><dd class="num">{feed.member_count}</dd></div>
					<div><dt>{tn('family.framesLabel', feed.subscription_count)}</dt><dd class="num">{feed.subscription_count}</dd></div>
				</dl>
			</div>
			{#if memberLine}<p class="muted small names">{memberLine}</p>{/if}
			<div class="buttons">
				<button class="btn btn-primary" onclick={() => (inviteOpen = true)}><Icon name="users" size={18} />{t('family.invite')}</button>
				<button class="btn" onclick={() => (addOpen = true)}><Icon name="frame" size={18} />{t('family.addFrame')}</button>
			</div>
		</header>

		{#if discovery.social || discovery.engagement || discovery.chats}
			<FeedSocialBar {feed} onupdated={(f) => (feed = { ...feed!, ...f })} />
		{/if}

		<div class="tabs segmented" role="tablist" aria-label={t('feed.sections')}>
			{#each segments as tb, i (tb.id)}
				<button
					id="ftab-{tb.id}"
					role="tab"
					aria-selected={segment === tb.id}
					aria-controls="fpanel-{tb.id}"
					tabindex={segment === tb.id ? 0 : -1}
					onclick={() => selectSegment(tb.id)}
					onkeydown={(e) => onTabKey(e, i)}
				>
					<Icon name={tb.icon} size={16} />{t(tb.label)}{#if tb.badge}<span class="count num" aria-label={tn('feed.pending', tb.badge)}>{tb.badge}</span>{/if}
				</button>
			{/each}
		</div>

		<div id="fpanel-{segment}" role="tabpanel" aria-labelledby="ftab-{segment}" class="panel" tabindex="-1">
			{#if segment === 'posts'}
				{#if discovery.leaving && isFeedAdmin(feed.role)}<TakenAwayGroups scope="feed" id={feed.id} />{/if}
				<FamilyPhotos feedId={feed.id} />
			{:else if segment === 'members'}
				<FeedMembers {feed} onchanged={() => (void load(feed!.id), void directory.load(feed!.id, true))} />
			{:else}
				{#key subsVersion}<FeedSubscriptions {feed} onchanged={() => (void load(feed!.id), void directory.load(feed!.id, true))} />{/key}
			{/if}
		</div>

		<InviteSheet bind:open={inviteOpen} {feed} />
		<AddFrameSheet bind:open={addOpen} {feed} />
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
	.profile {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		margin-bottom: var(--s-4);
		max-width: 620px;
	}
	.top-row {
		display: flex;
		align-items: center;
		gap: var(--s-2);
	}
	.top-row h1 {
		font-size: 1.6rem;
		margin: 0;
		overflow-wrap: anywhere;
	}
	.gear {
		margin-inline-start: auto;
	}
	.id-row {
		display: flex;
		align-items: center;
		gap: var(--s-5);
	}
	.round :global(*) {
		border-radius: 50% !important;
	}
	.counts {
		display: flex;
		flex: 1;
		justify-content: space-around;
		margin: 0;
	}
	.counts div {
		display: flex;
		flex-direction: column-reverse;
		align-items: center;
	}
	.counts dd {
		margin: 0;
		font-size: 1.3rem;
		font-weight: 800;
	}
	.counts dt {
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.names {
		margin: 0;
	}
	.buttons {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s-2);
	}
	.section-h {
		margin-bottom: 0;
	}
	.section-of {
		margin-bottom: var(--s-4);
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
</style>
