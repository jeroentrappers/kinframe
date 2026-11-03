<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type CalendarSource, type CalendarSourceCreate, type CalendarTestResult } from '$lib/api/client';
	import { colorVars, nextColor, type CalendarColor } from '$lib/calendar';
	import { problemMessage } from '$lib/problem';
	import { t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';
	import ColorPicker from './ColorPicker.svelte';

	let {
		open = $bindable(false),
		frameId,
		source = null,
		usedColors,
		onsaved
	}: {
		open?: boolean;
		frameId: string;
		/** null: add a new calendar. */
		source?: CalendarSource | null;
		usedColors: string[];
		onsaved: (saved: CalendarSource[]) => void;
	} = $props();

	const id = $props.id();
	const URL_RE = /^(https?|webcal):\/\/[^\s/]+\S*$/i;

	let kind = $state<'ics' | 'caldav'>('ics');
	let url = $state('');
	let username = $state('');
	let password = $state('');
	let name = $state('');
	let color = $state<CalendarColor>('coral');
	let enabled = $state(true);
	let testing = $state(false);
	let result = $state<CalendarTestResult | null>(null);
	let picked = $state<string[]>([]);
	let saving = $state(false);
	let error = $state<string | null>(null);
	let urlTouched = $state(false);

	const editing = $derived(source != null);
	const isLocal = $derived(source?.kind === 'local');

	// Reset each time the dialog opens.
	$effect(() => {
		if (!open) return;
		untrack(() => {
			kind = source?.kind === 'caldav' ? 'caldav' : 'ics';
			url = '';
			username = source?.username ?? '';
			password = '';
			name = source?.name ?? '';
			color = (source?.color as CalendarColor) ?? nextColor(usedColors);
			enabled = source?.enabled ?? true;
			result = null;
			picked = [];
			error = null;
			urlTouched = false;
		});
	});

	// A new test is needed once the address or credentials change.
	$effect(() => {
		void url;
		void username;
		void password;
		void kind;
		untrack(() => {
			result = null;
			picked = [];
		});
	});

	const urlOk = $derived(URL_RE.test(url.trim()));
	const urlError = $derived(urlTouched && url.trim() !== '' && !urlOk);
	const calendars = $derived(result?.ok ? (result.calendars ?? []) : []);
	const multi = $derived(kind === 'caldav' && calendars.length > 0);

	const canTest = $derived(urlOk && (kind === 'ics' || username.trim() !== '') && !testing);
	const canSave = $derived.by(() => {
		if (saving) return false;
		if (editing) return url.trim() === '' || urlOk;
		if (!urlOk) return false;
		if (kind === 'caldav') return username.trim() !== '' && password !== '' && (!multi || picked.length > 0);
		return true;
	});

	function body(): CalendarSourceCreate {
		const b: CalendarSourceCreate = { kind, url: url.trim() };
		if (kind === 'caldav') {
			b.username = username.trim();
			b.password = password;
		}
		return b;
	}

	async function test() {
		urlTouched = true;
		if (!canTest) return;
		testing = true;
		error = null;
		try {
			const r = await call(api.POST('/v1/frames/{frameId}/calendars:test', { params: { path: { frameId } }, body: body() }));
			result = r;
			if (r.ok && r.calendars?.length) picked = [r.calendars[0]!.url];
			if (r.ok && !name.trim() && r.calendar_name) name = r.calendar_name;
		} catch (e) {
			error = problemMessage(e);
		} finally {
			testing = false;
		}
	}

	const testMessage = $derived.by(() => {
		if (!result) return '';
		if (!result.ok) return result.status === 'auth_failed' ? t('cal.testAuth') : t('cal.testFailed', { error: result.error ?? '' });
		const events = result.event_count != null ? tn('cal.testUpcoming', result.event_count) : '';
		return result.calendar_name ? t('cal.testOkNamed', { name: result.calendar_name, events }) : t('cal.testOk', { events });
	});

	function togglePick(u: string) {
		picked = picked.includes(u) ? picked.filter((x) => x !== u) : [...picked, u];
	}

	async function save() {
		urlTouched = true;
		if (!canSave) return;
		saving = true;
		error = null;
		try {
			if (editing && source) {
				const patch: Record<string, unknown> = { name: name.trim(), color, enabled };
				if (!isLocal) {
					if (url.trim()) patch.url = url.trim();
					if (source.kind === 'caldav') {
						if (username.trim() && username.trim() !== source.username) patch.username = username.trim();
						if (password) patch.password = password;
					}
				}
				const saved = await call(
					api.PATCH('/v1/frames/{frameId}/calendars/{calendarId}', {
						params: { path: { frameId, calendarId: source.id } },
						body: patch
					})
				);
				onsaved([saved]);
			} else if (multi) {
				const used = [...usedColors];
				const out: CalendarSource[] = [];
				for (const u of picked) {
					const cal = calendars.find((c) => c.url === u)!;
					// One picked calendar keeps the colour chosen above; several get their own.
					const c = picked.length === 1 ? color : nextColor(used);
					used.push(c);
					out.push(
						await call(
							api.POST('/v1/frames/{frameId}/calendars', {
								params: { path: { frameId }, header: { 'Idempotency-Key': crypto.randomUUID() } },
								body: { ...body(), calendar_url: u, name: (picked.length === 1 && name.trim()) || cal.name, color: c, enabled: true }
							})
						)
					);
				}
				onsaved(out);
			} else {
				const saved = await call(
					api.POST('/v1/frames/{frameId}/calendars', {
						params: { path: { frameId }, header: { 'Idempotency-Key': crypto.randomUUID() } },
						body: { ...body(), ...(name.trim() ? { name: name.trim() } : {}), color, enabled }
					})
				);
				onsaved([saved]);
			}
			open = false;
		} catch (e) {
			error = problemMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Dialog bind:open title={editing ? t('cal.editTitle') : t('cal.addTitle')} size="md">
	<form
		id="{id}-form"
		class="stack"
		onsubmit={(e) => {
			e.preventDefault();
			void save();
		}}
	>
		{#if !editing}
			<fieldset class="kinds">
				<legend class="label">{t('cal.type')}</legend>
				<label class="kind" class:on={kind === 'ics'}>
					<input type="radio" name="{id}-kind" value="ics" bind:group={kind} />
					<Icon name="link" size={20} />
					<span><strong>{t('cal.typeIcs')}</strong><span class="hint">{t('cal.typeIcsHint')}</span></span>
				</label>
				<label class="kind" class:on={kind === 'caldav'}>
					<input type="radio" name="{id}-kind" value="caldav" bind:group={kind} />
					<Icon name="server" size={20} />
					<span><strong>{t('cal.typeCaldav')}</strong><span class="hint">{t('cal.typeCaldavHint')}</span></span>
				</label>
			</fieldset>
		{/if}

		{#if !isLocal}
			<div class="field">
				<label for="{id}-url">{kind === 'caldav' ? t('cal.serverUrl') : t('cal.url')}</label>
				<input
					id="{id}-url"
					class="input"
					type="url"
					inputmode="url"
					autocomplete="off"
					spellcheck="false"
					bind:value={url}
					onblur={() => (urlTouched = true)}
					placeholder={editing && source?.url_hint ? t('cal.urlKeep', { hint: source.url_hint }) : kind === 'caldav' ? t('cal.serverUrlPlaceholder') : t('cal.urlPlaceholder')}
					aria-invalid={urlError}
					aria-describedby="{id}-url-help"
					required={!editing}
				/>
				{#if urlError}
					<span class="hint err" id="{id}-url-help" role="alert">{t('cal.urlInvalid')}</span>
				{:else if kind === 'ics' && !editing}
					<details class="hint" id="{id}-url-help"><summary>{t('cal.typeIcsHint')}</summary><p>{t('cal.urlHelp')}</p></details>
				{:else}
					<span id="{id}-url-help" class="visually-hidden"></span>
				{/if}
			</div>

			{#if kind === 'caldav'}
				<div class="two">
					<div class="field">
						<label for="{id}-user">{t('cal.username')}</label>
						<input id="{id}-user" class="input" autocomplete="username" spellcheck="false" bind:value={username} required={!editing} />
					</div>
					<div class="field">
						<label for="{id}-pass">{t('cal.password')}</label>
						<input
							id="{id}-pass"
							class="input"
							type="password"
							autocomplete="new-password"
							bind:value={password}
							required={!editing}
							placeholder={editing && source?.has_password ? t('cal.passwordKeep') : ''}
						/>
					</div>
				</div>
				<p class="hint">{t('cal.passwordHelp')}</p>
			{/if}

			{#if !editing}
				<div class="test-row">
					<button type="button" class="btn btn-sm" onclick={test} disabled={!canTest}>
						{#if testing}<span class="spinner" aria-hidden="true"></span>{t('cal.testing')}{:else}<Icon name="refresh" size={16} />{t('cal.test')}{/if}
					</button>
				</div>
				{#if result}
					<p class="callout small" class:callout-ok={result.ok} class:callout-error={!result.ok} role="status" in:softFly>
						<Icon name={result.ok ? 'check' : 'alert'} size={18} />{testMessage}
					</p>
				{/if}
			{/if}
		{/if}

		{#if multi}
			<fieldset class="pick" in:softFly>
				<legend class="label">{t('cal.pickCalendars')}</legend>
				<span class="hint">{t('cal.pickHint')}</span>
				{#each calendars as c (c.url)}
					<label class="pick-row">
						<input type="checkbox" checked={picked.includes(c.url)} onchange={() => togglePick(c.url)} />
						<span class="pick-dot" style:background={c.color ?? 'var(--text-faint)'} aria-hidden="true"></span>
						<span class="pick-name">{c.name}</span>
					</label>
				{/each}
				{#if picked.length === 0}<span class="hint err">{t('cal.pickNone')}</span>{/if}
			</fieldset>
		{/if}

		{#if !multi || picked.length === 1}
			<div class="field">
				<label for="{id}-name">{t('cal.name')}</label>
				<input id="{id}-name" class="input" maxlength="60" bind:value={name} placeholder={isLocal ? t('cal.received') : t('cal.namePlaceholder')} />
			</div>
			<ColorPicker bind:value={color} name="{id}-color" label={t('cal.color')} />
			<div class="sample cal-color" style={colorVars(color)} aria-hidden="true">
				<span class="bar"></span><span>{name.trim() || (isLocal ? t('cal.received') : t('cal.namePlaceholder'))}</span>
			</div>
		{/if}

		{#if editing}
			<label class="switch"><input type="checkbox" bind:checked={enabled} /><span>{t('cal.show')}</span></label>
		{/if}

		{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{error}</p>{/if}
	</form>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="submit" form="{id}-form" class="btn btn-primary" disabled={!canSave}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{editing ? t('common.save') : t('cal.save')}
		</button>
	{/snippet}
</Dialog>

<style>
	.kinds,
	.pick {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
		min-width: 0;
	}
	@media (min-width: 560px) {
		.kinds {
			display: grid;
			grid-template-columns: 1fr 1fr;
		}
		.kinds legend {
			grid-column: 1 / -1;
		}
	}
	.kind {
		position: relative;
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 12px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r);
		cursor: pointer;
		color: var(--text-muted);
		transition:
			border-color var(--dur) var(--ease),
			background var(--dur) var(--ease);
	}
	.kind.on {
		border-color: var(--primary);
		background: var(--primary-soft);
		color: var(--primary-soft-text);
	}
	.kind input {
		position: absolute;
		opacity: 0;
		inset: 0;
		margin: 0;
		cursor: pointer;
	}
	.kind:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 1px;
	}
	.kind > span {
		display: flex;
		flex-direction: column;
		gap: 2px;
		color: var(--text);
	}
	.two {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 520px) {
		.two {
			grid-template-columns: 1fr 1fr;
		}
	}
	.err {
		color: var(--danger);
	}
	details summary {
		cursor: pointer;
	}
	details p {
		margin: 6px 0 0;
	}
	.test-row {
		display: flex;
	}
	.callout {
		margin: 0;
		align-items: center;
	}
	.pick-row {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 40px;
		padding: 4px 10px;
		border-radius: var(--r-sm);
		border: 1px solid var(--border);
		cursor: pointer;
	}
	.pick-row input {
		width: 18px;
		height: 18px;
		accent-color: var(--primary);
	}
	.pick-dot {
		width: 12px;
		height: 12px;
		border-radius: 50%;
	}
	.pick-name {
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.sample {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 12px;
		border-radius: var(--r-sm);
		background: color-mix(in srgb, var(--ev) 18%, var(--surface));
		font-weight: 600;
		font-size: 0.9rem;
		overflow-wrap: anywhere;
	}
	.sample .bar {
		width: 4px;
		align-self: stretch;
		border-radius: 2px;
		background: var(--ev);
		flex: none;
	}
</style>
