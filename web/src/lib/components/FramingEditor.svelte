<script lang="ts">
	// The large framing editor (docs/spec/framing.md §4): the frame's shape as a window over the
	// photo. Drag moves it, the wheel, a pinch, the slider or +/− zoom it (1×..4×), and it can't
	// leave the photo (the clamp of §3). Done keeps the view, Reset removes it.
	import {
		boxAspectOf,
		clampView,
		cropRect,
		looksSoft,
		MAX_ZOOM,
		MIN_ZOOM,
		moveView,
		zoomView,
		type FramingView,
		type Orientation
	} from '$lib/framing';
	import type { Focal } from '$lib/logic';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from './Dialog.svelte';
	import Icon from './Icon.svelte';

	let {
		open = $bindable(false),
		src,
		orientation,
		label,
		screenAspect,
		imageAspect,
		width,
		height,
		current,
		focal,
		onsave
	}: {
		open?: boolean;
		src: string;
		orientation: Orientation;
		/** "Landscape" / "Portrait". */
		label: string;
		screenAspect: number;
		imageAspect: number;
		width: number | null;
		height: number | null;
		/** This orientation's view, or null (the focal point at 1×). */
		current: FramingView | null;
		focal: Focal;
		/** Done after a change: the new view, or null when it was reset. */
		onsave: (view: FramingView | null) => void;
	} = $props();

	const STEP = 0.1; // of the visible part (§4 accessibility)
	const ZOOM_STEP = 0.25;

	const box = $derived(boxAspectOf(orientation, screenAspect));
	const ia = $derived(imageAspect > 0 ? imageAspect : 4 / 3);
	const plain = $derived<FramingView>(clampView({ x: focal.x, y: focal.y, zoom: 1 }, ia, box));

	let view = $state<FramingView>({ x: 0.5, y: 0.5, zoom: 1 });
	/** Whether the person changed it, and whether the last change was Reset. */
	let changed = $state(false);
	let cleared = $state(false);

	let wasOpen = false;
	$effect(() => {
		if (open && !wasOpen) {
			view = current ? clampView(current, ia, box) : plain;
			changed = false;
			cleared = false;
		}
		wasOpen = open;
	});

	function set(v: FramingView) {
		view = v;
		changed = true;
		cleared = false;
	}

	const win = $derived.by(() => {
		const r = cropRect(ia, 1, box, 1, view);
		return { left: (r.left / ia) * 100, top: r.top * 100, width: (r.width / ia) * 100, height: r.height * 100 };
	});
	const soft = $derived(looksSoft(view, width, height, box));
	const zoomText = $derived(new Intl.NumberFormat(i18n.tag, { maximumFractionDigits: 1 }).format(view.zoom));
	const valueText = $derived(
		t('framing.value', { orientation: label, zoom: zoomText, x: Math.round(view.x * 100), y: Math.round(view.y * 100) })
	);

	const zoomBy = (d: number) => set(zoomView(view, view.zoom + d, ia, box));
	const move = (dx: number, dy: number) => set(moveView(view, dx, dy, ia, box));

	function reset() {
		view = plain;
		changed = true;
		cleared = true;
	}

	function done() {
		if (changed) onsave(cleared ? null : view);
		open = false;
	}

	function onKey(e: KeyboardEvent) {
		const moves: Record<string, [number, number]> = {
			ArrowLeft: [-STEP, 0],
			ArrowRight: [STEP, 0],
			ArrowUp: [0, -STEP],
			ArrowDown: [0, STEP]
		};
		const m = moves[e.key];
		if (m) move(m[0], m[1]);
		else if (e.key === '+' || e.key === '=') zoomBy(ZOOM_STEP);
		else if (e.key === '-' || e.key === '_') zoomBy(-ZOOM_STEP);
		else if (e.key === 'Home') reset();
		else return;
		e.preventDefault();
	}

	// ---- drag (one pointer) and pinch (two), from where the gesture started so clamping doesn't drift
	let photo: HTMLElement | undefined = $state();
	const pointers = new Map<number, { x: number; y: number }>();
	let gesture: { view: FramingView; cx: number; cy: number; dist: number } | null = null;

	function measure() {
		const pts = [...pointers.values()];
		const cx = pts.reduce((s, p) => s + p.x, 0) / pts.length;
		const cy = pts.reduce((s, p) => s + p.y, 0) / pts.length;
		const dist = pts.length > 1 ? Math.hypot(pts[0]!.x - pts[1]!.x, pts[0]!.y - pts[1]!.y) : 0;
		return { cx, cy, dist };
	}

	function begin() {
		gesture = pointers.size ? { view: { ...view }, ...measure() } : null;
	}

	function onDown(e: PointerEvent) {
		if (e.button !== 0 && e.pointerType === 'mouse') return;
		(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		begin();
	}

	function onMove(e: PointerEvent) {
		if (!pointers.has(e.pointerId) || !gesture || !photo) return;
		pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		const r = photo.getBoundingClientRect();
		if (!r.width || !r.height) return;
		const m = measure();
		const zoom = pointers.size > 1 && gesture.dist > 0 ? gesture.view.zoom * (m.dist / gesture.dist) : gesture.view.zoom;
		set(
			clampView(
				{ x: gesture.view.x + (m.cx - gesture.cx) / r.width, y: gesture.view.y + (m.cy - gesture.cy) / r.height, zoom },
				ia,
				box
			)
		);
	}

	function onUp(e: PointerEvent) {
		pointers.delete(e.pointerId);
		begin();
	}

	/** The wheel zooms; Svelte's own wheel handlers are passive, so this one is added by hand. */
	function wheel(node: HTMLElement) {
		const handler = (e: WheelEvent) => {
			e.preventDefault();
			const z = view.zoom * Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0015));
			set(zoomView(view, z, ia, box));
		};
		node.addEventListener('wheel', handler, { passive: false });
		return { destroy: () => node.removeEventListener('wheel', handler) };
	}
