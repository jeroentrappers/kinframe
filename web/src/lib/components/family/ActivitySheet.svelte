<script lang="ts">
	import { api, call, type Feed, type TimelinePost } from '$lib/api/client';
	import { isFeedAdmin } from '$lib/feeds';
	import { relativeTime } from '$lib/format';
	import { personName } from '$lib/social';
	import { dedupePosts } from '$lib/family';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** Home's activity button (simple-app.md §3.1): new comments on the families' posts and what waits for an admin. */
	let { open = $bindable(false), feedIds }: { open?: boolean; feedIds: string[] } = $props();

	let list = $state<Feed[]>([]);
	let unread = $state<TimelinePost[]>([]);
	let loading = $state(false);

	$effect(() => {
		if (!open) return;
		const ids = feedIds;
		loading = true;
		Promise.all([
			Promise.all(ids.map((id) => call(api.GET('/v1/feeds/{feedId}', { params: { path: { feedId: id } } })).catch(() => null))),
			call(api.GET('/v1/timeline', { params: { query: { limit: 50, ...(ids.length === 1 ? { feed_id: ids[0] } : {}) } } })).catch(() => null)
		])
			.then(([fs, tl]) => {
				list = fs.filter((f): f is Feed => f !== null) as Feed[];
				unread = dedupePosts((tl?.posts ?? []).filter((p) => (p.thread.unread_count ?? 0) > 0 && (!p.feed_id || ids.includes(p.feed_id))));
			})
			.finally(() => (loading = false));
	});
	const pending = $derived(
		list.flatMap((f) => [
			...(f.pending_join_request_count ? [{ key: `j${f.id}`, href: `/feeds/${f.id}?tab=members`, icon: 'users' as const, text: tn('family.joinRequests', f.pending_join_request_count), family: f.name }] : []),
			...(isFeedAdmin(f.role) && f.pending_subscription_count
				? [{ key: `s${f.id}`, href: `/feeds/${f.id}?tab=subscriptions`, icon: 'frame' as const, text: tn('family.frameRequests', f.pending_subscription_count), family: f.name }]
				: [])
		])
	);
	const nothing = $derived(!loading && unread.length === 0 && pending.length === 0);
</script>

<Dialog bind:open title={t('family.activity')}>
	{#if loading && list.length === 0}
		<div class="skeleton" style="height:120px" aria-busy="true" aria-label={t('common.loading')}></div>
	{:else if nothing}
		<p class="muted center">{t('family.activityNone')}</p>
	{:else}
		<ul class="list" role="list">
			{#each pending as x (x.key)}
				<li><a href={x.href} onclick={() => (open = false)}><Icon name={x.icon} size={18} /><span class="grow">{x.text}{#if feedIds.length > 1}<span class="muted tiny">{x.family}</span>{/if}</span></a></li>
			{/each}
			{#each unread as p (p.id)}
				<li>
					<a href="/posts/{p.id}" onclick={() => (open = false)}>
						<Icon name="message" size={18} />
						<span class="grow">
							{tn('family.newComments', p.thread.unread_count ?? 0, { name: personName(p.poster, t('social.formerMember')) })}
							<span class="muted tiny">{relativeTime(p.created_at, i18n.tag)}</span>
						</span>
					</a>
				</li>
			{/each}
		</ul>
	{/if}
</Dialog>

<style>
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.list a {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3);
		border-radius: var(--r);
		color: inherit;
		text-decoration: none;
	}
	.list a:hover {
		background: var(--surface-2);
	}
	.grow {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.center {
		text-align: center;
	}
</style>
