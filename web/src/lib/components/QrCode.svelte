<script lang="ts">
	import qrcode from 'qrcode-generator';

	/** A QR code as inline SVG (crisp at any size, dark modules on white so every scanner reads it). */
	let { value, size = 180, label }: { value: string; size?: number; label: string } = $props();

	const qr = $derived.by(() => {
		const q = qrcode(0, 'M');
		q.addData(value);
		q.make();
		return q;
	});
	const n = $derived(qr.getModuleCount());
	const path = $derived.by(() => {
		let d = '';
		for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c + 4} ${r + 4}h1v1h-1z`;
		return d;
	});
</script>

<svg class="qr" width={size} height={size} viewBox="0 0 {n + 8} {n + 8}" role="img" aria-label={label} shape-rendering="crispEdges">
	<rect width={n + 8} height={n + 8} fill="#fff" />
	<path d={path} fill="#1b1714" />
</svg>

<style>
	.qr {
		display: block;
		border-radius: var(--r-sm);
		max-width: 100%;
		height: auto;
	}
</style>
