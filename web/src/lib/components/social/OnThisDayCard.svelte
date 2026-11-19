<script lang="ts" module>
	import { api, call, type Item } from '$lib/api/client';
	import { newIdempotencyKey } from '$lib/logic';

	/** Re-share existing items of the caller's (On this day): a normal send with the item ids as uploads (README §5.20). */
	export async function resendItems(items: Item[], target: { frame_ids: string[]; feed_ids: string[]; circle_ids: string[] }) {
		return call(
			api.POST('/v1/sends', {
				params: { header: { 'Idempotency-Key': newIdempotencyKey() } },
				body: {
					frame_ids: target.frame_ids,
					...(target.feed_ids.length ? { feed_ids: target.feed_ids } : {}),
					...(target.circle_ids.length ? { circle_ids: target.circle_ids } : {}),
					items: items.map((i) => ({ upload_id: i.id }))
				} as never
			})
		);
	}
</script>

<script lang="ts">
	import type { OnThisDay } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';
	import ShareInChatDialog from './ShareInChatDialog.svelte';
	import { withMemories } from '$lib/leaving';
	import { problemMessage } from '$lib/problem';
	import TargetDialog from './TargetDialog.svelte';

	let { day }: { day: OnThisDay } = $props();

	let resendOpen = $state(false);
	let resendItemsList = $state<Item[]>([]);
	let shareOpen = $state(false);
	let sharePost = $state<{ postId: string; feedId: string } | null>(null);

	// "Don't show this memory again" (leaving.md story 8): adds the group's posts to the private memories filter.
	let hiddenGroups = $state(new Set<string>());
	function hideMemory(g: OnThisDay['groups'][number]) {
		const key = g.feed_id + g.years_ago;
		toasts.deferred({
			message: t('leaving.memoryHidden'),
			undoLabel: t('common.undo'),
			apply: () => (hiddenGroups = new Set([...hiddenGroups, key])),
			rollback: () => (hiddenGroups = new Set([...hiddenGroups].filter((k) => k !== key))),
			commit: async () => {
				const prefs = await call(api.GET('/v1/me/memories'));
				await call(api.PUT('/v1/me/memories', { body: withMemories(prefs, g.post_ids, true) }));
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	function openResend(items: Item[]) {
		resendItemsList = items.filter((i) => i.sender_id === auth.me?.id);
		resendOpen = true;
	}
</script>

{#each day.groups.filter((x) => !hiddenGroups.has(x.feed_id + x.years_ago)) as g (g.feed_id + g.years_ago)}
	{@const own = g.items.filter((i) => i.sender_id === auth.me?.id)}
	<section class="card otd" aria-labelledby="otd-{g.feed_id}-{g.years_ago}">
		<header>
			<span class="badge-otd" aria-hidden="true">🗓️</span>
			<div>
				<h2 id="otd-{g.feed_id}-{g.years_ago}">{t('engagement.onThisDay')}</h2>
				<p class="muted small">{tn('engagement.yearsAgoIn', g.years_ago, { family: g.feed_name })}</p>
			</div>
		</header>
		<ul class="thumbs" role="list">
			{#each g.items.slice(0, 6) as it (it.id)}
				<li><Thumb itemId={it.id} variant="thumb_256" kind={it.kind} radius="var(--r-sm)" /></li>
			{/each}
		</ul>
		<div class="row-wrap">
			{#if own.length > 0}
				<button class="btn btn-sm btn-soft" onclick={() => openResend(g.items)}><Icon name="frame" size={16} />{t('engagement.shareAgain')}</button>
			{/if}
			{#if discovery.chats && g.post_ids[0]}
				<button class="btn btn-sm btn-ghost" onclick={() => ((sharePost = { postId: g.post_ids[0]!, feedId: g.feed_id }), (shareOpen = true))}>
					<Icon name="message" size={16} />{t('engagement.shareInFamilyChat')}
				</button>
			{/if}
			{#if g.post_ids[0]}<a class="btn btn-sm btn-ghost" href="/posts/{g.post_ids[0]}">{t('engagement.viewPost')}</a>{/if}
			{#if discovery.leaving && g.post_ids.length > 0}
				<button class="btn btn-sm btn-ghost" onclick={() => hideMemory(g)}><Icon name="eyeOff" size={16} />{t('leaving.hideMemory')}</button>
			{/if}
		</div>
	</section>
{/each}

<TargetDialog
	bind:open={resendOpen}
	title={t('engagement.shareAgainTitle')}
	intro={tn('engagement.shareAgainBody', resendItemsList.length)}
	submitLabel={t('engagement.shareAgain')}
	onsubmit={async (target) => {
		await resendItems(resendItemsList, target);
		toasts.success(t('send.sent'));
	}}
/>

{#if shareOpen && sharePost}
	<ShareInChatDialog bind:open={shareOpen} postId={sharePost.postId} preferFeedId={sharePost.feedId} />
{/if}

<style>
	.otd {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		background: linear-gradient(160deg, var(--warn-soft), var(--surface) 60%);
	}
	header {
		display: flex;
		gap: var(--s-3);
		align-items: center;
	}
	h2 {
		font-size: 1.05rem;
	}
	header p {
		margin: 0;
	}
	.badge-otd {
		font-size: 1.6rem;
	}
	.thumbs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(76px, 1fr));
		gap: 6px;
	}
	.thumbs li {
		aspect-ratio: 1;
	}
</style>
