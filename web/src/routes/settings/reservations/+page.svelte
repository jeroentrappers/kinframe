<script lang="ts">
	// Operator view of the shop's reservations, or its orders when the shop takes payment (checkout): filter, status,
	// internal notes and a CSV export. Orders show the delivery address and payment; unpaid ones are hidden by default.
	// Paid orders take the carrier's tracking link, which the server emails to the customer once the order is shipped.
	// Shops with stock management: the count of frames ready to ship per product.
	import { api, authFetch, call } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import { countryName, formatMoney, ORDER_STATUSES, RESERVATION_STATUSES, type ShopReservation, type ShopReservationStatus } from '$lib/shop';
	import { toasts } from '$lib/toast.svelte';
	import Icon from '$components/Icon.svelte';

	const allowed = $derived(Boolean(auth.me?.is_operator) && discovery.shop !== null);
	let filter = $state<ShopReservationStatus | ''>('');
	let list = $state<ShopReservation[] | null>(null);
	let error = $state<string | null>(null);
	let notes = $state<Record<string, string>>({});
	let tracking = $state<Record<string, string>>({});
	/** An absolute http(s) link, as the server accepts it. */
	const validLink = (v: string) => {
		try {
			const u = new URL(v);
			return (u.protocol === 'https:' || u.protocol === 'http:') && !/\s/.test(v);
		} catch {
			return false;
		}
	};
	let downloading = $state(false);
	const checkout = $derived(!!discovery.shop?.checkout);
	const statuses = $derived(checkout ? ORDER_STATUSES : RESERVATION_STATUSES);
	/** The status choices for one entry: its own status stays selectable (older reservations can be "contacted"). */
	const choices = (r: ShopReservation) => (statuses.includes(r.status) ? statuses : [...statuses, r.status]);
	let showUnpaid = $state(false);
	const stocked = $derived(!!discovery.shop?.stock);
	let stock = $state<Record<string, number> | null>(null);
	let stockEdit = $state<Record<string, number | null>>({});

	async function loadStock() {
		try {
			const res = await call(api.GET('/v1/operator/shop/stock'));
			stock = Object.fromEntries(res.stock.map((x) => [x.product_id, x.quantity]));
			stockEdit = { ...stock };
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	$effect(() => {
		if (allowed && stocked) void loadStock();
	});

	const stockValid = (n: number | null | undefined): n is number => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 100000;

	async function saveStock(productId: string) {
		const n = stockEdit[productId];
		if (!stockValid(n)) return;
		try {
			const saved = await call(api.PUT('/v1/operator/shop/stock/{productId}', { params: { path: { productId } }, body: { quantity: n } }));
			stock = { ...stock, [productId]: saved.quantity };
			toasts.success(t('reservations.stockSaved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}
	const unpaid = (r: ShopReservation) => r.payment_status != null && r.payment_status !== 'paid';
	const shown = $derived(list?.filter((r) => showUnpaid || !unpaid(r)) ?? null);
	const hiddenCount = $derived((list?.length ?? 0) - (shown?.length ?? 0));

	async function load() {
		error = null;
		try {
			const res = await call(api.GET('/v1/operator/reservations', { params: { query: filter ? { status: filter } : {} } }));
			list = res.reservations;
			notes = Object.fromEntries(res.reservations.map((r) => [r.id, r.internal_note ?? '']));
			tracking = Object.fromEntries(res.reservations.map((r) => [r.id, r.tracking_url ?? '']));
		} catch (e) {
			error = problemMessage(e);
		}
	}

	$effect(() => {
		void filter;
		if (allowed) void load();
	});

	async function update(r: ShopReservation, body: { status?: ShopReservationStatus; internal_note?: string | null; tracking_url?: string | null }) {
		try {
			const next = await call(api.PATCH('/v1/operator/reservations/{reservationId}', { params: { path: { reservationId: r.id } }, body }));
			if (list) list = list.map((x) => (x.id === r.id ? next : x)).filter((x) => !filter || x.status === filter);
			notes[r.id] = next.internal_note ?? '';
			tracking[r.id] = next.tracking_url ?? '';
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

<svelte:head><title>{checkout ? t('reservations.titleOrders') : t('reservations.title')} · kinframe</title></svelte:head>

<div class="container page">
	<div class="page-header">
		<div>
			<a class="back" href="/settings"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
			<h1>{checkout ? t('reservations.titleOrders') : t('reservations.title')}</h1>
		</div>
	</div>

	{#if !allowed}
		<p class="callout callout-warn"><Icon name="shield" size={18} />{t('reservations.forbidden')}</p>
	{:else}
		<p class="muted lede">{t('reservations.lede')}</p>
		{#if stocked && discovery.shop}
			<section class="card card-pad stock" aria-labelledby="stock-title" data-testid="stock">
				<h2 id="stock-title">{t('reservations.stockTitle')}</h2>
				{#if stock === null}
					<div class="skeleton" style="height: 40px"></div>
				{:else}
					<div class="stock-rows">
						{#each discovery.shop.products as p (p.id)}
							<div class="stock-row">
								<label for="stock-{p.id}">{t('reservations.stockLabel', { name: p.name })}</label>
								<input id="stock-{p.id}" class="input num" type="number" min="0" max="100000" step="1" inputmode="numeric" bind:value={stockEdit[p.id]} />
								<button class="btn" disabled={!stockValid(stockEdit[p.id]) || stockEdit[p.id] === stock[p.id]} onclick={() => saveStock(p.id)}
									>{t('common.save')}</button
								>
							</div>
						{/each}
					</div>
				{/if}
				<p class="hint">{t('reservations.stockHint')}</p>
			</section>
		{/if}
		<div class="toolbar">
			<label class="filter">
				<span class="label">{t('reservations.filterLabel')}</span>
				<select class="select" bind:value={filter}>
					<option value="">{t('reservations.filterAll')}</option>
					{#each statuses as s (s)}<option value={s}>{t(`reservations.status.${s}`)}</option>{/each}
				</select>
			</label>
			{#if shown}
				<span class="muted small num">
					{shown.length} · {t('reservations.framesCount', { count: shown.filter((r) => r.status !== 'cancelled').reduce((a, r) => a + frames(r), 0) })}
				</span>
			{/if}
			{#if checkout}
				<label class="unpaid-toggle small"><input type="checkbox" bind:checked={showUnpaid} />{t('reservations.showUnpaid')}</label>
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
		{:else if !shown || shown.length === 0}
			<p class="card card-pad muted">{filter ? t('reservations.emptyFilter') : t('reservations.empty')}</p>
			{#if hiddenCount > 0}<p class="muted small">{t('reservations.unpaidHidden')}</p>{/if}
		{:else}
			{#if hiddenCount > 0}<p class="muted small">{t('reservations.unpaidHidden')}</p>{/if}
			<ul class="list">
				{#each shown as r (r.id)}
					<li class="card card-pad res" data-testid="reservation">
						<div class="res-head">
							<strong class="ref">{r.reference}</strong>
							<span class="badge status-{r.status}">{t(`reservations.status.${r.status}`)}</span>
							{#if r.payment_status}
								<span class="badge pay-{r.payment_status}" data-testid="payment-status">{t(`reservations.payment.${r.payment_status}`)}</span>
							{/if}
							<span class="faint small">{r.paid_at ? t('reservations.paidOn', { date: date(r.paid_at) }) : t('reservations.reservedOn', { date: date(r.created_at) })}</span>
						</div>
						<div class="res-grid">
							<div>
								<p class="who">{r.name}</p>
								<p><a href="mailto:{r.email}?subject={encodeURIComponent(r.reference)}">{r.email}</a></p>
								{#if r.street}
									<p class="address small" aria-label={t('reservations.address')}>
										{r.street}<br />{r.postal_code} {r.city}<br />{countryName(r.country, i18n.tag)}
									</p>
									{#if r.phone}<p class="small">{t('reservations.phone')}: <a href="tel:{r.phone}">{r.phone}</a></p>{/if}
									<p class="muted small">{r.locale}</p>
								{:else}
									<p class="muted small">{countryName(r.country, i18n.tag)}{r.postal_code ? ` · ${r.postal_code}` : ''} · {r.locale}</p>
								{/if}
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
									{#each choices(r) as s (s)}<option value={s}>{t(`reservations.status.${s}`)}</option>{/each}
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
							{#if r.paid_at || r.tracking_url}
								{@const link = (tracking[r.id] ?? '').trim()}
								<div class="field grow">
									<label for="track-{r.id}">{t('reservations.tracking')}</label>
									<div class="note-row">
										<input
											id="track-{r.id}"
											class="input"
											type="url"
											inputmode="url"
											maxlength="2000"
											placeholder="https://"
											bind:value={tracking[r.id]}
											aria-invalid={link !== '' && !validLink(link)}
											aria-describedby="track-hint-{r.id}"
											data-testid="tracking-input"
										/>
										{#if r.tracking_url}
											<a class="btn btn-icon" href={r.tracking_url} target="_blank" rel="noopener noreferrer" aria-label={t('reservations.tracking')}
												><Icon name="link" size={16} /></a
											>
										{/if}
										<button
											class="btn"
											disabled={link === (r.tracking_url ?? '') || (link !== '' && !validLink(link))}
											onclick={() => update(r, { tracking_url: link || null })}>{t('common.save')}</button
										>
									</div>
									<span id="track-hint-{r.id}" class="hint">{t('reservations.trackingHint')}</span>
								</div>
							{/if}
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</div>

<style>
	.stock {
		display: grid;
		gap: var(--s-3);
		margin-bottom: var(--s-4);
	}
	.stock h2 {
		font-size: 1.1rem;
		margin: 0;
	}
	.stock-rows {
		display: grid;
		gap: var(--s-2);
	}
	.stock-row {
		display: grid;
		grid-template-columns: 1fr 7rem auto;
		align-items: center;
		gap: var(--s-2);
	}
	.unpaid-toggle {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.address {
		margin: 4px 0;
	}
	.pay-paid {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.pay-open,
	.pay-pending,
	.pay-authorized {
		background: var(--warn-soft);
		color: var(--warn);
	}
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
	.status-confirmed,
	.status-shipped {
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
