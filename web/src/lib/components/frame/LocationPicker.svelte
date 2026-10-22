<script lang="ts">
	import { api, call, ApiError, type Schemas } from '$lib/api/client';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';

	type Place = Schemas['Place'];
	export type PickedLocation = { name?: string; lat: number; lon: number };

	let {
		value = $bindable(),
		id = 'loc',
		onpick
	}: { value: PickedLocation | null | undefined; id?: string; onpick?: (p: PickedLocation) => void } = $props();

	const DEBOUNCE_MS = 300;
	const MIN_CHARS = 2;

	let searching = $state(!value);
	let query = $state('');
	let results = $state<Place[]>([]);
	let active = $state(-1);
	let loading = $state(false);
	let problem = $state<string | null>(null);
	let open = $state(false);
	let picked = $state<Place | null>(null);
	let input = $state<HTMLInputElement | null>(null);

	let timer: ReturnType<typeof setTimeout> | undefined;
	let inflight: AbortController | null = null;

	const listId = $derived(`${id}-results`);
	const optId = (i: number) => `${id}-opt-${i}`;

	/** Region and country, without repeating the place name. */
	function context(p: { name?: string | null; admin1?: string | null; country?: string | null }): string {
		return [p.admin1, p.country].filter((x): x is string => !!x && x !== p.name).join(', ');
	}

	function friendly(e: unknown): string {
		if (e instanceof ApiError) {
			if (e.code === 'rate_limited' || e.status === 429) return t('location.errorBusy');
			if (e.code === 'feature_disabled' || e.status === 503) return t('location.errorDisabled');
			if (e.code === 'upstream_unavailable' || e.status === 502 || e.status === 504) return t('location.errorUpstream');
		}
		return problemMessage(e);
	}

	async function search(q: string) {
		inflight?.abort();
		const ctl = new AbortController();
		inflight = ctl;
		loading = true;
		problem = null;
		try {
			const list = await call(
				api.GET('/v1/geocode', { params: { query: { q, lang: i18n.locale, count: 8 } }, signal: ctl.signal })
			);
			if (ctl.signal.aborted) return;
			results = list ?? [];
			active = results.length > 0 ? 0 : -1;
			open = true;
		} catch (e) {
			if (ctl.signal.aborted) return;
			results = [];
			active = -1;
			problem = friendly(e);
			open = true;
		} finally {
			if (inflight === ctl) {
				inflight = null;
				loading = false;
			}
		}
	}

	function onInput() {
		clearTimeout(timer);
		const q = query.trim();
		if (q.length < MIN_CHARS) {
			inflight?.abort();
			inflight = null;
			loading = false;
			results = [];
			problem = null;
			open = false;
			return;
		}
		timer = setTimeout(() => void search(q), DEBOUNCE_MS);
	}

	function choose(p: Place) {
		picked = p;
		value = { name: p.name, lat: Math.round(p.lat * 1e4) / 1e4, lon: Math.round(p.lon * 1e4) / 1e4 };
		searching = false;
		open = false;
		query = '';
		results = [];
		onpick?.(value);
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			if (!open && results.length) open = true;
			else if (results.length) active = (active + 1) % results.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (results.length) active = (active - 1 + results.length) % results.length;
		} else if (e.key === 'Enter') {
			// Never submit the settings form from the search box.
			e.preventDefault();
			if (open && active >= 0 && results[active]) choose(results[active]!);
		} else if (e.key === 'Escape') {
			if (open) {
				e.preventDefault();
				e.stopPropagation();
				open = false;
			} else if (value) {
				e.preventDefault();
				cancelChange();
			}
		}
	}

	function startChange() {
		searching = true;
		queueMicrotask(() => input?.focus());
	}

	function cancelChange() {
		clearTimeout(timer);
		inflight?.abort();
		searching = false;
		open = false;
		query = '';
		results = [];
		problem = null;
	}

	function remove() {
		value = null;
		picked = null;
		searching = true;
	}

	// The summary card shows region/country only for a place picked in this session;
	// a saved location carries just its name and coordinates.
	const summaryContext = $derived(picked && value && picked.name === value.name ? context(picked) : '');
	const coords = $derived(value ? `${value.lat.toFixed(2)}°, ${value.lon.toFixed(2)}°` : '');

	$effect(() => {
		// Discarding the form brings a location back: show it instead of the search box.
		if (value && searching && !query) searching = false;
	});

	$effect(() => () => {
		clearTimeout(timer);
		inflight?.abort();
	});
</script>