</script>

<Dialog bind:open title="{t('framing.title')} · {label}" size="md">
	<div class="framing">
		<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
		<div
			class="stage"
			role="slider"
			tabindex="0"
			aria-label={t('framing.window', { orientation: label })}
			aria-valuemin={MIN_ZOOM}
			aria-valuemax={MAX_ZOOM}
			aria-valuenow={view.zoom}
			aria-valuetext={valueText}
			aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown + - Home"
			onkeydown={onKey}
			onpointerdown={onDown}
			onpointermove={onMove}
			onpointerup={onUp}
			onpointercancel={onUp}
			use:wheel
		>
			<div class="photo" bind:this={photo} style="aspect-ratio:{ia}; width:min(100%, calc(min(56dvh, 520px) * {ia}))">
				<img {src} alt="" draggable="false" />
				<span
					class="window"
					aria-hidden="true"
					style="left:{win.left}%; top:{win.top}%; width:{win.width}%; height:{win.height}%"
				></span>
			</div>
		</div>

		<div class="zoom-row">
			<button type="button" class="btn btn-ghost btn-icon btn-sm" onclick={() => zoomBy(-ZOOM_STEP)} disabled={view.zoom <= MIN_ZOOM} aria-label={t('framing.zoomOut')}>
				<span class="sign" aria-hidden="true">−</span>
			</button>
			<input
				type="range"
				min={MIN_ZOOM}
				max={MAX_ZOOM}
				step="0.01"
				value={view.zoom}
				oninput={(e) => set(zoomView(view, Number(e.currentTarget.value), ia, box))}
				aria-label={t('framing.zoom')}
				aria-valuetext="{zoomText}×"
			/>
			<button type="button" class="btn btn-ghost btn-icon btn-sm" onclick={() => zoomBy(ZOOM_STEP)} disabled={view.zoom >= MAX_ZOOM} aria-label={t('framing.zoomIn')}>
				<Icon name="plus" size={18} />
			</button>
			<span class="num zoom-val" aria-hidden="true">{zoomText}×</span>
		</div>

		<p class="hint">{t('framing.hint')}</p>
		{#if soft}
			<p class="soft" role="status"><Icon name="info" size={16} />{t('framing.soft')}</p>
		{/if}
	</div>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={reset}><Icon name="refresh" size={16} />{t('framing.reset')}</button>
		<span class="spacer"></span>
		<button type="button" class="btn btn-primary" onclick={done}>{t('common.done')}</button>
	{/snippet}
</Dialog>

<style>
	.framing {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.stage {
		display: grid;
		place-items: center;
		padding: 8px;
		border-radius: var(--r);
		background: #111;
		cursor: grab;
		touch-action: none;
		user-select: none;
	}
	.stage:active {
		cursor: grabbing;
	}
	.stage:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.photo {
		position: relative;
		overflow: hidden;
		line-height: 0;
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: fill;
		pointer-events: none;
	}
	.window {
		position: absolute;
		border: 2px solid #fff;
		border-radius: 2px;
		box-shadow:
			0 0 0 1px rgb(0 0 0 / 0.4),
			0 0 0 9999px rgb(0 0 0 / 0.55);
		pointer-events: none;
		/* thirds, to help place a face */
		background:
			linear-gradient(to right, transparent calc(33.33% - 0.5px), rgb(255 255 255 / 0.35) 0 calc(33.33% + 0.5px), transparent 0 calc(66.66% - 0.5px), rgb(255 255 255 / 0.35) 0 calc(66.66% + 0.5px), transparent 0),
			linear-gradient(to bottom, transparent calc(33.33% - 0.5px), rgb(255 255 255 / 0.35) 0 calc(33.33% + 0.5px), transparent 0 calc(66.66% - 0.5px), rgb(255 255 255 / 0.35) 0 calc(66.66% + 0.5px), transparent 0);
	}
	.zoom-row {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.zoom-row input {
		flex: 1;
		min-width: 0;
	}
	.sign {
		font-size: 1.25rem;
		line-height: 1;
		font-weight: 600;
	}
	.zoom-val {
		min-width: 3.2em;
		text-align: right;
		font-weight: 600;
		color: var(--text-muted);
	}
	.soft {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		font-size: 0.85rem;
		color: var(--warn);
	}
</style>
