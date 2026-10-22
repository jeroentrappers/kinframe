<script lang="ts">
	// A drawn photo frame for the shop (no product photos): a 16:10 frame for `frame-10`, a larger 16:9 one for
	// `frame-15`, both on the same canvas so the size difference reads at a glance.
	let { image = 'frame-10' }: { image?: string } = $props();
	const uid = $props.id();
	const large = $derived(image === 'frame-15');
	// Outer frame box in a 320×220 canvas, centred above the shadow.
	const w = $derived(large ? 268 : 206);
	const h = $derived(large ? 160 : 136);
	const x = $derived((320 - w) / 2);
	const y = $derived(large ? 22 : 40);
	const bezel = $derived(large ? 13 : 11);
</script>

<svg viewBox="0 0 320 220" class="art" aria-hidden="true" focusable="false">
	<defs>
		<linearGradient id="{uid}-sky" x1="0" y1="0" x2="0" y2="1">
			<stop offset="0" stop-color="#f9d9b5" />
			<stop offset="0.65" stop-color="#f4c39b" />
			<stop offset="1" stop-color="#eeb48c" />
		</linearGradient>
		<linearGradient id="{uid}-wood" x1="0" y1="0" x2="1" y2="1">
			<stop offset="0" stop-color="#3a2f29" />
			<stop offset="1" stop-color="#231c18" />
		</linearGradient>
		<linearGradient id="{uid}-glare" x1="0" y1="0" x2="1" y2="1">
			<stop offset="0" stop-color="#fff" stop-opacity="0.28" />
			<stop offset="0.45" stop-color="#fff" stop-opacity="0" />
		</linearGradient>
		<clipPath id="{uid}-screen">
			<rect x={x + bezel} y={y + bezel} width={w - 2 * bezel} height={h - 2 * bezel} rx="3" />
		</clipPath>
	</defs>

	<ellipse cx="160" cy={y + h + 22} rx={w * 0.42} ry="7" fill="var(--text)" opacity="0.1" />
	<!-- easel stand behind the frame -->
	<path d="M{160 + w * 0.08} {y + h - 24} L{160 + w * 0.2} {y + h + 19}" stroke="#4a3d35" stroke-width="7" stroke-linecap="round" />

	<rect x={x} y={y} width={w} height={h} rx="10" fill="url(#{uid}-wood)" />
	<rect x={x + 1.5} y={y + 1.5} width={w - 3} height={h - 3} rx="9" fill="none" stroke="#fff" stroke-opacity="0.08" />

	<g clip-path="url(#{uid}-screen)">
		<rect x={x} y={y} width={w} height={h} fill="url(#{uid}-sky)" />
		<circle cx={x + w * 0.7} cy={y + h * 0.36} r={h * 0.13} fill="#fbe7b0" />
		<circle cx={x + w * 0.7} cy={y + h * 0.36} r={h * 0.085} fill="var(--sun)" />
		<path d="M{x} {y + h * 0.72} Q{x + w * 0.28} {y + h * 0.5} {x + w * 0.55} {y + h * 0.7} T{x + w} {y + h * 0.64} V{y + h} H{x}z" fill="#6f9a72" />
		<path d="M{x} {y + h * 0.86} Q{x + w * 0.35} {y + h * 0.68} {x + w * 0.7} {y + h * 0.84} T{x + w} {y + h * 0.8} V{y + h} H{x}z" fill="#4f7a5a" />
		<g fill="#2b2420" opacity="0.82">
			<circle cx={x + w * 0.36} cy={y + h * 0.6} r={h * 0.05} />
			<rect x={x + w * 0.36 - h * 0.05} y={y + h * 0.66} width={h * 0.1} height={h * 0.2} rx={h * 0.04} />
			<circle cx={x + w * 0.44} cy={y + h * 0.66} r={h * 0.037} />
			<rect x={x + w * 0.44 - h * 0.037} y={y + h * 0.7} width={h * 0.074} height={h * 0.15} rx={h * 0.03} />
		</g>
		<rect x={x} y={y} width={w} height={h} fill="url(#{uid}-glare)" />
	</g>
	<circle cx={x + w / 2} cy={y + h - bezel / 2} r="1.6" fill="#fff" opacity="0.35" />
</svg>

<style>
	.art {
		display: block;
		width: 100%;
		height: auto;
	}
</style>