{#if value && !searching}
	<div class="place" in:softFly>
		<span class="pin" aria-hidden="true"><Icon name="mapPin" size={20} /></span>
		<div class="place-text">
			<span class="place-name">{value.name || t('location.unnamed')}</span>
			{#if summaryContext}<span class="small muted">{summaryContext}</span>{/if}
			<span class="tiny muted num">{coords}</span>
		</div>
		<div class="place-actions">
			<button type="button" class="btn btn-ghost btn-sm" onclick={startChange}>{t('location.change')}</button>
			<button type="button" class="btn btn-danger-ghost btn-sm" onclick={remove}>{t('location.remove')}</button>
		</div>
	</div>
{:else}
	<div class="combo">
		<label for={id} class="label">{t('location.search')}</label>
		<div class="search-wrap">
			<span class="search-icon" aria-hidden="true"><Icon name="search" size={18} /></span>
			<input
				bind:this={input}
				{id}
				class="input"
				type="search"
				autocomplete="off"
				spellcheck="false"
				placeholder={t('location.placeholder')}
				role="combobox"
				aria-autocomplete="list"
				aria-expanded={open}
				aria-controls={listId}
				aria-activedescendant={open && active >= 0 ? optId(active) : undefined}
				bind:value={query}
				oninput={onInput}
				onkeydown={onKeydown}
				onfocus={() => {
					if (results.length || problem) open = true;
				}}
				onblur={() => setTimeout(() => (open = false), 150)}
			/>
			{#if loading}<span class="spinner small-spin" aria-hidden="true"></span>{/if}
		{#if open}
			<div class="pop" in:softFly={{ y: -4 }}>
				{#if problem}
					<p class="pop-msg small" role="alert"><Icon name="alert" size={16} />{problem}</p>
				{:else if results.length === 0}
					<p class="pop-msg small muted" role="status">{t('location.noResults', { q: query.trim() })}</p>
				{/if}
				<ul id={listId} role="listbox" aria-label={t('location.results')} hidden={results.length === 0}>
					{#each results as p, i (`${p.lat},${p.lon},${p.name}`)}
						<li
							id={optId(i)}
							role="option"
							aria-selected={i === active}
							class:active={i === active}
							onmousedown={(e) => {
								e.preventDefault();
								choose(p);
							}}
							onmousemove={() => (active = i)}
						>
							<Icon name="mapPin" size={16} />
							<span class="opt-text">
								<span class="opt-name">{p.name}</span>
								{#if context(p)}<span class="tiny muted">{context(p)}</span>{/if}
							</span>
							{#if p.country_code}<span class="cc tiny">{p.country_code}</span>{/if}
						</li>
					{/each}
				</ul>
			</div>
		{/if}
		</div>
		{#if value}
			<button type="button" class="btn btn-ghost btn-sm keep" onclick={cancelChange}>{t('location.keep', { name: value.name || t('location.unnamed') })}</button>
		{/if}
	</div>
{/if}

<style>
	.place {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3);
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface-2);
		flex-wrap: wrap;
	}
	.pin {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--primary);
		flex: none;
		box-shadow: inset 0 0 0 1px var(--border);
	}
	.place-text {
		display: flex;
		flex-direction: column;
		gap: 1px;
		flex: 1 1 160px;
		min-width: 0;
	}
	.place-name {
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.place-actions {
		display: flex;
		gap: 4px;
		margin-left: auto;
	}
	.combo {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.search-wrap {
		position: relative;
	}
	.search-wrap .input {
		width: 100%;
		padding-left: 38px;
	}
	.search-icon {
		position: absolute;
		left: 12px;
		top: 50%;
		transform: translateY(-50%);
		color: var(--text-faint);
		display: flex;
		pointer-events: none;
	}
	.small-spin {
		position: absolute;
		right: 12px;
		top: 50%;
		margin-top: -8px;
	}
	.pop {
		position: absolute;
		z-index: 30;
		left: 0;
		right: 0;
		top: calc(100% + 4px);
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r);
		box-shadow: var(--shadow-lg);
		overflow: hidden;
	}
	.pop-msg {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
		padding: 12px 14px;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 4px;
		max-height: 300px;
		overflow-y: auto;
	}
	li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: calc(var(--r) - 4px);
		cursor: pointer;
		color: var(--text-muted);
	}
	li.active {
		background: var(--surface-2);
		color: var(--text);
	}
	.opt-text {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}
	.opt-name {
		color: var(--text);
		font-weight: 500;
	}
	.cc {
		font-weight: 600;
		letter-spacing: 0.04em;
		padding: 2px 6px;
		border-radius: var(--r-full);
		border: 1px solid var(--border);
		color: var(--text-muted);
	}
	.keep {
		align-self: flex-start;
	}
</style>
