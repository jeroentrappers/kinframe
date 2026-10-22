<script lang="ts">
	// Pre-order reservation (README §5.11): choose frames, leave contact details, no payment. Only on servers with a
	// shop; the server recomputes every price, the live total here is a preview of the same rule.
	import { tick } from 'svelte';
	import { page } from '$app/state';
	import { api, ApiError, call } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import {
		clampQty,
		countryName,
		errorsFromProblem,
		formatMoney,
		localized,
		quote,
		reservationBody,
		validateReservation,
		type FormErrors,
		type FormField,
		type ReservationForm,
		type ShopReceipt
	} from '$lib/shop';
	import Icon from '$components/Icon.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';
	import Logo from '$components/Logo.svelte';
	import SellerFooter from '$components/SellerFooter.svelte';
	import ShopFrameArt from '$components/ShopFrameArt.svelte';

	const shop = $derived(discovery.shop);
	const brand = $derived(discovery.brand);

	function initialQty(): Record<string, number> {
		const pick = page.url.searchParams.get('product');
		const s = discovery.shop;
		if (!s) return {};
		const chosen = s.products.find((p) => p.id === pick) ?? (s.products.length === 1 ? s.products[0] : undefined);
		return chosen ? { [chosen.id]: 1 } : {};
	}

	/** The browser's region when we ship there (nl-BE → BE), or the only country. */
	function initialCountry(): string {
		const countries = discovery.shop?.shipping.countries ?? [];
		if (countries.length === 1) return countries[0]!;
		for (const l of typeof navigator !== 'undefined' ? navigator.languages ?? [] : []) {
			try {
				const region = new Intl.Locale(l).maximize().region;
				if (region && countries.includes(region)) return region;
			} catch {
				/* malformed tag */
			}
		}
		return '';
	}

	let form = $state<ReservationForm>({ name: '', email: '', country: initialCountry(), postalCode: '', note: '', consent: false, qty: initialQty() });
	let honeypot = $state('');
	let errors = $state<FormErrors>({});
	let submitted = $state(false);
	let busy = $state(false);
	let failure = $state<string | null>(null);
	let receipt = $state<ShopReceipt | null>(null);
	let summaryEl: HTMLDivElement | undefined = $state();
	let thanksEl: HTMLHeadingElement | undefined = $state();

	const q = $derived(shop ? quote(shop, form.qty) : null);
	const money = (cents: number) => (shop ? formatMoney(cents, shop.currency, i18n.tag) : '');
	const vat = $derived(shop ? new Intl.NumberFormat(i18n.tag).format(shop.vat_percent) : '');
	const when = $derived(localized(shop?.estimated_delivery, i18n.locale));
	const countryOptions = $derived(
		(shop?.shipping.countries ?? [])
			.map((c) => ({ code: c, name: countryName(c, i18n.tag) }))
			.sort((a, b) => a.name.localeCompare(b.name, i18n.tag))
	);

	// Once the person tried to submit, errors follow their edits.
	$effect(() => {
		if (submitted && shop) errors = validateReservation(form, shop);
	});

	function setQty(id: string, n: number) {
		const p = shop?.products.find((x) => x.id === id);
		if (!p) return;
		form.qty = { ...form.qty, [id]: clampQty(n, p) };
	}

	const MESSAGES: Record<FormField, (code: string) => string> = {
		items: () => t('shop.errItems'),
		name: () => t('shop.errName'),
		email: (c) => (c === 'format' ? t('shop.errEmailFormat') : t('shop.errEmail')),
		country: () => t('shop.errCountry'),
		consent: () => t('shop.errConsent')
	};
	const FIELD_IDS: Record<FormField, string> = { items: 'qty-first', name: 'r-name', email: 'r-email', country: 'r-country', consent: 'r-consent' };
	const errorList = $derived((Object.keys(errors) as FormField[]).map((f) => ({ field: f, text: MESSAGES[f](errors[f]!) })));

	async function showErrors() {
		await tick();
		summaryEl?.focus();
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (!shop || busy) return;
		submitted = true;
		failure = null;
		errors = validateReservation(form, shop);
		if (Object.keys(errors).length) return showErrors();
		busy = true;
		try {
			receipt = await call(api.POST('/v1/shop/reservations', { body: reservationBody(form, shop, i18n.locale, honeypot) }));
			window.scrollTo({ top: 0 });
			await tick();
			thanksEl?.focus();
		} catch (err) {
			if (err instanceof ApiError && err.code === 'validation_failed') {
				errors = errorsFromProblem(err.errors);
				if (Object.keys(errors).length) return showErrors();
			}
			failure = problemMessage(err);
		} finally {
			busy = false;
		}
	}

	function again() {
		receipt = null;
		submitted = false;
		errors = {};
		form = { ...form, note: '', consent: false, qty: initialQty() };
	}

	const describedBy = (...ids: (string | false | undefined)[]) => ids.filter(Boolean).join(' ') || undefined;
