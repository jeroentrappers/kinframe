<script lang="ts">
	import { api, call, type Conversation } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { previewKind } from '$lib/chat';
	import { discovery } from '$lib/discovery.svelte';
	import { relativeTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { chats } from '$lib/social.svelte';
	import { i18n, kindSummary, t, tn } from '$lib/i18n/index.svelte';
	import { kindCounts } from '$lib/logic';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import ConversationAvatar from '$components/chat/ConversationAvatar.svelte';
	import NewChatDialog from '$components/chat/NewChatDialog.svelte';
	import OperatorNote from '$components/chat/OperatorNote.svelte';
	import { systemText } from '$components/chat/systemText';

	let showArchived = $state(false);
	let archived = $state<Conversation[] | null>(null);
	let newOpen = $state(false);

	$effect(() => void chats.load());

	async function loadArchived() {
		showArchived = !showArchived;
		if (!showArchived) return;
		try {
			archived = (await call(api.GET('/v1/conversations', { params: { query: { archived: true } } }))).filter((c) => c.archived);
		} catch {
			archived = [];
		}
	}

	function preview(c: Conversation): string {
		const m = c.last_message;
		const k = previewKind(m);
		if (!m || k === 'empty') return c.kind === 'family' || c.kind === 'circle' ? t('chat.sayHello') : '';
		const who = m.author?.user_id === auth.me?.id ? t('social.you') : personName(m.author, t('social.formerMember')).split(' ')[0];
		const withWho = (s: string) => (c.kind === 'direct' && m.author?.user_id !== auth.me?.id ? s : `${who}: ${s}`);
		if (k === 'deleted') return t('chat.messageDeleted');
		if (k === 'system') return systemText(m.system);
		if (k === 'voice') return withWho(t('chat.voiceMessage'));
		if (k === 'post') return withWho(t('chat.sharedPost'));
		if (k === 'photos' || k === 'videos') return withWho(kindSummary(kindCounts(m.items)));
		return withWho(m.body ?? '');
	}
</script>

<svelte:head><title>{t('chat.title')} · kinframe</title></svelte:head>

<div class="container page chats">
	<div class="page-header">
		<h1>{t('chat.title')}</h1>
		{#if discovery.chats}
			<button class="btn btn-primary" onclick={() => (newOpen = true)}><Icon name="plus" size={18} />{t('chat.newChat')}</button>
		{/if}
	</div>

	{#if !discovery.chats}
		<p class="callout"><Icon name="info" size={18} />{t('chat.offBody')}</p>
	{:else if chats.error && !chats.loaded}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm"><span>{problemMessage(chats.error)}</span><button class="btn btn-sm" onclick={() => chats.load(true)}>{t('common.tryAgain')}</button></div>
		</div>
	{:else if !chats.loaded}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
			{#each [0, 1, 2, 3] as i (i)}<div class="skeleton" style="height:68px"></div>{/each}
		</div>
	{:else}
		{#if chats.list.length === 0}
			<div class="card">
				<EmptyState compact art="people" title={t('chat.emptyTitle')} body={t('chat.emptyBody')}>
					{#snippet actions()}<button class="btn btn-primary" onclick={() => (newOpen = true)}><Icon name="plus" size={18} />{t('chat.newChat')}</button>{/snippet}
				</EmptyState>
			</div>
		{:else}
			<ul class="card list" role="list">
				{#each chats.list as c (c.id)}
					{@render row(c)}
				{/each}
			</ul>
		{/if}

		<button class="btn btn-ghost btn-sm archived-toggle" aria-expanded={showArchived} onclick={loadArchived}><Icon name="archive" size={16} />{t('chat.archived')}</button>
		{#if showArchived}
			{#if archived === null}
				<div class="skeleton" style="height:68px"></div>
			{:else if archived.length === 0}
				<p class="muted small">{t('chat.noArchived')}</p>
			{:else}
				<ul class="card list" role="list">
					{#each archived as c (c.id)}{@render row(c)}{/each}
				</ul>
			{/if}
		{/if}
		<OperatorNote />
	{/if}
</div>

{#snippet row(c: Conversation)}
	<li>
		<a class="conv" href="/chats/{c.id}" class:unread={c.unread_count > 0 && !c.muted}>
			<ConversationAvatar conversation={c} size={48} />
			<span class="text">
				<span class="top">
					<span class="name">{c.kind === 'direct' && c.other?.former_member ? t('social.formerMember') : c.name}</span>
					{#if c.kind === 'family'}<span class="kind-badge">{t('chat.kind.family')}</span>{:else if c.kind === 'circle'}<span class="kind-badge">{t('chat.kind.circle')}</span>{/if}
					<time class="when faint tiny" datetime={c.last_activity_at}>{relativeTime(c.last_activity_at, i18n.tag)}</time>
				</span>
				<span class="bottom">
					<span class="preview">{preview(c)}</span>
					{#if c.muted}<Icon name="bellOff" size={14} label={t('chat.muted')} />{/if}
					{#if (c.unread_mentions ?? 0) > 0}<span class="at" aria-label={t('chat.mentioned')}>@</span>{/if}
					{#if c.unread_count > 0}<span class="count num" aria-label={tn('chat.unread', c.unread_count)}>{c.unread_count > 99 ? '99+' : c.unread_count}</span>{/if}
				</span>
			</span>
		</a>
	</li>
{/snippet}

<NewChatDialog bind:open={newOpen} />

<style>
	.chats {
		max-width: 760px;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 4px;
		display: flex;
		flex-direction: column;
	}
	.conv {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 12px;
		border-radius: var(--r);
		color: inherit;
		text-decoration: none;
	}
	.conv:hover {
		background: var(--surface-2);
	}
	.text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.top,
	.bottom {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-width: 0;
	}
	.name {
		font-weight: 650;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.unread .name {
		font-weight: 800;
	}
	.kind-badge {
		flex: none;
		font-size: 0.68rem;
		font-weight: 700;
		padding: 1px 7px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.when {
		margin-inline-start: auto;
		flex: none;
	}
	.preview {
		flex: 1;
		min-width: 0;
		color: var(--text-muted);
		font-size: 0.9rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.unread .preview {
		color: var(--text);
	}
	.count,
	.at {
		flex: none;
		min-width: 22px;
		height: 22px;
		padding: 0 7px;
		border-radius: var(--r-full);
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.75rem;
		font-weight: 800;
		display: inline-grid;
		place-items: center;
	}
	.at {
		background: var(--accent);
	}
	.archived-toggle {
		margin-top: var(--s-4);
		align-self: flex-start;
	}
</style>
