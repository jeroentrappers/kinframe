<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress, type EmailAddressUpdate } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { addressParts, addressView, mailtoHref, shareMessage, type ChannelTarget } from '$lib/emailInbox';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import EmailLogDialog from './EmailLogDialog.svelte';
	import EmailPendingDialog from './EmailPendingDialog.svelte';
	import EmailSendersDialog from './EmailSendersDialog.svelte';
	import EmailSettingsDialog from './EmailSettingsDialog.svelte';

	let {
		address,
		target,
		tick = 0,
		openPending = false,
		onupdated,
		onremoved
	}: {
		address: EmailAddress;
		target: ChannelTarget;
		/** Changes when this address's senders change (websocket). */
		tick?: number;
		/** Open the pending list right away (from a notification). */
		openPending?: boolean;
		onupdated: (a: EmailAddress) => void;
		onremoved: (id: string) => void;
	} = $props();

	const view = $derived(addressView(address, i18n.tag));
	const parts = $derived(addressParts(address.address, address.slug));
	const c = $derived(address.counts);

	let busy = $state<'pause' | 'regenerate' | 'delete' | 'takeover' | null>(null);
	let pendingOpen = $state(false);
	let sendersOpen = $state(false);
	let logOpen = $state(false);
	let settingsOpen = $state(false);
	let regenOpen = $state(false);
	let deleteOpen = $state(false);

	$effect(() => {
		if (openPending) untrack(() => (pendingOpen = true));
	});

	async function run<T>(what: typeof busy, p: () => Promise<T>): Promise<T | undefined> {
		busy = what;
		try {
			return await p();
		} catch (e) {
			if (isCode(e, 'not_found')) {
				toasts.show(t('email.gone'));
				onremoved(address.id);
			} else toasts.error(problemMessage(e));
			return undefined;
		} finally {
			busy = null;
		}
	}

	async function patch(what: typeof busy, body: EmailAddressUpdate, done: string) {
		const a = await run(what, () => call(api.PATCH('/v1/email-addresses/{addressId}', { params: { path: { addressId: address.id } }, body })));
		if (!a) return false;
		onupdated(a);
		toasts.success(done);
		return true;
	}

	function togglePause() {
		const pausing = address.status !== 'paused';
		void patch('pause', { paused: pausing }, pausing ? t('email.pausedToast') : t('email.resumedToast'));
	}

	async function regenerate() {
		const a = await run('regenerate', () => call(api.POST('/v1/email-addresses/{addressId}/regenerate', { params: { path: { addressId: address.id } } })));
		if (!a) return;
		onupdated(a);
		regenOpen = false;
		toasts.success(t('email.regenerated', { address: a.address }));
	}

	async function remove() {
		const ok = await run('delete', async () => {
			await call(api.DELETE('/v1/email-addresses/{addressId}', { params: { path: { addressId: address.id } } }));
			return true;
		});
		if (!ok) return;
		deleteOpen = false;
		onremoved(address.id);
		toasts.success(t('email.deleted'));
	}

	/** Counts after a decision (the websocket also brings them). */
	async function refresh() {
		try {
			onupdated(await call(api.GET('/v1/email-addresses/{addressId}', { params: { path: { addressId: address.id } } })));
		} catch {
			/* keep what we have */
		}
	}

	async function copy() {
		try {
			await navigator.clipboard.writeText(address.address);
			toasts.success(t('email.copied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}

	async function share() {
		const text = shareMessage(address, auth.me?.display_name ?? '');
		if (navigator.share) {
			try {
				await navigator.share({ title: t('email.shareTitle', { name: address.name }), text });
			} catch {
				/* cancelled */
			}
			return;
		}
		try {
			await navigator.clipboard.writeText(text);
			toasts.success(t('email.shareCopied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}
</script>

<article class="card addr tone-{view.tone}" data-testid="email-address" aria-labelledby="ea-{address.id}">
	<header class="a-head">
		<div class="who">
			<h3 id="ea-{address.id}">{address.name}</h3>
			<p class="status small" role="status"><span class="dot-tone" aria-hidden="true"></span>{view.label}</p>
		</div>
		{#if !address.is_owner}
			<span class="faint tiny">{address.owner ? t('email.setUpBy', { name: address.owner.display_name }) : ''}</span>
		{/if}
	</header>

	<div class="addr-box" class:paused={address.status === 'paused'}>
		<code class="address" data-testid="email-address-value"><span class="slug">{parts.slug}</span>{#if parts.random}-{parts.random}{/if}{#if parts.domain}<span class="domain">@{parts.domain}</span>{/if}</code>
		<div class="row-wrap">
			<button class="btn btn-sm" onclick={copy}><Icon name="copy" size={16} />{t('email.copy')}</button>
			<button class="btn btn-sm" onclick={share}><Icon name="share" size={16} />{t('email.share')}</button>
			<a class="btn btn-sm btn-ghost" href={mailtoHref(address.address, address.name)}><Icon name="mail" size={16} />{t('email.tryIt')}</a>
		</div>
	</div>

	{#if view.pending}
		<div class="callout callout-warn small pending" data-testid="email-pending-callout">
			<Icon name="bell" size={18} />
			<div class="row-wrap grow">
				<span class="grow">{view.pending}</span>
				<button class="btn btn-sm btn-primary" onclick={() => (pendingOpen = true)}>{t('email.review')}</button>
			</div>
		</div>
	{/if}

	<p class="faint tiny counts">
		<span>{tn('email.allowedCount', c.allowed_senders)}</span>
		{#if c.delivered_30d}<span>{tn('email.delivered30', c.delivered_30d)}</span>{/if}
		{#if c.held_messages}<span>{tn('email.heldCount', c.held_messages)}</span>{/if}
	</p>

	<div class="row-wrap actions">
		<button class="btn btn-sm btn-ghost" onclick={() => (sendersOpen = true)}><Icon name="users" size={16} />{t('email.senders')}</button>
		<button class="btn btn-sm btn-ghost" onclick={() => (logOpen = true)}><Icon name="history" size={16} />{t('email.recentMail')}</button>
		<button class="btn btn-sm btn-ghost" onclick={() => (settingsOpen = true)}><Icon name="settings" size={16} />{t('email.settings')}</button>
		<button class="btn btn-sm btn-ghost" onclick={togglePause} disabled={busy !== null}>
			{#if busy === 'pause'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={address.status === 'paused' ? 'play' : 'pause'} size={16} />{/if}
			{address.status === 'paused' ? t('email.resume') : t('email.pause')}
		</button>
		{#if !address.is_owner}
			<button class="btn btn-sm btn-ghost" onclick={() => patch('takeover', { take_over: true }, t('email.tookOver'))} disabled={busy !== null}><Icon name="user" size={16} />{t('email.takeOver')}</button>
		{/if}
		<span class="spacer"></span>
		<button class="btn btn-sm btn-ghost" onclick={() => (regenOpen = true)}><Icon name="refresh" size={16} />{t('email.regenerate')}</button>
		<button class="btn btn-sm btn-danger-ghost" onclick={() => (deleteOpen = true)}><Icon name="trash" size={16} />{t('email.delete')}</button>
	</div>
</article>

<EmailPendingDialog bind:open={pendingOpen} {address} {target} {tick} onchanged={refresh} />
<EmailSendersDialog bind:open={sendersOpen} {address} {target} {tick} onchanged={refresh} />
<EmailLogDialog bind:open={logOpen} {address} />
<EmailSettingsDialog bind:open={settingsOpen} {address} onsaved={onupdated} />

<Dialog bind:open={regenOpen} title={t('email.regenerateTitle')}>
	<p>{t('email.regenerateBody', { address: address.address })}</p>
	<p class="muted small">{t('email.regenerateKeeps')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (regenOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={regenerate} disabled={busy !== null}>
			{#if busy === 'regenerate'}<span class="spinner" aria-hidden="true"></span>{/if}{t('email.regenerateConfirm')}
		</button>
	{/snippet}
</Dialog>

<Dialog bind:open={deleteOpen} title={t('email.deleteTitle')}>
	<p>{t('email.deleteBody', { address: address.address })}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (deleteOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={remove} disabled={busy !== null}>
			{#if busy === 'delete'}<span class="spinner" aria-hidden="true"></span>{/if}{t('email.deleteConfirm')}
		</button>
	{/snippet}
</Dialog>

<style>
	.addr {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		border-inline-start: 4px solid var(--tone, var(--border));
	}
	.tone-ok {
		--tone: var(--accent);
	}
	.tone-paused {
		--tone: var(--text-faint);
	}
	.tone-attention {
		--tone: var(--warn);
	}
	.a-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: var(--s-3);
	}
	.who {
		min-width: 0;
	}
	h3 {
		font-size: 1.05rem;
		overflow-wrap: anywhere;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 2px 0 0;
		color: var(--text-muted);
	}
	.dot-tone {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--tone);
		flex: none;
	}
	.addr-box {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface-2, var(--bg));
		border: 1px solid var(--border);
	}
	.addr-box.paused .address {
		opacity: 0.6;
	}
	.address {
		font-family: ui-monospace, monospace;
		font-size: 0.95rem;
		overflow-wrap: anywhere;
		user-select: all;
	}
	.slug {
		font-weight: 700;
	}
	.domain {
		color: var(--text-muted);
	}
	.pending {
		margin: 0;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
	.counts {
		display: flex;
		flex-wrap: wrap;
		gap: 2px 12px;
		margin: 0;
	}
	.actions {
		gap: 6px;
	}
	.actions .spacer {
		flex: 1;
	}
</style>
