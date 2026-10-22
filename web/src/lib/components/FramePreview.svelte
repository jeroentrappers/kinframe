<script lang="ts">
	import { coverPosition, type Focal } from '$lib/logic';

	let {
		src,
		kind = 'photo',
		imageAspect,
		focal,
		fit,
		screenAspect = 16 / 10,
		orientation = 'landscape',
		label
	}: {
		src: string;
		kind?: 'photo' | 'video';
		imageAspect: number;
		focal: Focal;
		fit: 'fill' | 'fit';
		screenAspect?: number;
		orientation?: 'landscape' | 'portrait';
		label: string;
	} = $props();

	const boxAspect = $derived(orientation === 'landscape' ? Math.max(screenAspect, 1 / screenAspect) : Math.min(screenAspect, 1 / screenAspect));
	const pos = $derived(coverPosition(focal, imageAspect || 1, boxAspect));
</script>

<figure class="preview {orientation}">
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
			{:else}
				{#if fit === 'fit'}
					<img class="bg" {src} alt="" aria-hidden="true" />
				{/if}
				<img class="fg" class:contain={fit === 'fit'} {src} alt="" style="object-position:{pos.x}% {pos.y}%" />
			{/if}
		</div>
	</div>
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