</script>

<svelte:head>
	<title>{shop ? t('shop.pageTitle', { brand }) : t('shop.unavailable')}{discovery.storefront ? '' : ` · ${discovery.name}`}</title>
</svelte:head>

<div class="shop-page">
	<header class="container top">
		<a href="/" class="home" aria-label={t('shop.backHome')}><Logo size={30} /></a>
		<div class="spacer"></div>
		<LanguagePicker />
	</header>

	<main id="main" class="container">
		{#if !shop}
			<div class="card card-pad center">
				<h1>{t('shop.unavailable')}</h1>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			</div>
		{:else if receipt}
			<section class="thanks card" aria-labelledby="thanks-title">
				<span class="ok-badge" aria-hidden="true"><Icon name="check" size={30} /></span>
				<h1 id="thanks-title" tabindex="-1" bind:this={thanksEl}>{t('shop.thanksTitle', { brand })}</h1>
				<div class="ref">
					<span class="label">{t('shop.thanksRef')}</span>
					<strong class="ref-code" data-testid="reservation-reference">{receipt.reference}</strong>
				</div>
				<ul class="receipt">
					{#each receipt.items as l (l.product_id)}
						<li><span>{l.quantity} × {l.name}</span><span class="num">{money(l.line_cents)}</span></li>
					{/each}
					<li>
						<span>{t('shop.shipping')}</span>
						<span class="num">{receipt.shipping_cents === 0 ? t('shop.shippingFree') : money(receipt.shipping_cents)}</span>
					</li>
					<li class="total"><span>{t('shop.total')}</span><span class="num">{money(receipt.total_cents)}</span></li>
				</ul>
				<p class="muted small">{t('shop.vatNote', { vat })}</p>
				<p>{t('shop.thanksEmail', { email: receipt.email })}</p>
				<p class="callout callout-ok"><Icon name="info" size={18} />{t('shop.thanksNext')}{#if when}&nbsp;{t('shop.delivery', { when })}.{/if}</p>
				<div class="row-wrap">
					<a class="btn btn-primary" href="/">{t('shop.backHome')}</a>
					<button class="btn btn-ghost" type="button" onclick={again}>{t('shop.thanksAgain')}</button>
				</div>
			</section>
		{:else}
			<div class="head">
				<h1>{t('shop.pageTitle', { brand })}</h1>
				<p class="lede">{t('shop.pageLede')}</p>
			</div>

			<section class="how" aria-labelledby="how-title">
				<h2 id="how-title" class="visually-hidden">{t('shop.howTitle')}</h2>
				<ol>
					<li><span class="step" aria-hidden="true">1</span>{t('shop.how1')}</li>
					<li><span class="step" aria-hidden="true">2</span>{t('shop.how2')}</li>
					<li><span class="step" aria-hidden="true">3</span>{t('shop.how3')}</li>
				</ol>
				{#if when}<p class="delivery"><Icon name="calendar" size={16} />{t('shop.delivery', { when })}</p>{/if}
			</section>

			<form class="layout" onsubmit={submit} novalidate aria-describedby="how-title">
				<div class="main stack-lg">
					{#if submitted && errorList.length}
						<div class="callout callout-error error-summary" role="alert" tabindex="-1" bind:this={summaryEl}>
							<Icon name="alert" size={20} />
							<div>
								<strong>{t('shop.errorSummary')}</strong>
								<ul>
									{#each errorList as e (e.field)}
										<li><a href="#{FIELD_IDS[e.field]}">{e.text}</a></li>
									{/each}
								</ul>
							</div>
						</div>
					{/if}

					<fieldset class="card card-pad" aria-describedby={describedBy(errors.items && 'items-error')}>
						<legend class="section-legend">{t('shop.chooseTitle')}</legend>
						<div class="products">
							{#each shop.products as p, i (p.id)}
								{@const n = form.qty[p.id] ?? 0}
								{@const specs = localized(p.specs, i18n.locale) ?? []}
								<div class="product" class:chosen={n > 0}>
									<div class="p-art"><ShopFrameArt image={p.image ?? (i === 0 ? 'frame-10' : 'frame-15')} /></div>
									<div class="p-info">
										<h3 id="pname-{p.id}">{p.name}</h3>
										<p class="p-price"><span class="num">{money(p.price_cents)}</span> <span class="hint">{t('shop.inclVat', { vat })}</span></p>
										{#if specs.length}<p class="hint p-specs">{specs.join(' · ')}</p>{/if}
									</div>
									<div class="stepper" role="group" aria-labelledby="pname-{p.id}">
										<button
											type="button"
											class="btn btn-icon"
											aria-label={t('shop.decrease', { name: p.name })}
											disabled={n <= 0}
											onclick={() => setQty(p.id, n - 1)}><span aria-hidden="true">−</span></button
										>
										<label class="visually-hidden" for={i === 0 ? 'qty-first' : `qty-${p.id}`}>{t('shop.qtyLabel', { name: p.name })}</label>
										<input
											id={i === 0 ? 'qty-first' : `qty-${p.id}`}
											class="input qty num"
											type="number"
											inputmode="numeric"
											min="0"
											max={p.max_qty}
											value={n}
											aria-invalid={errors.items ? 'true' : undefined}
											aria-describedby="max-{p.id}"
											oninput={(e) => setQty(p.id, e.currentTarget.valueAsNumber)}
										/>
										<button
											type="button"
											class="btn btn-icon"
											aria-label={t('shop.increase', { name: p.name })}
											disabled={n >= p.max_qty}
											onclick={() => setQty(p.id, n + 1)}><span aria-hidden="true">+</span></button
										>
									</div>
									<p id="max-{p.id}" class="visually-hidden">{t('shop.maxQty', { max: p.max_qty })}</p>
								</div>
							{/each}
						</div>
						{#if errors.items}<p id="items-error" class="error-text">{t('shop.errItems')}</p>{/if}
					</fieldset>

					<fieldset class="card card-pad details">
						<legend class="section-legend">{t('shop.detailsTitle')}</legend>
						<div class="grid2">
							<div class="field">
								<label for="r-name">{t('shop.name')}</label>
								<input
									id="r-name"
									class="input"
									autocomplete="name"
									maxlength="100"
									bind:value={form.name}
									aria-invalid={errors.name ? 'true' : undefined}
									aria-describedby={describedBy(errors.name && 'r-name-error')}
								/>
								{#if errors.name}<p id="r-name-error" class="error-text">{MESSAGES.name('required')}</p>{/if}
							</div>
							<div class="field">
								<label for="r-email">{t('shop.email')}</label>
								<input
									id="r-email"
									class="input"
									type="email"
									autocomplete="email"
									inputmode="email"
									maxlength="254"
									bind:value={form.email}
									aria-invalid={errors.email ? 'true' : undefined}
									aria-describedby={describedBy(errors.email && 'r-email-error', 'r-email-hint')}
								/>
								<span id="r-email-hint" class="hint">{t('shop.emailHint')}</span>
								{#if errors.email}<p id="r-email-error" class="error-text">{MESSAGES.email(errors.email)}</p>{/if}
							</div>
							<div class="field">
								<label for="r-country">{t('shop.country')}</label>
								<select
									id="r-country"
									class="select"
									autocomplete="country"
									bind:value={form.country}
									aria-invalid={errors.country ? 'true' : undefined}
									aria-describedby={describedBy(errors.country && 'r-country-error')}
								>
									<option value="" disabled>{t('shop.countryPick')}</option>
									{#each countryOptions as c (c.code)}<option value={c.code}>{c.name}</option>{/each}
								</select>
								{#if errors.country}<p id="r-country-error" class="error-text">{MESSAGES.country('required')}</p>{/if}
							</div>
							<div class="field">
								<label for="r-postal">{t('shop.postalCode')} <span class="hint">({t('common.optional')})</span></label>
								<input id="r-postal" class="input" autocomplete="postal-code" maxlength="12" bind:value={form.postalCode} />
							</div>
						</div>
						<div class="field">
							<label for="r-note">{t('shop.note')} <span class="hint">({t('common.optional')})</span></label>
							<textarea id="r-note" class="textarea" maxlength="1000" rows="3" placeholder={t('shop.notePlaceholder')} bind:value={form.note}></textarea>
						</div>
						<!-- Honeypot: hidden from people and assistive tech; bots fill it in. -->
						<div class="hp" aria-hidden="true">
							<label for="r-website">{t('shop.honeypot')}</label>
							<input id="r-website" name="website" type="text" tabindex="-1" autocomplete="off" bind:value={honeypot} />
						</div>
						<div class="consent" class:invalid={errors.consent}>
							<input
								id="r-consent"
								type="checkbox"
								bind:checked={form.consent}
								aria-invalid={errors.consent ? 'true' : undefined}
								aria-describedby={describedBy('r-consent-hint', errors.consent && 'r-consent-error')}
							/>
							<div>
								<label for="r-consent">{t('shop.consent', { seller: shop.seller.name })}</label>
								<p id="r-consent-hint" class="hint">{t('shop.consentHint')}</p>
								{#if errors.consent}<p id="r-consent-error" class="error-text">{MESSAGES.consent('required')}</p>{/if}
							</div>
						</div>
					</fieldset>
				</div>

				<aside class="aside" aria-labelledby="summary-title">
					<div class="card card-pad summary">
						<h2 id="summary-title">{t('shop.summaryTitle')}</h2>
						<div aria-live="polite" class="summary-body" data-testid="shop-summary">
							{#if q && q.lines.length}
								<ul class="lines">
									{#each q.lines as l (l.product.id)}
										<li><span>{l.quantity} × {l.product.name}</span><span class="num">{money(l.lineCents)}</span></li>
									{/each}
									<li class="sub"><span>{t('shop.subtotal')}</span><span class="num">{money(q.subtotalCents)}</span></li>
									<li>
										<span>{t('shop.shipping')}</span>
										<span class="num" class:free={q.shippingCents === 0}>{q.shippingCents === 0 ? t('shop.shippingFree') : money(q.shippingCents)}</span>
									</li>
									<li class="total"><span>{t('shop.total')}</span><span class="num" data-testid="shop-total">{money(q.totalCents)}</span></li>
								</ul>
								{#if q.missingForFreeCents != null}
									<p class="free-hint" data-testid="free-hint"><Icon name="send" size={16} />{t('shop.freeShippingHint', { amount: money(q.missingForFreeCents) })}</p>
								{:else if q.freeFromCents != null}
									<p class="free-hint reached"><Icon name="check" size={16} />{t('shop.freeShippingReached')}</p>
								{/if}
							{:else}
								<p class="muted">{t('shop.summaryEmpty')}</p>
								{#if shop.shipping.free_from_cents != null}
									<p class="hint">{t('shop.pointShipping', { amount: money(shop.shipping.free_from_cents) })}</p>
								{/if}
							{/if}
						</div>
						<p class="hint">{t('shop.vatNote', { vat })}</p>
						{#if failure}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{failure}</p>{/if}
						<button class="btn btn-primary btn-lg btn-block" type="submit" disabled={busy}>
							{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}
							{t('shop.submit')}
						</button>
						<p class="hint center">{t('shop.noPaymentNow')}</p>
					</div>
				</aside>
			</form>
		{/if}
	</main>

	{#if shop}
		<footer class="container foot"><SellerFooter {shop} /></footer>
	{/if}
</div>

<style>
	.shop-page {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		background:
			radial-gradient(1000px 500px at 90% -10%, color-mix(in srgb, var(--primary-soft) 60%, transparent), transparent 60%),
			var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		height: 72px;
	}
	.home {
		text-decoration: none;
	}
	main {
		padding-bottom: var(--s-7);
	}
	.center {
		text-align: center;
		display: grid;
		justify-items: center;
		gap: var(--s-4);
	}
	.head {
		max-width: 640px;
		margin-block: var(--s-4) var(--s-5);
	}
	h1 {
		font-size: clamp(1.9rem, 1.4rem + 2vw, 2.8rem);
		line-height: 1.08;
		letter-spacing: -0.02em;
	}
	.lede {
		margin-top: var(--s-2);
		font-size: 1.08rem;
		color: var(--text-muted);
	}
	.how {
		display: grid;
		gap: var(--s-3);
		margin-bottom: var(--s-5);
	}
	.how ol {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 860px) {
		.how ol {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
	}
	.how li {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		padding: var(--s-4);
		border-radius: var(--r-lg);
		background: color-mix(in srgb, var(--surface) 75%, transparent);
		border: 1px solid var(--border);
	}
	.step {
		flex: none;
		width: 30px;
		height: 30px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--primary);
		color: var(--on-primary);
		font-weight: 700;
	}
	.delivery {
		justify-self: start;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 6px 14px;
		border-radius: var(--r-full);
		background: var(--accent-soft);
		color: var(--accent-text);
		font-weight: 600;
		font-size: 0.9rem;
	}
	.layout {
		display: grid;
		gap: var(--s-5);
		align-items: start;
	}
	@media (min-width: 960px) {
		.layout {
			grid-template-columns: minmax(0, 1fr) 360px;
		}
		.aside {
			position: sticky;
			top: var(--s-4);
		}
	}
	fieldset {
		margin: 0;
		min-width: 0;
		display: grid;
		gap: var(--s-4);
	}
	.section-legend {
		float: left;
		width: 100%;
		font-family: var(--font-display);
		font-size: 1.3rem;
		font-weight: 620;
		margin-bottom: var(--s-2);
		padding: 0;
	}
	.section-legend + * {
		clear: both;
	}
	.products {
		display: grid;
		gap: var(--s-3);
	}
	.product {
		display: grid;
		grid-template-columns: 96px minmax(0, 1fr);
		grid-template-areas: 'art info' 'art stepper';
		gap: var(--s-2) var(--s-4);
		align-items: center;
		padding: var(--s-3);
		border-radius: var(--r-lg);
		border: 1.5px solid var(--border);
		transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
	}
	.product.chosen {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 35%, transparent);
	}
	@media (min-width: 600px) {
		.product {
			grid-template-columns: 120px minmax(0, 1fr) auto;
			grid-template-areas: 'art info stepper';
		}
	}
	.p-art {
		grid-area: art;
	}
	.p-info {
		grid-area: info;
		display: grid;
		gap: 2px;
	}
	.p-info h3 {
		font-size: 1.08rem;
	}
	.p-price .num {
		font-weight: 700;
		font-size: 1.05rem;
	}
	.p-specs {
		line-height: 1.4;
	}
	.stepper {
		grid-area: stepper;
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.stepper .btn {
		font-size: 1.3rem;
		line-height: 1;
	}
	.qty {
		width: 64px;
		text-align: center;
		padding-inline: 6px;
		-moz-appearance: textfield;
		appearance: textfield;
	}
	.qty::-webkit-outer-spin-button,
	.qty::-webkit-inner-spin-button {
		-webkit-appearance: none;
		margin: 0;
	}
	.grid2 {
		display: grid;
		gap: var(--s-4);
	}
	@media (min-width: 600px) {
		.grid2 {
			grid-template-columns: 1fr 1fr;
		}
	}
	.select[aria-invalid='true'] {
		border-color: var(--danger);
	}
	.hp {
		position: absolute;
		left: -10000px;
		width: 1px;
		height: 1px;
		overflow: hidden;
	}
	.consent {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		padding: var(--s-3) var(--s-4);
		border-radius: var(--r);
		background: var(--bg-tint);
	}
	.consent.invalid {
		outline: 1.5px solid var(--danger);
	}
	.consent input {
		width: 22px;
		height: 22px;
		margin-top: 1px;
		flex: none;
		accent-color: var(--primary);
	}
	.consent label {
		font-weight: 600;
	}
	.error-summary {
		outline: none;
	}
	.error-summary ul {
		margin: 6px 0 0;
		padding-inline-start: 18px;
	}
	.error-summary a {
		color: inherit;
	}
	.summary {
		display: grid;
		gap: var(--s-3);
	}
	.summary h2 {
		font-size: 1.25rem;
	}
	.lines,
	.receipt {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
	}
	.lines li,
	.receipt li {
		display: flex;
		justify-content: space-between;
		gap: var(--s-3);
		padding-block: 8px;
		border-bottom: 1px solid var(--border);
	}
	.lines .sub {
		color: var(--text-muted);
	}
	.lines .total,
	.receipt .total {
		border-bottom: 0;
		font-weight: 700;
		font-size: 1.15rem;
	}
	.free {
		color: var(--accent-text);
		font-weight: 600;
	}
	.free-hint {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		border-radius: var(--r);
		background: var(--warn-soft);
		font-size: 0.9rem;
		font-weight: 600;
	}
	.free-hint.reached {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.hint.center {
		text-align: center;
	}
	.thanks {
		max-width: 620px;
		margin: var(--s-6) auto 0;
		padding: var(--s-6) var(--s-5);
		display: grid;
		gap: var(--s-4);
		justify-items: start;
	}
	.thanks h1 {
		font-size: clamp(1.6rem, 1.3rem + 1.4vw, 2.2rem);
		outline: none;
	}
	.thanks .receipt {
		width: 100%;
	}
	.ok-badge {
		width: 56px;
		height: 56px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent);
	}
	.ref {
		display: grid;
		gap: 2px;
	}
	.ref-code {
		font-family: ui-monospace, Menlo, monospace;
		font-size: 1.8rem;
		letter-spacing: 0.08em;
	}
	.foot {
		margin-top: auto;
		padding-block: var(--s-5);
		border-top: 1px solid var(--border);
	}
</style>
