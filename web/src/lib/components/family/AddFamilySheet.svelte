<script lang="ts">
	import type { Subscription } from '$lib/api/client';
	import { addFrameToFamily, families, shows } from '$lib/family.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import FeedCover from '../feed/FeedCover.svelte';

	/**
	 * "Add a family" on a frame (simple-app.md §3.3): any family the manager is in; it shows at once with its last 50
	 * posts (§7). Families they are not in come through that family's invite link for frames.
	 */
	let {
		open = $bindable(false),
		frameId,
		frameName,
		onadded
	}: { open?: boolean; frameId: string; frameName: string; onadded?: (s: Subscription) => void } = $props();

	let busy = $state<string | null>(null);
	$effect(() => {
		if (open) void shows.load(frameId);
	});
	const taken = $derived(new Set((shows.byFrame[frameId] ?? []).map((s) => s.feed.id)));
	const choices = $derived(families.ranked.filter((f) => !taken.has(f.id)));

	async function add(feedId: string, name: string) {
		busy = feedId;
		try {
			const sub = await addFrameToFamily(feedId, frameId);
			open = false;
			if (sub.status === 'pending') toasts.show(t('family.addPending', { frame: frameName }));
			else toasts.success(t('family.added', { frame: frameName, family: name }));
			onadded?.(sub);
		} catch (e) {
			toasts.error(isCode(e, 'already_subscribed') ? t('family.alreadyShows', { frame: frameName }) : problemMessage(e));
		} finally {
			busy = null;
		}
	}
</script>

<Dialog bind:open title={t('frameCard.addFamily')}>
	{#if choices.length === 0}
		<p class="muted">{t('frameCard.addFamilyNone')}</p>
	{:else}
		<p class="muted small">{t('frameCard.addFamilyBody', { frame: frameName })}</p>
		<ul class="choices" role="list">
			{#each choices as f (f.id)}
				<li>
					<button class="choice" onclick={() => add(f.id, f.name)} disabled={busy !== null}>
						<FeedCover name={f.name} url={f.cover_url} size={40} />
						<span class="body"><span class="name">{f.name}</span><span class="muted small">{tn('family.people', f.member_count)}</span></span>
						{#if busy === f.id}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="plus" size={18} />{/if}
					</button>
				</li>
			{/each}
		</ul>
	{/if}
</Dialog>

<style>
	.choices {
		list-style: none;
		margin: var(--s-3) 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.choice {
		width: 100%;
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3);
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: inherit;
		font: inherit;
		text-align: start;
		cursor: pointer;
	}
	.choice:hover:not(:disabled) {
		border-color: var(--primary);
	}
	.body {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.name {
		font-weight: 650;
	}
</style>
