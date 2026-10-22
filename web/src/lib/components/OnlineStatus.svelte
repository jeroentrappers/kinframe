<script lang="ts">
	import { relativeTime } from '$lib/format';
	import { i18n, t } from '$lib/i18n/index.svelte';

	let { online, lastSeen = null }: { online: boolean; lastSeen?: string | null } = $props();
</script>

<span class="status" class:online>
	<span class="dot" class:dot-online={online} aria-hidden="true"></span>
	{#if online}
		{t('frame.online')}
	{:else if lastSeen}
		{t('frame.lastSeen', { when: relativeTime(lastSeen, i18n.tag) })}
	{:else}
		{t('frame.offline')}
	{/if}
</span>

<style>
	.status {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		font-size: 0.86rem;
		color: var(--text-muted);
	}
	.online {
		color: var(--accent-text);
		font-weight: 600;
	}
</style>
