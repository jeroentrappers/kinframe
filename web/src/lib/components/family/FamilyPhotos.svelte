<script lang="ts">
	import { api, call, type FeedPost } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { problemMessage } from '$lib/problem';
	import { t, tn } from '$lib/i18n/index.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	/** The family's posts as a grid, newest first; "Only mine" replaces the old History tab (simple-app.md §3.4). */
	let { feedId }: { feedId: string } = $props();

	let mineOnly = $state(false);
	let posts = $state<FeedPost[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let more = $state(false);
	let error = $state<unknown>(null);

	async function load(reset: boolean) {
		if (reset) loading = true;
		else more = true;
		const id = feedId;
		const mine = mineOnly;
		try {
			const res = await call(
				api.GET('/v1/feeds/{feedId}/posts', {
					params: { path: { feedId: id }, query: { limit: 30, ...(mine && auth.me ? { poster_id: auth.me.id } : {}), ...(!reset && cursor ? { cursor } : {}) } }
				})
			);
			if (id !== feedId || mine !== mineOnly) return;
			posts = reset ? res.posts : [...posts, ...res.posts.filter((p) => !posts.some((x) => x.id === p.id))];
			cursor = res.next_cursor;
			error = null;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			more = false;
		}
	}

	$effect(() => {
		void feedId;
		void mineOnly;
		void load(true);
	});

	const tiles = $derived(posts.filter((p) => p.items.length > 0));
</script>

<div class="bar">
	<label class="switch only-mine">
		<input type="checkbox" bind:checked={mineOnly} data-testid="only-mine" />
		<span><span class="s-label">{t('family.onlyMine')}</span></span>
	</label>
	{#if mineOnly}<a class="btn btn-ghost btn-sm" href="/history"><Icon name="history" size={16} />{t('family.sentHistory')}</a>{/if}
</div>

{#if loading}
	<div class="grid" aria-busy="true" aria-label={t('common.loading')}>{#each [0, 1, 2, 3, 4, 5] as i (i)}<div class="skeleton tile"></div>{/each}</div>
{:else if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" /><div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load(true)}>{t('common.tryAgain')}</button></div></div>
{:else if tiles.length === 0}
	<div class="card"><EmptyState compact art="send" title={mineOnly ? t('family.noMine') : t('family.emptyTitle')} body={t('family.photosEmptyBody')} /></div>
{:else}
	<ul class="grid" role="list" aria-label={mineOnly ? t('family.onlyMine') : t('family.photos')}>
		{#each tiles as p (p.id)}
			{@const first = p.items[0]!}
			<li>
				<a class="tile" href="/posts/{p.id}" aria-label={tn('family.postBy', p.items.length, { name: p.poster.display_name })}>
					<Thumb itemId={first.id} kind={first.kind} radius="0" />
					{#if p.items.length > 1}<span class="many num" aria-hidden="true"><Icon name="album" size={14} />{p.items.length}</span>{/if}
				</a>
			</li>
		{/each}
	</ul>
	{#if cursor}<button class="btn btn-block more" onclick={() => load(false)} disabled={more}>{t('common.loadMore')}</button>{/if}
{/if}

<style>
	.bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		margin-bottom: var(--s-3);
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 3px;
	}
	@media (min-width: 720px) {
		.grid {
			grid-template-columns: repeat(4, 1fr);
		}
	}
	.tile {
		position: relative;
		display: block;
		aspect-ratio: 1;
		overflow: hidden;
		background: var(--surface-2);
	}
	.tile :global(img),
	.tile :global(.thumb) {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.many {
		position: absolute;
		top: 6px;
		inset-inline-end: 6px;
		display: inline-flex;
		align-items: center;
		gap: 3px;
		padding: 2px 6px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.55);
		color: #fff;
		font-size: 0.72rem;
		font-weight: 700;
	}
	.more {
		margin-top: var(--s-4);
	}
</style>
