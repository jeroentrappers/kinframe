<script lang="ts">
	import { api, call, type Feed, type Subscription } from '$lib/api/client';
	import { groupSubscriptions, isFeedAdmin } from '$lib/feeds';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import OnlineStatus from '../OnlineStatus.svelte';
	import SubscriptionStatusBadge from './SubscriptionStatusBadge.svelte';
	// Frames on other servers (README §5.22): server names, blocking a server.
	import { discovery } from '$lib/discovery.svelte';
	import BlockedServers from '../remote/BlockedServers.svelte';
	let blockHost = $state<string | null>(null);

	let { feed, onchanged }: { feed: Feed; onchanged?: () => void } = $props();

	const admin = $derived(isFeedAdmin(feed.role));
	let subs = $state<Subscription[] | null>(null);
	let error = $state<unknown>(null);
	let showEnded = $state(false);

	async function load() {
		try {
			subs = await call(api.GET('/v1/feeds/{feedId}/subscriptions', { params: { path: { feedId: feed.id } } }));
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void feed.id;
		void load();
		return live.on((e) => {
			if ((e.type === 'subscription.request' || e.type === 'subscription.updated') && e.feed_id === feed.id) void load();
		});
	});

	const groups = $derived(groupSubscriptions(subs ?? [], 'frame'));

	function replace(s: Subscription) {
		subs = (subs ?? []).some((x) => x.id === s.id) ? (subs ?? []).map((x) => (x.id === s.id ? s : x)) : [s, ...(subs ?? [])];
	}

	async function decide(s: Subscription, decision: 'approve' | 'reject') {
		try {
			const res =
				decision === 'approve'
					? await call(api.POST('/v1/subscriptions/{subscriptionId}/approve', { params: { path: { subscriptionId: s.id } } }))
					: await call(api.POST('/v1/subscriptions/{subscriptionId}/reject', { params: { path: { subscriptionId: s.id } } }));
			replace(res);
			toasts.success(t(decision === 'approve' ? 'subs.approved' : 'subs.rejected', { frame: s.frame.name }));
			onchanged?.();
		} catch (e) {
			toasts.error(isCode(e, 'already_decided') ? t('subs.alreadyDecided') : problemMessage(e));
			void load();
		}
	}

	let removing = $state<Subscription | null>(null);
	let removeOpen = $state(false);
	async function remove() {
		const s = removing;
		if (!s) return;
		removeOpen = false;
		try {
			await call(api.DELETE('/v1/subscriptions/{subscriptionId}', { params: { path: { subscriptionId: s.id } } }));
			toasts.success(t('subs.removedToast', { frame: s.frame.name }));
			void load();
			onchanged?.();
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

</script>


{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
{:else if !subs}
	<div class="stack-sm">{#each [0, 1] as i (i)}<div class="skeleton" style="height:60px"></div>{/each}</div>
{:else}
	<div class="stack">
		{#if admin && groups.pending.length > 0}
			<section class="card card-pad requests" aria-labelledby="sreq-title" in:softFly>
				<div class="section-title"><h2 id="sreq-title"><Icon name="bell" size={18} /> {t('subs.pendingTitle')}</h2></div>
				<ul class="list" role="list">
					{#each groups.pending as s (s.id)}
						<li class="row-item wrap" out:softFly>
							<span class="f-icon"><Icon name="frame" size={18} /></span>
							<div class="who">
								<strong>{s.frame.name}{#if s.remote} <span class="badge"><Icon name="globe" size={12} />{s.remote.server_name}</span>{/if}</strong>
								{#if s.remote}
									<span class="small">{t('remote.wantsToShow', { name: s.remote.requested_by_name ?? s.requested_by_name ?? '', feed: feed.name, frame: s.frame.name, server: s.remote.host ?? s.remote.server_name })}</span>
								{/if}
								<span class="muted small">
									{s.requested_by_name && !s.remote ? t('subs.requestedBy', { name: s.requested_by_name }) : ''}{#if s.frame.location} · {s.frame.location}{/if} · {relativeTime(s.created_at, i18n.tag)}
								</span>
								<span class="tiny muted">{t(`subs.historyShort.${s.history}`, { n: s.history_count ?? 0 })}</span>
							</div>
							<div class="row-wrap">
								<button class="btn btn-sm btn-ghost" onclick={() => decide(s, 'reject')}>{t('subs.reject')}</button>
								<button class="btn btn-sm btn-primary" onclick={() => decide(s, 'approve')}><Icon name="check" size={16} />{t('people.approve')}</button>
							</div>
						</li>
					{/each}
				</ul>
			</section>
		{:else if !admin && groups.pending.length > 0}
			<p class="callout small"><Icon name="clock" size={18} />{tn('subs.yourPending', groups.pending.length)}</p>
		{/if}

		<section class="card card-pad" aria-labelledby="slive-title">
			<div class="section-title">
				<h2 id="slive-title">{t('subs.framesTitle')}</h2>
				<span class="muted small num">{groups.live.length}</span>
			</div>
			{#if groups.live.length === 0}
				<p class="muted small">{t('subs.noFrames')}</p>
			{:else}
				<ul class="list" role="list">
					{#each groups.live as s (s.id)}
						<li class="row-item">
							<span class="f-icon"><Icon name="frame" size={18} /></span>
							<div class="who">
								<strong>{s.frame.name}</strong>
								<span class="muted small row-wrap meta">
									{#if s.remote}<span class="badge" title={s.remote.server_url}><Icon name="globe" size={12} />{t('remote.frameOnServer', { frame: s.remote.server_name, server: s.remote.host ?? s.remote.server_url })}</span>{/if}
									{#if s.frame.location}<span>{s.frame.location}</span>{/if}
									{#if s.frame.online !== undefined}<OnlineStatus online={s.frame.online} lastSeen={s.frame.last_seen_at ?? null} />{/if}
								</span>
							</div>
							<SubscriptionStatusBadge status={s.status} />
							{#if s.remote && admin && discovery.remoteFamilies}
								<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('remote.blockServer', { server: s.remote.server_name })} title={t('remote.blockServer', { server: s.remote.server_name })} onclick={() => (blockHost = s.remote?.host ?? s.remote?.server_url ?? null)}>
									<Icon name="ban" size={16} />
								</button>
							{/if}
							{#if s.can_decide}
								<button
									class="btn btn-danger-ghost btn-icon btn-sm"
									aria-label={t('subs.removeFrame', { frame: s.frame.name })}
									title={t('subs.removeFrame', { frame: s.frame.name })}
									onclick={() => ((removing = s), (removeOpen = true))}><Icon name="x" size={16} /></button
								>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</section>

		{#if admin && groups.ended.length > 0}
			<section class="card card-pad">
				<button class="btn btn-ghost btn-sm" aria-expanded={showEnded} onclick={() => (showEnded = !showEnded)}>
					<Icon name="chevronDown" size={16} />{tn('subs.ended', groups.ended.length)}
				</button>
				{#if showEnded}
					<ul class="list" role="list" in:softFly>
						{#each groups.ended as s (s.id)}
							<li class="row-item">
								<div class="who">
									<span>{s.frame.name}</span>
									<span class="muted tiny">{s.status === 'rejected' ? t('subs.status.rejected') : t(`subs.reason.${s.removed_reason ?? 'unsubscribed'}`)} · {relativeTime(s.ended_at ?? s.created_at, i18n.tag)}</span>
								</div>
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		{/if}
	</div>
{/if}

{#if admin && discovery.remoteFamilies && (subs ?? []).some((s) => s.remote)}
	<div class="blocked"><BlockedServers scope={{ kind: 'feed', feedId: feed.id }} bind:prefill={blockHost} onchange={load} /></div>
{/if}


<Dialog bind:open={removeOpen} title={t('subs.removeConfirm', { frame: removing?.frame.name ?? '' })}>
	<p class="muted">{t('subs.removeBody', { feed: feed.name })}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={remove}>{t('subs.remove')}</button>
	{/snippet}
</Dialog>

<style>
	.blocked {
		margin-top: var(--s-4);
	}
	.requests {
		border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
		background: color-mix(in srgb, var(--primary-soft) 35%, var(--surface));
	}
	.requests h2 {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list > li + li {
		border-top: 1px solid var(--border);
	}
	.row-item {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 0;
	}
	.wrap {
		flex-wrap: wrap;
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.who strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.meta {
		gap: 8px;
	}
	.f-icon {
		width: 36px;
		height: 36px;
		border-radius: 10px;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
</style>
