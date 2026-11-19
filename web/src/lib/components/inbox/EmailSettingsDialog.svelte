<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress, type EmailAddressUpdate } from '$lib/api/client';
	import { cleanSlugInput, isValidSlug, SLUG_MAX } from '$lib/emailInbox';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let { open = $bindable(false), address, onsaved }: { open?: boolean; address: EmailAddress; onsaved: (a: EmailAddress) => void } = $props();

	const uid = $props.id();
	type Form = Required<Pick<EmailAddressUpdate, 'name' | 'slug' | 'anyone_may_send' | 'send_confirmations' | 'offer_app_invite' | 'notify_reactions'>>;
	const fromAddress = (a: EmailAddress): Form => ({
		name: a.name,
		slug: a.slug,
		anyone_may_send: a.anyone_may_send,
		send_confirmations: a.send_confirmations,
		offer_app_invite: a.offer_app_invite,
		notify_reactions: a.notify_reactions
	});
	const BLANK: Form = { name: '', slug: '', anyone_may_send: false, send_confirmations: true, offer_app_invite: true, notify_reactions: true };
	let form = $state<Form>({ ...BLANK });
	let base = $state<Form>({ ...BLANK });
	let busy = $state(false);
	let slugError = $state<string | null>(null);

	$effect(() => {
		if (!open) return;
		untrack(() => {
			base = fromAddress(address);
			form = { ...base };
			slugError = null;
		});
	});

	const patch = $derived.by(() => {
		const out: EmailAddressUpdate = {};
		const name = form.name.trim();
		if (name && name !== base.name) out.name = name;
		const slug = form.slug.replace(/-+$/, '');
		if (slug !== base.slug) out.slug = slug;
		for (const k of ['anyone_may_send', 'send_confirmations', 'offer_app_invite', 'notify_reactions'] as const) if (form[k] !== base[k]) out[k] = form[k];
		if (!address.is_owner) delete out.notify_reactions;
		return out;
	});
	const slugOk = $derived(isValidSlug(form.slug.replace(/-+$/, '')));
	const dirty = $derived(Object.keys(patch).length > 0);

	async function save(e: SubmitEvent) {
		e.preventDefault();
		if (!dirty || !slugOk) return;
		busy = true;
		slugError = null;
		try {
			const a = await call(api.PATCH('/v1/email-addresses/{addressId}', { params: { path: { addressId: address.id } }, body: patch }));
			onsaved(a);
			open = false;
			toasts.success(patch.slug ? t('email.slugChanged', { address: a.address }) : t('common.saved'));
		} catch (err) {
			if (isCode(err, 'validation_failed')) slugError = t('email.slugInvalid');
			else toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={t('email.settingsTitle', { name: address.name })} size="md">
	<form id="{uid}-form" class="stack" onsubmit={save}>
		<div class="field">
			<label for="{uid}-name">{t('email.nameLabel')}</label>
			<input id="{uid}-name" class="input" bind:value={form.name} maxlength="60" required />
			<span class="hint">{t('email.nameHint')}</span>
		</div>
		<div class="field">
			<label for="{uid}-slug">{t('email.slugLabel')}</label>
			<input
				id="{uid}-slug"
				class="input mono"
				value={form.slug}
				oninput={(e) => (form.slug = cleanSlugInput(e.currentTarget.value))}
				maxlength={SLUG_MAX}
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
				aria-invalid={!slugOk || !!slugError}
			/>
			{#if patch.slug}<span class="callout callout-warn small"><Icon name="alert" size={16} />{t('email.slugWarning')}</span>{/if}
			{#if slugError}<span class="error-text">{slugError}</span>{/if}
		</div>
		<label class="switch">
			<input type="checkbox" bind:checked={form.anyone_may_send} />
			<span><span class="s-label">{t('email.anyoneMaySend')}</span><span class="hint">{t('email.anyoneMaySendHint')}</span></span>
		</label>
		<label class="switch">
			<input type="checkbox" bind:checked={form.send_confirmations} disabled={address.replies_available === false} />
			<span><span class="s-label">{t('email.confirmations')}</span><span class="hint">{address.replies_available === false ? t('email.noReplies') : t('email.confirmationsHint')}</span></span>
		</label>
		<label class="switch">
			<input type="checkbox" bind:checked={form.offer_app_invite} disabled={address.replies_available === false} />
			<span><span class="s-label">{t('email.offerInvite')}</span><span class="hint">{t('email.offerInviteHint')}</span></span>
		</label>
		{#if address.is_owner}
			<label class="switch">
				<input type="checkbox" bind:checked={form.notify_reactions} />
				<span><span class="s-label">{t('email.notifyReactions')}</span><span class="hint">{t('email.notifyReactionsHint')}</span></span>
			</label>
		{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={busy || !dirty || !slugOk}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
	{/snippet}
</Dialog>

<style>
	.mono {
		font-family: ui-monospace, monospace;
	}
	.s-label {
		display: block;
		font-weight: 600;
	}
	.hint {
		display: block;
	}
</style>
