<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EmailAddress } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { addressPreview, cleanSlugInput, isValidSlug, SLUG_MAX, slugify, type ChannelTarget } from '$lib/emailInbox';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';

	let {
		open = $bindable(false),
		target,
		oncreated
	}: { open?: boolean; target: ChannelTarget; oncreated: (a: EmailAddress) => void } = $props();

	const uid = $props.id();
	let slug = $state('');
	let name = $state('');
	let busy = $state(false);
	let slugError = $state<string | null>(null);

	$effect(() => {
		if (!open) return;
		untrack(() => {
			slug = slugify(target.name) || 'family';
			name = target.name;
			slugError = null;
		});
	});

	const valid = $derived(isValidSlug(slug.replace(/-+$/, '')));

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		const s = slug.replace(/-+$/, '');
		if (!isValidSlug(s)) return;
		busy = true;
		slugError = null;
		try {
			const body = { slug: s, name: name.trim() || undefined };
			const a =
				target.kind === 'frame'
					? await call(api.POST('/v1/frames/{frameId}/email-addresses', { params: { path: { frameId: target.id }, header: { 'Idempotency-Key': crypto.randomUUID() } }, body }))
					: await call(api.POST('/v1/feeds/{feedId}/email-addresses', { params: { path: { feedId: target.id }, header: { 'Idempotency-Key': crypto.randomUUID() } }, body }));
			oncreated(a);
			open = false;
			toasts.success(t('email.created'));
		} catch (err) {
			if (isCode(err, 'validation_failed')) slugError = t('email.slugInvalid');
			else if (isCode(err, 'limit_reached')) toasts.error(t('email.limitReached', { max: discovery.limits.max_email_addresses_per_target }));
			else toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={t('email.createTitle')}>
	<form id="{uid}-form" class="stack" onsubmit={submit}>
		<p class="muted small">{target.kind === 'feed' ? t('email.introFeed', { name: target.name }) : t('email.introFrame')}</p>
		<div class="field">
			<label for="{uid}-slug">{t('email.slugLabel')}</label>
			<input
				id="{uid}-slug"
				class="input mono"
				value={slug}
				oninput={(e) => (slug = cleanSlugInput(e.currentTarget.value))}
				maxlength={SLUG_MAX}
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
				aria-describedby="{uid}-preview"
				aria-invalid={!valid || !!slugError}
				required
			/>
			<span class="hint" id="{uid}-preview">{t('email.slugPreview', { address: addressPreview(slug, discovery.emailInboxDomain) })}</span>
			{#if slugError}<span class="error-text">{slugError}</span>{/if}
		</div>
		<div class="field">
			<label for="{uid}-name">{t('email.nameLabel')}</label>
			<input id="{uid}-name" class="input" bind:value={name} maxlength="60" />
			<span class="hint">{t('email.nameHint')}</span>
		</div>
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={busy || !valid}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('email.createButton')}
		</button>
	{/snippet}
</Dialog>

<style>
	.mono {
		font-family: ui-monospace, monospace;
	}
</style>
