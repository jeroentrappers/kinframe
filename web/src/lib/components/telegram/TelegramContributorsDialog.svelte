<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type TelegramChat, type TelegramContributor } from '$lib/api/client';
	import { upsertById } from '$lib/emailInbox';
	import { relativeTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** The people who sent in a chat (telegram.md §3, story 6): block one to ignore their photos from now on. */
	let { open = $bindable(false), chat }: { open?: boolean; chat: TelegramChat } = $props();

	let list = $state<TelegramContributor[]>([]);
	let loading = $state(false);
	let error = $state<unknown>(null);
	let busy = $state<string | null>(null);

	async function load() {
		loading = true;
		error = null;
		try {
			list = await call(api.GET('/v1/telegram-chats/{chatId}/contributors', { params: { path: { chatId: chat.id } } }));
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (open) untrack(() => void load());
	});

	async function setBlocked(c: TelegramContributor, blocked: boolean) {
		busy = c.id;
		try {
			const next = await call(
				api.PATCH('/v1/telegram-chats/{chatId}/contributors/{contributorId}', { params: { path: { chatId: chat.id, contributorId: c.id } }, body: { blocked } })
			);
			list = upsertById(list, next);
			toasts.success(blocked ? t('telegram.blocked', { name: c.display_name }) : t('telegram.unblocked', { name: c.display_name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}
</script>

<Dialog bind:open title={t('telegram.peopleTitle', { name: chat.title })}>
	{#if loading && list.length === 0}
		<div class="skeleton" style="height:80px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</div>
	{:else if list.length === 0}
		<p class="muted">{t('telegram.peopleEmpty')}</p>
	{:else}
		<ul class="people" role="list">
			{#each list as c (c.id)}
				<li class="p-row" class:blocked={c.blocked} data-testid="telegram-contributor">
					<div class="who">
						<strong>{c.display_name}</strong>
						<span class="faint tiny">
							{tn('telegram.sentCount', c.sent_count)} · {relativeTime(c.last_seen_at, i18n.tag)}{#if c.linked} · {t('telegram.linkedBadge')}{/if}{#if c.blocked} · {t('email.blockedBadge')}{/if}
						</span>
					</div>
					{#if c.blocked}
						<button class="btn btn-sm btn-ghost" onclick={() => setBlocked(c, false)} disabled={busy === c.id}>{t('email.unblock')}</button>
					{:else}
						<button class="btn btn-sm btn-danger-ghost" onclick={() => setBlocked(c, true)} disabled={busy === c.id}>{t('email.block')}</button>
					{/if}
				</li>
			{/each}
		</ul>
		<p class="faint tiny note">{t('telegram.blockHint')}</p>
	{/if}
</Dialog>

<style>
	.people {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.p-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		padding: var(--s-2) 0;
		border-bottom: 1px solid var(--border);
	}
	.p-row:last-child {
		border-bottom: 0;
	}
	.p-row.blocked strong {
		color: var(--text-muted);
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.note {
		margin: var(--s-3) 0 0;
	}
</style>
