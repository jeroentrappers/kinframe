<script lang="ts">
	import type { Snippet } from 'svelte';
	import Illustration from './Illustration.svelte';

	let {
		title,
		body,
		art = 'frame',
		actions,
		compact = false
	}: {
		title: string;
		body?: string;
		art?: 'frame' | 'send' | 'history' | 'library' | 'people' | 'none';
		actions?: Snippet;
		compact?: boolean;
	} = $props();
</script>

<div class="empty" class:compact>
	{#if art !== 'none'}
		<div class="art"><Illustration kind={art} /></div>
	{/if}
	<h2>{title}</h2>
	{#if body}<p class="muted">{body}</p>{/if}
	{#if actions}<div class="actions">{@render actions()}</div>{/if}
</div>

<style>
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-3);
		padding: var(--s-7) var(--s-4);
		max-width: 460px;
		margin: 0 auto;
	}
	.empty.compact {
		padding: var(--s-5) var(--s-3);
	}
	.art {
		width: 180px;
		margin-bottom: var(--s-2);
	}
	.compact .art {
		width: 120px;
	}
	h2 {
		font-size: 1.35rem;
	}
	p {
		max-width: 38ch;
	}
	.actions {
		display: flex;
		gap: var(--s-2);
		flex-wrap: wrap;
		justify-content: center;
		margin-top: var(--s-2);
	}
</style>
