<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress, type EmailHeldMessage, type EmailPendingSender } from '$lib/api/client';
	import { authText, canApprove, expiresText, heldMediaCount, heldWarning, pendingName, skipText, upsertById, type ChannelTarget } from '$lib/emailInbox';
	import { formatDateTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let {
		open = $bindable(false),
		address,
		target,
		tick = 0,
		onchanged
	}: { open?: boolean; address: EmailAddress; target: ChannelTarget; tick?: number; onchanged?: () => void } = $props();

	let list = $state<EmailPendingSender[]>([]);
	let loading = $state(false);
	let error = $state<unknown>(null);
	let busy = $state<string | null>(null);
	/** One held mail, opened from the list. */
	let viewing = $state<EmailHeldMessage | null>(null);
	let viewingSender = $state<EmailPendingSender | null>(null);

	async function load() {
		loading = true;
		error = null;
		try {
			list = await call(api.GET('/v1/email-addresses/{addressId}/pending-senders', { params: { path: { addressId: address.id } } }));
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (!open) return;
		void tick;
		untrack(() => void load());
	});

	$effect(() => {
		if (!open) {
			viewing = null;
			viewingSender = null;
			return;
		}
		return live.on((e) => {
			if (e.type === 'email_sender.pending' && e.address_id === address.id) list = upsertById(list, e.pending);
			else if (e.type === 'email_sender.decided' && e.address_id === address.id) list = list.filter((p) => p.id !== e.sender_id);
		});
	});

	function decided(id: string, message: string) {
		list = list.filter((p) => p.id !== id);
		if (viewingSender?.id === id) {
			viewing = null;
			viewingSender = null;
		}
		toasts.success(message);
		onchanged?.();
	}

	async function act(key: string, p: () => Promise<unknown>, onDone: () => void) {
		busy = key;
		try {
			await p();
			onDone();
		} catch (e) {
			if (isCode(e, 'already_decided', 'not_found')) {
				toasts.show(t('email.alreadyDecided'));
				void load();
				viewing = null;
			} else toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	const path = (senderId: string) => ({ params: { path: { addressId: address.id, senderId } } });

	function approve(p: EmailPendingSender) {
		void act(
			`approve:${p.id}`,
			() => call(api.POST('/v1/email-addresses/{addressId}/pending-senders/{senderId}/approve', { ...path(p.id), body: { deliver: true } })),
			() => decided(p.id, t('email.approved', { name: pendingName(p), target: target.name }))
		);
	}

	function reject(p: EmailPendingSender, block: boolean) {
		void act(
			`${block ? 'block' : 'reject'}:${p.id}`,
			() => call(api.POST('/v1/email-addresses/{addressId}/pending-senders/{senderId}/reject', { ...path(p.id), body: { block } })),
			() => decided(p.id, block ? t('email.blocked', { name: pendingName(p) }) : t('email.rejected', { name: pendingName(p) }))
		);
	}

	async function view(p: EmailPendingSender, messageId: string) {
		busy = `view:${messageId}`;
		try {
			viewing = await call(api.GET('/v1/email-addresses/{addressId}/held-messages/{messageId}', { params: { path: { addressId: address.id, messageId } } }));
			viewingSender = p;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	/** Drops one held mail from the list (and the sender when it was their last). */
	function dropMessage(messageId: string) {
		list = list.flatMap((p) => {
			const messages = p.messages.filter((m) => m.id !== messageId);
			return messages.length === p.messages.length ? [p] : messages.length ? [{ ...p, messages }] : [];
		});
		viewing = null;
		viewingSender = null;
		onchanged?.();
	}

	function deliverOne(messageId: string) {
		void act(
			`deliver:${messageId}`,
			() => call(api.POST('/v1/email-addresses/{addressId}/held-messages/{messageId}/deliver', { params: { path: { addressId: address.id, messageId } } })),
			() => {
				dropMessage(messageId);
				toasts.success(t('email.deliveredOne', { target: target.name }));
			}
		);
	}

	function deleteOne(messageId: string) {
		void act(
			`delete:${messageId}`,
			() => call(api.DELETE('/v1/email-addresses/{addressId}/held-messages/{messageId}', { params: { path: { addressId: address.id, messageId } } })),
			() => {
				dropMessage(messageId);
				toasts.success(t('email.heldDeleted'));
			}
		);
	}
</script>

<Dialog bind:open title={viewing ? (viewing.subject?.trim() || t('email.noSubject')) : t('email.pendingTitle', { name: address.name })} size="md">
	{#if viewing && viewingSender}
		{@const warn = heldWarning(viewing.reason, viewing.sender_email)}
		<div class="stack held" data-testid="email-held">
			<button class="btn btn-sm btn-ghost back" onclick={() => (viewing = null)}><Icon name="chevronLeft" size={16} />{t('email.backToList')}</button>
			<dl class="facts small">
				<div><dt>{t('email.from')}</dt><dd>{viewing.sender_name ? `${viewing.sender_name} · ${viewing.sender_email}` : viewing.sender_email}</dd></div>
				<div><dt>{t('email.received')}</dt><dd>{formatDateTime(viewing.received_at, i18n.tag)}</dd></div>
				{#if viewing.caption}<div><dt>{t('email.caption')}</dt><dd>“{viewing.caption}”</dd></div>{/if}
				<div><dt>{t('email.check')}</dt><dd>{authText(viewing.auth)}</dd></div>
			</dl>
			{#if warn}<p class="callout callout-warn small" role="alert"><Icon name="alert" size={18} />{warn}</p>{/if}
			<div class="media-grid">
				{#each viewing.media as m, i (i)}
					<figure class="tile">
						{#if m.thumb_url}<img src={m.thumb_url} alt={m.filename ?? ''} loading="lazy" />{:else}<span class="ph"><Icon name={m.kind === 'video' ? 'video' : 'image'} size={22} /></span>{/if}
						{#if m.kind === 'video'}<span class="kind"><Icon name="video" size={14} /></span>{/if}
					</figure>
				{/each}
			</div>
			{#if skipText(viewing.skipped)}<p class="muted small">{t('email.skippedLine', { list: skipText(viewing.skipped) })}</p>{/if}
			<p class="faint tiny">{expiresText(viewing.expires_at, i18n.tag)}</p>
			<div class="row-wrap">
				<button class="btn btn-sm btn-primary" onclick={() => deliverOne(viewing!.id)} disabled={busy !== null}>
					{#if busy === `deliver:${viewing.id}`}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="send" size={16} />{/if}{t('email.deliverOne')}
				</button>
				<button class="btn btn-sm btn-danger-ghost" onclick={() => deleteOne(viewing!.id)} disabled={busy !== null}><Icon name="trash" size={16} />{t('email.deleteOne')}</button>
			</div>
		</div>
	{:else if loading && list.length === 0}
		<div class="skeleton" style="height:140px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</div>
	{:else if list.length === 0}
		<p class="muted">{t('email.pendingEmpty')}</p>
	{:else}
		<ul class="pending stack" role="list">
			{#each list as p (p.id)}
				<li class="p-item stack-sm" data-testid="email-pending-sender">
					<div class="p-head">
						<div class="who">
							<strong>{pendingName(p)}</strong>
							{#if p.display_name}<span class="muted small mail">{p.email}</span>{/if}
						</div>
						{#if p.authenticated}
							<span class="badge ok"><Icon name="check" size={14} />{t('email.verified')}</span>
						{:else}
							<span class="badge warn"><Icon name="alert" size={14} />{t('email.notVerified')}</span>
						{/if}
					</div>
					<p class="muted small">{tn('email.wantsToSend', heldMediaCount(p), { target: target.name })}</p>
					{#if !p.authenticated}<p class="callout callout-warn small"><Icon name="alert" size={18} />{t('email.unverified', { email: p.email })}</p>{/if}
					<ul class="msgs" role="list">
						{#each p.messages as m (m.id)}
							<li class="msg">
								<div class="thumbs">
									{#each (m.thumb_urls ?? []).slice(0, 4) as u, i (i)}<img src={u} alt="" loading="lazy" />{/each}
								</div>
								<div class="m-text">
									<span class="subject">{m.subject?.trim() || t('email.noSubject')}</span>
									<span class="faint tiny">{formatDateTime(m.received_at, i18n.tag)} · {tn('email.mediaCount', m.media_count)} · {expiresText(m.expires_at, i18n.tag)}</span>
								</div>
								<button class="btn btn-sm btn-ghost" onclick={() => view(p, m.id)} disabled={busy !== null}>
									{#if busy === `view:${m.id}`}<span class="spinner" aria-hidden="true"></span>{/if}{t('email.view')}
								</button>
							</li>
						{/each}
					</ul>
					{#if canApprove(p)}
						<div class="row-wrap">
							<button class="btn btn-sm btn-primary" onclick={() => approve(p)} disabled={busy !== null}>
								{#if busy === `approve:${p.id}`}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="check" size={16} />{/if}{t('email.approve')}
							</button>
							<button class="btn btn-sm btn-ghost" onclick={() => reject(p, false)} disabled={busy !== null}>{t('email.reject')}</button>
							<button class="btn btn-sm btn-danger-ghost" onclick={() => reject(p, true)} disabled={busy !== null}><Icon name="x" size={16} />{t('email.block')}</button>
						</div>
					{:else}
						<p class="faint tiny">{t('email.unverifiedHint')}</p>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</Dialog>

<style>
	.pending,
	.msgs {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.p-item {
		padding-bottom: var(--s-4);
		border-bottom: 1px solid var(--border);
	}
	.p-item:last-child {
		border-bottom: 0;
		padding-bottom: 0;
	}
	.p-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: var(--s-3);
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.p-item p {
		margin: 0;
	}
	.badge {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		flex: none;
	}
	.badge.warn {
		background: var(--warn-soft);
		border-color: transparent;
	}
	.msgs {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.msg {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.thumbs {
		display: flex;
		gap: 3px;
		flex: none;
	}
	.thumbs img {
		width: 44px;
		height: 44px;
		object-fit: cover;
		border-radius: var(--r-sm);
		background: var(--border);
	}
	.m-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.subject {
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.back {
		align-self: flex-start;
	}
	.facts {
		display: grid;
		gap: 6px;
		margin: 0;
	}
	.facts div {
		display: grid;
		grid-template-columns: minmax(90px, auto) 1fr;
		gap: var(--s-3);
	}
	.facts dt {
		color: var(--text-muted);
	}
	.facts dd {
		margin: 0;
		overflow-wrap: anywhere;
	}
	.media-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
		gap: 6px;
	}
	.tile {
		position: relative;
		margin: 0;
		aspect-ratio: 1;
		border-radius: var(--r-sm);
		overflow: hidden;
		background: var(--border);
	}
	.tile img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.ph {
		display: grid;
		place-items: center;
		height: 100%;
		color: var(--text-muted);
	}
	.kind {
		position: absolute;
		inset-inline-end: 4px;
		bottom: 4px;
		color: #fff;
		filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.6));
	}
	.held p {
		margin: 0;
	}
</style>
