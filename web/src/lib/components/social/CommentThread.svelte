<script lang="ts">
	import { tick } from 'svelte';
	import { api, call, type Comment, type Mention, type ThreadSummary } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { codePoints, lastPersonCommentId, nestComments, personName, removeComment, trimWithMentions, upsertComment } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import CommentItem from './CommentItem.svelte';
	import MentionInput, { type MentionCandidate } from './MentionInput.svelte';
	import ReportDialog from './ReportDialog.svelte';

	let {
		postId,
		thread = $bindable(),
		people,
		focus = null
	}: { postId: string; thread: ThreadSummary; people: MentionCandidate[]; focus?: string | null } = $props();

	let comments = $state<Comment[]>([]);
	let hasMore = $state(false);
	let loading = $state(true);
	let error = $state<unknown>(null);
	/** Comments being deleted (undo window). */
	let hidden = $state<Set<string>>(new Set());

	const nodes = $derived(nestComments(comments.filter((c) => !hidden.has(c.id))));
	const canComment = $derived(thread.can_comment !== false && thread.state !== 'closed');
	const max = $derived(discovery.limits.max_comment_chars);

	async function load(after?: string) {
		if (!after) loading = true;
		error = null;
		try {
			const page = await call(api.GET('/v1/posts/{postId}/comments', { params: { path: { postId }, query: { limit: 100, ...(after ? { after } : {}) } } }));
			comments = after ? [...comments, ...page.comments.filter((c) => !comments.some((x) => x.id === c.id))] : page.comments;
			hasMore = page.has_more;
			void markRead();
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	/** The thread is on screen: mark it read up to its newest person comment (never moves back on the server). */
	async function markRead() {
		const last = lastPersonCommentId(comments);
		if (!last || document.visibilityState !== 'visible') return;
		if (thread.read_up_to && thread.read_up_to >= last && !thread.unread_count) return;
		thread = { ...thread, read_up_to: last, unread_count: 0, unread_mentions: 0 };
		await call(api.PUT('/v1/posts/{postId}/read', { params: { path: { postId } }, body: { up_to: last } })).catch(() => {});
	}

	$effect(() => {
		const id = postId;
		void load();
		const off = live.on((e) => {
			if ((e.type === 'comment.created' || e.type === 'comment.updated') && e.post_id === id) {
				comments = upsertComment(comments, e.comment);
				if (e.type === 'comment.created') void markRead();
			} else if (e.type === 'comment.deleted' && e.post_id === id) comments = removeComment(comments, e.comment_id);
			else if (e.type === 'reaction.updated' && e.target_kind === 'comment') {
				const c = comments.find((x) => x.id === e.target_id);
				if (c) comments = upsertComment(comments, { ...c, reactions: e.summary });
			}
		});
		const onVis = () => void markRead();
		document.addEventListener('visibilitychange', onVis);
		return () => {
			off();
			document.removeEventListener('visibilitychange', onVis);
		};
	});

	// A link to one comment (push, mention): scroll to it once it's there.
	let focused = false;
	$effect(() => {
		if (!focus || loading || focused) return;
		if (!comments.some((c) => c.id === focus)) return;
		focused = true;
		void tick().then(() => document.getElementById(`c-${focus}`)?.scrollIntoView({ block: 'center' }));
	});

	// ---- composing
	let body = $state('');
	let mentions = $state<Mention[]>([]);
	let replyTo = $state<{ parent: Comment; quoted: Comment['author'] | null; label: string } | null>(null);
	let sending = $state(false);
	let input: HTMLTextAreaElement | undefined = $state();
	const length = $derived(codePoints(body.trim()));

	function startReply(c: Comment) {
		const parent = c.parent_id ? comments.find((x) => x.id === c.parent_id) : c;
		if (!parent) return;
		// One level of replies: answering a reply hangs under the same top comment and quotes the person.
		replyTo = { parent, quoted: c.parent_id ? c.author : null, label: personName(c.author, t('social.formerMember')) };
		input?.focus();
	}

	async function send() {
		const text = trimWithMentions(body, mentions);
		if (!text.body || codePoints(text.body) > max || sending) return;
		sending = true;
		try {
			const c = await call(
				api.POST('/v1/posts/{postId}/comments', {
					params: { path: { postId }, header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: {
						body: text.body,
						...(text.mentions.length ? { mentions: text.mentions } : {}),
						...(replyTo ? { parent_id: replyTo.parent.id } : {}),
						...(replyTo?.quoted ? { quoted_user_id: replyTo.quoted.user_id } : {})
					}
				})
			);
			comments = upsertComment(comments, c);
			thread = { ...thread, comment_count: thread.comment_count + 1 };
			body = '';
			mentions = [];
			replyTo = null;
			void markRead();
		} catch (e) {
			if (isCode(e, 'comments_closed')) thread = { ...thread, state: 'closed', can_comment: false };
			toasts.error(problemMessage(e));
		} finally {
			sending = false;
		}
	}

	async function save(c: Comment, text: string, ms: Mention[]): Promise<boolean> {
		const tr = trimWithMentions(text, ms);
		try {
			const next = await call(
				api.PATCH('/v1/posts/{postId}/comments/{commentId}', { params: { path: { postId, commentId: c.id } }, body: { body: tr.body, mentions: tr.mentions } })
			);
			comments = upsertComment(comments, next);
			return true;
		} catch (e) {
			toasts.error(problemMessage(e));
			return false;
		}
	}

	function remove(c: Comment, asModerator: boolean) {
		const ids = new Set([c.id, ...(asModerator ? comments.filter((x) => x.parent_id === c.id).map((x) => x.id) : [])]);
		toasts.deferred({
			message: asModerator ? t('social.commentRemoved') : t('social.commentDeletedToast'),
			undoLabel: t('common.undo'),
			apply: () => (hidden = new Set([...hidden, ...ids])),
			rollback: () => (hidden = new Set([...hidden].filter((x) => !ids.has(x)))),
			commit: async () => {
				await call(api.DELETE('/v1/posts/{postId}/comments/{commentId}', { params: { path: { postId, commentId: c.id } } }));
				// The author's delete of a top comment with replies leaves a tombstone; the event brings it.
				const keep = !asModerator && comments.some((x) => x.parent_id === c.id);
				comments = keep ? comments.map((x) => (x.id === c.id ? { ...x, deleted: true, body: null } : x)) : removeComment(comments, c.id);
				hidden = new Set([...hidden].filter((x) => !ids.has(x)));
				thread = { ...thread, comment_count: Math.max(0, thread.comment_count - (asModerator ? ids.size : 1)) };
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	let reportOpen = $state(false);
	let reportId = $state('');
</script>

<section class="thread" aria-labelledby="thread-title">
	<h2 id="thread-title" class="visually-hidden">{tn('social.comments', thread.comment_count)}</h2>
	{#if loading}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:52px"></div>
			<div class="skeleton" style="height:52px;width:80%"></div>
		</div>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div>
		</div>
	{:else}
		{#if nodes.length === 0}
			<p class="muted empty">{canComment ? t('social.noCommentsYet') : t('social.noComments')}</p>
		{/if}
		<ul class="list" role="list" aria-live="polite">
			{#each nodes as n (n.comment.id)}
				<li>
					<CommentItem
						comment={n.comment}
						{people}
						{canComment}
						canModerate={thread.can_moderate === true}
						highlight={focus === n.comment.id}
						onreply={startReply}
						onsave={save}
						ondelete={remove}
						onreport={(c) => ((reportId = c.id), (reportOpen = true))}
						onreactions={(c, s) => (comments = upsertComment(comments, { ...c, reactions: s }))}
					/>
					{#if n.replies.length}
						<ul class="replies" role="list">
							{#each n.replies as r (r.id)}
								<li>
									<CommentItem
										comment={r}
										reply
										{people}
										{canComment}
										canModerate={thread.can_moderate === true}
										highlight={focus === r.id}
										onreply={startReply}
										onsave={save}
										ondelete={remove}
										onreport={(c) => ((reportId = c.id), (reportOpen = true))}
										onreactions={(c, s) => (comments = upsertComment(comments, { ...c, reactions: s }))}
									/>
								</li>
							{/each}
						</ul>
					{/if}
				</li>
			{/each}
		</ul>
		{#if hasMore}
			<button class="btn btn-sm btn-ghost" onclick={() => load(comments[comments.length - 1]?.id)}>{t('social.moreComments')}</button>
		{/if}
	{/if}

	{#if canComment}
		<form
			id="comment"
			class="composer"
			onsubmit={(e) => {
				e.preventDefault();
				void send();
			}}
		>
			{#if replyTo}
				<p class="replying small">
					<Icon name="reply" size={14} />{t('social.replyingTo', { name: replyTo.label })}
					<button type="button" class="btn btn-ghost btn-icon btn-sm" aria-label={t('social.cancelReply')} onclick={() => (replyTo = null)}><Icon name="x" size={14} /></button>
				</p>
			{/if}
			<div class="row-input">
				<MentionInput bind:value={body} bind:mentions bind:textarea={input} {people} label={replyTo ? t('social.writeReply') : t('social.writeComment')} placeholder={replyTo ? t('social.writeReply') : t('social.writeComment')} maxMentions={discovery.limits.max_mentions} onsubmit={send} />
				<button class="btn btn-primary btn-icon send" type="submit" disabled={sending || !body.trim() || length > max} aria-label={t('social.send')}>
					{#if sending}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="send" size={18} />{/if}
				</button>
			</div>
			{#if length > max - 200}
				<p class="small counter num" class:over={length > max}>{length}/{max}</p>
			{/if}
		</form>
	{:else}
		<p class="closed muted small"><Icon name="lock" size={14} />{thread.state === 'closed' ? t('social.commentsOff') : t('social.cannotComment')}</p>
	{/if}
</section>

<ReportDialog bind:open={reportOpen} kind="comment" targetId={reportId} />

<style>
	.thread {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.list,
	.replies {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.replies {
		margin-top: var(--s-2);
		gap: var(--s-2);
	}
	.empty {
		margin: 0;
	}
	.composer {
		position: sticky;
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 8px);
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: var(--s-2);
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow);
		scroll-margin-bottom: 120px;
	}
	@media (min-width: 860px) {
		.composer {
			bottom: 12px;
		}
	}
	.row-input {
		display: flex;
		gap: var(--s-2);
		align-items: flex-end;
	}
	.send {
		flex: none;
		width: 44px;
		height: 44px;
	}
	.replying {
		margin: 0;
		display: flex;
		align-items: center;
		gap: 6px;
		color: var(--text-muted);
	}
	.counter {
		margin: 0;
		text-align: end;
		color: var(--text-muted);
	}
	.counter.over {
		color: var(--danger);
	}
	.closed {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin: 0;
	}
</style>
