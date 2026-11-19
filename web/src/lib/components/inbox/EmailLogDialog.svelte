<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress, type EmailMessage } from '$lib/api/client';
	import { outcomeText, outcomeTone, replyText, skipText } from '$lib/emailInbox';
	import { formatDateTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** "Recent mail" (email-inbox.md §3, story 8): what became of each mail in the last 30 days. */
	let { open = $bindable(false), address }: { open?: boolean; address: EmailAddress } = $props();

	let items = $state<EmailMessage[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let error = $state<unknown>(null);

	async function load(more = false) {
		loading = true;
		error = null;
		try {
			const page = await call(
				api.GET('/v1/email-addresses/{addressId}/messages', {
					params: { path: { addressId: address.id }, query: more && cursor ? { cursor } : {} }
				})
			);
			items = more ? [...items, ...page.items] : page.items;
			cursor = page.next_cursor ?? null;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (!open) return;
		untrack(() => {
			items = [];
			cursor = null;
			void load();
		});
	});
</script>

<Dialog bind:open title={t('email.logTitle', { name: address.name })} size="md">
	{#if loading && items.length === 0}
		<div class="skeleton" style="height:120px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</div>
	{:else if items.length === 0}
		<p class="muted">{t('email.logEmpty')}</p>
	{:else}
		<ul class="log" role="list">
			{#each items as m (m.id)}
				{@const skipped = skipText(m.skipped)}
				{@const reply = replyText(m.reply)}
				<li class="entry tone-{outcomeTone(m.outcome)}" data-testid="email-log-entry">
					<span class="dot" aria-hidden="true"></span>
					<div class="text">
						<span class="what">{outcomeText(m)}</span>
						<span class="faint tiny">
							{formatDateTime(m.received_at, i18n.tag)}{#if m.sender_email} · {m.sender_email}{/if}{#if !m.authenticated} · {t('email.notVerified')}{/if}
						</span>
						{#if skipped}<span class="muted tiny">{t('email.skippedLine', { list: skipped })}</span>{/if}
						{#if reply}<span class="muted tiny">{reply}</span>{/if}
					</div>
				</li>
			{/each}
		</ul>
		{#if cursor}
			<button class="btn btn-sm btn-ghost more" onclick={() => load(true)} disabled={loading}>
				{#if loading}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.loadMore')}
			</button>
		{/if}
	{/if}
</Dialog>

<style>
	.log {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.entry {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
	}
	.dot {
		width: 10px;
		height: 10px;
		margin-top: 6px;
		border-radius: 50%;
		background: var(--tone);
		flex: none;
	}
	.tone-ok {
		--tone: var(--accent);
	}
	.tone-held {
		--tone: var(--warn);
	}
	.tone-problem {
		--tone: var(--danger);
	}
	.tone-muted {
		--tone: var(--text-faint);
	}
	.text {
		display: flex;
		flex-direction: column;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.what {
		font-weight: 600;
	}
	.more {
		margin-top: var(--s-3);
	}
</style>
