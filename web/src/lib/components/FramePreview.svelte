<script lang="ts">
	import { boxAspectOf, cropStyle, type FramingView } from '$lib/framing';
	import { coverPosition, type Focal } from '$lib/logic';
	import Icon from './Icon.svelte';

	let {
		src,
		kind = 'photo',
		imageAspect,
		focal,
		fit,
		screenAspect = 16 / 10,
		orientation = 'landscape',
		label,
		view = null,
		onedit,
		editLabel
	}: {
		src: string;
		kind?: 'photo' | 'video';
		imageAspect: number;
		focal: Focal;
		fit: 'fill' | 'fit';
		screenAspect?: number;
		orientation?: 'landscape' | 'portrait';
		label: string;
		/** This orientation's framing view (docs/spec/framing.md §4: the preview shows the §3 result). */
		view?: FramingView | null;
		/** Set when the preview opens the framing editor. */
		onedit?: () => void;
		editLabel?: string;
	} = $props();

	const boxAspect = $derived(boxAspectOf(orientation, screenAspect));
	const centre = $derived(view ? { x: view.x, y: view.y } : focal);
	const pos = $derived(coverPosition(centre, imageAspect || 1, boxAspect));
	// Zoomed in: a positioned, scaled image inside the screen (object-view-box isn't everywhere).
	const crop = $derived(kind === 'photo' && fit === 'fill' && view && view.zoom > 1.0005 ? cropStyle(imageAspect || 1, 1, boxAspect, view) : null);
</script>

{#snippet bezel()}
	<div class="bezel">
		<div class="screen" style="aspect-ratio:{boxAspect}">
			{#if kind === 'video'}
				{#if fit === 'fit'}
					<video class="bg" src="{src}#t=0.1" muted playsinline preload="metadata" aria-hidden="true"></video>
				{/if}
				<video
					class="fg"
					class:contain={fit === 'fit'}
					src="{src}#t=0.1"
					muted
					playsinline
					preload="metadata"
					style="object-position:{pos.x}% {pos.y}%"
					aria-hidden="true"
				></video>
			{:else if crop}
				<img class="crop" {src} alt="" style={crop} draggable="false" />
			{:else}
				{#if fit === 'fit'}
					<img class="bg" {src} alt="" aria-hidden="true" />
				{/if}
				<img class="fg" class:contain={fit === 'fit'} {src} alt="" style="object-position:{pos.x}% {pos.y}%" draggable="false" />
			{/if}
		</div>
	</div>
{/snippet}

<figure class="preview {orientation}">
	{#if onedit}
		<button type="button" class="edit" onclick={onedit} aria-label={editLabel ?? label}>
			{@render bezel()}
			<span class="edit-badge" aria-hidden="true"><Icon name="crop" size={14} /></span>
		</button>
	{:else}
		{@render bezel()}
	{/if}
	<figcaption>{label}</figcaption>
</figure>

<style>
	.preview {
		margin: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
	}
	.landscape {
		width: 100%;
	}
	.portrait {
		width: 58%;
	}
	.bezel {
		width: 100%;
		padding: 7px;
		border-radius: 12px;
		background: linear-gradient(160deg, #3b332d, #1f1a16);
		box-shadow: var(--shadow);
	}
	.screen {
		position: relative;
		width: 100%;
		overflow: hidden;
		border-radius: 4px;
		background: #000;
	}
	.fg,
	.bg {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.fg.contain {
		object-fit: contain;
		object-position: 50% 50% !important;
	}
	.crop {
		position: absolute;
		max-width: none;
		max-height: none;
	}
	.edit {
		position: relative;
		display: block;
		width: 100%;
		padding: 0;
		border: 0;
		background: none;
		border-radius: 12px;
		cursor: zoom-in;
	}
	.edit:focus-visible {
		outline: 3px solid var(--focus, var(--accent));
		outline-offset: 2px;
	}
	.edit-badge {
		position: absolute;
		right: 12px;
		bottom: 12px;
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border-radius: 50%;
		background: rgb(0 0 0 / 0.55);
		color: #fff;
	}
	.bg {
		filter: blur(14px) brightness(0.75);
		transform: scale(1.15);
	}
	figcaption {
		font-size: 0.78rem;
		color: var(--text-muted);
		font-weight: 600;
	}
</style>
