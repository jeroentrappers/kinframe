<script lang="ts">
	// Retailer links of the DIY and "Outside …?" sections: the main links as buttons, secondary ones as small text
	// links below them ("Also at bol.com"), and the affiliate disclosure when one of the links is an affiliate link.
	// Affiliate links carry rel="sponsored".
	import { t } from '$lib/i18n/index.svelte';
	import type { RetailerLinks, ShopElsewhereLink } from '$lib/shop';
	import Icon from './Icon.svelte';

	let { links, where }: { links: RetailerLinks; where?: (l: ShopElsewhereLink) => string } = $props();
	const rel = (l: ShopElsewhereLink) => (l.affiliate ? 'sponsored noopener' : 'noopener');
</script>

{#if links.main.length}
	<ul class="main">
		{#each links.main as l, i (i)}
			<li>
				<a href={l.url} target="_blank" rel={rel(l)}>
					{#if where}<span class="where">{where(l)}</span>{/if}
					<span class="what" class:lead={!where}>{l.product} · {l.retailer}</span>
					<Icon name="chevronRight" size={16} />
				</a>
			</li>
		{/each}
	</ul>
{/if}
{#if links.secondary.length}
	<ul class="also">
		{#each links.secondary as l, i (i)}
			<li>
				<a href={l.url} target="_blank" rel={rel(l)}>{t('shop.alsoAt', { retailer: l.retailer })}</a>{#if where}<span class="also-where"> · {where(l)}</span>{/if}
			</li>
		{/each}
	</ul>
{/if}
{#if links.affiliate}<p class="disclosure">{t('shop.elsewhereDisclosure')}</p>{/if}

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.main {
		display: grid;
		gap: var(--s-2);
	}
	@media (min-width: 760px) {
		.main {
			grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
		}
	}
	.main a {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 12px 14px;
		border-radius: var(--r);
		border: 1px solid var(--border);
		background: var(--surface);
		color: inherit;
		text-decoration: none;
	}
	.main a:hover {
		border-color: var(--primary);
	}
	.where {
		font-weight: 600;
	}
	.what {
		flex: 1;
		color: var(--text-muted);
		font-size: 0.92rem;
	}
	.what.lead {
		color: var(--text);
		font-weight: 600;
		font-size: 1rem;
	}
	.also {
		display: flex;
		flex-wrap: wrap;
		gap: 4px var(--s-4);
		font-size: 0.88rem;
		color: var(--text-muted);
	}
	.also a {
		color: inherit;
		text-decoration: underline;
	}
	.disclosure {
		font-size: 0.8rem;
		color: var(--text-muted);
	}
</style>
