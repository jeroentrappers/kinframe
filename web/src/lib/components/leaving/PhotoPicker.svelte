<script lang="ts">
	import type { Item } from '$lib/api/client';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	/** A grid of thumbnails to pick from (asking to keep, allowing some). */
	let { items, picked = $bindable(new Set<string>()), label }: { items: Item[]; picked?: Set<string>; label: string } = $props();

	function toggle(id: string) {
		const next = new Set(picked);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		picked = next;
	}
</script>

<div class="picker">
	<p class="hint num" aria-live="polite">{t('leaving.pickCount', { n: picked.size, total: items.length })}</p>
	<ul class="grid" role="list" aria-label={label}>
		{#each items as it, i (it.id)}
			<li>
				<label class="tile" class:on={picked.has(it.id)}>
					<input type="checkbox" class="visually-hidden" checked={picked.has(it.id)} onchange={() => toggle(it.id)} aria-label={it.caption ?? `${i + 1}`} />
					<Thumb itemId={it.id} variant="thumb_256" kind={it.kind} radius="var(--r-sm)" />
					<span class="mark" aria-hidden="true">{#if picked.has(it.id)}<Icon name="check" size={14} stroke={3} />{/if}</span>
				</label>
			</li>
		{/each}
	</ul>
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(72px, 1fr));
		gap: 6px;
		max-height: 300px;
		overflow: auto;
	}
	.tile {
		position: relative;
		display: block;
		aspect-ratio: 1;
		cursor: pointer;
		border-radius: var(--r-sm);
		outline: 2px solid transparent;
		outline-offset: 1px;
	}
	.tile.on {
		outline-color: var(--primary);
	}
	.tile:focus-within {
		outline-color: var(--focus, var(--primary));
	}
	.mark {
		position: absolute;
		top: 4px;
		inset-inline-end: 4px;
		width: 20px;
		height: 20px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: color-mix(in srgb, var(--surface) 80%, transparent);
		border: 1.5px solid var(--border);
	}
	.tile.on .mark {
		background: var(--primary);
		color: var(--on-primary);
		border-color: var(--primary);
	}
</style>
