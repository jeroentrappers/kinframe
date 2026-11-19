<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Feed, type FeedMember, type JoinRequest } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { canEditFeedMember, isFeedAdmin, sortFeedMembers } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import FeedRoleBadge from './FeedRoleBadge.svelte';

	let { feed, onchanged }: { feed: Feed; onchanged?: () => void } = $props();

	const admin = $derived(isFeedAdmin(feed.role));
	const meId = $derived(auth.me?.id);

	let members = $state<FeedMember[] | null>(null);
	let requests = $state<JoinRequest[]>([]);
	let error = $state<unknown>(null);
	let expanded = $state<string | null>(null);

	async function load() {
		try {
			const [m, r] = await Promise.all([
				call(api.GET('/v1/feeds/{feedId}/members', { params: { path: { feedId: feed.id } } })),
				admin ? call(api.GET('/v1/feeds/{feedId}/join-requests', { params: { path: { feedId: feed.id } } })) : Promise.resolve([])
			]);
			members = m;
			requests = r;
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void feed.id;
		void admin;
		void load();
		return live.on((e) => {
			if (e.type === 'feed.join_request' && e.feed_id === feed.id && !requests.some((r) => r.id === e.request.id))
				requests = [e.request, ...requests];
			if (e.type === 'feed.membership_updated' && e.feed_id === feed.id) void load();
		});
	});

	const sorted = $derived(sortFeedMembers(members ?? []));
	const editable = (m: FeedMember) => canEditFeedMember({ role: feed.role, userId: meId }, m);

	// ---- join requests
	async function decide(r: JoinRequest, decision: 'approve' | 'deny') {
		const before = requests;
		requests = requests.filter((x) => x.id !== r.id);
		try {
			await call(
				api.POST('/v1/feeds/{feedId}/join-requests/{requestId}/{decision}', {
					params: { path: { feedId: feed.id, requestId: r.id, decision } }
				})
			);
			toasts.success(t(decision === 'approve' ? 'people.approved' : 'people.denied', { name: r.user.display_name ?? '' }));
			if (decision === 'approve') {
				void load();
				onchanged?.();
			}
		} catch (e) {
			requests = before;
			toasts.error(isCode(e, 'already_decided') ? t('feedMembers.alreadyDecided') : problemMessage(e));
			if (isCode(e, 'already_decided')) void load();
		}
	}

	// ---- role
	async function setRole(m: FeedMember, role: 'admin' | 'poster') {
		const before = m.role;
		m.role = role;
		try {
			Object.assign(
				m,
				await call(api.PATCH('/v1/feeds/{feedId}/members/{userId}', { params: { path: { feedId: feed.id, userId: m.user_id } }, body: { role } }))
			);
		} catch (e) {
			m.role = before;
			toasts.error(problemMessage(e));
		}
	}

	// ---- remove member
	let removing = $state<FeedMember | null>(null);
	let removeOpen = $state(false);
	let removePosts = $state(false);

	async function removeMember() {
		const m = removing;
		if (!m) return;
		removeOpen = false;
		const before = members;
		members = (members ?? []).filter((x) => x.user_id !== m.user_id);
		try {
			await call(
				api.DELETE('/v1/feeds/{feedId}/members/{userId}', {
					params: { path: { feedId: feed.id, userId: m.user_id }, query: { remove_posts: removePosts } }
				})
			);
			toasts.success(t('people.removed', { name: m.display_name }));
			onchanged?.();
		} catch (e) {
			members = before;
			toasts.error(problemMessage(e));
		}
	}

	// ---- transfer ownership
	let transferOpen = $state(false);
	let transferTo = $state<string>('');
	let transferring = $state(false);
	const transferCandidates = $derived(sorted.filter((m) => m.user_id !== meId));

	async function transfer() {
		if (!transferTo) return;
		transferring = true;
		try {
			await call(api.POST('/v1/feeds/{feedId}/transfer', { params: { path: { feedId: feed.id } }, body: { user_id: transferTo } }));
			const name = members?.find((m) => m.user_id === transferTo)?.display_name ?? '';
			transferOpen = false;
			toasts.success(t('transfer.done', { name }));
			void load();
			onchanged?.();
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			transferring = false;
		}
	}

	// ---- leave
	let leaveOpen = $state(false);
	let leavePosts = $state(false);
	let leaving = $state(false);

	async function leave() {
		leaving = true;
		try {
			await call(api.POST('/v1/feeds/{feedId}/leave', { params: { path: { feedId: feed.id } }, body: { remove_my_posts: leavePosts } }));
			leaveOpen = false;
			feeds.remove(feed.id);
			toasts.success(t('feedMembers.left', { feed: feed.name }));
			await goto('/feeds');
		} catch (e) {
			toasts.error(isCode(e, 'owner_cannot_leave') ? t('feedMembers.ownerCannotLeave') : problemMessage(e));
		} finally {
			leaving = false;
		}
	}
