<script lang="ts">
	import { goto } from '$app/navigation';
	import type { FeedSummary } from '$lib/api/client';
	import { addFrameToFamily, directory } from '$lib/family.svelte';
	import { can, frames } from '$lib/frames.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import OnlineStatus from '../OnlineStatus.svelte';

	/**
	 * "Add a frame" (simple-app.md §3.4, §5): pick one of the frames you manage, or pair a new one. The frame shows the
	 * family at once with its last 50 posts; no questions. A frame someone else manages waits for an admin.
	 */
	let { open = $bindable(false), feed }: { open?: boolean; feed: Pick<FeedSummary, 'id' | 'name'> } = $props();

	let busy = $state<string | null>(null);
	const shown = $derived(new Set((directory.frames[feed.id] ?? []).map((f) => f.id)));
	const mine = $derived(frames.list.filter((f) => can(f, 'change_settings') && !shown.has(f.id)));

	$effect(() => {
		if (open) {
			void frames.load();
			void directory.load(feed.id);
		}
	});

	async function add(frameId: string, name: string) {
		busy = frameId;
		try {
			const sub = await addFrameToFamily(feed.id, frameId);
			open = false;
			if (sub.status === 'pending') toasts.show(t('family.addPending', { frame: name }));
			else toasts.success(t('family.added', { frame: name, family: feed.name }));
		} catch (e) {
			toasts.error(isCode(e, 'already_subscribed') ? t('family.alreadyShows', { frame: name }) : problemMessage(e));
		} finally {
			busy = null;
		}
	}
</script>

<Dialog bind:open title={t('family.addFrame')}>
	<div class="stack">
		{#if mine.length > 0}
			<p class="muted small">{t('family.addFrameBody', { family: feed.name })}</p>
			<ul class="choices" role="list">
				{#each mine as f (f.id)}
					<li>
						<button class="choice" onclick={() => add(f.id, f.name)} disabled={busy !== null}>
							<span class="icon"><Icon name="frame" size={20} /></span>
							<span class="body"><span class="name">{f.name}</span><OnlineStatus online={f.online} lastSeen={f.last_seen_at} /></span>
							{#if busy === f.id}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="plus" size={18} />{/if}
						</button>
					</li>
				{/each}
			</ul>
		{:else}
			<p class="muted small">{t('family.addFrameNone')}</p>
		{/if}
		<button class="btn btn-block" onclick={() => ((open = false), goto(`/frames/add?family=${feed.id}`))}>
			<Icon name="link" size={18} />{t('family.pairNew')}
		</button>
	</div>
</Dialog>

<style>
	.choices {
		list-style: none;
		margin: 0;
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
	.icon {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.body {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.name {
		font-weight: 650;
	}
</style>
