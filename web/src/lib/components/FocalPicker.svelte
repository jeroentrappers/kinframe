<script lang="ts">
	import { clampFocal, type Focal } from '$lib/logic';
	import { t } from '$lib/i18n/index.svelte';

	let {
		src,
		kind = 'photo',
		focal = $bindable({ x: 0.5, y: 0.5 }),
		alt
	}: { src: string; kind?: 'photo' | 'video'; focal?: Focal; alt: string } = $props();

	let media: HTMLElement | undefined = $state();

	function setFromPointer(e: PointerEvent) {
		if (!media) return;
		const r = media.getBoundingClientRect();
		focal = clampFocal((e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height);
	}

	function onKey(e: KeyboardEvent) {
		const step = e.shiftKey ? 0.1 : 0.02;
		const d: Record<string, [number, number]> = {
			ArrowLeft: [-step, 0],
			ArrowRight: [step, 0],
			ArrowUp: [0, -step],
			ArrowDown: [0, step]
		};
		const delta = d[e.key];
		if (delta) {
			e.preventDefault();
			focal = clampFocal(focal.x + delta[0], focal.y + delta[1]);
		} else if (e.key === 'Home') {
			e.preventDefault();
			focal = { x: 0.5, y: 0.5 };
		}
	}

	let dragging = false;
</script>

<div class="picker">
	<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
	<div
		class="stage"
		role="slider"
		tabindex="0"
		aria-label={t('send.focalLabel')}
		aria-valuetext={t('send.focalValue', { x: Math.round(focal.x * 100), y: Math.round(focal.y * 100) })}
		aria-valuenow={Math.round(focal.x * 100)}
		aria-valuemin={0}
		aria-valuemax={100}
		onkeydown={onKey}
		onpointerdown={(e) => {
			dragging = true;
			(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
			setFromPointer(e);
		}}
		onpointermove={(e) => dragging && setFromPointer(e)}
		onpointerup={() => (dragging = false)}
		onpointercancel={() => (dragging = false)}
	>
		<div class="media" bind:this={media}>
			{#if kind === 'video'}
				<video src="{src}#t=0.1" muted playsinline preload="metadata" aria-label={alt}></video>
			{:else}
				<img {src} {alt} draggable="false" />
			{/if}
			<span class="marker" style="left:{focal.x * 100}%; top:{focal.y * 100}%" aria-hidden="true"></span>
		</div>
	</div>
	<p class="hint">{t('send.focalHint')}</p>
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.stage {
		display: grid;
		place-items: center;
		background: repeating-conic-gradient(var(--surface-2) 0% 25%, var(--surface-3) 0% 50%) 50% / 16px 16px;
		border-radius: var(--r);
		padding: 8px;
		cursor: crosshair;
		touch-action: none;
		user-select: none;
	}
	.media {
		position: relative;
		line-height: 0;
		overflow: hidden; /* clips the marker's dimming shadow to the photo */
		border-radius: 6px;
	}
	img,
	video {
		max-width: 100%;
		max-height: 340px;
		border-radius: 6px;
		pointer-events: none;
	}
	.marker {
		position: absolute;
		width: 34px;
		height: 34px;
		margin: -17px 0 0 -17px;
		border-radius: 50%;
		border: 3px solid #fff;
		box-shadow:
			0 0 0 2px rgb(0 0 0 / 0.35),
			0 0 0 9999px rgb(0 0 0 / 0.08);
		transition:
			left 90ms var(--ease),
			top 90ms var(--ease);
	}
	.marker::after {
		content: '';
		position: absolute;
		inset: 11px;
		border-radius: 50%;
		background: #fff;
	}
</style>
