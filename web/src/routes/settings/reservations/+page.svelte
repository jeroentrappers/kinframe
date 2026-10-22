<script lang="ts">
	// Operator view of the shop's pre-order reservations: filter, status, internal notes and a CSV export.
	import { api, authFetch, call } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import { countryName, formatMoney, RESERVATION_STATUSES, type ShopReservation, type ShopReservationStatus } from '$lib/shop';
	import { toasts } from '$lib/toast.svelte';
	import Icon from '$components/Icon.svelte';

	const allowed = $derived(Boolean(auth.me?.is_operator) && discovery.shop !== null);
	let filter = $state<ShopReservationStatus | ''>('');
	let list = $state<ShopReservation[] | null>(null);
	let error = $state<string | null>(null);
	let notes = $state<Record<string, string>>({});
	let downloading = $state(false);

	async function load() {
		error = null;
		try {
			const res = await call(api.GET('/v1/operator/reservations', { params: { query: filter ? { status: filter } : {} } }));
			list = res.reservations;
			notes = Object.fromEntries(res.reservations.map((r) => [r.id, r.internal_note ?? '']));
		} catch (e) {
			error = problemMessage(e);
		}
	}

	$effect(() => {
		void filter;
		if (allowed) void load();
	});

	async function update(r: ShopReservation, body: { status?: ShopReservationStatus; internal_note?: string | null }) {
		try {
			const next = await call(api.PATCH('/v1/operator/reservations/{reservationId}', { params: { path: { reservationId: r.id } }, body }));
			if (list) list = list.map((x) => (x.id === r.id ? next : x)).filter((x) => !filter || x.status === filter);
			notes[r.id] = next.internal_note ?? '';
			toasts.success(t('reservations.saved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function download() {
		downloading = true;
		try {
			const res = await authFetch(`/v1/operator/reservations.csv${filter ? `?status=${filter}` : ''}`);
			const url = URL.createObjectURL(await res.blob());
			const a = document.createElement('a');
			a.href = url;
			a.download = `reservations-${new Date().toISOString().slice(0, 10)}.csv`;
			a.click();
			setTimeout(() => URL.revokeObjectURL(url), 1000);
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			downloading = false;
		}
	}

	const money = (cents: number, cur: string) => formatMoney(cents, cur, i18n.tag);
	const date = (iso: string) => new Intl.DateTimeFormat(i18n.tag, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
	const frames = (r: ShopReservation) => r.items.reduce((a, l) => a + l.quantity, 0);
</script>

<svelte:head><title>{t('reservations.title')} · kinframe</title></svelte:head>

<div class="container page">
	<div class="page-header">
		<div>
			<a class="back" href="/settings"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
			<h1>{t('reservations.title')}</h1>
		</div>
	</div>

	{#if !allowed}
		<p class="callout callout-warn"><Icon name="shield" size={18} />{t('reservations.forbidden')}</p>
	{:else}
		<p class="muted lede">{t('reservations.lede')}</p>
		<div class="toolbar">
			<label class="filter">
				<span class="label">{t('reservations.filterLabel')}</span>
				<select class="select" bind:value={filter}>
					<option value="">{t('reservations.filterAll')}</option>
					{#each RESERVATION_STATUSES as s (s)}<option value={s}>{t(`reservations.status.${s}`)}</option>{/each}
				</select>
			</label>
			{#if list}
				<span class="muted small num">
					{list.length} · {t('reservations.framesCount', { count: list.filter((r) => r.status !== 'cancelled').reduce((a, r) => a + frames(r), 0) })}
				</span>
			{/if}
			<div class="spacer"></div>
			<button class="btn" onclick={download} disabled={downloading}>
				{#if downloading}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="inbox" size={16} />{/if}
				{t('reservations.export')}
			</button>
		</div>

		{#if error}
			<p class="callout callout-error" role="alert">{error}</p>
		{:else if list === null}
			<div class="skeleton" style="height: 120px"></div>
		{:else if list.length === 0}
			<p class="card card-pad muted">{filter ? t('reservations.emptyFilter') : t('reservations.empty')}</p>
		{:else}
			<ul class="list">
				{#each list as r (r.id)}
					<li class="card card-pad res" data-testid="reservation">
						<div class="res-head">
							<strong class="ref">{r.reference}</strong>
							<span class="badge status-{r.status}">{t(`reservations.status.${r.status}`)}</span>
							<span class="faint small">{t('reservations.reservedOn', { date: date(r.created_at) })}</span>
						</div>
						<div class="res-grid">
							<div>
								<p class="who">{r.name}</p>
								<p><a href="mailto:{r.email}?subject={encodeURIComponent(r.reference)}">{r.email}</a></p>
								<p class="muted small">{countryName(r.country, i18n.tag)}{r.postal_code ? ` · ${r.postal_code}` : ''} · {r.locale}</p>
							</div>
							<div>
								{#each r.items as l (l.product_id)}<p>{l.quantity} × {l.name}</p>{/each}
								<p class="num"><strong>{money(r.total_cents, r.currency)}</strong>
									<span class="muted small">({t('shop.shipping')} {money(r.shipping_cents, r.currency)})</span></p>
							</div>
						</div>
						{#if r.note}
							<blockquote class="note"><span class="label">{t('reservations.customerNote')}</span>{r.note}</blockquote>
						{/if}
						<div class="res-edit">
							<label class="field">
								<span class="label">{t('reservations.statusLabel')}</span>
								<select class="select" value={r.status} onchange={(e) => update(r, { status: e.currentTarget.value as ShopReservationStatus })}>
									{#each RESERVATION_STATUSES as s (s)}<option value={s}>{t(`reservations.status.${s}`)}</option>{/each}
								</select>
							</label>
							<div class="field grow">
								<label for="note-{r.id}">{t('reservations.internalNote')}</label>
								<div class="note-row">
									<input id="note-{r.id}" class="input" maxlength="2000" bind:value={notes[r.id]} aria-describedby="note-hint-{r.id}" />
									<button
										class="btn"
										disabled={(notes[r.id] ?? '') === (r.internal_note ?? '')}
										onclick={() => update(r, { internal_note: notes[r.id]?.trim() ? notes[r.id]!.trim() : null })}>{t('common.save')}</button
									>
								</div>
								<span id="note-hint-{r.id}" class="hint">{t('reservations.internalNoteHint')}</span>
							</div>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</div>

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-size: 0.9rem;
	}
	.lede {
		margin-bottom: var(--s-4);
	}
	.toolbar {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.filter {
		display: flex;
		align-items: center;
		gap: var(--s-2);
	}
	.filter .select {
		width: auto;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-3);
	}
	.res {
		display: grid;
		gap: var(--s-3);
	}
	.res-head {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		flex-wrap: wrap;
	}
	.ref {
		font-family: ui-monospace, Menlo, monospace;
		font-size: 1.05rem;
		letter-spacing: 0.04em;
	}
	.status-new {
		background: var(--primary-soft);
		color: var(--primary-soft-text);
	}
	.status-contacted {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.status-confirmed {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.status-cancelled {
		background: var(--bg-tint);
		color: var(--text-faint);
		text-decoration: line-through;
	}
	.res-grid {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 700px) {
		.res-grid {
			grid-template-columns: 1fr 1fr;
		}
	}
	.who {
		font-weight: 650;
	}
	.res-grid a {
		overflow-wrap: anywhere;
	}
	.note {
		margin: 0;
		padding: var(--s-2) var(--s-3);
		border-inline-start: 3px solid var(--border-strong);
		background: var(--bg-tint);
		border-radius: var(--r-sm);
		display: grid;
		gap: 2px;
		white-space: pre-wrap;
	}
	.res-edit {
		display: flex;
		gap: var(--s-3);
		flex-wrap: wrap;
		align-items: flex-start;
	}
	.grow {
		flex: 1;
		min-width: 220px;
	}
	.note-row {
		display: flex;
		gap: var(--s-2);
	}
</style>
