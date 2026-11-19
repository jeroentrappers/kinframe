<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Person } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { MILESTONE_EMOJI, MILESTONE_ORDER } from '$lib/engagement';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { chats } from '$lib/social.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Avatar from '$components/Avatar.svelte';
	import Icon from '$components/Icon.svelte';

	const id = $derived(page.params.id!);
	let person = $state<Person | null>(null);
	let error = $state<unknown>(null);
	let busy = $state(false);
	const self = $derived(id === auth.me?.id);

	$effect(() => {
		const userId = id;
		person = null;
		error = null;
		call(api.GET('/v1/people/{userId}', { params: { path: { userId } } }))
			.then((p) => (person = p))
			.catch((e) => (error = e));
	});

	async function message() {
		if (!person) return;
		if (person.direct_conversation_id) return goto(`/chats/${person.direct_conversation_id}`);
		busy = true;
		try {
			const c = await call(api.POST('/v1/conversations', { params: { header: { 'Idempotency-Key': newIdempotencyKey() } }, body: { kind: 'direct', member_ids: [person.person.user_id] } }));
			chats.upsert(c);
			await goto(`/chats/${c.id}`);
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	async function setBlocked(blocked: boolean) {
		if (!person) return;
		busy = true;
		try {
			if (blocked) await call(api.PUT('/v1/blocks/{userId}', { params: { path: { userId: person.person.user_id } } }));
			else await call(api.DELETE('/v1/blocks/{userId}', { params: { path: { userId: person.person.user_id } } }));
			person = { ...person, blocked };
			toasts.success(blocked ? t('chat.blocked', { name: person.person.display_name }) : t('chat.unblocked', { name: person.person.display_name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	const milestones = $derived(MILESTONE_ORDER.filter((k) => person?.milestones?.includes(k)));
</script>

<svelte:head><title>{person ? personName(person.person, t('social.formerMember')) : t('chat.profile')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<button class="back" onclick={() => history.back()}><Icon name="chevronLeft" size={18} />{t('chat.back')}</button>
	{#if error}
		<div class="callout callout-error" role="alert"><Icon name="alert" /><span>{isCode(error, 'not_found') ? t('chat.personNotFound') : problemMessage(error)}</span></div>
	{:else if !person}
		<div class="skeleton" style="height:180px"></div>
	{:else}
		<section class="card card-pad profile">
			<Avatar name={personName(person.person, t('social.formerMember'))} url={person.person.former_member ? null : person.person.avatar_url} size={96} />
			<h1>{personName(person.person, t('social.formerMember'))}{#if self}<span class="faint you"> ({t('people.you')})</span>{/if}</h1>
			{#if !self && !person.person.former_member && discovery.chats}
				<div class="row-wrap center">
					{#if !person.blocked}
						<button class="btn btn-primary" onclick={message} disabled={busy}><Icon name="message" size={18} />{t('chat.message')}</button>
					{/if}
					<button class="btn {person.blocked ? '' : 'btn-danger-ghost'}" onclick={() => setBlocked(!person!.blocked)} disabled={busy}>
						<Icon name="ban" size={16} />{person.blocked ? t('chat.unblock') : t('chat.blockShort')}
					</button>
				</div>
				{#if person.blocked}<p class="small muted">{t('chat.blockedHint')}</p>{/if}
			{/if}
		</section>

		{#if milestones.length}
			<section class="card card-pad stack-sm" aria-labelledby="p-ms">
				<h2 id="p-ms">{t('engagement.milestones')}</h2>
				<ul class="ms" role="list">
					{#each milestones as k (k)}<li><span aria-hidden="true">{MILESTONE_EMOJI[k]}</span>{t(`engagement.milestone.${k}`)}</li>{/each}
				</ul>
				{#if self}<a class="small" href="/settings#milestones">{t('engagement.milestonesManage')}</a>{/if}
			</section>
		{/if}

		{#if person.feeds.length || person.frames.length}
			<section class="card card-pad stack-sm" aria-labelledby="p-shared">
				<h2 id="p-shared">{self ? t('chat.yourPlaces') : t('chat.sharedPlaces')}</h2>
				<ul class="shared" role="list">
					{#each person.feeds as f (f.feed_id)}<li><Icon name="users" size={16} /><a href="/feeds/{f.feed_id}">{f.feed_name}</a></li>{/each}
					{#each person.frames as f (f.frame_id)}<li><Icon name="frame" size={16} /><a href="/frames/{f.frame_id}">{f.frame_name}</a></li>{/each}
				</ul>
			</section>
		{/if}
	{/if}
</div>

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		background: none;
		border: 0;
		font: inherit;
		font-weight: 600;
		margin-bottom: var(--s-4);
		cursor: pointer;
		padding: 0;
	}
	.profile {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-3);
		text-align: center;
		margin-bottom: var(--s-4);
	}
	.profile p {
		margin: 0;
	}
	h1 {
		font-size: 1.5rem;
	}
	h2 {
		font-size: 1.05rem;
	}
	.center {
		justify-content: center;
	}
	.ms,
	.shared {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.ms li,
	.shared li {
		display: flex;
		align-items: center;
		gap: var(--s-2);
	}
	section + section {
		margin-top: var(--s-4);
	}
</style>
