<script lang="ts">
	import { api, call, type Frame, type Placement } from '$lib/api/client';
	import { todayMonthDay, yearsAgo } from '$lib/parity';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	let { frame, onopen }: { frame: Frame; onopen: (p: Placement) => void } = $props();

	let memories = $state<Placement[]>([]);

	$effect(() => {
		const frameId = frame.id;
		let alive = true;
		call(
			api.GET('/v1/frames/{frameId}/placements', {
				params: { path: { frameId }, query: { on_this_day: todayMonthDay(), sort: 'taken_desc', limit: 24 } }
			})
		)
			.then((r) => alive && (memories = r.placements))
			.catch(() => alive && (memories = [])); // memories are a bonus; stay quiet on errors
		return () => {
			alive = false;
		};
	});

	const today = $derived(new Intl.DateTimeFormat(i18n.tag, { day: 'numeric', month: 'long' }).format(new Date()));
</script>

{#if memories.length > 0}
	<section class="memories card" aria-labelledby="mem-title-{frame.id}" in:softFly>
		<div class="m-head">
			<span class="m-icon"><Icon name="sparkle" size={18} /></span>
			<div class="m-text">
				<h2 id="mem-title-{frame.id}">{t('memories.title')}</h2>
				<p class="muted small">{tn('memories.subtitle', memories.length, { date: today })}</p>
			</div>
		</div>
		<ul class="strip" role="list">
			{#each memories as p (p.id)}
				{@const years = yearsAgo(p.item.captured_at)}
				<li>
					<button class="m-tile" onclick={() => onopen(p)} aria-label={tn('memories.yearsAgo', years) + (p.item.caption ? ` · ${p.item.caption}` : '')}>
						<Thumb itemId={p.item.id} variant="thumb_256" kind={p.item.kind} radius="12px" />
						<span class="years num">{tn('memories.yearsAgo', years)}</span>
					</button>
				</li>
			{/each}
		</ul>
	</section>
{/if}

<style>
	.memories {
		padding: var(--s-4);
		margin-bottom: var(--s-4);
		background: linear-gradient(135deg, color-mix(in srgb, var(--accent-soft) 70%, var(--surface)), var(--surface));
		min-width: 0;
	}
	.m-head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		margin-bottom: var(--s-3);
	}
	.m-icon {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent);
		flex: none;
	}
	.m-text {
		min-width: 0;
	}
	h2 {
		font-size: 1.05rem;
	}
	.strip {
		list-style: none;
		margin: 0;
		padding: 0 0 4px;
		display: flex;
		gap: 10px;
		overflow-x: auto;
		scroll-snap-type: x proximity;
		overscroll-behavior-x: contain;
	}
	.strip li {
		flex: none;
		scroll-snap-align: start;
	}
	.m-tile {
		all: unset;
		position: relative;
		display: block;
		width: 112px;
		height: 112px;
		border-radius: 12px;
		overflow: hidden;
		cursor: pointer;
	}
	.m-tile:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.years {
		position: absolute;
		left: 6px;
		bottom: 6px;
		padding: 2px 8px;
		border-radius: var(--r-full);
		background: rgb(20 14 10 / 0.65);
		color: #fff;
		font-size: 0.72rem;
		font-weight: 700;
	}
</style>
