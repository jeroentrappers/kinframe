<script lang="ts">
	// Storefront hero: the frame with a phone in front of it, a photo on its way from one to the other.
	// Drawn, like the shop's product art (no product photos in the repository).
	import ShopFrameArt from './ShopFrameArt.svelte';

	const uid = $props.id();
</script>

<div class="scene" aria-hidden="true">
	<div class="glow"></div>
	<div class="frame"><ShopFrameArt image="frame-10" /></div>
	<svg class="phone" viewBox="0 0 120 220" focusable="false">
		<defs>
			<linearGradient id="{uid}-photo" x1="0" y1="0" x2="0" y2="1">
				<stop offset="0" stop-color="#cfe3f2" />
				<stop offset="1" stop-color="#9ec5e3" />
			</linearGradient>
			<clipPath id="{uid}-screen"><rect x="10" y="12" width="100" height="196" rx="14" /></clipPath>
		</defs>
		<rect x="2" y="2" width="116" height="216" rx="22" fill="#2b2420" stroke="var(--border-strong)" stroke-width="2" />
		<g clip-path="url(#{uid}-screen)">
			<rect x="10" y="12" width="100" height="196" fill="var(--surface)" />
			<!-- the photo being sent -->
			<rect x="18" y="40" width="84" height="104" rx="8" fill="url(#{uid}-photo)" />
			<circle cx="80" cy="64" r="9" fill="var(--sun)" />
			<path d="M18 122 40 100l16 14 12-10 34 26v6a8 8 0 0 1-8 8H26a8 8 0 0 1-8-8z" fill="#6c8f62" />
			<g fill="#2b2420" opacity="0.8">
				<circle cx="48" cy="104" r="6" />
				<rect x="42" y="111" width="12" height="22" rx="5" />
			</g>
			<rect x="18" y="156" width="56" height="7" rx="3.5" fill="var(--border)" />
			<rect x="18" y="170" width="38" height="7" rx="3.5" fill="var(--border)" />
			<circle cx="92" cy="176" r="12" fill="var(--primary)" />
			<path d="M86 176h11m-4-5 5 5-5 5" stroke="var(--on-primary)" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round" />
		</g>
		<rect x="46" y="18" width="28" height="5" rx="2.5" fill="#2b2420" />
	</svg>
	<svg class="trail" viewBox="0 0 120 80" focusable="false">
		<path d="M6 70C30 20 70 8 112 14" stroke="var(--primary)" stroke-width="3" stroke-dasharray="3 9" stroke-linecap="round" fill="none" />
		<path d="M10 0C3 -6 0 -9 0 -13a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 4-3 7-10 13z" transform="translate(96 30)" fill="var(--primary)" />
	</svg>
</div>

<style>
	.scene {
		position: relative;
		width: 100%;
		max-width: 520px;
		margin-inline: auto;
		padding: 4% 0 10% 12%;
	}
	.glow {
		position: absolute;
		inset: 6% 0 6% 10%;
		border-radius: 50%;
		background: radial-gradient(closest-side, color-mix(in srgb, var(--primary-soft) 90%, transparent), transparent);
	}
	.frame {
		position: relative;
	}
	.phone {
		position: absolute;
		left: 0;
		bottom: 0;
		width: 26%;
		height: auto;
		transform: rotate(-7deg);
		filter: drop-shadow(0 18px 24px rgb(40 25 10 / 0.28));
	}
	.trail {
		position: absolute;
		left: 18%;
		top: 2%;
		width: 26%;
		height: auto;
	}
	@media (prefers-reduced-motion: no-preference) {
		.phone {
			animation: float 6s var(--ease) infinite alternate;
		}
	}
	@keyframes float {
		to {
			transform: rotate(-5deg) translateY(-6px);
		}
	}
</style>
