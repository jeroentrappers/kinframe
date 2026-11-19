<script lang="ts">
	import type { ReactionSummary, TimelinePost } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { authorCredit } from '$lib/sender';
	import { personName, posterName, postPlace } from '$lib/social';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';
	import PostMedia from './PostMedia.svelte';
	import ReactionRow from './ReactionRow.svelte';
	import RichText from './RichText.svelte';
	import ShareInChatDialog from './ShareInChatDialog.svelte';

	let { post, onreactions, showPrompt = true }: { post: TimelinePost; onreactions?: (s: ReactionSummary) => void; showPrompt?: boolean } = $props();

	const place = $derived(postPlace(post));
	const poster = $derived(posterName(post, t('social.formerMember'), (name) => t('leaving.formerMember', { name })));
	const caption = $derived(post.items.find((i) => i.caption)?.caption ?? null);
	const thread = $derived(post.thread);
	const own = $derived(post.poster.user_id === auth.me?.id);
	const unread = $derived((thread.unread_count ?? 0) > 0);
	let shareOpen = $state(false);
</script>

<article class="card post" in:softFly aria-labelledby="post-{post.id}-who">
	<header class="head">
		<a href={post.poster.former_member ? undefined : `/people/${post.poster.user_id}`} class="avatar-link" tabindex="-1" aria-hidden="true">
			<Avatar name={poster} url={post.poster.former_member ? null : post.poster.avatar_url} size={40} />
		</a>
		<div class="who">
			<span id="post-{post.id}-who" class="name">
				{authorCredit(poster, post.via)}{#if own}<span class="faint you"> ({t('people.you')})</span>{/if}
			</span>
			<span class="meta muted small">
				{#if place.kind === 'frame'}
					<Icon name="frame" size={14} />{t('social.toFrame', { frame: place.name })}
				{:else if place.kind === 'circle'}
					<a href="/feeds/{post.feed_id}">{place.name}</a><span aria-hidden="true">·</span><Icon name="lock" size={13} /><span>{place.circle}</span>
				{:else}
					<a href="/feeds/{post.feed_id}">{place.name}</a>
				{/if}
				<span aria-hidden="true">·</span>
				<a class="time" href="/posts/{post.id}"><time datetime={post.created_at} title={formatDateTime(post.created_at, i18n.tag)}>{relativeTime(post.created_at, i18n.tag)}</time></a>
			</span>
		</div>
	</header>

	{#if post.items.length > 0}
		<PostMedia items={post.items} postId={post.id} alt={t('social.photosBy', { name: poster })} />
	{/if}

	<div class="body">
		{#if showPrompt && post.prompt}
			<p class="prompt-chip"><span aria-hidden="true">💡</span>{t('engagement.answers', { title: post.prompt.title })}</p>
		{/if}
		{#if caption}<p class="caption">{caption}</p>{/if}

		<div class="actions">
			<ReactionRow summary={thread.reactions} kind="post" targetId={post.id} onchange={onreactions} />
			<span class="spacer"></span>
			<a class="icon-btn" href="/posts/{post.id}#comment" aria-label={thread.can_comment === false ? t('social.viewComments') : t('social.comment')}>
				<Icon name="message" size={19} />
				{#if thread.comment_count > 0}<span class="num">{thread.comment_count}</span>{/if}
				{#if unread}<span class="unread-dot" aria-hidden="true"></span>{/if}
			</a>
			{#if discovery.chats}
				<button class="icon-btn" onclick={() => (shareOpen = true)} aria-label={t('social.shareInChat')}><Icon name="send" size={18} /></button>
			{/if}
		</div>

		{#if post.seen && post.seen.frames > 0}
			<p class="seen muted small"><Icon name="eye" size={14} />{tn('social.seenOn', post.seen.frames, { seen: post.seen.seen })}</p>
		{/if}

		{#if thread.latest_comments.length > 0}
			<ul class="latest" role="list">
				{#each thread.latest_comments as c (c.id)}
					<li>
						<strong>{personName(c.author, t('social.formerMember'))}</strong>
						<RichText body={c.body} mentions={c.mentions} />
					</li>
				{/each}
			</ul>
		{/if}
		{#if thread.comment_count > thread.latest_comments.length}
			<a class="all" href="/posts/{post.id}">
				{tn('social.viewAll', thread.comment_count)}{#if unread}<span class="new-badge">{tn('social.newComments', thread.unread_count ?? 0)}</span>{/if}
			</a>
		{:else if unread}
			<a class="all" href="/posts/{post.id}"><span class="new-badge">{tn('social.newComments', thread.unread_count ?? 0)}</span></a>
		{/if}
		{#if thread.state === 'closed'}
			<p class="closed faint small"><Icon name="lock" size={13} />{t('social.commentsOff')}</p>
		{/if}
	</div>
</article>

{#if shareOpen}
	<ShareInChatDialog bind:open={shareOpen} postId={post.id} />
{/if}

<style>
	.post {
		overflow: hidden;
		padding: 0;
	}
	.head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
	}
	.avatar-link {
		border-radius: 50%;
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.name {
		font-weight: 700;
		overflow-wrap: anywhere;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px;
	}
	.meta a {
		color: inherit;
		text-decoration: none;
	}
	.meta a:hover {
		text-decoration: underline;
	}
	.body {
		padding: var(--s-3) var(--s-4) var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.caption {
		margin: 0;
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
	.actions {
		display: flex;
		align-items: center;
		gap: var(--s-1);
		min-width: 0;
	}
	.icon-btn {
		position: relative;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		min-width: 40px;
		height: 40px;
		padding: 0 8px;
		justify-content: center;
		border: 0;
		border-radius: var(--r-full);
		background: transparent;
		color: var(--text-muted);
		text-decoration: none;
		font: inherit;
		font-weight: 600;
		font-size: 0.9rem;
		cursor: pointer;
	}
	.icon-btn:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.unread-dot {
		position: absolute;
		top: 8px;
		inset-inline-end: 6px;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--primary);
	}
	.seen {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.latest {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: 0.93rem;
	}
	.latest li {
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.latest strong {
		margin-inline-end: 6px;
	}
	.all {
		color: var(--text-muted);
		font-size: 0.9rem;
		text-decoration: none;
		display: inline-flex;
		gap: var(--s-2);
		align-items: center;
	}
	.all:hover {
		color: var(--text);
		text-decoration: underline;
	}
	.new-badge {
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		font-weight: 700;
		font-size: 0.78rem;
	}
	.closed {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
</style>
