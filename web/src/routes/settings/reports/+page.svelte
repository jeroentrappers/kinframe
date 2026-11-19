<script lang="ts">
	import { api, call } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';
	import ReportList from '$components/social/ReportList.svelte';
</script>

<svelte:head><title>{t('chat.operatorReports')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
	<div class="page-header">
		<div>
			<h1>{t('chat.operatorReports')}</h1>
			<p class="lede">{t('chat.operatorReportsLede')}</p>
		</div>
	</div>
	{#if !discovery.chats || !auth.me?.is_operator}
		<p class="callout"><Icon name="info" size={18} />{t('chat.operatorOnly')}</p>
	{:else}
		<ReportList load={() => call(api.GET('/v1/operator/reports', { params: { query: { status: 'all' } } }))} />
	{/if}
</div>

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
</style>
