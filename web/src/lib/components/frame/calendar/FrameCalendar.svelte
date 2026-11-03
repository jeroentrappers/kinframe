<script lang="ts">
	import { api, call, type CalendarSource, type CalendarView, type EventInvitation, type Frame, type FrameSettings } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { colorVars, type FirstDay } from '$lib/calendar';
	import { discovery } from '$lib/discovery.svelte';
	import { relativeTime } from '$lib/format';
	import { can, canManage } from '$lib/frames.svelte';
	import { getFrameSettings } from '$lib/frameSettings';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';
	import CalendarPreview from './CalendarPreview.svelte';
	import CalendarSettings from './CalendarSettings.svelte';
	import CalendarSourceDialog from './CalendarSourceDialog.svelte';
	import SendEventDialog from './SendEventDialog.svelte';

	let { frame }: { frame: Frame } = $props();

	const manage = $derived(can(frame, 'change_settings'));
	const mayPut = $derived(can(frame, 'send'));
	const enabled = $derived(discovery.doc?.calendar !== false);

	let sources = $state<CalendarSource[] | null>(null);
	let view = $state<CalendarView | null>(null);
	let settings = $state<FrameSettings | null>(null);
	let invitations = $state<EventInvitation[] | null>(null);
	let error = $state<unknown>(null);

	async function loadSources() {
		sources = await call(api.GET('/v1/frames/{frameId}/calendars', { params: { path: { frameId: frame.id } } }));
	}
	async function loadView() {
		view = await call(api.GET('/v1/frames/{frameId}/calendar', { params: { path: { frameId: frame.id } } }));
	}
	async function loadInvitations() {
		invitations = await call(api.GET('/v1/frames/{frameId}/events', { params: { path: { frameId: frame.id } } }));
	}

	async function load() {
		error = null;
		try {
			const jobs: Promise<unknown>[] = [];
			if (manage) jobs.push(loadSources(), loadView(), getFrameSettings(frame.id).then((s) => (settings = s)));
			if (mayPut || manage) jobs.push(loadInvitations());
			await Promise.all(jobs);
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void frame.id;
		if (!enabled) return;
		sources = view = settings = invitations = null;
		void load();
		return live.on((e) => {
			if (e.type !== 'event_invitation.decided' || e.frame_id !== frame.id) return;
			const inv = e.invitation;
			if (invitations) {
				const i = invitations.findIndex((x) => x.id === inv.id);
				if (i >= 0) invitations[i] = inv;
				else invitations = [inv, ...invitations];
			}
			if (inv.status === 'accepted' || inv.status === 'declined') {
				toasts.show(t(inv.status === 'accepted' ? 'cal.decidedAccepted' : 'cal.decidedDeclined', { frame: frame.name, title: inv.title }));
			}
			if (manage && inv.status === 'accepted') void refresh();
		});
	});

	// While something syncs, check back every few seconds (the server finishes within a minute).
	const syncing = $derived((sources ?? []).some((s) => s.status === 'syncing'));
	$effect(() => {
		if (!syncing) return;
		let tries = 0;
		const timer = setInterval(async () => {
			if (++tries > 20) return clearInterval(timer);
			try {
				await loadSources();
				if (!(sources ?? []).some((s) => s.status === 'syncing')) await loadView();
			} catch {
				/* next tick */
			}
		}, 3000);
		return () => clearInterval(timer);
	});

	async function refresh() {
		try {
			await Promise.all([loadSources(), loadView()]);
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- sources
	let dialogOpen = $state(false);
	let editing = $state<CalendarSource | null>(null);
	const usedColors = $derived((sources ?? []).map((s) => s.color));
	const label = (s: CalendarSource) => (s.kind === 'local' && !s.name ? t('cal.received') : s.name);

	function openAdd() {
		editing = null;
		dialogOpen = true;
	}
	function openEdit(s: CalendarSource) {
		editing = s;
		dialogOpen = true;
	}
	function onSaved(saved: CalendarSource[]) {
		const list = [...(sources ?? [])];
		for (const s of saved) {
			const i = list.findIndex((x) => x.id === s.id);
			if (i >= 0) list[i] = s;
			else list.push(s);
		}
		sources = list;
		if (!editing) toasts.success(tn('cal.added', saved.length));
		else toasts.success(t('cal.saved'));
		void loadView().catch(() => {});
	}

	async function syncNow(s: CalendarSource) {
		try {
			const r = await call(api.POST('/v1/frames/{frameId}/calendars/{calendarId}/sync', { params: { path: { frameId: frame.id, calendarId: s.id } } }));
			sources = (sources ?? []).map((x) => (x.id === s.id ? r : x));
			toasts.show(t('cal.syncStarted', { name: label(s) }));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function setEnabled(s: CalendarSource, on: boolean) {
		try {
			const r = await call(api.PATCH('/v1/frames/{frameId}/calendars/{calendarId}', { params: { path: { frameId: frame.id, calendarId: s.id } }, body: { enabled: on } }));
			sources = (sources ?? []).map((x) => (x.id === s.id ? r : x));
			void loadView().catch(() => {});
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	let removing = $state<CalendarSource | null>(null);
	let removeOpen = $state(false);
	let removeBusy = $state(false);
	async function confirmRemove() {
		const s = removing;
		if (!s) return;
		removeBusy = true;
		try {
			await call(api.DELETE('/v1/frames/{frameId}/calendars/{calendarId}', { params: { path: { frameId: frame.id, calendarId: s.id } } }));
			sources = (sources ?? []).filter((x) => x.id !== s.id);
			removeOpen = false;
			toasts.success(t('cal.removed', { name: label(s) }));
			void loadView().catch(() => {});
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			removeBusy = false;
		}
	}

	function statusText(s: CalendarSource): string {
		if (s.status === 'ok' || (s.status === 'disabled' && s.last_synced_at)) {
			return s.last_synced_at ? t('cal.lastSynced', { when: relativeTime(s.last_synced_at, i18n.tag) }) : t('cal.neverSynced');
		}
		return t(`cal.status.${s.status}` as MessageKey);
	}

	// ---- sent events
	let sendOpen = $state(false);
	function onSent(inv: EventInvitation) {
		invitations = [inv, ...(invitations ?? [])];
		toasts.success(t('cal.evSent', { frame: frame.name }));
	}

	async function withdraw(inv: EventInvitation) {
		try {
			await call(api.DELETE('/v1/frames/{frameId}/events/{invitationId}', { params: { path: { frameId: frame.id, invitationId: inv.id } } }));
			invitations = (invitations ?? []).map((x) => (x.id === inv.id ? { ...x, status: 'withdrawn' as const } : x));
			toasts.success(t('cal.withdrawn'));
		} catch (e) {
			toasts.error(problemMessage(e));
			void loadInvitations().catch(() => {});
		}
	}

	function invWhen(inv: EventInvitation): string {
		const day = (d: Date) => new Intl.DateTimeFormat(i18n.tag, { weekday: 'short', day: 'numeric', month: 'short' }).format(d);
		if (inv.all_day && inv.start_date) {
			const s = day(new Date(`${inv.start_date}T12:00`));
			return inv.end_date && inv.end_date !== inv.start_date ? `${s} – ${day(new Date(`${inv.end_date}T12:00`))}` : `${s} · ${t('cal.allDay')}`;
		}
		if (!inv.start_at) return '';
		const s = new Date(inv.start_at);
		return `${day(s)} · ${new Intl.DateTimeFormat(i18n.tag, { hour: '2-digit', minute: '2-digit' }).format(s)}`;
	}

	const mine = (inv: EventInvitation) => inv.sender_id === auth.me?.id;
	const firstDay = $derived((settings?.locale?.first_day_of_week ?? 'mon') as FirstDay);
</script>

{#if !enabled}
	<p class="callout"><Icon name="info" size={18} />{t('cal.serverOff')}</p>
{:else if error}
	<div class="callout callout-error" role="alert">
		<Icon name="alert" />
		<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
	</div>
{:else}
	<div class="cal-layout" class:solo={!manage}>
		{#if manage}
			<section class="card card-pad stack preview-card" aria-labelledby="cal-prev-title">
				<h2 id="cal-prev-title">{t('cal.previewTitle')}</h2>
				{#if view}
					<CalendarPreview
						frameId={frame.id}
						{view}
						{firstDay}
						weekNumbers={settings?.calendar?.week_numbers ?? false}
						fadePast={settings?.calendar?.fade_past ?? true}
						unit={settings?.locale?.temperature_unit ?? 'c'}
						clock24={settings?.widgets?.clock_24h ?? true}
						{manage}
						onchange={() => void loadView().catch(() => {})}
					/>
				{:else}
					<div class="skeleton" style="height:320px" aria-busy="true"></div>
				{/if}
			</section>
		{/if}

		<div class="side stack">
			{#if manage}
				<section class="card card-pad stack" aria-labelledby="cal-src-title">
					<div class="section-title">
						<h2 id="cal-src-title">{t('cal.sourcesTitle')}</h2>
						<button class="btn btn-primary btn-sm" onclick={openAdd}><Icon name="plus" size={16} />{t('cal.add')}</button>
					</div>
					<p class="hint">{t('cal.sourcesHint')}</p>
					{#if !sources}
						<div class="skeleton" style="height:120px" aria-busy="true"></div>
					{:else if sources.length === 0}
						<p class="empty muted small">{t('cal.none')}</p>
					{:else}
						<ul class="sources">
							{#each sources as s (s.id)}
								<li class="src cal-color" class:off={!s.enabled} style={colorVars(s.color)}>
									<span class="swatch" aria-hidden="true">{#if s.kind === 'local'}<Icon name="heart" size={14} />{/if}</span>
									<div class="src-main">
										<strong class="src-name">{label(s)}</strong>
										<span class="tiny muted src-sub">
											{s.kind === 'local' ? t('cal.receivedHint') : s.kind === 'caldav' ? `${t('cal.kind.caldav')} · ${s.username ?? ''}` : (s.url_hint ?? t('cal.kind.ics'))}
										</span>
										<span class="status tiny st-{s.status}">
											{#if s.status === 'syncing'}<span class="spinner mini" aria-hidden="true"></span>{:else if s.status === 'failed' || s.status === 'auth_failed'}<Icon name="alert" size={13} />{:else if s.status === 'ok'}<Icon name="check" size={13} />{/if}
											<span>{statusText(s)}</span>
											{#if s.status === 'ok' && s.event_count != null}<span class="muted nowrap">· {tn('cal.events', s.event_count)}</span>{/if}
										</span>
										{#if (s.status === 'failed' || s.status === 'auth_failed') && (s.last_error || s.status === 'auth_failed')}
											<span class="tiny err">{s.status === 'auth_failed' ? t('cal.authHelp') : s.last_error}</span>
										{/if}
									</div>
									<label class="switch compact" title={t('cal.show')}>
										<input type="checkbox" checked={s.enabled} onchange={(e) => setEnabled(s, (e.currentTarget as HTMLInputElement).checked)} />
										<span class="visually-hidden">{t('cal.show')}: {label(s)}</span>
									</label>
									<div class="src-actions">
										{#if s.kind !== 'local'}
											<button class="btn btn-ghost btn-icon btn-sm" aria-label="{t('cal.syncNow')}: {label(s)}" title={t('cal.syncNow')} disabled={s.status === 'syncing' || !s.enabled} onclick={() => syncNow(s)}>
												<Icon name="refresh" size={16} />
											</button>
										{/if}
										<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('cal.edit', { name: label(s) })} title={t('common.edit')} onclick={() => openEdit(s)}>
											<Icon name="edit" size={16} />
										</button>
										<button
											class="btn btn-danger-ghost btn-icon btn-sm"
											aria-label="{t('cal.remove')}: {label(s)}"
											title={t('cal.remove')}
											onclick={() => {
												removing = s;
												removeOpen = true;
											}}
										>
											<Icon name="trash" size={16} />
										</button>
									</div>
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			{/if}

			{#if mayPut || manage}
				<section class="card card-pad stack" aria-labelledby="cal-send-title">
					<div class="section-title">
						<h2 id="cal-send-title">{t('cal.sentTitle')}</h2>
						{#if mayPut}<button class="btn btn-sm" onclick={() => (sendOpen = true)}><Icon name="send" size={16} />{t('cal.sendButton')}</button>{/if}
					</div>
					<p class="hint">{t('cal.sendHint')}</p>
					{#if !invitations}
						<div class="skeleton" style="height:60px" aria-busy="true"></div>
					{:else if invitations.length === 0}
						<p class="empty muted small">{t('cal.sentNone')}</p>
					{:else}
						<ul class="invs">
							{#each invitations as inv (inv.id)}
								<li class="inv">
									<div class="inv-main">
										<strong>{inv.title}</strong>
										<span class="tiny muted">{invWhen(inv)}{#if !mine(inv)} · {t('cal.sentBy', { name: inv.sender_name })}{/if}</span>
									</div>
									<span class="badge inv-{inv.status}">{t(`cal.inv.${inv.status}` as MessageKey)}</span>
									{#if inv.status === 'pending' && (mine(inv) || canManage(frame))}
										<button class="btn btn-ghost btn-sm" onclick={() => withdraw(inv)}>{t('cal.withdraw')}</button>
									{/if}
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			{/if}

			{#if manage && settings}
				<section class="card card-pad stack" aria-labelledby="cal-set-title">
					<h2 id="cal-set-title">{t('cal.settingsTitle')}</h2>
					<CalendarSettings frameId={frame.id} {settings} onsaved={(s) => {
						settings = s;
						void loadView().catch(() => {});
					}} />
				</section>
			{/if}
		</div>
	</div>

	{#if manage}
		<CalendarSourceDialog bind:open={dialogOpen} frameId={frame.id} source={editing} {usedColors} onsaved={onSaved} />
		<Dialog bind:open={removeOpen} title={removing ? t('cal.removeTitle', { name: label(removing) }) : ''}>
			<p>{removing?.kind === 'local' ? t('cal.removeReceivedBody') : t('cal.removeBody')}</p>
			{#snippet footer()}
				<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
				<button class="btn btn-danger" disabled={removeBusy} onclick={confirmRemove}>
					{#if removeBusy}<span class="spinner" aria-hidden="true"></span>{/if}{t('cal.remove')}
				</button>
			{/snippet}
		</Dialog>
	{/if}
	{#if mayPut}
		<SendEventDialog bind:open={sendOpen} frameId={frame.id} frameName={frame.name} senderName={auth.me?.display_name ?? ''} onsent={onSent} />
	{/if}
{/if}

<style>
	.cal-layout {
		display: grid;
		gap: var(--s-4);
		align-items: start;
	}
	@media (min-width: 1000px) {
		.cal-layout:not(.solo) {
			grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
		}
	}
	.cal-layout.solo {
		max-width: 640px;
	}
	.preview-card,
	.side {
		min-width: 0;
	}
	@media (max-width: 999px) {
		.preview-card {
			order: 2;
		}
	}
	h2 {
		font-size: 1.15rem;
	}
	.section-title {
		margin: 0;
	}
	.hint {
		margin: 0;
	}
	.empty {
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface-2);
		text-align: center;
		margin: 0;
	}
	.sources,
	.invs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.src {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		grid-template-areas: 'sw main toggle' '. actions actions';
		column-gap: 10px;
		row-gap: 2px;
		align-items: start;
		padding: 10px 10px 6px;
		border-radius: var(--r);
		border: 1px solid var(--border);
		border-inline-start: 4px solid var(--ev);
		background: color-mix(in srgb, var(--ev) 7%, var(--surface));
		transition: opacity var(--dur) var(--ease);
	}
	.src.off .swatch,
	.src.off .src-main {
		opacity: 0.55;
	}
	.swatch {
		grid-area: sw;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		background: var(--ev);
		display: grid;
		place-items: center;
		color: #fff;
		margin-top: 1px;
	}
	.src-main {
		grid-area: main;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.src-name {
		overflow-wrap: anywhere;
	}
	.src-sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
	}
	.status :global(svg),
	.status .spinner {
		flex: none;
	}
	.st-ok {
		color: var(--accent-text);
	}
	.st-failed,
	.st-auth_failed,
	.err {
		color: var(--danger);
	}
	.spinner.mini {
		width: 11px;
		height: 11px;
		border-width: 2px;
	}
	.switch.compact {
		grid-area: toggle;
		min-height: 0;
	}
	.src-actions {
		grid-area: actions;
		display: flex;
		align-items: center;
		justify-content: flex-end;
		gap: 2px;
		margin-inline-end: -4px;
	}
	.nowrap {
		white-space: nowrap;
	}
	.inv {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 10px 12px;
		border-radius: var(--r);
		background: var(--surface-2);
		flex-wrap: wrap;
	}
	.inv-main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.inv-main strong {
		overflow-wrap: anywhere;
	}
	.inv-accepted {
		background: var(--accent-soft);
		color: var(--accent-text);
		border-color: transparent;
	}
	.inv-declined {
		background: var(--danger-soft);
		color: var(--danger);
		border-color: transparent;
	}
	.inv-pending {
		background: var(--warn-soft);
		color: var(--warn);
		border-color: transparent;
	}
</style>
