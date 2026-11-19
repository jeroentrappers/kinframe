<script lang="ts">
	import { api, call, type EmailAddress } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { addressBelongsTo, sortAddresses, upsertById, type ChannelTarget } from '$lib/emailInbox';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import EmailAddressCard from './EmailAddressCard.svelte';
	import EmailCreateDialog from './EmailCreateDialog.svelte';

	/**
	 * "Email photos to this frame / feed" (email-inbox.md §3): the target's inbox addresses for its managers.
	 * `focus` opens one address's pending list (from the `email_pending_sender` push).
	 */
	let { target, focus = null }: { target: ChannelTarget; focus?: string | null } = $props();

	let list = $state<EmailAddress[]>([]);
	let loading = $state(true);
	let error = $state<unknown>(null);
	let createOpen = $state(false);
	/** Bumped per address when its senders change, so an open pending list reloads. */
	let ticks = $state<Record<string, number>>({});

	const full = $derived(list.length >= discovery.limits.max_email_addresses_per_target);
	const sorted = $derived(sortAddresses(list));
	const noReplies = $derived(list.some((a) => a.replies_available === false));

	async function load() {
		loading = true;
		error = null;
		try {
			list =
				target.kind === 'frame'
					? await call(api.GET('/v1/frames/{frameId}/email-addresses', { params: { path: { frameId: target.id } } }))
					: await call(api.GET('/v1/feeds/{feedId}/email-addresses', { params: { path: { feedId: target.id } } }));
		} catch (e) {
			error = e;
			list = [];
		} finally {
			loading = false;
		}
	}

	async function refresh(id: string) {
		try {
			const a = await call(api.GET('/v1/email-addresses/{addressId}', { params: { path: { addressId: id } } }));
			list = upsertById(list, a);
		} catch {
			/* gone or no longer ours: the removed event follows */
		}
	}

	$effect(() => {
		void target.id;
		void load();
		return live.on((e) => {
			if (e.type === 'email_address.updated' && addressBelongsTo(e.address, target)) list = upsertById(list, e.address);
			else if (e.type === 'email_address.removed') list = list.filter((a) => a.id !== e.address_id);
			else if ((e.type === 'email_sender.pending' || e.type === 'email_sender.decided') && list.some((a) => a.id === e.address_id)) {
				ticks = { ...ticks, [e.address_id]: (ticks[e.address_id] ?? 0) + 1 };
				void refresh(e.address_id);
			}
		});
	});
</script>

<section class="card card-pad stack inbox" id="email" aria-labelledby="email-title" data-testid="email-inbox">
	<div class="section-title">
		<h2 id="email-title"><Icon name="mail" size={18} />{target.kind === 'feed' ? t('email.titleFeed') : t('email.titleFrame')}</h2>
		{#if list.length > 0}
			<button class="btn btn-sm" onclick={() => (createOpen = true)} disabled={full} title={full ? t('email.limitReached', { max: discovery.limits.max_email_addresses_per_target }) : undefined}>
				<Icon name="plus" size={16} />{t('email.addAnother')}
			</button>
		{/if}
	</div>
	<p class="muted small intro">{target.kind === 'feed' ? t('email.introFeed', { name: target.name }) : t('email.introFrame')}</p>

	{#if loading && list.length === 0}
		<div class="skeleton" style="height:120px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert">
			<Icon name="alert" size={18} />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
		</div>
	{:else if list.length === 0}
		<div><button class="btn btn-primary" onclick={() => (createOpen = true)}><Icon name="mail" size={18} />{t('email.create')}</button></div>
	{:else}
		{#if noReplies}<p class="callout small"><Icon name="info" size={18} />{t('email.noReplies')}</p>{/if}
		<div class="stack-sm">
			{#each sorted as a (a.id)}
				<EmailAddressCard
					address={a}
					{target}
					tick={ticks[a.id] ?? 0}
					openPending={focus === a.id}
					onupdated={(x) => (list = upsertById(list, x))}
					onremoved={(id) => (list = list.filter((x) => x.id !== id))}
				/>
			{/each}
		</div>
	{/if}
</section>

<EmailCreateDialog bind:open={createOpen} {target} oncreated={(a) => (list = upsertById(list, a))} />

<style>
	.inbox {
		margin-top: var(--s-5);
	}
	h2 {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.intro {
		margin: 0;
	}
</style>
