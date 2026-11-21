<script lang="ts">
	import type { FrameSummary } from '$lib/api/client';
	import { DROP_FRAME_ATTR } from '$lib/dropfiles';
	import { shows } from '$lib/family.svelte';
	import AddFamilySheet from './family/AddFamilySheet.svelte';
	import { framePhotoUrl, reactedToday, showingNow } from '$lib/frameFace';
	import { can } from '$lib/frames.svelte';
	import { relativeTime } from '$lib/format';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';

	/**
	 * A frame card (simple-app.md §3.3): the photo it shows now (or the last placed one), online or offline, its name,
	 * the family it shows and today's reactions. On the list it opens the frame; on the frame's own page
	 * (`link = false`) it is the header, with the larger display rendition.
	 */
	let {
		frame,
		link = true,
		dragging = false,
		dropOver = false
	}: {
		frame: FrameSummary;
		link?: boolean;
		/** Files are being dragged over the page: show whether this card takes them. */
		dragging?: boolean;
		/** The dragged files are right over this card. */
		dropOver?: boolean;
	} = $props();

	// A calm, per-frame tint for frames without a photo yet.
	const TINTS = [
		['#f6d9c2', '#e9b38f'],
		['#dbe8d6', '#a9c7a1'],
		['#f3e3bd', '#e2c27a'],
		['#dde3ef', '#a8b6d4'],
		['#efd9e0', '#d6a7b6'],
		['#d8e9e7', '#97c3bd']
	];
	const tint = $derived(TINTS[[...frame.id].reduce((a, c) => a + c.charCodeAt(0), 0) % TINTS.length]!);
	const sendable = $derived(can(frame, 'send'));
	const manager = $derived(can(frame, 'change_settings'));

	const photo = $derived(framePhotoUrl(frame, !link));
	let photoFailed = $state(false);
	$effect(() => {
		void photo;
		photoFailed = false;
	});
	const now = $derived(showingNow(frame));
	const reacted = $derived(reactedToday(frame));
	$effect(() => void shows.load(frame.id));
	const subs = $derived(shows.byFrame[frame.id]);
	const showing = $derived((subs ?? []).filter((s) => s.status !== 'pending'));
	const waiting = $derived((subs ?? []).filter((s) => s.status === 'pending'));
	let addOpen = $state(false);
</script>

<article
	class="card frame-card"
	class:static={!link}
	class:drop-ready={dragging && sendable}
	class:drop-over={dragging && sendable && dropOver}
	class:drop-off={dragging && !sendable}
	{...sendable ? { [DROP_FRAME_ATTR]: frame.id } : {}}
