<script lang="ts" module>
	import type { MappedErrors, PluginInstance } from '$lib/plugins';

	export type SaveResult = { ok: true; saved: PluginInstance } | { ok: false; message: string; errors: MappedErrors | null };
</script>

<script lang="ts">
	import { tick, untrack } from 'svelte';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import { toasts } from '$lib/toast.svelte';
	import {
		SEGMENTED_MAX,
		VISIBILITIES,
		applyPreset,
		approveUpdate,
		communityMeta,
		draftOf,
		fieldProblems,
		instanceComplete,
		instanceFrom,
		isCommunity,
		manifestOf,
		missingRequired,
		optionLabel,
		pendingUpdate,
		pluginName,
		presetInputs,
		presetLabel,
		settingHelp,
		settingLabel,
		settingPlaceholder,
		settingsSpecs,
		slotsOf,
		statusKind,
		statusOf,
		suggestions,
		withdrawnReason,
		type CatalogPlugin,
		type Draft,
		type FramePlugins,
		type SettingSpec,
		type Slot,
		type Visibility
	} from '$lib/plugins';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';
	import EntityPicker from './EntityPicker.svelte';
	import PermissionList from './PermissionList.svelte';
	import TimeZonePicker from './TimeZonePicker.svelte';

	let {
		open = $bindable(false),
		plugin,
		instance,
		isNew,
		status,
		onsave,
		onremove,
		dismissed = [],
		ondismiss
	}: {
		open?: boolean;
		plugin: CatalogPlugin;
		instance: PluginInstance;
		isNew: boolean;
		/** The frame's latest report (polled while this is open). */
		status: FramePlugins | null;
		onsave: (instance: PluginInstance) => Promise<SaveResult>;
		onremove: (instance: PluginInstance) => void;
		/** Updates put off for now, as `<instance id>@<version>`. */
		dismissed?: string[];
		ondismiss?: (instanceId: string, version: string) => void;
	} = $props();

	const uid = $props.id();
	const m = $derived(manifestOf(plugin));
	const specs = $derived(settingsSpecs(m));
	const loc = $derived(i18n.locale);
	const name = $derived(pluginName(plugin, loc));

	/** The instance as last saved (null until a new one is saved). */
	let stored = $state<PluginInstance | null>(untrack(() => (isNew ? null : instance)));
	let current = $state<PluginInstance>(untrack(() => instance));

	function initialDraft(inst: PluginInstance, fresh: boolean): Draft {
		const d = draftOf(manifestOf(plugin), inst);
		// A new instance, or one saved off because it was incomplete, is meant to show once it is complete.
		d.enabled = fresh || inst.enabled !== false || !instanceComplete(manifestOf(plugin), inst);
		return d;
	}

	let draft = $state<Draft>(untrack(() => initialDraft(instance, isNew)));
	let showMissing = $state(false);
	let serverErrors = $state<Record<string, string>>({});
	let general = $state<string | null>(null);
	let notice = $state<string | null>(null);
	let saving = $state(false);

	const problems = $derived({ ...fieldProblems(m, draft, showMissing && draft.enabled), ...serverErrors });
	const st = $derived(stored ? statusOf(status, stored.instance_id) : undefined);
	const kind = $derived(stored ? statusKind(m, stored, st) : null);
	const entities = $derived(st?.entities);
	const hasEntities = $derived(specs.some((s) => s.type === 'entity'));
	const firstEntityKey = $derived(specs.find((s) => s.type === 'entity')?.key);
	const slots = $derived(slotsOf(m));
	const presets = $derived(m.presets ?? []);
	const community = $derived(isCommunity(plugin));
	const meta = $derived(community ? communityMeta(m) : null);
	const update = $derived(stored ? pendingUpdate(plugin, stored, st) : null);
	const showUpdate = $derived(!!update && !!stored && kind !== 'withdrawn' && !dismissed.includes(`${stored.instance_id}@${update.version}`));

	const fid = (key: string) => `${uid}-f-${key}`;
	const PROBLEM: Record<string, MessageKey> = {
		required: 'plugins.errRequired',
		number: 'plugins.errNumber',
		color: 'plugins.errColor',
		url: 'plugins.errUrl',
		enum: 'plugins.errEnum',
		timezone: 'plugins.errTimezone',
		unknown: 'plugins.errUnknown'
	};
	const problemText = (code: string) => t(PROBLEM[code] ?? 'plugins.errType');

	function str(key: string): string {
		const v = draft.values[key];
		return typeof v === 'string' ? v : '';
	}
	function setValue(key: string, v: string | boolean | string[]) {
		draft.values[key] = v;
		clearError(key);
	}
	function clearError(key: string) {
		if (serverErrors[key]) {
			const { [key]: _gone, ...rest } = serverErrors;
			serverErrors = rest;
		}
	}
	function describedBy(s: SettingSpec): string | undefined {
		const ids = [settingHelp(plugin, s, loc) ? `${fid(s.key)}-help` : '', problems[s.key] ? `${fid(s.key)}-err` : ''].filter(Boolean);
		return ids.length ? ids.join(' ') : undefined;
	}

	// ------------------------------------------------------------ secrets
	function changeSecret(key: string) {
		draft.secrets[key] = { ...draft.secrets[key]!, action: 'set', value: '' };
		void tick().then(() => document.getElementById(fid(key))?.focus());
	}
	function keepSecret(key: string) {
		draft.secrets[key] = { ...draft.secrets[key]!, action: 'keep', value: '' };
	}

	// ------------------------------------------------------------ presets
	let presetPick = $state('');
	let presetPending = $state<string | null>(null);
	let presetAnnounce = $state('');
	const pendingPreset = $derived(presets.find((p) => p.id === presetPending));
	const pendingInputs = $derived(pendingPreset ? presetInputs(pendingPreset) : []);
	let presetTried = $state(false);

	function pickPreset(id: string) {
		presetPick = id;
		if (!id) return;
		const p = presets.find((x) => x.id === id);
		if (!p) return;
		if (presetInputs(p).length === 0) runPreset(id);
		else {
			presetPending = id;
			presetTried = false;
			void tick().then(() => document.getElementById(`${uid}-pi-${presetInputs(p)[0]}`)?.focus());
		}
	}
	function runPreset(id: string) {
		const p = presets.find((x) => x.id === id);
		const next = applyPreset(m, id, draft);
		if (!next || !p) {
			presetTried = true;
			return;
		}
		draft = next;
		presetPending = null;
		presetPick = '';
		presetAnnounce = t('plugins.presetApplied', { name: presetLabel(plugin, p, loc) });
	}

	// ------------------------------------------------------------ save
	async function focusFirstProblem() {
		await tick();
		const key = specs.find((s) => problems[s.key])?.key;
		if (key) document.getElementById(fid(key))?.focus();
	}

	async function save() {
		if (saving) return;
		serverErrors = {};
		general = null;
		notice = null;
		const typeProblems = fieldProblems(m, draft, false);
		if (Object.keys(typeProblems).length) {
			await focusFirstProblem();
			return;
		}
		const missing = missingRequired(m, draft);
		const wanted = draft.enabled;
		// An incomplete form is saved switched off: the frame can then list what it finds (entities) meanwhile.
		const enabled = wanted && missing.length === 0;
		saving = true;
		const res = await onsave(instanceFrom(m, current, draft, enabled, community));
		saving = false;
		if (!res.ok) {
			serverErrors = res.errors?.fields ?? {};
			const inst = res.errors?.instance ?? {};
			general = Object.keys(serverErrors).length && !Object.keys(inst).length ? null : res.message;
			await focusFirstProblem();
			return;
		}
		stored = res.saved;
		current = res.saved;
		draft = { ...draftOf(m, res.saved), enabled: wanted };
		if (wanted && missing.length) {
			showMissing = true;
			notice = t('plugins.savedIncomplete');
			await focusFirstProblem();
		} else {
			toasts.success(t('plugins.saved', { name }));
			open = false;
		}
	}

	// ------------------------------------------------------------ registry updates (§7)
	let approving = $state(false);

	/** Approves the waiting update: the instance is saved with its version, like any other change. */
	async function approve() {
		if (!stored || !update || saving || approving) return;
		const version = update.version;
		general = null;
		approving = true;
		const res = await onsave(approveUpdate(stored, version));
		approving = false;
		if (!res.ok) {
			general = res.message;
			return;
		}
		stored = res.saved;
		current = { ...current, version: res.saved.version };
		toasts.success(t('plugins.updateApproved', { version }));
	}

	const VIS: Record<Visibility, MessageKey> = { always: 'plugins.visAlways', on_change: 'plugins.visOnChange', on_tap: 'plugins.visOnTap' };
	const SLOT: Record<Slot, MessageKey> = { 'top-left': 'plugins.slotTopLeft', 'top-right': 'plugins.slotTopRight' };
