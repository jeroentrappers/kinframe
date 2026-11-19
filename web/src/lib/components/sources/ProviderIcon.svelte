<script lang="ts">
	import type { Service } from '$lib/photoSources';
	import { SERVICE_LABEL } from '$lib/photoSources';

	/** A small badge for the outside service (simple shapes in the service's colours, not its logo). */
	let { service, size = 36, decorative = false }: { service: Service; size?: number; decorative?: boolean } = $props();
</script>

<span class="pi {service}" style="--size:{size}px" role={decorative ? undefined : 'img'} aria-hidden={decorative ? 'true' : undefined} aria-label={decorative ? undefined : SERVICE_LABEL[service]}>
	<svg viewBox="0 0 24 24" width={Math.round(size * 0.62)} height={Math.round(size * 0.62)} aria-hidden="true">
		{#if service === 'icloud'}
			<path d="M7 18h10.5a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.6 9.1 4.5 4.5 0 0 0 7 18z" fill="#3b8ff5" />
		{:else if service === 'google_photos'}
			<path d="M12 12V3.5a4.25 4.25 0 0 1 0 8.5z" fill="#ea4335" />
			<path d="M12 12h8.5a4.25 4.25 0 0 1-8.5 0z" fill="#4285f4" />
			<path d="M12 12v8.5a4.25 4.25 0 0 1 0-8.5z" fill="#34a853" />
			<path d="M12 12H3.5a4.25 4.25 0 0 1 8.5 0z" fill="#fbbc04" />
		{:else}
			<ellipse cx="12" cy="7.2" rx="3.3" ry="4.4" fill="#fa2921" />
			<ellipse cx="16.8" cy="12" rx="4.4" ry="3.3" fill="#18c249" />
			<ellipse cx="12" cy="16.8" rx="3.3" ry="4.4" fill="#1e83f7" />
			<ellipse cx="7.2" cy="12" rx="4.4" ry="3.3" fill="#ffb400" />
		{/if}
	</svg>
</span>

<style>
	.pi {
		flex: none;
		display: inline-grid;
		place-items: center;
		width: var(--size);
		height: var(--size);
		border-radius: 28%;
		background: var(--surface);
		border: 1px solid var(--border);
	}
	.pi svg {
		display: block;
	}
</style>
