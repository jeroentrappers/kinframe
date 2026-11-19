<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type FeedPrompt, type TimelinePost } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { authorCredit } from '$lib/sender';
	import { personName, postPlace } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Avatar from '$components/Avatar.svelte';
	import Icon from '$components/Icon.svelte';
	import CommentThread from '$components/social/CommentThread.svelte';
	import type { MentionCandidate } from '$components/social/MentionInput.svelte';
	import MoreMenu, { type MenuItem } from '$components/social/MoreMenu.svelte';
	import PostMedia from '$components/social/PostMedia.svelte';
	import ReactionRow from '$components/social/ReactionRow.svelte';
	import ReportDialog from '$components/social/ReportDialog.svelte';
	import ShareInChatDialog from '$components/social/ShareInChatDialog.svelte';

	const id = $derived(page.params.id!);
	let post = $state<TimelinePost | null>(null);
	let error = $state<unknown>(null);
	let people = $state<MentionCandidate[]>([]);

	async function load(postId: string) {
		try {
			post = await call(api.GET('/v1/posts/{postId}', { params: { path: { postId } } }));
			error = null;
			void loadPeople(post);
			void loadPrompts(post);
		} catch (e) {
			error = e;
		}
	}

	/** Who can be mentioned: the people who may see the post (README §5.18). */
	async function loadPeople(p: TimelinePost) {
		try {
			let list: MentionCandidate[] = [];
			if (p.kind === 'feed' && p.circle_id && p.feed_id) {
				const c = await call(api.GET('/v1/feeds/{feedId}/circles/{circleId}', { params: { path: { feedId: p.feed_id, circleId: p.circle_id } } }));
				list = c.members;
			} else if (p.kind === 'feed' && p.feed_id) {
				list = await call(api.GET('/v1/feeds/{feedId}/members', { params: { path: { feedId: p.feed_id } } }));
			} else if (p.frame_id) {
				const members = await call(api.GET('/v1/frames/{frameId}/members', { params: { path: { frameId: p.frame_id } } }));
				list = members.filter((m) => m.role === 'owner' || m.role === 'admin' || m.permissions?.see_library);
			}
			people = list.filter((m) => m.user_id !== auth.me?.id);
		} catch {
			people = [];
		}
	}

	$effect(() => {
		const postId = id;
		post = null;
		error = null;
		void load(postId);
		return live.on((e) => {
			if (!post) return;
			if (e.type === 'post.updated' && e.post_id === postId) post.thread = { ...e.thread };
			else if (e.type === 'post.removed' && e.post_id === postId) {
				toasts.show(t('social.postGone'));
				void goto('/home');
			} else if (e.type === 'reaction.updated' && e.target_kind === 'post' && e.target_id === postId) post.thread = { ...post.thread, reactions: e.summary };
		});
	});

	const own = $derived(!!post && post.poster.user_id === auth.me?.id);
	const place = $derived(post ? postPlace(post) : null);
	const poster = $derived(post ? personName(post.poster, t('social.formerMember')) : '');
	const caption = $derived(post?.items.find((i) => i.caption)?.caption ?? null);

	// ---- prompts the poster can tag the post with (this and last week's)
	let prompts = $state<FeedPrompt[]>([]);
	async function loadPrompts(p: TimelinePost) {
		if (!discovery.engagement || p.kind !== 'feed' || !p.feed_id || p.poster.user_id !== auth.me?.id) return;
		try {
			const list = await call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: p.feed_id } } }));
			prompts = list.filter((x) => (x.status === 'current' || x.status === 'past') && x.title.trim()).slice(0, 2);
		} catch {
			prompts = [];
		}
	}
	async function tagPrompt(promptId: string | null) {
		if (!post) return;
		try {
			post = await call(api.PUT('/v1/posts/{postId}/prompt', { params: { path: { postId: post.id } }, body: { prompt_id: promptId } }));
			toasts.success(promptId ? t('engagement.tagged') : t('engagement.untagged'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- thread and post actions
	async function setMute(muted: boolean) {
		if (!post) return;
		try {
			await call(api.PUT('/v1/posts/{postId}/mute', { params: { path: { postId: post.id } }, body: { muted } }));
			post.thread = { ...post.thread, muted };
			toasts.success(muted ? t('social.threadMuted') : t('social.threadUnmuted'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function setState(state: 'open' | 'closed') {
		if (!post) return;
		try {
			post.thread = await call(api.PUT('/v1/posts/{postId}/thread', { params: { path: { postId: post.id } }, body: { state } }));
			toasts.success(state === 'closed' ? t('social.closedToast') : t('social.openedToast'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function removePost() {
		if (!post) return;
		const p = post;
		toasts.deferred({
			message: own ? t('feedPosts.unsent') : t('feedPosts.removed'),
			undoLabel: t('common.undo'),
			apply: () => void goto('/home'),
			rollback: () => void goto(`/posts/${p.id}`),
			commit: async () => {
				if (p.kind === 'feed' && p.feed_id) await call(api.DELETE('/v1/feeds/{feedId}/posts/{postId}', { params: { path: { feedId: p.feed_id, postId: p.id } } }));
				else if (p.send_id) await call(api.DELETE('/v1/sends/{sendId}', { params: { path: { sendId: p.send_id } } }));
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	let reportOpen = $state(false);
	let shareOpen = $state(false);

	const menu = $derived.by((): MenuItem[] => {
		if (!post) return [];
		const items: MenuItem[] = [];
		const th = post.thread;
		items.push(th.muted ? { label: t('social.unmuteThread'), icon: 'bell', run: () => setMute(false) } : { label: t('social.muteThread'), icon: 'bellOff', run: () => setMute(true) });
		if (th.can_moderate || own) {
			items.push(th.state === 'closed' ? { label: t('social.openComments'), icon: 'message', run: () => setState('open') } : { label: t('social.closeComments'), icon: 'lock', run: () => setState('closed') });
		}
		for (const pr of prompts) {
			if (post.prompt?.id === pr.id) items.push({ label: t('engagement.untag'), icon: 'x', run: () => tagPrompt(null) });
			else items.push({ label: t('engagement.tagAs', { title: pr.title }), icon: 'sparkle', run: () => tagPrompt(pr.id) });
		}
		if (discovery.chats) items.push({ label: t('social.shareInChat'), icon: 'send', run: () => (shareOpen = true) });
		if (!own && discovery.chats) items.push({ label: t('chat.report'), icon: 'flag', run: () => (reportOpen = true) });
		if (post.can_remove) items.push({ label: own ? t('social.unsendPost') : t('social.removePost'), icon: 'trash', danger: true, run: removePost });
		return items;
	});
</script>

<svelte:head><title>{post ? t('social.postBy', { name: poster }) : t('social.post')} · kinframe</title></svelte:head>

<div class="container page narrow-post">
	<a href="/home" class="back"><Icon name="chevronLeft" size={18} />{t('social.homeTitle')}</a>

	{#if !discovery.social}
		<p class="callout"><Icon name="info" size={18} />{t('social.offBody')}</p>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<span>{isCode(error, 'not_found') ? t('social.postNotFound') : problemMessage(error)}</span>
		</div>
	{:else if !post}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:56px;width:60%"></div>
			<div class="skeleton" style="height:360px"></div>
		</div>
	{:else}
		<article class="card post" aria-labelledby="post-who">
			<header class="head">
				<Avatar name={poster} url={post.poster.former_member ? null : post.poster.avatar_url} size={44} />
				<div class="who">
					<h1 id="post-who" class="name">
						{#if !post.poster.former_member}<a href="/people/{post.poster.user_id}">{authorCredit(poster, post.via)}</a>{:else}{poster}{/if}
					</h1>
					<span class="meta muted small">
						{#if place?.kind === 'frame'}
							<Icon name="frame" size={14} />{t('social.toFrame', { frame: place.name })}
						{:else if place?.kind === 'circle'}
							<a href="/feeds/{post.feed_id}">{place.name}</a> · <Icon name="lock" size={13} />{place.circle}
						{:else if place}
							<a href="/feeds/{post.feed_id}">{place.name}</a>
						{/if}
						· <time datetime={post.created_at} title={formatDateTime(post.created_at, i18n.tag)}>{relativeTime(post.created_at, i18n.tag)}</time>
					</span>
				</div>
				<span class="spacer"></span>
				<MoreMenu label={t('social.postActions')} items={menu} size="md" />
			</header>
			{#if post.items.length}
				<PostMedia items={post.items} postId={post.id} alt={t('social.photosBy', { name: poster })} itemReactions aspect="1 / 1" />
			{/if}
			<div class="body">
				{#if post.prompt}<p class="prompt-chip"><span aria-hidden="true">💡</span>{t('engagement.answers', { title: post.prompt.title })}</p>{/if}
				{#if caption}<p class="caption">{caption}</p>{/if}
				<ReactionRow summary={post.thread.reactions} kind="post" targetId={post.id} onchange={(s) => post && (post.thread = { ...post.thread, reactions: s })} />
				{#if post.seen && post.seen.frames > 0}
					<p class="seen muted small"><Icon name="eye" size={14} />{tn('social.seenOn', post.seen.frames, { seen: post.seen.seen })}</p>
				{/if}
				{#if post.thread.muted}<p class="faint small muted-note"><Icon name="bellOff" size={14} />{t('social.threadMutedNote')}</p>{/if}
			</div>
		</article>

		<div class="card card-pad thread-card">
			<CommentThread postId={post.id} bind:thread={post.thread} {people} focus={page.url.searchParams.get('comment')} />
		</div>
	{/if}
</div>

{#if post}
	<ReportDialog bind:open={reportOpen} kind="post" targetId={post.id} />
	{#if shareOpen}<ShareInChatDialog bind:open={shareOpen} postId={post.id} preferFeedId={post.feed_id ?? undefined} />{/if}
{/if}

<style>
	.narrow-post {
		max-width: 680px;
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.post {
		overflow: visible;
		padding: 0;
	}
	.post :global(.media) {
		border-radius: 0;
	}
	.head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
	}
	.who {
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.name {
		font-size: 1.05rem;
		font-family: var(--font-body);
		overflow-wrap: anywhere;
	}
	.name a,
	.meta a {
		color: inherit;
		text-decoration: none;
	}
	.name a:hover,
	.meta a:hover {
		text-decoration: underline;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px;
	}
	.body {
		padding: var(--s-3) var(--s-4) var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.caption {
		margin: 0;
		font-size: 1.02rem;
		overflow-wrap: anywhere;
	}
	.prompt-chip {
		margin: 0;
		align-self: flex-start;
		display: inline-flex;
		gap: 6px;
		padding: 3px 10px;
		border-radius: var(--r-full);
		background: var(--warn-soft);
		font-size: 0.82rem;
		font-weight: 600;
	}
	.seen,
	.muted-note {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.thread-card {
		margin-top: var(--s-4);
	}
</style>
