<script lang="ts">
	import type { FrameSummary } from '$lib/api/client';
	import { DROP_FRAME_ATTR } from '$lib/dropfiles';
	import { can } from '$lib/frames.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';
	import OnlineStatus from './OnlineStatus.svelte';
	import RoleBadge from './RoleBadge.svelte';

	let {
		frame,
		dragging = false,
		dropOver = false
	}: {
		frame: FrameSummary;
		/** Files are being dragged over the page: show whether this card takes them. */
		dragging?: boolean;
		/** The dragged files are right over this card. */
		dropOver?: boolean;
	} = $props();

	// A calm, per-frame tint so frames are recognisable at a glance.
	const TINTS = [
		['#f6d9c2', '#e9b38f'],
		['#dbe8d6', '#a9c7a1'],
		['#f3e3bd', '#e2c27a'],
		['#dde3ef', '#a8b6d4'],
		['#efd9e0', '#d6a7b6'],
		['#d8e9e7', '#97c3bd']
	];
	const tint = $derived(TINTS[[...frame.id].reduce((a, c) => a + c.charCodeAt(0), 0) % TINTS.length]!);
	const portrait = $derived(frame.screen?.orientation === 'portrait');
	const sendable = $derived(can(frame, 'send'));
</script>

<article
	class="card frame-card"
	class:drop-ready={dragging && sendable}
	class:drop-over={dragging && sendable && dropOver}
	class:drop-off={dragging && !sendable}
	{...sendable ? { [DROP_FRAME_ATTR]: frame.id } : {}}
>
	<a href="/frames/{frame.id}" class="cover" style="--a:{tint[0]}; --b:{tint[1]}" aria-label={t('frames.open', { name: frame.name })}>
		<span class="mini-frame" class:portrait>
			<span class="mini-screen">
				<span class="sun"></span>
				<span class="hill"></span>
			</span>
		</span>
		{#if frame.online}<span class="live-badge"><span class="dot dot-online"></span>{t('frame.online')}</span>{/if}
	</a>
	<div class="body">
		<div class="title-row">
			<h2><a href="/frames/{frame.id}">{frame.name}</a></h2>
			{#if frame.device_kind === 'browser'}<span class="badge browser" title={t('frames.browserBadge')}><Icon name="globe" size={13} />{t('frames.browserBadge')}</span>{/if}
			<RoleBadge role={frame.role} />
		</div>
		{#if frame.location}
			<p class="loc"><Icon name="mapPin" size={15} />{frame.location}</p>
		{/if}
		<div class="meta">
			<OnlineStatus online={frame.online} lastSeen={frame.last_seen_at} />
			{#if can(frame, 'send')}
				<a class="btn btn-ghost btn-sm" href="/send?frame={frame.id}">
					<Icon name="send" size={16} />{t('nav.send')}
				</a>
			{/if}
		</div>
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
		aspect-ratio: 16 / 8;
		background: radial-gradient(120% 120% at 20% 0%, var(--a), var(--b));
		border-radius: 0;
	}
	@media (max-width: 520px) {
		.cover {
			aspect-ratio: 16 / 6.5;
		}
	}
	@media (prefers-color-scheme: dark) {
		.cover {
			filter: saturate(0.8) brightness(0.72);
		}
	}
	.mini-frame {
		width: 42%;
		aspect-ratio: 16 / 10;
		padding: 5px;
		border-radius: 8px;
		background: #2f2823;
		box-shadow: 0 10px 22px -10px rgb(40 20 0 / 0.5);
		transform: rotate(-2deg);
	}
	.mini-frame.portrait {
		width: 22%;
		aspect-ratio: 10 / 16;
	}
	.mini-screen {
		position: relative;
		display: block;
		width: 100%;
		height: 100%;
		overflow: hidden;
		border-radius: 3px;
		background: linear-gradient(#fde7cf, #f3c9a3);
	}
	.sun {
		position: absolute;
		inset-inline-end: 18%;
		top: 18%;
		width: 16%;
		aspect-ratio: 1;
		border-radius: 50%;
		background: #e7a23a;
	}
	.hill {
		position: absolute;
		left: -10%;
		right: -10%;
		bottom: -40%;
		height: 80%;
		border-radius: 50%;
		background: #4f7a5a;
	}
	.live-badge {
		position: absolute;
		top: 10px;
		inset-inline-end: 10px;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 3px 10px;
		border-radius: var(--r-full);
		background: rgb(255 255 255 / 0.85);
		color: #2f5a3c;
		font-size: 0.75rem;
		font-weight: 700;
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
	.meta {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: auto;
		padding-top: var(--s-2);
	}
	.update {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 0.82rem;
		color: var(--warn);
	}
</style>
