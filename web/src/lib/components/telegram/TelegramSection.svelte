<script lang="ts">
	import { api, call, type TelegramChat } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { upsertById, type ChannelTarget } from '$lib/emailInbox';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { chatBelongsTo, isLive, sortChats, type CodeKind } from '$lib/telegram';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import TelegramChatCard from './TelegramChatCard.svelte';
	import TelegramConnectDialog from './TelegramConnectDialog.svelte';

	/**
	 * The "Telegram" section of a frame's or feed's settings (telegram.md §3): connected chats and the three ways
	 * to connect one. `focus` scrolls to and highlights a chat (from the `telegram_attention` push).
	 */
	let { target, focus = null }: { target: ChannelTarget; focus?: string | null } = $props();

	let list = $state<TelegramChat[]>([]);
	let loading = $state(true);
	let error = $state<unknown>(null);
	let connectOpen = $state(false);
	let kind = $state<CodeKind>('group');

	const sorted = $derived(sortChats(list));
	const liveChats = $derived(sorted.filter(isLive));
	const earlier = $derived(sorted.filter((c) => !isLive(c)));
	const full = $derived(liveChats.length >= discovery.limits.max_telegram_chats_per_target);

	async function load() {
		loading = true;
		error = null;
		try {
			list =
				target.kind === 'frame'
					? await call(api.GET('/v1/frames/{frameId}/telegram-chats', { params: { path: { frameId: target.id } } }))
					: await call(api.GET('/v1/feeds/{feedId}/telegram-chats', { params: { path: { feedId: target.id } } }));
		} catch (e) {
			error = e;
			list = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void target.id;
		void load();
		return live.on((e) => {
			if (e.type === 'telegram_chat.updated' && chatBelongsTo(e.chat, target)) list = upsertById(list, e.chat);
			else if (e.type === 'telegram_chat.removed') list = list.filter((c) => c.id !== e.chat_id);
		});
	});

	$effect(() => {
		if (!focus || loading) return;
		document.getElementById(`tg-${focus}`)?.scrollIntoView({ block: 'center' });
	});

	function connect(k: CodeKind) {
		kind = k;
		connectOpen = true;
	}

	async function connected(chatId: string) {
		try {
			const c = await call(api.GET('/v1/telegram-chats/{chatId}', { params: { path: { chatId } } }));
			list = upsertById(list, c);
		} catch {
			void load();
		}
	}
</script>

<section class="card card-pad stack telegram" id="telegram" aria-labelledby="tg-title" data-testid="telegram-section">
	<div class="section-title">
		<h2 id="tg-title"><Icon name="plane" size={18} />Telegram</h2>
	</div>
	<p class="muted small intro">{target.kind === 'feed' ? t('telegram.introFeed', { name: target.name }) : t('telegram.introFrame', { name: target.name })}</p>

	{#if loading && list.length === 0}
		<div class="skeleton" style="height:96px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert">
			<Icon name="alert" size={18} />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
		</div>
	{:else}
		{#if liveChats.length > 0}
			<div class="stack-sm">
				{#each liveChats as c (c.id)}
					<TelegramChatCard chat={c} {target} highlight={focus === c.id} onupdated={(x) => (list = upsertById(list, x))} onremoved={(id) => (list = list.filter((x) => x.id !== id))} />
				{/each}
			</div>
		{/if}

		<div class="connect">
			<button class="btn btn-primary" onclick={() => connect('group')} disabled={full}><Icon name="users" size={18} />{t('telegram.connectGroup')}</button>
			<button class="btn" onclick={() => connect('private_self')} disabled={full}><Icon name="plane" size={18} />{t('telegram.sendFromMine')}</button>
			<button class="btn btn-ghost" onclick={() => connect('private_invite')} disabled={full}><Icon name="share" size={18} />{t('telegram.inviteSomeone')}</button>
		</div>
		{#if full}<p class="faint tiny">{t('telegram.limitReached', { max: discovery.limits.max_telegram_chats_per_target })}</p>{/if}

		{#if earlier.length > 0}
			<details class="earlier">
				<summary class="small">{t('telegram.earlier', { count: earlier.length })}</summary>
				<div class="stack-sm">
					{#each earlier as c (c.id)}
						<TelegramChatCard chat={c} {target} onupdated={(x) => (list = upsertById(list, x))} onremoved={(id) => (list = list.filter((x) => x.id !== id))} />
					{/each}
				</div>
			</details>
		{/if}
	{/if}
</section>

<TelegramConnectDialog bind:open={connectOpen} {target} {kind} onconnected={connected} />

<style>
	.telegram {
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
	.connect {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-2);
	}
	.earlier summary {
		cursor: pointer;
		color: var(--text-muted);
		margin-bottom: var(--s-2);
	}
</style>