</script>

{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
{:else}
	<div class="stack">
		{#if requests.length > 0}
			<section class="card card-pad requests" aria-labelledby="freq-title" in:softFly>
				<div class="section-title"><h2 id="freq-title"><Icon name="bell" size={18} /> {t('feedMembers.requests')}</h2></div>
				<ul class="list" role="list">
					{#each requests as r (r.id)}
						<li class="row-item req" out:softFly>
							<Avatar name={r.user.display_name ?? '?'} url={r.user.avatar_url} size={40} />
							<div class="who">
								<strong>{r.user.display_name}</strong>
								<span class="muted small">
									{r.invited_by_name ? t('people.viaInvite', { name: r.invited_by_name }) : t('feedMembers.viaLink')} · {relativeTime(r.created_at, i18n.tag)}
								</span>
								{#if r.note}<span class="small">“{r.note}”</span>{/if}
							</div>
							<div class="row-wrap">
								<button class="btn btn-sm btn-ghost" onclick={() => decide(r, 'deny')}>{t('people.deny')}</button>
								<button class="btn btn-sm btn-primary" onclick={() => decide(r, 'approve')}><Icon name="check" size={16} />{t('people.approve')}</button>
							</div>
						</li>
					{/each}
				</ul>
			</section>
		{/if}

		<section class="card card-pad" aria-labelledby="fmem-title">
			<div class="section-title">
				<h2 id="fmem-title">{t('people.members')}</h2>
				{#if members}<span class="muted small num">{members.length}</span>{/if}
			</div>
			<p class="muted small intro">{t('feedMembers.intro')}</p>
			{#if !members}
				<div class="stack-sm">{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:52px"></div>{/each}</div>
			{:else}
				<ul class="list" role="list">
					{#each sorted as m (m.user_id)}
						<li class="member" class:open={expanded === m.user_id}>
							<div class="row-item">
								<Avatar name={m.display_name} url={m.avatar_url} size={40} />
								<div class="who">
									<strong>{m.display_name}{#if m.user_id === meId}<span class="faint you">({t('people.you')})</span>{/if}</strong>
									<span class="muted small">
										{m.last_posted_at ? t('feedMembers.lastPosted', { when: relativeTime(m.last_posted_at, i18n.tag) }) : t('feedMembers.neverPosted')}
									</span>
								</div>
								<FeedRoleBadge role={m.role} />
								{#if editable(m)}
									<button
										class="btn btn-ghost btn-icon btn-sm"
										aria-expanded={expanded === m.user_id}
										aria-controls="fm-{m.user_id}"
										aria-label={t('people.editMember', { name: m.display_name })}
										onclick={() => (expanded = expanded === m.user_id ? null : m.user_id)}
									>
										<Icon name="chevronDown" size={18} />
									</button>
								{/if}
							</div>
							{#if expanded === m.user_id}
								<div class="panel" id="fm-{m.user_id}" in:softFly>
									<div class="field">
										<label for="frole-{m.user_id}">{t('people.role')}</label>
										<select
											id="frole-{m.user_id}"
											class="select"
											value={m.role}
											onchange={(e) => setRole(m, e.currentTarget.value as 'admin' | 'poster')}
										>
											<option value="poster">{t('feedRole.poster')}</option>
											<option value="admin">{t('feedRole.admin')}</option>
										</select>
										<span class="hint">{m.role === 'admin' ? t('feedMembers.adminHint') : t('feedMembers.posterHint')}</span>
									</div>
									<div class="row-wrap">
										{#if feed.role === 'owner'}
											<button
												class="btn btn-ghost btn-sm"
												onclick={() => {
													transferTo = m.user_id;
													transferOpen = true;
												}}><Icon name="crown" size={16} />{t('people.makeOwner')}</button
											>
										{/if}
										<button
											class="btn btn-danger-ghost btn-sm"
											onclick={() => {
												removing = m;
												removePosts = false;
												removeOpen = true;
											}}><Icon name="trash" size={16} />{t('people.remove')}</button
										>
									</div>
								</div>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</section>

		<section class="card card-pad stack-sm" aria-labelledby="fyou-title">
			<h2 id="fyou-title" class="h3">{t('feedMembers.youTitle')}</h2>
			<div class="row-wrap">
				{#if feed.role === 'owner'}
					<button class="btn" onclick={() => ((transferTo = ''), (transferOpen = true))} disabled={transferCandidates.length === 0}>
						<Icon name="crown" size={16} />{t('feedMembers.transfer')}
					</button>
				{/if}
				<button
					class="btn btn-danger-ghost"
					onclick={() => {
						if (feed.role === 'owner') toasts.show(t('feedMembers.ownerCannotLeave'));
						else {
							leavePosts = false;
							leaveOpen = true;
						}
					}}><Icon name="logout" size={16} />{t('feedMembers.leave')}</button
				>
			</div>
			{#if feed.role === 'owner'}<p class="hint">{t('feedMembers.ownerLeaveHint')}</p>{/if}
		</section>
	</div>
{/if}

<Dialog bind:open={removeOpen} title={t('people.removeConfirm', { name: removing?.display_name ?? '' })}>
	<div class="stack">
		<p class="muted">{t('feedMembers.removeBody', { feed: feed.name })}</p>
		<label class="switch"><input type="checkbox" bind:checked={removePosts} /><span>{t('feedMembers.removePosts')}</span></label>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={removeMember}>{t('people.remove')}</button>
	{/snippet}
</Dialog>

<Dialog bind:open={leaveOpen} title={t('feedMembers.leaveConfirm', { feed: feed.name })}>
	<div class="stack">
		<p class="muted">{t('feedMembers.leaveBody')}</p>
		<label class="switch"><input type="checkbox" bind:checked={leavePosts} /><span>{t('feedMembers.leavePosts')}</span></label>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (leaveOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={leave} disabled={leaving}>{t('feedMembers.leave')}</button>
	{/snippet}
</Dialog>

<Dialog bind:open={transferOpen} title={t('feedMembers.transferTitle', { feed: feed.name })}>
	<div class="stack">
		<p class="muted">{t('feedMembers.transferBody')}</p>
		<div class="field">
			<label for="ftransfer">{t('feedMembers.newOwner')}</label>
			<select id="ftransfer" class="select" bind:value={transferTo}>
				<option value="" disabled>{t('feedMembers.pickMember')}</option>
				{#each transferCandidates as m (m.user_id)}<option value={m.user_id}>{m.display_name}</option>{/each}
			</select>
		</div>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (transferOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={transfer} disabled={!transferTo || transferring}>
			{#if transferring}<span class="spinner" aria-hidden="true"></span>{/if}{t('feedMembers.transfer')}
		</button>
	{/snippet}
</Dialog>

<style>
	.requests {
		border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
		background: color-mix(in srgb, var(--primary-soft) 35%, var(--surface));
	}
	.requests h2 {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.intro {
		margin-bottom: var(--s-2);
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
	.req {
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
	.you {
		margin-inline-start: 0.35em;
		font-weight: 500;
	}
	.member.open {
		background: linear-gradient(var(--surface-2), var(--surface-2)) padding-box;
		margin: 0 calc(-1 * var(--s-3));
		padding: 0 var(--s-3);
		border-radius: var(--r);
	}
	.panel {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: 0 0 var(--s-3) 52px;
	}
	@media (max-width: 520px) {
		.panel {
			padding-inline-start: 0;
		}
	}
	.h3 {
		font-size: 1.05rem;
	}
</style>
