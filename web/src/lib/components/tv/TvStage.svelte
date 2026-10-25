<script lang="ts">
	// The full-screen slide renderer shared by the TV mirror and the browser frame: the same
	// transitions as the frame (cross-fade, slide, cut), Ken Burns with the frame's motion,
	// fill on the focal point or the whole photo over a blurred backdrop, collages, and
	// videos that can follow someone else's playback position.
	import { untrack } from 'svelte';
	import { kenBurnsKeyframes } from '$lib/kenburns';
	import { reducedMotion } from '$lib/motion';
	import { collageClass, remainingTransition, tileObjectPosition, videoPositionNow, type StageSlide, type StageVideo } from '$lib/tvstage';

	let {
		slide,
		background = 'blur',
		muted = true,
		volume = 0.6,
		loop = false,
		onvideoended
	}: {
		slide: StageSlide | null;
		background?: 'blur' | 'black' | 'palette';
		muted?: boolean;
		volume?: number;
		loop?: boolean;
		onvideoended?: () => void;
	} = $props();

	let w = $state(1920);
	let h = $state(1080);
	const aspect = $derived(w > 0 && h > 0 ? w / h : 16 / 9);

	/** The slide fading or sliding out stays until the new one is in. */
	let layers = $state<{ slide: StageSlide; out: boolean }[]>([]);
	let dropTimer: ReturnType<typeof setTimeout> | null = null;

	$effect(() => {
		const s = slide;
		untrack(() => {
			const top = layers[layers.length - 1];
			if (!s) {
				layers = [];
				return;
			}
			if (top?.slide.key === s.key) {
				// Same slide, new details (caption, reaction, video position): update in place.
				if (top.slide !== s) layers = layers.map((l, i) => (i === layers.length - 1 ? { slide: s, out: false } : l));
				return;
			}
			const ms = reducedMotion() ? 0 : remainingTransition(s.transitionMs, Date.now() - s.startedAt);
			layers = [...(top ? [{ slide: top.slide, out: true }] : []), { slide: s, out: false }];
			if (dropTimer) clearTimeout(dropTimer);
			dropTimer = setTimeout(() => (layers = layers.filter((l) => !l.out)), ms + 50);
		});
	});

	/** Animates a layer in (on mount) and out (when it becomes the outgoing one), Web Animations: cheap on TV browsers. */
	function transition(node: HTMLElement, p: { slide: StageSlide; out: boolean; incoming?: StageSlide }) {
		const run = (s: StageSlide, out: boolean) => {
			const ms = reducedMotion() ? 0 : remainingTransition(s.transitionMs, Date.now() - s.startedAt);
			if (ms <= 0 || s.transition === 'cut') return;
			if (s.transition === 'slide') {
				const frames = out
					? [{ transform: 'translateX(0)' }, { transform: `translateX(${-s.direction * 100}%)` }]
					: [{ transform: `translateX(${s.direction * 100}%)` }, { transform: 'translateX(0)' }];
				node.animate(frames, { duration: ms, easing: 'cubic-bezier(.2,.7,.2,1)', fill: 'both' });
			} else if (!out) {
				// Cross-fade: the new slide fades in over the old one, which stays until it is covered.
				node.animate([{ opacity: 0 }, { opacity: 1 }], { duration: ms, easing: 'linear', fill: 'both' });
			}
		};
		let out = p.out;
		if (!out) run(p.slide, false);
		return {
			update(np: { slide: StageSlide; out: boolean; incoming?: StageSlide }) {
				if (np.out && !out && np.incoming) run(np.incoming, true);
				out = np.out;
			}
		};
	}

	/** Ken Burns from the moment the slide appeared, so a late TV joins the motion where the frame is. */
	function kenBurns(node: HTMLElement, s: StageSlide) {
		if (!s.kenBurns || reducedMotion()) return {};
		const duration = s.holdMs + 15_000;
		const anim = node.animate(kenBurnsKeyframes(s.kenBurns, duration), { duration, easing: 'linear', fill: 'forwards' });
		anim.currentTime = Math.max(0, Date.now() - s.startedAt);
		return { destroy: () => anim.cancel() };
	}

	/** Plays a video, following a reported position when there is one. */
	function follow(node: HTMLVideoElement, v: StageVideo | null | undefined) {
		let current = v;
		const sync = () => {
			if (!current) return;
			const want = videoPositionNow(current, Date.now()) / 1000;
			if (Number.isFinite(node.duration) && want > node.duration) return;
			if (Math.abs(node.currentTime - want) > 1.5) node.currentTime = want;
			if (current.playing && node.paused) void node.play().catch(() => {});
			if (!current.playing && !node.paused) node.pause();
		};
		node.addEventListener('loadedmetadata', sync);
		if (!current) void node.play().catch(() => {});
		else sync();
		return {
			update(next: StageVideo | null | undefined) {
				current = next;
				sync();
			},
			destroy() {
				node.removeEventListener('loadedmetadata', sync);
			}
		};
	}

	function volumeOf(node: HTMLVideoElement, v: number) {
		node.volume = Math.min(1, Math.max(0, v));
		return { update: (nv: number) => (node.volume = Math.min(1, Math.max(0, nv))) };
	}

	const tileAspect = (n: number, i: number) => {
		// Box aspect of a tile in the collage layouts below.
		if (n <= 1) return aspect;
		if (n === 2) return aspect / 2;
		if (n === 3) return i === 0 ? aspect / 2 : aspect;
		if (n === 4) return aspect;
		return (aspect * 2) / 3;
	};
