<script lang="ts" module>
	import type { FeedInvite } from '$lib/api/client';
	// One open "Invite family" link per family per visit: opening the sheet again shows the same one.
	const made = new Map<string, FeedInvite>();
</script>

<script lang="ts">
	import { api, call, type FeedSummary } from '$lib/api/client';
	import { formatFeedCode } from '$lib/codes';
	import { canInviteToFeed, inviteBody } from '$lib/feeds';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import QrCode from '../QrCode.svelte';

	/** "Invite family" (simple-app.md §3.1, §3.4): a link anyone can use for a week, to share right away. */
	let { open = $bindable(false), feed }: { open?: boolean; feed: Pick<FeedSummary, 'id' | 'name' | 'role'> & { posters_can_invite?: boolean } } = $props();

	let invite = $state<FeedInvite | null>(null);
	let error = $state<string | null>(null);
	let busy = $state(false);
	const allowed = $derived(canInviteToFeed(feed));

	$effect(() => {
		if (!open || !allowed) return;
		const id = feed.id;
		invite = made.get(id) ?? null;
		if (invite) return;
		busy = true;
		error = null;
		call(
			api.POST('/v1/feeds/{feedId}/invites', {
				params: { path: { feedId: id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
				body: inviteBody({ purpose: 'join', expiresInHours: 168, uses: 'unlimited', note: '' })
			})
		)
			.then((inv) => {
				made.set(id, inv);
				if (feed.id === id) invite = inv;
			})
			.catch((e) => (error = problemMessage(e)))
			.finally(() => (busy = false));
	});

	async function copy(text: string) {
		try {
			await navigator.clipboard.writeText(text);
			toasts.success(t('people.copied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}

	async function share(inv: FeedInvite) {
		const text = t('feedInvites.shareTextJoin', { feed: feed.name, code: formatFeedCode(inv.code) });
		if (navigator.share) {
			try {
				await navigator.share({ title: feed.name, text, url: inv.url });
			} catch {
				/* cancelled */
			}
		} else void copy(`${text}\n${inv.url}`);
	}
</script>

<Dialog bind:open title={t('family.inviteTitle', { family: feed.name })}>
	{#if !allowed}
		<p class="muted">{t('family.inviteAskAdmin')}</p>
	{:else if error}
		<p class="error-text" role="alert">{error}</p>
	{:else if busy || !invite}
		<div class="skeleton" style="height:180px" aria-busy="true" aria-label={t('common.loading')}></div>
	{:else}
		<div class="stack invite">
			<p class="muted small">{t('family.inviteBody')}</p>
			<div class="qr"><QrCode value={invite.url} size={168} label={t('feedInvites.qrLabel', { feed: feed.name })} /></div>
			<div class="center">
				<span class="hint">{t('feedInvites.code')}</span>
				<p class="code num" data-testid="invite-code">{formatFeedCode(invite.code)}</p>
			</div>
			<div class="link-box"><code>{invite.url}</code></div>
		</div>
	{/if}
	{#snippet footer()}
		{#if allowed && invite}
			<button class="btn" onclick={() => copy(invite!.url)}><Icon name="copy" size={16} />{t('people.copy')}</button>
			<button class="btn btn-primary" onclick={() => share(invite!)}><Icon name="share" size={16} />{t('people.share')}</button>
		{:else}
			<button class="btn btn-primary" onclick={() => (open = false)}>{t('common.done')}</button>
		{/if}
	{/snippet}
</Dialog>

<style>
	.qr,
	.center {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
	}
	.code {
		font-size: 1.5rem;
		font-weight: 800;
		letter-spacing: 0.08em;
		margin: 0;
	}
	.link-box {
		padding: 8px 12px;
		border-radius: var(--r);
		background: var(--surface-2);
		overflow-wrap: anywhere;
		font-size: 0.85rem;
	}
</style>
