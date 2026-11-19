<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Conversation, type Person } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { matchPeople } from '$lib/social';
	import { chats } from '$lib/social.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import OperatorNote from './OperatorNote.svelte';

	/**
	 * Start a chat with contacts (people you share a family or a frame with; decision C), or add them to a group
	 * (`addTo`).
	 */
	let { open = $bindable(false), addTo = null, onadded }: { open?: boolean; addTo?: Conversation | null; onadded?: (c: Conversation) => void } = $props();

	let contacts = $state<Person[] | null>(null);
	let error = $state<unknown>(null);
	let picked = $state<string[]>([]);
	let filter = $state('');
	let name = $state('');
	let busy = $state(false);

	const already = $derived(new Set((addTo?.members ?? []).map((m) => m.person.user_id)));
	const available = $derived((contacts ?? []).filter((c) => !c.blocked && !already.has(c.person.user_id)));
	const shown = $derived(
		filter.trim()
			? matchPeople(
					available.map((c) => ({ ...c, display_name: c.person.display_name })),
					filter,
					100
				)
			: available
	);
	const limit = $derived(discovery.limits.max_group_members - (addTo ? addTo.member_count : 1));
	const isGroup = $derived(!addTo && picked.length > 1);

	$effect(() => {
		if (!open) return;
		picked = [];
		filter = '';
		name = '';
		contacts = null;
		error = null;
		call(api.GET('/v1/contacts'))
			.then((c) => (contacts = c))
			.catch((e) => (error = e));
	});

	function toggle(id: string) {
		if (picked.includes(id)) picked = picked.filter((x) => x !== id);
		else if (picked.length < limit) picked = [...picked, id];
	}

	async function submit() {
		busy = true;
		try {
			if (addTo) {
				const c = await call(api.POST('/v1/conversations/{conversationId}/members', { params: { path: { conversationId: addTo.id } }, body: { user_ids: picked } }));
				chats.upsert(c);
				onadded?.(c);
				toasts.success(tn('chat.added', picked.length));
				open = false;
				return;
			}
			const c = await call(
				api.POST('/v1/conversations', {
					params: { header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: isGroup ? { kind: 'group', member_ids: picked, name: name.trim() } : { kind: 'direct', member_ids: picked }
				})
			);
			chats.upsert(c);
			open = false;
			await goto(`/chats/${c.id}`);
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={addTo ? t('chat.addPeople') : t('chat.newChat')}>
	<div class="stack">
		{#if !addTo}<p class="muted small intro">{t('chat.contactsHint')}</p>{/if}
		{#if error}
			<p class="callout callout-error" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>
		{:else if contacts === null}
			<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
				<div class="skeleton" style="height:44px"></div>
				<div class="skeleton" style="height:44px"></div>
			</div>
		{:else if available.length === 0}
			<p class="muted">{addTo ? t('chat.noOneToAdd') : t('chat.noContacts')}</p>
		{:else}
			{#if available.length > 6}
				<input class="input" type="search" bind:value={filter} placeholder={t('chat.searchContacts')} aria-label={t('chat.searchContacts')} />
			{/if}
			<ul class="people" role="list" aria-label={t('chat.contacts')}>
				{#each shown as c (c.person.user_id)}
					<li>
						<label class="opt" class:checked={picked.includes(c.person.user_id)}>
							<input type="checkbox" checked={picked.includes(c.person.user_id)} onchange={() => toggle(c.person.user_id)} />
							<Avatar name={c.person.display_name} url={c.person.avatar_url} size={36} />
							<span class="body">
								<span class="nm">{c.person.display_name}</span>
								<span class="faint tiny">{[...c.feeds.map((f) => f.feed_name), ...c.frames.map((f) => f.frame_name)].slice(0, 3).join(' · ')}</span>
							</span>
							<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
						</label>
					</li>
				{/each}
			</ul>
			{#if isGroup}
				<div class="field">
					<label for="group-name">{t('chat.groupName')}</label>
					<input id="group-name" class="input" maxlength="60" required bind:value={name} placeholder={t('chat.groupNamePlaceholder')} />
				</div>
			{/if}
		{/if}
		{#if !addTo}<OperatorNote compact />{/if}
	</div>
	{#snippet footer()}
		<button class="btn" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" disabled={busy || picked.length === 0 || (isGroup && !name.trim())} onclick={submit}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}
			{addTo ? t('chat.add') : isGroup ? t('chat.createGroup') : t('chat.startChat')}
		</button>
	{/snippet}
</Dialog>

<style>
	.intro {
		margin: 0;
	}
	.people {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
		max-height: 340px;
		overflow-y: auto;
	}
	.opt {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 6px 10px;
		border-radius: var(--r);
		border: 1px solid transparent;
		cursor: pointer;
	}
	.opt:hover {
		background: var(--surface-2);
	}
	.opt.checked {
		border-color: var(--primary);
		background: var(--primary-soft);
	}
	.opt input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.opt:has(input:focus-visible) {
		outline: 3px solid var(--focus);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.nm {
		font-weight: 600;
	}
	.check {
		color: var(--primary);
		visibility: hidden;
	}
	.opt.checked .check {
		visibility: visible;
	}
</style>
