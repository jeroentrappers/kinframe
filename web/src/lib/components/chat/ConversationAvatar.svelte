<script lang="ts">
	import type { Conversation } from '$lib/api/client';
	import Avatar from '../Avatar.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import Icon from '../Icon.svelte';

	let { conversation: c, size = 44 }: { conversation: Pick<Conversation, 'kind' | 'name' | 'photo_url' | 'other'>; size?: number } = $props();
</script>

{#if c.kind === 'family'}
	<FeedCover name={c.name} url={c.photo_url} {size} />
{:else if c.kind === 'circle'}
	<span class="circle" style="--size:{size}px" aria-hidden="true"><Icon name="users" size={Math.round(size * 0.45)} /></span>
{:else if c.kind === 'direct'}
	<Avatar name={c.other?.former_member ? '' : (c.other?.display_name ?? c.name)} url={c.other?.former_member ? null : (c.photo_url ?? c.other?.avatar_url)} {size} />
{:else}
	<Avatar name={c.name} url={c.photo_url} {size} />
{/if}

<style>
	.circle {
		width: var(--size);
		height: var(--size);
		flex: none;
		border-radius: 50%;
		display: inline-grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent-text);
	}
</style>
