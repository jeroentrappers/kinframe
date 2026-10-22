<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api, call, type Send } from '$lib/api/client';
	import { dayBucket } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { followSends } from '$lib/sendsLive';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import SendCard from '$components/SendCard.svelte';

	let sends = $state<Send[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<unknown>(null);
	let filter = $state<'all' | 'ongoing' | 'delivered'>('all');

	async function load(reset = true) {
		if (reset) loading = true;
		else loadingMore = true;
		error = null;
		try {
			const res = await call(
				api.GET('/v1/sends', {
					params: { query: { limit: 20, state: filter, ...(reset || !cursor ? {} : { cursor }) } }
				})
			);
			sends = reset ? res.sends : [...sends, ...res.sends];
			cursor = res.next_cursor;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	onMount(() => {
		void load();
		return followSends(
			() => sends,
			(s) => {
				const i = sends.findIndex((x) => x.id === s.id);
				if (i >= 0) sends[i] = s;
			}
		);
	});

	const groups = $derived.by(() => {
		const out: { key: string; label: string; sends: Send[] }[] = [];
		for (const s of sends) {
			const b = dayBucket(s.created_at);
			const label =
				b === 'today'
					? t('history.today')
					: b === 'yesterday'
						? t('history.yesterday')
						: new Intl.DateTimeFormat(i18n.tag, { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date(s.created_at));
			const last = out[out.length - 1];
			if (last && last.key === b) last.sends.push(s);
			else out.push({ key: b, label, sends: [s] });
		}
		return out;
	});

	const highlight = $derived(page.url.searchParams.get('send'));
</script>

<svelte:head><title>{t('history.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<div class="page-header">
		<div>
			<h1>{t('history.title')}</h1>
			<p class="lede">{t('history.lede')}</p>
		</div>
		<div class="segmented" role="tablist" aria-label={t('history.filter')}>
			{#each ['all', 'ongoing', 'delivered'] as const as f}
				<button
					role="tab"
					aria-selected={filter === f}
					onclick={() => {
						filter = f;
						void load();
					}}>{t(`history.filter_${f}`)}</button
				>
			{/each}
		</div>
	</div>

	{#if loading}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			{#each [0, 1, 2] as i (i)}
				<div class="card skel">
					<div class="skeleton" style="min-height:140px; border-radius:0"></div>
					<div class="stack-sm" style="padding:16px">
						<div class="skeleton" style="height:18px;width:40%"></div>
						<div class="skeleton" style="height:14px;width:70%"></div>
						<div class="skeleton" style="height:14px;width:55%"></div>
					</div>
				</div>
			{/each}
		</div>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm">
				<span>{problemMessage(error)}</span>
				<button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button>
			</div>
		</div>
	{:else if sends.length === 0}
		<div class="card">
			<EmptyState
				title={filter === 'all' ? t('history.emptyTitle') : t('history.emptyFiltered')}
				body={filter === 'all' ? t('history.emptyBody') : undefined}
				art="history"
			>
				{#snippet actions()}
					<a class="btn btn-primary btn-lg" href="/send"><Icon name="send" size={18} />{t('nav.sendPhotos')}</a>
				{/snippet}
			</EmptyState>
		</div>
	{:else}
		<div class="stack-lg">
			{#each groups as g (g.key)}
				<section class="stack" aria-labelledby="day-{g.key}">
					<h2 id="day-{g.key}" class="day">{g.label}</h2>
					{#each g.sends as s (s.id)}
						<SendCard send={s} highlight={highlight === s.id} />
					{/each}
				</section>
			{/each}
			{#if cursor}
				<button class="btn" onclick={() => load(false)} disabled={loadingMore}>
					{#if loadingMore}<span class="spinner" aria-hidden="true"></span>{/if}
					{t('common.loadMore')}
				</button>
			{/if}
		</div>
	{/if}
</div>

<style>
	.narrow {
		max-width: 860px;
	}
	.day {
		font-family: var(--font-body);
		font-size: 0.8rem;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		color: var(--text-faint);
		font-weight: 700;
	}
	.skel {
		overflow: hidden;
		display: grid;
	}
	@media (min-width: 640px) {
		.skel {
			grid-template-columns: 200px 1fr;
		}
	}
</style>
