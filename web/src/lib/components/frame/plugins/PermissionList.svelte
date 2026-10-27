<script lang="ts">
	// What a community plugin may do (docs/plugins.md §3.1 permissions, §7): before adding it, and what an update asks.
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { manifestOf, settingLabel, settingsSpecs, type CatalogPlugin, type Permission } from '$lib/plugins';
	import Icon from '../../Icon.svelte';

	let { items, plugin, empty = false }: { items: Permission[]; plugin?: CatalogPlugin; empty?: boolean } = $props();

	const loc = $derived(i18n.locale);

	/** The address settings by their form label when the manifest at hand has them, else by key. */
	function labels(keys: string[]): string {
		const specs = plugin ? settingsSpecs(manifestOf(plugin)) : [];
		return keys
			.map((k) => {
				const s = specs.find((x) => x.key === k);
				return s && plugin ? settingLabel(plugin, s, loc) : k;
			})
			.join(', ');
	}
</script>

<ul class="perms">
	{#each items as p (p.kind)}
		<li class="small">
			{#if p.kind === 'internet'}
				<Icon name="globe" size={16} /><span>{t('plugins.reachesInternet', { hosts: p.hosts.join(', ') })}</span>
			{:else if p.kind === 'addresses'}
				<Icon name="wifi" size={16} /><span>{t('plugins.permAddresses', { settings: labels(p.settings) })}</span>
			{:else if p.kind === 'location'}
				<Icon name="mapPin" size={16} /><span>{t('plugins.usesLocation')}</span>
			{:else}
				<Icon name="power" size={16} /><span>{t('plugins.permActions')}</span>
			{/if}
		</li>
	{:else}
		{#if empty}<li class="small"><Icon name="check" size={16} /><span>{t('plugins.permNone')}</span></li>{/if}
	{/each}
</ul>

<style>
	.perms {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	li {
		display: flex;
		align-items: flex-start;
		gap: 8px;
		overflow-wrap: anywhere;
	}
	li :global(svg) {
		flex: none;
		margin-top: 2px;
		color: var(--text-muted);
	}
</style>
