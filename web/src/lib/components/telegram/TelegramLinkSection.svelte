<script lang="ts">
	import { api, call, type TelegramLink } from '$lib/api/client';
	import { formatDateTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { linkLabel } from '$lib/telegram';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import TelegramConnectDialog from './TelegramConnectDialog.svelte';

	/** Account settings → "Link my Telegram" (telegram.md §3, Telegram users 1). */
	let link = $state<TelegramLink | null>(null);
	let loading = $state(true);
	let error = $state<unknown>(null);
	let connectOpen = $state(false);
	let unlinkOpen = $state(false);
	let busy = $state(false);

	async function load() {
		loading = true;
		error = null;
		try {
			link = await call(api.GET('/v1/me/telegram'));
		} catch (e) {
			if (isCode(e, 'not_found')) link = null;
			else error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void load();
		return live.on((e) => {
			if (e.type === 'telegram_link.updated') {
				link = e.link;
				if (e.link && connectOpen) toasts.success(t('telegram.linkedAs', { name: e.link.display_name }));
			}
		});
	});

	async function unlink() {
		busy = true;
		try {
			await call(api.DELETE('/v1/me/telegram'));
			link = null;
			unlinkOpen = false;
			toasts.success(t('telegram.unlinked'));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}
</script>

<section class="card card-pad" aria-labelledby="tg-link-title" data-testid="telegram-link">
	<div class="section-title"><h2 id="tg-link-title"><Icon name="plane" size={18} />Telegram</h2></div>
	{#if loading && !link}
		<div class="skeleton" style="height:44px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</div>
	{:else if link}
		<div class="row-wrap linked">
			<div class="grow">
				<p class="s-label">{t('telegram.linkedTo', { name: linkLabel(link) })}</p>
				<p class="hint">{t('telegram.linkedHint')} · {t('telegram.linkedSince', { when: formatDateTime(link.linked_at, i18n.tag) })}</p>
				{#if !link.can_message}<p class="callout callout-warn small"><Icon name="alert" size={18} />{t('telegram.botBlocked')}</p>{/if}
			</div>
			<button class="btn btn-sm btn-danger-ghost" onclick={() => (unlinkOpen = true)}>{t('telegram.unlink')}</button>
		</div>
	{:else}
		<div class="row-wrap linked">
			<p class="hint grow">{t('telegram.linkHint')}</p>
			<button class="btn btn-sm btn-primary" onclick={() => (connectOpen = true)}><Icon name="link" size={16} />{t('telegram.linkTitle')}</button>
		</div>
	{/if}
</section>

<TelegramConnectDialog bind:open={connectOpen} kind="link" onlinked={load} />

<Dialog bind:open={unlinkOpen} title={t('telegram.unlinkTitle')}>
	<p>{t('telegram.unlinkBody')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (unlinkOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={unlink} disabled={busy}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('telegram.unlink')}
		</button>
	{/snippet}
</Dialog>

<style>
	h2 {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.linked {
		justify-content: space-between;
		gap: var(--s-3);
	}
	.grow {
		flex: 1;
		min-width: 200px;
	}
	.grow p {
		margin: 0;
	}
	.s-label {
		font-weight: 600;
	}
	.callout {
		margin-top: var(--s-2) !important;
	}
</style>
