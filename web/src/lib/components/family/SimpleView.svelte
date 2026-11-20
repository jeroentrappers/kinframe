<script lang="ts">
	import { api, call, type TimelinePost } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { dedupePosts, view } from '$lib/family.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { appendPosts, applyPostReactions, posterName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import PostMedia from '../social/PostMedia.svelte';

	/**
	 * Simple view (simple-app.md §6): one post at a time, large; a big "Send a heart"; next and previous by button or
	 * swipe. No tab bar and no comments. Hearts are the ordinary `love` reaction. All the person's families together, with
	 * the family's name under the poster.
	 */
	const several = $derived(feeds.list.length > 1);

	let posts = $state<TimelinePost[]>([]);
	let next = $state<string | null>(null);
	let index = $state(0);
	let loading = $state(true);
	let error = $state<unknown>(null);
	let menuOpen = $state(false);
	let hearting = $state(false);

	async function load(more = false) {
		try {
			const res = await call(api.GET('/v1/timeline', { params: { query: { limit: 20, ...(more && next ? { before: next } : {}) } } }));
			posts = dedupePosts((more ? appendPosts(posts, res.posts) : res.posts).filter((p) => p.items.length > 0));
			next = res.next_before;
			error = null;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		index = 0;
		loading = true;
		void load();
		return live.on((e) => {
			if (e.type === 'post.removed') posts = posts.filter((p) => p.id !== e.post_id);
			else if (e.type === 'reaction.updated' && e.target_kind === 'post') applyPostReactions(posts, e.target_id, e.summary);
			else if (e.type === 'feed_post.updated' && !posts.some((p) => p.id === e.post_id)) void load();
		});
	});

	const post = $derived(posts[index] ?? null);
	const loved = $derived(post?.thread.reactions?.mine === 'love');

	function go(delta: number) {
		const to = index + delta;
		if (to < 0) return;
		if (to >= posts.length) {
			if (next) void load(true).then(() => to < posts.length && (index = to));
			return;
		}
		index = to;
	}

	async function heart() {
		if (!post || hearting) return;
		hearting = true;
		const p = post;
		try {
			const s = await call(api.PUT('/v1/reactions/{targetKind}/{targetId}', { params: { path: { targetKind: 'post', targetId: p.id } }, body: { emoji: loved ? null : 'love' } }));
			p.thread = { ...p.thread, reactions: s };
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			hearting = false;
		}
	}

	// Swipe up for the next post, down for the previous one; the wheel and arrow keys do the same.
	let startY: number | null = null;
	function onTouchStart(e: TouchEvent) {
		startY = e.touches[0]?.clientY ?? null;
	}
	function onTouchEnd(e: TouchEvent) {
		if (startY === null) return;
		const dy = (e.changedTouches[0]?.clientY ?? startY) - startY;
		startY = null;
		if (Math.abs(dy) > 60) go(dy < 0 ? 1 : -1);
	}
	let wheelAt = 0;
	function onWheel(e: WheelEvent) {
		if (Math.abs(e.deltaY) < 30 || Date.now() - wheelAt < 600) return;
		wheelAt = Date.now();
		go(e.deltaY > 0 ? 1 : -1);
	}
	function onKey(e: KeyboardEvent) {
		if (menuOpen || (e.target as HTMLElement | null)?.closest('input, textarea')) return;
		if (e.key === 'ArrowDown' || e.key === 'PageDown') (e.preventDefault(), go(1));
		else if (e.key === 'ArrowUp' || e.key === 'PageUp') (e.preventDefault(), go(-1));
	}
</script>

<svelte:window onkeydown={onKey} />
<svelte:head><title>{t('social.homeTitle')} · kinframe</title></svelte:head>

