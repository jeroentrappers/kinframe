<script lang="ts">
	import { tick, untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Conversation, type Mention, type Message, type ReactionSummary } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { activeTypists, applyRead, canEditMessage, chatRows, isLocal, lastSeenOwn, localId, mergeMessages, seenBy, upsertMessage, type Typist } from '$lib/chat';
	import { discovery } from '$lib/discovery.svelte';
	import { canInviteToFeed, isFeedAdmin } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { live } from '$lib/live.svelte';
	import { EMPTY_REACTIONS, personName, trimWithMentions } from '$lib/social';
	import { chats } from '$lib/social.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';
	import ChatComposer from '$components/chat/ChatComposer.svelte';
	import ChatSettingsDialog from '$components/chat/ChatSettingsDialog.svelte';
	import ConversationAvatar from '$components/chat/ConversationAvatar.svelte';
	import MessageBubble from '$components/chat/MessageBubble.svelte';
	import OperatorNote from '$components/chat/OperatorNote.svelte';
	import type { MenuItem } from '$components/social/MoreMenu.svelte';
	import MoreMenu from '$components/social/MoreMenu.svelte';
	import ReportDialog from '$components/social/ReportDialog.svelte';
	import TargetDialog from '$components/social/TargetDialog.svelte';
	import { readOnlyName } from '$lib/leaving';

	const id = $derived(page.params.id!);
	let conv = $state<Conversation | null>(null);
	let messages = $state<Message[]>([]);
	let hasMoreBefore = $state(false);
	let loading = $state(true);
	let loadingOlder = $state(false);
	let error = $state<unknown>(null);
	/** Optimistic bubbles whose send failed (by client_id), with what to resend. */
	let failed = $state<Record<string, Parameters<typeof sendNow>[0]>>({});
	let typists = $state<Map<string, Typist>>(new Map());
	let now = $state(Date.now());
	let scroller: HTMLDivElement | undefined = $state();
	const meId = $derived(auth.me?.id);

	async function load(conversationId: string) {
		loading = true;
		error = null;
		try {
			const [c, p] = await Promise.all([
				call(api.GET('/v1/conversations/{conversationId}', { params: { path: { conversationId } } })),
				call(api.GET('/v1/conversations/{conversationId}/messages', { params: { path: { conversationId }, query: { limit: 50 } } }))
			]);
			conv = c;
			messages = p.messages;
			hasMoreBefore = p.has_more_before;
			loading = false;
			await tick();
			scrollToBottom(false);
			void markRead();
		} catch (e) {
			error = e;
			loading = false;
		}
	}

	async function loadOlder() {
		if (!hasMoreBefore || loadingOlder || !messages.length) return;
		loadingOlder = true;
		const first = messages.find((m) => !isLocal(m));
		const height = scroller?.scrollHeight ?? 0;
		try {
			const p = await call(api.GET('/v1/conversations/{conversationId}/messages', { params: { path: { conversationId: id }, query: { before: first?.id, limit: 50 } } }));
			messages = mergeMessages(messages, p.messages);
			hasMoreBefore = p.has_more_before;
			await tick();
			if (scroller) scroller.scrollTop += scroller.scrollHeight - height;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			loadingOlder = false;
		}
	}

	/** After a reconnect: everything after the newest message we have (README §5.19). */
	async function catchUp() {
		const last = [...messages].reverse().find((m) => !isLocal(m));
		if (!last) return;
		try {
			let after = last.id;
			for (let i = 0; i < 5; i++) {
				const p = await call(api.GET('/v1/conversations/{conversationId}/messages', { params: { path: { conversationId: id }, query: { after, limit: 200 } } }));
				messages = mergeMessages(messages, p.messages);
				if (!p.has_more_after || !p.messages.length) break;
				after = p.messages[p.messages.length - 1]!.id;
			}
			void markRead();
		} catch {
			/* next reconnect */
		}
	}

	function nearBottom() {
		if (!scroller) return true;
		return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 160;
	}
	function scrollToBottom(smooth = true) {
		scroller?.scrollTo({ top: scroller.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
	}

	async function markRead() {
		if (!conv || document.visibilityState !== 'visible') return;
		const last = [...messages].reverse().find((m) => !isLocal(m));
		if (!last || (conv.read_up_to && conv.read_up_to >= last.id)) return;
		conv = { ...conv, read_up_to: last.id, unread_count: 0, unread_mentions: 0 };
		chats.markRead(conv.id);
		await call(api.PUT('/v1/conversations/{conversationId}/read', { params: { path: { conversationId: conv.id } }, body: { message_id: last.id } })).catch(() => {});
	}

	$effect(() => {
		const conversationId = id;
		conv = null;
		messages = [];
		typists = new Map();
		chats.openId = conversationId;
		void load(conversationId);
		const off = live.on((e) => {
			if ((e.type === 'message.created' || e.type === 'message.updated') && e.conversation_id === conversationId) {
				const stick = nearBottom();
				messages = upsertMessage(messages, e.message);
				if (e.message.author) {
					const next = new Map(typists);
					next.delete(e.message.author.user_id);
					typists = next;
				}
				if (e.type === 'message.created') {
					if (stick || e.message.author?.user_id === meId) void tick().then(() => scrollToBottom());
					void markRead();
				}
			} else if (e.type === 'reaction.updated' && e.target_kind === 'message' && e.conversation_id === conversationId) {
				const m = messages.find((x) => x.id === e.target_id);
				if (m) messages = upsertMessage(messages, { ...m, reactions: e.summary });
			} else if (e.type === 'read.updated' && e.conversation_id === conversationId && conv) {
				conv = applyRead(conv, e.user_id, e.last_read_message_id);
			} else if (e.type === 'typing' && e.conversation_id === conversationId && e.user_id !== meId) {
				const next = new Map(typists);
				next.set(e.user_id, { userId: e.user_id, name: e.display_name, activity: e.activity, expires: Date.parse(e.expires_at) || Date.now() + 6000 });
				typists = next;
			} else if (e.type === 'conversation.updated' && e.conversation.id === conversationId) {
				conv = e.conversation;
			} else if (e.type === 'conversation.removed' && e.conversation_id === conversationId) {
				toasts.show(t('chat.removedFromChat'));
				void goto('/chats');
			}
		});
		const timer = setInterval(() => (now = Date.now()), 1000);
		const onVis = () => void markRead();
		document.addEventListener('visibilitychange', onVis);
		return () => {
			off();
			clearInterval(timer);
			document.removeEventListener('visibilitychange', onVis);
			if (chats.openId === conversationId) chats.openId = null;
		};
	});

	// Back online after a drop: fetch what we missed.
	let wasWaiting = false;
	$effect(() => {
		const s = live.status;
		if (s === 'waiting') wasWaiting = true;
		else if (s === 'open' && wasWaiting) {
			wasWaiting = false;
			untrack(() => void catchUp());
		}
	});

	// ---- who is who
	const feed = $derived(conv?.feed_id ? feeds.get(conv.feed_id) : undefined);
	/** Group admins; for family and circle chats the family's owner and admins (circle admins too, but we can't tell). */
	const canModerate = $derived(!!conv && (conv.kind === 'group' ? conv.my_role === 'admin' : (conv.kind === 'family' || conv.kind === 'circle') && isFeedAdmin(feed?.role)));
	const people = $derived((conv?.members ?? []).filter((m) => m.person.user_id !== meId && !m.person.former_member).map((m) => ({ user_id: m.person.user_id, display_name: m.person.display_name, avatar_url: m.person.avatar_url })));
	const showAuthors = $derived(conv?.kind !== 'direct');
	const dayKey = (iso: string) => {
		const d = new Date(iso);
		return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
	};
	const rows = $derived(chatRows(messages, meId, dayKey));
	const seenId = $derived(conv ? lastSeenOwn(messages, conv, meId) : null);
	const typing = $derived(activeTypists(typists, now));
	const title = $derived(conv ? (conv.kind === 'direct' && conv.other?.former_member ? t('social.formerMember') : conv.name) : t('chat.title'));

	function dayLabel(iso: string): string {
		const d = new Date(iso);
		const today = new Date();
		const y = new Date();
		y.setDate(today.getDate() - 1);
		if (d.toDateString() === today.toDateString()) return t('chat.today');
		if (d.toDateString() === y.toDateString()) return t('chat.yesterday');
		return new Intl.DateTimeFormat(i18n.tag, { weekday: 'long', day: 'numeric', month: 'long', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) }).format(d);
	}

	// ---- sending
	let replyTo = $state<Message | null>(null);
	let editing = $state<Message | null>(null);

	async function sendNow(m: { clientId: string; body?: string; mentions?: Mention[]; uploadIds?: string[]; voice?: { upload_id: string; duration_ms: number }; replyTo?: string; linkPreview?: boolean }): Promise<boolean> {
		try {
			const sent = await call(
				api.POST('/v1/conversations/{conversationId}/messages', {
					params: { path: { conversationId: id }, header: { 'Idempotency-Key': m.clientId } },
					body: {
						client_id: m.clientId,
						...(m.body ? { body: m.body } : {}),
						...(m.mentions?.length ? { mentions: m.mentions } : {}),
						...(m.uploadIds?.length ? { items: m.uploadIds.map((u) => ({ upload_id: u })) } : {}),
						...(m.voice ? { voice: m.voice } : {}),
						...(m.replyTo ? { reply_to_id: m.replyTo } : {}),
						...(m.linkPreview === false ? { link_preview: false } : {})
					}
				})
			);
			messages = upsertMessage(messages, sent);
			const { [m.clientId]: _, ...rest } = failed;
			failed = rest;
			return true;
		} catch (e) {
			failed = { ...failed, [m.clientId]: m };
			toasts.error(isCode(e, 'cannot_message') ? t('problem.cannot_message') : problemMessage(e));
			return false;
		}
	}

	function optimistic(clientId: string, body: string, mentions: Mention[], reply: Message | null): Message {
		return {
			id: localId(clientId),
			conversation_id: id,
			kind: 'person',
			author: auth.me ? { user_id: auth.me.id, display_name: auth.me.display_name, avatar_url: auth.me.avatar_url } : null,
			client_id: clientId,
			body: body || null,
			mentions,
			items: [],
			reactions: EMPTY_REACTIONS,
			deleted: false,
			reply_to: reply ? { id: reply.id, author: reply.author, excerpt: reply.body?.slice(0, 200) ?? null, deleted: false, voice: !!reply.voice } : null,
			created_at: new Date().toISOString()
		};
	}

	async function onsend(c: { body: string; mentions: Mention[]; uploadIds: string[]; linkPreview: boolean }): Promise<boolean> {
		const text = trimWithMentions(c.body, c.mentions);
		const clientId = crypto.randomUUID();
		// Text shows at once; photos come back from the server as processing items.
		if (!c.uploadIds.length) {
			messages = upsertMessage(messages, optimistic(clientId, text.body, text.mentions, replyTo));
			void tick().then(() => scrollToBottom());
		}
		const ok = await sendNow({ clientId, body: text.body, mentions: text.mentions, uploadIds: c.uploadIds, replyTo: replyTo?.id, linkPreview: c.linkPreview });
		if (ok) void tick().then(() => scrollToBottom());
		return ok || !c.uploadIds.length;
	}

	async function onvoice(v: { upload_id: string; duration_ms: number }) {
		const clientId = crypto.randomUUID();
		if (await sendNow({ clientId, voice: v, replyTo: replyTo?.id })) {
			replyTo = null;
			void tick().then(() => scrollToBottom());
		}
	}

	async function onedit(m: Message, body: string, mentions: Mention[]): Promise<boolean> {
		const text = trimWithMentions(body, mentions);
		try {
			const next = await call(
				api.PATCH('/v1/conversations/{conversationId}/messages/{messageId}', { params: { path: { conversationId: id, messageId: m.id } }, body: { body: text.body, mentions: text.mentions } })
			);
			messages = upsertMessage(messages, next);
			return true;
		} catch (e) {
			toasts.error(problemMessage(e));
			return false;
		}
	}

	function ontyping(activity: 'typing' | 'recording') {
		void call(api.POST('/v1/conversations/{conversationId}/typing', { params: { path: { conversationId: id } }, body: { activity } })).catch(() => {});
	}

	function deleteMessage(m: Message, asModerator: boolean) {
		const before = m;
		toasts.deferred({
			message: asModerator ? t('chat.messageRemovedToast') : t('chat.messageDeletedToast'),
			undoLabel: t('common.undo'),
			apply: () => (messages = upsertMessage(messages, { ...m, deleted: true, removed_by_admin: asModerator, body: null, items: [], voice: null, link_preview: null })),
			rollback: () => (messages = upsertMessage(messages, before)),
			commit: () => call(api.DELETE('/v1/conversations/{conversationId}/messages/{messageId}', { params: { path: { conversationId: id, messageId: m.id } } })),
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	let reportOpen = $state(false);
	let reportId = $state('');
	let putOpen = $state(false);
	let putMessage = $state<Message | null>(null);
	let settingsOpen = $state(false);

	function menuFor(m: Message): MenuItem[] {
		const own = m.author?.user_id === meId;
		const items: MenuItem[] = [];
		if (!conv?.read_only) items.push({ label: t('social.reply'), icon: 'reply', run: () => ((replyTo = m), (editing = null)) });
		if (canEditMessage(m, meId, discovery.limits.message_edit_hours)) items.push({ label: t('common.edit'), icon: 'edit', run: () => ((editing = m), (replyTo = null)) });
		if (m.body) items.push({ label: t('chat.copyText'), icon: 'copy', run: () => void navigator.clipboard?.writeText(m.body ?? '').then(() => toasts.success(t('chat.copied'))) });
		if (own) items.push({ label: t('chat.deleteMessage'), icon: 'trash', danger: true, run: () => deleteMessage(m, false) });
		else if (canModerate) items.push({ label: t('chat.removeMessage'), icon: 'trash', danger: true, run: () => deleteMessage(m, true) });
		if (!own) items.push({ label: t('chat.report'), icon: 'flag', run: () => ((reportId = m.id), (reportOpen = true)) });
		return items;
	}

	async function block() {
		const other = conv?.other;
		if (!other) return;
		try {
			await call(api.PUT('/v1/blocks/{userId}', { params: { path: { userId: other.user_id } } }));
			toasts.success(t('chat.blocked', { name: other.display_name }));
			await load(id);
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	const headerMenu = $derived.by((): MenuItem[] => {
		if (!conv) return [];
		const items: MenuItem[] = [{ label: t('chat.details'), icon: 'info', run: () => (settingsOpen = true) }];
		if (conv.kind === 'direct' && conv.other && !conv.other.former_member) {
			items.push({ label: t('chat.viewProfile'), icon: 'user', run: () => goto(`/people/${conv!.other!.user_id}`) });
			items.push({ label: t('chat.block', { name: conv.other.display_name }), icon: 'ban', danger: true, run: block });
		}
		return items;
	});
</script>

<svelte:head><title>{title} · kinframe</title></svelte:head>

<div class="chat-page">
	{#if !discovery.chats}
		<div class="container page"><p class="callout"><Icon name="info" size={18} />{t('chat.offBody')}</p></div>
	{:else if error}
		<div class="container page">
			<a href="/chats" class="back"><Icon name="chevronLeft" size={18} />{t('chat.title')}</a>
			<div class="callout callout-error" role="alert"><Icon name="alert" /><span>{isCode(error, 'not_found') ? t('chat.notFound') : problemMessage(error)}</span></div>
		</div>
	{:else}
		<header class="head">
			<a href="/chats" class="btn btn-ghost btn-icon" aria-label={t('chat.title')}><Icon name="chevronLeft" size={20} /></a>
			{#if conv}
				<button class="who" onclick={() => (settingsOpen = true)}>
					<ConversationAvatar conversation={conv} size={40} />
					<span class="who-text">
						<h1>{title}</h1>
						<span class="sub small muted" aria-live="polite">
							{#if typing.length}
								<span class="typing">{typing[0]!.activity === 'recording' ? t('chat.recording', { name: typing[0]!.name.split(' ')[0]! }) : typing.length > 1 ? t('chat.severalTyping') : t('chat.typing', { name: typing[0]!.name.split(' ')[0]! })}</span>
							{:else if conv.kind === 'direct'}{t('chat.direct')}{:else}{tn('chat.members', conv.member_count)}{/if}
						</span>
					</span>
				</button>
				<span class="spacer"></span>
				{#if conv.muted}<Icon name="bellOff" size={18} label={t('chat.muted')} />{/if}
				<MoreMenu label={t('chat.chatActions')} items={headerMenu} size="md" />
			{/if}
		</header>

		<div class="scroller" bind:this={scroller} onscroll={() => scroller && scroller.scrollTop < 200 && loadOlder()}>
			<div class="inner">
				{#if loading}
					<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
						<div class="skeleton" style="height:44px;width:60%"></div>
						<div class="skeleton" style="height:44px;width:40%;align-self:flex-end"></div>
						<div class="skeleton" style="height:44px;width:50%"></div>
					</div>
				{:else}
					{#if hasMoreBefore}
						<button class="btn btn-sm btn-ghost older" onclick={loadOlder} disabled={loadingOlder}>{#if loadingOlder}<span class="spinner" aria-hidden="true"></span>{/if}{t('chat.older')}</button>
					{:else}
						<div class="start">
							{#if conv}<ConversationAvatar conversation={conv} size={56} />{/if}
							<p class="muted small">{conv?.kind === 'family' ? t('chat.startFamily', { name: title }) : t('chat.start')}</p>
							{#if conv?.kind === 'family' && feed && canInviteToFeed(feed)}
								<a class="invite-card" href="/feeds/{feed.id}?tab=invites">
									<Icon name="link" size={18} />
									<span><strong>{t('chat.inviteTitle')}</strong><span class="small muted">{t('chat.inviteBody')}</span></span>
								</a>
							{/if}
							<OperatorNote compact />
						</div>
					{/if}
					<ol class="messages" aria-label={t('chat.messages')}>
						{#each rows as row (row.key)}
							<li>
								{#if row.kind === 'day'}
									<p class="day"><span>{dayLabel(row.date)}</span></p>
								{:else}
									<MessageBubble
										message={row.message}
										mine={row.mine}
										first={row.first}
										last={row.last}
										showAuthor={showAuthors}
										seen={row.message.id === seenId && conv ? seenBy(conv, row.message.id, meId) : []}
										receipts={row.message.id === seenId}
										menu={menuFor(row.message)}
										failed={!!row.message.client_id && isLocal(row.message) && !!failed[row.message.client_id]}
										onretry={row.message.client_id && failed[row.message.client_id] ? () => sendNow(failed[row.message.client_id!]!) : undefined}
										onreactions={(s: ReactionSummary) => (messages = upsertMessage(messages, { ...row.message, reactions: s }))}
										onquote={(qid) => document.getElementById(`m-${qid}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })}
										onputonframe={() => ((putMessage = row.message), (putOpen = true))}
									/>
								{/if}
							</li>
						{/each}
					</ol>
				{/if}
			</div>
		</div>

		{#if conv}
			{#if conv.read_only}
				<p class="readonly small muted">
					<Icon name="lock" size={14} />{conv.read_only_reason === 'no_longer_contacts' ? t('leaving.noLongerContacts', { name: readOnlyName(conv, auth.me?.id) }) : t('chat.readOnly')}
				</p>
			{:else}
				<div class="composer-wrap">
					<ChatComposer {people} bind:replyTo bind:editing {onsend} {onedit} {ontyping} {onvoice} />
				</div>
			{/if}
		{/if}
	{/if}
</div>

{#if conv && settingsOpen}<ChatSettingsDialog bind:open={settingsOpen} bind:conversation={conv} />{/if}
<ReportDialog bind:open={reportOpen} kind="message" targetId={reportId} />
{#if putMessage}
	<TargetDialog
		bind:open={putOpen}
		title={t('chat.putOnFrame')}
		intro={putMessage.author?.user_id === meId ? tn('chat.putOnFrameBody', putMessage.items.length) : t('chat.putOnFrameShared', { name: personName(putMessage.author, t('social.formerMember')) })}
		allowFeeds={putMessage.author?.user_id === meId}
		submitLabel={t('chat.putOnFrameSend')}
		onsubmit={async (target) => {
			await call(
				api.POST('/v1/conversations/{conversationId}/messages/{messageId}/send', {
					params: { path: { conversationId: id, messageId: putMessage!.id }, header: { 'Idempotency-Key': crypto.randomUUID() } },
					body: { frame_ids: target.frame_ids, ...(target.feed_ids.length ? { feed_ids: target.feed_ids } : {}), ...(target.circle_ids.length ? { circle_ids: target.circle_ids } : {}) }
				})
			);
			toasts.success(t('send.sent'));
		}}
	/>
{/if}

<style>
	.chat-page {
		display: flex;
		flex-direction: column;
		height: calc(100dvh - var(--nav-h) - var(--tabbar-h) - env(safe-area-inset-bottom));
		max-width: 900px;
		margin-inline: auto;
	}
	@media (min-width: 860px) {
		.chat-page {
			height: calc(100dvh - var(--nav-h));
		}
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
	.head {
		flex: none;
		display: flex;
		align-items: center;
		gap: var(--s-2);
		padding: var(--s-2) var(--s-3);
		border-bottom: 1px solid var(--border);
		background: var(--bg);
	}
	.who {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		min-width: 0;
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		text-align: start;
		padding: 4px;
		border-radius: var(--r);
		cursor: pointer;
	}
	.who:hover {
		background: var(--surface-2);
	}
	.who-text {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	h1 {
		font-size: 1.05rem;
		font-family: var(--font-body);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.typing {
		color: var(--primary-soft-text);
		font-weight: 600;
	}
	.scroller {
		flex: 1;
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	.inner {
		padding: var(--s-3) var(--s-3) var(--s-4);
		display: flex;
		flex-direction: column;
	}
	.older {
		align-self: center;
	}
	.start {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-2);
		text-align: center;
		padding: var(--s-4) var(--s-2);
		max-width: 460px;
		align-self: center;
	}
	.start p {
		margin: 0;
	}
	.invite-card {
		display: flex;
		gap: var(--s-3);
		align-items: center;
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface);
		border: 1px solid var(--border);
		color: inherit;
		text-decoration: none;
		text-align: start;
	}
	.invite-card span {
		display: flex;
		flex-direction: column;
	}
	.messages {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.day {
		display: flex;
		justify-content: center;
		margin: var(--s-4) 0 var(--s-2);
	}
	.day span {
		padding: 2px 12px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-muted);
		font-size: 0.76rem;
		font-weight: 600;
	}
	.composer-wrap {
		flex: none;
	}
	.readonly {
		flex: none;
		margin: 0;
		padding: var(--s-3);
		display: flex;
		gap: 6px;
		align-items: center;
		justify-content: center;
		border-top: 1px solid var(--border);
	}
</style>
