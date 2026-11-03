<script lang="ts">
	import { untrack } from 'svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../../Icon.svelte';
	import { filterTimeZones, knownTimeZone, timeZoneOption, timeZoneOptions, type TimeZoneOption } from '$lib/plugins';

	/**
	 * A `timezone` setting (docs/plugins.md §3.1, §6.1): a searchable list of time zones, each shown as its city with the
	 * region and the current UTC offset under it. A stored id the browser doesn't know still shows, as written.
	 */
	let {
		id,
		value = $bindable(''),
		placeholder = '',
		invalid = false,
		describedby
	}: {
		id: string;
		value: string;
		placeholder?: string;
		invalid?: boolean;
		describedby?: string;
	} = $props();

	const LIMIT = 60;

	/** Built on first use: one formatter per zone, so not while the form opens. */
	let options = $state<TimeZoneOption[] | null>(null);
	let text = $state('');
	let editing = $state(false);
	let open = $state(false);
	let active = $state(-1);

	const chosen = $derived(value ? (options?.find((o) => o.id === value) ?? timeZoneOption(value)) : null);
	/** What the field shows while not being typed in: the city, or an unknown id as it is. */
	const shown = $derived(chosen ? (knownTimeZone(chosen.id) ? chosen.city : chosen.id) : '');

	// Keep the text in step with values set from outside (a preset, a save) while the owner isn't typing.
	$effect(() => {
		const s = shown;
		if (!untrack(() => editing)) text = s;
	});

	const matches = $derived(options ? filterTimeZones(options, editing ? text : '').slice(0, LIMIT) : []);
	const listId = $derived(`${id}-list`);
	const optId = (i: number) => `${id}-opt-${i}`;

	function ensureOptions() {
		options ??= timeZoneOptions();
	}

	function onFocus() {
		ensureOptions();
		open = true;
		active = Math.max(-1, matches.findIndex((o) => o.id === value));
	}

	function onInput() {
		ensureOptions();
		editing = true;
		open = true;
		active = -1;
		// Clearing the field clears the setting.
		if (text.trim() === '') value = '';
	}

	function choose(o: TimeZoneOption) {
		value = o.id;
		editing = false;
		text = o.city;
		open = false;
		active = -1;
	}

	function close() {
		open = false;
		editing = false;
		text = shown;
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			ensureOptions();
			open = true;
			if (matches.length) active = (active + 1) % matches.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (matches.length) active = (active - 1 + matches.length) % matches.length;
		} else if (e.key === 'Enter') {
			// Enter picks; it never saves the form from here.
			e.preventDefault();
			const pick = open && active >= 0 ? matches[active] : editing && matches.length === 1 ? matches[0] : undefined;
			if (pick) choose(pick);
			else close();
		} else if (e.key === 'Escape' && open) {
			e.preventDefault();
			e.stopPropagation();
			close();
		}
	}
</script>

<div class="picker">
	<div class="search-wrap">
	<span class="search-icon" aria-hidden="true"><Icon name="search" size={18} /></span>
	<input
		{id}
		class="input"
		type="text"
		autocomplete="off"
		spellcheck="false"
		autocapitalize="off"
		placeholder={placeholder || t('plugins.tzSearch')}
		role="combobox"
		aria-autocomplete="list"
		aria-expanded={open && matches.length > 0}
		aria-controls={listId}
		aria-activedescendant={open && active >= 0 ? optId(active) : undefined}
		aria-invalid={invalid || undefined}
		aria-describedby={describedby}
		bind:value={text}
		oninput={onInput}
		onkeydown={onKeydown}
		onfocus={onFocus}
		onblur={() => setTimeout(close, 150)}
	/>
	{#if open && matches.length > 0}
		<ul id={listId} role="listbox" class="pop" aria-label={t('plugins.tzResults')}>
			{#each matches as o, i (o.id)}
				<li
					id={optId(i)}
					role="option"
					aria-selected={i === active}
					class:active={i === active}
					class:chosen={o.id === value}
					onmousedown={(ev) => {
						ev.preventDefault();
						choose(o);
					}}
					onmousemove={() => (active = i)}
				>
					<span class="city">{o.city}</span>
					<span class="tiny muted">{o.detail}</span>
				</li>
			{/each}
		</ul>
	{/if}
	</div>
	{#if chosen && !editing && chosen.offset}<span class="tiny muted">{chosen.detail}</span>{/if}
	{#if open && editing && text.trim() && matches.length === 0}
		<span class="tiny muted" role="status">{t('plugins.tzNoMatch')}</span>
	{/if}
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.search-wrap {
		position: relative;
	}
	.search-wrap .input {
		width: 100%;
		padding-inline-start: 38px;
	}
	.search-icon {
		position: absolute;
		inset-inline-start: 12px;
		top: 50%;
		transform: translateY(-50%);
		color: var(--text-faint);
		display: flex;
		pointer-events: none;
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
		list-style: none;
		margin: 0;
		padding: 4px;
		max-height: 260px;
		overflow-y: auto;
	}
	.pop li {
		display: flex;
		flex-direction: column;
		padding: 8px 10px;
		border-radius: calc(var(--r) - 4px);
		cursor: pointer;
		min-width: 0;
	}
	.pop li.active {
		background: var(--surface-2);
	}
	.pop li.chosen .city {
		color: var(--primary);
	}
	.city {
		font-weight: 500;
		overflow-wrap: anywhere;
	}
</style>