<div class="simple" ontouchstart={onTouchStart} ontouchend={onTouchEnd} onwheel={onWheel} role="region" aria-label={t('simple.simpleView')}>
	<header class="top">
		<span class="family">{t('social.homeTitle')}</span>
		<button class="settings" onclick={() => (menuOpen = true)}>{t('nav.settings')}</button>
	</header>

	{#if loading}
		<div class="skeleton big" aria-busy="true" aria-label={t('common.loading')}></div>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-lg" onclick={() => load()}>{t('common.tryAgain')}</button></div>
		</div>
	{:else if !post}
		<p class="empty">{t('simple.empty')}</p>
	{:else}
		{@const name = posterName(post, t('social.formerMember'), (n) => t('leaving.formerMember', { name: n }))}
		{@const caption = post.items.find((i) => i.caption)?.caption ?? null}
		<article class="post" aria-labelledby="sv-who">
			<div class="who">
				<Avatar name={name} url={post.poster.former_member ? null : post.poster.avatar_url} size={56} />
				<div>
					<p id="sv-who" class="name">{name}</p>
					{#if several && post.feed_name}<p class="fam">{post.feed_name}</p>{/if}
					<p class="time"><time datetime={post.created_at} title={formatDateTime(post.created_at, i18n.tag)}>{relativeTime(post.created_at, i18n.tag)}</time></p>
				</div>
			</div>
			{#key post.id}
				<PostMedia items={post.items} postId={post.id} alt={t('social.photosBy', { name })} aspect="1 / 1" />
			{/key}
			{#if caption}<p class="caption">{caption}</p>{/if}
		</article>
		<div class="buttons">
			<button class="btn heart" class:loved onclick={heart} disabled={hearting} aria-pressed={loved}>
				<Icon name="heart" size={30} />{loved ? t('simple.hearted') : t('simple.heart')}
			</button>
			<div class="nav">
				<button class="btn big-btn" onclick={() => go(-1)} disabled={index === 0}><Icon name="chevronLeft" size={26} />{t('simple.previous')}</button>
				<button class="btn big-btn" onclick={() => go(1)} disabled={index >= posts.length - 1 && !next}>{t('simple.next')}<Icon name="chevronRight" size={26} /></button>
			</div>
		</div>
	{/if}
</div>

<Dialog bind:open={menuOpen} title={t('nav.settings')}>
	<div class="stack menu">
		<button class="btn btn-primary big-btn" onclick={() => ((menuOpen = false), view.set(false))}>{t('simple.normalView')}</button>
		<div class="account">
			<Avatar name={auth.me?.display_name ?? ''} url={auth.me?.avatar_url} size={48} />
			<span>{t('settings.signedInAs', { name: auth.me?.display_name ?? '' })}</span>
		</div>
		<button class="btn big-btn" onclick={() => auth.signOut()}><Icon name="logout" size={22} />{t('settings.signOut')}</button>
	</div>
</Dialog>

<style>
	/* Type of at least 20px and buttons of at least 64px (§6). */
	.simple {
		max-width: 720px;
		margin-inline: auto;
		padding: var(--s-3) var(--s-4) var(--s-5);
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		gap: var(--s-4);
		font-size: 1.25rem;
		touch-action: pan-x;
	}
	.top {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
	}
	.family {
		font-family: var(--font-display);
		font-size: 1.5rem;
		font-weight: 650;
	}
	.settings {
		border: 0;
		background: transparent;
		color: var(--text-muted);
		font: inherit;
		font-size: 1.25rem;
		text-decoration: underline;
		padding: 8px;
		cursor: pointer;
		min-height: 48px;
	}
	.big {
		height: 60vh;
	}
	.empty {
		text-align: center;
		margin-top: 20vh;
		font-size: 1.5rem;
	}
	.post {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.post :global(.media),
	.post :global(.track) {
		border-radius: var(--r-lg);
	}
	.who {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.name {
		margin: 0;
		font-size: 1.6rem;
		font-weight: 700;
	}
	.fam {
		margin: 0;
		font-size: 1.25rem;
		font-weight: 650;
		color: var(--primary-soft-text);
	}
	.time {
		margin: 0;
		font-size: 1.25rem;
		color: var(--text-muted);
	}
	.caption {
		margin: 0;
		font-size: 1.4rem;
	}
	.buttons {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.heart,
	.big-btn {
		min-height: 64px;
		font-size: 1.3rem;
		gap: 10px;
		justify-content: center;
	}
	.heart {
		width: 100%;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		font-weight: 800;
	}
	.heart.loved {
		background: var(--primary);
		color: var(--on-primary);
	}
	.nav {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s-3);
	}
	.menu {
		font-size: 1.25rem;
	}
	.account {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
</style>
