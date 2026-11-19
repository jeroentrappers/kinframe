<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Frame } from '$lib/api/client';
	import { historyBody, type HistoryChoice } from '$lib/feeds';
	import { can } from '$lib/frames.svelte';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { groupFollows, isOtherServer, parseInviteUrl, serverName, upsertFollow, type RemoteInvitePreview, type RemoteOnEnd, type RemoteSubscription } from '$lib/remote';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import HistoryPicker from '../feed/HistoryPicker.svelte';
	import ChoiceGroup from '../leaving/ChoiceGroup.svelte';

	/**
	 * A frame following families on other servers (multi-server.md §3.1, README §5.22): "Add a family from another
	 * server" (paste the invite link, preview, history, the on-end choice), the list, and unfollowing.
	 * `addUrl` opens the dialog with a link pasted elsewhere ("Use an invite").
	 */
	let { frame, addUrl = $bindable(null) }: { frame: Frame; addUrl?: string | null } = $props();

	const manage = $derived(can(frame, 'change_settings'));
	let follows = $state<RemoteSubscription[]>([]);
	let showEnded = $state(false);

	async function load() {
		try {
			follows = await call(api.GET('/v1/frames/{frameId}/remote-subscriptions', { params: { path: { frameId: frame.id } } }));
		} catch {
			follows = [];
		}
	}

	$effect(() => {
		void frame.id;
		void load();
		return live.on((e) => {
			if (e.type === 'remote_subscription.updated' && e.frame_id === frame.id) follows = upsertFollow(follows, e.remote_subscription);
		});
	});

	const groups = $derived(groupFollows(follows));

	// ---- add
	let addOpen = $state(false);
	let step = $state<'link' | 'confirm'>('link');
	let link = $state('');
	let linkError = $state<string | null>(null);
	let preview = $state<RemoteInvitePreview | null>(null);
	let history = $state<HistoryChoice>({ history: 'last_n', count: 50 });
	let onEnd = $state<RemoteOnEnd>('remove');
	let busy = $state(false);

	function startAdd(url = '') {
		step = 'link';
		link = url;
		linkError = null;
		preview = null;
		history = { history: 'last_n', count: 50 };
		onEnd = 'remove';
		addOpen = true;
		if (url) void check();
	}

	$effect(() => {
		if (addUrl) {
			const u = addUrl;
			addUrl = null;
			startAdd(u);
		}
	});

	function remoteError(e: unknown): string {
		if (isCode(e, 'forbidden')) return t('remote.refused');
		if (isCode(e, 'upstream_unavailable')) return t('remote.unreachable');
		if (isCode(e, 'feature_disabled')) return t('remote.off');
		if (isCode(e, 'already_subscribed')) return t('remote.already');
		if (isCode(e, 'validation_failed')) return t('remote.linkInvalid');
		return problemMessage(e);
	}

	async function check(e?: SubmitEvent) {
		e?.preventDefault();
		linkError = null;
		const parsed = parseInviteUrl(link);
		if (!parsed) {
			linkError = t('remote.linkInvalid');
			return;
		}
		// A link of this server: the usual flow.
		if (!isOtherServer(parsed.host, location.host)) {
			addOpen = false;
			await goto(`/fi/${encodeURIComponent(parsed.token)}`);
			return;
		}
		busy = true;
		try {
			preview = await call(api.POST('/v1/remote-invites:preview', { body: { invite_url: parsed.url } }));
			link = parsed.url;
			step = 'confirm';
		} catch (err) {
			linkError = remoteError(err);
		} finally {
			busy = false;
		}
	}

	async function follow() {
		busy = true;
		try {
			const s = await call(
				api.POST('/v1/frames/{frameId}/remote-subscriptions', {
					params: { path: { frameId: frame.id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: { invite_url: link, ...historyBody(history), on_end: onEnd }
				})
			);
			follows = upsertFollow(follows, s);
			addOpen = false;
			toasts.success(t(s.status === 'pending' ? 'remote.requested' : 'remote.followed', { feed: s.feed_name, frame: frame.name }));
		} catch (err) {
			toasts.error(remoteError(err));
		} finally {
			busy = false;
		}
	}

	// ---- on end, unfollow
	async function setOnEnd(s: RemoteSubscription, v: RemoteOnEnd) {
		try {
			follows = upsertFollow(follows, await call(api.PATCH('/v1/frames/{frameId}/remote-subscriptions/{remoteSubscriptionId}', { params: { path: { frameId: frame.id, remoteSubscriptionId: s.id } }, body: { on_end: v } })));
			toasts.success(t('common.saved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	let removing = $state<RemoteSubscription | null>(null);
	let removeOpen = $state(false);
	let removeChoice = $state<RemoteOnEnd>('remove');
	async function unfollow() {
		const s = removing;
		if (!s) return;
		removeOpen = false;
		try {
			await call(api.DELETE('/v1/frames/{frameId}/remote-subscriptions/{remoteSubscriptionId}', { params: { path: { frameId: frame.id, remoteSubscriptionId: s.id }, query: { placements: removeChoice } } }));
			toasts.success(t(s.status === 'pending' ? 'frameFeeds.withdrawn' : 'frameFeeds.unsubscribed', { feed: s.feed_name }));
			void load();
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	const ON_END = $derived<{ id: RemoteOnEnd; label: string }[]>([
		{ id: 'remove', label: t('remote.onEnd.remove') },
		{ id: 'keep', label: t('remote.onEnd.keep') }
	]);
	const host = (s: RemoteSubscription) => s.server.host ?? serverName(s.server);
</script>

{#if manage || groups.live.length > 0}
	<section class="remote stack-sm" aria-labelledby="remote-{frame.id}">
		<div class="head">
			<h2 id="remote-{frame.id}" class="h3"><Icon name="globe" size={18} /> {t('remote.followsTitle')}</h2>
			{#if manage}<button class="btn btn-sm" onclick={() => startAdd()}><Icon name="plus" size={16} />{t('remote.add')}</button>{/if}
		</div>
		{#if groups.live.length > 0}
			<ul class="subs" role="list">
				{#each groups.live as s (s.id)}
					<li class="card sub" in:softFly>
						<div class="s-head">
							<FeedCover name={s.feed_name} url={s.cover_url} size={44} />
							<div class="who">
								<strong>{s.feed_name}</strong>
								<span class="muted small"><span class="badge server">{serverName(s.server)}</span> {host(s)}</span>
							</div>
							<span class="badge" class:badge-owner={s.status === 'active'} class:warn={s.status === 'error'}>{t(`remote.status.${s.status}`)}</span>
						</div>
						<p class="small muted meta">
							{t(`subs.historyShort.${s.history}`, { n: s.history_count ?? 0 })}{#if s.item_count}{' · '}{tn('history.photos', s.item_count)}{/if}{#if s.last_sync_at}{' · '}{t('remote.lastSync', { when: relativeTime(s.last_sync_at, i18n.tag) })}{/if}
						</p>
						{#if s.backfill?.state === 'running'}
							<p class="small" role="status">{t('frameFeeds.backfilling', { placed: s.backfill.placed ?? 0, total: s.backfill.total ?? 0 })}</p>
						{/if}
						{#if manage}
							<div class="row-wrap actions">
								<label class="visually-hidden" for="onend-{s.id}">{t('remote.onEnd')}</label>
								<select id="onend-{s.id}" class="select select-sm" value={s.on_end} onchange={(e) => setOnEnd(s, e.currentTarget.value as RemoteOnEnd)} title={t('remote.onEnd')}>
									{#each ON_END as o (o.id)}<option value={o.id}>{t('remote.onEnd')}: {o.label}</option>{/each}
								</select>
								<button class="btn btn-danger-ghost btn-sm" onclick={() => ((removing = s), (removeChoice = s.on_end), (removeOpen = true))}>
									<Icon name="x" size={16} />{s.status === 'pending' ? t('frameFeeds.withdraw') : t('frameFeeds.unsubscribe')}
								</button>
							</div>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
		{#if manage && groups.ended.length > 0}
			<button class="btn btn-ghost btn-sm align-start" aria-expanded={showEnded} onclick={() => (showEnded = !showEnded)}>
				<Icon name="chevronDown" size={16} />{tn('subs.ended', groups.ended.length)}
			</button>
			{#if showEnded}
				<ul class="ended" role="list">
					{#each groups.ended as s (s.id)}
						<li class="small">
							<strong>{s.feed_name}</strong>
							<span class="muted">({host(s)}) · {s.status === 'rejected' ? t('remote.status.rejected') : t(`remote.reason.${s.removed_reason ?? 'unsubscribed'}`)} · {relativeTime(s.ended_at ?? s.created_at, i18n.tag)}</span>
						</li>
					{/each}
				</ul>
			{/if}
		{/if}
	</section>
{/if}

<Dialog bind:open={addOpen} title={t('remote.add')} size="md">
	{#if step === 'link'}
		<form id="remote-link-{frame.id}" class="stack" onsubmit={check}>
			<p class="muted small">{t('remote.addBody')}</p>
			<div class="field">
				<label for="remote-link-in-{frame.id}">{t('remote.inviteLink')}</label>
				<input
					id="remote-link-in-{frame.id}"
					class="input"
					bind:value={link}
					placeholder="https://kinframe.example.org/fi/…"
					autocomplete="off"
					spellcheck="false"
					inputmode="url"
					aria-invalid={Boolean(linkError)}
				/>
			</div>
			{#if linkError}<p class="error-text small" role="alert">{linkError}</p>{/if}
		</form>
	{:else if preview}
		<div class="stack">
			<div class="s-head">
				<FeedCover name={preview.feed_name} url={preview.cover_url} size={56} />
				<div class="who">
					<strong>{t('remote.previewOn', { feed: preview.feed_name, host: preview.server.host ?? serverName(preview.server) })}</strong>
					{#if preview.frame_count != null}<span class="muted small">{tn('feeds.frames', preview.frame_count)}</span>{/if}
				</div>
			</div>
			<p>{t('remote.previewCopy', { server: preview.this_server_host ?? preview.this_server_name, frame: frame.name })}</p>
			<p class="hint"><Icon name="shield" size={14} /> {t('remote.previewOperator', { server: preview.this_server_name })}</p>
			{#if preview.requires_approval}<p class="callout small"><Icon name="clock" size={18} />{t('remote.needsApproval')}</p>{/if}
			<HistoryPicker bind:value={history} />
			<ChoiceGroup name="onend-new-{frame.id}" legend={t('remote.onEnd')} bind:value={onEnd} options={ON_END} />
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (step === 'confirm' ? (step = 'link') : (addOpen = false))}>{step === 'confirm' ? t('common.cancel') : t('common.cancel')}</button>
		{#if step === 'link'}
			<button class="btn btn-primary" type="submit" form="remote-link-{frame.id}" disabled={!link.trim() || busy}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('frameFeeds.continue')}
			</button>
		{:else}
			<button class="btn btn-primary" onclick={follow} disabled={busy}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('remote.follow', { frame: frame.name })}
			</button>
		{/if}
	{/snippet}
</Dialog>

<Dialog bind:open={removeOpen} title={t('remote.unsubscribeTitle', { feed: removing?.feed_name ?? '' })}>
	<ChoiceGroup name="onend-rm-{frame.id}" legend={t('remote.onEnd')} bind:value={removeChoice} options={ON_END} />
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={unfollow}>{removing?.status === 'pending' ? t('frameFeeds.withdraw') : t('frameFeeds.unsubscribe')}</button>
	{/snippet}
</Dialog>

<style>
	.remote {
		margin-top: var(--s-5);
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	.h3 {
		font-size: 1.05rem;
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.subs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.sub {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.s-head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		overflow-wrap: anywhere;
	}
	.meta {
		margin: 0;
	}
	.server {
		margin-inline-end: 4px;
	}
	.warn {
		color: var(--warn);
	}
	.ended {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.align-start {
		align-self: flex-start;
	}
</style>