>
	<svelte:element this={link ? 'a' : 'div'} href={link ? `/frames/${frame.id}` : undefined} class="cover" style="--a:{tint[0]}; --b:{tint[1]}" aria-label={link ? t('frames.open', { name: frame.name }) : undefined}>
		{#if photo && !photoFailed}
			<img class="photo" src={photo} alt="" draggable="false" onerror={() => (photoFailed = true)} />
		{:else}
			<span class="no-photo" aria-hidden="true"><Icon name="frame" size={34} /></span>
		{/if}
		<span class="chip" class:on={frame.online}>
			<span class="dot" class:dot-online={frame.online} aria-hidden="true"></span>
			{#if now}{t('frameCard.showingNow')}{:else if frame.online}{t('frame.online')}{:else if frame.last_seen_at}{t('frameCard.offlineSince', { when: relativeTime(frame.last_seen_at, i18n.tag) })}{:else}{t('frame.offline')}{/if}
		</span>
	</svelte:element>
	<div class="body">
		<div class="title-row">
			{#if link}<h2><a href="/frames/{frame.id}">{frame.name}</a></h2>{:else}<h1>{frame.name}</h1>{/if}
			{#if frame.device_kind === 'browser'}<span class="badge browser" title={t('frames.browserBadge')}><Icon name="globe" size={13} />{t('frames.browserBadge')}</span>{/if}
		</div>
		{#if frame.location}<p class="loc"><Icon name="mapPin" size={15} />{frame.location}</p>{/if}
		{#if subs}
			{#if showing.length > 0}
				<p class="shows"><Icon name="users" size={15} />{showing.length === 1 ? t('frameCard.shows', { family: showing[0]!.feed.name }) : tn('frameCard.showsMore', showing.length - 1, { family: showing[0]!.feed.name })}</p>
			{:else if waiting.length > 0}
				<p class="shows muted"><Icon name="clock" size={15} />{t('family.waitingAdmin')}</p>
			{:else}
				<p class="shows muted"><Icon name="users" size={15} />{t('frameCard.noFamily')}</p>
				{#if manager}
					<button class="btn btn-soft btn-sm add" onclick={() => (addOpen = true)}><Icon name="plus" size={16} />{t('frameCard.addFamily')}</button>
				{/if}
			{/if}
		{/if}
		{#if reacted !== null}
			<p class="reacted" data-testid="reactions-today"><Icon name="heart" size={15} />{tn('frameCard.reactedToday', reacted, { frame: frame.name })}</p>
		{/if}
		{#if frame.needs_update}
			<p class="update"><Icon name="info" size={15} />{t('frames.needsUpdate')}</p>
		{/if}
	</div>
	{#if dragging && sendable && dropOver}
		<!-- The page announces drop targets in one live region; this is the visual twin. -->
		<div class="drop-label" aria-hidden="true">
			<Icon name="upload" size={28} />
			<span>{t('frames.dropTo', { name: frame.name })}</span>
		</div>
	{/if}
</article>

{#if manager}<AddFamilySheet bind:open={addOpen} frameId={frame.id} frameName={frame.name} />{/if}

<style>
	.badge.browser {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 2px 8px;
		border-radius: var(--r-full, 999px);
		background: var(--surface-2);
		color: var(--text-muted);
		font-size: 0.75rem;
		font-weight: 650;
		white-space: nowrap;
	}
	.frame-card {
		position: relative;
		overflow: hidden;
		height: 100%;
		outline: 2px dashed transparent;
		outline-offset: 3px;
		display: flex;
		flex-direction: column;
		transition:
			box-shadow var(--dur) var(--ease),
			transform var(--dur) var(--ease);
	}
	.frame-card:hover {
		box-shadow: var(--shadow);
		transform: translateY(-2px);
	}
	.frame-card.drop-ready {
		outline-color: color-mix(in srgb, var(--primary) 55%, transparent);
	}
	.frame-card.drop-over {
		outline: 3px solid var(--primary);
		box-shadow: var(--shadow-lg);
		transform: translateY(-3px) scale(1.01);
	}
	.frame-card.drop-off {
		opacity: 0.55;
	}
	.drop-label {
		position: absolute;
		inset: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 10px;
		padding: var(--s-4);
		text-align: center;
		font-weight: 650;
		font-size: 1.05rem;
		color: var(--primary);
		background: color-mix(in srgb, var(--surface) 86%, transparent);
		backdrop-filter: blur(2px);
		pointer-events: none;
	}
	.cover {
		position: relative;
		display: grid;
		place-items: center;
		aspect-ratio: 16 / 10;
		overflow: hidden;
		background: radial-gradient(120% 120% at 20% 0%, var(--a), var(--b));
		border-radius: 0;
		color: rgb(47 40 35 / 0.6);
	}
	.cover :global(img),
	.cover :global(.thumb) {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.static:hover {
		transform: none;
		box-shadow: var(--shadow-sm);
	}
	.chip {
		position: absolute;
		top: 10px;
		inset-inline-start: 10px;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 3px 10px;
		border-radius: var(--r-full);
		background: rgb(255 255 255 / 0.9);
		color: #5a4d43;
		font-size: 0.75rem;
		font-weight: 700;
	}
	.chip.on {
		color: #2f5a3c;
	}
	.shows {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		font-size: 0.9rem;
	}
	.add {
		align-self: flex-start;
	}
	.reacted {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		font-size: 0.9rem;
		color: var(--primary);
		font-weight: 600;
	}
	h1 {
		font-size: 1.6rem;
		margin: 0;
		overflow-wrap: anywhere;
	}
	.body {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: 6px;
		flex: 1;
	}
	.title-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-2);
	}
	h2 {
		font-size: 1.2rem;
		min-width: 0;
	}
	h2 a {
		color: inherit;
		text-decoration: none;
		overflow-wrap: anywhere;
	}
	h2 a:hover {
		text-decoration: underline;
	}
	.loc {
		display: flex;
		align-items: center;
		gap: 5px;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.update {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 0.82rem;
		color: var(--warn);
	}
</style>