</script>

{#snippet fieldError(s: SettingSpec)}
	{#if problems[s.key]}
		<span id="{fid(s.key)}-err" class="error-text">{problemText(problems[s.key]!)}</span>
	{/if}
{/snippet}

{#snippet help(s: SettingSpec)}
	{#if settingHelp(plugin, s, loc)}<span id="{fid(s.key)}-help" class="hint">{settingHelp(plugin, s, loc)}</span>{/if}
{/snippet}

<Dialog bind:open title={name} size="md">
	<form
		id="{uid}-form"
		class="stack editor"
		novalidate
		onsubmit={(e) => {
			e.preventDefault();
			void save();
		}}
	>
		{#if kind === 'withdrawn'}
			<div class="callout callout-error small" role="status">
				<Icon name="alert" size={18} />
				<div class="stack-sm">
					<strong class="wrap">{t('plugins.withdrawn', { reason: withdrawnReason(st) ?? '' })}</strong>
					<span>{t('plugins.withdrawnHint')}</span>
					{#if stored}
						<button type="button" class="btn btn-danger-ghost btn-sm self-start" onclick={() => stored && onremove(stored)}>
							<Icon name="trash" size={16} />{t('plugins.remove')}
						</button>
					{/if}
				</div>
			</div>
		{:else if kind === 'error'}
			<p class="callout callout-error small" role="status"><Icon name="alert" size={18} /><span class="wrap">{st?.error}</span></p>
		{:else if kind === 'waiting'}
			<p class="callout small" role="status"><span class="spinner" aria-hidden="true"></span>{t('plugins.statusWaiting')}</p>
		{:else if kind === 'running'}
			<p class="callout callout-ok small" role="status"><Icon name="check" size={18} />{t('plugins.statusRunning')}</p>
		{/if}
		{#if showUpdate && update && stored}
			<div class="callout callout-warn small update" role="region" aria-label={t('plugins.updateWaiting')} in:softFly>
				<Icon name="shield" size={18} />
				<div class="stack-sm grow">
					<strong>{t(update.fewer ? 'plugins.updateFewer' : 'plugins.updateAsks', { version: update.version })}</strong>
					{#if !update.fewer}<PermissionList items={update.asks} {plugin} empty />{/if}
					<div class="row-wrap">
						<button type="button" class="btn btn-primary btn-sm" disabled={approving || saving} onclick={approve}>
							{#if approving}<span class="spinner" aria-hidden="true"></span>{/if}{t('plugins.updateApprove')}
						</button>
						<button type="button" class="btn btn-ghost btn-sm" onclick={() => stored && update && ondismiss?.(stored.instance_id, update.version)}>
							{t('plugins.updateDismiss')}
						</button>
					</div>
				</div>
			</div>
		{/if}
		{#if notice}
			<p class="callout callout-warn small" role="status" in:softFly><Icon name="info" size={18} />{notice}</p>
		{/if}
		{#if general}
			<p class="callout callout-error small" role="alert" in:softFly><Icon name="alert" size={18} />{general}</p>
		{/if}

		{#if presets.length}
			<div class="field preset">
				<label for="{uid}-preset">{t('plugins.fillInFor')}</label>
				<select id="{uid}-preset" class="select" value={presetPick} onchange={(e) => pickPreset(e.currentTarget.value)}>
					<option value="">{t('plugins.fillInForPick')}</option>
					{#each presets as p (p.id)}<option value={p.id}>{presetLabel(plugin, p, loc)}</option>{/each}
				</select>
				{#if pendingPreset}
					<div class="preset-ask stack-sm" in:softFly>
						<p class="small">{t('plugins.presetNeeds')}</p>
						{#each pendingInputs as k (k)}
							{@const s = specs.find((x) => x.key === k)}
							{#if s}
								<div class="field">
									<label for="{uid}-pi-{k}">{settingLabel(plugin, s, loc)}</label>
									<input
										id="{uid}-pi-{k}"
										class="input"
										type="text"
										autocomplete="off"
										autocapitalize="off"
										spellcheck="false"
										placeholder={settingPlaceholder(plugin, s, loc)}
										value={str(k)}
										aria-invalid={(presetTried && !str(k).trim()) || undefined}
										oninput={(e) => setValue(k, e.currentTarget.value)}
										onkeydown={(e) => {
											if (e.key === 'Enter') {
												e.preventDefault();
												runPreset(pendingPreset.id);
											}
										}}
									/>
									{#if presetTried && !str(k).trim()}<span class="error-text">{t('plugins.errRequired')}</span>{/if}
								</div>
							{/if}
						{/each}
						<div class="row-wrap">
							<button type="button" class="btn btn-soft btn-sm" onclick={() => runPreset(pendingPreset.id)}>{t('plugins.presetApply')}</button>
							<button
								type="button"
								class="btn btn-ghost btn-sm"
								onclick={() => {
									presetPending = null;
									presetPick = '';
								}}>{t('common.cancel')}</button
							>
						</div>
					</div>
				{/if}
				<span class="visually-hidden" role="status" aria-live="polite">{presetAnnounce}</span>
			</div>
		{/if}

		{#each specs as s (s.key)}
			{#if s.type === 'entity' && s.key === firstEntityKey && hasEntities && !entities?.length}
				<p class="callout small"><Icon name="info" size={18} />{t('plugins.entityNone')}</p>
			{/if}
			{#if s.type === 'boolean'}
				<div class="field">
					<label class="switch">
						<input
							id={fid(s.key)}
							type="checkbox"
							checked={draft.values[s.key] === true}
							aria-describedby={describedBy(s)}
							onchange={(e) => setValue(s.key, e.currentTarget.checked)}
						/>
						<span class="s-label">{settingLabel(plugin, s, loc)}</span>
					</label>
					{@render help(s)}
					{@render fieldError(s)}
				</div>
			{:else if s.type === 'choice' && (s.options ?? []).length <= SEGMENTED_MAX}
				<fieldset class="fs" aria-describedby={describedBy(s)}>
					<legend class="label">{settingLabel(plugin, s, loc)}</legend>
					<div class="segmented">
						{#each s.options ?? [] as o, i (o)}
							<label>
								<input
									id={i === 0 ? fid(s.key) : undefined}
									type="radio"
									name="{uid}-{s.key}"
									value={o}
									checked={str(s.key) === o}
									onchange={() => setValue(s.key, o)}
								/>{optionLabel(plugin, s.key, o, loc)}
							</label>
						{/each}
					</div>
					{@render help(s)}
					{@render fieldError(s)}
				</fieldset>
			{:else}
				<div class="field">
					<label for={fid(s.key)}>{settingLabel(plugin, s, loc)}</label>
					{#if s.type === 'choice'}
						<select
							id={fid(s.key)}
							class="select"
							value={str(s.key)}
							aria-invalid={!!problems[s.key] || undefined}
							aria-describedby={describedBy(s)}
							onchange={(e) => setValue(s.key, e.currentTarget.value)}
						>
							{#if !str(s.key) || s.default === undefined}<option value="">{t('plugins.choiceNone')}</option>{/if}
							{#each s.options ?? [] as o (o)}<option value={o}>{optionLabel(plugin, s.key, o, loc)}</option>{/each}
						</select>
					{:else if s.type === 'entity'}
						<EntityPicker
							id={fid(s.key)}
							spec={s}
							{entities}
							placeholder={settingPlaceholder(plugin, s, loc)}
							invalid={!!problems[s.key]}
							describedby={describedBy(s)}
							bind:value={() => (Array.isArray(draft.values[s.key]) ? (draft.values[s.key] as string[]) : []), (v) => setValue(s.key, v)}
						/>
					{:else if s.type === 'timezone'}
						<TimeZonePicker
							id={fid(s.key)}
							placeholder={settingPlaceholder(plugin, s, loc)}
							invalid={!!problems[s.key]}
							describedby={describedBy(s)}
							bind:value={() => str(s.key), (v) => setValue(s.key, v)}
						/>
					{:else if s.type === 'secret'}
						{@const sec = draft.secrets[s.key]!}
						{#if sec.isSet && sec.action === 'keep'}
							<div class="secret-set">
								<span class="badge"><Icon name="check" size={14} />{t('plugins.secretSet')}</span>
								<span class="spacer"></span>
								<button type="button" class="btn btn-ghost btn-sm" onclick={() => changeSecret(s.key)}>{t('plugins.secretChange')}</button>
								<button
									type="button"
									class="btn btn-danger-ghost btn-sm"
									onclick={() => (draft.secrets[s.key] = { ...sec, action: 'remove', value: '' })}>{t('plugins.secretRemove')}</button
								>
							</div>
						{:else if sec.action === 'remove'}
							<div class="secret-set">
								<span class="small muted">{t('plugins.secretRemoving')}</span>
								<span class="spacer"></span>
								<button type="button" class="btn btn-ghost btn-sm" onclick={() => keepSecret(s.key)}>{t('common.undo')}</button>
							</div>
						{:else}
							<div class="secret-edit">
								<input
									id={fid(s.key)}
									class="input"
									type="password"
									autocomplete="new-password"
									spellcheck="false"
									placeholder={settingPlaceholder(plugin, s, loc)}
									value={sec.value}
									aria-invalid={!!problems[s.key] || undefined}
									aria-describedby={describedBy(s)}
									oninput={(e) => {
										draft.secrets[s.key] = { ...sec, action: 'set', value: e.currentTarget.value };
										clearError(s.key);
									}}
								/>
								{#if sec.isSet}
									<button type="button" class="btn btn-ghost btn-sm" onclick={() => keepSecret(s.key)}>{t('common.cancel')}</button>
								{/if}
							</div>
						{/if}
					{:else}
						<input
							id={fid(s.key)}
							class="input"
							class:color={s.type === 'color'}
							type={s.type === 'number' ? 'number' : s.type === 'url' ? 'url' : 'text'}
							inputmode={s.type === 'number' ? 'decimal' : s.type === 'url' ? 'url' : undefined}
							step={s.type === 'number' ? 'any' : undefined}
							autocomplete="off"
							autocapitalize={s.type === 'text' ? undefined : 'off'}
							spellcheck={s.type === 'text' ? undefined : 'false'}
							placeholder={settingPlaceholder(plugin, s, loc) || (s.type === 'color' ? '#rrggbb' : '')}
							value={str(s.key)}
							aria-invalid={!!problems[s.key] || undefined}
							aria-describedby={describedBy(s)}
							oninput={(e) => setValue(s.key, e.currentTarget.value)}
						/>
						{#if suggestions(status, s).length}
							<div class="found" role="group" aria-label={t('plugins.found')}>
								<span class="tiny muted">{t('plugins.found')}</span>
								{#each suggestions(status, s) as svc (svc.url)}
									<button
										type="button"
										class="chip found-chip"
										aria-pressed={str(s.key) === svc.url}
										title={svc.url}
										onclick={() => setValue(s.key, svc.url)}
									>
										<Icon name="wifi" size={14} /><span>{svc.name}</span><span class="tiny muted">{svc.url.replace(/^https?:\/\//, '')}</span>
									</button>
								{/each}
							</div>
						{/if}
					{/if}
					{@render help(s)}
					{@render fieldError(s)}
				</div>
			{/if}
		{/each}

		<fieldset class="fs">
			<legend class="label">{t('plugins.corner')}</legend>
			<div class="segmented">
				{#each slots as sl (sl)}
					<label><input type="radio" name="{uid}-slot" value={sl} bind:group={draft.slot} />{t(SLOT[sl])}</label>
				{/each}
			</div>
		</fieldset>
		<fieldset class="fs">
			<legend class="label">{t('plugins.when')}</legend>
			<div class="segmented">
				{#each VISIBILITIES as v (v)}
					<label><input type="radio" name="{uid}-vis" value={v} bind:group={draft.visibility} />{t(VIS[v])}</label>
				{/each}
			</div>
		</fieldset>
		<label class="switch">
			<input type="checkbox" bind:checked={draft.enabled} onchange={() => draft.enabled && stored && (showMissing = true)} />
			<span class="s-label">{t('plugins.showSwitch')}</span>
		</label>
		{#if meta || st?.version}
			<p class="tiny muted details">
				{#if meta}
					<span class="badge"><Icon name="users" size={12} />{t('plugins.groupCommunity')}</span>
					<span>
						{[meta.author ? t('plugins.byAuthor', { name: meta.author }) : '', meta.license ?? ''].filter(Boolean).join(' · ')}
					</span>
				{/if}
				{#if st?.version}<span>{t('plugins.runsVersion', { version: st.version })}</span>{/if}
			</p>
		{/if}
	</form>
	{#snippet footer()}
		{#if stored}
			<button type="button" class="btn btn-danger-ghost remove" onclick={() => stored && onremove(stored)}>
				<Icon name="trash" size={16} />{t('plugins.remove')}
			</button>
		{/if}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="submit" form="{uid}-form" class="btn btn-primary" disabled={saving}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
	{/snippet}
</Dialog>

<style>
	.editor {
		min-width: 0;
	}
	.fs {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
		min-width: 0;
	}
	.fs .segmented {
		align-self: flex-start;
	}
	.s-label {
		font-weight: 500;
	}
	.field {
		min-width: 0;
	}
	.callout {
		margin: 0;
	}
	.wrap {
		overflow-wrap: anywhere;
	}
	.preset-ask {
		padding: var(--s-3);
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.preset-ask p {
		margin: 0;
	}
	.secret-set,
	.secret-edit {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		flex-wrap: wrap;
	}
	.secret-set {
		min-height: 44px;
	}
	.secret-edit .input {
		flex: 1 1 200px;
		min-width: 0;
	}
	.badge {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.found {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
	}
	.found-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		cursor: pointer;
		max-width: 100%;
		min-height: 36px;
		white-space: normal;
		overflow-wrap: anywhere;
		text-align: start;
	}
	.found-chip[aria-pressed='true'] {
		border-color: var(--primary);
		color: var(--text);
	}
	.remove {
		margin-inline-end: auto;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
	.update strong {
		overflow-wrap: anywhere;
	}
	.self-start {
		align-self: flex-start;
	}
	.details {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px 10px;
		margin: 0;
		overflow-wrap: anywhere;
	}
</style>
