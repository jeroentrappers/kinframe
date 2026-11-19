<script lang="ts">
	import type { Mention } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { segments } from '$lib/social';

	let { body, mentions = [] }: { body: string | null | undefined; mentions?: Mention[] | null } = $props();
	const parts = $derived(segments(body, mentions));
</script>

<span class="rich">{#each parts as p, i (i)}{#if p.kind === 'mention'}<a class="mention" class:me={p.userId === auth.me?.id} href="/people/{p.userId}">{p.text}</a>{:else if p.kind === 'link'}<a class="link" href={p.href} target="_blank" rel="noopener noreferrer nofollow ugc">{p.text}</a>{:else}{p.text}{/if}{/each}</span>

<style>
	.rich {
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.mention {
		color: var(--primary-soft-text);
		font-weight: 650;
		text-decoration: none;
		border-radius: 4px;
	}
	.mention.me {
		background: var(--primary-soft);
		padding: 0 2px;
	}
	.mention:hover {
		text-decoration: underline;
	}
	.link {
		color: var(--primary);
		text-decoration: underline;
		text-underline-offset: 2px;
	}
</style>
