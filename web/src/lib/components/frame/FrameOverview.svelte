<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Frame, type FrameCommandType, type FrameHealth } from '$lib/api/client';
	import { can, canManage, frames } from '$lib/frames.svelte';
	import { formatBytes, formatDateTime, formatUptime, relativeTime, signalBars } from '$lib/format';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon, { type IconName } from '../Icon.svelte';
	import ReplaceDeviceDialog from './ReplaceDeviceDialog.svelte';
	import TransferDialog from './TransferDialog.svelte';

	let { frame, onupdated }: { frame: Frame; onupdated: (f: Frame) => void } = $props();

	const manage = $derived(canManage(frame));
	const control = $derived(can(frame, 'change_settings'));

	// ---- details
	let name = $state('');
	let location = $state('');
	let savingDetails = $state(false);
	$effect.pre(() => {
		name = frame.name;
		location = frame.location ?? '';
	});
	const dirty = $derived(name.trim() !== frame.name || location.trim() !== (frame.location ?? ''));

	async function saveDetails(e: SubmitEvent) {
		e.preventDefault();
		savingDetails = true;
		try {
			const f = await call(
				api.PATCH('/v1/frames/{frameId}', {
					params: { path: { frameId: frame.id } },
					body: { name: name.trim(), location: location.trim() }
				})
			);
			onupdated(f);
			toasts.success(t('common.saved'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			savingDetails = false;
		}
	}

	// ---- health
	let health = $state<FrameHealth | null>(null);
	let healthError = $state<unknown>(null);
	$effect(() => {
		if (!control) return;
		const frameId = frame.id;
		call(api.GET('/v1/frames/{frameId}/health', { params: { path: { frameId } } }))
			.then((h) => (health = h))
			.catch((e) => (healthError = e));
	});
	const hb = $derived(health?.last_heartbeat);
	const storagePct = $derived(
		hb?.storage_total_bytes ? 1 - (hb.storage_free_bytes ?? 0) / hb.storage_total_bytes : null
	);

	// ---- commands
	const COMMANDS: { type: FrameCommandType; icon: IconName; label: MessageKey; done: MessageKey }[] = [
		{ type: 'previous', icon: 'prev', label: 'cmd.previous', done: 'cmd.donePrevious' },
		{ type: 'next', icon: 'next', label: 'cmd.next', done: 'cmd.doneNext' },
		{ type: 'sleep', icon: 'moon', label: 'cmd.sleep', done: 'cmd.doneSleep' },
		{ type: 'wake', icon: 'sun', label: 'cmd.wake', done: 'cmd.doneWake' },
		{ type: 'identify', icon: 'sparkle', label: 'cmd.identify', done: 'cmd.doneIdentify' }
	];
	let running = $state<FrameCommandType | null>(null);

	async function command(type: FrameCommandType, done: MessageKey) {
		running = type;
		try {
			const res = await call(
				api.POST('/v1/frames/{frameId}/commands', { params: { path: { frameId: frame.id } }, body: { type } })
			);
			if (res.ok) toasts.success(t(done, { name: frame.name }));
			else toasts.error(t('cmd.failed'));
		} catch (e) {
			if (isCode(e, 'frame_offline')) {
				onupdated({ ...frame, online: false });
				frames.setStatus(frame.id, false, frame.last_seen_at ?? null);
			}
			toasts.error(problemMessage(e));
		} finally {
			running = null;
		}
	}

	// ---- leave / remove
	let leaveOpen = $state(false);
	let removeMine = $state(false);
	let removeOpen = $state(false);
	let confirmName = $state('');
	let busy = $state(false);

	async function leave() {
		busy = true;
		try {
			await call(
				api.POST('/v1/frames/{frameId}/leave', {
					params: { path: { frameId: frame.id } },
					body: { remove_my_items: removeMine }
				})
			);
			frames.remove(frame.id);
			leaveOpen = false;
			toasts.success(t('overview.left', { name: frame.name }));
			await goto('/frames');
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	// ---- ownership
	let transferOpen = $state(false);
	let transferLeaving = $state(false);
	let replaceOpen = $state(false);

	function openTransfer(leaving: boolean) {
		transferLeaving = leaving;
		transferOpen = true;
	}

	async function afterTransfer(r: { left: boolean; newOwner: { display_name: string } }) {
		if (r.left) {
			frames.remove(frame.id);
			toasts.success(t('transfer.doneLeft', { name: r.newOwner.display_name, frame: frame.name }));
			await goto('/frames');
			return;
		}
		toasts.success(t('transfer.done', { name: r.newOwner.display_name }));
		try {
			const f = await call(api.GET('/v1/frames/{frameId}', { params: { path: { frameId: frame.id } } }));
			onupdated(f);
		} catch {
			/* the live update will refresh it */
		}
	}

	async function removeFrame() {
		busy = true;
		try {
			await call(api.DELETE('/v1/frames/{frameId}', { params: { path: { frameId: frame.id } } }));
			frames.remove(frame.id);
			removeOpen = false;
			toasts.success(t('overview.removed', { name: frame.name }));
			await goto('/frames');
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}
</script>

<div class="grid">
	<div class="stack">
		{#if control}
			<section class="card card-pad" aria-labelledby="rc-title">
				<div class="section-title">
					<h2 id="rc-title">{t('overview.remote')}</h2>
				</div>
				{#if !frame.online}
					<p class="callout callout-warn small"><Icon name="wifi" size={18} />{t('overview.offlineControl')}</p>
				{/if}
				<div class="commands">
					{#each COMMANDS as c (c.type)}
						<button class="cmd" onclick={() => command(c.type, c.done)} disabled={!frame.online || running !== null}>
							<span class="cmd-icon">
								{#if running === c.type}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={c.icon} size={20} />{/if}
							</span>
							<span>{t(c.label)}</span>
						</button>
					{/each}
				</div>
			</section>
		{/if}

		<section class="card card-pad" aria-labelledby="details-title">
			<div class="section-title"><h2 id="details-title">{t('overview.details')}</h2></div>
			{#if manage}
				<form class="stack" onsubmit={saveDetails}>
					<div class="two">
						<div class="field">
							<label for="f-name">{t('frame.name')}</label>
							<input id="f-name" class="input" required maxlength="40" bind:value={name} />
						</div>
						<div class="field">
							<label for="f-loc">{t('frame.location')}</label>
							<input id="f-loc" class="input" maxlength="40" bind:value={location} placeholder={t('add.locationPlaceholder')} />
						</div>
					</div>
					<div class="row">
						<button class="btn btn-primary" type="submit" disabled={!dirty || savingDetails || !name.trim()}>
							{#if savingDetails}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
						</button>
					</div>
				</form>
			{/if}
			<dl class="facts" class:with-form={manage}>
				{#if frame.owner_name}<div><dt>{t('overview.owner')}</dt><dd>{frame.owner_name}</dd></div>{/if}
				{#if frame.member_count != null}<div><dt>{t('overview.members')}</dt><dd class="num">{frame.member_count}</dd></div>{/if}
				{#if frame.placement_count != null}<div><dt>{t('overview.photos')}</dt><dd class="num">{frame.placement_count}</dd></div>{/if}
				{#if frame.screen}<div><dt>{t('overview.screen')}</dt><dd class="num">{frame.screen.width} × {frame.screen.height}</dd></div>{/if}
				{#if frame.created_at}<div><dt>{t('overview.since')}</dt><dd>{formatDateTime(frame.created_at, i18n.tag)}</dd></div>{/if}
			</dl>
		</section>
	</div>

	<div class="stack">
		{#if control}
			<section class="card card-pad" aria-labelledby="health-title">
				<div class="section-title"><h2 id="health-title">{t('overview.health')}</h2></div>
				{#if healthError}
					<p class="muted small">{problemMessage(healthError)}</p>
				{:else if !health}
					<div class="stack-sm"><div class="skeleton" style="height:16px"></div><div class="skeleton" style="height:16px;width:70%"></div><div class="skeleton" style="height:16px;width:80%"></div></div>
				{:else}
					<ul class="health" role="list">
						<li>
							<Icon name="wifi" size={18} />
							<span class="h-label">{t('health.wifi')}</span>
							<span class="h-val">
								{#if hb?.wifi_rssi_dbm != null}
									<span class="bars" aria-hidden="true">
										{#each [1, 2, 3, 4] as b}<span class:on={signalBars(hb.wifi_rssi_dbm) >= b} style="height:{4 + b * 3}px"></span>{/each}
									</span>
									<span class="num">{hb.wifi_ssid ?? ''} · {hb.wifi_rssi_dbm} dBm</span>
								{:else}–{/if}
							</span>
						</li>
						<li>
							<Icon name="hardDrive" size={18} />
							<span class="h-label">{t('health.storage')}</span>
							<span class="h-val">
								{#if hb?.storage_total_bytes}
									<span class="num">{t('health.free', { free: formatBytes(hb.storage_free_bytes ?? 0, i18n.tag), total: formatBytes(hb.storage_total_bytes, i18n.tag) })}</span>
								{:else}–{/if}
							</span>
						</li>
						{#if storagePct !== null}
							<li class="bar-row"><div class="progress"><span style="width:{Math.round(storagePct * 100)}%"></span></div></li>
						{/if}
						<li>
							<Icon name="image" size={18} />
							<span class="h-label">{t('health.cached')}</span>
							<span class="h-val num">
								{[String(hb?.placements_cached ?? '–'), hb?.placements_missing ? tn('health.missing', hb.placements_missing) : null].filter(Boolean).join(' · ')}
							</span>
						</li>
						<li>
							<Icon name="clock" size={18} />
							<span class="h-label">{t('health.uptime')}</span>
							<span class="h-val num">{hb ? formatUptime(hb.uptime_s) : '–'}</span>
						</li>
						<li>
							<Icon name="info" size={18} />
							<span class="h-label">{t('health.version')}</span>
							<span class="h-val num">{hb?.app_version ?? frame.app_version ?? '–'}</span>
						</li>
						<li>
							<Icon name="refresh" size={18} />
							<span class="h-label">{t('health.lastSeen')}</span>
							<span class="h-val">{health.online ? t('frame.online') : relativeTime(health.last_seen_at, i18n.tag) || '–'}</span>
						</li>
						{#if health.last_unclean_shutdown_at || (hb?.crash_count_24h ?? 0) > 0}
							<li class="warn-row">
								<Icon name="alert" size={18} />
								<span>
									{#if health.last_unclean_shutdown_at}{t('health.unclean', { when: relativeTime(health.last_unclean_shutdown_at, i18n.tag) })}{/if}
									{#if (hb?.crash_count_24h ?? 0) > 0} {tn('health.crashes', hb?.crash_count_24h ?? 0)}{/if}
								</span>
							</li>
						{/if}
					</ul>
				{/if}
			</section>
		{/if}

		{#if frame.role === 'owner'}
			<section class="card card-pad owner-tools" aria-labelledby="own-title">
				<div class="section-title"><h2 id="own-title">{t('overview.ownership')}</h2></div>
				<div class="tool">
					<span class="tool-icon"><Icon name="crown" size={18} /></span>
					<div class="tool-text">
						<strong>{t('transfer.title')}</strong>
						<span class="muted small">{t('overview.transferBody')}</span>
					</div>
					<button class="btn btn-sm" onclick={() => openTransfer(false)}>{t('overview.transferButton')}</button>
				</div>
				<div class="tool">
					<span class="tool-icon"><Icon name="swap" size={18} /></span>
					<div class="tool-text">
						<strong>{t('replace.short')}</strong>
						<span class="muted small">{t('overview.replaceBody')}</span>
					</div>
					<button class="btn btn-sm" onclick={() => (replaceOpen = true)}>{t('overview.replaceButton')}</button>
				</div>
			</section>
		{/if}

		<section class="card card-pad danger" aria-labelledby="dz-title">
			<div class="section-title"><h2 id="dz-title">{frame.role === 'owner' ? t('overview.dangerTitle') : t('overview.leaveTitle')}</h2></div>
			{#if frame.role === 'owner'}
				<p class="muted small">{t('overview.ownerLeaveBody')}</p>
				<div class="row-wrap">
					<button class="btn btn-danger-ghost" onclick={() => openTransfer(true)}><Icon name="logout" size={18} />{t('overview.leaveButton')}</button>
					<button class="btn btn-danger-ghost" onclick={() => (removeOpen = true)}><Icon name="trash" size={18} />{t('overview.removeButton')}</button>
				</div>
			{:else}
				<p class="muted small">{t('overview.leaveBody')}</p>
				<button class="btn btn-danger-ghost" onclick={() => (leaveOpen = true)}><Icon name="logout" size={18} />{t('overview.leaveButton')}</button>
			{/if}
		</section>
	</div>
</div>

<TransferDialog bind:open={transferOpen} {frame} leaving={transferLeaving} ondone={afterTransfer} />
<ReplaceDeviceDialog
	bind:open={replaceOpen}
	{frame}
	onreplaced={(f) => {
		onupdated(f);
		frames.upsert(f);
	}}
/>

<Dialog bind:open={leaveOpen} title={t('overview.leaveConfirm', { name: frame.name })}>
	<div class="stack">
		<p class="muted">{t('overview.leaveDialogBody')}</p>
		<label class="switch"><input type="checkbox" bind:checked={removeMine} /><span>{t('overview.removeMine')}</span></label>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (leaveOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={leave} disabled={busy}>{t('overview.leaveButton')}</button>
	{/snippet}
</Dialog>

<Dialog bind:open={removeOpen} title={t('overview.removeConfirm', { name: frame.name })}>
	<div class="stack">
		<p class="muted">{t('overview.removeDialogBody')}</p>
		<div class="field">
			<label for="confirm-name">{t('overview.typeName', { name: frame.name })}</label>
			<input id="confirm-name" class="input" bind:value={confirmName} autocomplete="off" />
		</div>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={removeFrame} disabled={busy || confirmName.trim() !== frame.name}>{t('overview.removeButton')}</button>
	{/snippet}
</Dialog>

<style>
	.grid {
		display: grid;
		gap: var(--s-4);
		align-items: start;
	}
	@media (min-width: 900px) {
		.grid {
			grid-template-columns: 1.3fr 1fr;
		}
	}
	.commands {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
		gap: var(--s-2);
	}
	.cmd {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 8px;
		padding: var(--s-3) var(--s-2);
		border-radius: var(--r);
		border: 1px solid var(--border);
		background: var(--surface);
		font-weight: 600;
		font-size: 0.85rem;
		cursor: pointer;
		transition:
			background var(--dur) var(--ease),
			border-color var(--dur) var(--ease);
	}
	.cmd:hover:not(:disabled) {
		background: var(--surface-2);
		border-color: var(--border-strong);
	}
	.cmd:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.cmd-icon {
		width: 42px;
		height: 42px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
	}
	.commands + :global(*),
	.callout + .commands {
		margin-top: var(--s-3);
	}
	.two {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 560px) {
		.two {
			grid-template-columns: 1fr 1fr;
		}
	}
	.facts {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
		gap: var(--s-3);
		margin: 0;
	}
	.facts.with-form {
		margin-top: var(--s-5);
		padding-top: var(--s-4);
		border-top: 1px solid var(--border);
	}
	.facts dt {
		font-size: 0.78rem;
		color: var(--text-faint);
		font-weight: 650;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}
	.facts dd {
		margin: 2px 0 0;
		font-weight: 600;
	}
	.health {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.health li {
		display: flex;
		align-items: center;
		gap: 10px;
		color: var(--text-muted);
	}
	.h-label {
		flex: 1;
		font-size: 0.92rem;
	}
	.h-val {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		color: var(--text);
		font-weight: 600;
		font-size: 0.9rem;
		text-align: right;
	}
	.bar-row {
		padding-left: 28px;
	}
	.bar-row .progress {
		flex: 1;
	}
	.bars {
		display: inline-flex;
		align-items: flex-end;
		gap: 2px;
	}
	.bars span {
		width: 4px;
		border-radius: 2px;
		background: var(--surface-3);
	}
	.bars span.on {
		background: var(--accent);
	}
	.warn-row {
		color: var(--warn) !important;
		font-size: 0.88rem;
	}
	.danger p {
		margin-bottom: var(--s-3);
	}
	.owner-tools {
		display: flex;
		flex-direction: column;
	}
	.tool {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 0;
		flex-wrap: wrap;
	}
	.tool + .tool {
		border-top: 1px solid var(--border);
	}
	.tool-icon {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
	.tool-text {
		flex: 1;
		min-width: 180px;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
</style>
