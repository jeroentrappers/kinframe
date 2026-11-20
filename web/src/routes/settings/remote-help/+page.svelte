<script lang="ts">
	import { api, call, type Schemas } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { endedKey, formatClock, REMOTE_HELP_DEFAULT_HOURS, REMOTE_HELP_HOURS, upsertSession, type RemoteHelpSession } from '$lib/remoteHelp';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import Dialog from '$components/Dialog.svelte';
	import Icon from '$components/Icon.svelte';

	// Operator: remote help on frames (docs/spec/remote-help.md §5.2).
	type Scope = Schemas['OperatorScope'];

	const allowed = $derived(auth.me?.is_operator === true && (auth.me.remote_help === true || discovery.remoteHelp));
	let q = $state('');
	let searching = $state(false);
	let frames = $state<Scope[] | null>(null);
	let error = $state<unknown>(null);

	async function search(e?: SubmitEvent) {
		e?.preventDefault();
		if (q.trim().length < 2) return;
		searching = true;
		error = null;
		try {
			frames = (await call(api.GET('/v1/operator/ownership/search', { params: { query: { q: q.trim() } } }))).frames;
		} catch (err) {
			error = err;
		} finally {
			searching = false;
		}
	}

	// ---- sessions (active first, then the log)
	let sessions = $state<RemoteHelpSession[]>([]);
	let cursor = $state<string | null>(null);
	const active = $derived(sessions.filter((s) => s.state !== 'ended'));
	const past = $derived(sessions.filter((s) => s.state === 'ended'));
	const activeFrames = $derived(new Set(active.map((s) => s.frame_id)));

	async function loadSessions(more = false) {
		try {
			const page = await call(api.GET('/v1/operator/remote-help', { params: { query: { limit: 30, ...(more && cursor ? { cursor } : {}) } } }));
			sessions = more ? [...sessions, ...page.sessions.filter((s) => !sessions.some((x) => x.id === s.id))] : page.sessions;
			cursor = page.next_cursor;
		} catch {
			if (!more) sessions = [];
		}
	}
	$effect(() => {
		if (!allowed) return;
		void loadSessions();
		return live.on((e) => {
			if (e.type === 'remote_help.updated') sessions = upsertSession(sessions, e.session);
		});
	});

	// ---- start
	let target = $state<Scope | null>(null);
	let open = $state(false);
	let hours = $state<number>(REMOTE_HELP_DEFAULT_HOURS);
	let reason = $state('');
	let busy = $state(false);

	function startDialog(s: Scope) {
		target = s;
		hours = REMOTE_HELP_DEFAULT_HOURS;
		reason = '';
		open = true;
	}

	async function start() {
		const s = target;
		if (!s || !reason.trim()) return;
		busy = true;
		try {
			const created = await call(
				api.POST('/v1/operator/frames/{frameId}/remote-help', {
					params: { path: { frameId: s.id } },
					body: { hours: hours as 1 | 8 | 24, reason: reason.trim().slice(0, 500) }
				})
			);
			open = false;
			sessions = upsertSession(sessions, created);
			toasts.success(t('remoteHelp.started', { name: s.name }));
		} catch (err) {
			toasts.error(isCode(err, 'upstream_unavailable') ? t('remoteHelp.upstream') : problemMessage(err));
		} finally {
			busy = false;
		}
	}

	async function stop(s: RemoteHelpSession) {
		try {
			await call(api.DELETE('/v1/operator/frames/{frameId}/remote-help', { params: { path: { frameId: s.frame_id } } }));
			toasts.success(t('remoteHelp.stopped'));
			void loadSessions();
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	async function copy(text: string) {
		try {
			await navigator.clipboard.writeText(text);
			toasts.success(t('remoteHelp.copied'));
		} catch {
			toasts.error(t('remoteHelp.copyFailed'));
		}
	}

	const hourKey = (h: number): MessageKey => (h === 1 ? 'remoteHelp.hour1' : h === 24 ? 'remoteHelp.hour24' : 'remoteHelp.hour8');

	function ended(s: RemoteHelpSession): string {
		const k = endedKey(s);
		return k ? t(k.key, { name: k.name ?? '' }) : '';
	}
</script>

<svelte:head><title>{t('remoteHelp.opLink')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
	<div class="page-header">
		<div>
			<h1>{t('remoteHelp.opLink')}</h1>
			<p class="lede">{t('remoteHelp.opLede')}</p>
		</div>
	</div>

	{#if !allowed}
		<p class="callout"><Icon name="info" size={18} />{t('chat.operatorOnly')}</p>
	{:else}
		<div class="stack">
			<section class="card card-pad stack-sm" aria-labelledby="rh-active">
				<h2 id="rh-active" class="h3">{t('remoteHelp.active')}</h2>
				{#if active.length === 0}
					<p class="muted small">{t('remoteHelp.activeEmpty')}</p>
				{:else}
					<ul class="list" role="list">
						{#each active as s (s.id)}
							<li class="session">
								<div class="row-item">
									<div class="who">
										<strong>{s.frame_name}</strong>
										<span class="muted tiny">
											<span class="badge" class:on={s.state === 'on'}>{t(s.state === 'on' ? 'remoteHelp.stateOn' : 'remoteHelp.statePending')}</span>
											{' · '}{t('remoteHelp.until', { time: formatClock(s.ends_at, i18n.tag) })}
											{' · '}{t('remoteHelp.startedBy', { name: s.started_by_name })}
										</span>
										<span class="muted tiny">“{s.reason}”</span>
									</div>
									<button class="btn btn-sm" onclick={() => stop(s)}><Icon name="x" size={16} />{t('remoteHelp.stop')}</button>
								</div>
								<div class="row-wrap cmd">
									{#if s.tailnet_ip}<span class="tiny">{t('remoteHelp.ip')}: <code>{s.tailnet_ip}</code></span>{/if}
									<code class="adb">{s.adb_command}</code>
									<button class="btn btn-sm btn-ghost" onclick={() => copy(s.adb_command)}><Icon name="copy" size={16} />{t('remoteHelp.copy')}</button>
								</div>
								{#if s.error}<p class="callout callout-error small"><Icon name="alert" size={16} />{s.error}</p>{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>

			<form class="row-wrap" onsubmit={search} role="search">
				<label for="rh-q" class="visually-hidden">{t('remoteHelp.search')}</label>
				<input id="rh-q" class="input grow" bind:value={q} minlength="2" maxlength="200" placeholder={t('remoteHelp.search')} />
				<button class="btn btn-primary" type="submit" disabled={q.trim().length < 2 || searching}><Icon name="search" size={16} />{t('leaving.opSearchButton')}</button>
			</form>
			{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>{/if}
			{#if frames}
				{#if frames.length === 0}
					<p class="muted small">{t('remoteHelp.none')}</p>
				{:else}
					<section class="card card-pad stack-sm">
						<h2 class="h3">{t('leaving.opFrames')}</h2>
						<ul class="list" role="list">
							{#each frames as f (f.id)}
								<li class="row-item">
									<div class="who">
										<strong>{f.name}</strong>
										<span class="muted tiny">
											{f.members
												.slice(0, 6)
												.map((m) => (m.role === 'owner' ? `${m.display_name} (${t('leaving.opOwner')})` : m.display_name))
												.join(', ')}{#if f.members.length > 6} …{/if}
										</span>
									</div>
									{#if f.remote_help_supported}
										<button class="btn btn-sm" onclick={() => startDialog(f)} disabled={activeFrames.has(f.id)}><Icon name="shield" size={16} />{t('remoteHelp.start')}</button>
									{:else}
										<span class="muted tiny">{t('remoteHelp.unsupported')}</span>
									{/if}
								</li>
							{/each}
						</ul>
					</section>
				{/if}
			{/if}

			<section class="card card-pad stack-sm" aria-labelledby="rh-log">
				<h2 id="rh-log" class="h3">{t('remoteHelp.log')}</h2>
				{#if past.length === 0}
					<p class="muted small">{t('remoteHelp.logEmpty')}</p>
				{:else}
					<ul class="list" role="list">
						{#each past as s (s.id)}
							<li class="log">
								<span><strong>{s.frame_name}</strong>{' · '}{ended(s)}</span>
								<span class="muted tiny">
									{formatDateTime(s.started_at, i18n.tag)}{#if s.ended_at}{' – '}{formatDateTime(s.ended_at, i18n.tag)}{/if}{' · '}{t('remoteHelp.startedBy', {
										name: s.started_by_name
									})}{' · '}“{s.reason}”
								</span>
								{#if s.error}<span class="muted tiny err">{s.error}</span>{/if}
							</li>
						{/each}
					</ul>
				{/if}
				{#if cursor}<button class="btn btn-sm btn-ghost align-start" onclick={() => loadSessions(true)}>{t('common.loadMore')}</button>{/if}
			</section>
		</div>
	{/if}
</div>

<Dialog bind:open title={t('remoteHelp.startTitle', { name: target?.name ?? '' })}>
	<div class="stack">
		<fieldset class="field">
			<legend>{t('remoteHelp.hours')}</legend>
			<div class="row-wrap">
				{#each REMOTE_HELP_HOURS as h (h)}
					<label class="radio"><input type="radio" name="rh-hours" value={h} bind:group={hours} />{t(hourKey(h))}</label>
				{/each}
			</div>
		</fieldset>
		<div class="field">
			<label for="rh-reason">{t('remoteHelp.reason')}</label>
			<textarea id="rh-reason" class="input" rows="2" maxlength="500" required bind:value={reason} placeholder={t('remoteHelp.reasonHint')}></textarea>
		</div>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={start} disabled={busy || !reason.trim()}>{t('remoteHelp.start')}</button>
	{/snippet}
</Dialog>

<style>
	.narrow {
		max-width: 760px;
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.grow {
		flex: 1;
		min-width: 200px;
	}
	.h3 {
		font-size: 1.05rem;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list > li + li {
		border-top: 1px solid var(--border);
	}
	.row-item {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 8px 0;
		flex-wrap: wrap;
	}
	.who {
		flex: 1;
		min-width: 180px;
		display: flex;
		flex-direction: column;
		overflow-wrap: anywhere;
	}
	.session {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-bottom: 8px;
	}
	.cmd {
		align-items: center;
		gap: var(--s-2);
	}
	.adb {
		overflow-wrap: anywhere;
		font-size: 0.85rem;
	}
	.badge {
		font-weight: 650;
	}
	.badge.on {
		color: var(--success, var(--primary));
	}
	.log {
		display: flex;
		flex-direction: column;
		padding: 8px 0;
		overflow-wrap: anywhere;
	}
	.err {
		color: var(--danger, inherit);
	}
	fieldset {
		border: 0;
		padding: 0;
		margin: 0;
	}
	.radio {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-inline-end: var(--s-4);
	}
	.align-start {
		align-self: flex-start;
	}
</style>
