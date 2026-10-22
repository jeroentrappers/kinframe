<script lang="ts">
	let { kind = 'frame' }: { kind?: 'frame' | 'send' | 'history' | 'library' | 'people' | 'hero' } = $props();
</script>

<svg viewBox="0 0 240 180" role="presentation" aria-hidden="true" class="ill">
	<defs>
		<linearGradient id="sky-{kind}" x1="0" y1="0" x2="0" y2="1">
			<stop offset="0" stop-color="#fbe3c8" />
			<stop offset="1" stop-color="#f6cfae" />
		</linearGradient>
	</defs>
	<ellipse cx="120" cy="96" rx="104" ry="78" fill="var(--primary-soft)" />
	<ellipse cx="120" cy="164" rx="70" ry="7" fill="var(--text)" opacity="0.07" />

	{#if kind === 'frame' || kind === 'hero'}
		<g transform="rotate(-3 120 90)">
			<rect x="52" y="34" width="136" height="104" rx="12" fill="var(--surface)" stroke="var(--border-strong)" stroke-width="2" />
			<rect x="62" y="44" width="116" height="84" rx="6" fill="url(#sky-{kind})" />
			<circle cx="148" cy="68" r="10" fill="var(--sun)" />
			<path d="M62 116l30-30 22 22 14-14 50 40v-6H62z" fill="#5c8a68" />
			<path d="M62 128v-12l30-30 22 22 14-14 50 34v6a6 6 0 0 1-6 6H68a6 6 0 0 1-6-6z" fill="#4f7a5a" />
		</g>
		<path d="M112 140l-8 22M128 140l8 22" stroke="var(--border-strong)" stroke-width="3" stroke-linecap="round" />
		<g transform="translate(186 34)">
			<path d="M10 18C3 12 0 9 0 5a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 4-3 7-10 13z" fill="var(--primary)" />
		</g>
		{#if kind === 'hero'}
			<g transform="translate(30 50) scale(0.7)">
				<path d="M10 18C3 12 0 9 0 5a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 4-3 7-10 13z" fill="var(--sun)" />
			</g>
		{/if}
	{:else if kind === 'send'}
		<g transform="rotate(-8 100 100)">
			<rect x="58" y="58" width="80" height="62" rx="8" fill="var(--surface)" stroke="var(--border-strong)" stroke-width="2" />
			<rect x="64" y="64" width="68" height="44" rx="4" fill="url(#sky-{kind})" />
			<path d="M64 102l18-16 12 10 8-6 30 18v0H64z" fill="#4f7a5a" />
		</g>
		<g transform="rotate(6 140 90)">
			<rect x="96" y="44" width="80" height="62" rx="8" fill="var(--surface)" stroke="var(--border-strong)" stroke-width="2" />
			<rect x="102" y="50" width="68" height="44" rx="4" fill="#e6efe4" />
			<circle cx="150" cy="64" r="7" fill="var(--sun)" />
			<path d="M102 88l20-18 14 12 8-6 26 16v2H102z" fill="#5c8a68" />
		</g>
		<path d="M168 128l40-26-46 10 8 6z" fill="var(--primary)" />
		<path d="M170 118l-2 12 8-8" fill="var(--primary-hover)" />
		<path d="M150 140c-10 6-26 8-38 4" stroke="var(--primary)" stroke-width="2.5" stroke-dasharray="2 7" stroke-linecap="round" fill="none" />
	{:else if kind === 'history'}
		{#each [0, 1, 2] as i}
			<g transform="translate({64 + i * 8} {40 + i * 22})">
				<rect width="112" height="40" rx="10" fill="var(--surface)" stroke="var(--border-strong)" stroke-width="2" />
				<rect x="8" y="8" width="24" height="24" rx="5" fill={i === 1 ? '#e6efe4' : 'url(#sky-history)'} />
				<rect x="40" y="12" width="44" height="6" rx="3" fill="var(--border-strong)" />
				<rect x="40" y="23" width="28" height="5" rx="2.5" fill="var(--border)" />
				<circle cx="98" cy="20" r="8" fill={i === 2 ? 'var(--border)' : '#4f7a5a'} />
				{#if i !== 2}<path d="M94 20l3 3 5-6" stroke="#fff" stroke-width="2.2" fill="none" stroke-linecap="round" stroke-linejoin="round" />{/if}
			</g>
		{/each}
	{:else if kind === 'library'}
		{#each [0, 1, 2] as r}
			{#each [0, 1, 2] as c}
				<rect
					x={72 + c * 34}
					y={42 + r * 34}
					width="28"
					height="28"
					rx="6"
					fill={(r + c) % 3 === 0 ? 'url(#sky-library)' : (r + c) % 3 === 1 ? '#e6efe4' : 'var(--surface)'}
					stroke="var(--border-strong)"
					stroke-width="1.5"
				/>
			{/each}
		{/each}
	{:else if kind === 'people'}
		<circle cx="92" cy="78" r="18" fill="#f2c9a8" />
		<path d="M62 136a30 30 0 0 1 60 0z" fill="var(--primary)" opacity="0.85" />
		<circle cx="148" cy="84" r="16" fill="#d9b48f" />
		<path d="M122 136a26 26 0 0 1 52 0z" fill="#4f7a5a" />
		<g transform="translate(110 30)">
			<path d="M10 18C3 12 0 9 0 5a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 4-3 7-10 13z" fill="var(--primary)" />
		</g>
	{/if}
</svg>

<style>
	.ill {
		width: 100%;
		height: auto;
		display: block;
	}
</style>
