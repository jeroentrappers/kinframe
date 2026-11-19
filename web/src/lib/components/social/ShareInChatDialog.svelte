<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call } from '$lib/api/client';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { chats } from '$lib/social.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ConversationAvatar from '../chat/ConversationAvatar.svelte';

	let { open = $bindable(false), postId, preferFeedId }: { open?: boolean; postId: string; preferFeedId?: string } = $props();

	let chosen = $state<string | null>(null);
	let note = $state('');
	let sending = $state(false);
	const list = $derived(chats.list.filter((c) => !c.read_only));

	$effect(() => {
		if (!open) return;
		void chats.load();
	});
	$effect(() => {
		if (chosen || !preferFeedId) return;
		const fam = list.find((c) => c.kind === 'family' && c.feed_id === preferFeedId);
		if (fam) chosen = fam.id;
	});

	async function share() {
		if (!chosen) return;
		sending = true;
		const clientId = newIdempotencyKey();
		try {
			await call(
				api.POST('/v1/conversations/{conversationId}/messages', {
					params: { path: { conversationId: chosen }, header: { 'Idempotency-Key': clientId } },
					body: { client_id: clientId, shared_post_id: postId, ...(note.trim() ? { body: note.trim() } : {}) }
				})
			);
			open = false;
			const id = chosen;
			toasts.show(t('social.sharedInChat', { name: chats.get(id)?.name ?? '' }), { action: { label: t('chat.open'), run: () => goto(`/chats/${id}`) } });
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			sending = false;
		}
	}
</script>

<Dialog bind:open title={t('social.shareInChat')}>
	{#if !chats.loaded}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:48px"></div>
			<div class="skeleton" style="height:48px"></div>
		</div>
	{:else if list.length === 0}
		<p class="muted">{t('chat.noChats')}</p>
	{:else}
		<fieldset class="pick">
			<legend class="visually-hidden">{t('social.pickChat')}</legend>
			{#each list as c (c.id)}
				<label class="opt" class:checked={chosen === c.id}>
					<input type="radio" name="share-chat" value={c.id} bind:group={chosen} />
					<ConversationAvatar conversation={c} size={36} />
					<span class="nm">{c.name}</span>
					<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
				</label>
			{/each}
		</fieldset>
		<div class="field">
			<label for="share-note">{t('social.shareNote')} <span class="faint">({t('common.optional')})</span></label>
			<input id="share-note" class="input" maxlength="500" bind:value={note} placeholder={t('social.shareNotePlaceholder')} />
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" disabled={!chosen || sending} onclick={share}>
			{#if sending}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="send" size={18} />{/if}{t('social.share')}
		</button>
	{/snippet}
</Dialog>

<style>
	.pick {
		border: 0;
		margin: 0 0 var(--s-4);
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
		max-height: 320px;
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
	.nm {
		flex: 1;
		font-weight: 600;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.check {
		color: var(--primary);
		visibility: hidden;
	}
	.opt.checked .check {
		visibility: visible;
	}
</style>
