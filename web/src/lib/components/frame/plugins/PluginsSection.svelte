<script lang="ts">
	import { ApiError, type FrameSettings } from '$lib/api/client';
	import { getFramePlugins, savePluginInstance } from '$lib/frameSettings';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import {
		MAX_INSTANCES,
		catalogGroups,
		communityMeta,
		findPlugin,
		instanceComplete,
		internetHosts,
		isCommunity,
		manifestOf,
		mapErrors,
		newInstance,
		pluginDescription,
		pendingUpdate,
		permissionsOf,
		pluginName,
		statusKind,
		statusOf,
		usesLocation,
		withdrawnReason,
		type CatalogPlugin,
		type FramePlugins,
		type InstanceOp,
		type PluginInstance,
		type Slot
	} from '$lib/plugins';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';
	import PermissionList from './PermissionList.svelte';
	import PluginEditor, { type SaveResult } from './PluginEditor.svelte';

	type Layout = NonNullable<NonNullable<FrameSettings['plugins']>['layout']>;

	let {
		frameId,
		layout = $bindable(),
		instances,
		version,
		onsaved
	}: {
		frameId: string;
		/** The layout in the settings form (saved with the rest of the form). */
		layout: Layout;
		/** The saved instances (instances are saved one change at a time, §6.1). */
		instances: PluginInstance[];
		version: number;
		onsaved: (settings: FrameSettings) => void;
	} = $props();

	const POLL_MS = 3000;

	let fp = $state<FramePlugins | null>(null);
	let fpError = $state<unknown>(null);

	async function refresh() {
		try {
			fp = await getFramePlugins(frameId);
			fpError = null;
		} catch (e) {
			// Keep showing the last report while polling; only a first failure replaces the section.
			if (!fp) fpError = e;
		}
	}

	$effect(() => {
		void frameId;
		fp = null;
		fpError = null;
		void refresh();
	});

	const loc = $derived(i18n.locale);
	const noCatalog = $derived(fp != null && !fp.catalog_at);
	const groups = $derived(catalogGroups(fp?.catalog, loc, i18n.tag));
	const catalogEmpty = $derived(groups.builtin.length + groups.community.length === 0);
	const full = $derived(instances.length >= MAX_INSTANCES);

	// ------------------------------------------------------------ editor
	let editor = $state<{ key: number; plugin: CatalogPlugin; instance: PluginInstance; isNew: boolean } | null>(null);
	let editorOpen = $state(false);
	let editorKey = 0;

	// Poll the frame's report (errors, entities, what it found) while an editor is open.
	$effect(() => {
		if (!editorOpen) return;
		const h = setInterval(() => {
			if (document.visibilityState === 'visible') void refresh();
		}, POLL_MS);
		return () => clearInterval(h);
	});

	function edit(inst: PluginInstance) {
		const plugin = findPlugin(fp?.catalog, inst.plugin_id);
		if (!plugin) return;
		editor = { key: ++editorKey, plugin, instance: inst, isNew: false };
		editorOpen = true;
		void refresh();
	}

	let addOpen = $state(false);

	// A community plugin first says what it may do (§7): Add / Cancel.
	let confirming = $state<CatalogPlugin | null>(null);
	let confirmOpen = $state(false);

	function pick(plugin: CatalogPlugin) {
		addOpen = false;
		if (isCommunity(plugin)) {
			confirming = plugin;
			confirmOpen = true;
		} else add(plugin);
	}

	function confirmAdd() {
		confirmOpen = false;
		if (confirming) add(confirming);
	}

	function add(plugin: CatalogPlugin) {
		addOpen = false;
		editor = { key: ++editorKey, plugin, instance: newInstance(manifestOf(plugin), instances), isNew: true };
		editorOpen = true;
		void refresh();
	}

	// ------------------------------------------------------------ saving
	async function commit(op: InstanceOp) {
		return savePluginInstance(frameId, { version, instances }, op);
	}

	async function saveFromEditor(inst: PluginInstance): Promise<SaveResult> {
		const r = await commit({ kind: 'upsert', instance: inst });
		if (!r.ok) {
			const errors = r.error instanceof ApiError && r.error.status === 422 ? mapErrors(r.error.errors, r.index) : null;
			return { ok: false, message: problemMessage(r.error), errors };
		}
		onsaved(r.settings);
		void refresh();
		const saved = r.settings.plugins?.instances?.find((x) => x.instance_id === inst.instance_id) ?? inst;
		return { ok: true, saved };
	}

	let busy = $state<string | null>(null);

	async function toggle(inst: PluginInstance, input: HTMLInputElement) {
		const on = input.checked;
		const plugin = findPlugin(fp?.catalog, inst.plugin_id);
		if (on && plugin && !instanceComplete(manifestOf(plugin), inst)) {
			// It can't show yet: open its form instead, which marks what is missing.
			input.checked = false;
			edit(inst);
			return;
		}
		busy = inst.instance_id;
		const r = await commit({ kind: 'upsert', instance: { ...inst, enabled: on } });
		busy = null;
		if (r.ok) {
			onsaved(r.settings);
			void refresh();
		} else {
			input.checked = !on;
			toasts.error(problemMessage(r.error));
		}
	}

	let removing = $state<PluginInstance | null>(null);
	let removeOpen = $state(false);

	function askRemove(inst: PluginInstance) {
		editorOpen = false;
		removing = inst;
		removeOpen = true;
	}

	async function remove() {
		const inst = removing;
		if (!inst) return;
		removeOpen = false;
		const r = await commit({ kind: 'remove', id: inst.instance_id });
		if (r.ok) {
			onsaved(r.settings);
			toasts.success(t('plugins.removed', { name: nameOf(inst) }));
		} else toasts.error(problemMessage(r.error));
	}

	// Updates the owner put off for now ("Not now"), by instance and version; not kept.
	let dismissed = $state<string[]>([]);
	const dismissKey = (id: string, v: string) => `${id}@${v}`;

	// ------------------------------------------------------------ list
	function nameOf(inst: PluginInstance): string {
		const p = findPlugin(fp?.catalog, inst.plugin_id);
		return p ? pluginName(p, loc) : inst.plugin_id;
	}

	const SLOT: Record<Slot, MessageKey> = { 'top-left': 'plugins.slotTopLeft', 'top-right': 'plugins.slotTopRight' };
	const MODES: [NonNullable<Layout['mode']>, MessageKey][] = [
		['off', 'plugins.layoutOff'],
		['overlay', 'plugins.layoutOverlay'],
		['band', 'plugins.layoutBand'],
		['split', 'plugins.layoutSplit']
	];
	const MODE_HINT: Record<NonNullable<Layout['mode']>, MessageKey> = {
		off: 'plugins.layoutOffHint',
		overlay: 'plugins.layoutOverlayHint',
		band: 'plugins.layoutBandHint',
		split: 'plugins.layoutSplitHint'
	};
