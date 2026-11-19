<script lang="ts">
	import { discovery } from '$lib/discovery.svelte';
	import { canPostToFeed } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { can, frames } from '$lib/frames.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import Icon from '../Icon.svelte';
	import OnlineStatus from '../OnlineStatus.svelte';
	import AudiencePicker from './AudiencePicker.svelte';

	/**
	 * Pick frames and families for photos that already exist ("Put on a frame" from a chat, "Share again" from On this
	 * day). Families only where the caller may post, with a circle as audience.
	 */
	let {
		open = $bindable(false),
		title,
		intro,
		allowFeeds = true,
		submitLabel,
		preselectFrame,
		onsubmit
	}: {
		open?: boolean;
		title: string;
		intro?: string;
		allowFeeds?: boolean;
		submitLabel: string;
		preselectFrame?: string;
		onsubmit: (t: { frame_ids: string[]; feed_ids: string[]; circle_ids: string[] }) => Promise<void>;
	} = $props();

	let frameIds = $state<string[]>([]);
	let feedIds = $state<string[]>([]);
	let circleOf = $state<Record<string, string | null>>({});
	let busy = $state(false);

	const sendable = $derived(frames.list.filter((f) => can(f, 'send')));
	const postable = $derived(allowFeeds && discovery.feeds ? feeds.list.filter((f) => canPostToFeed(f.role)) : []);

	$effect(() => {
		if (!open) return;
		void frames.load();
		void feeds.load();
		frameIds = preselectFrame ? [preselectFrame] : [];
		feedIds = [];
		circleOf = {};
	});

	const toggle = (list: string[], id: string) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

	async function submit() {
		busy = true;
		try {
			const circle_ids = feedIds.map((f) => circleOf[f]).filter((c): c is string => !!c);
			await onsubmit({ frame_ids: frameIds, feed_ids: feedIds, circle_ids });
			open = false;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open {title}>
	{#if intro}<p class="muted intro">{intro}</p>{/if}
	{#if sendable.length === 0 && postable.length === 0}
		<p class="muted">{t('send.noFramesBody')}</p>
	{:else}
		<fieldset class="targets">
			<legend class="visually-hidden">{t('send.to')}</legend>
			{#if sendable.length && postable.length}<h3 class="grp">{t('send.framesGroup')}</h3>{/if}
			{#each sendable as f (f.id)}
				<label class="opt" class:checked={frameIds.includes(f.id)}>
					<input type="checkbox" checked={frameIds.includes(f.id)} onchange={() => (frameIds = toggle(frameIds, f.id))} />
					<span class="ic" aria-hidden="true"><Icon name="frame" size={18} /></span>
					<span class="body"><span class="nm">{f.name}</span><OnlineStatus online={f.online} lastSeen={f.last_seen_at} /></span>
					<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
				</label>
			{/each}
			{#if postable.length}
				<h3 class="grp">{t('send.feedsGroup')}</h3>
				{#each postable as fd (fd.id)}
					<label class="opt" class:checked={feedIds.includes(fd.id)}>
						<input type="checkbox" checked={feedIds.includes(fd.id)} onchange={() => (feedIds = toggle(feedIds, fd.id))} />
						<FeedCover name={fd.name} url={fd.cover_url} size={32} />
						<span class="body"><span class="nm">{fd.name}</span></span>
						<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
					</label>
					{#if discovery.social && feedIds.includes(fd.id)}
						<AudiencePicker feedId={fd.id} feedName={fd.name} bind:value={circleOf[fd.id]} />
					{/if}
				{/each}
			{/if}
		</fieldset>
	{/if}
	{#snippet footer()}
		<button class="btn" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" disabled={busy || frameIds.length + feedIds.length === 0} onclick={submit}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="send" size={18} />{/if}{submitLabel}
		</button>
	{/snippet}
</Dialog>

<style>
	.intro {
		margin: 0 0 var(--s-3);
	}
	.targets {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.grp {
		font-size: 0.8rem;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--text-muted);
		margin: var(--s-2) 0 2px;
	}
	.opt {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 8px 10px;
		border-radius: var(--r);
		border: 1px solid var(--border);
		cursor: pointer;
	}
	.opt.checked {
		border-color: var(--primary);
		background: var(--primary-soft);
	}
	.opt input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.opt:has(input:focus-visible) {
		outline: 3px solid var(--focus);
	}
	.ic {
		width: 32px;
		height: 32px;
		border-radius: 8px;
		display: grid;
		place-items: center;
		background: var(--surface-2);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.nm {
		font-weight: 600;
	}
	.check {
		color: var(--primary);
		visibility: hidden;
	}
	.opt.checked .check {
		visibility: visible;
	}
</style>
