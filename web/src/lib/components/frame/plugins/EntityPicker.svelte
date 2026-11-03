<script lang="ts">
	import { untrack } from 'svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { filterEntities, type DiscoveredEntity, type SettingSpec } from '$lib/plugins';
	import Icon from '../../Icon.svelte';

	/**
	 * A Home Assistant entity setting (docs/plugins.md §6.1): a searchable list of what the frame found, filtered by the
	 * setting's domain and unit, with the friendly name and the id under it. Typing an id by hand always works.
	 */
	let {
		id,
		spec,
		entities,
		value = $bindable([]),
		placeholder = '',
		invalid = false,
		describedby
	}: {
		id: string;
		spec: SettingSpec;
		entities: DiscoveredEntity[] | undefined;
		value: string[];
		placeholder?: string;
		invalid?: boolean;
		describedby?: string;
	} = $props();

	const LIMIT = 50;
	const multiple = $derived(spec.multiple === true);

	let text = $state('');
	let open = $state(false);
	let active = $state(-1);

	// Keep the single field's text in step with values set from outside (a preset, discarding).
	$effect(() => {
		if (multiple) return;
		const v = value[0] ?? '';
		if (v !== untrack(() => text).trim()) text = v;
	});

	const byId = $derived(new Map((entities ?? []).map((e) => [e.entity_id, e])));
	const exact = $derived(!multiple && byId.has(text.trim()));
	const matches = $derived(
		filterEntities(entities, spec, exact ? '' : text)
			.filter((e) => !multiple || !value.includes(e.entity_id))
			.slice(0, LIMIT)
	);
	const listId = $derived(`${id}-list`);
	const optId = (i: number) => `${id}-opt-${i}`;
	const selectedName = $derived(!multiple && value[0] ? byId.get(value[0])?.name : undefined);

	function onInput() {
		if (!multiple) {
			const v = text.trim();
			value = v ? [v] : [];
		}
		open = true;
		active = -1;
	}

	function choose(e: DiscoveredEntity) {
		if (multiple) {
			if (!value.includes(e.entity_id)) value = [...value, e.entity_id];
			text = '';
		} else {
			value = [e.entity_id];
			text = e.entity_id;
			open = false;
		}
		active = -1;
	}

	function addTyped() {
		const v = text.trim();
		if (v && !value.includes(v)) value = [...value, v];
		text = '';
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			open = true;
			if (matches.length) active = (active + 1) % matches.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (matches.length) active = (active - 1 + matches.length) % matches.length;
		} else if (e.key === 'Enter') {
			// Enter picks; it never saves the form from here.
			e.preventDefault();
			if (open && active >= 0 && matches[active]) choose(matches[active]!);
			else if (multiple) addTyped();
			else open = false;
		} else if (e.key === 'Escape' && open) {
			e.preventDefault();
			e.stopPropagation();
			open = false;
		}
	}
</script>

<div class="picker">
	{#if multiple && value.length}
		<ul class="chosen" aria-label={t('plugins.entityChosen')}>
			{#each value as v (v)}
				<li class="chip-row">
					<span class="chosen-text">
						<span class="ent-name">{byId.get(v)?.name || v}</span>
						{#if byId.get(v)?.name}<span class="tiny muted mono">{v}</span>{/if}
					</span>
					<button
						type="button"
						class="btn btn-ghost btn-icon btn-sm"
						aria-label={t('plugins.entityRemove', { name: byId.get(v)?.name || v })}
						onclick={() => (value = value.filter((x) => x !== v))}
					>
						<Icon name="x" size={16} />
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	<div class="search-wrap">
		<span class="search-icon" aria-hidden="true"><Icon name="search" size={18} /></span>
		<input
			{id}
			class="input"
			type="text"
			autocomplete="off"
			spellcheck="false"
			autocapitalize="off"
			placeholder={placeholder || t('plugins.entitySearch')}
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
			onfocus={() => (open = true)}
			onblur={() => setTimeout(() => (open = false), 150)}
		/>
		{#if open && matches.length > 0}
			<ul id={listId} role="listbox" class="pop" aria-label={t('plugins.entityResults')}>
				{#each matches as e, i (e.entity_id)}
					<li
						id={optId(i)}
						role="option"
						aria-selected={i === active}
						class:active={i === active}
						onmousedown={(ev) => {
							ev.preventDefault();
							choose(e);
						}}
						onmousemove={() => (active = i)}
					>
						<span class="ent-name">{e.name || e.entity_id}</span>
						<span class="tiny muted mono">{e.entity_id}{e.unit ? ` · ${e.unit}` : ''}</span>
					</li>
				{/each}
			</ul>
		{/if}
	</div>
	{#if multiple && text.trim()}
		<button type="button" class="btn btn-ghost btn-sm add-typed" onclick={addTyped}><Icon name="plus" size={16} />{t('plugins.entityAdd', { id: text.trim() })}</button>
	{/if}
	{#if selectedName}<span class="tiny muted">{selectedName}</span>{/if}
	{#if open && text.trim() && matches.length === 0 && entities?.length && !exact}
		<span class="tiny muted" role="status">{t('plugins.entityNoMatch')}</span>
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
	.ent-name {
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.mono {
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		overflow-wrap: anywhere;
	}
	.chosen {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.chip-row {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		padding: 4px 4px 4px 12px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.chosen-text {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}
	.add-typed {
		align-self: flex-start;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
	}
</style>