</script>

<section class="card card-pad stack plugins" aria-labelledby="pl-title">
	<div class="section-title">
		<h2 id="pl-title">{t('plugins.title')}</h2>
		{#if fp && !noCatalog}
			<button type="button" class="btn btn-ghost btn-sm" disabled={full} onclick={() => (addOpen = true)}><Icon name="plus" size={16} />{t('plugins.add')}</button>
		{/if}
	</div>
	<p class="hint">{t('plugins.hint')}</p>

	{#if fpError}
		<div class="callout callout-error small" role="alert">
			<Icon name="alert" size={18} />
			<div class="stack-sm"><span>{problemMessage(fpError)}</span><button type="button" class="btn btn-sm" onclick={refresh}>{t('common.tryAgain')}</button></div>
		</div>
	{:else if noCatalog}
		<div class="callout callout-warn small" role="status">
			<Icon name="info" size={18} />
			<div class="stack-sm"><strong>{t('plugins.updateApp')}</strong><span>{t('plugins.updateAppBody')}</span></div>
		</div>
	{/if}

	<fieldset class="fs">
		<legend class="label">{t('plugins.layout')}</legend>
		<div class="segmented">
			{#each MODES as [v, l] (v)}
				<label><input type="radio" name="pl-mode" value={v} bind:group={layout.mode} />{t(l)}</label>
			{/each}
		</div>
		<span class="hint">{t(MODE_HINT[layout.mode ?? 'off'])}</span>
	</fieldset>
	{#if layout.mode === 'split'}
		<fieldset class="fs" in:softFly>
			<legend class="label">{t('plugins.splitSide')}</legend>
			<div class="segmented">
				<label><input type="radio" name="pl-side" value="left" bind:group={layout.side} />{t('plugins.sideLeft')}</label>
				<label><input type="radio" name="pl-side" value="right" bind:group={layout.side} />{t('plugins.sideRight')}</label>
			</div>
		</fieldset>
	{/if}
	{#if layout.mode === 'band'}
		<div class="two" in:softFly>
			<fieldset class="fs">
				<legend class="label">{t('plugins.bandSide')}</legend>
				<div class="segmented">
					<label><input type="radio" name="pl-side" value="left" bind:group={layout.side} />{t('plugins.sideLeft')}</label>
					<label><input type="radio" name="pl-side" value="right" bind:group={layout.side} />{t('plugins.sideRight')}</label>
				</div>
			</fieldset>
			<fieldset class="fs">
				<legend class="label">{t('plugins.bandBackground')}</legend>
				<div class="segmented">
					<label><input type="radio" name="pl-bg" value="paper" bind:group={layout.background} />{t('plugins.bgPaper')}</label>
					<label><input type="radio" name="pl-bg" value="photo" bind:group={layout.background} />{t('plugins.bgPhoto')}</label>
				</div>
			</fieldset>
		</div>
	{/if}

	{#if instances.length === 0}
		{#if !noCatalog}<p class="muted small empty">{t('plugins.none')}</p>{/if}
	{:else}
		<ul class="list" aria-label={t('plugins.instances')}>
			{#each instances as inst (inst.instance_id)}
				{@const plugin = findPlugin(fp?.catalog, inst.plugin_id)}
				{@const m = plugin ? manifestOf(plugin) : undefined}
				{@const st = statusOf(fp, inst.instance_id)}
				{@const kind = fp && !noCatalog ? statusKind(m, inst, st) : null}
				{@const update = pendingUpdate(plugin, inst, st)}
				<li class="inst" class:off={inst.enabled === false}>
					<button type="button" class="inst-main" disabled={!plugin} onclick={() => edit(inst)} aria-label={t('plugins.edit', { name: nameOf(inst) })}>
						<span class="inst-name">{nameOf(inst)}</span>
						<span class="inst-meta small muted">
							<span>{t(SLOT[inst.slot ?? 'top-right'])}</span>
							{#if kind === 'withdrawn'}
								<span class="err"><Icon name="alert" size={14} />{t('plugins.withdrawn', { reason: withdrawnReason(st) ?? '' })}</span>
							{:else if kind === 'error'}
								<span class="err"><Icon name="alert" size={14} />{st?.error}</span>
							{:else if kind === 'waiting'}
								<span>· {t('plugins.statusWaiting')}</span>
							{:else if kind === 'incomplete'}
								<span class="warn">· {t('plugins.statusIncomplete')}</span>
							{:else if kind === 'not_on_frame'}
								<span class="warn">· {t('plugins.statusNotOnFrame')}</span>
							{/if}
							{#if update && kind !== 'withdrawn' && !dismissed.includes(dismissKey(inst.instance_id, update.version))}
								<span class="update"><Icon name="shield" size={14} />{t('plugins.updateWaiting')}</span>
							{/if}
						</span>
					</button>
					{#if plugin}
						<label class="switch compact">
							<input
								type="checkbox"
								checked={inst.enabled !== false}
								disabled={busy === inst.instance_id}
								aria-label={t('plugins.showOnFrame', { name: nameOf(inst) })}
								onchange={(e) => toggle(inst, e.currentTarget)}
							/>
						</label>
					{:else}
						<button type="button" class="btn btn-danger-ghost btn-icon btn-sm" aria-label={t('plugins.removeConfirm', { name: nameOf(inst) })} onclick={() => askRemove(inst)}>
							<Icon name="trash" size={16} />
						</button>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
	{#if full}<p class="hint">{t('plugins.max', { n: MAX_INSTANCES })}</p>{/if}
</section>

{#snippet catalogEntry(p: CatalogPlugin)}
	{@const m = manifestOf(p)}
	{@const hosts = internetHosts(m)}
	{@const meta = isCommunity(p) ? communityMeta(m) : null}
	<li class="cat">
		<div class="cat-text">
			<span class="inst-name">{pluginName(p, loc)}</span>
			{#if meta}
				<span class="tiny muted meta">
					{[meta.author ? t('plugins.byAuthor', { name: meta.author }) : '', meta.license ?? '', meta.version ? t('plugins.versionNumber', { version: meta.version }) : '']
						.filter(Boolean)
						.join(' · ')}
				</span>
			{/if}
			{#if pluginDescription(p, loc)}<span class="small muted">{pluginDescription(p, loc)}</span>{/if}
			<span class="perm tiny">
				<Icon name={hosts.length ? 'globe' : 'wifi'} size={14} />
				{hosts.length ? t('plugins.reachesInternet', { hosts: hosts.join(', ') }) : t('plugins.ownAddressesOnly')}
			</span>
			{#if usesLocation(m)}
				<span class="perm tiny"><Icon name="mapPin" size={14} />{t('plugins.usesLocation')}</span>
			{/if}
		</div>
		<button type="button" class="btn btn-soft btn-sm" onclick={() => pick(p)} aria-label={t('plugins.addNamed', { name: pluginName(p, loc) })}>
			<Icon name="plus" size={16} />{t('plugins.addOne')}
		</button>
	</li>
{/snippet}

<Dialog bind:open={addOpen} title={t('plugins.add')} size="md">
	{#if catalogEmpty}
		<p class="muted">{t('plugins.catalogEmpty')}</p>
	{:else}
		<div class="stack groups">
			{#if groups.builtin.length}
				<section class="group" aria-labelledby="pl-g-builtin">
					{#if groups.community.length}<h3 id="pl-g-builtin" class="group-title">{t('plugins.groupBuiltin')}</h3>{/if}
					<ul class="catalog">
						{#each groups.builtin as p (manifestOf(p).id)}{@render catalogEntry(p)}{/each}
					</ul>
				</section>
			{/if}
			{#if groups.community.length}
				<section class="group" aria-labelledby="pl-g-community">
					<h3 id="pl-g-community" class="group-title"><Icon name="users" size={16} />{t('plugins.groupCommunity')}</h3>
					<p class="hint">{t('plugins.groupCommunityHint')}</p>
					<ul class="catalog">
						{#each groups.community as p (manifestOf(p).id)}{@render catalogEntry(p)}{/each}
					</ul>
				</section>
			{/if}
		</div>
	{/if}
</Dialog>

<Dialog bind:open={confirmOpen} title={t('plugins.confirmTitle', { name: confirming ? pluginName(confirming, loc) : '' })}>
	{#if confirming}
		{@const meta = communityMeta(manifestOf(confirming))}
		<div class="stack-sm">
			{#if meta.author || meta.license || meta.version}
				<p class="small muted confirm-meta">
					{[meta.author ? t('plugins.byAuthor', { name: meta.author }) : '', meta.license ?? '', meta.version ? t('plugins.versionNumber', { version: meta.version }) : '']
						.filter(Boolean)
						.join(' · ')}
				</p>
			{/if}
			<p class="confirm-body">{t('plugins.confirmBody')}</p>
			<PermissionList items={permissionsOf(manifestOf(confirming).permissions)} plugin={confirming} empty />
		</div>
	{/if}
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (confirmOpen = false)}>{t('common.cancel')}</button>
		<button type="button" class="btn btn-primary" onclick={confirmAdd}>{t('plugins.addOne')}</button>
	{/snippet}
</Dialog>

{#if editor}
	{#key editor.key}
		<PluginEditor
			bind:open={editorOpen}
			plugin={editor.plugin}
			instance={editor.instance}
			isNew={editor.isNew}
			status={fp}
			onsave={saveFromEditor}
			onremove={askRemove}
			{dismissed}
			ondismiss={(id, v) => (dismissed = [...dismissed, dismissKey(id, v)])}
		/>
	{/key}
{/if}

<Dialog bind:open={removeOpen} title={t('plugins.removeConfirm', { name: removing ? nameOf(removing) : '' })}>
	<p class="muted">{t('plugins.removeBody')}</p>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button type="button" class="btn btn-danger" onclick={remove}>{t('plugins.remove')}</button>
	{/snippet}
</Dialog>

<style>
	.plugins {
		min-width: 0;
	}
	h2 {
		font-size: 1.15rem;
	}
	.section-title {
		margin-bottom: 0;
	}
	.callout {
		margin: 0;
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
	.two {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 520px) {
		.two {
			grid-template-columns: 1fr 1fr;
		}
	}
	.empty {
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface-2);
		text-align: center;
		margin: 0;
	}
	.list,
	.catalog {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.inst {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		border: 1px solid var(--border);
		border-radius: var(--r);
		padding: 4px var(--s-3) 4px 4px;
		transition: opacity var(--dur) var(--ease);
	}
	.inst-main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 2px;
		padding: 8px 10px;
		border: 0;
		border-radius: calc(var(--r) - 4px);
		background: none;
		color: inherit;
		font: inherit;
		text-align: start;
		cursor: pointer;
	}
	.inst-main:hover:not(:disabled) {
		background: var(--surface-2);
	}
	.inst-main:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 0;
	}
	.inst-main:disabled {
		cursor: default;
	}
	.inst.off .inst-name {
		color: var(--text-muted);
	}
	.inst-name {
		font-weight: 600;
		overflow-wrap: anywhere;
	}
	.inst-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		min-width: 0;
	}
	.inst-meta .err {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--danger);
		overflow-wrap: anywhere;
	}
	.inst-meta .update {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--primary-soft-text);
		font-weight: 600;
	}
	.inst-meta .warn {
		color: var(--text-muted);
		font-weight: 600;
	}
	.switch.compact {
		min-height: 0;
	}
	.cat {
		display: flex;
		align-items: flex-start;
		gap: var(--s-3);
		padding: var(--s-3);
		border: 1px solid var(--border);
		border-radius: var(--r);
	}
	.cat-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.groups {
		gap: var(--s-4);
	}
	.group {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	.group-title {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		font-size: 0.95rem;
	}
	.group .hint {
		margin: 0;
	}
	.meta {
		overflow-wrap: anywhere;
	}
	.confirm-meta,
	.confirm-body {
		margin: 0;
	}
	.perm {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		color: var(--text-muted);
		overflow-wrap: anywhere;
	}
	@media (max-width: 480px) {
		.cat {
			flex-direction: column;
			align-items: stretch;
		}
	}
</style>
