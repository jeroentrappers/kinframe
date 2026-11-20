<script lang="ts">
	import type { FeedSummary } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { directory } from '$lib/family.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';
	import FrameFace from './FrameFace.svelte';

	/** Home's row of the family (simple-app.md §3.1): people, then frames, then "Invite". */
	let { feed, oninvite }: { feed: Pick<FeedSummary, 'id' | 'name'>; oninvite: () => void } = $props();

	$effect(() => void directory.load(feed.id));
	const members = $derived(
		[...(directory.members[feed.id] ?? [])].sort((a, b) => Number(b.user_id === auth.me?.id) - Number(a.user_id === auth.me?.id))
	);
	const frameList = $derived((directory.frames[feed.id] ?? []).filter((f) => f.status !== 'pending'));
</script>

<nav class="strip" aria-label={t('family.rowLabel', { family: feed.name })}>
	<ul role="list">
		{#each members as m (m.user_id)}
			<li>
				<a class="tile" href="/people/{m.user_id}">
					<Avatar name={m.display_name} url={m.avatar_url} size={60} />
					<span class="nm">{m.user_id === auth.me?.id ? t('family.you') : m.display_name}</span>
				</a>
			</li>
		{/each}
		{#each frameList as f (f.id)}
			<li>
				<a class="tile" href="/frames/{f.id}" aria-label={f.online ? t('family.frameOnline', { frame: f.name }) : f.name}>
					<FrameFace frameId={f.id} online={f.online} size={64} />
					<span class="nm">{f.name}</span>
				</a>
			</li>
		{/each}
		<li>
			<button class="tile" onclick={oninvite}>
				<span class="invite" aria-hidden="true"><Icon name="plus" size={24} /></span>
				<span class="nm">{t('family.inviteShort')}</span>
			</button>
		</li>
	</ul>
</nav>

<style>
	.strip {
		margin-inline: calc(-1 * var(--s-4));
	}
	ul {
		list-style: none;
		margin: 0;
		padding: var(--s-1) var(--s-4) var(--s-2);
		display: flex;
		gap: var(--s-2);
		overflow-x: auto;
		scrollbar-width: thin;
	}
	.tile {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		width: 78px;
		padding: 4px 2px;
		border: 0;
		border-radius: var(--r);
		background: transparent;
		color: var(--text);
		font: inherit;
		text-decoration: none;
		cursor: pointer;
	}
	.tile:hover {
		background: var(--surface-2);
	}
	.nm {
		font-weight: 600;
		font-size: 0.78rem;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.invite {
		display: grid;
		place-items: center;
		width: 64px;
		height: 64px;
		border-radius: 50%;
		border: 2px dashed var(--border-strong);
		color: var(--primary);
	}
</style>