</script>

<div class="stage" class:black={background === 'black'} bind:clientWidth={w} bind:clientHeight={h}>
	{#each layers as l (l.slide.key)}
		{@const s = l.slide}
		{@const n = s.tiles.length}
		<div class="layer" class:out={l.out} use:transition={{ slide: s, out: l.out, incoming: layers[layers.length - 1]?.slide }}>
			<div class="tiles {collageClass(n)}" use:kenBurns={s}>
				{#each s.tiles as t, i (t.id)}
					{@const fill = t.fit === 'fill' && t.rotation % 180 === 0}
					<div class="tile r{t.rotation}">
						{#if !fill && background !== 'black' && n === 1}
							<img class="backdrop" src={t.kind === 'video' ? (t.poster ?? '') : t.src} alt="" aria-hidden="true" />
						{/if}
						{#if t.kind === 'video'}
							<!-- svelte-ignore a11y_media_has_caption -->
							<video
								class="media"
								class:cover={fill}
								src={t.src}
								poster={t.poster ?? undefined}
								playsinline
								{muted}
								{loop}
								preload="auto"
								style:object-position={tileObjectPosition(t, tileAspect(n, i))}
								use:follow={l.out ? null : s.video}
								use:volumeOf={volume}
								onended={() => !l.out && onvideoended?.()}
							></video>
						{:else}
							<img class="media" class:cover={fill || n > 1} src={t.src} alt="" style:object-position={tileObjectPosition(t, tileAspect(n, i))} />
						{/if}
					</div>
				{/each}
			</div>
		</div>
	{/each}
</div>

<style>
	.stage {
		position: absolute;
		inset: 0;
		overflow: hidden;
		background: #0b0a09;
	}
	.layer {
		position: absolute;
		inset: 0;
		will-change: opacity, transform;
	}
	.layer.out {
		z-index: 0;
	}
	.layer:not(.out) {
		z-index: 1;
	}
	.tiles {
		position: absolute;
		inset: 0;
		display: grid;
		gap: 6px;
		transform-origin: 50% 50%;
	}
	.tiles.one {
		gap: 0;
	}
	.tiles.two {
		grid-template-columns: 1fr 1fr;
	}
	.tiles.three {
		grid-template-columns: 1fr 1fr;
		grid-template-rows: 1fr 1fr;
	}
	.tiles.three .tile:first-child {
		grid-row: span 2;
	}
	.tiles.four {
		grid-template-columns: 1fr 1fr;
		grid-template-rows: 1fr 1fr;
	}
	.tiles.many {
		grid-template-columns: 1fr 1fr 1fr;
		grid-template-rows: 1fr 1fr;
	}
	.tile {
		position: relative;
		overflow: hidden;
		container-type: size;
		min-width: 0;
		min-height: 0;
	}
	.backdrop {
		position: absolute;
		inset: -40px;
		width: calc(100% + 80px);
		height: calc(100% + 80px);
		object-fit: cover;
		filter: blur(36px) brightness(0.55) saturate(1.1);
	}
	.media {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: contain;
	}
	.media.cover {
		object-fit: cover;
	}
	/* A photo turned on the frame: swap the box, then rotate it into place. */
	.r90 .media,
	.r270 .media {
		inset: auto;
		left: 50%;
		top: 50%;
		width: 100cqh;
		height: 100cqw;
		transform: translate(-50%, -50%) rotate(90deg);
	}
	.r270 .media {
		transform: translate(-50%, -50%) rotate(270deg);
	}
	.r180 .media {
		transform: rotate(180deg);
	}
	.black .backdrop {
		display: none;
	}
</style>
