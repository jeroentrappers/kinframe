<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress, type EmailSender } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { looksLikeEmail, sortSenders, upsertById, type ChannelTarget } from '$lib/emailInbox';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let {
		open = $bindable(false),
		address,
		target,
		tick = 0,
		onchanged
	}: { open?: boolean; address: EmailAddress; target: ChannelTarget; tick?: number; onchanged?: () => void } = $props();

	const uid = $props.id();
	let list = $state<EmailSender[]>([]);
	let loading = $state(false);
	let error = $state<unknown>(null);
	let email = $state('');
	let name = $state('');
	let adding = $state(false);
	let busy = $state<string | null>(null);

	const shown = $derived(sortSenders(list.filter((s) => s.state !== 'pending')));
	const full = $derived(list.length >= discovery.limits.max_email_senders_per_address);

	async function load() {
		loading = true;
		error = null;
		try {
			list = await call(api.GET('/v1/email-addresses/{addressId}/senders', { params: { path: { addressId: address.id } } }));
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

	async function add(e: SubmitEvent) {
		e.preventDefault();
		if (!looksLikeEmail(email)) return;
		adding = true;
		try {
			const s = await call(
				api.POST('/v1/email-addresses/{addressId}/senders', {
					params: { path: { addressId: address.id } },
					body: { email: email.trim(), display_name: name.trim() || undefined }
				})
			);
			list = upsertById(list, s);
			toasts.success(t('email.senderAdded', { email: s.email }));
			email = '';
			name = '';
			onchanged?.();
		} catch (err) {
			if (isCode(err, 'duplicate_sender')) toasts.error(t('problem.duplicate_sender'));
			else if (isCode(err, 'validation_failed')) toasts.error(t('email.emailInvalid'));
			else toasts.error(problemMessage(err));
		} finally {
			adding = false;
		}
	}

	async function setBlocked(s: EmailSender, blocked: boolean) {
		busy = s.id;
		try {
			const next = await call(
				api.PATCH('/v1/email-addresses/{addressId}/senders/{senderId}', { params: { path: { addressId: address.id, senderId: s.id } }, body: { blocked } })
			);
			list = upsertById(list, next);
			toasts.success(blocked ? t('email.blocked', { name: s.display_name || s.email }) : t('email.unblocked', { name: s.display_name || s.email }));
			onchanged?.();
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = null;
		}
	}

	async function remove(s: EmailSender) {
		busy = s.id;
		try {
			await call(api.DELETE('/v1/email-addresses/{addressId}/senders/{senderId}', { params: { path: { addressId: address.id, senderId: s.id } } }));
			list = list.filter((x) => x.id !== s.id);
			toasts.success(t('email.senderRemoved', { name: s.display_name || s.email }));
			onchanged?.();
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = null;
		}
	}
</script>

<Dialog bind:open title={t('email.sendersTitle', { name: address.name })} size="md">
	<div class="stack">
		<p class="callout small"><Icon name="users" size={18} />{t('email.membersAllowed', { name: target.name })}</p>
		{#if address.anyone_may_send}<p class="callout callout-warn small"><Icon name="info" size={18} />{t('email.anyoneOn')}</p>{/if}

		<form class="add" onsubmit={add} aria-label={t('email.addSender')}>
			<div class="field">
				<label for="{uid}-email">{t('email.senderEmail')}</label>
				<input id="{uid}-email" class="input" type="email" bind:value={email} autocomplete="off" placeholder="jan.peeters@telenet.be" required />
			</div>
			<div class="field">
				<label for="{uid}-name">{t('email.senderName')} <span class="faint">({t('common.optional')})</span></label>
				<input id="{uid}-name" class="input" bind:value={name} maxlength="40" placeholder="Jan" />
			</div>
			<button class="btn btn-primary" type="submit" disabled={adding || full || !looksLikeEmail(email)}>
				{#if adding}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="plus" size={16} />{/if}{t('email.addSender')}
			</button>
		</form>

		{#if loading && list.length === 0}
			<div class="skeleton" style="height:80px"></div>
		{:else if error}
			<div class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</div>
		{:else if shown.length === 0}
			<p class="muted small">{t('email.sendersEmpty')}</p>
		{:else}
			<ul class="senders" role="list">
				{#each shown as s (s.id)}
					<li class="s-row" class:blocked={s.state === 'blocked'} data-testid="email-sender">
						<div class="who">
							<strong>{s.display_name || s.email}</strong>
							{#if s.display_name}<span class="muted small">{s.email}</span>{/if}
							{#if s.state === 'blocked'}<span class="badge">{t('email.blockedBadge')}</span>{/if}
						</div>
						<div class="row-wrap">
							{#if s.state === 'blocked'}
								<button class="btn btn-sm btn-ghost" onclick={() => setBlocked(s, false)} disabled={busy === s.id}>{t('email.unblock')}</button>
							{:else}
								<button class="btn btn-sm btn-ghost" onclick={() => setBlocked(s, true)} disabled={busy === s.id}>{t('email.block')}</button>
							{/if}
							<button class="btn btn-sm btn-danger-ghost btn-icon" onclick={() => remove(s)} disabled={busy === s.id} aria-label={t('email.removeSender', { name: s.display_name || s.email })}>
								<Icon name="trash" size={16} />
							</button>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	</div>
</Dialog>

<style>
	.add {
		display: grid;
		grid-template-columns: 1.4fr 1fr auto;
		gap: var(--s-3);
		align-items: end;
	}
	@media (max-width: 560px) {
		.add {
			grid-template-columns: 1fr;
		}
	}
	.callout {
		margin: 0;
	}
	.senders {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.s-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		padding: var(--s-2) 0;
		border-bottom: 1px solid var(--border);
	}
	.s-row:last-child {
		border-bottom: 0;
	}
	.s-row.blocked .who strong {
		color: var(--text-muted);
	}
	.who {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 4px 10px;
		min-width: 0;
		overflow-wrap: anywhere;
	}
</style>
