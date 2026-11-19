<script lang="ts">
	import { api, call, type TelegramChat, type TelegramChatUpdate } from '$lib/api/client';
	import type { ChannelTarget } from '$lib/emailInbox';
	import { isCode, problemMessage } from '$lib/problem';
	import { chatView, needsPrivacyFix, warningText } from '$lib/telegram';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import TelegramContributorsDialog from './TelegramContributorsDialog.svelte';

	let {
		chat,
		target,
		highlight = false,
		onupdated,
		onremoved
	}: { chat: TelegramChat; target: ChannelTarget; highlight?: boolean; onupdated: (c: TelegramChat) => void; onremoved: (id: string) => void } = $props();

	const uid = $props.id();
	const view = $derived(chatView(chat, i18n.tag));
	const ended = $derived(chat.status === 'disconnected');
	const manage = $derived(chat.can_manage !== false && !ended);
	const privacy = $derived(needsPrivacyFix(chat));

	let busy = $state<string | null>(null);
	let peopleOpen = $state(false);
	let disconnectOpen = $state(false);

	async function patch(key: string, body: TelegramChatUpdate, done?: string) {
		busy = key;
		try {
			const c = await call(api.PATCH('/v1/telegram-chats/{chatId}', { params: { path: { chatId: chat.id } }, body }));
			onupdated(c);
			if (done) toasts.success(done);
		} catch (e) {
			if (isCode(e, 'not_found')) onremoved(chat.id);
			else toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	async function disconnect() {
		busy = 'disconnect';
		try {
			await call(api.DELETE('/v1/telegram-chats/{chatId}', { params: { path: { chatId: chat.id } } }));
			disconnectOpen = false;
			toasts.success(t('telegram.disconnected', { name: chat.title }));
			// The chat stays listed under "Earlier" (the server keeps it 30 days).
			try {
				onupdated(await call(api.GET('/v1/telegram-chats/{chatId}', { params: { path: { chatId: chat.id } } })));
			} catch {
				onremoved(chat.id);
			}
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	function onTopic(e: Event & { currentTarget: HTMLSelectElement }) {
		const v = e.currentTarget.value;
		void patch('topic', { topic_id: v === '' ? null : Number(v) }, t('common.saved'));
	}
</script>

<article class="card chat tone-{view.tone}" class:highlight id="tg-{chat.id}" data-testid="telegram-chat" aria-labelledby="{uid}-title">
	<header class="c-head">
		<span class="kind" aria-hidden="true"><Icon name={chat.chat_kind === 'group' ? 'users' : 'user'} size={18} /></span>
		<div class="who">
			<h3 id="{uid}-title">{chat.title}</h3>
			<p class="muted small meta">
				<span>{chat.chat_kind === 'group' ? t('telegram.kindGroup') : t('telegram.kindPrivate')}</span>
				{#if chat.owner}<span>{chat.is_owner ? t('telegram.connectedByYou') : t('telegram.connectedBy', { name: chat.owner.display_name })}</span>{/if}
			</p>
		</div>
	</header>

	<p class="status" role="status"><span class="dot-tone" aria-hidden="true"></span><span class="s-label">{view.label}</span></p>

	{#if view.detail}
		<div class="callout small" class:callout-warn={view.tone === 'attention'} data-testid="telegram-detail">
			<Icon name={view.tone === 'attention' ? 'alert' : 'info'} size={18} />
			<div class="stack-sm">
				<span>{view.detail}</span>
				{#if privacy && chat.error_code !== 'bot_changed'}<span class="faint tiny">{t('telegram.privacyOperator')}</span>{/if}
			</div>
		</div>
	{/if}

	{#if !ended}
		{#each chat.warnings ?? [] as w (w)}
			<p class="callout small warning"><Icon name="info" size={18} />{warningText(w)}</p>
		{/each}

		<p class="faint tiny counts">
			<span>{tn('telegram.sentCount', chat.counts.sent)}</span>
			<span>{tn('telegram.contributorCount', chat.counts.contributors)}</span>
		</p>
	{/if}

	{#if manage}
		<div class="settings stack-sm">
			{#if chat.chat_kind === 'group'}
				<div class="field">
					<label for="{uid}-who">{t('telegram.whoCanSend')}</label>
					<select id="{uid}-who" class="select" value={chat.contributors} onchange={(e) => patch('contributors', { contributors: e.currentTarget.value as TelegramChat['contributors'] }, t('common.saved'))} disabled={busy !== null}>
						<option value="everyone">{t('telegram.everyone')}</option>
						<option value="linked_only">{t('telegram.linkedOnly', { name: target.name })}</option>
					</select>
				</div>
				{#if chat.is_forum && (chat.topics?.length ?? 0) > 0}
					<div class="field">
						<label for="{uid}-topic">{t('telegram.topic')}</label>
						<select id="{uid}-topic" class="select" value={chat.topic_id == null ? '' : String(chat.topic_id)} onchange={onTopic} disabled={busy !== null}>
							<option value="">{t('telegram.allTopics')}</option>
							{#each chat.topics ?? [] as tp (tp.id)}<option value={String(tp.id)}>{tp.name}</option>{/each}
						</select>
					</div>
				{/if}
			{/if}
			{#if target.kind === 'frame'}
				<label class="switch">
					<input type="checkbox" checked={chat.frame_replies} onchange={(e) => patch('replies', { frame_replies: e.currentTarget.checked })} disabled={busy !== null} />
					<span><span class="s-label">{t('telegram.frameReplies')}</span><span class="hint">{t('telegram.frameRepliesHint', { name: target.name })}</span></span>
				</label>
			{/if}
			<label class="switch">
				<input type="checkbox" checked={chat.offer_app_invite} onchange={(e) => patch('invite', { offer_app_invite: e.currentTarget.checked })} disabled={busy !== null} />
				<span><span class="s-label">{t('telegram.offerInvite')}</span><span class="hint">{t('telegram.offerInviteHint', { name: target.name })}</span></span>
			</label>
			{#if chat.is_owner}
				<label class="switch">
					<input type="checkbox" checked={chat.notify_reactions} onchange={(e) => patch('notify', { notify_reactions: e.currentTarget.checked })} disabled={busy !== null} />
					<span><span class="s-label">{t('telegram.notifyReactions')}</span><span class="hint">{t('telegram.notifyReactionsHint')}</span></span>
				</label>
			{/if}
		</div>

		<div class="row-wrap actions">
			<button class="btn btn-sm btn-ghost" onclick={() => (peopleOpen = true)}><Icon name="users" size={16} />{t('telegram.people')}</button>
			<button class="btn btn-sm btn-ghost" onclick={() => patch('pause', { paused: chat.status !== 'paused' }, chat.status === 'paused' ? t('telegram.resumedToast') : t('telegram.pausedToast'))} disabled={busy !== null}>
				{#if busy === 'pause'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={chat.status === 'paused' ? 'play' : 'pause'} size={16} />{/if}
				{chat.status === 'paused' ? t('telegram.resume') : t('telegram.pause')}
			</button>
			{#if !chat.is_owner}
				<button class="btn btn-sm btn-ghost" onclick={() => patch('takeover', { take_over: true }, t('telegram.tookOver', { name: chat.title }))} disabled={busy !== null}><Icon name="user" size={16} />{t('telegram.takeOver')}</button>
			{/if}
			<span class="spacer"></span>
			<button class="btn btn-sm btn-danger-ghost" onclick={() => (disconnectOpen = true)}><Icon name="x" size={16} />{t('telegram.disconnect')}</button>
		</div>
	{/if}
</article>

{#if manage}
	<TelegramContributorsDialog bind:open={peopleOpen} {chat} />
	<Dialog bind:open={disconnectOpen} title={t('telegram.disconnectTitle', { name: chat.title })}>
		<p>{t('telegram.disconnectBody', { name: target.name })}</p>
		{#snippet footer()}
			<button class="btn btn-ghost" onclick={() => (disconnectOpen = false)}>{t('common.cancel')}</button>
			<button class="btn btn-danger" onclick={disconnect} disabled={busy !== null}>
				{#if busy === 'disconnect'}<span class="spinner" aria-hidden="true"></span>{/if}{t('telegram.disconnect')}
			</button>
		{/snippet}
	</Dialog>
{/if}

<style>
	.chat {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		border-inline-start: 4px solid var(--tone, var(--border));
	}
	.chat.highlight {
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 35%, transparent);
	}
	.tone-ok {
		--tone: var(--accent);
	}
	.tone-attention {
		--tone: var(--warn);
	}
	.tone-paused,
	.tone-ended {
		--tone: var(--text-faint);
	}
	.c-head {
		display: flex;
		align-items: flex-start;
		gap: var(--s-3);
	}
	.kind {
		display: grid;
		place-items: center;
		width: 34px;
		height: 34px;
		border-radius: 50%;
		background: color-mix(in srgb, #229ed9 15%, transparent);
		color: #1b87bb;
		flex: none;
	}
	.who {
		flex: 1;
		min-width: 0;
	}
	h3 {
		font-size: 1.05rem;
		overflow-wrap: anywhere;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		gap: 2px 10px;
		margin: 0;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
	}
	.s-label {
		font-weight: 600;
	}
	.dot-tone {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--tone);
		flex: none;
	}
	.warning,
	.callout {
		margin: 0;
	}
	.counts {
		display: flex;
		flex-wrap: wrap;
		gap: 2px 12px;
		margin: 0;
	}
	.settings .s-label {
		display: block;
	}
	.settings .hint {
		display: block;
	}
	.actions {
		gap: 6px;
	}
	.actions .spacer {
		flex: 1;
	}
</style>
